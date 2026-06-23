package com.flatide.teebox.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embeddable, zero-dependency client for a ProperTee TeeBox server.
 *
 * <p>Single self-contained source file: copy it into any project (the {@code com/flatide/teebox/client/}
 * package path) and call it. Requires only the JDK ({@link HttpURLConnection} + a tiny built-in JSON
 * parser/writer) and is held to <b>Java 7 source level</b> (no lambdas/streams/{@code java.time}) so it
 * embeds into legacy hosts. No Gson/Jackson, no TeeBox server classes. Modern JDKs can no longer emit
 * bytecode 7 ({@code javac --release 7} was dropped in JDK 20), so the build verifies it at the
 * {@code --release 8} floor (Gradle task {@code compileStandaloneClient}); keep it Java-7-clean by hand.
 *
 * <p>Scope: register/update scripts, run them, and track runs. Designed for trusted internal networks,
 * so there is no auth by default. If tokens are configured, set a shared one via
 * {@link #setBearerToken(String)}, or per-namespace ones via {@link #setClientApiToken}/
 * {@link #setPublisherApiToken} when the operator splits {@code clientApiToken}/{@code publisherApiToken}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");
 *
 * // register + activate a script version
 * teebox.registerScript("calc_sum", "v1", "return {\"sum\": a + b}\n", true);
 *
 * // later: publish a new version and make it active (= "update")
 * teebox.addScriptVersion("calc_sum", "v2", "return {\"sum\": a + b, \"v\": 2}\n", true);
 *
 * // run it and block until it finishes (best for short / immediate scripts)
 * Map<String, Object> props = new LinkedHashMap<String, Object>();
 * props.put("a", 40); props.put("b", 2);
 * Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);
 * Object sum = ((Map<?, ?>) result.get("resultData")).get("sum"); // 42.0
 *
 * // or fire-and-track yourself
 * String runId = (String) teebox.submitRun("calc_sum", null, props).get("runId");
 * Map<String, Object> status = teebox.waitForRunTerminal(runId, 30000L);
 * }</pre>
 *
 * <p>The TeeBox server is asynchronous: {@link #submitRun} returns immediately with a {@code runId}.
 * Waiting here is client-side polling, so a timeout/dropped connection never aborts the run — re-poll
 * the same {@code runId} to resume tracking.
 *
 * <p>Configuration (timeouts, tokens) is plain mutable state: set it once at construction time before
 * sharing the instance. Once configured, concurrent requests are safe — each call opens its own
 * connection and keeps no per-request state — but invoking a setter concurrently with an in-flight
 * request is not synchronized.
 */
public class TeeBoxClient {

    private final String baseUrl;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private long pollIntervalMinMs = 50L;
    private long pollIntervalMaxMs = 1000L;
    private String sharedToken;
    private String clientApiToken;
    private String publisherApiToken;
    private String adminApiToken;

    public TeeBoxClient(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().length() == 0) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
    }

    public TeeBoxClient setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        return this;
    }

    public TeeBoxClient setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    /**
     * Shared fallback bearer token for every namespace (internal networks usually leave TeeBox
     * unauthenticated, so this is optional). When the operator configures <b>distinct</b> per-namespace
     * tokens, set them via {@link #setClientApiToken}/{@link #setPublisherApiToken}; a namespace with no
     * specific token falls back to this shared one (mirrors the server's {@code apiToken} fallback).
     */
    public TeeBoxClient setBearerToken(String token) {
        this.sharedToken = token;
        return this;
    }

    /** Token for the {@code /api/client} namespace (run submit/track). Falls back to {@link #setBearerToken}. */
    public TeeBoxClient setClientApiToken(String token) {
        this.clientApiToken = token;
        return this;
    }

    /** Token for the {@code /api/publisher} namespace (register/version/activate/content). Falls back to {@link #setBearerToken}. */
    public TeeBoxClient setPublisherApiToken(String token) {
        this.publisherApiToken = token;
        return this;
    }

    /** Token for the {@code /api/admin} namespace. Falls back to {@link #setBearerToken}. */
    public TeeBoxClient setAdminApiToken(String token) {
        this.adminApiToken = token;
        return this;
    }

    /** Resolve the bearer token for a request path: per-namespace token, else the shared fallback. */
    private String tokenForPath(String path) {
        String token = null;
        if (path.startsWith("/api/client")) {
            token = clientApiToken;
        } else if (path.startsWith("/api/publisher")) {
            token = publisherApiToken;
        } else if (path.startsWith("/api/admin")) {
            token = adminApiToken;
        }
        if (token == null || token.length() == 0) {
            token = sharedToken;
        }
        return token;
    }

    // ===================== Scripts (register / update / inspect) =====================

    /** Register a script and its first version. Returns the script detail. (POST /api/publisher/scripts) */
    public Map<String, Object> registerScript(String scriptId, String version, String content, boolean activate)
            throws IOException {
        return registerScript(scriptId, version, content, null, null, activate);
    }

    public Map<String, Object> registerScript(String scriptId, String version, String content,
                                              String description, List<String> labels, boolean activate)
            throws IOException {
        requireText("scriptId", scriptId);
        requireText("version", version);
        requireText("content", content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("scriptId", scriptId);
        body.put("version", version);
        body.put("content", content);
        if (description != null) {
            body.put("description", description);
        }
        if (labels != null) {
            body.put("labels", labels);
        }
        body.put("activate", Boolean.valueOf(activate));
        return asMap(request("POST", "/api/publisher/scripts", body, 201));
    }

    /**
     * Register a script and let the server auto-assign the next sequential integer version ("1", "2", ...).
     * The assigned version is in the returned script detail ({@code activeVersion} when {@code activate=true},
     * otherwise the newest entry of {@code versions}). (POST /api/publisher/scripts)
     */
    public Map<String, Object> registerScript(String scriptId, String content, boolean activate) throws IOException {
        requireText("scriptId", scriptId);
        requireText("content", content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("scriptId", scriptId);
        body.put("content", content);
        body.put("activate", Boolean.valueOf(activate));
        return asMap(request("POST", "/api/publisher/scripts", body, 201));
    }

    /**
     * Add a new version with an auto-assigned (next integer) version label — the "update" path without
     * managing version strings yourself. Set {@code activate=true} to make it active.
     * (POST /api/publisher/scripts/{id}/versions)
     */
    public Map<String, Object> addScriptVersion(String scriptId, String content, boolean activate) throws IOException {
        requireText("scriptId", scriptId);
        requireText("content", content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("content", content);
        body.put("activate", Boolean.valueOf(activate));
        return asMap(request("POST", "/api/publisher/scripts/" + enc(scriptId) + "/versions", body, 201));
    }

    /**
     * Build an output-capture rule (for {@code outputRules}) that publishes the first stdout match of
     * {@code pattern} (capture group 1) under {@code publishKey}. Use this to surface a long job's id
     * early so callers can track it via {@link #waitForPublished}.
     */
    public static Map<String, Object> outputRule(String publishKey, String pattern) {
        return outputRule(publishKey, pattern, "stdout", 1, true);
    }

    /** Build a fully-specified output-capture rule. */
    public static Map<String, Object> outputRule(String publishKey, String pattern, String stream,
                                                 int captureGroup, boolean firstOnly) {
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("stream", stream);
        rule.put("pattern", pattern);
        rule.put("captureGroup", Integer.valueOf(captureGroup));
        rule.put("publishKey", publishKey);
        rule.put("firstOnly", Boolean.valueOf(firstOnly));
        return rule;
    }

    /** Register a script (auto version) with output-capture {@code outputRules} (see {@link #outputRule}). */
    public Map<String, Object> registerScript(String scriptId, String content, boolean activate,
                                              List<Map<String, Object>> outputRules) throws IOException {
        requireText("scriptId", scriptId);
        requireText("content", content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("scriptId", scriptId);
        body.put("content", content);
        body.put("activate", Boolean.valueOf(activate));
        if (outputRules != null && !outputRules.isEmpty()) {
            body.put("outputRules", outputRules);
        }
        return asMap(request("POST", "/api/publisher/scripts", body, 201));
    }

    /** Add a new version (auto version) with output-capture {@code outputRules} (see {@link #outputRule}). */
    public Map<String, Object> addScriptVersion(String scriptId, String content, boolean activate,
                                                List<Map<String, Object>> outputRules) throws IOException {
        requireText("scriptId", scriptId);
        requireText("content", content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("content", content);
        body.put("activate", Boolean.valueOf(activate));
        if (outputRules != null && !outputRules.isEmpty()) {
            body.put("outputRules", outputRules);
        }
        return asMap(request("POST", "/api/publisher/scripts/" + enc(scriptId) + "/versions", body, 201));
    }

    /**
     * Add a new version to an existing script (the "update" path) with an explicit version label.
     * Set {@code activate=true} to also make it the active version. Returns the script detail.
     * (POST /api/publisher/scripts/{id}/versions)
     */
    public Map<String, Object> addScriptVersion(String scriptId, String version, String content, boolean activate)
            throws IOException {
        requireText("scriptId", scriptId);
        requireText("version", version);
        requireText("content", content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("version", version);
        body.put("content", content);
        body.put("activate", Boolean.valueOf(activate));
        return asMap(request("POST", "/api/publisher/scripts/" + enc(scriptId) + "/versions", body, 201));
    }

    /** Make {@code version} the active version of {@code scriptId}. (POST /api/publisher/scripts/{id}/activate) */
    public Map<String, Object> activateScriptVersion(String scriptId, String version) throws IOException {
        requireText("scriptId", scriptId);
        requireText("version", version);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("version", version);
        return asMap(request("POST", "/api/publisher/scripts/" + enc(scriptId) + "/activate", body, 200));
    }

    /** List all registered scripts. (GET /api/publisher/scripts) */
    public List<Object> listScripts() throws IOException {
        return asList(request("GET", "/api/publisher/scripts", null, 200));
    }

    /** Get one script's detail (versions, active version, settings). (GET /api/publisher/scripts/{id}) */
    public Map<String, Object> getScript(String scriptId) throws IOException {
        requireText("scriptId", scriptId);
        return asMap(request("GET", "/api/publisher/scripts/" + enc(scriptId), null, 200));
    }

    /** Fetch the active version's source content. (GET /api/publisher/scripts/{id}/content) */
    public String getScriptContent(String scriptId) throws IOException {
        return getScriptContent(scriptId, null);
    }

    /**
     * Fetch a version's source content. {@code version} may be {@code null} for the active version.
     * Returns the raw script text. (GET /api/publisher/scripts/{id}/content[?version=...])
     */
    public String getScriptContent(String scriptId, String version) throws IOException {
        requireText("scriptId", scriptId);
        String path = "/api/publisher/scripts/" + enc(scriptId) + "/content";
        if (version != null && version.trim().length() > 0) {
            path = path + "?version=" + enc(version.trim());
        }
        Map<String, Object> payload = asMap(request("GET", path, null, 200));
        Object content = payload.get("content");
        return content != null ? String.valueOf(content) : null;
    }

    // ===================== Runs (submit / track) =====================

    /**
     * Submit a run of {@code scriptId}. {@code version} may be {@code null} to use the active version;
     * {@code props} are the script inputs (may be {@code null}). Returns immediately with a run summary
     * containing {@code runId}. (POST /api/client/scripts/{id}/runs)
     */
    public Map<String, Object> submitRun(String scriptId, String version, Map<String, Object> props)
            throws IOException {
        requireText("scriptId", scriptId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (version != null && version.trim().length() > 0) {
            body.put("version", version);
        }
        body.put("props", props != null ? props : new LinkedHashMap<String, Object>());
        return asMap(request("POST", "/api/client/scripts/" + enc(scriptId) + "/runs", body, 202));
    }

    public Map<String, Object> submitRun(String scriptId, Map<String, Object> props) throws IOException {
        return submitRun(scriptId, null, props);
    }

    /** Full run summary. (GET /api/client/runs/{runId}) */
    public Map<String, Object> getRun(String runId) throws IOException {
        requireText("runId", runId);
        return asMap(request("GET", "/api/client/runs/" + enc(runId), null, 200));
    }

    /** Run status only. (GET /api/client/runs/{runId}/status) */
    public Map<String, Object> getRunStatus(String runId) throws IOException {
        requireText("runId", runId);
        return asMap(request("GET", "/api/client/runs/" + enc(runId) + "/status", null, 200));
    }

    /** Run result data (available once terminal). (GET /api/client/runs/{runId}/result) */
    public Map<String, Object> getRunResult(String runId) throws IOException {
        requireText("runId", runId);
        return asMap(request("GET", "/api/client/runs/" + enc(runId) + "/result", null, 200));
    }

    /** Per-status task counts for a run. (GET /api/client/runs/{runId}/tasks-summary) */
    public Map<String, Object> getRunTasksSummary(String runId) throws IOException {
        requireText("runId", runId);
        return asMap(request("GET", "/api/client/runs/" + enc(runId) + "/tasks-summary", null, 200));
    }

    /** List runs of a script. (GET /api/client/scripts/{id}/runs) */
    public List<Object> listScriptRuns(String scriptId) throws IOException {
        requireText("scriptId", scriptId);
        return asList(request("GET", "/api/client/scripts/" + enc(scriptId) + "/runs", null, 200));
    }

    /**
     * Captured stdout of a run (the script's {@code PRINT(...)} output), as a {@code {runId, scriptId,
     * version, status, stream, lines, lineCount}} map. {@code lines} is the most recent
     * {@code RunRegistry.MAX_LOG_LINES} lines (a server-side ring buffer, so very long output is
     * truncated to the tail). Available while the run is RUNNING and after it ends. Use
     * {@link #getRunStdoutLines} for just the lines. (GET /api/client/runs/{runId}/stdout)
     */
    public Map<String, Object> getRunStdout(String runId) throws IOException {
        requireText("runId", runId);
        return asMap(request("GET", "/api/client/runs/" + enc(runId) + "/stdout", null, 200));
    }

    /** Captured stderr of a run, same shape as {@link #getRunStdout}. (GET /api/client/runs/{runId}/stderr) */
    public Map<String, Object> getRunStderr(String runId) throws IOException {
        requireText("runId", runId);
        return asMap(request("GET", "/api/client/runs/" + enc(runId) + "/stderr", null, 200));
    }

    /** Convenience: just the captured stdout lines of a run (see {@link #getRunStdout}). */
    public List<String> getRunStdoutLines(String runId) throws IOException {
        return extractLines(getRunStdout(runId));
    }

    /** Convenience: just the captured stderr lines of a run (see {@link #getRunStderr}). */
    public List<String> getRunStderrLines(String runId) throws IOException {
        return extractLines(getRunStderr(runId));
    }

    private static List<String> extractLines(Map<String, Object> output) {
        List<String> result = new ArrayList<String>();
        Object lines = output != null ? output.get("lines") : null;
        if (lines instanceof List) {
            for (Object line : (List<?>) lines) {
                result.add(line != null ? String.valueOf(line) : "");
            }
        }
        return result;
    }

    /**
     * Block (client-side polling, 50ms&rarr;1s backoff) until the run reaches a terminal state
     * ({@code COMPLETED} / {@code FAILED} / {@code SERVER_RESTARTED}) and return its status payload,
     * or throw {@link IOException} if {@code timeoutMs} elapses first. The run keeps executing
     * server-side regardless — the timeout message carries the {@code runId} so you can re-poll.
     */
    public Map<String, Object> waitForRunTerminal(String runId, long timeoutMs)
            throws IOException, InterruptedException {
        requireText("runId", runId);
        long start = System.currentTimeMillis();
        long pollMs = pollIntervalMinMs;
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> status = getRunStatus(runId);
            Object value = status.get("status");
            if (isTerminalStatus(value != null ? String.valueOf(value) : null)) {
                return status;
            }
            Thread.sleep(pollMs);
            pollMs = Math.min(pollMs * 2L, pollIntervalMaxMs);
        }
        throw new IOException("Timed out waiting for run " + runId + " after " + timeoutMs + "ms");
    }

    /**
     * Convenience "synchronous run": submit, block until terminal, and return the run's result payload
     * (see {@link #getRunResult}). Throws {@link IOException} if the run ends non-{@code COMPLETED}
     * (carrying the server {@code errorMessage}) or if the wait times out. Best for short / immediate
     * scripts; for long-running jobs prefer {@link #submitRun} + your own polling.
     */
    public Map<String, Object> runAndWait(String scriptId, String version, Map<String, Object> props, long timeoutMs)
            throws IOException, InterruptedException {
        Map<String, Object> submitted = submitRun(scriptId, version, props);
        Object runIdValue = submitted.get("runId");
        if (runIdValue == null) {
            throw new IOException("Submit response did not include a runId");
        }
        String runId = String.valueOf(runIdValue);
        Map<String, Object> status = waitForRunTerminal(runId, timeoutMs);
        String terminal = status.get("status") != null ? String.valueOf(status.get("status")) : null;
        if (!"COMPLETED".equals(terminal)) {
            Object error = status.get("errorMessage");
            throw new IOException("Run " + runId + " ended " + terminal + (error != null ? ": " + error : ""));
        }
        return getRunResult(runId);
    }

    /**
     * Block until the run publishes a value under {@code key} (via an {@code outputRules} capture) and
     * return it, polling {@link #getRun} (whose summary carries the {@code published} map). Useful for
     * surfacing a long job's id mid-run. Throws {@link IOException} if the run terminates without ever
     * publishing {@code key}, or if {@code timeoutMs} elapses. Returning early does NOT stop the run.
     */
    public Object waitForPublished(String runId, String key, long timeoutMs)
            throws IOException, InterruptedException {
        requireText("runId", runId);
        requireText("key", key);
        long start = System.currentTimeMillis();
        long pollMs = pollIntervalMinMs;
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> run = getRun(runId);
            Object published = run.get("published");
            if (published instanceof Map) {
                Object value = ((Map<?, ?>) published).get(key);
                if (value != null) {
                    return value;
                }
            }
            String status = run.get("status") != null ? String.valueOf(run.get("status")) : null;
            if (isTerminalStatus(status)) {
                throw new IOException("Run " + runId + " ended " + status + " without publishing '" + key + "'");
            }
            Thread.sleep(pollMs);
            pollMs = Math.min(pollMs * 2L, pollIntervalMaxMs);
        }
        throw new IOException("Timed out waiting for published key '" + key + "' on run " + runId
            + " after " + timeoutMs + "ms");
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "SERVER_RESTARTED".equals(status);
    }

    // ===================== HTTP plumbing =====================

    private Object request(String method, String path, Object body, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            String token = tokenForPath(path);
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = Json.write(body).getBytes("UTF-8");
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int code = conn.getResponseCode();
            String responseBody = readBody(conn, code);
            if (code != expectedStatus) {
                throw new IOException("TeeBox " + method + " " + path + " -> HTTP " + code
                    + (responseBody.length() > 0 ? ": " + responseBody : ""));
            }
            return responseBody.length() == 0 ? null : Json.parse(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn, int code) throws IOException {
        InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String enc(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) throws IOException {
        if (value == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IOException("Expected a JSON object but got " + value.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) throws IOException {
        if (value == null) {
            return new ArrayList<Object>();
        }
        if (value instanceof List) {
            return (List<Object>) value;
        }
        throw new IOException("Expected a JSON array but got " + value.getClass().getSimpleName());
    }

    // ===================== Minimal JSON (RFC 8259 subset, zero-dependency) =====================

    /**
     * Tiny JSON codec. {@link #parse} returns {@code Map<String,Object>} / {@code List<Object>} /
     * {@code String} / {@code Double} / {@code Boolean} / {@code null}; {@link #write} accepts the same
     * plus any {@link Number} and {@link Boolean}. Sufficient for TeeBox payloads; not a general library.
     */
    public static final class Json {

        private Json() {
        }

        public static Object parse(String text) {
            if (text == null) {
                return null;
            }
            Parser parser = new Parser(text);
            parser.skipWhitespace();
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                throw new IllegalArgumentException("Trailing content in JSON at index " + parser.index());
            }
            return value;
        }

        public static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            writeValue(sb, value);
            return sb.toString();
        }

        private static void writeValue(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String) {
                writeString(sb, (String) value);
            } else if (value instanceof Boolean) {
                sb.append(((Boolean) value).booleanValue() ? "true" : "false");
            } else if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    sb.append(value.toString());
                }
            } else if (value instanceof Number) {
                sb.append(value.toString());
            } else if (value instanceof Map) {
                writeObject(sb, (Map<?, ?>) value);
            } else if (value instanceof Iterable) {
                writeArray(sb, (Iterable<?>) value);
            } else {
                // Fallback: serialize unknown types as their string form.
                writeString(sb, value.toString());
            }
        }

        private static void writeObject(StringBuilder sb, Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        }

        private static void writeArray(StringBuilder sb, Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        }

        private static void writeString(StringBuilder sb, String text) {
            sb.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20) {
                            sb.append("\\u");
                            String hex = Integer.toHexString(c);
                            for (int p = hex.length(); p < 4; p++) {
                                sb.append('0');
                            }
                            sb.append(hex);
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        private static final class Parser {
            private final String s;
            private final int len;
            private int i;

            Parser(String s) {
                this.s = s;
                this.len = s.length();
            }

            int index() {
                return i;
            }

            boolean atEnd() {
                return i >= len;
            }

            void skipWhitespace() {
                while (i < len) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        i++;
                    } else {
                        break;
                    }
                }
            }

            Object parseValue() {
                skipWhitespace();
                if (i >= len) {
                    throw err("Unexpected end of JSON");
                }
                char c = s.charAt(i);
                switch (c) {
                    case '{': return parseObject();
                    case '[': return parseArray();
                    case '"': return parseString();
                    case 't': case 'f': return parseBoolean();
                    case 'n': return parseNull();
                    default:  return parseNumber();
                }
            }

            private Map<String, Object> parseObject() {
                expect('{');
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                skipWhitespace();
                if (peek() == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    if (peek() != '"') {
                        throw err("Expected string key");
                    }
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    map.put(key, value);
                    skipWhitespace();
                    char c = next();
                    if (c == ',') {
                        continue;
                    }
                    if (c == '}') {
                        break;
                    }
                    throw err("Expected ',' or '}' but got '" + c + "'");
                }
                return map;
            }

            private List<Object> parseArray() {
                expect('[');
                List<Object> list = new ArrayList<Object>();
                skipWhitespace();
                if (peek() == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    Object value = parseValue();
                    list.add(value);
                    skipWhitespace();
                    char c = next();
                    if (c == ',') {
                        continue;
                    }
                    if (c == ']') {
                        break;
                    }
                    throw err("Expected ',' or ']' but got '" + c + "'");
                }
                return list;
            }

            private String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = next();
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\') {
                        char e = next();
                        switch (e) {
                            case '"':  sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/':  sb.append('/'); break;
                            case 'b':  sb.append('\b'); break;
                            case 'f':  sb.append('\f'); break;
                            case 'n':  sb.append('\n'); break;
                            case 'r':  sb.append('\r'); break;
                            case 't':  sb.append('\t'); break;
                            case 'u':  sb.append((char) parseHex4()); break;
                            default:   throw err("Invalid escape '\\" + e + "'");
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private int parseHex4() {
                if (i + 4 > len) {
                    throw err("Invalid \\u escape");
                }
                int value = 0;
                for (int k = 0; k < 4; k++) {
                    char c = s.charAt(i++);
                    value <<= 4;
                    if (c >= '0' && c <= '9') {
                        value += c - '0';
                    } else if (c >= 'a' && c <= 'f') {
                        value += 10 + (c - 'a');
                    } else if (c >= 'A' && c <= 'F') {
                        value += 10 + (c - 'A');
                    } else {
                        throw err("Invalid hex digit '" + c + "'");
                    }
                }
                return value;
            }

            private Boolean parseBoolean() {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                throw err("Invalid literal at index " + i);
            }

            private Object parseNull() {
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                throw err("Invalid literal at index " + i);
            }

            private Double parseNumber() {
                int start = i;
                if (i < len && s.charAt(i) == '-') {
                    i++;
                }
                while (i < len && isNumberChar(s.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw err("Invalid number at index " + start);
                }
                try {
                    return Double.valueOf(s.substring(start, i));
                } catch (NumberFormatException e) {
                    throw err("Invalid number '" + s.substring(start, i) + "'");
                }
            }

            private static boolean isNumberChar(char c) {
                return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
            }

            private char peek() {
                if (i >= len) {
                    throw err("Unexpected end of JSON");
                }
                return s.charAt(i);
            }

            private char next() {
                if (i >= len) {
                    throw err("Unexpected end of JSON");
                }
                return s.charAt(i++);
            }

            private void expect(char c) {
                char actual = next();
                if (actual != c) {
                    throw err("Expected '" + c + "' but got '" + actual + "'");
                }
            }

            private IllegalArgumentException err(String message) {
                return new IllegalArgumentException(message);
            }
        }
    }
}
