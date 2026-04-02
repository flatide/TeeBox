package com.propertee.teebox;

import com.google.gson.Gson;
import com.propertee.task.TaskInfo;
import com.propertee.task.TaskObservation;

import java.util.List;
import java.util.Locale;

public class AdminPageRenderer {
    static final int DEFAULT_RUNS_PAGE_SIZE = 25;
    private final TeeBoxConfig config;
    private final RunManager runManager;
    private final Gson gson;

    public AdminPageRenderer(TeeBoxConfig config, RunManager runManager, Gson gson) {
        this.config = config;
        this.runManager = runManager;
        this.gson = gson;
    }

    private String renderTopNav(String activePage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='top-nav'>");
        sb.append("<a href='/admin' class='top-nav-brand'>TeeBox</a>");
        sb.append("<div class='top-nav-links'>");
        sb.append("<a href='/admin' class='top-nav-link").append("dashboard".equals(activePage) ? " active" : "").append("'>Dashboard</a>");
        sb.append("<a href='/admin/scripts' class='top-nav-link").append("scripts".equals(activePage) ? " active" : "").append("'>Scripts</a>");
        sb.append("<a href='/admin/runs' class='top-nav-link").append("runs".equals(activePage) ? " active" : "").append("'>Runs</a>");
        sb.append("</div>");
        sb.append("<div class='top-nav-meta' id='nav-counts'>");
        sb.append("<span class='tag tag-nav'>active ").append(runManager.getActiveCount()).append("</span> ");
        sb.append("<span class='tag tag-nav'>queued ").append(runManager.getQueuedCount()).append("</span>");
        sb.append("</div>");
        sb.append("<label class='auto-toggle'><input type='checkbox' id='auto-refresh-toggle'/> Auto-refresh</label>");
        sb.append("</div>");
        return sb.toString();
    }

    public String renderIndexPage() {
        List<RunInfo> running = runManager.listRuns("RUNNING", 0, -1);
        List<RunInfo> queued = runManager.listRuns("QUEUED", 0, -1);
        List<RunInfo> activeRuns = new java.util.ArrayList<RunInfo>(running);
        activeRuns.addAll(queued);
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("TeeBox Admin"));
        sb.append(renderTopNav("dashboard"));

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
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='table-wrap'><table><thead><tr><th>Run ID</th><th>Script</th><th>Status</th><th>Created</th><th>Duration</th><th>Threads</th><th>Tasks</th></tr></thead><tbody>");
        for (RunInfo run : runs) {
            sb.append("<tr>");
            sb.append("<td><a href='/admin/runs/").append(urlPath(run.runId)).append("' class='mono'>").append(escape(run.runId)).append("</a>");
            if (run.archived) {
                sb.append(" <span class='dim'>[archived]</span>");
            }
            sb.append("</td>");
            sb.append("<td class='mono'>").append(escape(run.scriptId != null ? run.scriptId : "")).append(run.version != null ? " <span class='dim'>@" + escape(run.version) + "</span>" : "").append("</td>");
            List<TaskInfo> tasks = runManager.listTasksForRun(run.runId);
            sb.append("<td>").append(renderRunStatusWithTaskWarnings(run, tasks)).append("</td>");
            sb.append("<td class='dim'>").append(escape(formatTime(run.createdAt))).append("</td>");
            sb.append("<td class='dim'>").append(formatDuration(run.startedAt, run.endedAt)).append("</td>");
            sb.append("<td class='center'>").append(run.threads != null ? run.threads.size() : 0).append("</td>");
            sb.append("<td class='center'>").append(tasks.size()).append("</td>");
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
        int totalCount = runManager.countRuns(null);
        List<RunInfo> runs = runManager.listRuns(null, 0, pageSize);
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
        sb.append("<option value='RUNNING'>RUNNING</option>");
        sb.append("<option value='COMPLETED'>COMPLETED</option>");
        sb.append("<option value='FAILED'>FAILED</option>");
        sb.append("<option value='SERVER_RESTARTED'>SERVER_RESTARTED</option>");
        sb.append("</select></div>");
        sb.append("<div id='runs-table-content'>");
        sb.append(renderRunsTableWithPagination(runs, 1, pageSize, totalCount));
        sb.append("</div>");
        sb.append("</div>");

        sb.append("<script>");
        sb.append("(function(){");
        sb.append("var currentPage=1;");
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
        sb.append("var url='/admin/fragments/all-runs?page='+currentPage;");
        sb.append("if(status)url+='&status='+encodeURIComponent(status);");
        sb.append("fetchFragment(url,'runs-table-content');");
        sb.append("fetchFragment('/admin/fragments/nav-counts','nav-counts');");
        sb.append("};");
        sb.append("window.filterRuns=function(){currentPage=1;refreshRunsPage();};");
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

    public String renderRunPage(String runId) {
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

        sb.append("<div id='run-detail-content'>");
        sb.append(renderRunDetailFragment(runId));
        sb.append("</div>");

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
        sb.append("window.refreshPage=function(){");
        sb.append("fetchFragment('/admin/fragments/run-detail/").append(urlPath(runId)).append("','run-detail-content');");
        sb.append("fetchFragment('/admin/fragments/nav-counts','nav-counts');");
        sb.append("};");
        sb.append("})();");
        sb.append("</script>");
        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderRunDetailFragment(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) return "<p class='empty'>Run not found</p>";
        List<RunThreadInfo> threads = runManager.listThreads(runId);
        List<TaskInfo> tasks = runManager.listTasksForRun(runId);
        StringBuilder sb = new StringBuilder();

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>").append(escape(runId)).append("</h2>");
        sb.append("<form method='post' action='/admin/runs/").append(urlPath(runId)).append("/kill-tasks'>");
        sb.append("<button type='submit' class='btn-danger btn-sm'>Kill All Tasks</button></form></div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Script</div><div class='detail-value'><code>").append(escape(run.scriptId != null ? run.scriptId : "")).append(run.version != null ? "@" + escape(run.version) : "").append("</code></div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Status</div><div class='detail-value'>").append(renderRunStatusWithTaskWarnings(run, tasks)).append("</div></div>");
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
            for (TaskInfo task : tasks) {
                String taskStdout = tail(runManager.getTaskStdout(task.taskId), 4000);
                String taskStderr = tail(runManager.getTaskStderr(task.taskId), 4000);
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
                        sb.append("<pre>").append(escape(taskStdout)).append("</pre>");
                    }
                    if (taskStderr.length() > 0) {
                        sb.append("<pre style='border-left:3px solid #fca5a5;'>").append(escape(taskStderr)).append("</pre>");
                    }
                    sb.append("</div>");
                }
            }
            if (!anyOutput) {
                sb.append("<p class='empty'>No task output</p>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    public String renderTaskPage(String taskId) {
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

        sb.append("<div id='task-detail-content'>");
        sb.append(renderTaskDetailFragment(taskId));
        sb.append("</div>");

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
        sb.append("window.refreshPage=function(){");
        sb.append("fetchFragment('/admin/fragments/task-detail/").append(urlPath(taskId)).append("','task-detail-content');");
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
        sb.append("<form method='post' action='/admin/tasks/").append(urlPath(taskId)).append("/kill'>");
        sb.append("<button type='submit' class='btn-danger btn-sm'>Kill Task</button></form></div>");
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

        String stdoutTail = tail(runManager.getTaskStdout(taskId), 4000);
        String stderrTail = tail(runManager.getTaskStderr(taskId), 4000);
        if (stdoutTail.length() > 0) {
            sb.append("<div class='card'><h2>Stdout</h2><pre>").append(escape(stdoutTail)).append("</pre></div>");
        }
        if (stderrTail.length() > 0) {
            sb.append("<div class='card'><h2>Stderr</h2><pre>").append(escape(stderrTail)).append("</pre></div>");
        }
        return sb.toString();
    }

    public String renderScriptsPage() {
        List<ScriptInfo> scripts = runManager.listScripts();
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Scripts - TeeBox Admin"));
        sb.append(renderTopNav("scripts"));

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>Registered Scripts (").append(scripts.size()).append(")</h2>");
        sb.append("<div class='card-actions'><button class='btn btn-sm' onclick='document.getElementById(\"register-modal\").style.display=\"flex\"'>Register Script</button></div>");
        sb.append("</div>");
        if (scripts.isEmpty()) {
            sb.append("<p class='empty'>No scripts registered</p>");
        } else {
            sb.append("<div class='table-wrap'><table><thead><tr>");
            sb.append("<th>Script ID</th><th>Active Version</th><th>Versions</th><th>Created</th><th>Updated</th><th></th>");
            sb.append("</tr></thead><tbody>");
            for (ScriptInfo script : scripts) {
                sb.append("<tr>");
                sb.append("<td><a href='/admin/scripts/").append(urlPath(script.scriptId)).append("' class='mono'>").append(escape(script.scriptId)).append("</a></td>");
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
                sb.append("<td><a href='/admin/scripts/").append(urlPath(script.scriptId)).append("#run' class='btn btn-sm'>Run</a></td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div>");

        // Register Script modal
        sb.append("<div id='register-modal' class='modal-overlay' style='display:none'>");
        sb.append("<div class='modal-content'>");
        sb.append("<div class='card-header'><h2>Register Script</h2>");
        sb.append("<button class='btn-refresh' onclick='document.getElementById(\"register-modal\").style.display=\"none\"'>Close</button></div>");
        sb.append("<form method='post' action='/admin/scripts/register' class='form-grid' id='register-form'>");
        sb.append("<div class='form-row'><label>Script ID</label><input type='text' name='scriptId' placeholder='calc_sum' required/></div>");
        sb.append("<div class='form-row'><label>Version</label><input type='text' name='version' placeholder='v1' required/></div>");
        sb.append("<div class='form-row'><label>Description</label><input type='text' name='description' placeholder=''/></div>");
        sb.append("<div class='form-row'><label>Script File</label>");
        sb.append("<input type='file' id='script-file' accept='.pt,.txt' style='font-size:13px;'/>");
        sb.append("</div>");
        sb.append("<div class='form-row'><label>Script Content</label><textarea name='content' id='script-content' rows='8' style='font-family:monospace;font-size:13px;padding:8px 12px;border:1px solid #cbd5e1;border-radius:6px;resize:vertical;' placeholder='return {\"ok\": true}'></textarea></div>");
        sb.append("<div class='form-row-inline'>");
        sb.append("<label class='checkbox-label'><input type='checkbox' name='activate' checked/> Activate</label>");
        sb.append("<button type='submit'>Register</button>");
        sb.append("</div></form>");
        sb.append("</div></div>");

        sb.append("<script>");
        sb.append("document.getElementById('script-file').addEventListener('change',function(e){");
        sb.append("var file=e.target.files[0];if(!file)return;");
        sb.append("var reader=new FileReader();");
        sb.append("reader.onload=function(ev){document.getElementById('script-content').value=ev.target.result;};");
        sb.append("reader.readAsText(file);");
        sb.append("});");
        sb.append("document.getElementById('register-modal').addEventListener('click',function(e){");
        sb.append("if(e.target===this)this.style.display='none';");
        sb.append("});");
        sb.append("</script>");

        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderScriptPage(String scriptId) {
        ScriptInfo script = runManager.getScript(scriptId);
        if (script == null) {
            return renderErrorPage("Script not found", scriptId);
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
        sb.append("<div class='detail-item'><div class='detail-label'>Created</div><div class='detail-value dim'>").append(escape(formatTime(script.createdAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Updated</div><div class='detail-value dim'>").append(escape(formatTime(script.updatedAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Total Versions</div><div class='detail-value'>").append(script.versions.size()).append("</div></div>");
        sb.append("</div></div>");

        sb.append("<div class='card'>");
        sb.append("<h2>Versions (").append(script.versions.size()).append(")</h2>");
        if (script.versions.isEmpty()) {
            sb.append("<p class='empty'>No versions</p>");
        } else {
            sb.append("<div class='table-wrap'><table><thead><tr>");
            sb.append("<th>Version</th><th>Status</th><th>Description</th><th>Labels</th><th>SHA-256</th><th>Created</th>");
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
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div>");

        if (script.activeVersion != null && script.activeVersion.length() > 0) {
            String content = runManager.getScriptVersionContent(scriptId, script.activeVersion);
            if (content != null) {
                sb.append("<div class='card'>");
                sb.append("<div class='card-header'><h2>Active Version Source (").append(escape(script.activeVersion)).append(")</h2></div>");
                sb.append("<form method='post' action='/admin/scripts/update-source' class='form-grid'>");
                sb.append("<input type='hidden' name='scriptId' value='").append(escape(scriptId)).append("'/>");
                sb.append("<input type='hidden' name='version' value='").append(escape(script.activeVersion)).append("'/>");
                sb.append("<textarea name='content' rows='12' style='font-family:\"SF Mono\",SFMono-Regular,Consolas,\"Liberation Mono\",Menlo,monospace;font-size:12px;padding:12px;border:1px solid #cbd5e1;border-radius:6px;resize:vertical;line-height:1.6;background:#1e293b;color:#e2e8f0;'>").append(escape(content)).append("</textarea>");
                sb.append("<div class='form-row-inline'><button type='submit'>Save</button></div>");
                sb.append("</form></div>");
            }
        }

        sb.append("<div class='card'>");
        sb.append("<h2>Run Script</h2>");
        sb.append("<form method='post' action='/admin/submit' class='form-grid'>");
        sb.append("<input type='hidden' name='scriptId' value='").append(escape(scriptId)).append("'/>");
        sb.append("<div class='form-row'><label>Version (blank = active)</label><select name='version' style='padding:8px 12px;border:1px solid #cbd5e1;border-radius:6px;font-size:14px;'>");
        sb.append("<option value=''>active (").append(escape(script.activeVersion)).append(")</option>");
        for (ScriptVersionInfo version : script.versions) {
            sb.append("<option value='").append(escape(version.version)).append("'>").append(escape(version.version)).append("</option>");
        }
        sb.append("</select></div>");
        sb.append("<div class='form-row'><label>Props (JSON)</label><input type='text' name='propsJson' value='{}'/></div>");
        sb.append("<div class='form-row-inline'>");
        sb.append("<div><label>Max Iterations</label><input type='text' name='maxIterations' value='1000' style='width:100px'/></div>");
        sb.append("<label class='checkbox-label'><input type='checkbox' name='warnLoops'/> Warn Loops</label>");
        sb.append("<button type='submit'>Run</button>");
        sb.append("</div></form></div>");

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

    private String renderRunStatusWithTaskWarnings(RunInfo run, List<TaskInfo> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append(statusBadge(run.status != null ? run.status.name() : "UNKNOWN"));
        int killed = 0;
        int lost = 0;
        for (TaskInfo task : tasks) {
            if ("killed".equals(task.status)) killed++;
            else if ("lost".equals(task.status)) lost++;
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
                if (task.alive) {
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
        sb.append("button,.btn{padding:8px 16px;background:#2563eb;color:#fff;border:none;border-radius:6px;");
        sb.append("font-size:13px;font-weight:500;cursor:pointer;transition:background 0.15s;} ");
        sb.append("button:hover,.btn:hover{background:#1d4ed8;} ");
        sb.append(".btn-danger{background:#dc2626;} .btn-danger:hover{background:#b91c1c;} ");
        sb.append(".btn-sm{padding:4px 10px;font-size:12px;} ");
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
        sb.append(".callout-warn{background:#fff7ed;border-color:#fdba74;color:#9a3412;} ");
        sb.append(".nav{display:flex;gap:12px;align-items:center;margin-bottom:16px;font-size:13px;} ");
        sb.append(".nav-sep{color:#cbd5e1;} ");
        sb.append("pre{background:#1e293b;color:#e2e8f0;padding:16px;border-radius:6px;overflow-x:auto;font-size:12px;");
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
        sb.append(".btn-refresh{background:transparent;border:1px solid #cbd5e1;padding:4px 10px;border-radius:4px;font-size:12px;cursor:pointer;} ");
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
        sb.append("</style></head><body>");
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
            else if ("killed".equals(lower)) css = "badge badge-killed";
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
