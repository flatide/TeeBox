package com.flatide.teebox;

import java.io.InputStream;
import java.util.Properties;

/**
 * The TeeBox build version plus the embedded engine's version/commit, baked into
 * {@code teebox-version.properties} by Gradle's {@code processResources}. The engine fields exist for
 * traceability: the composite build embeds whatever {@code ../propertee2-java} has checked out, so a
 * TeeBox commit alone does not pin the engine — the jar records exactly what it shipped with
 * ({@code engineCommit} carries a {@code -dirty} suffix when the sibling tree had local changes).
 * Read from the classpath, so it resolves the same whether running from the fat jar,
 * {@code gradle run}, or tests. Falls back to {@code "unknown"} if the resource is missing or the
 * token was never expanded (e.g. running from raw source without the Gradle resource step).
 */
public final class TeeBoxVersion {

    private static final Properties PROPS = load();
    private static final String VERSION = read("version");
    private static final String ENGINE_VERSION = read("engineVersion");
    private static final String ENGINE_COMMIT = read("engineCommit");

    private TeeBoxVersion() {
    }

    /** The build version (e.g. {@code "1.2.0"}), or {@code "unknown"} if it could not be determined. */
    public static String get() {
        return VERSION;
    }

    /** The embedded propertee2 engine version (e.g. {@code "0.25.0"}), or {@code "unknown"}. */
    public static String engineVersion() {
        return ENGINE_VERSION;
    }

    /** The embedded engine's git commit (short hash, {@code -dirty} suffixed), or {@code "unknown"}. */
    public static String engineCommit() {
        return ENGINE_COMMIT;
    }

    private static String read(String key) {
        String v = PROPS.getProperty(key);
        if (v != null && !v.trim().isEmpty() && !v.contains("${")) {
            return v.trim();
        }
        return "unknown";
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = TeeBoxVersion.class.getResourceAsStream("/teebox-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignore) {
            // fall through to empty
        }
        return props;
    }
}
