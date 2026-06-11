package org.maria.compliance.service;

import org.junit.jupiter.api.Test;
import org.maria.compliance.model.TaskEvent;
import org.maria.compliance.model.TaskEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventBusTest {

    private final TaskEventBus bus = new TaskEventBus(Duration.ofHours(1));

    @Test
    void publish_storesEventInHistory() {
        String taskId = "task-1";
        TaskEvent event = newEvent(taskId, TaskEventType.UPLOADED, "received");

        bus.publish(event);

        assertThat(bus.historyFor(taskId)).containsExactly(event);
        assertThat(bus.hasHistory(taskId)).isTrue();
    }

    @Test
    void hasHistory_returnsFalseForUnknownTask() {
        assertThat(bus.hasHistory("never-seen")).isFalse();
        assertThat(bus.historyFor("never-seen")).isEmpty();
    }

    @Test
    void subscribe_replaysExistingHistoryToNewEmitter() {
        String taskId = "task-replay";
        bus.publish(newEvent(taskId, TaskEventType.UPLOADED, "1"));
        bus.publish(newEvent(taskId, TaskEventType.EXTRACTING, "2"));

        RecordingEmitter emitter = new RecordingEmitter();
        bus.subscribe(taskId, emitter);

        assertThat(emitter.sendCount.get()).isEqualTo(2);
        assertThat(emitter.completed.get()).isFalse();
    }

    @Test
    void subscribe_completesEmitterIfHistoryAlreadyTerminal() {
        String taskId = "task-already-done";
        bus.publish(newEvent(taskId, TaskEventType.UPLOADED, "1"));
        bus.publish(newEvent(taskId, TaskEventType.COMPLETED, "done"));

        RecordingEmitter emitter = new RecordingEmitter();
        bus.subscribe(taskId, emitter);

        assertThat(emitter.sendCount.get()).isEqualTo(2);
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void publish_broadcastsToAllActiveSubscribers() {
        String taskId = "task-broadcast";
        RecordingEmitter a = new RecordingEmitter();
        RecordingEmitter b = new RecordingEmitter();
        bus.subscribe(taskId, a);
        bus.subscribe(taskId, b);

        bus.publish(newEvent(taskId, TaskEventType.EXTRACTING, "go"));

        assertThat(a.sendCount.get()).isEqualTo(1);
        assertThat(b.sendCount.get()).isEqualTo(1);
        assertThat(a.completed.get()).isFalse();
        assertThat(b.completed.get()).isFalse();
    }

    @Test
    void terminalEvent_completesAllSubscribers() {
        String taskId = "task-terminal";
        RecordingEmitter a = new RecordingEmitter();
        RecordingEmitter b = new RecordingEmitter();
        bus.subscribe(taskId, a);
        bus.subscribe(taskId, b);

        bus.publish(newEvent(taskId, TaskEventType.COMPLETED, "all done"));

        assertThat(a.completed.get()).isTrue();
        assertThat(b.completed.get()).isTrue();
    }

    @Test
    void failedEvent_alsoCompletesSubscribers() {
        String taskId = "task-failed";
        RecordingEmitter a = new RecordingEmitter();
        bus.subscribe(taskId, a);

        bus.publish(newEvent(taskId, TaskEventType.FAILED, "boom"));

        assertThat(a.completed.get()).isTrue();
    }

    @Test
    void eventsAreScopedByTaskId() {
        bus.publish(newEvent("task-A", TaskEventType.UPLOADED, "a"));
        bus.publish(newEvent("task-B", TaskEventType.UPLOADED, "b"));

        RecordingEmitter emitterA = new RecordingEmitter();
        bus.subscribe("task-A", emitterA);

        // emitterA must only see task-A's event — history is scoped per taskId
        assertThat(emitterA.sendCount.get()).isEqualTo(1);
        assertThat(bus.historyFor("task-A")).hasSize(1);
        assertThat(bus.historyFor("task-B")).hasSize(1);
    }

    @Test
    void subscribe_attachesCleanupCallbacks() {
        // Smoke test: subscribe should register completion/timeout/error handlers.
        // We verify by checking that completing the emitter manually doesn't throw
        // (i.e., the bus doesn't hold a stale reference that breaks).
        String taskId = "task-cleanup";
        RecordingEmitter emitter = new RecordingEmitter();
        bus.subscribe(taskId, emitter);
        emitter.complete();

        // Subsequent publish should not throw even though the emitter is completed
        bus.publish(newEvent(taskId, TaskEventType.EXTRACTING, "after-close"));
    }

    private TaskEvent newEvent(String taskId, TaskEventType type, String message) {
        return TaskEvent.builder()
                .taskId(taskId)
                .type(type)
                .timestamp(Instant.now())
                .message(message)
                .build();
    }

    /**
     * Test double counting send() and complete() invocations. Avoids reflecting into
     * SseEventBuilder internals which vary across Spring versions.
     */
    private static class RecordingEmitter extends SseEmitter {
        final AtomicInteger sendCount = new AtomicInteger();
        final AtomicBoolean completed = new AtomicBoolean();

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) {
            sendCount.incrementAndGet();
        }

        @Override
        public synchronized void complete() {
            completed.set(true);
        }
    }
}