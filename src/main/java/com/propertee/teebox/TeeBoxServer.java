package com.propertee.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.propertee.task.TaskInfo;
import com.propertee.task.TaskObservation;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public class TeeBoxServer {
    private final TeeBoxConfig config;
    private final RunManager runManager;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AdminPageRenderer pageRenderer;
    private final HttpServer server;

    public TeeBoxServer(TeeBoxConfig config) throws IOException {
        this.config = config;
        this.runManager = new RunManager(config.dataDir, config.maxConcurrentRuns, config);
        this.pageRenderer = new AdminPageRenderer(config, runManager, gson);
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        registerContexts();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(5);
        runManager.shutdown();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void registerContexts() {
        server.createContext("/api", new ApiHandler());
        server.createContext("/admin", new AdminHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/", new RootHandler());
    }

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            redirect(exchange, "/admin");
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, HttpURLConnection.HTTP_BAD_METHOD, errorMap("Use GET"));
                return;
            }
            try {
                HealthStatus health = runManager.getHealthStatus();
                int status = health.healthy ? HttpURLConnection.HTTP_OK : 503;
                writeJson(exchange, status, health);
            } catch (Exception e) {
                writeJson(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, errorMap(e.getMessage()));
            }
        }
    }

    private class AdminHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            try {
                if ("GET".equals(method) && "/admin".equals(path)) {
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderIndexPage());
                    return;
                }
                if ("POST".equals(method) && "/admin/submit".equals(path)) {
                    Map<String, String> form = parseForm(exchange);
                    RunRequest request = new RunRequest();
                    request.scriptId = form.get("scriptId");
                    String version = form.get("version");
                    request.version = (version != null && version.trim().length() > 0) ? version.trim() : null;
                    request.props = parsePropsJson(form.get("propsJson"));
                    request.maxIterations = parseInt(form.get("maxIterations"), 1000);
                    request.warnLoops = "on".equals(form.get("warnLoops")) || "true".equals(form.get("warnLoops"));
                    RunInfo run = runManager.submit(request);
                    redirect(exchange, "/admin/runs/" + urlPath(run.runId));
                    return;
                }
                if ("GET".equals(method) && "/admin/scripts".equals(path)) {
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderScriptsPage());
                    return;
                }
                if ("POST".equals(method) && "/admin/scripts/register".equals(path)) {
                    Map<String, String> form = parseForm(exchange);
                    String scriptId = form.get("scriptId");
                    String version = form.get("version");
                    String content = form.get("content");
                    String description = form.get("description");
                    boolean activate = "on".equals(form.get("activate")) || "true".equals(form.get("activate"));
                    if (scriptId == null || scriptId.trim().length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    if (version == null || version.trim().length() == 0) {
                        throw new IllegalArgumentException("Version is required");
                    }
                    if (content == null || content.trim().length() == 0) {
                        throw new IllegalArgumentException("Script content is required");
                    }
                    runManager.registerScriptVersion(scriptId.trim(), version.trim(), content, description, new ArrayList<String>(), activate);
                    redirect(exchange, "/admin/scripts/" + urlPath(scriptId.trim()));
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/admin/scripts/")) {
                    String scriptId = path.substring("/admin/scripts/".length());
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderScriptPage(scriptId));
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/admin/runs/")) {
                    String suffix = path.substring("/admin/runs/".length());
                    if (suffix.endsWith("/kill-tasks")) {
                        writeText(exchange, HttpURLConnection.HTTP_BAD_METHOD, "Use POST");
                        return;
                    }
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderRunPage(suffix));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/runs/") && path.endsWith("/kill-tasks")) {
                    String runId = path.substring("/admin/runs/".length(), path.length() - "/kill-tasks".length());
                    runManager.killRunTasks(runId);
                    redirect(exchange, "/admin/runs/" + urlPath(runId));
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/admin/tasks/")) {
                    String suffix = path.substring("/admin/tasks/".length());
                    if (suffix.endsWith("/kill")) {
                        writeText(exchange, HttpURLConnection.HTTP_BAD_METHOD, "Use POST");
                        return;
                    }
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderTaskPage(suffix));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/tasks/") && path.endsWith("/kill")) {
                    String taskId = path.substring("/admin/tasks/".length(), path.length() - "/kill".length());
                    TaskInfo info = runManager.getTask(taskId);
                    runManager.killTask(taskId);
                    if (info != null && info.runId != null) {
                        redirect(exchange, "/admin/runs/" + urlPath(info.runId));
                    } else {
                        redirect(exchange, "/admin/tasks/" + urlPath(taskId));
                    }
                    return;
                }
                writeText(exchange, HttpURLConnection.HTTP_NOT_FOUND, "Not found");
            } catch (IllegalArgumentException e) {
                writeHtml(exchange, HttpURLConnection.HTTP_BAD_REQUEST, pageRenderer.renderErrorPage("Bad request", e.getMessage()));
            } catch (Exception e) {
                writeHtml(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, pageRenderer.renderErrorPage("Server error", e.getMessage()));
            }
        }
    }

    private class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            try {
                if (!isAuthorized(exchange, path)) {
                    writeJson(exchange, HttpURLConnection.HTTP_UNAUTHORIZED, errorMap("Unauthorized"));
                    return;
                }
                if (path.startsWith("/api/client/") || "/api/client".equals(path)) {
                    handleClientApi(exchange, method, path);
                    return;
                }
                if (path.startsWith("/api/admin/") || "/api/admin".equals(path)) {
                    handleAdminApi(exchange, method, path);
                    return;
                }
                if (path.startsWith("/api/publisher/") || "/api/publisher".equals(path)) {
                    handlePublisherApi(exchange, method, path);
                    return;
                }
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
            } catch (IllegalArgumentException e) {
                writeJson(exchange, HttpURLConnection.HTTP_BAD_REQUEST, errorMap(e.getMessage()));
            } catch (Exception e) {
                writeJson(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, errorMap(e.getMessage()));
            }
        }
    }

    private boolean isAuthorized(HttpExchange exchange, String path) {
        String requiredToken = requiredApiToken(path);
        if (requiredToken == null || requiredToken.length() == 0) {
            return true;
        }
        Headers headers = exchange.getRequestHeaders();
        String auth = headers.getFirst("Authorization");
        return ("Bearer " + requiredToken).equals(auth);
    }

    private String requiredApiToken(String path) {
        if (path.startsWith("/api/client/") || "/api/client".equals(path)) {
            return config.tokenForClientApi();
        }
        if (path.startsWith("/api/publisher/") || "/api/publisher".equals(path)) {
            return config.tokenForPublisherApi();
        }
        if (path.startsWith("/api/admin/") || "/api/admin".equals(path)) {
            return config.tokenForAdminApi();
        }
        return null;
    }

    private void handleClientApi(HttpExchange exchange, String method, String path) throws IOException {
        if ("GET".equals(method) && "/api/client/runs".equals(path)) {
            Map<String, String> query = parseQuery(exchange);
            String status = trimToNull(query.get("status"));
            int offset = parseInt(query.get("offset"), 0);
            int limit = parseInt(query.get("limit"), -1);
            List<RunInfo> runs = runManager.listRuns(status, offset, limit);
            List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
            for (RunInfo run : runs) {
                payload.add(buildClientRunSummary(run));
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, payload);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/client/runs/")) {
            String suffix = path.substring("/api/client/runs/".length());
            if (suffix.endsWith("/status")) {
                String runId = suffix.substring(0, suffix.length() - "/status".length());
                Map<String, Object> status = buildClientRunStatusMap(runId);
                if (status == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                    return;
                }
                writeJson(exchange, HttpURLConnection.HTTP_OK, status);
                return;
            }
            if (suffix.endsWith("/result")) {
                String runId = suffix.substring(0, suffix.length() - "/result".length());
                Map<String, Object> result = buildClientRunResultMap(runId);
                if (result == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                    return;
                }
                writeJson(exchange, HttpURLConnection.HTTP_OK, result);
                return;
            }
            if (suffix.endsWith("/tasks-summary")) {
                String runId = suffix.substring(0, suffix.length() - "/tasks-summary".length());
                Map<String, Object> summary = buildClientTaskSummaryMap(runId);
                if (summary == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                    return;
                }
                writeJson(exchange, HttpURLConnection.HTTP_OK, summary);
                return;
            }
            Map<String, Object> detail = buildClientRunDetailMap(suffix);
            if (detail == null) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                return;
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, detail);
            return;
        }
        if (path.startsWith("/api/client/scripts/")) {
            handleClientScriptApi(exchange, method, path);
            return;
        }
        writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
    }

    private void handleClientScriptApi(HttpExchange exchange, String method, String path) throws IOException {
        // /api/client/scripts/{scriptId}/runs
        String suffix = path.substring("/api/client/scripts/".length());
        int slashIdx = suffix.indexOf('/');
        if (slashIdx < 0) {
            writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
            return;
        }
        String scriptId = suffix.substring(0, slashIdx);
        String rest = suffix.substring(slashIdx);
        if ("/runs".equals(rest)) {
            if ("POST".equals(method)) {
                RunRequest request = parseScriptRunRequest(exchange, scriptId);
                RunInfo run = runManager.submit(request);
                writeJson(exchange, HttpURLConnection.HTTP_ACCEPTED, buildClientRunSummary(run));
                return;
            }
            if ("GET".equals(method)) {
                Map<String, String> query = parseQuery(exchange);
                String status = trimToNull(query.get("status"));
                int offset = parseInt(query.get("offset"), 0);
                int limit = parseInt(query.get("limit"), -1);
                List<RunInfo> runs = runManager.listRuns(status, scriptId, offset, limit);
                List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
                for (RunInfo run : runs) {
                    payload.add(buildClientRunSummary(run));
                }
                writeJson(exchange, HttpURLConnection.HTTP_OK, payload);
                return;
            }
        }
        writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
    }

    private void handleAdminApi(HttpExchange exchange, String method, String path) throws IOException {
        if ("GET".equals(method) && "/api/admin/health".equals(path)) {
            HealthStatus health = runManager.getHealthStatus();
            int statusCode = health.healthy ? HttpURLConnection.HTTP_OK : 503;
            writeJson(exchange, statusCode, health);
            return;
        }
        if ("GET".equals(method) && "/api/admin/system".equals(path)) {
            SystemInfo info = runManager.getSystemInfo();
            if (info == null) {
                writeJson(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, errorMap("System info not available"));
                return;
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, info);
            return;
        }
        if ("GET".equals(method) && "/api/admin/runs".equals(path)) {
            Map<String, String> query = parseQuery(exchange);
            String status = trimToNull(query.get("status"));
            int offset = parseInt(query.get("offset"), 0);
            int limit = parseInt(query.get("limit"), -1);
            writeJson(exchange, HttpURLConnection.HTTP_OK, runManager.listRuns(status, offset, limit));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/admin/runs/")) {
            String suffix = path.substring("/api/admin/runs/".length());
            if (suffix.endsWith("/threads")) {
                String runId = suffix.substring(0, suffix.length() - "/threads".length());
                writeJson(exchange, HttpURLConnection.HTTP_OK, runManager.listThreads(runId));
                return;
            }
            if (suffix.endsWith("/tasks")) {
                String runId = suffix.substring(0, suffix.length() - "/tasks".length());
                writeJson(exchange, HttpURLConnection.HTTP_OK, runManager.listTasksForRun(runId));
                return;
            }
            Map<String, Object> detail = buildRunDetailMap(suffix);
            if (detail == null) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                return;
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, detail);
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/admin/runs/") && path.endsWith("/kill-tasks")) {
            String runId = path.substring("/api/admin/runs/".length(), path.length() - "/kill-tasks".length());
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runId", runId);
            result.put("killed", Integer.valueOf(runManager.killRunTasks(runId)));
            writeJson(exchange, HttpURLConnection.HTTP_OK, result);
            return;
        }
        if ("GET".equals(method) && "/api/admin/tasks".equals(path)) {
            Map<String, String> query = parseQuery(exchange);
            String runId = trimToNull(query.get("runId"));
            String status = trimToNull(query.get("status"));
            int offset = parseInt(query.get("offset"), 0);
            int limit = parseInt(query.get("limit"), -1);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tasks", runManager.listTasks(runId, status, offset, limit));
            if (runId == null && status == null && offset == 0 && limit <= 0) {
                payload.put("detached", runManager.listDetachedTasks());
            } else {
                payload.put("detached", new ArrayList<TaskInfo>());
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, payload);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/admin/tasks/")) {
            String taskId = path.substring("/api/admin/tasks/".length());
            if (taskId.endsWith("/kill")) {
                writeJson(exchange, HttpURLConnection.HTTP_BAD_METHOD, errorMap("Use POST"));
                return;
            }
            Map<String, Object> detail = buildTaskDetailMap(taskId);
            if (detail == null) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Task not found"));
                return;
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, detail);
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/admin/tasks/") && path.endsWith("/kill")) {
            String taskId = path.substring("/api/admin/tasks/".length(), path.length() - "/kill".length());
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("taskId", taskId);
            result.put("killed", Boolean.valueOf(runManager.killTask(taskId)));
            writeJson(exchange, HttpURLConnection.HTTP_OK, result);
            return;
        }
        writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
    }

    private void handlePublisherApi(HttpExchange exchange, String method, String path) throws IOException {
        if ("GET".equals(method) && "/api/publisher/scripts".equals(path)) {
            writeJson(exchange, HttpURLConnection.HTTP_OK, runManager.listScripts());
            return;
        }
        if ("POST".equals(method) && "/api/publisher/scripts".equals(path)) {
            ScriptPublishRequest request = parseScriptPublishRequest(exchange);
            ScriptInfo info = runManager.registerScriptVersion(request.scriptId, request.version, request.content, request.description, request.labels, request.activate);
            writeJson(exchange, HttpURLConnection.HTTP_CREATED, info);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/publisher/scripts/")) {
            String scriptId = path.substring("/api/publisher/scripts/".length());
            if (scriptId.endsWith("/activate") || scriptId.endsWith("/versions")) {
                writeJson(exchange, HttpURLConnection.HTTP_BAD_METHOD, errorMap("Use POST"));
                return;
            }
            ScriptInfo info = runManager.getScript(scriptId);
            if (info == null) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Script not found"));
                return;
            }
            writeJson(exchange, HttpURLConnection.HTTP_OK, info);
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/publisher/scripts/") && path.endsWith("/versions")) {
            String scriptId = path.substring("/api/publisher/scripts/".length(), path.length() - "/versions".length());
            ScriptPublishRequest request = parseScriptPublishRequest(exchange);
            if (request.scriptId == null || request.scriptId.length() == 0) {
                request.scriptId = scriptId;
            }
            if (!scriptId.equals(request.scriptId)) {
                throw new IllegalArgumentException("scriptId in path and body must match");
            }
            ScriptInfo info = runManager.registerScriptVersion(request.scriptId, request.version, request.content, request.description, request.labels, request.activate);
            writeJson(exchange, HttpURLConnection.HTTP_CREATED, info);
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/publisher/scripts/") && path.endsWith("/activate")) {
            String scriptId = path.substring("/api/publisher/scripts/".length(), path.length() - "/activate".length());
            Map<String, Object> raw = parseJsonBody(exchange);
            Object version = raw.get("version");
            if (!(version instanceof String) || ((String) version).trim().length() == 0) {
                throw new IllegalArgumentException("version is required");
            }
            ScriptInfo info = runManager.activateScriptVersion(scriptId, ((String) version).trim());
            writeJson(exchange, HttpURLConnection.HTTP_OK, info);
            return;
        }
        writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
    }

    private RunRequest parseScriptRunRequest(HttpExchange exchange, String scriptId) throws IOException {
        Map<String, Object> raw = parseJsonBody(exchange);
        RunRequest request = new RunRequest();
        request.scriptId = scriptId;
        Object version = raw.get("version");
        request.version = version instanceof String ? ((String) version).trim() : null;
        parseRunOptions(raw, request);
        return request;
    }

    private void parseRunOptions(Map<String, Object> raw, RunRequest request) {
        Object props = raw.get("props");
        if (props instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> propsMap = (Map<String, Object>) props;
            request.props = normalizeNumbers(propsMap);
        }
        Object maxIterations = raw.get("maxIterations");
        if (maxIterations instanceof Number) {
            request.maxIterations = ((Number) maxIterations).intValue();
        }
        Object warnLoops = raw.get("warnLoops");
        if (warnLoops instanceof Boolean) {
            request.warnLoops = ((Boolean) warnLoops).booleanValue();
        }
    }

    private ScriptPublishRequest parseScriptPublishRequest(HttpExchange exchange) throws IOException {
        Map<String, Object> raw = parseJsonBody(exchange);
        ScriptPublishRequest request = new ScriptPublishRequest();
        Object scriptId = raw.get("scriptId");
        request.scriptId = scriptId instanceof String ? ((String) scriptId).trim() : null;
        Object version = raw.get("version");
        request.version = version instanceof String ? ((String) version).trim() : null;
        Object content = raw.get("content");
        request.content = content instanceof String ? (String) content : null;
        Object description = raw.get("description");
        request.description = description instanceof String ? (String) description : null;
        Object activate = raw.get("activate");
        request.activate = activate instanceof Boolean && ((Boolean) activate).booleanValue();
        Object labels = raw.get("labels");
        if (labels instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> rawLabels = (List<Object>) labels;
            for (Object value : rawLabels) {
                if (value instanceof String) {
                    request.labels.add(((String) value).trim());
                }
            }
        }
        return request;
    }

    private Map<String, Object> parseJsonBody(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.trim().length() == 0) {
            throw new IllegalArgumentException("Request body is required");
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> raw = gson.fromJson(body, type);
        if (raw == null) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
        return raw;
    }

    private Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (body == null || body.length() == 0) {
            return result;
        }
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair.length() == 0) continue;
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    private Map<String, String> parseQuery(HttpExchange exchange) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.length() == 0) {
            return result;
        }
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            if (pair.length() == 0) continue;
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    private Map<String, Object> parsePropsJson(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return new LinkedHashMap<String, Object>();
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> props = gson.fromJson(raw, type);
        if (props == null) {
            return new LinkedHashMap<String, Object>();
        }
        return normalizeNumbers(props);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeNumbers(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Double) {
                double d = ((Double) value).doubleValue();
                if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                    normalized.put(entry.getKey(), Integer.valueOf((int) d));
                } else {
                    normalized.put(entry.getKey(), value);
                }
            } else if (value instanceof Map) {
                normalized.put(entry.getKey(), normalizeNumbers((Map<String, Object>) value));
            } else if (value instanceof List) {
                normalized.put(entry.getKey(), normalizeList((List<Object>) value));
            } else {
                normalized.put(entry.getKey(), value);
            }
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeList(List<Object> list) {
        List<Object> normalized = new ArrayList<Object>();
        for (Object value : list) {
            if (value instanceof Double) {
                double d = ((Double) value).doubleValue();
                if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                    normalized.add(Integer.valueOf((int) d));
                } else {
                    normalized.add(value);
                }
            } else if (value instanceof Map) {
                normalized.add(normalizeNumbers((Map<String, Object>) value));
            } else if (value instanceof List) {
                normalized.add(normalizeList((List<Object>) value));
            } else {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private Map<String, Object> buildRunDetailMap(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("run", run);
        detail.put("threads", runManager.listThreads(runId));
        detail.put("tasks", runManager.listTasksForRun(runId));
        return detail;
    }

    private Map<String, Object> buildTaskDetailMap(String taskId) {
        TaskInfo info = runManager.getTask(taskId);
        if (info == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("task", info);
        detail.put("observation", runManager.observeTask(taskId));
        detail.put("stdoutTail", tail(runManager.getTaskStdout(taskId), 4000));
        detail.put("stderrTail", tail(runManager.getTaskStderr(taskId), 4000));
        return detail;
    }

    private Map<String, Object> buildClientRunDetailMap(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return null;
        }
        return buildClientRunSummary(run);
    }

    private Map<String, Object> buildClientRunStatusMap(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return null;
        }
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("runId", run.runId);
        status.put("scriptId", run.scriptId);
        status.put("version", run.version);
        status.put("status", run.status != null ? run.status.name() : null);
        status.put("createdAt", Long.valueOf(run.createdAt));
        status.put("startedAt", run.startedAt);
        status.put("endedAt", run.endedAt);
        status.put("hasExplicitReturn", Boolean.valueOf(run.hasExplicitReturn));
        status.put("errorMessage", run.errorMessage);
        return status;
    }

    private Map<String, Object> buildClientRunResultMap(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", run.runId);
        result.put("scriptId", run.scriptId);
        result.put("version", run.version);
        result.put("status", run.status != null ? run.status.name() : null);
        result.put("hasExplicitReturn", Boolean.valueOf(run.hasExplicitReturn));
        result.put("resultData", run.resultData);
        result.put("errorMessage", run.errorMessage);
        return result;
    }

    private Map<String, Object> buildClientTaskSummaryMap(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return null;
        }
        List<TaskInfo> tasks = runManager.listTasksForRun(runId);
        int running = 0;
        int completed = 0;
        int failed = 0;
        int killed = 0;
        int other = 0;
        for (TaskInfo task : tasks) {
            if ("running".equalsIgnoreCase(task.status)) {
                running++;
            } else if ("completed".equalsIgnoreCase(task.status)) {
                completed++;
            } else if ("failed".equalsIgnoreCase(task.status)) {
                failed++;
            } else if ("killed".equalsIgnoreCase(task.status)) {
                killed++;
            } else {
                other++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("runId", runId);
        summary.put("total", Integer.valueOf(tasks.size()));
        summary.put("running", Integer.valueOf(running));
        summary.put("completed", Integer.valueOf(completed));
        summary.put("failed", Integer.valueOf(failed));
        summary.put("killed", Integer.valueOf(killed));
        summary.put("other", Integer.valueOf(other));
        return summary;
    }

    private Map<String, Object> buildClientRunSummary(RunInfo run) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("runId", run.runId);
        summary.put("scriptId", run.scriptId);
        summary.put("version", run.version);
        summary.put("status", run.status != null ? run.status.name() : null);
        summary.put("createdAt", Long.valueOf(run.createdAt));
        summary.put("startedAt", run.startedAt);
        summary.put("endedAt", run.endedAt);
        summary.put("hasExplicitReturn", Boolean.valueOf(run.hasExplicitReturn));
        summary.put("resultSummary", run.resultSummary);
        summary.put("errorMessage", run.errorMessage);
        return summary;
    }

    private void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private void writeHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private void writeText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = input.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("error", message);
        return map;
    }

    private String tail(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(text.length() - maxChars);
    }

    private int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.trim().length() == 0) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private String urlDecode(String value) throws IOException {
        return URLDecoder.decode(value, "UTF-8");
    }

    private String urlPath(String value) {
        if (value == null) return "";
        return value.replace(" ", "%20");
    }

    private static class ScriptPublishRequest {
        String scriptId;
        String version;
        String content;
        String description;
        List<String> labels = new ArrayList<String>();
        boolean activate;
    }
}
