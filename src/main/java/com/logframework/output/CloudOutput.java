package com.logframework.output;

import com.logframework.model.LogRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CloudOutput ships log records to an external ingestion endpoint.
 *
 * Bridge Pattern — Concrete Implementation (optional / mock).
 *
 * Real-world production features demonstrated:
 *  1. Micro-batching — accumulates records and flushes every N seconds
 *     (avoids one HTTP call per log line)
 *  2. Bounded in-memory queue — drops records gracefully under back-pressure
 *     rather than blocking the caller
 *  3. Drop counter — surfaces data-loss metrics via droppedCount()
 *  4. Pluggable endpoint — just change the URL in logger.properties
 *
 * NOTE: Network I/O is mocked here so the project runs without external deps.
 *       Replace simulateHttpPost() with a real java.net.http.HttpClient call
 *       in a production environment.
 *
 * SOLID - OCP: Adding batching strategies (size-based, time-based) is possible
 *              without changing the LogOutput contract.
 * SOLID - SRP: Solely responsible for shipping records to the cloud.
 */
public final class CloudOutput implements LogOutput {

    private static final int QUEUE_CAPACITY  = 1_000;
    private static final int BATCH_SIZE      = 50;
    private static final int FLUSH_INTERVAL_SECONDS = 5;

    private final String endpoint;
    private final BlockingQueue<LogRecord> buffer;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong droppedCount = new AtomicLong(0);

    public CloudOutput(String endpoint) {
        this.endpoint  = endpoint;
        this.buffer    = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        // Virtual thread factory (Java 21+) — falls back to platform thread on Java 17
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-log-flusher");
            t.setDaemon(true);   // don't block JVM shutdown
            return t;
        });

        // Schedule periodic flush
        scheduler.scheduleAtFixedRate(
                this::flush,
                FLUSH_INTERVAL_SECONDS,
                FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Override
    public void write(LogRecord record) {
        // Non-blocking offer — drops if queue is full (back-pressure)
        boolean accepted = buffer.offer(record);
        if (!accepted) {
            droppedCount.incrementAndGet();
            System.err.printf("[CloudOutput] Queue full — record dropped. Total dropped: %d%n",
                    droppedCount.get());
        }

        // Eagerly flush if we've accumulated a full batch
        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    @Override
    public String name() {
        return "CloudOutput[" + endpoint + "]";
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            // Give the flusher up to 10 s to ship remaining records
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flush(); // final flush of whatever remains
    }

    /** Returns the total number of records dropped due to back-pressure. */
    public long droppedCount() {
        return droppedCount.get();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void flush() {
        List<LogRecord> batch = new ArrayList<>(BATCH_SIZE);
        buffer.drainTo(batch, BATCH_SIZE);

        if (batch.isEmpty()) return;

        // Java 17: streams to build the JSON payload
        String payload = buildJsonPayload(batch);
        simulateHttpPost(payload);
    }

    private String buildJsonPayload(List<LogRecord> batch) {
        // Java 17: Streams + String.join for concise JSON construction
        // (No external JSON library dependency — keeps the demo self-contained)
        var entries = batch.stream()
                .map(r -> """
                        {"level":"%s","timestamp":"%s","thread":"%s","message":"%s"}"""
                        .formatted(
                                r.level(),
                                r.timestamp(),
                                r.threadName(),
                                escapeJson(r.message())
                        ))
                .toList(); // Java 17: List.copyOf via .toList()

        return "{\"logs\":[" + String.join(",", entries) + "]}";
    }

    /**
     * Mock HTTP POST — replace with java.net.http.HttpClient for production.
     *
     * java.net.http.HttpClient example (Java 11+):
     *   HttpClient.newHttpClient().send(
     *       HttpRequest.newBuilder(URI.create(endpoint))
     *           .POST(HttpRequest.BodyPublishers.ofString(payload))
     *           .header("Content-Type", "application/json")
     *           .build(),
     *       HttpResponse.BodyHandlers.discarding());
     */
    private void simulateHttpPost(String payload) {
        System.out.printf("[CloudOutput] → POST %s  (%d chars, simulated OK)%n",
                endpoint, payload.length());
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
