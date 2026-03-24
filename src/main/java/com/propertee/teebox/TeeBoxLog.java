package com.propertee.teebox;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Minimal structured logging to stderr.
 * Format: [timestamp] [LEVEL] [component] message
 */
public class TeeBoxLog {
    private static final String FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    public static void info(String component, String message) {
        log("INFO", component, message);
    }

    public static void warn(String component, String message) {
        log("WARN", component, message);
    }

    public static void warn(String component, String message, Throwable t) {
        log("WARN", component, message + " -- " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    public static void error(String component, String message) {
        log("ERROR", component, message);
    }

    public static void error(String component, String message, Throwable t) {
        log("ERROR", component, message + " -- " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static void log(String level, String component, String message) {
        String ts = new SimpleDateFormat(FORMAT, Locale.ENGLISH).format(new Date());
        System.err.println("[" + ts + "] [" + level + "] [" + component + "] " + message);
    }
}
