package com.flatide.teebox;

import com.google.gson.Gson;
import com.flatide.propertee2.task.TaskInfo;
import com.flatide.propertee2.task.TaskObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AdminPageRenderer {
    static final int DEFAULT_RUNS_PAGE_SIZE = 25;
    private final TeeBoxConfig config;
    private final RunManager runManager;
    private final Gson gson;
    private boolean loggedIn = true;
    private boolean loginRequired = false;
    private String currentUser = null;
    private String currentRole = null;

    /** ProperTee code-editor assets (ported from the ProperTee playground), loaded once from the
     *  classpath and inlined into admin pages (TeeBox serves no static assets, and the login gate
     *  would block a separate /admin asset request anyway). */
    private static final String EDITOR_CSS = loadResource("/propertee-editor.css");
    private static final String EDITOR_JS = loadResource("/propertee-editor.js");
    /** The propertee-js browser bundle, copied verbatim from ../propertee-js/docs/dist/ — provides
     *  the client-side checkScript (syntax + builtin lint) used by the editor pre-check. Inlined
     *  (TeeBox serves no static files) and emitted only on the script detail page. Refresh the
     *  copy when the language spec moves. */
    private static final String BUNDLE_JS = loadResource("/propertee-bundle.js");

    private static String loadResource(String path) {
        try (java.io.InputStream in = AdminPageRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                return "";
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    public AdminPageRenderer(TeeBoxConfig config, RunManager runManager, Gson gson) {
        this.config = config;
        this.runManager = runManager;
        this.gson = gson;
    }

    /**
     * Per-request identity, set by the server before each render. Drives read-only mode and per-owner
     * button visibility. A null session means either open mode (loginRequired false) or not-logged-in.
     */
    public void setSession(AdminSessionManager.Session session, boolean loginRequired) {
        this.loginRequired = loginRequired;
        this.loggedIn = !loginRequired || session != null;
        this.currentUser = session != null ? session.username : null;
        this.currentRole = session != null ? session.role : null;
    }

    private boolean isReadOnly() {
        return loginRequired && !loggedIn;
    }

    /** Open mode or an admin session. */
    private boolean isAdmin() {
        return !loginRequired || UserStore.ROLE_ADMIN.equals(currentRole);
    }

    /** A logged-in admin in roster mode — the only viewer who sees/uses user management. Open mode
     *  deliberately does NOT count: it has no users to manage. */
    private boolean isRosterAdmin() {
        return loginRequired && UserStore.ROLE_ADMIN.equals(currentRole);
    }

    /** Whether the current viewer may mutate/run this script (open mode, admin, or owner). */
    private boolean canModify(ScriptInfo script) {
        if (isReadOnly()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        return script != null && script.owner != null && currentUser != null && script.owner.equals(currentUser);
    }

    /** Ownership check when only a scriptId is on hand (runs/tasks); loads the script to read its owner. */
    private boolean canModifyScriptId(String scriptId) {
        if (isReadOnly()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        if (scriptId == null) {
            return false;
        }
        ScriptInfo info;
        try {
            info = runManager.getScript(scriptId);
        } catch (RuntimeException e) {
            return false;
        }
        return canModify(info);
    }

    /** Ownership check for a run/task, resolved via the run's script owner. */
    private boolean canModifyRunId(String runId) {
        if (isReadOnly()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        RunInfo run = runId != null ? runManager.getRun(runId) : null;
        if (run == null) {
            return false;
        }
        return canModifyScriptId(run.scriptId);
    }

    private String ownerLabel(String owner) {
        return (owner != null && owner.length() > 0) ? escape(owner) : "<span class='dim'>&mdash;</span>";
    }

    private String renderTopNav(String activePage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='top-nav'>");
        sb.append("<a href='/admin' class='top-nav-brand'>TeeBox <span class='dim'>v")
          .append(escape(TeeBoxVersion.get())).append("</span></a>");
        sb.append("<div class='top-nav-links'>");
        sb.append("<a href='/admin' class='top-nav-link").append("dashboard".equals(activePage) ? " active" : "").append("'>Dashboard</a>");
        sb.append("<a href='/admin/scripts' class='top-nav-link").append("scripts".equals(activePage) ? " active" : "").append("'>Scripts</a>");
        sb.append("<a href='/admin/runs' class='top-nav-link").append("runs".equals(activePage) ? " active" : "").append("'>Runs</a>");
        // Admin-only user management — shown only to a logged-in admin in roster mode (open mode has
        // no users; the server gates the routes the same way, this is display only).
        if (isRosterAdmin()) {
            sb.append("<a href='/admin/users' class='top-nav-link").append("users".equals(activePage) ? " active" : "").append("'>Users</a>");
        }
        sb.append("</div>");
        sb.append("<div class='top-nav-meta' id='nav-counts'>");
        sb.append("<span class='tag tag-nav'>active ").append(runManager.getActiveCount()).append("</span> ");
        sb.append("<span class='tag tag-nav'>queued ").append(runManager.getQueuedCount()).append("</span>");
        sb.append("</div>");
        sb.append("<label class='auto-toggle'><input type='checkbox' id='auto-refresh-toggle'/> Auto-refresh</label>");
        if (loginRequired) {
            if (loggedIn) {
                if (currentUser != null) {
                    sb.append("<span class='dim' style='margin-left:8px;font-size:11px;'>")
                      .append(escape(currentUser));
                    if (UserStore.ROLE_ADMIN.equals(currentRole)) {
                        sb.append(" <span class='tag tag-nav'>admin</span>");
                    }
                    sb.append("</span>");
                }
                sb.append("<form method='post' action='/admin/logout' style='margin-left:8px;display:inline;'>");
                sb.append("<button type='submit' class='btn btn-sm' style='font-size:11px;'>Logout</button></form>");
            } else {
                sb.append("<a href='/admin/login' class='btn btn-sm' style='margin-left:8px;font-size:11px;'>Login</a>");
            }
        }
        sb.append("</div>");
        return sb.toString();
    }

    public String renderIndexPage() {
        List<RunInfo> running = runManager.listRuns("RUNNING", 0, -1);
        List<RunInfo> queued = runManager.listRuns("QUEUED", 0, -1);
        List<RunInfo> pending = runManager.listRuns("PENDING", 0, -1);
        List<RunInfo> activeRuns = new java.util.ArrayList<RunInfo>(running);
        activeRuns.addAll(queued);
        activeRuns.addAll(pending);
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("TeeBox Admin"));
        sb.append(renderTopNav("dashboard"));

        // Drain banner
        if (runManager.isDraining()) {
            sb.append("<div class='card' style='background:#fef3c7;border-color:#f59e0b;'>");
            sb.append("<h2 style='color:#92400e;'>Draining for Shutdown</h2>");
            sb.append("<p>Server is waiting for in-flight runs to complete. New runs are rejected.</p>");
            sb.append("<p><span class='mono'>active=").append(runManager.getActiveCount()).append("</span> ");
            sb.append("<span class='mono'>queued=").append(runManager.getQueuedCount()).append("</span></p>");
            sb.append("</div>");
        }

        // Shutdown card (admin only)
        if (isAdmin()) {
            sb.append("<div class='card'>");
            sb.append("<div class='card-header'><h2>Server Control</h2></div>");
            if (runManager.isDraining()) {
                sb.append("<p class='dim'>Drain in progress — shutdown when all runs complete.</p>");
            } else {
                sb.append("<form method='post' action='/admin/shutdown' style='display:inline;' onsubmit='return confirm(\"Drain and shut down server?\\nNew runs will be rejected. Existing runs will complete first.\")'>");
                sb.append("<button type='submit' class='btn-danger btn-sm'>Graceful Shutdown</button></form>");
                sb.append(" <span class='dim' style='font-size:12px;'>Rejects new runs, waits for queue to drain, then exits</span>");
            }
            sb.append("</div>");
        }

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>Active Runs</h2>");
        sb.append("<div class='card-actions'>");
        sb.append("<a href='/admin/runs' class='link-subtle'>View All Runs</a> ");
        sb.append("<button class='btn-refresh' onclick='refreshRuns()'>Refresh</button>");
        sb.append("</div></div>");
        sb.append("<div id='dashboard-runs-content'>");
        sb.append(renderRunsTableFragment(activeRuns));
        sb.append("</div>");
        sb.append("</div>");

        SystemInfo sysInfo = runManager.getSystemInfo();
        if (sysInfo != null) {
            sb.append("<div class='card'>");
            sb.append("<div class='card-header'><h2>System Info</h2>");
            sb.append("<div class='card-actions'><a href='/api/admin/system' class='link-subtle'>JSON</a> ");
            sb.append("<button class='btn-refresh' onclick='refreshSystemInfo()'>Refresh</button>");
            sb.append("</div></div>");
            sb.append("<div id='dashboard-sysinfo-content'>");
            sb.append(renderSystemInfoFragment());
            sb.append("</div>");
            sb.append("</div>");
        }

        sb.append("<script>");
        sb.append("(function(){");
        sb.append("function fetchFragment(url,targetId){");
        sb.append("var xhr=new XMLHttpRequest();");
        sb.append("xhr.open('GET',url,true);");
        sb.append("xhr.onreadystatechange=function(){");
        sb.append("if(xhr.readyState===4&&xhr.status===200){");
        sb.append("var el=document.getElementById(targetId);");
        sb.append("if(el)el.innerHTML=xhr.responseText;");
        sb.append("}};xhr.send();}");
        sb.append("window.refreshRuns=function(){");
        sb.append("fetchFragment('/admin/fragments/dashboard-runs','dashboard-runs-content');");
        sb.append("fetchFragment('/admin/fragments/nav-counts','nav-counts');");
        sb.append("};");
        sb.append("window.refreshSystemInfo=function(){");
        sb.append("fetchFragment('/admin/fragments/dashboard-sysinfo','dashboard-sysinfo-content');");
        sb.append("};");
        sb.append("window.refreshPage=function(){refreshRuns();refreshSystemInfo();};");
        sb.append("})();");
        sb.append("</script>");
        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderRunsTableFragment(List<RunInfo> runs) {
        if (runs.isEmpty()) {
            return "<p class='empty'>No runs</p>";
        }
        // One task-index pass for every row's task count + killed/lost badges. Fetching each run's
        // tasks per row cost a full index query per run — and a disk read per archived task.
        List<String> runIds = new ArrayList<String>();
        for (RunInfo run : runs) {
            runIds.add(run.runId);
        }
        java.util.Map<String, List<String>> taskStatusesByRun = runManager.getTaskStatusesByRun(runIds);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='table-wrap'><table><thead><tr><th>Run ID</th><th>Script</th><th>By</th><th>Status</th><th>Created</th><th>Duration</th><th>Threads</th><th>Tasks</th></tr></thead><tbody>");
        for (RunInfo run : runs) {
            sb.append("<tr>");
            sb.append("<td><a href='/admin/runs/").append(urlPath(run.runId)).append("' class='mono'>").append(escape(run.runId)).append("</a>");
            if (run.archived) {
                sb.append(" <span class='dim'>[archived]</span>");
            }
            sb.append("</td>");
            sb.append("<td class='mono'>").append(escape(run.scriptId != null ? run.scriptId : "")).append(run.version != null ? " <span class='dim'>@" + escape(run.version) + "</span>" : "");
            if (run.immediate) {
                sb.append(" <span class='tag' title='Instant run (immediate script — bypasses the global queue)'>instant</span>");
            }
            sb.append("</td>");
            // Submitter identity (X-TeeBox-User header on API submits / admin-UI session username).
            sb.append("<td class='mono'>");
            if (run.submittedBy != null && run.submittedBy.length() > 0) {
                sb.append(escape(run.submittedBy));
            } else {
                sb.append("<span class='dim'>&mdash;</span>");
            }
            sb.append("</td>");
            List<String> taskStatuses = taskStatusesByRun.get(run.runId);
            if (taskStatuses == null) {
                taskStatuses = java.util.Collections.emptyList();
            }
            sb.append("<td>").append(renderRunStatusWithTaskWarnings(run, taskStatuses)).append("</td>");
            sb.append("<td class='dim'>").append(escape(formatTime(run.createdAt))).append("</td>");
            sb.append("<td class='dim'>").append(formatDuration(run.startedAt, run.endedAt)).append("</td>");
            sb.append("<td class='center'>").append(run.threads != null ? run.threads.size() : 0).append("</td>");
            sb.append("<td class='center'>").append(taskStatuses.size()).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    public String renderSystemInfoFragment() {
        SystemInfo info = runManager.getSystemInfo();
        if (info == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        sb.append("<div class='sys-section'><div class='sys-section-title'>JVM</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Java</div><div class='detail-value'>").append(escape(info.javaVersion)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Vendor</div><div class='detail-value'>").append(escape(info.javaVendor)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>OS</div><div class='detail-value'>").append(escape(info.osName)).append(" ").append(escape(info.osArch)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>CPUs</div><div class='detail-value'>").append(info.availableProcessors).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Uptime</div><div class='detail-value'>").append(formatUptime(info.uptimeMs)).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>Memory</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Heap</div><div class='detail-value'>")
            .append(formatBytes(info.heapUsed)).append(" / ").append(formatBytes(info.heapMax))
            .append("</div>").append(renderUsageBar(info.heapUsed, info.heapMax)).append("</div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Non-Heap</div><div class='detail-value'>")
            .append(formatBytes(info.nonHeapUsed)).append(" / ").append(formatBytes(info.nonHeapCommitted))
            .append("</div></div>");
        sb.append("</div></div>");

        long diskUsed = info.diskTotal - info.diskFree;
        sb.append("<div class='sys-section'><div class='sys-section-title'>Disk</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Partition</div><div class='detail-value'>")
            .append(formatBytes(diskUsed)).append(" used / ").append(formatBytes(info.diskTotal)).append(" total")
            .append("</div>").append(renderUsageBar(diskUsed, info.diskTotal)).append("</div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Free</div><div class='detail-value'>")
            .append(formatBytes(info.diskFree)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Usable</div><div class='detail-value'>")
            .append(formatBytes(info.diskUsable)).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>Data Directories</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>runs/</div><div class='detail-value'>").append(formatBytes(info.runsDirSize)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>tasks/</div><div class='detail-value'>").append(formatBytes(info.tasksDirSize)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>script-registry/</div><div class='detail-value'>").append(formatBytes(info.scriptRegistryDirSize)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Total</div><div class='detail-value'>").append(formatBytes(info.totalDataSize)).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>Configuration</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>dataDir</div><div class='detail-value'><code>").append(escape(info.dataDirPath)).append("</code></div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Bind</div><div class='detail-value'>").append(escape(info.bindAddress)).append(":").append(info.port).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Max Concurrent Runs</div><div class='detail-value'>").append(info.maxConcurrentRuns).append("</div></div>");
        sb.append("</div></div>");

        return sb.toString();
    }



    public String renderNavCountsFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("<span class='tag tag-nav'>active ").append(runManager.getActiveCount()).append("</span> ");
        sb.append("<span class='tag tag-nav'>queued ").append(runManager.getQueuedCount()).append("</span>");
        return sb.toString();
    }

    public String renderRunsPage() {
        int pageSize = DEFAULT_RUNS_PAGE_SIZE;
        // Default view excludes instant runs (immediate scripts tend to be high-frequency and would
        // drown the list) — matches the Instant select's initial "exclude" value below.
        int totalCount = runManager.countRuns(null, Boolean.FALSE, null);
        List<RunInfo> runs = runManager.listRuns(null, Boolean.FALSE, null, 0, pageSize);
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Runs - TeeBox Admin"));
        sb.append(renderTopNav("runs"));

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>All Runs</h2>");
        sb.append("<div class='card-actions'>");
        sb.append("<button class='btn-refresh' onclick='refreshRunsPage()'>Refresh</button>");
        sb.append("</div></div>");
        sb.append("<div class='filter-bar'>");
        sb.append("<label style='font-size:13px;color:#64748b;'>Status:</label>");
        sb.append("<select id='status-filter' onchange='filterRuns()'>");
        sb.append("<option value=''>All</option>");
        sb.append("<option value='QUEUED'>QUEUED</option>");
        sb.append("<option value='PENDING'>PENDING</option>");
        sb.append("<option value='RUNNING'>RUNNING</option>");
        sb.append("<option value='COMPLETED'>COMPLETED</option>");
        sb.append("<option value='FAILED'>FAILED</option>");
        sb.append("<option value='CANCELLED'>CANCELLED</option>");
        sb.append("<option value='SERVER_RESTARTED'>SERVER_RESTARTED</option>");
        sb.append("</select>");
        // Unchecked (default) = hide instant runs; checked = include them. (The fragment/API still
        // accept instant=only for API consumers — the UI just doesn't surface it.)
        sb.append("<label class='checkbox-label' style='padding-bottom:0;font-size:13px;color:#64748b;'>");
        sb.append("<input type='checkbox' id='instant-filter' onchange='filterRuns()'/> Include instant</label>");
        sb.append("<input type='text' id='runs-search' placeholder='Search script name / run ID' ");
        sb.append("oninput='searchRuns()' style='flex:1;min-width:180px;padding:6px 10px;border:1px solid #cbd5e1;border-radius:6px;font-size:13px;'/>");
        sb.append("</div>");
        sb.append("<div id='runs-table-content'>");
        sb.append(renderRunsTableWithPagination(runs, 1, pageSize, totalCount));
        sb.append("</div>");
        sb.append("</div>");

        sb.append("<script>");
        sb.append("(function(){");
        sb.append("var currentPage=1;");
        sb.append("var searchTimer=null;");
        sb.append("function fetchFragment(url,targetId){");
        sb.append("var xhr=new XMLHttpRequest();");
        sb.append("xhr.open('GET',url,true);");
        sb.append("xhr.onreadystatechange=function(){");
        sb.append("if(xhr.readyState===4&&xhr.status===200){");
        sb.append("var el=document.getElementById(targetId);");
        sb.append("if(el)el.innerHTML=xhr.responseText;");
        sb.append("}};xhr.send();}");
        sb.append("window.goToPage=function(p){currentPage=p;refreshRunsPage();};");
        sb.append("window.refreshRunsPage=function(){");
        sb.append("var status=document.getElementById('status-filter').value;");
        sb.append("var includeInstant=document.getElementById('instant-filter').checked;");
        sb.append("var q=document.getElementById('runs-search').value.trim();");
        sb.append("var url='/admin/fragments/all-runs?page='+currentPage;");
        sb.append("if(status)url+='&status='+encodeURIComponent(status);");
        sb.append("if(!includeInstant)url+='&instant=exclude';");
        sb.append("if(q)url+='&q='+encodeURIComponent(q);");
        sb.append("fetchFragment(url,'runs-table-content');");
        sb.append("fetchFragment('/admin/fragments/nav-counts','nav-counts');");
        sb.append("};");
        sb.append("window.filterRuns=function(){currentPage=1;refreshRunsPage();};");
        // Debounce typing so each keystroke doesn't fire a request; 300ms after the last key.
        sb.append("window.searchRuns=function(){");
        sb.append("if(searchTimer)clearTimeout(searchTimer);");
        sb.append("searchTimer=setTimeout(function(){currentPage=1;refreshRunsPage();},300);");
        sb.append("};");
        sb.append("window.refreshPage=window.refreshRunsPage;");
        sb.append("})();");
        sb.append("</script>");
        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderRunsTableWithPagination(List<RunInfo> runs, int page, int pageSize, int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderRunsTableFragment(runs));
        if (totalCount > 0) {
            int totalPages = (totalCount + pageSize - 1) / pageSize;
            int start = (page - 1) * pageSize + 1;
            int end = Math.min(page * pageSize, totalCount);
            sb.append("<div class='pagination'>");
            sb.append("<span class='pagination-info'>").append(start).append("-").append(end);
            sb.append(" of ").append(totalCount).append("</span>");
            sb.append("<div class='pagination-controls'>");
            if (page > 1) {
                sb.append("<button class='pagination-btn' onclick='goToPage(1)'>&#171;</button>");
                sb.append("<button class='pagination-btn' onclick='goToPage(").append(page - 1).append(")'>&#8249;</button>");
            } else {
                sb.append("<button class='pagination-btn' disabled>&#171;</button>");
                sb.append("<button class='pagination-btn' disabled>&#8249;</button>");
            }
            int windowStart = Math.max(1, page - 2);
            int windowEnd = Math.min(totalPages, page + 2);
            if (windowEnd - windowStart < 4) {
                windowStart = Math.max(1, windowEnd - 4);
                windowEnd = Math.min(totalPages, windowStart + 4);
            }
            if (windowStart > 1) {
                sb.append("<button class='pagination-btn' onclick='goToPage(1)'>1</button>");
                if (windowStart > 2) {
                    sb.append("<span class='pagination-ellipsis'>...</span>");
                }
            }
            for (int i = windowStart; i <= windowEnd; i++) {
                if (i == page) {
                    sb.append("<button class='pagination-btn pagination-active'>").append(i).append("</button>");
                } else {
                    sb.append("<button class='pagination-btn' onclick='goToPage(").append(i).append(")'>").append(i).append("</button>");
                }
            }
            if (windowEnd < totalPages) {
                if (windowEnd < totalPages - 1) {
                    sb.append("<span class='pagination-ellipsis'>...</span>");
                }
                sb.append("<button class='pagination-btn' onclick='goToPage(").append(totalPages).append(")'>").append(totalPages).append("</button>");
            }
            if (page < totalPages) {
                sb.append("<button class='pagination-btn' onclick='goToPage(").append(page + 1).append(")'>&#8250;</button>");
                sb.append("<button class='pagination-btn' onclick='goToPage(").append(totalPages).append(")'>&#187;</button>");
            } else {
                sb.append("<button class='pagination-btn' disabled>&#8250;</button>");
                sb.append("<button class='pagination-btn' disabled>&#187;</button>");
            }
            sb.append("</div></div>");
        }
        return sb.toString();
    }

    public String renderRunPage(String runId, boolean killRequested) {
        return renderRunPage(runId, killRequested, false);
    }

    public String renderRunPage(String runId, boolean killRequested, boolean cancelRequested) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return renderErrorPage("Run not found", runId);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Run " + runId));
        sb.append(renderTopNav(""));

        sb.append("<div class='nav'>");
        sb.append("<a href='/admin'>Dashboard</a>");
        sb.append("<span class='nav-sep'>/</span>");
        sb.append("<span>Run ").append(escape(shortId(runId))).append("</span>");
        sb.append("<span class='nav-sep'>|</span>");
        sb.append("<a href='/api/admin/runs/").append(urlPath(runId)).append("' class='link-subtle'>JSON</a>");
        sb.append("</div>");

        if (killRequested) {
            sb.append(killRequestedCallout());
        }
        if (cancelRequested) {
            sb.append(cancelRequestedCallout());
        }

        sb.append("<div id='run-detail-content'>");
        sb.append(renderRunDetailFragment(runId));
        sb.append("</div>");

        sb.append("<script>");
        sb.append("(function(){");
        // Save scroll state of all .task-out elements before refresh
        sb.append("function saveScrollState(){");
        sb.append("var state={};");
        sb.append("var els=document.querySelectorAll('.task-out');");
        sb.append("for(var i=0;i<els.length;i++){");
        sb.append("var el=els[i];if(!el.id)continue;");
        sb.append("var atBottom=el.scrollHeight-el.scrollTop-el.clientHeight<8;");
        sb.append("state[el.id]={top:el.scrollTop,atBottom:atBottom};");
        sb.append("}return state;}");
        // Restore scroll state after refresh
        sb.append("function restoreScrollState(state){");
        sb.append("var els=document.querySelectorAll('.task-out');");
        sb.append("for(var i=0;i<els.length;i++){");
        sb.append("var el=els[i];if(!el.id||!state[el.id])continue;");
        sb.append("if(state[el.id].atBottom){el.scrollTop=el.scrollHeight;}");
        sb.append("else{el.scrollTop=state[el.id].top;}");
        sb.append("}}");
        sb.append("function fetchFragment(url,targetId,cb){");
        sb.append("var xhr=new XMLHttpRequest();");
        sb.append("xhr.open('GET',url,true);");
        sb.append("xhr.onreadystatechange=function(){");
        sb.append("if(xhr.readyState===4&&xhr.status===200){");
        sb.append("var el=document.getElementById(targetId);");
        sb.append("if(el)el.innerHTML=xhr.responseText;");
        sb.append("if(cb)cb();");
        sb.append("}};xhr.send();}");
        sb.append("window.refreshPage=function(){");
        sb.append("var ss=saveScrollState();");
        sb.append("fetchFragment('/admin/fragments/run-detail/").append(urlPath(runId)).append("','run-detail-content',function(){restoreScrollState(ss);});");
        sb.append("fetchFragment('/admin/fragments/nav-counts','nav-counts');");
        sb.append("};");
        sb.append("})();");
        sb.append("</script>");
        sb.append(pageEnd());
        return sb.toString();
    }

    /** One-shot notice after an admin-UI kill POST: the kill runs in the background (the redirect
     *  does not wait for it), so the state shown below may lag until the auto-refresh catches up.
     *  Rendered outside the fragment-replaced region so the 5s refresh doesn't wipe it. */
    private String killRequestedCallout() {
        return "<div class='callout callout-warn'>Kill requested &mdash; terminating in the background. " +
                "Status updates automatically; a kill can take a few seconds.</div>";
    }

    /** One-shot notice after an admin-UI cancel POST: the engine aborts cooperatively and the
     *  task kill runs in the background, so CANCELLED appears when the run unwinds. */
    private String cancelRequestedCallout() {
        return "<div class='callout callout-warn'>Cancel requested &mdash; the run is being aborted. " +
                "Status updates automatically; it becomes CANCELLED once the engine unwinds.</div>";
    }

    public String renderRunDetailFragment(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) return "<p class='empty'>Run not found</p>";
        List<RunThreadInfo> threads = runManager.listThreads(runId);
        List<TaskInfo> tasks = runManager.listTasksForRun(runId);
        StringBuilder sb = new StringBuilder();

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>").append(escape(runId)).append("</h2>");
        if (canModifyScriptId(run.scriptId)) {
            boolean cancellable = run.status == RunStatus.QUEUED || run.status == RunStatus.PENDING
                || run.status == RunStatus.RUNNING;
            if (cancellable) {
                sb.append("<form method='post' action='/admin/runs/").append(urlPath(runId)).append("/cancel' ");
                sb.append("style='display:inline;margin-right:6px'>");
                sb.append("<button type='submit' class='btn-danger btn-sm'>Cancel Run</button></form>");
            }
            sb.append("<form method='post' action='/admin/runs/").append(urlPath(runId)).append("/kill-tasks'>");
            sb.append("<button type='submit' class='btn-danger btn-sm'>Kill All Tasks</button></form>");
        }
        sb.append("</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Script</div><div class='detail-value'><code>").append(escape(run.scriptId != null ? run.scriptId : "")).append(run.version != null ? "@" + escape(run.version) : "").append("</code></div></div>");
        List<String> taskStatuses = new ArrayList<String>();
        for (TaskInfo task : tasks) {
            taskStatuses.add(task.status);
        }
        sb.append("<div class='detail-item'><div class='detail-label'>Status</div><div class='detail-value'>").append(renderRunStatusWithTaskWarnings(run, taskStatuses)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Submitted By</div><div class='detail-value'>");
        if (run.submittedBy != null && run.submittedBy.length() > 0) {
            sb.append("<code>").append(escape(run.submittedBy)).append("</code>");
        } else {
            sb.append("<span class='dim'>&mdash;</span>");
        }
        sb.append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>From (IP)</div><div class='detail-value'>");
        if (run.submittedFrom != null && run.submittedFrom.length() > 0) {
            sb.append("<code>").append(escape(run.submittedFrom)).append("</code>");
        } else {
            sb.append("<span class='dim'>&mdash;</span>");
        }
        sb.append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Archived</div><div class='detail-value'>").append(run.archived ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Created</div><div class='detail-value dim'>").append(escape(formatTime(run.createdAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Started</div><div class='detail-value dim'>").append(escape(formatTime(run.startedAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Ended</div><div class='detail-value dim'>").append(escape(formatTime(run.endedAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Duration</div><div class='detail-value'>").append(formatDuration(run.startedAt, run.endedAt)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Explicit Return</div><div class='detail-value'>").append(run.hasExplicitReturn ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='card'><h2>Input Properties</h2>");
        if (run.properties != null && !run.properties.isEmpty()) {
            sb.append("<pre>").append(escape(gson.toJson(run.properties))).append("</pre>");
        } else {
            sb.append("<p class='empty'>No properties</p>");
        }
        sb.append("</div>");

        if (run.errorMessage != null && run.errorMessage.length() > 0) {
            sb.append("<div class='card'><h2>Error</h2><pre>").append(escape(run.errorMessage)).append("</pre></div>");
        }
        if (run.resultData != null) {
            sb.append("<div class='card'><h2>Result</h2><pre>").append(escape(gson.toJson(run.resultData))).append("</pre></div>");
        } else if (run.resultSummary != null && run.resultSummary.length() > 0) {
            sb.append("<div class='card'><h2>Result</h2><pre>").append(escape(run.resultSummary)).append("</pre></div>");
        }

        boolean hasPublished = run.published != null && !run.published.isEmpty();
        boolean hasOutputRules = runManager.getOutputRulesForScript(run.scriptId, run.version) != null;
        if (hasPublished || hasOutputRules) {
            sb.append("<div class='card'><h2>Published</h2>");
            if (hasPublished) {
                // One row per base key; the .values/.count/.detectedAt companion entries are
                // folded into the row (latest value + capture count + last capture time).
                sb.append("<table class='data-table'><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>");
                for (java.util.Map.Entry<String, Object> entry : run.published.entrySet()) {
                    String key = entry.getKey();
                    if (key.endsWith(".detectedAt") || key.endsWith(".values") || key.endsWith(".count")) continue;
                    sb.append("<tr><td class='mono'>").append(escape(key)).append("</td>");
                    sb.append("<td class='mono'>").append(escape(entry.getValue()));
                    Object count = run.published.get(key + ".count");
                    Object detectedAt = run.published.get(key + ".detectedAt");
                    sb.append(" <span class='dim'>(");
                    if (count instanceof Number) {
                        sb.append("captures: ").append(((Number) count).longValue());
                        if (detectedAt instanceof Number) sb.append(", ");
                    }
                    if (detectedAt instanceof Number) {
                        sb.append("last at ").append(formatTime(((Number) detectedAt).longValue()));
                    }
                    sb.append(")</span>");
                    Object values = run.published.get(key + ".values");
                    if (values instanceof java.util.List && ((java.util.List<?>) values).size() > 1) {
                        sb.append("<br/><span class='dim'>values: ").append(escape(values)).append("</span>");
                    }
                    sb.append("</td></tr>");
                }
                sb.append("</tbody></table>");
            } else {
                sb.append("<p class='empty'>No captures yet</p>");
            }
            sb.append("</div>");
        }

        sb.append("<div class='card'><h2>Threads (").append(threads.size()).append(")</h2>");
        sb.append(renderThreadTable(threads));
        sb.append("</div>");

        sb.append("<div class='card'><h2>Tasks (").append(tasks.size()).append(")</h2>");
        sb.append(renderTaskTable(tasks, true));
        sb.append("</div>");

        String stdout = joinLines(run.stdoutLines);
        String stderr = joinLines(run.stderrLines);
        sb.append("<div class='card'><h2>Script Output</h2>");
        if (stdout.length() > 0) {
            sb.append("<pre>").append(escape(stdout)).append("</pre>");
        } else {
            sb.append("<p class='empty'>No output</p>");
        }
        sb.append("</div>");
        if (stderr.length() > 0) {
            sb.append("<div class='card'><h2>Script Errors</h2><pre>").append(escape(stderr)).append("</pre></div>");
        }

        if (!tasks.isEmpty()) {
            sb.append("<div class='card'><h2>Task Output</h2>");
            boolean anyOutput = false;
            int taskIdx = 0;
            for (TaskInfo task : tasks) {
                String taskStdout = tailLines(nullToEmpty(runManager.getTaskStdoutTail(task.taskId, DEFAULT_TAIL_BYTES)), DEFAULT_TAIL_LINES);
                String taskStderr = tailLines(nullToEmpty(runManager.getTaskStderrTail(task.taskId, DEFAULT_TAIL_BYTES)), DEFAULT_TAIL_LINES);
                if (taskStdout.length() > 0 || taskStderr.length() > 0) {
                    anyOutput = true;
                    sb.append("<div class='task-output-block'>");
                    sb.append("<div class='task-output-label mono'>").append(escape(shortId(task.taskId)));
                    if (task.threadName != null) {
                        sb.append(" <span class='dim'>").append(escape(task.threadName)).append("</span>");
                    }
                    sb.append(" ").append(statusBadge(task.status));
                    sb.append("</div>");
                    if (taskStdout.length() > 0) {
                        sb.append("<pre class='task-out' id='task-out-").append(taskIdx).append("'>").append(escape(taskStdout)).append("</pre>");
                    }
                    if (taskStderr.length() > 0) {
                        sb.append("<pre class='task-out' style='border-left:3px solid #fca5a5;'>").append(escape(taskStderr)).append("</pre>");
                    }
                    sb.append("</div>");
                }
                taskIdx++;
            }
            if (!anyOutput) {
                sb.append("<p class='empty'>No task output</p>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    public String renderTaskPage(String taskId, boolean killRequested) {
        TaskInfo info = runManager.getTask(taskId);
        if (info == null) {
            return renderErrorPage("Task not found", taskId);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Task " + taskId));
        sb.append(renderTopNav(""));

        sb.append("<div class='nav'>");
        sb.append("<a href='/admin'>Dashboard</a>");
        if (info.runId != null) {
            sb.append("<span class='nav-sep'>/</span>");
            sb.append("<a href='/admin/runs/").append(urlPath(info.runId)).append("'>Run ").append(escape(shortId(info.runId))).append("</a>");
        }
        sb.append("<span class='nav-sep'>/</span>");
        sb.append("<span>Task ").append(escape(shortId(taskId))).append("</span>");
        sb.append("<span class='nav-sep'>|</span>");
        sb.append("<a href='/api/admin/tasks/").append(urlPath(taskId)).append("' class='link-subtle'>JSON</a>");
        sb.append("</div>");

        if (killRequested) {
            sb.append(killRequestedCallout());
        }

        sb.append("<div id='task-detail-content'>");
        sb.append(renderTaskDetailFragment(taskId));
        sb.append("</div>");

        sb.append("<script>");
        sb.append("(function(){");
        sb.append("function saveScrollState(){");
        sb.append("var state={};var els=document.querySelectorAll('.task-out');");
        sb.append("for(var i=0;i<els.length;i++){var el=els[i];if(!el.id)continue;");
        sb.append("var atBottom=el.scrollHeight-el.scrollTop-el.clientHeight<8;");
        sb.append("state[el.id]={top:el.scrollTop,atBottom:atBottom};}return state;}");
        sb.append("function restoreScrollState(state){");
        sb.append("var els=document.querySelectorAll('.task-out');");
        sb.append("for(var i=0;i<els.length;i++){var el=els[i];if(!el.id||!state[el.id])continue;");
        sb.append("if(state[el.id].atBottom){el.scrollTop=el.scrollHeight;}");
        sb.append("else{el.scrollTop=state[el.id].top;}}}");
        sb.append("function fetchFragment(url,targetId,cb){");
        sb.append("var xhr=new XMLHttpRequest();");
        sb.append("xhr.open('GET',url,true);");
        sb.append("xhr.onreadystatechange=function(){");
        sb.append("if(xhr.readyState===4&&xhr.status===200){");
        sb.append("var el=document.getElementById(targetId);");
        sb.append("if(el)el.innerHTML=xhr.responseText;");
        sb.append("if(cb)cb();");
        sb.append("}};xhr.send();}");
        sb.append("window.refreshPage=function(){");
        sb.append("var ss=saveScrollState();");
        sb.append("fetchFragment('/admin/fragments/task-detail/").append(urlPath(taskId)).append("','task-detail-content',function(){restoreScrollState(ss);});");
        sb.append("fetchFragment('/admin/fragments/nav-counts','nav-counts');");
        sb.append("};");
        sb.append("})();");
        sb.append("</script>");
        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderTaskDetailFragment(String taskId) {
        TaskInfo info = runManager.getTask(taskId);
        if (info == null) return "<p class='empty'>Task not found</p>";
        TaskObservation obs = runManager.observeTask(taskId);
        StringBuilder sb = new StringBuilder();

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>").append(escape(taskId)).append("</h2>");
        if (canModifyRunId(info.runId)) {
            sb.append("<form method='post' action='/admin/tasks/").append(urlPath(taskId)).append("/kill'>");
            sb.append("<button type='submit' class='btn-danger btn-sm'>Kill Task</button></form>");
        }
        sb.append("</div>");
        if (info.timeoutExceeded) {
            sb.append("<div class='callout callout-warn'>Task exceeded its configured timeout. This is a warning only; automatic kill is not performed.</div>");
        }
        if (info.healthHints != null && !info.healthHints.isEmpty()) {
            sb.append("<div class='callout'>Health hints: ").append(escape(joinComma(info.healthHints))).append("</div>");
        }
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Status</div><div class='detail-value'>").append(statusBadge(info.status)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Archived</div><div class='detail-value'>").append(info.archived ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Run ID</div><div class='detail-value mono'>").append(escape(info.runId)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Thread</div><div class='detail-value'>").append(escape(info.threadName)).append(" <span class='dim'>#").append(escape(info.threadId)).append("</span></div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>PID</div><div class='detail-value mono'>").append(info.pid).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Alive</div><div class='detail-value'>").append(info.alive ? statusBadge("RUNNING") : statusBadge("DONE")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Timeout Exceeded</div><div class='detail-value'>").append(info.timeoutExceeded ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Last Output Age</div><div class='detail-value'>").append(formatNullableElapsed(info.lastOutputAgeMs)).append("</div></div>");
        if (info instanceof TeeBoxTaskInfo) {
            TeeBoxTaskInfo tbInfo = (TeeBoxTaskInfo) info;
            if (tbInfo.phase != null) {
                sb.append("<div class='detail-item'><div class='detail-label'>Phase</div><div class='detail-value'>").append(statusBadge(tbInfo.phase)).append("</div></div>");
            }
            if (tbInfo.lossReason != null) {
                sb.append("<div class='detail-item'><div class='detail-label'>Loss Reason</div><div class='detail-value'>").append(statusBadge(tbInfo.lossReason)).append("</div></div>");
            }
        }
        sb.append("</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>CWD</div><div class='detail-value'><code>").append(escape(info.cwd)).append("</code></div></div>");
        sb.append("</div>");
        sb.append("<div class='detail-item' style='margin-top:12px'><div class='detail-label'>Command</div></div>");
        sb.append("<pre>").append(escape(info.command)).append("</pre>");
        sb.append("</div>");

        if (obs != null) {
            sb.append("<div class='card'><h2>Observation</h2><pre>").append(escape(gson.toJson(obs))).append("</pre></div>");
        }

        String taskStdout = tailLines(nullToEmpty(runManager.getTaskStdoutTail(taskId, DEFAULT_TAIL_BYTES)), DEFAULT_TAIL_LINES);
        String taskStderr = tailLines(nullToEmpty(runManager.getTaskStderrTail(taskId, DEFAULT_TAIL_BYTES)), DEFAULT_TAIL_LINES);
        if (taskStdout.length() > 0) {
            sb.append("<div class='card'><h2>Stdout</h2><pre class='task-out' id='task-stdout'>").append(escape(taskStdout)).append("</pre></div>");
        }
        if (taskStderr.length() > 0) {
            sb.append("<div class='card'><h2>Stderr</h2><pre>").append(escape(taskStderr)).append("</pre></div>");
        }
        return sb.toString();
    }

    public String renderScriptsPage() {
        List<ScriptInfo> allScripts = runManager.listScripts(true);
        List<ScriptInfo> scripts = new ArrayList<ScriptInfo>();
        List<ScriptInfo> deletedScripts = new ArrayList<ScriptInfo>();
        for (ScriptInfo s : allScripts) {
            if (s.deletedAt > 0) deletedScripts.add(s);
            else scripts.add(s);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Scripts - TeeBox Admin"));
        sb.append(renderTopNav("scripts"));

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>Registered Scripts (").append(scripts.size()).append(")</h2>");
        if (!isReadOnly()) {
            sb.append("<div class='card-actions'><button class='btn btn-sm' onclick='document.getElementById(\"register-modal\").style.display=\"flex\"'>Register Script</button></div>");
        }
        sb.append("</div>");
        if (scripts.isEmpty()) {
            sb.append("<p class='empty'>No scripts registered</p>");
        } else {
            sb.append("<div class='table-wrap'><table><thead><tr>");
            sb.append("<th>Script ID</th><th>Owner</th><th>Active Version</th><th>Versions</th><th>Created</th><th>Updated</th><th></th>");
            sb.append("</tr></thead><tbody>");
            for (ScriptInfo script : scripts) {
                sb.append("<tr>");
                sb.append("<td><a href='/admin/scripts/").append(urlPath(script.scriptId)).append("' class='mono'>").append(escape(script.scriptId)).append("</a>");
                // immediate is a script-level setting, so the tag sits on the script id (shown even
                // when no version is active yet — the setting applies as soon as one is).
                if (script.immediate) {
                    sb.append(" <span class='tag' title='Instant run (immediate script — bypasses the global queue)'>instant</span>");
                }
                sb.append("</td>");
                sb.append("<td class='dim'>").append(ownerLabel(script.owner)).append("</td>");
                sb.append("<td>");
                if (script.activeVersion != null && script.activeVersion.length() > 0) {
                    sb.append(statusBadge(script.activeVersion));
                } else {
                    sb.append("<span class='dim'>&mdash;</span>");
                }
                sb.append("</td>");
                sb.append("<td class='center'>").append(script.versions.size()).append("</td>");
                sb.append("<td class='dim'>").append(escape(formatTime(script.createdAt))).append("</td>");
                sb.append("<td class='dim'>").append(escape(formatTime(script.updatedAt))).append("</td>");
                if (canModify(script)) {
                    sb.append("<td style='white-space:nowrap;'>");
                    sb.append("<form method='post' action='/admin/scripts/delete/").append(urlPath(script.scriptId)).append("' style='display:inline;' onsubmit='return confirm(\"Delete script ").append(escape(script.scriptId).replace("'", "\\'")).append("?\")'>");
                    sb.append("<button type='submit' class='btn-danger btn-sm'>Delete</button></form>");
                    sb.append("</td>");
                } else {
                    sb.append("<td></td>");
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div>");

        // Deleted scripts section
        if (!deletedScripts.isEmpty()) {
            sb.append("<div class='card'>");
            sb.append("<div class='card-header'><h2>Deleted Scripts (").append(deletedScripts.size()).append(")</h2>");
            sb.append("<span class='dim' style='font-size:12px;'>Scheduled for permanent removal</span></div>");
            sb.append("<div class='table-wrap'><table><thead><tr>");
            sb.append("<th>Script ID</th><th>Owner</th><th>Deleted At</th><th></th>");
            sb.append("</tr></thead><tbody>");
            for (ScriptInfo script : deletedScripts) {
                sb.append("<tr>");
                sb.append("<td><span class='mono dim'>").append(escape(script.scriptId)).append("</span></td>");
                sb.append("<td class='dim'>").append(ownerLabel(script.owner)).append("</td>");
                sb.append("<td class='dim'>").append(escape(formatTime(script.deletedAt))).append("</td>");
                if (canModify(script)) {
                    sb.append("<td style='white-space:nowrap;'>");
                    sb.append("<form method='post' action='/admin/scripts/restore/").append(urlPath(script.scriptId)).append("' style='display:inline;'>");
                    sb.append("<button type='submit' class='btn btn-sm'>Restore</button></form>");
                    sb.append("</td>");
                } else {
                    sb.append("<td></td>");
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div></div>");
        }

        // Register Script modal
        sb.append("<div id='register-modal' class='modal-overlay' style='display:none'>");
        sb.append("<div class='modal-content'>");
        sb.append("<div class='card-header'><h2>Register Script</h2>");
        sb.append("<button class='btn-refresh' onclick='document.getElementById(\"register-modal\").style.display=\"none\"'>Close</button></div>");
        // First registration is metadata-only: it creates an empty script (no version, not active). The
        // code — and activation — happen afterward on the script detail page, so runs never serve an
        // untested first version. No editor / file upload here on purpose.
        sb.append("<form method='post' action='/admin/scripts/register' class='form-grid' id='register-form'>");
        sb.append("<div class='form-row'><label>Script ID</label><input type='text' name='scriptId' placeholder='calc_sum' required autofocus/></div>");
        sb.append("<p class='dim' style='font-size:12px;margin:-4px 0 0;'>Creates an empty script. You'll add the code and activate a version on the next page.</p>");
        sb.append("<div class='form-row-inline'><button type='submit'>Register</button></div>");
        sb.append("</form>");
        sb.append("</div></div>");

        sb.append("<script>");
        sb.append("document.getElementById('register-modal').addEventListener('click',function(e){");
        sb.append("if(e.target===this)this.style.display='none';");
        sb.append("});");
        sb.append("</script>");

        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderScriptPage(String scriptId) {
        return renderScriptPage(scriptId, null);
    }

    public String renderScriptPage(String scriptId, String selectedVersionParam) {
        ScriptInfo script = runManager.getScript(scriptId);
        if (script == null) {
            return renderErrorPage("Script not found", scriptId);
        }
        // The source editor targets the requested version when it exists, else the active one, else the
        // latest version (a script may now have versions but no active one — activation is explicit), else
        // null for a freshly-registered shell with no versions yet. This lets the Versions list open any
        // version (including inactive ones) for editing, not just the active.
        String selectedVersion;
        if (hasVersion(script, selectedVersionParam)) {
            selectedVersion = selectedVersionParam;
        } else if (script.activeVersion != null && script.activeVersion.length() > 0) {
            selectedVersion = script.activeVersion;
        } else if (!script.versions.isEmpty()) {
            selectedVersion = script.versions.get(0).version; // newest first (sortVersions is desc)
        } else {
            selectedVersion = null; // shell — no versions yet
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Script " + scriptId));
        sb.append(renderTopNav("scripts"));

        sb.append("<div class='nav'>");
        sb.append("<a href='/admin/scripts'>Scripts</a>");
        sb.append("<span class='nav-sep'>/</span>");
        sb.append("<span>").append(escape(scriptId)).append("</span>");
        sb.append("<span class='nav-sep'>|</span>");
        sb.append("<a href='/api/publisher/scripts/").append(urlPath(scriptId)).append("' class='link-subtle'>JSON</a>");
        sb.append("</div>");

        sb.append("<div class='card'>");
        sb.append("<h2>").append(escape(scriptId)).append("</h2>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Script ID</div><div class='detail-value'><code>").append(escape(scriptId)).append("</code></div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Active Version</div><div class='detail-value'>");
        if (script.activeVersion != null && script.activeVersion.length() > 0) {
            sb.append(statusBadge(script.activeVersion));
        } else {
            sb.append("<span class='dim'>&mdash;</span>");
        }
        sb.append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Owner</div><div class='detail-value dim'>").append(ownerLabel(script.owner)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Created</div><div class='detail-value dim'>").append(escape(formatTime(script.createdAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Updated</div><div class='detail-value dim'>").append(escape(formatTime(script.updatedAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Total Versions</div><div class='detail-value'>").append(script.versions.size()).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Script Concurrency Limit</div><div class='detail-value'>").append(script.maxConcurrentRuns > 0 ? script.maxConcurrentRuns : "<span class='dim'>unlimited</span>").append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Immediate</div><div class='detail-value'>").append(script.immediate ? statusBadge("YES") + " <span class='dim'>(bypass global queue)</span>" : "<span class='dim'>no</span>").append("</div></div>");
        sb.append("</div></div>");

        // Execution settings card (owner or admin)
        if (canModify(script)) {
        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>Execution Settings</h2></div>");
        sb.append("<form method='post' action='/admin/scripts/settings/").append(urlPath(scriptId)).append("' class='form-grid'>");
        sb.append("<div style='display:flex;gap:16px;align-items:end;'>");
        sb.append("<div class='form-row' style='flex:1'><label>Script Concurrency Limit</label>");
        sb.append("<input type='number' name='maxConcurrentRuns' value='").append(script.maxConcurrentRuns).append("' min='0' style='width:80px;'/>");
        sb.append("<span class='dim' style='font-size:11px;margin-left:4px;'>0 = unlimited (this script only)</span></div>");
        sb.append("<div class='form-row'><label class='checkbox-label'><input type='checkbox' name='immediate'");
        if (script.immediate) sb.append(" checked");
        sb.append("/> Immediate (bypass global queue, use separate thread pool)</label></div>");
        sb.append("<div class='form-row'><button type='submit' class='btn btn-sm'>Save</button></div>");
        sb.append("</div></form></div>");
        } // end canModify check for Execution Settings

        sb.append("<div class='card'>");
        sb.append("<h2>Versions (").append(script.versions.size()).append(")</h2>");
        if (script.versions.isEmpty()) {
            sb.append("<p class='empty'>No versions</p>");
        } else {
            sb.append("<div class='table-wrap'><table><thead><tr>");
            sb.append("<th>Version</th><th>Status</th><th>Description</th><th>Labels</th><th>SHA-256</th><th>Created</th>");
            if (canModify(script)) sb.append("<th>Action</th>");
            sb.append("</tr></thead><tbody>");
            for (ScriptVersionInfo version : script.versions) {
                sb.append("<tr>");
                sb.append("<td class='mono'>").append(escape(version.version)).append("</td>");
                sb.append("<td>");
                if (version.active) {
                    sb.append("<span class='badge badge-completed'>ACTIVE</span>");
                } else {
                    sb.append("<span class='dim'>&mdash;</span>");
                }
                sb.append("</td>");
                sb.append("<td>").append(escape(version.description)).append("</td>");
                sb.append("<td>");
                if (version.labels != null && !version.labels.isEmpty()) {
                    for (int i = 0; i < version.labels.size(); i++) {
                        if (i > 0) sb.append(" ");
                        sb.append("<span class='tag'>").append(escape(version.labels.get(i))).append("</span>");
                    }
                } else {
                    sb.append("<span class='dim'>&mdash;</span>");
                }
                sb.append("</td>");
                sb.append("<td class='mono dim'>");
                if (version.sha256 != null && version.sha256.length() > 12) {
                    sb.append(escape(version.sha256.substring(0, 12))).append("...");
                } else {
                    sb.append(escape(version.sha256));
                }
                sb.append("</td>");
                sb.append("<td class='dim'>").append(escape(formatTime(version.createdAt))).append("</td>");
                if (canModify(script)) {
                    sb.append("<td style='white-space:nowrap;'>");
                    boolean editing = version.version != null && version.version.equals(selectedVersion);
                    if (editing) {
                        sb.append("<span class='btn btn-sm' style='background:#334155;cursor:default;' title='Shown in the editor below'>Editing</span>");
                    } else {
                        sb.append("<a class='btn btn-sm' href='/admin/scripts/").append(urlPath(scriptId)).append("?version=").append(urlParam(version.version)).append("#version-source'>Edit</a>");
                    }
                    if (!version.active) {
                        sb.append(" <form method='post' action='/admin/scripts/activate/").append(urlPath(scriptId)).append("' style='display:inline'>");
                        sb.append("<input type='hidden' name='version' value='").append(escape(version.version)).append("'/>");
                        sb.append("<button type='submit' class='btn btn-sm'>Set active</button></form>");
                        // The active version is protected server-side, so only inactive rows offer Delete.
                        sb.append(" <form method='post' action='/admin/scripts/delete-version/").append(urlPath(scriptId)).append("' style='display:inline' onsubmit='return confirm(\"Delete version ").append(escape(scriptId).replace("'", "\\'")).append("@").append(escape(version.version).replace("'", "\\'")).append("? This cannot be undone.\")'>");
                        sb.append("<input type='hidden' name='version' value='").append(escape(version.version)).append("'/>");
                        sb.append("<button type='submit' class='btn-danger btn-sm'>Delete</button></form>");
                    }
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div>");

        // Version Source doubles as the "add a version" surface: editing the source and pressing
        // "Save as new version" posts the editor content to /admin/scripts/register (blank version =>
        // next auto-increment integer). It renders for any modifiable script — including a freshly
        // registered shell with no versions yet (empty editor, "Save as new version" only). Versions
        // never auto-activate, so the card also warns when the script has no active version. The version
        // shown is the one picked from the Versions list (Edit link), else active, else latest.
        boolean canEditSource = canModify(script);
        boolean hasSelected = selectedVersion != null && selectedVersion.length() > 0;
        if (hasSelected || canEditSource) {
            String content = hasSelected ? runManager.getScriptVersionContent(scriptId, selectedVersion) : "";
            if (content == null) content = "";
            boolean selectedIsActive = hasSelected && selectedVersion.equals(script.activeVersion);
            boolean noActive = script.activeVersion == null || script.activeVersion.length() == 0;
            sb.append("<div class='card' id='version-source'>");
            sb.append("<div class='card-header'><h2>");
            if (hasSelected) {
                sb.append("Version Source (").append(escape(selectedVersion)).append(")");
                sb.append(selectedIsActive ? " <span class='badge badge-completed'>ACTIVE</span>" : " <span class='tag'>inactive</span>");
            } else {
                sb.append("New Version Source <span class='tag'>no versions yet</span>");
            }
            sb.append("</h2></div>");
            if (!canEditSource) {
                if (hasSelected) sb.append("<pre>").append(escape(content)).append("</pre>");
            } else {
                if (noActive) {
                    sb.append("<div class='callout'>");
                    sb.append(hasSelected
                        ? "No active version yet &mdash; use <strong>Set active</strong> in the Versions table to make a version runnable."
                        : "This script has no code yet &mdash; write it below and press <strong>Save as new version</strong>, then <strong>Set active</strong> in the Versions table to make it runnable.");
                    sb.append("</div>");
                }
                // Default action = update-source (overwrite the selected version). The "Save" button
                // carries the version via its own name/value; "Save as new version" overrides the action
                // to /admin/scripts/register (no version => auto next #). Only the clicked submit button
                // contributes its name/value, so the two never collide. A shell has no version to
                // overwrite, so it shows only "Save as new version".
                sb.append("<form method='post' action='/admin/scripts/update-source' class='form-grid' id='version-source-form'>");
                sb.append("<input type='hidden' name='scriptId' value='").append(escape(scriptId)).append("'/>");
                sb.append("<textarea name='content' rows='24' class='pt-editor-fallback' data-pt-editor data-pt-panel placeholder='return {\"ok\": true}'>").append(escape(content)).append("</textarea>");
                OutputPublishRule activeRule = hasSelected ? findOutputRuleForVersion(script, selectedVersion) : null;
                sb.append("<details style='margin-top:8px;'");
                if (activeRule != null) sb.append(" open");
                sb.append("><summary style='cursor:pointer;font-size:12px;color:#64748b;'>Output Capture Rule</summary>");
                sb.append("<div style='display:flex;flex-direction:column;gap:8px;margin-top:8px;'>");
                sb.append("<div class='form-row'><label>Regex Pattern</label><input type='text' name='publishPattern' value='").append(activeRule != null ? escape(activeRule.pattern) : "").append("' placeholder='jobid:\\s*(\\S+)' style='font-family:monospace;font-size:12px;'/></div>");
                sb.append("<div class='form-row'><label>Publish Key</label><input type='text' name='publishKey' value='").append(activeRule != null ? escape(activeRule.publishKey) : "").append("' placeholder='jobId'/></div>");
                sb.append("<div style='display:flex;gap:12px;'>");
                sb.append("<div class='form-row' style='flex:1'><label>Capture Group</label><input type='number' name='captureGroup' value='").append(activeRule != null ? activeRule.captureGroup : 1).append("' min='0' style='width:60px;'/></div>");
                sb.append("<div class='form-row' style='flex:1'><label>Stream</label><select name='publishStream'>");
                sb.append("<option value='stdout'").append(activeRule == null || !"stderr".equals(activeRule.stream) ? " selected" : "").append(">stdout</option>");
                sb.append("<option value='stderr'").append(activeRule != null && "stderr".equals(activeRule.stream) ? " selected" : "").append(">stderr</option>");
                sb.append("</select></div></div>");
                sb.append("<div style='display:flex;gap:12px;'>");
                sb.append("<div class='form-row' style='flex:1'><label>Task Index <span class='dim'>(SHELL execution order; 0 = first)</span></label><input type='number' name='taskIndex' value='").append(activeRule != null ? activeRule.taskIndex : 0).append("' min='0' style='width:80px;'/></div>");
                sb.append("<div class='form-row' style='flex:1'><label>Max Captures <span class='dim'>(1 = first match only, 0 = unlimited)</span></label><input type='number' name='maxCaptures' value='").append(activeRule != null ? activeRule.maxCaptures : 1).append("' min='0' style='width:80px;'/></div>");
                sb.append("</div></div></details>");
                sb.append("<div class='form-row-inline' style='align-items:center;'>");
                // Prefilled with the selected version's description so plain Save updates it in
                // place (clearing the field clears the description); Save-as-new records whatever
                // is in the field for the new version — visible to the operator before clicking.
                String currentDescription = "";
                if (hasSelected) {
                    for (ScriptVersionInfo vi : script.versions) {
                        if (selectedVersion.equals(vi.version)) {
                            currentDescription = vi.description != null ? vi.description : "";
                            break;
                        }
                    }
                }
                sb.append("<input type='text' name='description' value='").append(escape(currentDescription)).append("' placeholder='Description (saved with the version)' title='Saved by both Save (updates this version) and Save as new version' style='flex:1;min-width:200px;'/>");
                sb.append("<label class='checkbox-label' title='Applies only to \"Save as new version\"'><input type='checkbox' name='activate'/> Set new version active</label>");
                // Outlined (not gray) so it reads as an enabled secondary action, not a disabled button.
                sb.append("<button type='button' id='check-syntax-btn' style='background:#fff;color:#2563eb;border:1px solid #2563eb;' onclick='ptCheckSyntax()' title='Check the editor content with the server parser without saving'>Check syntax</button>");
                if (hasSelected) {
                    // The label names the overwrite target: whatever confusion exists about which
                    // version the editor is on, the destructive button always says where it writes.
                    sb.append("<button type='submit' name='version' value='").append(escape(selectedVersion)).append("' title='Overwrite version ").append(escape(selectedVersion)).append(" in place'>Save (").append(escape(selectedVersion)).append(")</button>");
                }
                sb.append("<button type='submit' formaction='/admin/scripts/register' style='background:#334155;' title='Register the editor content as a new version (auto next #)'>Save as new version</button>");
                sb.append("</div>");
                // Syntax pre-check result. Saving also runs the check first and is blocked while
                // the parser reports errors (the server-side save validation stays as the backstop).
                sb.append("<pre id='syntax-result' style='display:none'></pre>");
                sb.append("</form>");
                sb.append(syntaxCheckScript());
            }
            sb.append("</div>");
        }

        if (canModify(script) && !script.versions.isEmpty()) {
        boolean runNoActive = script.activeVersion == null || script.activeVersion.length() == 0;
        sb.append("<div class='card'>");
        sb.append("<h2>Run Script</h2>");
        if (runNoActive) {
            sb.append("<div class='callout'>No active version yet &mdash; pick a specific version to run, or <strong>Set active</strong> one first.</div>");
        }
        sb.append("<form method='post' action='/admin/submit' class='form-grid'>");
        sb.append("<input type='hidden' name='scriptId' value='").append(escape(scriptId)).append("'/>");
        sb.append("<div class='form-row'><label>Version").append(runNoActive ? "" : " (blank = active)").append("</label><select name='version' style='padding:8px 12px;border:1px solid #cbd5e1;border-radius:6px;font-size:14px;'>");
        if (runNoActive) {
            sb.append("<option value='' disabled selected>&mdash; select a version &mdash;</option>");
        } else {
            sb.append("<option value=''>active (").append(escape(script.activeVersion)).append(")</option>");
        }
        for (ScriptVersionInfo version : script.versions) {
            sb.append("<option value='").append(escape(version.version)).append("'>").append(escape(version.version)).append("</option>");
        }
        sb.append("</select></div>");
        sb.append("<div class='form-row'><label>Props (JSON)</label><input type='text' name='propsJson' value='{}'/></div>");
        sb.append("<div class='form-row-inline'>");
        sb.append("<div><label>Max Iterations</label><input type='text' name='maxIterations' value='1000' style='width:100px'/></div>");
        sb.append("<div><label>Timeout (ms, 0 = server default)</label><input type='text' name='timeoutMs' value='0' style='width:100px'/></div>");
        sb.append("<label class='checkbox-label'><input type='checkbox' name='warnLoops'/> Warn Loops</label>");
        sb.append("<button type='submit'>Run</button>");
        sb.append("</div></form></div>");
        } // end canModify check for Run Script

        // Duplicate = the supported "rename": copy everything to a new id, move callers over, then
        // delete this script. Kept at the bottom — administrative, not part of the edit/run loop.
        if (canModify(script) && script.deletedAt == 0) {
            sb.append("<div class='card'>");
            sb.append("<h2>Duplicate Script</h2>");
            sb.append("<p class='dim' style='font-size:12px;margin:4px 0 10px;'>Copies every version, the active version, and the execution settings to a new script id. ");
            sb.append("Run history stays with this script. To rename a script: duplicate it, point callers at the new id, then delete this one.</p>");
            sb.append("<form method='post' action='/admin/scripts/duplicate/").append(urlPath(scriptId)).append("' class='form-row-inline' style='align-items:center;'>");
            sb.append("<input type='text' name='newScriptId' placeholder='new_script_id' required pattern='[A-Za-z0-9._-]+' title='Letters, digits, dot, underscore, hyphen' style='max-width:260px;'/>");
            sb.append("<button type='submit' class='btn btn-sm'>Duplicate</button>");
            sb.append("</form></div>");
        }

        sb.append(bundleScript());
        sb.append(editorScript());
        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderLoginPage(String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Login - TeeBox Admin"));
        sb.append("<div style='max-width:360px;margin:80px auto;'>");
        sb.append("<div class='card'>");
        sb.append("<h2 style='text-align:center;margin-bottom:16px;'>TeeBox Login</h2>");
        if (errorMessage != null) {
            sb.append("<p style='color:#dc2626;font-size:13px;text-align:center;margin-bottom:12px;'>").append(escape(errorMessage)).append("</p>");
        }
        sb.append("<form method='post' action='/admin/login' class='form-grid'>");
        sb.append("<div class='form-row'><label>Username</label><input type='text' name='user' required autofocus/></div>");
        sb.append("<div class='form-row'><label>Password</label><input type='password' name='password' required/></div>");
        sb.append("<div class='form-row-inline' style='justify-content:center;'><button type='submit'>Login</button></div>");
        sb.append("</form>");
        sb.append("<p class='dim' style='font-size:12px;text-align:center;margin-top:12px;'>On first login, the password you enter is registered for your account.</p>");
        sb.append("</div></div>");
        sb.append(pageEnd());
        return sb.toString();
    }

    /**
     * Admin-only user management: roster listing with role/reset/delete actions plus an add-user
     * form. Reached only via the server's roster-mode + admin gate; renders live UserStore state.
     */
    public String renderUsersPage(UserStore userStore, String ok, String error) {
        List<UserStore.User> users = userStore.listUsers();
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Users - TeeBox Admin"));
        sb.append(renderTopNav("users"));
        sb.append("<div class='nav'><span>User Management</span></div>");
        if (ok != null && ok.length() > 0) {
            sb.append("<div class='callout callout-ok'>").append(escape(ok)).append("</div>");
        }
        if (error != null && error.length() > 0) {
            sb.append("<div class='callout callout-err'>").append(escape(error)).append("</div>");
        }

        sb.append("<div class='card'><h2>Users (").append(users.size()).append(")</h2>");
        sb.append("<table class='data-table'><thead><tr><th>Username</th><th>Role</th><th>Password</th><th>Actions</th></tr></thead><tbody>");
        for (UserStore.User user : users) {
            boolean self = user.username.equals(currentUser);
            sb.append("<tr><td class='mono'>").append(escape(user.username));
            if (self) {
                sb.append(" <span class='dim'>(you)</span>");
            }
            sb.append("</td>");
            sb.append("<td>").append(user.isAdmin() ? "<span class='tag tag-nav'>admin</span>" : "user").append("</td>");
            sb.append("<td>").append(userStore.hasPassword(user.username)
                    ? "set" : "<span class='dim'>set on first login</span>").append("</td>");
            sb.append("<td><div style='display:flex;gap:8px;align-items:center;flex-wrap:wrap;'>");
            // Role change (select + submit in one small form)
            sb.append("<form method='post' action='/admin/users/role' style='display:inline;'");
            if (self) {
                sb.append(" onsubmit='return confirm(\"Change your OWN role? You will be logged out immediately.\")'");
            }
            sb.append(">");
            sb.append("<input type='hidden' name='username' value='").append(escape(user.username)).append("'/>");
            sb.append("<select name='role' style='font-size:12px;'>");
            sb.append("<option value='user'").append(!user.isAdmin() ? " selected" : "").append(">user</option>");
            sb.append("<option value='admin'").append(user.isAdmin() ? " selected" : "").append(">admin</option>");
            sb.append("</select> ");
            sb.append("<button type='submit' class='btn btn-sm'>Set role</button>");
            sb.append("</form>");
            // Password reset (drops the credential; next login records a new password)
            sb.append("<form method='post' action='/admin/users/reset-password' style='display:inline;' onsubmit='return confirm(\"Reset password for ")
              .append(escape(user.username).replace("'", "\\'"))
              .append("? Their sessions end now and the next login sets a new password.\")'>");
            sb.append("<input type='hidden' name='username' value='").append(escape(user.username)).append("'/>");
            sb.append("<button type='submit' class='btn btn-sm'>Reset password</button>");
            sb.append("</form>");
            // Delete (roster entry + credential; live sessions are invalidated)
            sb.append("<form method='post' action='/admin/users/delete' style='display:inline;' onsubmit='return confirm(\"Delete user ")
              .append(escape(user.username).replace("'", "\\'"))
              .append(self ? "? This is YOUR account - you will be logged out." : "?")
              .append("\")'>");
            sb.append("<input type='hidden' name='username' value='").append(escape(user.username)).append("'/>");
            sb.append("<button type='submit' class='btn-danger btn-sm'>Delete</button>");
            sb.append("</form>");
            sb.append("</div></td></tr>");
        }
        sb.append("</tbody></table>");
        sb.append("<p class='dim' style='font-size:12px;margin-top:10px;'>Role changes, password resets and deletions end the user's live sessions immediately. ");
        sb.append("The last remaining admin cannot be deleted or demoted. ");
        sb.append("Hand-edits to <span class='mono'>users.json</span> keep working and are picked up live.</p>");
        sb.append("</div>");

        sb.append("<div class='card'><h2>Add User</h2>");
        sb.append("<form method='post' action='/admin/users/add' class='form-grid' style='max-width:420px;'>");
        sb.append("<div class='form-row'><label>Username <span class='dim'>(letters, digits, . _ -)</span></label>");
        sb.append("<input type='text' name='username' required pattern='[A-Za-z0-9._-]{1,64}'/></div>");
        sb.append("<div class='form-row'><label>Role</label><select name='role'>");
        sb.append("<option value='user' selected>user</option>");
        sb.append("<option value='admin'>admin</option>");
        sb.append("</select></div>");
        sb.append("<div class='form-row-inline'><button type='submit'>Add user</button></div>");
        sb.append("</form>");
        sb.append("<p class='dim' style='font-size:12px;margin-top:10px;'>No password is set here - the user registers their own password on first login.</p>");
        sb.append("</div>");

        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderErrorPage(String title, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart(title));
        sb.append(renderTopNav(""));
        sb.append("<div class='nav'><span>Error</span></div>");
        sb.append("<div class='card'>");
        sb.append("<h2>").append(escape(title)).append("</h2>");
        sb.append("<pre>").append(escape(message)).append("</pre>");
        sb.append("</div>");
        sb.append(pageEnd());
        return sb.toString();
    }

    private String renderSystemInfoCard(SystemInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>System Info</h2>");
        sb.append("<div class='card-actions'><a href='/api/admin/system' class='link-subtle'>JSON</a></div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>JVM</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Java</div><div class='detail-value'>").append(escape(info.javaVersion)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Vendor</div><div class='detail-value'>").append(escape(info.javaVendor)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>OS</div><div class='detail-value'>").append(escape(info.osName)).append(" ").append(escape(info.osArch)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>CPUs</div><div class='detail-value'>").append(info.availableProcessors).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Uptime</div><div class='detail-value'>").append(formatUptime(info.uptimeMs)).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>Memory</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Heap</div><div class='detail-value'>")
            .append(formatBytes(info.heapUsed)).append(" / ").append(formatBytes(info.heapMax))
            .append("</div>").append(renderUsageBar(info.heapUsed, info.heapMax)).append("</div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Non-Heap</div><div class='detail-value'>")
            .append(formatBytes(info.nonHeapUsed)).append(" / ").append(formatBytes(info.nonHeapCommitted))
            .append("</div></div>");
        sb.append("</div></div>");

        long diskUsed = info.diskTotal - info.diskFree;
        sb.append("<div class='sys-section'><div class='sys-section-title'>Disk</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Partition</div><div class='detail-value'>")
            .append(formatBytes(diskUsed)).append(" used / ").append(formatBytes(info.diskTotal)).append(" total")
            .append("</div>").append(renderUsageBar(diskUsed, info.diskTotal)).append("</div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Free</div><div class='detail-value'>")
            .append(formatBytes(info.diskFree)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Usable</div><div class='detail-value'>")
            .append(formatBytes(info.diskUsable)).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>Data Directories</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>runs/</div><div class='detail-value'>").append(formatBytes(info.runsDirSize)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>tasks/</div><div class='detail-value'>").append(formatBytes(info.tasksDirSize)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>script-registry/</div><div class='detail-value'>").append(formatBytes(info.scriptRegistryDirSize)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Total</div><div class='detail-value'>").append(formatBytes(info.totalDataSize)).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='sys-section'><div class='sys-section-title'>Configuration</div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>dataDir</div><div class='detail-value'><code>").append(escape(info.dataDirPath)).append("</code></div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Bind</div><div class='detail-value'>").append(escape(info.bindAddress)).append(":").append(info.port).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Max Concurrent Runs</div><div class='detail-value'>").append(info.maxConcurrentRuns).append("</div></div>");
        sb.append("</div></div>");

        sb.append("</div>");
        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatUptime(long ms) {
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        long mins = secs / 60;
        secs = secs % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        long hours = mins / 60;
        mins = mins % 60;
        if (hours < 24) return hours + "h " + mins + "m";
        long days = hours / 24;
        hours = hours % 24;
        return days + "d " + hours + "h " + mins + "m";
    }

    private String renderUsageBar(long used, long total) {
        if (total <= 0) return "";
        double pct = (used * 100.0) / total;
        if (pct > 100) pct = 100;
        String color;
        if (pct < 70) {
            color = "#22c55e";
        } else if (pct < 90) {
            color = "#f59e0b";
        } else {
            color = "#ef4444";
        }
        return "<div style='margin-top:4px;height:6px;background:#e2e8f0;border-radius:3px;overflow:hidden;'>" +
            "<div style='height:100%;width:" + String.format(Locale.ENGLISH, "%.1f", pct) + "%;background:" + color + ";border-radius:3px;'></div></div>";
    }

    private String renderThreadTable(List<RunThreadInfo> threads) {
        if (threads.isEmpty()) {
            return "<p class='empty'>No threads</p>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='table-wrap'><table><thead><tr>");
        sb.append("<th>ID</th><th>Name</th><th>State</th><th>Parent</th><th>Key</th><th>Result</th><th>Error</th><th>Updated</th>");
        sb.append("</tr></thead><tbody>");
        for (RunThreadInfo thread : threads) {
            sb.append("<tr>");
            sb.append("<td class='center'>").append(thread.threadId).append("</td>");
            sb.append("<td class='mono'>").append(escape(thread.name)).append("</td>");
            sb.append("<td>").append(statusBadge(thread.state)).append("</td>");
            sb.append("<td class='dim'>").append(formatParentThread(thread.parentId, threads)).append("</td>");
            sb.append("<td class='mono'>").append(escape(thread.resultKeyName)).append("</td>");
            sb.append("<td>").append(escape(thread.resultSummary)).append("</td>");
            sb.append("<td>").append(escape(thread.errorMessage)).append("</td>");
            sb.append("<td class='dim'>").append(escape(formatTime(thread.updatedAt))).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private String renderRunStatusWithTaskWarnings(RunInfo run, List<String> taskStatuses) {
        StringBuilder sb = new StringBuilder();
        sb.append(statusBadge(run.status != null ? run.status.name() : "UNKNOWN"));
        int killed = 0;
        int lost = 0;
        for (String status : taskStatuses) {
            if ("killed".equals(status)) killed++;
            else if ("lost".equals(status)) lost++;
        }
        if (killed > 0) {
            sb.append(" <span class='badge badge-killed'>").append(killed).append(" killed</span>");
        }
        if (lost > 0) {
            sb.append(" <span class='badge badge-lost'>").append(lost).append(" lost</span>");
        }
        return sb.toString();
    }

    private String formatParentThread(Integer parentId, List<RunThreadInfo> threads) {
        if (parentId == null) return "";
        for (RunThreadInfo t : threads) {
            if (t.threadId == parentId.intValue()) {
                return "<span class='mono'>" + escape(t.name) + "(" + parentId + ")</span>";
            }
        }
        return String.valueOf(parentId);
    }

    private String renderTaskTable(List<TaskInfo> tasks, boolean includeKill) {
        if (tasks.isEmpty()) {
            return "<p class='empty'>No tasks</p>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='table-wrap'><table><thead><tr>");
        sb.append("<th>Task ID</th><th>Thread</th><th>Status</th><th>PID</th><th>Alive</th><th>Elapsed</th>");
        if (includeKill) {
            sb.append("<th></th>");
        }
        sb.append("</tr></thead><tbody>");
        for (TaskInfo task : tasks) {
            sb.append("<tr>");
            sb.append("<td><a href='/admin/tasks/").append(urlPath(task.taskId)).append("' class='mono'>").append(escape(shortId(task.taskId))).append("</a>");
            if (task.archived) {
                sb.append(" <span class='dim'>[archived]</span>");
            }
            sb.append("</td>");
            sb.append("<td>").append(escape(task.threadName)).append(" <span class='dim'>#").append(escape(task.threadId)).append("</span></td>");
            sb.append("<td>").append(statusBadge(task.status));
            if (task.timeoutExceeded) {
                sb.append(" <span class='badge badge-timeout'>OVERDUE</span>");
            }
            sb.append("</td>");
            sb.append("<td class='mono center'>").append(task.pid).append("</td>");
            sb.append("<td class='center'>").append(task.alive ? statusBadge("RUNNING") : "<span class='dim'>no</span>").append("</td>");
            sb.append("<td class='dim'>").append(formatElapsed(task.elapsedMs)).append("</td>");
            if (includeKill) {
                sb.append("<td>");
                if (task.alive && canModifyRunId(task.runId)) {
                    sb.append("<form method='post' action='/admin/tasks/").append(urlPath(task.taskId)).append("/kill'><button type='submit' class='btn-danger btn-sm'>Kill</button></form>");
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private String pageStart(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'/>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'/>");
        sb.append("<title>").append(escape(title)).append("</title>");
        sb.append("<style>");
        sb.append("*{box-sizing:border-box;margin:0;padding:0;} ");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;");
        sb.append("background:#f0f2f5;color:#1a1a2e;line-height:1.5;padding:24px;max-width:1200px;margin:0 auto;} ");
        sb.append("a{color:#2563eb;text-decoration:none;} a:hover{text-decoration:underline;} ");
        sb.append("h1{font-size:22px;font-weight:600;} h2{font-size:16px;font-weight:600;margin:0 0 12px 0;} ");
        sb.append(".header{display:flex;align-items:center;justify-content:space-between;margin-bottom:24px;} ");
        sb.append(".header-meta{display:flex;gap:8px;} ");
        sb.append(".card{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:20px;margin-bottom:16px;} ");
        sb.append(".card-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;} ");
        sb.append(".card-header h2{margin:0;} ");
        sb.append(".card-actions{display:flex;gap:12px;} ");
        sb.append(".form-grid{display:flex;flex-direction:column;gap:12px;} ");
        sb.append(".form-row{display:flex;flex-direction:column;gap:4px;} ");
        sb.append(".form-row label{font-size:13px;font-weight:500;color:#64748b;} ");
        sb.append(".form-row input{padding:8px 12px;border:1px solid #cbd5e1;border-radius:6px;font-size:14px;} ");
        sb.append(".form-row input:focus{outline:none;border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,0.15);} ");
        sb.append(".form-row-inline{display:flex;align-items:flex-end;gap:16px;flex-wrap:wrap;} ");
        sb.append(".form-row-inline > div{display:flex;flex-direction:column;gap:4px;} ");
        sb.append(".form-row-inline label{font-size:13px;font-weight:500;color:#64748b;} ");
        sb.append(".form-row-inline input[type='text']{padding:8px 12px;border:1px solid #cbd5e1;border-radius:6px;font-size:14px;} ");
        sb.append(".checkbox-label{display:flex;align-items:center;gap:6px;font-size:14px;cursor:pointer;padding-bottom:4px;} ");
        sb.append(".table-wrap{overflow-x:auto;margin:0 -4px;} ");
        sb.append("table{border-collapse:collapse;width:100%;font-size:13px;} ");
        sb.append("th{background:#f8fafc;color:#64748b;font-weight:500;text-transform:uppercase;font-size:11px;letter-spacing:0.5px;");
        sb.append("padding:8px 12px;text-align:left;border-bottom:2px solid #e2e8f0;} ");
        sb.append("td{padding:8px 12px;border-bottom:1px solid #f1f5f9;vertical-align:top;} ");
        sb.append("tr:hover{background:#f8fafc;} ");
        sb.append(".badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:11px;font-weight:600;letter-spacing:0.3px;} ");
        sb.append(".badge-running{background:#dbeafe;color:#1d4ed8;} ");
        sb.append(".badge-completed,.badge-done{background:#dcfce7;color:#15803d;} ");
        sb.append(".badge-error,.badge-failed{background:#fee2e2;color:#b91c1c;} ");
        sb.append(".badge-killed{background:#fef3c7;color:#92400e;} ");
        sb.append(".badge-timeout{background:#ffedd5;color:#c2410c;} ");
        sb.append(".badge-queued,.badge-pending,.badge-waiting,.badge-blocked{background:#f3e8ff;color:#7c3aed;} ");
        sb.append(".badge-ready{background:#e0f2fe;color:#0369a1;} ");
        sb.append(".badge-sleeping{background:#fef9c3;color:#854d0e;} ");
        sb.append(".badge-lost{background:#fecaca;color:#991b1b;} ");
        sb.append(".badge-active{background:#dbeafe;color:#1e40af;} ");
        sb.append(".badge-terminal{background:#f1f5f9;color:#475569;} ");
        // Normalize height/alignment across the three .btn element types (button / a / span): give them
        // one line-height, inline-block display, and middle vertical-align so an <a class=btn> Edit and a
        // <button class=btn> Set active render at the same height and sit on the same line.
        sb.append("button,.btn{padding:8px 16px;background:#2563eb;color:#fff;border:none;border-radius:6px;");
        sb.append("font-size:13px;font-weight:500;font-family:inherit;line-height:1.4;display:inline-block;");
        sb.append("vertical-align:middle;text-align:center;cursor:pointer;transition:background 0.15s;} ");
        sb.append("button:hover,.btn:hover{background:#1d4ed8;} ");
        sb.append(".btn-danger{background:#dc2626;} .btn-danger:hover{background:#b91c1c;} ");
        sb.append(".btn-sm{padding:4px 10px;font-size:12px;} ");
        sb.append("a.btn{text-decoration:none;} a.btn:hover{text-decoration:none;color:#fff;} ");
        sb.append(".mono{font-family:'SF Mono',SFMono-Regular,Consolas,'Liberation Mono',Menlo,monospace;font-size:12px;} ");
        sb.append(".dim{color:#94a3b8;} ");
        sb.append(".center{text-align:center;} ");
        sb.append(".link-subtle{color:#64748b;font-size:12px;} .link-subtle:hover{color:#2563eb;} ");
        sb.append(".empty{color:#94a3b8;padding:24px 0;text-align:center;font-style:italic;} ");
        sb.append(".modal-overlay{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:1000;} ");
        sb.append(".modal-content{background:#fff;border-radius:8px;padding:20px;width:90%;max-width:600px;max-height:90vh;overflow-y:auto;} ");
        sb.append(".footer{margin-top:24px;padding-top:16px;border-top:1px solid #e2e8f0;font-size:12px;} ");
        sb.append(".tag{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:500;background:#e2e8f0;color:#475569;} ");
        sb.append(".detail-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;margin-bottom:16px;} ");
        sb.append(".detail-item{} .detail-item .detail-label{font-size:11px;color:#94a3b8;text-transform:uppercase;letter-spacing:0.5px;} ");
        sb.append(".detail-item .detail-value{font-size:14px;margin-top:2px;} ");
        sb.append(".callout{margin:0 0 12px 0;padding:12px 14px;border-radius:6px;background:#f8fafc;border:1px solid #e2e8f0;font-size:13px;} ");
        sb.append(".syntax-ok{margin:8px 0 0;padding:10px 12px;border-radius:6px;background:#f0fdf4;border:1px solid #86efac;color:#166534;font-size:13px;white-space:pre-wrap;} ");
        sb.append(".syntax-err{margin:8px 0 0;padding:10px 12px;border-radius:6px;background:#fef2f2;border:1px solid #fca5a5;color:#b91c1c;font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;} ");
        sb.append(".callout-warn{background:#fff7ed;border-color:#fdba74;color:#9a3412;} ");
        sb.append(".callout-ok{background:#f0fdf4;border-color:#86efac;color:#166534;} ");
        sb.append(".callout-err{background:#fef2f2;border-color:#fca5a5;color:#b91c1c;} ");
        sb.append(".nav{display:flex;gap:12px;align-items:center;margin-bottom:16px;font-size:13px;} ");
        sb.append(".nav-sep{color:#cbd5e1;} ");
        sb.append("pre{background:#1e293b;color:#e2e8f0;padding:16px;border-radius:6px;overflow-x:auto;overflow-y:auto;max-height:600px;font-size:12px;");
        sb.append("font-family:'SF Mono',SFMono-Regular,Consolas,'Liberation Mono',Menlo,monospace;line-height:1.6;margin-bottom:16px;} ");
        sb.append("code{font-family:'SF Mono',SFMono-Regular,Consolas,'Liberation Mono',Menlo,monospace;");
        sb.append("background:#f1f5f9;padding:2px 6px;border-radius:4px;font-size:12px;} ");
        sb.append(".sys-section{margin-bottom:16px;} .sys-section:last-child{margin-bottom:0;} ");
        sb.append(".sys-section-title{font-size:12px;font-weight:600;color:#64748b;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;padding-bottom:4px;border-bottom:1px solid #f1f5f9;} ");
        sb.append(".top-nav{display:flex;align-items:center;gap:16px;padding:12px 20px;background:#1e293b;border-radius:8px;margin-bottom:20px;flex-wrap:wrap;} ");
        sb.append(".top-nav-brand{color:#fff;font-weight:700;font-size:16px;letter-spacing:0.5px;text-decoration:none;} ");
        sb.append(".top-nav-brand:hover{text-decoration:none;color:#93c5fd;} ");
        sb.append(".top-nav-links{display:flex;gap:4px;} ");
        sb.append(".top-nav-link{color:#94a3b8;font-size:13px;font-weight:500;padding:6px 12px;border-radius:6px;text-decoration:none;transition:background 0.15s,color 0.15s;} ");
        sb.append(".top-nav-link:hover{background:#334155;color:#e2e8f0;text-decoration:none;} ");
        sb.append(".top-nav-link.active{background:#334155;color:#fff;} ");
        sb.append(".top-nav-meta{margin-left:auto;display:flex;gap:8px;} ");
        sb.append(".tag-nav{background:#334155;color:#94a3b8;font-size:11px;} ");
        sb.append(".btn-refresh{background:#fff;color:#334155;border:1px solid #cbd5e1;padding:4px 10px;border-radius:4px;font-size:12px;cursor:pointer;} ");
        sb.append(".btn-refresh:hover{background:#f1f5f9;} ");
        sb.append(".filter-bar{display:flex;align-items:center;gap:12px;margin-bottom:12px;} ");
        sb.append(".filter-bar select{padding:6px 10px;border:1px solid #cbd5e1;border-radius:6px;font-size:13px;} ");
        sb.append(".auto-toggle{font-size:11px;color:#94a3b8;display:flex;align-items:center;gap:4px;margin-left:12px;cursor:pointer;} ");
        sb.append(".pagination{display:flex;align-items:center;justify-content:space-between;padding:12px 0 4px 0;} ");
        sb.append(".pagination-info{font-size:13px;color:#64748b;} ");
        sb.append(".pagination-controls{display:flex;align-items:center;gap:4px;} ");
        sb.append(".pagination-btn{padding:4px 10px;border:1px solid #cbd5e1;border-radius:4px;background:#fff;font-size:13px;cursor:pointer;color:#334155;min-width:32px;text-align:center;} ");
        sb.append(".pagination-btn:hover:not([disabled]):not(.pagination-active){background:#f1f5f9;} ");
        sb.append(".pagination-btn:disabled{color:#cbd5e1;cursor:default;} ");
        sb.append(".pagination-active{background:#2563eb;color:#fff;border-color:#2563eb;cursor:default;} ");
        sb.append(".pagination-ellipsis{color:#94a3b8;font-size:13px;padding:0 4px;} ");
        sb.append(".task-output-block{margin-bottom:16px;} .task-output-block:last-child{margin-bottom:0;} ");
        sb.append(".task-output-label{font-size:12px;color:#64748b;margin-bottom:4px;} ");
        sb.append(".task-output-block pre{margin-top:4px;} ");
        sb.append(EDITOR_CSS);
        sb.append("</style></head><body>");
        return sb.toString();
    }

    /** The ProperTee code editor JS (upgrades any {@code <textarea data-pt-editor>}). Inlined only on
     *  pages that render a script editor, so other admin pages (and the login page) stay lean. The
     *  editor CSS is small and lives in {@code pageStart} for all pages. */
    private String editorScript() {
        return "<script>" + EDITOR_JS + "</script>";
    }

    private String bundleScript() {
        return "<script>" + BUNDLE_JS + "</script>";
    }

    /**
     * Syntax pre-check wiring for the version-source editor: the Check-syntax button posts the
     * editor content to /admin/scripts/validate (the same parser the save rejects with) and renders
     * the result under the form; Save/Save-as-new first run the same check and are blocked while it
     * reports errors. If the pre-check itself is unreachable, saving proceeds — the server-side
     * validation on the save paths remains the backstop, so this can only add friction, never let
     * a bad script through.
     */
    private String syntaxCheckScript() {
        // Known-name set for the client-side lint: the same Java-runtime enumeration the server
        // check uses (engine catalog + TeeBox host builtins), rendered into the page. The inlined
        // propertee-js bundle has its own default set (the JS engine's) — authoritative names must
        // come from the runtime that will actually execute the script, so the two checks can never
        // disagree on what counts as a known function.
        StringBuilder names = new StringBuilder();
        for (String name : runManager.getKnownFunctionNames()) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append('\'').append(name).append('\'');
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<script>");
        sb.append("var PT_KNOWN=[").append(names).append("];");
        // Client-side check via the inlined propertee-js bundle (checkScript — syntax + builtin
        // lint in one call, instant, no server round-trip). Returns null when the bundle is
        // unavailable/broken, in which case the caller falls back to POST /admin/scripts/validate;
        // the server-side save validation stays the backstop either way.
        sb.append("function ptClientCheck(src){");
        sb.append("if(typeof checkScript!=='function'||typeof ProperTeeCustomVisitor!=='function')return null;");
        sb.append("if(!src||!src.trim())return {ok:false,errors:['content is required']};");
        sb.append("try{");
        sb.append("if(!window.__ptLintVisitor){");
        sb.append("var fns={};for(var i=0;i<PT_KNOWN.length;i++){fns[PT_KNOWN[i]]=function(){};}");
        sb.append("window.__ptLintVisitor=new ProperTeeCustomVisitor({},fns,{stdout:function(){},stderr:function(){}},{});");
        sb.append("}");
        sb.append("var r=checkScript(src,{visitor:window.__ptLintVisitor});");
        sb.append("var errs=[];for(var j=0;j<r.problems.length;j++){var p=r.problems[j];");
        sb.append("errs.push('Line '+p.line+':'+p.column+' - '+p.message);}");
        sb.append("return {ok:r.ok===true,errors:errs};");
        sb.append("}catch(e){return null;}");
        sb.append("}");
        sb.append("function ptRenderCheck(d,box){");
        sb.append("box.style.display='block';");
        sb.append("if(d.ok){box.className='syntax-ok';box.textContent='No syntax errors.';}");
        sb.append("else{box.className='syntax-err';box.textContent=(d.errors||['Unknown error']).join('\\n').trim();}");
        sb.append("}");
        sb.append("function ptCheckSyntax(done){");
        sb.append("var form=document.getElementById('version-source-form');");
        sb.append("var ta=form.querySelector(\"textarea[name='content']\");");
        sb.append("var box=document.getElementById('syntax-result');");
        sb.append("var local=ptClientCheck(ta.value);");
        sb.append("if(local){ptRenderCheck(local,box);if(done)done(local.ok);return;}");
        sb.append("fetch('/admin/scripts/validate',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'content='+encodeURIComponent(ta.value)})");
        sb.append(".then(function(r){return r.json();})");
        sb.append(".then(function(d){ptRenderCheck(d,box);if(done)done(d.ok===true);})");
        sb.append(".catch(function(){box.style.display='none';if(done)done(true);});");
        sb.append("}");
        sb.append("(function(){");
        sb.append("var form=document.getElementById('version-source-form');");
        sb.append("if(!form)return;");
        sb.append("form.addEventListener('submit',function(ev){");
        // No ev.submitter (old browser) => skip the pre-check; the server still validates on save.
        sb.append("if(ev.submitter===undefined)return;");
        sb.append("if(form.dataset.syntaxOk==='1'){form.dataset.syntaxOk='';return;}");
        sb.append("ev.preventDefault();");
        sb.append("var submitter=ev.submitter;");
        sb.append("ptCheckSyntax(function(ok){");
        sb.append("if(!ok)return;");
        sb.append("form.dataset.syntaxOk='1';");
        // The client-side check is synchronous, so this callback still runs INSIDE the submit
        // event dispatch — a re-entrant submitter.click() here is silently dropped by the
        // browser's firing-submission-events guard (the bug: first Save only checked, second
        // Save saved). Defer the resubmit out of the current dispatch.
        sb.append("setTimeout(function(){if(submitter){submitter.click();}else{form.submit();}},0);");
        sb.append("});");
        sb.append("});");
        sb.append("})();");
        sb.append("</script>");
        return sb.toString();
    }

    private String pageEnd() {
        StringBuilder sb = new StringBuilder();
        sb.append("<script>");
        sb.append("(function(){");
        sb.append("var KEY='teebox-auto-refresh';");
        sb.append("var interval=null;");
        sb.append("var toggle=document.getElementById('auto-refresh-toggle');");
        sb.append("if(!toggle)return;");
        sb.append("function start(){if(typeof window.refreshPage!=='function')return;");
        sb.append("interval=setInterval(function(){window.refreshPage();},5000);}");
        sb.append("function stop(){if(interval){clearInterval(interval);interval=null;}}");
        sb.append("toggle.addEventListener('change',function(){");
        sb.append("if(this.checked){localStorage.setItem(KEY,'1');start();}");
        sb.append("else{localStorage.removeItem(KEY);stop();}");
        sb.append("});");
        sb.append("if(localStorage.getItem(KEY)==='1'){toggle.checked=true;start();}");
        sb.append("})();");
        sb.append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String statusBadge(String status) {
        String css = "badge";
        if (status != null) {
            String lower = status.toLowerCase(Locale.ENGLISH);
            if ("running".equals(lower)) css = "badge badge-running";
            else if ("completed".equals(lower) || "done".equals(lower)) css = "badge badge-completed";
            else if ("error".equals(lower) || "failed".equals(lower)) css = "badge badge-error";
            else if ("killed".equals(lower) || "cancelled".equals(lower)) css = "badge badge-killed";
            else if ("queued".equals(lower) || "pending".equals(lower)) css = "badge badge-queued";
            else if ("waiting".equals(lower) || "blocked".equals(lower)) css = "badge badge-blocked";
            else if ("ready".equals(lower)) css = "badge badge-ready";
            else if ("sleeping".equals(lower)) css = "badge badge-sleeping";
            else if ("lost".equals(lower)) css = "badge badge-lost";
            else if ("active".equals(lower)) css = "badge badge-active";
            else if ("terminal".equals(lower)) css = "badge badge-terminal";
        }
        return "<span class='" + css + "'>" + escape(status) + "</span>";
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private OutputPublishRule findOutputRuleForVersion(ScriptInfo script, String version) {
        if (script == null || version == null) return null;
        for (ScriptVersionInfo v : script.versions) {
            if (version.equals(v.version) && v.outputRules != null && !v.outputRules.isEmpty()) {
                return v.outputRules.get(0);
            }
        }
        return null;
    }

    private boolean hasVersion(ScriptInfo script, String version) {
        if (script == null || version == null) return false;
        for (ScriptVersionInfo v : script.versions) {
            if (version.equals(v.version)) return true;
        }
        return false;
    }

    private String urlParam(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String nullToEmpty(String text) {
        return text != null ? text : "";
    }

    private static final int DEFAULT_TAIL_LINES = 1000;
    // Byte budget for bounded tail reads. Generous for 1000 lines of typical output.
    private static final int DEFAULT_TAIL_BYTES = 1024 * 1024;

    private String tailLines(String text, int maxLines) {
        if (text == null || text.length() == 0) return "";
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;
        StringBuilder sb = new StringBuilder();
        int start = lines.length - maxLines;
        for (int i = start; i < lines.length; i++) {
            if (i > start) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String tail(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(text.length() - maxChars);
    }

    private String escape(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        text = text.replace("&", "&amp;");
        text = text.replace("<", "&lt;");
        text = text.replace(">", "&gt;");
        text = text.replace("\"", "&quot;");
        return text;
    }

    private String formatTime(Long epochMs) {
        if (epochMs == null) return "";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        return format.format(new java.util.Date(epochMs.longValue()));
    }

    private String formatTime(long epochMs) {
        return formatTime(Long.valueOf(epochMs));
    }

    private String formatDuration(Long startMs, Long endMs) {
        if (startMs == null) return "";
        long end = endMs != null ? endMs.longValue() : System.currentTimeMillis();
        long ms = end - startMs.longValue();
        if (ms < 0) return "";
        if (ms < 1000) return ms + "ms";
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        long mins = secs / 60;
        secs = secs % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        long hours = mins / 60;
        mins = mins % 60;
        return hours + "h " + mins + "m";
    }

    private String formatElapsed(long ms) {
        if (ms <= 0) return "";
        if (ms < 1000) return ms + "ms";
        long secs = ms / 1000;
        if (secs < 60) return secs + "." + ((ms % 1000) / 100) + "s";
        long mins = secs / 60;
        secs = secs % 60;
        return mins + "m " + secs + "s";
    }

    private String formatNullableElapsed(Long ms) {
        if (ms == null) {
            return "";
        }
        return formatElapsed(ms.longValue());
    }

    private String shortId(String id) {
        if (id == null) return "";
        if (id.length() <= 12) return id;
        return id.substring(0, 8) + "...";
    }

    private String joinComma(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private String urlPath(String value) {
        if (value == null) return "";
        return value.replace(" ", "%20");
    }
}
