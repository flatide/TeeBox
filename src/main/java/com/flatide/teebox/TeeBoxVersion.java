package com.flatide.teebox;

import java.io.InputStream;
import java.util.Properties;

/**
 * The TeeBox build version, baked into {@code teebox-version.properties} by Gradle's
 * {@code processResources} (from the project {@code version}). Read from the classpath, so it resolves
 * the same whether running from the fat jar, {@code gradle run}, or tests. Falls back to
 * {@code "unknown"} if the resource is missing or the token was never expanded (e.g. running from raw
 * source without the Gradle resource step).
 */
public final class TeeBoxVersion {

    private static final String VERSION = load();

    private TeeBoxVersion() {
    }

    /** The build version (e.g. {@code "1.2.0"}), or {@code "unknown"} if it could not be determined. */
    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = TeeBoxVersion.class.getResourceAsStream("/teebox-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.trim().isEmpty() && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (Exception ignore) {
            // fall through to unknown
        }
        return "unknown";
    }
}
