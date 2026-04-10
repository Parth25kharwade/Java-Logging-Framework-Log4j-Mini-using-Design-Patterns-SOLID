package com.logframework.output;

import com.logframework.model.LogRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FileOutput writes log records to a daily-rolling log file.
 *
 * Bridge Pattern — Concrete Implementation.
 *
 * Features:
 *  - Appends to file (does not truncate on restart)
 *  - Daily file rotation  (app-2025-04-09.log, app-2025-04-10.log …)
 *  - ReentrantLock for thread safety (multiple async threads write concurrently)
 *  - Buffered I/O for performance
 *
 * SOLID - SRP: Only responsibility is persisting log lines to disk.
 * SOLID - OCP: Rotation strategy could be extracted into a separate
 *              interface later without changing this class's contract.
 */
public final class FileOutput implements LogOutput {

    private final String basePath;           // e.g. "logs/application.log"
    private final ReentrantLock lock = new ReentrantLock();

    private BufferedWriter writer;
    private LocalDate currentDate;

    /**
     * @param basePath path to the log file (directory must be writable)
     */
    public FileOutput(String basePath) {
        this.basePath = basePath;
        this.currentDate = LocalDate.now();
        this.writer = openWriter(resolvedPath());
    }

    @Override
    public void write(LogRecord record) {
        lock.lock();
        try {
            rotateIfNeeded();
            writer.write(record.formatted());
            writer.newLine();
            writer.flush();       // flush every record for durability
        } catch (IOException e) {
            // Fallback: surface the I/O error to stderr so logs are never silently lost
            System.err.println("[FileOutput] Failed to write log: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String name() {
        return "FileOutput[" + resolvedPath() + "]";
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("[FileOutput] Failed to close writer: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Checks whether midnight has passed since the last write and, if so,
     * closes the current file and opens a new dated file.
     */
    private void rotateIfNeeded() throws IOException {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            writer.close();
            currentDate = today;
            writer = openWriter(resolvedPath());
        }
    }

    /**
     * Builds the actual file path by inserting the date before the extension.
     * "logs/application.log" → "logs/application-2025-04-09.log"
     */
    private String resolvedPath() {
        Path p = Path.of(basePath);
        String fileName = p.getFileName().toString();
        int dot = fileName.lastIndexOf('.');

        String dated = (dot == -1)
                ? fileName + "-" + currentDate
                : fileName.substring(0, dot) + "-" + currentDate + fileName.substring(dot);

        return p.getParent() != null
                ? p.getParent().resolve(dated).toString()
                : dated;
    }

    private static BufferedWriter openWriter(String path) {
        try {
            Path p = Path.of(path);
            // Create parent directories if they don't exist
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            return new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(path, true),   // append = true
                            StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open log file: " + path, e);
        }
    }
}
