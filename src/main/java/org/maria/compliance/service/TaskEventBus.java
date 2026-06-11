package org.maria.compliance.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
 * <p>History is a Caffeine cache with time-based eviction, not a plain map: every
 * COMPLETED event carries the full {@code PolicyAnalysisReport}, so an unbounded map
 * grows by one full report per upload for the lifetime of the JVM. Eviction is safe
 * by design — StatusController already rehydrates a terminal event from the
 * persisted {@code user_uploads} row when no in-memory history exists
 *
 * <p>Single-instance deployments only. Multi-instance would need a shared broker
 * (Redis pub/sub, Kafka) — out of scope for now
 */
@Service
@Slf4j
public class TaskEventBus {

    private final Cache<String, List<TaskEvent>> history;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TaskEventBus(
            @Value("${compliance.processing.event-history-retention:1h}") Duration historyRetention) {
        this.history = Caffeine.newBuilder()
                .expireAfterWrite(historyRetention)
                .build();
    }

    /**
     * Append the event to the task's history and broadcast it to every active emitter
     * subscribed to the task. If the event is terminal, all emitters are completed
     * after delivery and the task's emitter registration is released.
     */
    public void publish(TaskEvent event) {
        String taskId = event.taskId();
        history.get(taskId, k -> new CopyOnWriteArrayList<>()).add(event);

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