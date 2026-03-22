package com.propertee.teebox;

import com.propertee.runtime.TypeChecker;
import com.propertee.scheduler.ThreadContext;
import com.propertee.task.Task;
import com.propertee.task.TaskInfo;
import com.propertee.task.TaskObservation;
import com.propertee.teebox.lifecycle.TaskLifecycle;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RunManager {
    private static final int MAX_LOG_LINES = 200;
    private static final int ARCHIVED_STDOUT_LINES = 50;
    private static final int ARCHIVED_STDERR_LINES = 20;
    private static final long DEFAULT_RUN_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_RUN_ARCHIVE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long FLUSH_INTERVAL_MS = 2000L;
    private static final long DEFAULT_MAINTENANCE_INTERVAL_MS = 60L * 1000L;

    private final File dataDir;
    private final RunRegistry runRegistry;
    private final ScriptRegistry scriptRegistry;
    private final ManagedTaskEngine managedTaskEngine;
    private final ThreadPoolExecutor runExecutor;
    private final ScriptExecutor scriptExecutor;
    private final SystemInfoCollector systemInfoCollector;
    private final ScheduledExecutorService maintenanceScheduler;
    private final long maintenanceIntervalMs;
    private final java.util.concurrent.ConcurrentHashMap<String, Future<?>> activeRuns = new java.util.concurrent.ConcurrentHashMap<String, Future<?>>();
    private final long startTimeMs = System.currentTimeMillis();
    private volatile boolean shutdownRequested = false;

    public RunManager(File dataDir, int maxConcurrentRuns) {
        this(dataDir, maxConcurrentRuns, null);
    }

    public RunManager(File dataDir, int maxConcurrentRuns, TeeBoxConfig teeBoxConfig) {
        this.dataDir = dataDir;
        if (!this.dataDir.exists() && !this.dataDir.mkdirs()) {
            throw new IllegalStateException("Failed to create data directory: " + this.dataDir.getAbsolutePath());
        }
        long runRetentionMs = parseDurationProperty("runRetentionMs", DEFAULT_RUN_RETENTION_MS);
        long runArchiveRetentionMs = parseDurationProperty("runArchiveRetentionMs", DEFAULT_RUN_ARCHIVE_RETENTION_MS);
        this.runRegistry = new RunRegistry(this.dataDir, MAX_LOG_LINES, ARCHIVED_STDOUT_LINES, ARCHIVED_STDERR_LINES, runRetentionMs, runArchiveRetentionMs);
        this.scriptRegistry = new ScriptRegistry(this.dataDir);
        List<File> allowedRoots;
        if (teeBoxConfig != null && teeBoxConfig.allowedScriptRoots != null && !teeBoxConfig.allowedScriptRoots.isEmpty()) {
            allowedRoots = teeBoxConfig.allowedScriptRoots;
        } else {
            allowedRoots = Collections.singletonList(this.dataDir);
        }
        this.managedTaskEngine = new ManagedTaskEngine(this.dataDir.getAbsolutePath(), createHostInstanceId(), allowedRoots);
        this.managedTaskEngine.init();
        this.managedTaskEngine.archiveExpiredTasks();
        this.scriptExecutor = new ScriptExecutor();
        this.systemInfoCollector = teeBoxConfig != null ? new SystemInfoCollector(teeBoxConfig) : null;
        this.maintenanceIntervalMs = parseDurationProperty("maintenanceIntervalMs", DEFAULT_MAINTENANCE_INTERVAL_MS);
        this.runExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, maxConcurrentRuns));
        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor();
        startMaintenanceScheduler();
        maintainRuns();
    }

    public RunInfo submit(final RunRequest request) {
        final ResolvedRunTarget target = resolveRunTarget(request);
        final RunInfo run = new RunInfo();
        run.runId = createRunId();
        run.scriptPath = target.displayPath;
        run.scriptId = target.scriptId;
        run.version = target.version;
        run.scriptAbsolutePath = target.scriptFile.getAbsolutePath();
        run.status = RunStatus.QUEUED;
        run.createdAt = System.currentTimeMillis();
        run.maxIterations = request.maxIterations > 0 ? request.maxIterations : 1000;
        run.iterationLimitBehavior = request.warnLoops ? "warn" : "error";
        run.properties = sanitizeProperties(request.props);
        runRegistry.register(run);

        Future<?> future = runExecutor.submit(new Runnable() {
            @Override
            public void run() {
                executeRun(run, target.scriptFile);
            }
        });
        activeRuns.put(run.runId, future);
        return run.copy();
    }

    public List<ScriptInfo> listScripts() {
        List<ScriptInfo> scripts = scriptRegistry.listScripts();
        List<ScriptInfo> result = new ArrayList<ScriptInfo>();
        for (ScriptInfo info : scripts) {
            result.add(info.copy());
        }
        return result;
    }

    public ScriptInfo getScript(String scriptId) {
        ScriptInfo info = scriptRegistry.loadScript(scriptId);
        return info != null ? info.copy() : null;
    }

    public ScriptInfo registerScriptVersion(String scriptId,
                                            String version,
                                            String content,
                                            String description,
                                            List<String> labels,
                                            boolean activate) {
        return scriptRegistry.registerVersion(scriptId, version, content, description, labels, activate);
    }

    public ScriptInfo activateScriptVersion(String scriptId, String version) {
        return scriptRegistry.activateVersion(scriptId, version);
    }

    public String getScriptVersionContent(String scriptId, String version) {
        return scriptRegistry.readVersionContent(scriptId, version);
    }

    public int countRuns(String status) {
        return runRegistry.countRuns(status);
    }

    public List<RunInfo> listRuns() {
        return listRuns(null, 0, -1);
    }

    public List<RunInfo> listRuns(String status, int offset, int limit) {
        return runRegistry.listRuns(status, offset, limit);
    }

    public List<RunInfo> listRuns(String status, String scriptId, int offset, int limit) {
        return runRegistry.listRuns(status, scriptId, offset, limit);
    }

    public RunInfo getRun(String runId) {
        return runRegistry.getRun(runId);
    }

    public List<RunThreadInfo> listThreads(String runId) {
        return runRegistry.listThreads(runId);
    }

    public List<TaskInfo> listTasksForRun(String runId) {
        return listTasks(runId, null, 0, -1);
    }

    public List<TaskInfo> listAllTasks() {
        return listTasks(null, null, 0, -1);
    }

    public List<TaskInfo> listTasks(String runId, String status, int offset, int limit) {
        return toInfoList(managedTaskEngine.queryTasks(runId, status, offset, limit));
    }

    public TaskInfo getTask(String taskId) {
        Task task = managedTaskEngine.getTask(taskId);
        if (task == null) return null;
        return toInfo(task);
    }

    public TaskObservation observeTask(String taskId) {
        return managedTaskEngine.observe(taskId);
    }

    public String getTaskStdout(String taskId) {
        return managedTaskEngine.getStdout(taskId);
    }

    public String getTaskStderr(String taskId) {
        return managedTaskEngine.getStderr(taskId);
    }

    public boolean killTask(String taskId) {
        return managedTaskEngine.killTask(taskId);
    }

    public int killRunTasks(String runId) {
        return managedTaskEngine.killRun(runId);
    }

    public int getQueuedCount() {
        return runExecutor.getQueue().size();
    }

    public int getActiveCount() {
        return runExecutor.getActiveCount();
    }

    public SystemInfo getSystemInfo() {
        if (systemInfoCollector == null) {
            return null;
        }
        return systemInfoCollector.collect();
    }

    public HealthStatus getHealthStatus() {
        HealthStatus health = new HealthStatus();
        health.healthy = true;
        health.uptimeMs = System.currentTimeMillis() - startTimeMs;
        health.activeRuns = runExecutor.getActiveCount();
        health.queuedRuns = runExecutor.getQueue().size();
        health.maxConcurrentRuns = runExecutor.getMaximumPoolSize();
        health.completedRuns = runExecutor.getCompletedTaskCount();

        if (systemInfoCollector != null) {
            try {
                File dataDirFile = this.dataDir;
                health.diskFreeMb = dataDirFile.getUsableSpace() / (1024L * 1024L);
                if (health.diskFreeMb < 100) {
                    health.healthy = false;
                    health.reason = "Disk space low: " + health.diskFreeMb + " MB free";
                }
            } catch (Exception e) {
                // ignore disk check failure
            }
        }
        return health;
    }

    public void shutdown() {
        shutdownRequested = true;
        maintenanceScheduler.shutdownNow();
        runRegistry.flushDirty();
        managedTaskEngine.shutdown();
        runExecutor.shutdown();
        try {
            if (!runExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                runExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            runExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void startMaintenanceScheduler() {
        maintenanceScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    runRegistry.flushDirty();
                } catch (Exception e) {
                    // flush errors logged but not propagated
                }
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        maintenanceScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    maintainRuns();
                    maintainTasks();
                } catch (Exception e) {
                    // maintenance errors logged but not propagated
                }
            }
        }, maintenanceIntervalMs, maintenanceIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void executeRun(final RunInfo run, File scriptFile) {
        try {
            runRegistry.markStarted(run);
            ScriptExecutor.ExecutionResult result = scriptExecutor.execute(
                scriptFile,
                run.properties,
                run.maxIterations,
                run.iterationLimitBehavior,
                run.runId,
                managedTaskEngine,
                new ScriptExecutor.Callbacks() {
                    @Override
                    public void onStdout(String line) {
                        runRegistry.appendLog(run, true, line);
                    }

                    @Override
                    public void onStderr(String line) {
                        runRegistry.appendLog(run, false, line);
                    }

                    @Override
                    public void onThreadCreated(ThreadContext thread) {
                        runRegistry.upsertThread(run, createThreadSnapshot(thread));
                    }

                    @Override
                    public void onThreadUpdated(ThreadContext thread) {
                        runRegistry.upsertThread(run, createThreadSnapshot(thread));
                    }

                    @Override
                    public void onThreadCompleted(ThreadContext thread) {
                        runRegistry.upsertThread(run, createThreadSnapshot(thread));
                    }

                    @Override
                    public void onThreadError(ThreadContext thread) {
                        runRegistry.upsertThread(run, createThreadSnapshot(thread));
                    }
                }
            );
            runRegistry.flushDirty();
            if (result.success) {
                runRegistry.markCompleted(run, result.hasExplicitReturn, result.resultData);
            } else {
                runRegistry.markFailed(run, result.errorMessage);
            }
        } catch (Throwable error) {
            runRegistry.markFailed(run, error != null ? error.getMessage() : "Unknown error");
        } finally {
            activeRuns.remove(run.runId);
        }
    }

    private RunThreadInfo createThreadSnapshot(ThreadContext thread) {
        RunThreadInfo info = new RunThreadInfo();
        info.threadId = thread.id;
        info.name = thread.name;
        info.state = thread.state != null ? thread.state.name() : null;
        info.parentId = thread.parentId;
        info.inThreadContext = thread.inThreadContext;
        info.sleepUntil = thread.sleepUntil;
        info.asyncPending = thread.asyncFuture != null;
        info.resultKeyName = thread.resultKeyName;
        info.updatedAt = System.currentTimeMillis();
        if (thread.result != null) {
            info.resultSummary = summarizeValue(thread.result);
        }
        if (thread.error != null) {
            info.errorMessage = thread.error.getMessage();
        }
        return info;
    }

    private String summarizeValue(Object value) {
        try {
            String formatted = TypeChecker.formatValue(value);
            if (formatted != null && formatted.length() > 300) {
                return formatted.substring(0, 300) + "...";
            }
            return formatted;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private ResolvedRunTarget resolveRunTarget(RunRequest request) {
        String scriptId = trimToNull(request != null ? request.scriptId : null);
        if (scriptId == null) {
            throw new IllegalArgumentException("scriptId is required");
        }
        ScriptRegistry.ResolvedScript resolved = scriptRegistry.resolve(scriptId, trimToNull(request.version));
        ResolvedRunTarget target = new ResolvedRunTarget();
        target.scriptFile = resolved.file;
        target.displayPath = resolved.displayPath;
        target.scriptId = resolved.scriptId;
        target.version = resolved.version;
        return target;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeProperties(Map<String, Object> props) {
        if (props == null) {
            return new LinkedHashMap<String, Object>();
        }
        return (Map<String, Object>) TypeChecker.deepCopy(props);
    }

    private static String createRunId() {
        return "run-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ENGLISH).format(new Date()) +
            "-" + Integer.toHexString((int) (System.nanoTime() & 0xffff));
    }

    private static String createHostInstanceId() {
        return "teebox-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ENGLISH).format(new Date()) +
            "-" + Integer.toHexString((int) (System.nanoTime() & 0xffff));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private void maintainRuns() {
        List<String> purgedRunIds = runRegistry.maintainRuns();
        for (String runId : purgedRunIds) {
            activeRuns.remove(runId);
        }
    }

    private long parseDurationProperty(String suffix, long defaultValue) {
        String raw = System.getProperty("propertee.teebox." + suffix);
        if (raw == null || raw.trim().length() == 0) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private List<TaskInfo> toInfoList(List<Task> tasks) {
        List<TaskInfo> result = new ArrayList<TaskInfo>();
        for (Task task : tasks) {
            result.add(toInfo(task));
        }
        return result;
    }

    private TaskInfo toInfo(Task task) {
        TaskObservation obs = managedTaskEngine.observe(task.taskId);
        TeeBoxTaskInfo info = new TeeBoxTaskInfo();
        info.taskId = task.taskId;
        info.runId = task.runId;
        info.threadId = task.threadId;
        info.threadName = task.threadName;
        info.command = task.command;
        info.pid = task.pid;
        info.pgid = task.pgid;
        info.status = obs != null ? obs.status : (task.status != null ? task.status.value() : null);
        info.alive = obs != null ? obs.alive : task.alive;
        info.archived = task.archived;
        info.elapsedMs = obs != null ? obs.elapsedMs : 0;
        info.lastStdoutAt = task.lastStdoutAt;
        info.lastStderrAt = task.lastStderrAt;
        info.lastOutputAgeMs = obs != null ? obs.lastOutputAgeMs : null;
        info.timeoutExceeded = obs != null && obs.timeoutExceeded;
        info.exitCode = task.exitCode;
        info.cwd = task.cwd;
        info.hostInstanceId = task.hostInstanceId;
        info.healthHints = obs != null ? obs.healthHints : new ArrayList<String>();
        // Populate lifecycle fields
        TaskLifecycle lc = managedTaskEngine.getLifecycle(task.taskId);
        if (lc != null) {
            info.phase = lc.getPhase() != null ? lc.getPhase().name() : null;
            info.lossReason = lc.getLossReason() != null ? lc.getLossReason().name() : null;
        }
        return info;
    }

    private void maintainTasks() {
        managedTaskEngine.archiveExpiredTasks();
    }

    private static class ResolvedRunTarget {
        File scriptFile;
        String displayPath;
        String scriptId;
        String version;
    }
}
