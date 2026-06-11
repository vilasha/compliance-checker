package org.maria.compliance.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.TaskEvent;
import org.maria.compliance.model.TaskEventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory pub/sub for task lifecycle events. Holds both the SSE emitters
 * currently subscribed to each task and the history of events already published,
 * so a client that connects after some events have fired still receives them
 * (replay-on-late-subscribe)
 *
 * <p>History is a Caffeine cache with variable expiry, not a plain map: every
 * COMPLETED event carries the full {@code PolicyAnalysisReport}, so an unbounded map
 * grows by one full report per upload for the lifetime of the JVM. Expiry is
 * state-aware: tasks whose last event is terminal expire after the configured
 * retention; tasks still running get a long safety TTL instead, because a single
 * section can sit between SECTION_STARTED and SECTION_ANALYZED for over an hour
 * (LLM timeout × parse retries) with no events in between — evicting a live task
 * would make StatusController misreport it to late subscribers as a stale upload
 * and close their stream before the real events arrive. Evicting *terminal* history
 * is safe by design: StatusController rehydrates the result from the persisted
 * {@code user_uploads} row when no in-memory history exists
 *
 * <p>Single-instance deployments only. Multi-instance would need a shared broker
 * (Redis pub/sub, Kafka) — out of scope for now
 */
@Service
@Slf4j
public class TaskEventBus {

    // Safety net for tasks that never reach a terminal event (e.g. the async
    // submission was rejected after UPLOADED was published). Generous on purpose:
    // it only has to be longer than any legitimate analysis.
    private static final Duration ACTIVE_TASK_SAFETY_TTL = Duration.ofHours(24);

    private final Cache<String, List<TaskEvent>> history;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TaskEventBus(
            @Value("${compliance.processing.event-history-retention:1h}") Duration historyRetention) {
        this.history = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, List<TaskEvent>>() {
                    @Override
                    public long expireAfterCreate(String taskId, List<TaskEvent> events, long currentTime) {
                        return ttlFor(events, historyRetention);
                    }

                    @Override
                    public long expireAfterUpdate(String taskId, List<TaskEvent> events,
                                                  long currentTime, long currentDuration) {
                        return ttlFor(events, historyRetention);
                    }

                    @Override
                    public long expireAfterRead(String taskId, List<TaskEvent> events,
                                                long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    private long ttlFor(List<TaskEvent> events, Duration historyRetention) {
        boolean terminal = !events.isEmpty() && isTerminal(events.get(events.size() - 1).type());
        return terminal ? historyRetention.toNanos() : ACTIVE_TASK_SAFETY_TTL.toNanos();
    }

    /**
     * Append the event to the task's history and broadcast it to every active emitter
     * subscribed to the task. If the event is terminal, all emitters are completed
     * after delivery and the task's emitter registration is released.
     */
    public void publish(TaskEvent event) {
        String taskId = event.taskId();
        List<TaskEvent> events = history.get(taskId, k -> new CopyOnWriteArrayList<>());
        events.add(event);
        // Mutating the cached list does NOT count as a cache write — without this
        // re-put, the expiry clock would run from the task's *first* event and the
        // terminal-aware Expiry above would never re-evaluate
        history.put(taskId, events);

        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters != null) {
            for (SseEmitter emitter : taskEmitters) {
                deliver(emitter, event);
            }
            if (isTerminal(event.type())) {
                for (SseEmitter emitter : taskEmitters) {
                    safeComplete(emitter);
                }
                emitters.remove(taskId);
            }
        }
    }

    /**
     * Register an emitter for a task. Replays all events already in history before
     * returning, so the caller sees a consistent stream regardless of when it connected
     * If a terminal event was already published, completes the emitter after replay
     */
    public void subscribe(String taskId, SseEmitter emitter) {
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(t -> removeEmitter(taskId, emitter));

        List<TaskEvent> past = history.getIfPresent(taskId);
        if (past == null || past.isEmpty()) {
            return;
        }
        TaskEventType lastType = null;
        for (TaskEvent event : past) {
            deliver(emitter, event);
            lastType = event.type();
        }
        if (lastType != null && isTerminal(lastType)) {
            safeComplete(emitter);
        }
    }

    /**
     * Returns true if any history exists for the task — used by the controller to
     * distinguish "unknown task" (404) from "known task with no events yet"
     */
    public boolean hasHistory(String taskId) {
        List<TaskEvent> past = history.getIfPresent(taskId);
        return past != null && !past.isEmpty();
    }

    /**
     * Read-only view of the recorded events for a task
     */
    public List<TaskEvent> historyFor(String taskId) {
        List<TaskEvent> past = history.getIfPresent(taskId);
        return past == null ? List.of() : Collections.unmodifiableList(past);
    }

    private void deliver(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type().name())
                    .data(event));
        } catch (IOException e) {
            log.debug("Emitter delivery failed (likely disconnected): {}", e.getMessage());
            safeComplete(emitter);
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // emitter may already be completed or in error state
        }
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        emitters.computeIfPresent(taskId, (k, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    private boolean isTerminal(TaskEventType type) {
        return type == TaskEventType.COMPLETED || type == TaskEventType.FAILED;
    }
}