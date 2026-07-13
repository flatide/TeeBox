package com.flatide.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.flatide.propertee2.task.TaskInfo;
import com.flatide.propertee2.task.TaskObservation;
import com.flatide.teebox.webhook.WebhookDispatcher;
import com.flatide.teebox.webhook.WebhookTarget;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
    // The engine's first-class null (JsonNull.NULL) must serialize as JSON null, not {} — see
    // JsonNullGsonAdapter. Applies to run result values (resultData + the result envelope) and any
    // RunInfo the API returns; harmless everywhere else (only JsonNull values are affected).
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(com.flatide.propertee2.value.JsonNull.class, new JsonNullGsonAdapter())
            .create();
    private final AdminPageRenderer pageRenderer;
    private final UserStore userStore;
    private final AdminSessionManager sessionManager;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService httpExecutor;

    public TeeBoxServer(TeeBoxConfig config) throws IOException {
        this.config = config;
        this.runManager = new RunManager(config.dataDir, config.maxConcurrentRuns, config);
        this.userStore = new UserStore(config.dataDir);
        this.userStore.seedAdminIfEmpty(config.adminUser, config.adminPassword);
        this.sessionManager = new AdminSessionManager(userStore);
        // Periodic session cleanup
        runManager.addMaintenanceTask(new Runnable() {
            @Override
            public void run() {
                sessionManager.cleanExpired();
            }
        });
        this.pageRenderer = new AdminPageRenderer(config, runManager, gson);
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        this.httpExecutor = Executors.newCachedThreadPool();
        this.server.setExecutor(this.httpExecutor);
        registerContexts();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(5);
        runManager.shutdown();
        httpExecutor.shutdown();
        try {
            if (!httpExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    /** The server's run manager — an embedding/test hook (e.g. to inject a drain {@link RunManager.ExitHandler}). */
    public RunManager getRunManager() {
        return runManager;
    }

    private void registerContexts() {
        // Access-log only the /api context — the JSON API called by external/upstream servers
        // (client + publisher + admin API). The /admin operator HTML UI, /health (load-balancer
        // probes), and "/" (favicon/static) are left unlogged to avoid flooding the log.
        server.createContext("/api", accessLogged(new ApiHandler()));
        server.createContext("/admin", new AdminHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/", new RootHandler());
    }

    /**
     * Decorates a handler with one access-log line per request — method, path (+query), client, and the
     * response status + elapsed time — emitted in a {@code finally} so it fires on success and error
     * alike. Request/response bodies are intentionally not logged (they can carry API tokens, script
     * source, or large payloads). The line goes to the {@code access} logger, so operators can retune or
     * silence it independently in {@code log4j2.xml} (e.g. {@code <Logger name="access" level="WARN"/>}).
     */
    private HttpHandler accessLogged(final HttpHandler delegate) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                long startNanos = System.nanoTime();
                String method = exchange.getRequestMethod();
                String rawPath = exchange.getRequestURI().getRawPath();
                String rawQuery = exchange.getRequestURI().getRawQuery();
                String target = rawQuery == null ? rawPath : rawPath + "?" + rawQuery;
                String client = clientAddress(exchange);
                Throwable failure = null;
                try {
                    delegate.handle(exchange);
                } catch (IOException | RuntimeException e) {
                    failure = e;
                    throw e;
                } finally {
                    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    // -1 until the handler sends response headers (e.g. it threw first).
                    int status = exchange.getResponseCode();
                    String line = accessLogLine(method, target, client, status, elapsedMs);
                    if (failure != null) {
                        TeeBoxLog.warn("access", line, failure);
                    } else {
                        TeeBoxLog.info("access", line);
                    }
                }
            }
        };
    }

    /** Format one access-log line: {@code "GET /api/client/runs/x?limit=10 from 127.0.0.1 -> 200 (4ms)"}. */
    static String accessLogLine(String method, String target, String client, int status, long elapsedMs) {
        return method + " " + target + " from " + client + " -> "
                + (status >= 0 ? Integer.toString(status) : "no-response") + " (" + elapsedMs + "ms)";
    }

    /** Client IP for the access log: the first {@code X-Forwarded-For} hop if present (behind a proxy),
     *  else the socket's remote address. */
    private static String clientAddress(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        java.net.InetSocketAddress remote = exchange.getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "-";
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
                TeeBoxLog.error("API", "Server error", e);
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
                // Login page
                if ("GET".equals(method) && "/admin/login".equals(path)) {
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderLoginPage(null));
                    return;
                }
                // Login action
                if ("POST".equals(method) && "/admin/login".equals(path)) {
                    Map<String, String> form = parseForm(exchange);
                    String token = sessionManager.login(form.get("user"), form.get("password"));
                    if (token != null) {
                        exchange.getResponseHeaders().add("Set-Cookie", "teebox-session=" + token + "; HttpOnly; Path=/admin; Max-Age=28800");
                        redirect(exchange, "/admin");
                    } else {
                        writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderLoginPage("Invalid username or password"));
                    }
                    return;
                }
                // Logout
                if ("POST".equals(method) && "/admin/logout".equals(path)) {
                    String token = getSessionToken(exchange);
                    sessionManager.logout(token);
                    exchange.getResponseHeaders().add("Set-Cookie", "teebox-session=; HttpOnly; Path=/admin; Max-Age=0");
                    redirect(exchange, "/admin");
                    return;
                }

                // Resolve the current session (null when not logged in, or in open mode).
                AdminSessionManager.Session session = sessionManager.getSession(getSessionToken(exchange));
                boolean loginRequired = sessionManager.isLoginRequired();
                boolean loggedIn = !loginRequired || session != null;
                // Gate the whole admin UI behind login when a roster exists — GET as well as POST. The
                // login page GET and the login/logout POSTs are handled above, so they stay reachable.
                // (Otherwise unauthenticated clients could read script source and run/task output via GET.)
                if (!loggedIn) {
                    redirect(exchange, "/admin/login");
                    return;
                }

                // Pass login state + identity to the page renderer (drives per-owner button visibility)
                pageRenderer.setSession(session, loginRequired);

                if ("GET".equals(method) && "/admin".equals(path)) {
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderIndexPage());
                    return;
                }
                if ("POST".equals(method) && "/admin/submit".equals(path)) {
                    Map<String, String> form = parseForm(exchange);
                    if (!canModifyScript(session, form.get("scriptId"))) {
                        forbidden(exchange);
                        return;
                    }
                    RunRequest request = new RunRequest();
                    request.scriptId = form.get("scriptId");
                    String version = form.get("version");
                    request.version = (version != null && version.trim().length() > 0) ? version.trim() : null;
                    request.props = parsePropsJson(form.get("propsJson"));
                    request.maxIterations = parseInt(form.get("maxIterations"), 1000);
                    request.warnLoops = "on".equals(form.get("warnLoops")) || "true".equals(form.get("warnLoops"));
                    // Admin-UI submits record the logged-in operator as the submitter (null when the UI
                    // is open / no roster) — same field the API fills from the X-TeeBox-User header.
                    request.userId = session != null ? session.username : null;
                    request.clientAddress = submitClientAddress(exchange);
                    RunInfo run = runManager.submit(request);
                    redirect(exchange, "/admin/runs/" + urlPath(run.runId));
                    return;
                }
                if ("GET".equals(method) && "/admin/scripts".equals(path)) {
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderScriptsPage());
                    return;
                }
                if ("POST".equals(method) && "/admin/scripts/validate".equals(path)) {
                    // Editor pre-check: parse the content with the exact parser the save paths use
                    // and report errors as JSON instead of failing the save into an error page.
                    // Stateless (nothing saved), so no ownership check — any logged-in user.
                    Map<String, String> form = parseForm(exchange);
                    List<String> errors = runManager.validateScriptContent(form.get("content"));
                    Map<String, Object> result = new LinkedHashMap<String, Object>();
                    result.put("ok", Boolean.valueOf(errors.isEmpty()));
                    if (!errors.isEmpty()) {
                        result.put("errors", errors);
                    }
                    writeJson(exchange, HttpURLConnection.HTTP_OK, result);
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
                    // New script => any logged-in user may create it (becomes owner). Existing script
                    // (add-version) => only the owner or an admin.
                    if (!canModifyScript(session, scriptId.trim())) {
                        forbidden(exchange);
                        return;
                    }
                    // Content-less register = create an empty script "shell" (no version, not active); the
                    // operator adds the code afterward from the detail-page editor. Only valid for a
                    // brand-new script — a content-less register against an existing script is an error.
                    if (content == null || content.trim().length() == 0) {
                        if (runManager.getScript(scriptId.trim()) != null) {
                            // The register modal is metadata-only, so the operator's mistake here is
                            // the duplicate id — say that, not "content is required".
                            throw new IllegalArgumentException("Script already exists: " + scriptId.trim());
                        }
                        String shellOwner = session != null ? session.username : null;
                        runManager.createScript(scriptId.trim(), shellOwner);
                        redirect(exchange, "/admin/scripts/" + urlPath(scriptId.trim()));
                        return;
                    }
                    // Version is optional: blank => registry auto-assigns the next sequential integer.
                    String trimmedVersion = (version != null && version.trim().length() > 0) ? version.trim() : null;
                    List<OutputPublishRule> outputRules = parseOutputRuleFromForm(form);
                    String owner = session != null ? session.username : null;
                    ScriptRegistry.RegisteredVersion registered = runManager.registerScriptVersionDetailed(
                            scriptId.trim(), trimmedVersion, content, description, new ArrayList<String>(), activate, outputRules, owner);
                    // Land on the version just saved. The page's no-?version= fallback is the ACTIVE
                    // version, and a new version doesn't auto-activate — so "Save as new version" used
                    // to bounce back to the OLD content in the editor, and the next in-place Save
                    // (whose button targets the displayed version) silently overwrote the old version.
                    redirect(exchange, "/admin/scripts/" + urlPath(scriptId.trim())
                            + "?version=" + urlParam(registered.version));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/scripts/settings/")) {
                    String scriptId = path.substring("/admin/scripts/settings/".length());
                    if (!canModifyScript(session, scriptId)) {
                        forbidden(exchange);
                        return;
                    }
                    Map<String, String> form = parseForm(exchange);
                    int maxConcurrent = 0;
                    String maxStr = form.get("maxConcurrentRuns");
                    if (maxStr != null && maxStr.trim().length() > 0) {
                        try { maxConcurrent = Integer.parseInt(maxStr.trim()); } catch (NumberFormatException ignore) {}
                    }
                    boolean immediate = "on".equals(form.get("immediate")) || "true".equals(form.get("immediate"));
                    runManager.updateScriptSettings(scriptId, Math.max(0, maxConcurrent), immediate);
                    redirect(exchange, "/admin/scripts/" + urlPath(scriptId));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/scripts/activate/")) {
                    String scriptId = path.substring("/admin/scripts/activate/".length());
                    if (scriptId.length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    if (!canModifyScript(session, scriptId)) {
                        forbidden(exchange);
                        return;
                    }
                    Map<String, String> form = parseForm(exchange);
                    String version = form.get("version");
                    if (version == null || version.trim().length() == 0) {
                        throw new IllegalArgumentException("Version is required");
                    }
                    runManager.activateScriptVersion(scriptId, version.trim());
                    redirect(exchange, "/admin/scripts/" + urlPath(scriptId));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/scripts/delete-version/")) {
                    String scriptId = path.substring("/admin/scripts/delete-version/".length());
                    if (scriptId.length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    if (!canModifyScript(session, scriptId)) {
                        forbidden(exchange);
                        return;
                    }
                    Map<String, String> form = parseForm(exchange);
                    String version = form.get("version");
                    if (version == null || version.trim().length() == 0) {
                        throw new IllegalArgumentException("Version is required");
                    }
                    runManager.deleteScriptVersion(scriptId, version.trim());
                    // Back to the plain detail page — the deleted version may have been the selected one.
                    redirect(exchange, "/admin/scripts/" + urlPath(scriptId));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/scripts/duplicate/")) {
                    String scriptId = path.substring("/admin/scripts/duplicate/".length());
                    if (scriptId.length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    // Duplicating copies the source's content, so it takes the same right as
                    // reading/editing it; the duplicator becomes the copy's owner.
                    if (!canModifyScript(session, scriptId)) {
                        forbidden(exchange);
                        return;
                    }
                    Map<String, String> form = parseForm(exchange);
                    String newScriptId = form.get("newScriptId");
                    if (newScriptId == null || newScriptId.trim().length() == 0) {
                        throw new IllegalArgumentException("New script ID is required");
                    }
                    String owner = session != null ? session.username : null;
                    ScriptInfo copy = runManager.duplicateScript(scriptId, newScriptId.trim(), owner);
                    redirect(exchange, "/admin/scripts/" + urlPath(copy.scriptId));
                    return;
                }
                if ("POST".equals(method) && "/admin/shutdown".equals(path)) {
                    if (!isAdmin(session)) {
                        forbidden(exchange);
                        return;
                    }
                    runManager.startDraining(5L * 60L * 1000L);
                    redirect(exchange, "/admin");
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/scripts/restore/")) {
                    String scriptId = path.substring("/admin/scripts/restore/".length());
                    if (scriptId.length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    if (!canModifyScript(session, scriptId)) {
                        forbidden(exchange);
                        return;
                    }
                    runManager.restoreScript(scriptId);
                    redirect(exchange, "/admin/scripts");
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/admin/scripts/delete/")) {
                    String scriptId = path.substring("/admin/scripts/delete/".length());
                    if (scriptId.length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    if (!canModifyScript(session, scriptId)) {
                        forbidden(exchange);
                        return;
                    }
                    runManager.deleteScript(scriptId);
                    redirect(exchange, "/admin/scripts");
                    return;
                }
                if ("POST".equals(method) && "/admin/scripts/update-source".equals(path)) {
                    Map<String, String> form = parseForm(exchange);
                    String scriptId = form.get("scriptId");
                    String version = form.get("version");
                    String content = form.get("content");
                    if (scriptId == null || scriptId.trim().length() == 0) {
                        throw new IllegalArgumentException("Script ID is required");
                    }
                    if (!canModifyScript(session, scriptId.trim())) {
                        forbidden(exchange);
                        return;
                    }
                    if (version == null || version.trim().length() == 0) {
                        throw new IllegalArgumentException("Version is required");
                    }
                    if (content == null || content.trim().length() == 0) {
                        throw new IllegalArgumentException("Script content is required");
                    }
                    // Parse output rule from form
                    List<OutputPublishRule> outputRules = parseOutputRuleFromForm(form);
                    // The editor prefills the field with the version's current description, so the
                    // posted value IS the desired state ("" clears). Absent field (non-UI post) = keep.
                    String description = form.get("description");
                    runManager.updateScriptVersionContent(scriptId.trim(), version.trim(), content, outputRules, description);
                    // Return to the version just edited (which may not be the active one), not the default.
                    redirect(exchange, "/admin/scripts/" + urlPath(scriptId.trim()) + "?version=" + urlParam(version.trim()));
                    return;
                }
                if ("GET".equals(method) && "/admin/runs".equals(path)) {
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderRunsPage());
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/admin/fragments/")) {
                    handleAdminFragment(exchange, path);
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/admin/scripts/")) {
                    String scriptId = path.substring("/admin/scripts/".length());
                    String selectedVersion = trimToNull(parseQuery(exchange).get("version"));
                    writeHtml(exchange, HttpURLConnection.HTTP_OK, pageRenderer.renderScriptPage(scriptId, selectedVersion));
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
                    if (!canModifyRun(session, runId)) {
                        forbidden(exchange);
                        return;
                    }
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
                    if (!canModifyTask(session, taskId)) {
                        forbidden(exchange);
                        return;
                    }
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
                TeeBoxLog.warn("AdminUI", "Bad request: " + e.getMessage());
                writeHtml(exchange, HttpURLConnection.HTTP_BAD_REQUEST, pageRenderer.renderErrorPage("Bad request", e.getMessage()));
            } catch (IllegalStateException e) {
                // Expected operational state (e.g. draining) — no stack trace needed
                TeeBoxLog.warn("AdminUI", "Rejected: " + e.getMessage());
                writeHtml(exchange, HttpURLConnection.HTTP_CONFLICT, pageRenderer.renderErrorPage("Unavailable", e.getMessage()));
            } catch (Exception e) {
                TeeBoxLog.error("AdminUI", "Server error: " + path, e);
                writeHtml(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, pageRenderer.renderErrorPage("Server error", e.getMessage()));
            }
        }
    }

    // ---- Admin UI authorization ----
    // In "open mode" (no user roster ⇒ login not required) every action is allowed, preserving the
    // historical open-by-default posture. When a roster exists, admins do anything; a regular user may
    // only act on scripts they own. The /api/* namespaces are unaffected (token-gated, unrestricted).

    /** Open mode or an admin session. */
    private boolean isAdmin(AdminSessionManager.Session session) {
        return !sessionManager.isLoginRequired() || (session != null && session.isAdmin());
    }

    /**
     * Whether the session may mutate/run the given script. True in open mode, for admins, and for the
     * script's owner. A non-existent script returns true (new-script registration); callers that need
     * an existing script let the operation fail naturally with a 400.
     */
    private boolean canModifyScript(AdminSessionManager.Session session, String scriptId) {
        if (!sessionManager.isLoginRequired()) {
            return true;
        }
        if (session == null) {
            return false;
        }
        if (session.isAdmin()) {
            return true;
        }
        if (scriptId == null || scriptId.trim().length() == 0) {
            return false;
        }
        ScriptInfo info;
        try {
            info = runManager.getScript(scriptId.trim());
        } catch (RuntimeException e) {
            return false;
        }
        if (info == null) {
            return true; // new script — any logged-in user may create it
        }
        return info.owner != null && info.owner.equals(session.username);
    }

    /** Whether the session may act on a run's tasks (resolved via the run's script owner). */
    private boolean canModifyRun(AdminSessionManager.Session session, String runId) {
        if (isAdmin(session)) {
            return true;
        }
        RunInfo run = runId != null ? runManager.getRun(runId) : null;
        if (run == null) {
            return false;
        }
        return canModifyScript(session, run.scriptId);
    }

    /** Whether the session may act on a task (resolved via task → run → script owner). */
    private boolean canModifyTask(AdminSessionManager.Session session, String taskId) {
        if (isAdmin(session)) {
            return true;
        }
        TaskInfo task = taskId != null ? runManager.getTask(taskId) : null;
        if (task == null || task.runId == null) {
            return false;
        }
        return canModifyRun(session, task.runId);
    }

    private void forbidden(HttpExchange exchange) throws IOException {
        writeHtml(exchange, HttpURLConnection.HTTP_FORBIDDEN,
                pageRenderer.renderErrorPage("Forbidden", "You do not have permission to modify this resource."));
    }

    private void handleAdminFragment(HttpExchange exchange, String path) throws IOException {
        String fragment = path.substring("/admin/fragments/".length());
        String html;
        if ("dashboard-runs".equals(fragment)) {
            List<RunInfo> running = runManager.listRuns("RUNNING", 0, -1);
            List<RunInfo> queued = runManager.listRuns("QUEUED", 0, -1);
            List<RunInfo> active = new ArrayList<RunInfo>(running);
            active.addAll(queued);
            html = pageRenderer.renderRunsTableFragment(active);
        } else if ("dashboard-sysinfo".equals(fragment)) {
            html = pageRenderer.renderSystemInfoFragment();
        } else if ("nav-counts".equals(fragment)) {
            html = pageRenderer.renderNavCountsFragment();
        } else if (fragment.startsWith("all-runs")) {
            String status = null;
            Boolean immediate = null;
            String search = null;
            int page = 1;
            int pageSize = AdminPageRenderer.DEFAULT_RUNS_PAGE_SIZE;
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) {
                Map<String, String> query = parseQuery(exchange);
                status = trimToNull(query.get("status"));
                immediate = parseInstantFilter(query.get("instant"));
                search = trimToNull(query.get("q"));
                page = parseInt(query.get("page"), 1);
            }
            if (page < 1) page = 1;
            int offset = (page - 1) * pageSize;
            int totalCount = runManager.countRuns(status, immediate, search);
            List<RunInfo> runs = runManager.listRuns(status, immediate, search, offset, pageSize);
            html = pageRenderer.renderRunsTableWithPagination(runs, page, pageSize, totalCount);
        } else if (fragment.startsWith("run-detail/")) {
            String runId = fragment.substring("run-detail/".length());
            html = pageRenderer.renderRunDetailFragment(runId);
        } else if (fragment.startsWith("task-detail/")) {
            String taskId = fragment.substring("task-detail/".length());
            html = pageRenderer.renderTaskDetailFragment(taskId);
        } else {
            writeText(exchange, HttpURLConnection.HTTP_NOT_FOUND, "Not found");
            return;
        }
        writeHtml(exchange, HttpURLConnection.HTTP_OK, html);
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
                TeeBoxLog.warn("API", "Bad request: " + e.getMessage());
                writeJson(exchange, HttpURLConnection.HTTP_BAD_REQUEST, errorMap(e.getMessage()));
            } catch (IllegalStateException e) {
                TeeBoxLog.warn("API", "Rejected: " + e.getMessage());
                writeJson(exchange, HttpURLConnection.HTTP_CONFLICT, errorMap(e.getMessage()));
            } catch (Exception e) {
                TeeBoxLog.error("API", "Server error", e);
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
            if (suffix.endsWith("/result-stream")) {
                String runId = suffix.substring(0, suffix.length() - "/result-stream".length());
                RunInfo run = runManager.getRun(runId);
                if (run == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                    return;
                }
                File file = runManager.resolveStreamFile(runId);
                if (file == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_CONFLICT,
                        errorMap("Run result is not a stream, or the file is no longer available"));
                    return;
                }
                String contentType = (run.resultData instanceof Map)
                    ? String.valueOf(((Map<?, ?>) run.resultData).get("contentType")) : "application/octet-stream";
                if (!streamFile(exchange, file, contentType)) {
                    writeJson(exchange, HttpURLConnection.HTTP_CONFLICT,
                        errorMap("Run result file is no longer available"));
                }
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
            if (suffix.endsWith("/stdout")) {
                String runId = suffix.substring(0, suffix.length() - "/stdout".length());
                int maxTaskLines = parseInt(parseQuery(exchange).get("taskLines"), DEFAULT_TASK_TAIL_LINES);
                Map<String, Object> out = buildClientRunOutputMap(runId, true, maxTaskLines);
                if (out == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                    return;
                }
                writeJson(exchange, HttpURLConnection.HTTP_OK, out);
                return;
            }
            if (suffix.endsWith("/stderr")) {
                String runId = suffix.substring(0, suffix.length() - "/stderr".length());
                int maxTaskLines = parseInt(parseQuery(exchange).get("taskLines"), DEFAULT_TASK_TAIL_LINES);
                Map<String, Object> out = buildClientRunOutputMap(runId, false, maxTaskLines);
                if (out == null) {
                    writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Run not found"));
                    return;
                }
                writeJson(exchange, HttpURLConnection.HTTP_OK, out);
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
        if ("POST".equals(method) && "/api/admin/shutdown".equals(path)) {
            long maxWaitMs = 5L * 60L * 1000L; // 5 minutes default
            try {
                Map<String, Object> body = parseJsonBody(exchange);
                Object timeout = body.get("maxWaitMs");
                if (timeout instanceof Number) {
                    maxWaitMs = ((Number) timeout).longValue();
                }
            } catch (Exception ignore) {
                // no body or invalid — use default
            }
            runManager.startDraining(maxWaitMs);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("draining", Boolean.TRUE);
            result.put("maxWaitMs", maxWaitMs);
            result.put("drainStartedAt", runManager.getDrainStartedAt());
            writeJson(exchange, HttpURLConnection.HTTP_OK, result);
            return;
        }
        if ("GET".equals(method) && "/api/admin/drain-status".equals(path)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("draining", runManager.isDraining());
            result.put("drainStartedAt", runManager.getDrainStartedAt());
            result.put("activeRuns", runManager.getActiveCount());
            result.put("queuedRuns", runManager.getQueuedCount());
            writeJson(exchange, HttpURLConnection.HTTP_OK, result);
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
            // Same filters as the admin-UI Runs page: instant=exclude|only, q=<runId/scriptId substring>.
            Boolean immediate = parseInstantFilter(query.get("instant"));
            String search = trimToNull(query.get("q"));
            int offset = parseInt(query.get("offset"), 0);
            int limit = parseInt(query.get("limit"), -1);
            writeJson(exchange, HttpURLConnection.HTTP_OK,
                runManager.listRuns(status, immediate, search, offset, limit));
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
            List<TaskInfo> tasks = runManager.listTasks(runId, status, offset, limit);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tasks", tasks);
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
            ScriptInfo info = runManager.registerScriptVersion(request.scriptId, request.version, request.content, request.description, request.labels, request.activate, request.outputRules);
            writeJson(exchange, HttpURLConnection.HTTP_CREATED, info);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/publisher/scripts/") && path.endsWith("/content")) {
            String scriptId = path.substring("/api/publisher/scripts/".length(), path.length() - "/content".length());
            ScriptInfo info = runManager.getScript(scriptId);
            if (info == null) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Script not found"));
                return;
            }
            String version = trimToNull(parseQuery(exchange).get("version"));
            if (version == null) {
                version = info.activeVersion;
            }
            if (version == null || version.length() == 0) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("No active version for script: " + scriptId));
                return;
            }
            String content = runManager.getScriptVersionContent(scriptId, version);
            if (content == null) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Unknown script version: " + scriptId + "@" + version));
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("scriptId", scriptId);
            payload.put("version", version);
            payload.put("content", content);
            writeJson(exchange, HttpURLConnection.HTTP_OK, payload);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/publisher/scripts/")) {
            String scriptId = path.substring("/api/publisher/scripts/".length());
            if (scriptId.endsWith("/activate") || scriptId.endsWith("/versions") || scriptId.endsWith("/duplicate")) {
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
            ScriptInfo info = runManager.registerScriptVersion(request.scriptId, request.version, request.content, request.description, request.labels, request.activate, request.outputRules);
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
        // POST /api/publisher/scripts/{scriptId}/duplicate — copy all versions, the active-version
        // choice, and execution settings to a new id (the supported "rename" path; run history
        // stays with the source). 400 if the target id exists or the source is deleted.
        if ("POST".equals(method) && path.startsWith("/api/publisher/scripts/") && path.endsWith("/duplicate")) {
            String scriptId = path.substring("/api/publisher/scripts/".length(), path.length() - "/duplicate".length());
            Map<String, Object> raw = parseJsonBody(exchange);
            Object newId = raw.get("newScriptId");
            if (!(newId instanceof String) || ((String) newId).trim().length() == 0) {
                throw new IllegalArgumentException("newScriptId is required");
            }
            ScriptInfo info = runManager.duplicateScript(scriptId, ((String) newId).trim(), null);
            writeJson(exchange, HttpURLConnection.HTTP_CREATED, info);
            return;
        }
        // PUT /api/publisher/scripts/{scriptId}/settings
        if ("PUT".equals(method) && path.startsWith("/api/publisher/scripts/") && path.endsWith("/settings")) {
            String scriptId = path.substring("/api/publisher/scripts/".length(), path.length() - "/settings".length());
            Map<String, Object> raw = parseJsonBody(exchange);
            int maxConcurrent = 0;
            Object maxObj = raw.get("maxConcurrentRuns");
            if (maxObj instanceof Number) maxConcurrent = ((Number) maxObj).intValue();
            boolean immediate = Boolean.TRUE.equals(raw.get("immediate"));
            ScriptInfo info = runManager.updateScriptSettings(scriptId, Math.max(0, maxConcurrent), immediate);
            writeJson(exchange, HttpURLConnection.HTTP_OK, info);
            return;
        }
        // POST /api/publisher/scripts/{scriptId}/restore
        if ("POST".equals(method) && path.startsWith("/api/publisher/scripts/") && path.endsWith("/restore")) {
            String scriptId = path.substring("/api/publisher/scripts/".length(), path.length() - "/restore".length());
            boolean restored = runManager.restoreScript(scriptId);
            if (restored) {
                writeJson(exchange, HttpURLConnection.HTTP_OK, errorMap("Restored"));
            } else {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Script not found or not deleted"));
            }
            return;
        }
        // DELETE /api/publisher/scripts/{scriptId}/versions/{version} — hard-deletes one version
        // (400 for the active version; set another active first). Must precede the whole-script
        // DELETE below, whose no-slash guard would 404 this path.
        if ("DELETE".equals(method) && path.startsWith("/api/publisher/scripts/") && path.contains("/versions/")) {
            String rest = path.substring("/api/publisher/scripts/".length());
            int split = rest.indexOf("/versions/");
            String scriptId = rest.substring(0, split);
            String version = rest.substring(split + "/versions/".length());
            if (scriptId.length() == 0 || version.length() == 0 || version.contains("/")) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
                return;
            }
            ScriptInfo info = runManager.deleteScriptVersion(scriptId, version);
            writeJson(exchange, HttpURLConnection.HTTP_OK, info);
            return;
        }
        // DELETE /api/publisher/scripts/{scriptId}
        if ("DELETE".equals(method) && path.startsWith("/api/publisher/scripts/")) {
            String scriptId = path.substring("/api/publisher/scripts/".length());
            if (scriptId.contains("/")) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
                return;
            }
            boolean deleted = runManager.deleteScript(scriptId);
            if (deleted) {
                writeJson(exchange, HttpURLConnection.HTTP_OK, errorMap("Deleted"));
            } else {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Script not found"));
            }
            return;
        }
        writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, errorMap("Not found"));
    }

    /** HTTP header carrying the submitter identity on API run submits (optional; null = anonymous). */
    static final String USER_HEADER = "X-TeeBox-User";
    private static final int USER_ID_MAX_LENGTH = 128;

    private RunRequest parseScriptRunRequest(HttpExchange exchange, String scriptId) throws IOException {
        Map<String, Object> raw = parseJsonBody(exchange);
        RunRequest request = new RunRequest();
        request.scriptId = scriptId;
        Object version = raw.get("version");
        request.version = version instanceof String ? ((String) version).trim() : null;
        parseRunOptions(raw, request);
        request.callback = parseCallback(raw.get("callback"));
        request.userId = sanitizeUserId(exchange.getRequestHeaders().getFirst(USER_HEADER));
        request.clientAddress = submitClientAddress(exchange);
        return request;
    }

    /** Caller IP for run audit — same XFF-aware resolution as the access log; null when unknown. */
    private static String submitClientAddress(HttpExchange exchange) {
        String address = clientAddress(exchange);
        return "-".equals(address) ? null : address;
    }

    /** Trim, drop empty, strip control characters, and cap length — the id is display/audit-only. */
    private static String sanitizeUserId(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > USER_ID_MAX_LENGTH ? cleaned.substring(0, USER_ID_MAX_LENGTH) : cleaned;
    }

    /**
     * Parse and validate an optional {@code callback} (string URL or {@code {"url": ...}}).
     * Rejects (HTTP 400) when webhooks are disabled or the URL fails scheme/allowlist validation.
     */
    private WebhookTarget parseCallback(Object raw) {
        if (raw == null) {
            return null;
        }
        String url = null;
        if (raw instanceof String) {
            url = ((String) raw).trim();
        } else if (raw instanceof Map) {
            Object u = ((Map<?, ?>) raw).get("url");
            if (u instanceof String) {
                url = ((String) u).trim();
            }
        }
        if (url == null || url.length() == 0) {
            throw new IllegalArgumentException("callback.url is required when callback is provided");
        }
        WebhookDispatcher dispatcher = runManager.getWebhookDispatcher();
        if (dispatcher == null) {
            throw new IllegalArgumentException("webhook callbacks are not enabled on this server");
        }
        WebhookTarget target = new WebhookTarget(url);
        dispatcher.validateTarget(target);
        return target;
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
        Object outputRules = raw.get("outputRules");
        if (outputRules instanceof List) {
            request.outputRules = new ArrayList<OutputPublishRule>();
            @SuppressWarnings("unchecked")
            List<Object> rawRules = (List<Object>) outputRules;
            for (Object ruleObj : rawRules) {
                if (!(ruleObj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> ruleMap = (Map<String, Object>) ruleObj;
                OutputPublishRule rule = new OutputPublishRule();
                Object stream = ruleMap.get("stream");
                if (stream instanceof String) rule.stream = (String) stream;
                Object pattern = ruleMap.get("pattern");
                if (pattern instanceof String) rule.pattern = (String) pattern;
                Object captureGroup = ruleMap.get("captureGroup");
                if (captureGroup instanceof Number) rule.captureGroup = ((Number) captureGroup).intValue();
                Object publishKey = ruleMap.get("publishKey");
                if (publishKey instanceof String) rule.publishKey = (String) publishKey;
                Object firstOnly = ruleMap.get("firstOnly");
                if (firstOnly instanceof Boolean) rule.firstOnly = ((Boolean) firstOnly).booleanValue();
                request.outputRules.add(rule);
            }
            // Validate regex patterns at registration time
            try {
                TaskOutputWatcher.validateRules(request.outputRules);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid outputRule regex: " + e.getMessage());
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

    private List<OutputPublishRule> parseOutputRuleFromForm(Map<String, String> form) {
        String publishPattern = form.get("publishPattern");
        String publishKey = form.get("publishKey");
        if (publishPattern == null || publishPattern.trim().length() == 0
            || publishKey == null || publishKey.trim().length() == 0) {
            return null;
        }
        try {
            java.util.regex.Pattern.compile(publishPattern.trim());
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
        }
        OutputPublishRule rule = new OutputPublishRule();
        rule.pattern = publishPattern.trim();
        rule.publishKey = publishKey.trim();
        String publishStream = form.get("publishStream");
        if ("stderr".equals(publishStream)) rule.stream = "stderr";
        String captureGroup = form.get("captureGroup");
        if (captureGroup != null && captureGroup.trim().length() > 0) {
            try { rule.captureGroup = Integer.parseInt(captureGroup.trim()); } catch (NumberFormatException ignore) {}
        }
        List<OutputPublishRule> rules = new ArrayList<OutputPublishRule>();
        rules.add(rule);
        return rules;
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
        // 64KB is generous for the 4000-char tail and avoids loading multi-GB outputs.
        detail.put("stdoutTail", tail(runManager.getTaskStdoutTail(taskId, 64 * 1024), 4000));
        detail.put("stderrTail", tail(runManager.getTaskStderrTail(taskId, 64 * 1024), 4000));
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
        status.put("submittedBy", run.submittedBy);
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
        result.put("submittedBy", run.submittedBy);
        result.put("hasExplicitReturn", Boolean.valueOf(run.hasExplicitReturn));
        // A stream-result descriptor carries an internal server path; expose only a redacted hint
        // ({stream, contentType, size}). Clients fetch the bytes via GET .../result-stream.
        Object clientResultData;
        if (StreamResultSupport.isStreamDescriptor(run.resultData)) {
            clientResultData = StreamResultSupport.redactForClient(run.resultData);
            result.put("resultData", clientResultData);
            result.put("stream", Boolean.TRUE);
        } else {
            clientResultData = run.resultData;
            result.put("resultData", clientResultData);
        }
        result.put("errorMessage", run.errorMessage);
        result.put("result", buildRunEnvelope(run, clientResultData));
        return result;
    }

    /**
     * The whole run viewed as a ProperTee Result — the "thread #0" envelope (ProperTee
     * design-draft-result-handling.md §5). Every outcome has one shape, {@code {status, ok, value}},
     * exactly like a {@code multi} collection entry, so client code is always
     * {@code env.ok ? use(env.value) : handle(env.value)}:
     *
     * <pre>
     *   COMPLETED               → {status:"done",    ok:true,  value:&lt;resultData&gt;}
     *   FAILED                  → {status:"error",   ok:false, value:&lt;errorMessage&gt;}
     *   QUEUED/PENDING/RUNNING  → {status:"running", ok:false, value:{}}
     *   SERVER_RESTARTED        → {status:"error",   ok:false, value:"server restarted"}
     * </pre>
     *
     * No shape inspection ever happens: a script that deliberately returns a ProperTee Result
     * simply nests it inside {@code value} (legitimate double-wrapping — the inner run's
     * {@code ok:false} can never be mistaken for this run's failure). Per "no implicit null",
     * {@code ScriptExecutor} already yields {@code {}} (not null) for a no-return script, so
     * {@code resultData} is normally non-null here; the {@code != null} guard is a defensive backstop
     * that also keeps the {@code value} key present under Gson's null-dropping serialization.
     */
    private static Map<String, Object> buildRunEnvelope(RunInfo run, Object resultValue) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        RunStatus status = run.status;
        if (status == RunStatus.COMPLETED) {
            envelope.put("status", "done");
            envelope.put("ok", Boolean.TRUE);
            envelope.put("value", resultValue != null ? resultValue : new LinkedHashMap<String, Object>());
        } else if (status == RunStatus.FAILED) {
            envelope.put("status", "error");
            envelope.put("ok", Boolean.FALSE);
            envelope.put("value", run.errorMessage != null ? run.errorMessage : "");
        } else if (status == RunStatus.SERVER_RESTARTED) {
            envelope.put("status", "error");
            envelope.put("ok", Boolean.FALSE);
            envelope.put("value", "server restarted");
        } else {   // QUEUED / PENDING / RUNNING (or unknown): not finished yet
            envelope.put("status", "running");
            envelope.put("ok", Boolean.FALSE);
            envelope.put("value", new LinkedHashMap<String, Object>());
        }
        return envelope;
    }

    /** Default per-task line cap for the merged run-output endpoint — mirrors the script-output ring
     *  buffer ({@code RunRegistry.MAX_LOG_LINES}); override per request with {@code ?taskLines=N}
     *  ({@code <= 0} disables the line cap, leaving only the byte guard). */
    private static final int DEFAULT_TASK_TAIL_LINES = 200;

    /** Disk-read guard underneath the line cap: each task's output is read as at most this many tail
     *  bytes, so a pathological multi-GB task can't be loaded into a single JSON response even when
     *  the line cap is disabled. */
    private static final int TASK_OUTPUT_MAX_BYTES = 1024 * 1024;

    /**
     * Run stdout (or stderr) for client polling. The script's own output and its external task
     * (SHELL) output are captured separately, so this returns both in one response:
     * <ul>
     *   <li>{@code lines} / {@code lineCount} — the captured <b>script</b> output (the most recent
     *       {@code RunRegistry.MAX_LOG_LINES} lines; a ring buffer, so older lines may be dropped).</li>
     *   <li>{@code taskLines} / {@code taskLineCount} — the merged <b>task</b> output of every SHELL
     *       task spawned by the run, in spawn (chronological) order. Most scripts run a single SHELL,
     *       so this is simply that task's output. Each task is tailed to the last {@code maxTaskLines}
     *       lines (default {@code DEFAULT_TASK_TAIL_LINES}, like the script ring buffer; {@code <= 0}
     *       disables the line cap), read from at most the last {@code TASK_OUTPUT_MAX_BYTES} bytes.</li>
     *   <li>{@code taskLinesTruncated} — {@code true} if the line cap dropped any earlier task lines.</li>
     *   <li>{@code taskCount} and a {@code tasks} breakdown ({@code taskId, command, status,
     *       exitCode, lineCount}) so callers can attribute lines when more than one task ran; each
     *       {@code lineCount} is that task's contribution to {@code taskLines} (already tailed).</li>
     * </ul>
     * Works while the run is RUNNING and after it is terminal.
     */
    private Map<String, Object> buildClientRunOutputMap(String runId, boolean stdout, int maxTaskLines) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return null;
        }
        List<String> source = stdout ? run.stdoutLines : run.stderrLines;
        List<String> lines = source != null ? new ArrayList<String>(source) : new ArrayList<String>();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("runId", run.runId);
        out.put("scriptId", run.scriptId);
        out.put("version", run.version);
        out.put("status", run.status != null ? run.status.name() : null);
        out.put("stream", stdout ? "stdout" : "stderr");
        out.put("lines", lines);
        out.put("lineCount", Integer.valueOf(lines.size()));

        // Merge the run's task (SHELL) output. listTasksForRun is newest-first; reverse to the order
        // the tasks were spawned so multi-task output reads top-to-bottom chronologically.
        List<TaskInfo> tasks = runManager.listTasksForRun(runId);
        List<TaskInfo> ordered = new ArrayList<TaskInfo>(tasks);
        Collections.reverse(ordered);
        List<String> taskLines = new ArrayList<String>();
        List<Map<String, Object>> breakdown = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        for (TaskInfo task : ordered) {
            String content = stdout
                    ? runManager.getTaskStdoutTail(task.taskId, TASK_OUTPUT_MAX_BYTES)
                    : runManager.getTaskStderrTail(task.taskId, TASK_OUTPUT_MAX_BYTES);
            List<String> perTask = splitLines(content);
            if (maxTaskLines > 0 && perTask.size() > maxTaskLines) {
                perTask = new ArrayList<String>(perTask.subList(perTask.size() - maxTaskLines, perTask.size()));
                truncated = true;
            }
            taskLines.addAll(perTask);
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("taskId", task.taskId);
            entry.put("command", task.command);
            entry.put("status", task.status);
            entry.put("exitCode", task.exitCode);
            entry.put("lineCount", Integer.valueOf(perTask.size()));
            breakdown.add(entry);
        }
        out.put("taskLines", taskLines);
        out.put("taskLineCount", Integer.valueOf(taskLines.size()));
        out.put("taskLinesTruncated", Boolean.valueOf(truncated));
        out.put("taskCount", Integer.valueOf(ordered.size()));
        out.put("tasks", breakdown);
        return out;
    }

    /** Split raw captured task output into lines (matching the per-line shape of script output):
     *  tolerant of {@code \r\n}, and a single trailing newline does not yield a final empty line. */
    private static List<String> splitLines(String content) {
        List<String> result = new ArrayList<String>();
        if (content == null || content.isEmpty()) {
            return result;
        }
        String[] parts = content.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.endsWith("\r")) {
                part = part.substring(0, part.length() - 1);
            }
            if (i == parts.length - 1 && part.isEmpty()) {
                continue;  // drop the empty element a trailing '\n' produces
            }
            result.add(part);
        }
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
        summary.put("submittedBy", run.submittedBy);
        summary.put("createdAt", Long.valueOf(run.createdAt));
        summary.put("startedAt", run.startedAt);
        summary.put("endedAt", run.endedAt);
        summary.put("hasExplicitReturn", Boolean.valueOf(run.hasExplicitReturn));
        summary.put("resultSummary", run.resultSummary);
        summary.put("errorMessage", run.errorMessage);
        if (run.published != null && !run.published.isEmpty()) {
            summary.put("published", run.published);
        }
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

    /**
     * Stream a file straight to the response body in fixed-size chunks (O(1) heap, no buffering).
     * Opens the file BEFORE sending the 200 header so a failed open (file vanished/permission) can be
     * reported as a JSON error rather than corrupting an already-started 200 response. Returns false
     * (no bytes sent, no headers committed) if the file could not be opened.
     */
    private boolean streamFile(HttpExchange exchange, File file, String contentType) throws IOException {
        InputStream in;
        long length = file.length();
        try {
            in = new FileInputStream(file);
        } catch (IOException openFailed) {
            return false;
        }
        try {
            exchange.getResponseHeaders().set("Content-Type",
                StreamResultSupport.sanitizeContentType(contentType, file.getName()));
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, length);
            OutputStream out = exchange.getResponseBody();
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            try { in.close(); } catch (IOException ignore) { }
        }
        return true;
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

    private String getSessionToken(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("teebox-session=")) {
                return trimmed.substring("teebox-session=".length());
            }
        }
        return null;
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

    private String urlParam(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Parse the {@code instant} run filter: {@code exclude} → FALSE (hide instant runs — the Runs UI
     * default), {@code only} → TRUE (instant runs only), anything else/absent → null (all runs).
     */
    private static Boolean parseInstantFilter(String value) {
        if (value == null) return null;
        String v = value.trim();
        if ("exclude".equalsIgnoreCase(v)) return Boolean.FALSE;
        if ("only".equalsIgnoreCase(v)) return Boolean.TRUE;
        return null;
    }

    private static class ScriptPublishRequest {
        String scriptId;
        String version;
        String content;
        String description;
        List<String> labels = new ArrayList<String>();
        boolean activate;
        List<OutputPublishRule> outputRules;
    }
}
