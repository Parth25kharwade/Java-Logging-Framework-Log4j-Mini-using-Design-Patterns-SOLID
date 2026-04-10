package com.logframework.config;

import com.logframework.model.LogLevel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * LoggerConfig centralises all framework configuration.
 *
 * SOLID - Single Responsibility Principle (SRP):
 *   Only responsible for loading and exposing configuration values.
 *
 * SOLID - Dependency Inversion Principle (DIP):
 *   High-level modules depend on this abstraction rather than
 *   hard-coded constants scattered across the codebase.
 *
 * Design: Loads from logger.properties on the classpath;
 * falls back to sensible defaults when the file is absent.
 */
public final class LoggerConfig {

    // ── Default values ────────────────────────────────────────────────────────
    private static final String DEFAULT_LOG_FILE      = "application.log";
    private static final LogLevel DEFAULT_MIN_LEVEL   = LogLevel.DEBUG;
    private static final int DEFAULT_ASYNC_THREADS    = 2;
    private static final boolean DEFAULT_CLOUD_ENABLED = false;
    private static final String DEFAULT_CLOUD_ENDPOINT = "https://mock-log-service.example.com/ingest";

    // ── Resolved values ───────────────────────────────────────────────────────
    private final LogLevel minimumLevel;
    private final String logFilePath;
    private final int asyncThreadPoolSize;
    private final boolean cloudEnabled;
    private final String cloudEndpoint;

    /**
     * Private constructor — forces use of the factory methods.
     */
    private LoggerConfig(Properties props) {
        this.minimumLevel     = parseLevel(props.getProperty("log.level"), DEFAULT_MIN_LEVEL);
        this.logFilePath      = props.getProperty("log.file.path", DEFAULT_LOG_FILE);
        this.asyncThreadPoolSize = parseInt(props.getProperty("log.async.threads"),
                                            DEFAULT_ASYNC_THREADS);
        this.cloudEnabled     = Boolean.parseBoolean(
                                    props.getProperty("log.cloud.enabled",
                                    String.valueOf(DEFAULT_CLOUD_ENABLED)));
        this.cloudEndpoint    = props.getProperty("log.cloud.endpoint", DEFAULT_CLOUD_ENDPOINT);
    }

    /**
     * Loads configuration from logger.properties on the classpath.
     * Falls back to defaults if the file cannot be found.
     */
    public static LoggerConfig load() {
        Properties props = new Properties();
        try (InputStream is = LoggerConfig.class
                .getClassLoader()
                .getResourceAsStream("logger.properties")) {

            if (is != null) {
                props.load(is);
            } else {
                System.out.println("[LogFramework] logger.properties not found — using defaults.");
            }
        } catch (IOException e) {
            System.err.println("[LogFramework] Failed to load logger.properties: " + e.getMessage());
        }
        return new LoggerConfig(props);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public LogLevel getMinimumLevel()      { return minimumLevel; }
    public String   getLogFilePath()       { return logFilePath; }
    public int      getAsyncThreadPoolSize(){ return asyncThreadPoolSize; }
    public boolean  isCloudEnabled()       { return cloudEnabled; }
    public String   getCloudEndpoint()     { return cloudEndpoint; }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static LogLevel parseLevel(String value, LogLevel fallback) {
        // Java 17: enhanced try with Optional pattern
        return Optional.ofNullable(value)
                       .map(v -> {
                           try { return LogLevel.valueOf(v.toUpperCase()); }
                           catch (IllegalArgumentException ex) { return fallback; }
                       })
                       .orElse(fallback);
    }

    private static int parseInt(String value, int fallback) {
        try { return (value != null) ? Integer.parseInt(value) : fallback; }
        catch (NumberFormatException ex) { return fallback; }
    }

    @Override
    public String toString() {
        return "LoggerConfig{level=%s, file='%s', threads=%d, cloud=%b}"
                .formatted(minimumLevel, logFilePath, asyncThreadPoolSize, cloudEnabled);
    }
}
