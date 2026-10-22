package com.e2eq.framework.rest.resources;

import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Streams a long-running migration task to a server-sent-events client.
 *
 * <p>Each message from the task is sent as an event. On failure an {@code Error: <message>} event
 * is sent (deploy tooling treats it as a failed migration), and on success {@code Task completed};
 * either one closes the stream. While the task runs, an SSE comment is sent at a fixed interval so
 * idle-timeout proxies such as a load balancer keep the connection open during long silent steps
 * (for example a large index build), and so streamed client logs show progress.</p>
 */
final class MigrationEventStream {

    /** Comment text sent as a heartbeat while a task runs. */
    static final String HEARTBEAT_COMMENT = "migration running";

    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "migration-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** Where stream output goes; decoupled from JAX-RS so behavior can be tested directly. */
    interface Sink {
        void event(String data);

        void comment(String comment);

        void close();

        boolean isClosed();

        static Sink of(SseEventSink eventSink, Sse sse) {
            return new Sink() {
                @Override
                public void event(String data) {
                    eventSink.send(sse.newEvent(data));
                }

                @Override
                public void comment(String comment) {
                    eventSink.send(sse.newEventBuilder().comment(comment).build());
                }

                @Override
                public void close() {
                    eventSink.close();
                }

                @Override
                public boolean isClosed() {
                    return eventSink.isClosed();
                }
            };
        }
    }

    private MigrationEventStream() {
    }

    /**
     * Runs the task on the given executor and relays its output to the sink, in order.
     *
     * @param task the task's message stream
     * @param executor executor the task runs on
     * @param sink destination for events
     * @param heartbeatInterval interval between heartbeat comments; zero or negative disables them
     */
    static void stream(Multi<String> task, Executor executor, Sink sink, Duration heartbeatInterval) {
        Object sendLock = new Object();
        boolean[] finished = {false};
        ScheduledFuture<?> heartbeat = null;
        if (heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative()) {
            long millis = heartbeatInterval.toMillis();
            heartbeat = HEARTBEATS.scheduleAtFixedRate(() -> {
                synchronized (sendLock) {
                    if (!finished[0] && !sink.isClosed()) {
                        sink.comment(HEARTBEAT_COMMENT);
                    }
                }
            }, millis, millis, TimeUnit.MILLISECONDS);
        }
        ScheduledFuture<?> heartbeatTask = heartbeat;
        Runnable stopHeartbeat = () -> {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
            }
        };

        // Run the task on the executor and deliver its signals in order on that thread. emitOn
        // would re-dispatch each signal and lets a failure discard messages still queued before it.
        task.runSubscriptionOn(executor).subscribe().with(
                message -> {
                    synchronized (sendLock) {
                        if (!finished[0] && !sink.isClosed()) {
                            sink.event(message);
                        }
                    }
                },
                failure -> {
                    stopHeartbeat.run();
                    finish(sendLock, finished, sink, "Error: " + failure.getMessage());
                },
                () -> {
                    stopHeartbeat.run();
                    finish(sendLock, finished, sink, "Task completed");
                });
    }

    private static void finish(Object sendLock, boolean[] finished, Sink sink, String lastEvent) {
        synchronized (sendLock) {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            if (!sink.isClosed()) {
                sink.event(lastEvent);
                sink.close();
            }
        }
    }
}
