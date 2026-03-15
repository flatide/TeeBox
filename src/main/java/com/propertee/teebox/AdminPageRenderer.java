package com.propertee.teebox;

import com.google.gson.Gson;
import com.propertee.task.TaskInfo;
import com.propertee.task.TaskObservation;

import java.util.List;
import java.util.Locale;

public class AdminPageRenderer {
    private final TeeBoxConfig config;
    private final RunManager runManager;
    private final Gson gson;

    public AdminPageRenderer(TeeBoxConfig config, RunManager runManager, Gson gson) {
        this.config = config;
        this.runManager = runManager;
        this.gson = gson;
    }

    public String renderIndexPage() {
        List<RunInfo> runs = runManager.listRuns();
        List<TaskInfo> detached = runManager.listDetachedTasks();
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("TeeBox Admin"));
        sb.append("<div class='header'><h1>TeeBox Admin</h1>");
        sb.append("<div class='header-meta'>");
        sb.append("<span class='tag'>active ").append(runManager.getActiveCount()).append("</span> ");
        sb.append("<span class='tag'>queued ").append(runManager.getQueuedCount()).append("</span> ");
        sb.append("<a href='/admin/scripts' class='tag'>scripts ").append(runManager.listScripts().size()).append("</a>");
        sb.append("</div></div>");

        sb.append("<div class='card'>");
        sb.append("<h2>Run Script</h2>");
        sb.append("<form method='post' action='/admin/submit' class='form-grid'>");
        sb.append("<div class='form-row'><label>Script Path</label><input type='text' name='scriptPath' placeholder='01_basic_run.pt'/></div>");
        sb.append("<div class='form-row'><label>Props (JSON)</label><input type='text' name='propsJson' value='{}'/></div>");
        sb.append("<div class='form-row-inline'>");
        sb.append("<div><label>Max Iterations</label><input type='text' name='maxIterations' value='1000' style='width:100px'/></div>");
        sb.append("<label class='checkbox-label'><input type='checkbox' name='warnLoops'/> Warn Loops</label>");
        sb.append("<button type='submit'>Run</button>");
        sb.append("</div></form></div>");

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>Runs</h2>");
        sb.append("<div class='card-actions'><a href='/api/admin/runs' class='link-subtle'>Runs API</a> <a href='/api/admin/tasks' class='link-subtle'>Tasks API</a></div></div>");
        if (runs.isEmpty()) {
            sb.append("<p class='empty'>No runs yet</p>");
        } else {
            sb.append("<div class='table-wrap'><table><thead><tr><th>Run ID</th><th>Script</th><th>Status</th><th>Created</th><th>Duration</th><th>Threads</th><th>Tasks</th></tr></thead><tbody>");
            for (RunInfo run : runs) {
                sb.append("<tr>");
                sb.append("<td><a href='/admin/runs/").append(urlPath(run.runId)).append("' class='mono'>").append(escape(run.runId)).append("</a>");
                if (run.archived) {
                    sb.append(" <span class='dim'>[archived]</span>");
                }
                sb.append("</td>");
                sb.append("<td class='mono'>").append(escape(run.scriptPath)).append("</td>");
                sb.append("<td>").append(statusBadge(run.status != null ? run.status.name() : "UNKNOWN")).append("</td>");
                sb.append("<td class='dim'>").append(escape(formatTime(run.createdAt))).append("</td>");
                sb.append("<td class='dim'>").append(formatDuration(run.startedAt, run.endedAt)).append("</td>");
                sb.append("<td class='center'>").append(run.threads != null ? run.threads.size() : 0).append("</td>");
                sb.append("<td class='center'>").append(runManager.listTasksForRun(run.runId).size()).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div>");

        if (!detached.isEmpty()) {
            sb.append("<div class='card'>");
            sb.append("<h2>Detached Tasks</h2>");
            sb.append(renderTaskTable(detached, false));
            sb.append("</div>");
        }

        SystemInfo sysInfo = runManager.getSystemInfo();
        if (sysInfo != null) {
            sb.append(renderSystemInfoCard(sysInfo));
        }

        sb.append("<div class='footer'>");
        sb.append("<span class='dim'>scriptsRoot: ").append(escape(config.scriptsRoot.getAbsolutePath())).append("</span><br/>");
        sb.append("<span class='dim'>dataDir: ").append(escape(config.dataDir.getAbsolutePath())).append("</span>");
        sb.append("</div>");

        sb.append(pageEnd(true));
        return sb.toString();
    }

    public String renderRunPage(String runId) {
        RunInfo run = runManager.getRun(runId);
        if (run == null) {
            return renderErrorPage("Run not found", runId);
        }
        List<RunThreadInfo> threads = runManager.listThreads(runId);
        List<TaskInfo> tasks = runManager.listTasksForRun(runId);
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Run " + runId));

        sb.append("<div class='nav'>");
        sb.append("<a href='/admin'>Dashboard</a>");
        sb.append("<span class='nav-sep'>/</span>");
        sb.append("<span>Run ").append(escape(shortId(runId))).append("</span>");
        sb.append("<span class='nav-sep'>|</span>");
        sb.append("<a href='/api/admin/runs/").append(urlPath(runId)).append("' class='link-subtle'>JSON</a>");
        sb.append("</div>");

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>").append(escape(runId)).append("</h2>");
        sb.append("<form method='post' action='/admin/runs/").append(urlPath(runId)).append("/kill-tasks'>");
        sb.append("<button type='submit' class='btn-danger btn-sm'>Kill All Tasks</button></form></div>");
        sb.append("<div class='detail-grid'>");
        sb.append("<div class='detail-item'><div class='detail-label'>Script</div><div class='detail-value'><code>").append(escape(run.scriptPath)).append("</code></div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Status</div><div class='detail-value'>").append(statusBadge(run.status != null ? run.status.name() : "UNKNOWN")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Archived</div><div class='detail-value'>").append(run.archived ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Created</div><div class='detail-value dim'>").append(escape(formatTime(run.createdAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Started</div><div class='detail-value dim'>").append(escape(formatTime(run.startedAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Ended</div><div class='detail-value dim'>").append(escape(formatTime(run.endedAt))).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Duration</div><div class='detail-value'>").append(formatDuration(run.startedAt, run.endedAt)).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Explicit Return</div><div class='detail-value'>").append(run.hasExplicitReturn ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("</div></div>");

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
        if (stdout.length() > 0) {
            sb.append("<div class='card'><h2>Stdout</h2><pre>").append(escape(stdout)).append("</pre></div>");
        }
        if (stderr.length() > 0) {
            sb.append("<div class='card'><h2>Stderr</h2><pre>").append(escape(stderr)).append("</pre></div>");
        }

        sb.append(pageEnd(true));
        return sb.toString();
    }

    public String renderTaskPage(String taskId) {
        TaskInfo info = runManager.getTask(taskId);
        if (info == null) {
            return renderErrorPage("Task not found", taskId);
        }
        TaskObservation obs = runManager.observeTask(taskId);
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Task " + taskId));

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
        sb.append("<div class='detail-item'><div class='detail-label'>PGID</div><div class='detail-value mono'>").append(info.pgid).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Alive</div><div class='detail-value'>").append(info.alive ? statusBadge("RUNNING") : statusBadge("DONE")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Timeout Exceeded</div><div class='detail-value'>").append(info.timeoutExceeded ? statusBadge("YES") : statusBadge("NO")).append("</div></div>");
        sb.append("<div class='detail-item'><div class='detail-label'>Last Output Age</div><div class='detail-value'>").append(formatNullableElapsed(info.lastOutputAgeMs)).append("</div></div>");
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

        sb.append(pageEnd(true));
        return sb.toString();
    }

    public String renderScriptsPage() {
        List<ScriptInfo> scripts = runManager.listScripts();
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart("Scripts - TeeBox Admin"));

        sb.append("<div class='nav'>");
        sb.append("<a href='/admin'>Dashboard</a>");
        sb.append("<span class='nav-sep'>/</span>");
        sb.append("<span>Scripts</span>");
        sb.append("<span class='nav-sep'>|</span>");
        sb.append("<a href='/api/publisher/scripts' class='link-subtle'>JSON</a>");
        sb.append("</div>");

        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><h2>Registered Scripts (").append(scripts.size()).append(")</h2></div>");
        if (scripts.isEmpty()) {
            sb.append("<p class='empty'>No scripts registered</p>");
        } else {
            sb.append("<div class='table-wrap'><table><thead><tr>");
            sb.append("<th>Script ID</th><th>Active Version</th><th>Versions</th><th>Created</th><th>Updated</th>");
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
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div>");

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

        sb.append("<div class='nav'>");
        sb.append("<a href='/admin'>Dashboard</a>");
        sb.append("<span class='nav-sep'>/</span>");
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
            if (content != null && content.length() > 0) {
                sb.append("<div class='card'>");
                sb.append("<h2>Active Version Source (").append(escape(script.activeVersion)).append(")</h2>");
                sb.append("<pre>").append(escape(content)).append("</pre>");
                sb.append("</div>");
            }
        }

        sb.append(pageEnd());
        return sb.toString();
    }

    public String renderErrorPage(String title, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append(pageStart(title));
        sb.append("<div class='nav'><a href='/admin'>Dashboard</a><span class='nav-sep'>/</span><span>Error</span></div>");
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
        sb.append("<div class='detail-item'><div class='detail-label'>scriptsRoot</div><div class='detail-value'><code>").append(escape(info.scriptsRootPath)).append("</code></div></div>");
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
            sb.append("<td class='center dim'>").append(escape(thread.parentId)).append("</td>");
            sb.append("<td class='mono'>").append(escape(thread.resultKeyName)).append("</td>");
            sb.append("<td>").append(escape(thread.resultSummary)).append("</td>");
            sb.append("<td>").append(escape(thread.errorMessage)).append("</td>");
            sb.append("<td class='dim'>").append(escape(formatTime(thread.updatedAt))).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
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
        sb.append("</style></head><body>");
        return sb.toString();
    }

    private String pageEnd() {
        return pageEnd(false);
    }

    private String pageEnd(boolean autoRefresh) {
        if (!autoRefresh) {
            return "</body></html>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<script>");
        sb.append("(function(){");
        sb.append("var formFocused=false;");
        sb.append("var els=document.querySelectorAll('input,button,select,textarea');");
        sb.append("for(var i=0;i<els.length;i++){");
        sb.append("els[i].addEventListener('focus',function(){formFocused=true;});");
        sb.append("els[i].addEventListener('blur',function(){formFocused=false;});");
        sb.append("}");
        sb.append("setInterval(function(){if(!formFocused){location.reload();}},2000);");
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
