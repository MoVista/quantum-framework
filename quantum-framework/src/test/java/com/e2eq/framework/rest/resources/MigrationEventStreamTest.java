package com.e2eq.framework.rest.resources;

import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationEventStreamTest {

    /** Records sink output as "event:" / "comment:" lines. */
    static final class RecordingSink implements MigrationEventStream.Sink {
        final List<String> output = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean isClosed;

        @Override
        public void event(String data) {
            output.add("event:" + data);
        }

        @Override
        public void comment(String comment) {
            output.add("comment:" + comment);
        }

        @Override
        public void close() {
            isClosed = true;
            closed.countDown();
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }
    }

    @Test
    void sendsHeartbeatsWhileTaskRunsAndStopsAfterCompletion() throws Exception {
        RecordingSink sink = new RecordingSink();
        Multi<String> slowTask = Multi.createFrom().emitter(emitter -> {
            emitter.emit("started");
            sleep(300);
            emitter.emit("finished");
            emitter.complete();
        });

        MigrationEventStream.stream(slowTask, Executors.newSingleThreadExecutor(), sink, Duration.ofMillis(50));

        assertTrue(sink.closed.await(5, TimeUnit.SECONDS));
        int sizeAtClose = sink.output.size();
        sleep(200);
        assertEquals(sizeAtClose, sink.output.size(), "no heartbeats after the stream closes");

        long heartbeats = sink.output.stream().filter(line -> line.startsWith("comment:")).count();
        assertTrue(heartbeats >= 2, "expected heartbeats during the silent step, got " + sink.output);
        List<String> events = sink.output.stream().filter(line -> line.startsWith("event:")).toList();
        assertEquals(List.of("event:started", "event:finished", "event:Task completed"), events);
        assertEquals("event:Task completed", sink.output.get(sink.output.size() - 1));
    }

    @Test
    void reportsFailureAsErrorEventAndCloses() throws Exception {
        RecordingSink sink = new RecordingSink();
        Multi<String> failingTask = Multi.createFrom().emitter(emitter -> {
            emitter.emit("started");
            emitter.fail(new IllegalStateException("A migration is already running for realm r"));
        });

        MigrationEventStream.stream(failingTask, Executors.newSingleThreadExecutor(), sink, Duration.ZERO);

        assertTrue(sink.closed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("event:started", "event:Error: A migration is already running for realm r"), sink.output);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
