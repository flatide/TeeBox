package com.flatide.teebox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeeBoxClient {
    private final String baseUrl;
    private final String clientApiToken;
    private final String publisherApiToken;
    private final String adminApiToken;
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
    private final Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();

    public TeeBoxClient(String baseUrl, String apiToken) {
        this(baseUrl, apiToken, apiToken, apiToken);
    }

    public TeeBoxClient(String baseUrl,
                        String clientApiToken,
                        String publisherApiToken,
                        String adminApiToken) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.clientApiToken = clientApiToken;
        this.publisherApiToken = publisherApiToken;
        this.adminApiToken = adminApiToken;
    }

    public List<Map<String, Object>> listScripts() throws IOException {
        return getJsonList("/api/publisher/scripts", 200);
    }

    public Map<String, Object> getScript(String scriptId) throws IOException {
        return getJsonMap("/api/publisher/scripts/" + urlPath(scriptId), 200);
    }

    /** Fetch a version's source content ({scriptId, version, content}); version null = active. */
    public Map<String, Object> getScriptContent(String scriptId, String version) throws IOException {
        String path = "/api/publisher/scripts/" + urlPath(scriptId) + "/content";
        if (version != null && version.trim().length() > 0) {
            path = path + "?version=" + urlPath(version.trim());
        }
        return getJsonMap(path, 200);
    }

    public Map<String, Object> registerScript(String scriptId,
                                              String version,
                                              String content,
                                              String description,
                                              List<String> labels,
                                              boolean activate) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("scriptId", scriptId);
        payload.put("version", version);
        payload.put("content", content);
        payload.put("description", description);
        payload.put("labels", labels);
        payload.put("activate", Boolean.valueOf(activate));
        return postJson("/api/publisher/scripts", payload, 201);
    }

    /** Register a script letting the server auto-assign the next sequential integer version. */
    public Map<String, Object> registerScript(String scriptId, String content, boolean activate) throws IOException {
        return registerScript(scriptId, content, activate, null);
    }

    /** Register a script (auto version) with output-capture rules. */
    public Map<String, Object> registerScript(String scriptId, String content, boolean activate,
                                              List<Map<String, Object>> outputRules) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("scriptId", scriptId);
        payload.put("content", content);
        payload.put("activate", Boolean.valueOf(activate));
        if (outputRules != null && !outputRules.isEmpty()) {
            payload.put("outputRules", outputRules);
        }
        return postJson("/api/publisher/scripts", payload, 201);
    }

    /** Add a new version with an auto-assigned (next integer) version label. */
    public Map<String, Object> addScriptVersion(String scriptId, String content, boolean activate) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("content", content);
        payload.put("activate", Boolean.valueOf(activate));
        return postJson("/api/publisher/scripts/" + urlPath(scriptId) + "/versions", payload, 201);
    }

    public Map<String, Object> activateScript(String scriptId, String version) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        return postJson("/api/publisher/scripts/" + urlPath(scriptId) + "/activate", payload, 200);
    }

    public Map<String, Object> submitRun(String scriptId, String version, Map<String, Object> props) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        payload.put("props", props != null ? props : new LinkedHashMap<String, Object>());
        return postJson("/api/client/scripts/" + urlPath(scriptId) + "/runs", payload, 202);
    }

    public Map<String, Object> getRun(String runId) throws IOException {
        return getJsonMap("/api/client/runs/" + urlPath(runId), 200);
    }

    public Map<String, Object> getRunStatus(String runId) throws IOException {
        return getJsonMap("/api/client/runs/" + urlPath(runId) + "/status", 200);
    }

    public Map<String, Object> getRunResult(String runId) throws IOException {
        return getJsonMap("/api/client/runs/" + urlPath(runId) + "/result", 200);
    }

    public Map<String, Object> getRunTaskSummary(String runId) throws IOException {
        return getJsonMap("/api/client/runs/" + urlPath(runId) + "/tasks-summary", 200);
    }

    public Map<String, Object> getAdminRun(String runId) throws IOException {
        return getJsonMap("/api/admin/runs/" + urlPath(runId), 200);
    }

    public Map<String, Object> getAdminTask(String taskId) throws IOException {
        return getJsonMap("/api/admin/tasks/" + urlPath(taskId), 200);
    }

    public Map<String, Object> killAdminTask(String taskId) throws IOException {
        return postJson("/api/admin/tasks/" + urlPath(taskId) + "/kill", new LinkedHashMap<String, Object>(), 200);
    }

    /**
     * Block until the run reaches a terminal state ({@code COMPLETED} / {@code FAILED} /
     * {@code SERVER_RESTARTED}) and return its status payload, or throw if {@code timeoutMs}
     * elapses first. This is client-side polling: the server stays asynchronous, so a dropped
     * connection or timeout here never aborts the run — re-poll the same {@code runId} to resume.
     * Poll interval backs off from 50ms to 1s so short runs return fast without hammering long ones.
     */
    public Map<String, Object> waitForRunTerminal(String runId, long timeoutMs) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        long pollMs = 50L;
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> status = getRunStatus(runId);
            Object value = status.get("status");
            String current = value != null ? String.valueOf(value) : null;
            if (isTerminalStatus(current)) {
                return status;
            }
            Thread.sleep(pollMs);
            pollMs = Math.min(pollMs * 2L, 1000L);
        }
        throw new IOException("Timed out waiting for run " + runId + " after " + timeoutMs + "ms");
    }

    /**
     * Block until the run publishes a value under {@code key} (via an {@code outputRules} capture) and
     * return it, polling {@code getRun} (whose summary carries the {@code published} map). Throws if the
     * run terminates without publishing {@code key} or the wait times out.
     */
    public Object waitForPublished(String runId, String key, long timeoutMs)
            throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> run = getRun(runId);
            Object published = run.get("published");
            if (published instanceof Map) {
                Object value = ((Map<?, ?>) published).get(key);
                if (value != null) {
                    return value;
                }
            }
            Object statusValue = run.get("status");
            String status = statusValue != null ? String.valueOf(statusValue) : null;
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "SERVER_RESTARTED".equals(status)) {
                throw new IOException("Run " + runId + " ended " + status + " without publishing '" + key + "'");
            }
            Thread.sleep(50L);
        }
        throw new IOException("Timed out waiting for published key '" + key + "' on run " + runId);
    }

    /**
     * Convenience "synchronous run": submit a run, block until it terminates, and return the
     * run's result payload ({@code GET /api/client/runs/{runId}/result}). Throws {@link IOException}
     * if the run ends non-{@code COMPLETED} (carrying the server-reported {@code errorMessage}) or
     * if the wait times out. Intended for short / {@code immediate} scripts; for long-running jobs
     * prefer {@link #submitRun} + your own polling so a client timeout never strands you. The run
     * keeps executing server-side regardless of this call — recover via the returned/submitted runId.
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
            throw new IOException("Run " + runId + " ended " + terminal
                + (error != null ? ": " + error : ""));
        }
        return getRunResult(runId);
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "SERVER_RESTARTED".equals(status);
    }

    private Map<String, Object> getJsonMap(String path, int expectedStatus) throws IOException {
        HttpURLConnection conn = open("GET", path);
        return readJsonMap(conn, expectedStatus);
    }

    private List<Map<String, Object>> getJsonList(String path, int expectedStatus) throws IOException {
        HttpURLConnection conn = open("GET", path);
        return readJsonList(conn, expectedStatus);
    }

    private Map<String, Object> postJson(String path, Object payload, int expectedStatus) throws IOException {
        HttpURLConnection conn = open("POST", path);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = gson.toJson(payload).getBytes("UTF-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body);
        } finally {
            out.close();
        }
        return readJsonMap(conn, expectedStatus);
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        String token = tokenForPath(path);
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    private String tokenForPath(String path) {
        if (path.startsWith("/api/client/") || "/api/client".equals(path)) {
            return clientApiToken;
        }
        if (path.startsWith("/api/publisher/") || "/api/publisher".equals(path)) {
            return publisherApiToken;
        }
        if (path.startsWith("/api/admin/") || "/api/admin".equals(path)) {
            return adminApiToken;
        }
        return null;
    }

    private Map<String, Object> readJsonMap(HttpURLConnection conn, int expectedStatus) throws IOException {
        int status = conn.getResponseCode();
        if (status != expectedStatus) {
            throw new IOException("Unexpected HTTP status " + status + " for " + conn.getURL());
        }
        InputStream input = conn.getInputStream();
        try {
            return gson.fromJson(readAll(input), mapType);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private List<Map<String, Object>> readJsonList(HttpURLConnection conn, int expectedStatus) throws IOException {
        int status = conn.getResponseCode();
        if (status != expectedStatus) {
            throw new IOException("Unexpected HTTP status " + status + " for " + conn.getURL());
        }
        InputStream input = conn.getInputStream();
        try {
            return gson.fromJson(readAll(input), listType);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String urlPath(String value) {
        return value != null ? value.replace(" ", "%20") : "";
    }
}
