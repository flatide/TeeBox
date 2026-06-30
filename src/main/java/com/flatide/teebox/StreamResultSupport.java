package com.flatide.teebox;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Support for "stream results": instead of materializing a large payload in the script engine and
 * the HTTP response, a script returns a small descriptor (built by the host {@code STREAM_FILE}
 * builtin) that references a file. TeeBox streams that file directly to the client response,
 * bypassing JSON parse/serialize and the per-layer deep copies.
 *
 * <p>The descriptor is a plain ProperTee object (a {@code Map}) marked with {@link #MARKER}:
 * {@code {"__teebox_stream__": true, "path": <canonical>, "contentType": ..., "size": ...}}.
 *
 * <p>Security: the referenced path must canonicalize within one of the configured allowed roots
 * (default: the data directory). This is enforced both when the descriptor is created
 * ({@link #describe}) and again before streaming ({@link #resolveForStreaming}) to avoid TOCTOU.
 */
public final class StreamResultSupport {

    public static final String MARKER = "__teebox_stream__";

    private final List<File> allowedRoots;

    public StreamResultSupport(List<File> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }

    /** Build a validated stream descriptor for {@code path}, or throw if it is not allowed/found. */
    public Map<String, Object> describe(String path, String contentType) {
        File file = canonicalWithinRoots(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("STREAM_FILE(): not a readable file: " + path);
        }
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put(MARKER, Boolean.TRUE);
        descriptor.put("path", file.getAbsolutePath());
        descriptor.put("contentType", sanitizeContentType(contentType, file.getName()));
        descriptor.put("size", Long.valueOf(file.length()));
        return descriptor;
    }

    /**
     * Validate that {@code path} canonicalizes within one of the allowed roots and return the canonical
     * {@link File}; {@code builtinName} prefixes error messages. If {@code mustExist}, also require a
     * readable regular file (use for inputs); pass {@code false} for an output path that may not exist
     * yet (its canonical location must still be inside a root). Shared by file builtins (e.g. THUMBNAIL)
     * so they all honor the same filesystem boundary as STREAM_FILE.
     */
    public File requireWithinRoots(String path, String builtinName, boolean mustExist) {
        if (path == null || path.trim().length() == 0) {
            throw new IllegalArgumentException(builtinName + "(): path is required");
        }
        File canon;
        try {
            canon = new File(path).getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException(builtinName + "(): cannot resolve path: " + path);
        }
        String canonPath = canon.getPath();
        boolean within = false;
        for (File root : allowedRoots) {
            try {
                String rootPath = root.getCanonicalFile().getPath();
                if (canonPath.equals(rootPath) || canonPath.startsWith(rootPath + File.separator)) {
                    within = true;
                    break;
                }
            } catch (IOException ignore) {
                // skip an unresolvable root
            }
        }
        if (!within) {
            throw new IllegalArgumentException(builtinName + "(): path is outside the allowed roots: " + path);
        }
        if (mustExist && (!canon.exists() || !canon.isFile())) {
            throw new IllegalArgumentException(builtinName + "(): not a readable file: " + path);
        }
        return canon;
    }

    /**
     * Validate a caller-supplied content type before it becomes an HTTP header value. Rejects empty,
     * CR/LF/control-char (header injection), and non-MIME strings — falling back to the extension
     * guess (and ultimately {@code application/octet-stream}).
     */
    public static String sanitizeContentType(String contentType, String fileName) {
        if (contentType == null) {
            return guessContentType(fileName);
        }
        String ct = contentType.trim();
        if (ct.length() == 0 || ct.length() > 200) {
            return guessContentType(fileName);
        }
        // type/subtype with optional ;params, token chars only — no CR/LF/controls/whitespace tricks.
        if (!ct.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*(\\s*;\\s*[^\\x00-\\x1F]+)?")) {
            return guessContentType(fileName);
        }
        return ct;
    }

    /** Redacted one-line summary for a stream descriptor (no server path), e.g. for resultSummary. */
    public static String summaryFor(Object descriptor) {
        if (!isStreamDescriptor(descriptor)) {
            return null;
        }
        Map<?, ?> d = (Map<?, ?>) descriptor;
        return "STREAM_FILE(" + d.get("contentType") + ", " + d.get("size") + " bytes)";
    }

    /** True if {@code value} is a stream descriptor produced by {@link #describe}. */
    public static boolean isStreamDescriptor(Object value) {
        return value instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) value).get(MARKER));
    }

    /** Re-validate a descriptor's path against the allowed roots and return the file to stream. */
    public File resolveForStreaming(Object descriptor) {
        if (!isStreamDescriptor(descriptor)) {
            return null;
        }
        Object path = ((Map<?, ?>) descriptor).get("path");
        if (!(path instanceof String)) {
            return null;
        }
        File file = canonicalWithinRoots((String) path);
        return (file.exists() && file.isFile()) ? file : null;
    }

    /** Client-facing view of a descriptor: drops the absolute server path. */
    public static Map<String, Object> redactForClient(Object descriptor) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stream", Boolean.TRUE);
        if (descriptor instanceof Map) {
            Map<?, ?> d = (Map<?, ?>) descriptor;
            out.put("contentType", d.get("contentType"));
            out.put("size", d.get("size"));
        }
        return out;
    }

    private File canonicalWithinRoots(String path) {
        if (path == null || path.trim().length() == 0) {
            throw new IllegalArgumentException("STREAM_FILE(): path is required");
        }
        try {
            File canon = new File(path).getCanonicalFile();
            String canonPath = canon.getPath();
            for (File root : allowedRoots) {
                String rootPath = root.getCanonicalFile().getPath();
                if (canonPath.equals(rootPath)
                        || canonPath.startsWith(rootPath + File.separator)) {
                    return canon;
                }
            }
            throw new IllegalArgumentException(
                "STREAM_FILE(): path is outside the allowed stream roots: " + path);
        } catch (IOException e) {
            throw new IllegalArgumentException("STREAM_FILE(): cannot resolve path: " + path);
        }
    }

    private static String guessContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }
}
