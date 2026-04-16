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
    private static final long DEFAULT_SCRIPT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
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
    private final java.util.concurrent.ConcurrentHashMap<String, TaskOutputWatcher> outputWatchers = new java.util.concurrent.ConcurrentHashMap<String, TaskOutputWatcher>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> scriptActiveCount = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<PendingRun>> scriptPendingQueue = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<PendingRun>>();
    private final ThreadPoolExecutor immediateExecutor;
    private final long startTimeMs = System.currentTimeMillis();
    private volatile boolean shutdownRequested = false;
    private volatile boolean draining = false;
    private volatile long drainStartedAt = 0;

    private static class PendingRun {
        final RunInfo run;
        final File scriptFile;
        PendingRun(RunInfo run, File scriptFile) {
            this.run = run;
            this.scriptFile = scriptFile;
        }
    }

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
        this.managedTaskEngine = new ManagedTaskEngine(this.dataDir.getAbsolutePath(), createHostInstanceId());
        this.managedTaskEngine.init();
        this.managedTaskEngine.archiveExpiredTasks();
        this.scriptExecutor = new ScriptExecutor(new TeeBoxPlatformProvider(this.dataDir));
        this.systemInfoCollector = teeBoxConfig != null ? new SystemInfoCollector(teeBoxConfig) : null;
        this.maintenanceIntervalMs = parseDurationProperty("maintenanceIntervalMs", DEFAULT_MAINTENANCE_INTERVAL_MS);
        this.runExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, maxConcurrentRuns));
        this.immediateExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor();
        startMaintenanceScheduler();
        maintainRuns();
    }

    public RunInfo submit(final RunRequest request) {
        if (draining) {
            throw new IllegalStateException("Server is draining for shutdown; new runs are rejected");
        }
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

        ScriptInfo scriptInfo = scriptRegistry.loadScript(target.scriptId);

        // Immediate scripts bypass global queue
        if (scriptInfo != null && scriptInfo.immediate) {
            submitToExecutor(run, target.scriptFile, immediateExecutor);
            return run.copy();
        }

        // Check per-script concurrency limit (atomic check-and-increment)
        int maxPerScript = scriptInfo != null ? scriptInfo.maxConcurrentRuns : 0;
        if (maxPerScript > 0) {
            java.util.concurrent.atomic.AtomicInteger count = getScriptActiveCount(target.scriptId);
            synchronized (count) {
                int current = count.get();
                if (current >= maxPerScript) {
                    // Queue for later
                    getPendingQueue(target.scriptId).add(new PendingRun(run, target.scriptFile));
                    TeeBoxLog.info("RunManager", "Queued run " + run.runId + " for " + target.scriptId
                        + " (active=" + current + " max=" + maxPerScript + ")");
                    return run.copy();
                }
                count.incrementAndGet();
            }
        }

        submitToExecutor(run, target.scriptFile, runExecutor);
        return run.copy();
    }

    private void submitToExecutor(final RunInfo run, final File scriptFile, ThreadPoolExecutor executor) {
        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                executeRun(run, scriptFile);
            }
        });
        activeRuns.put(run.runId, future);
    }

    private java.util.concurrent.atomic.AtomicInteger getScriptActiveCount(String scriptId) {
        java.util.concurrent.atomic.AtomicInteger count = scriptActiveCount.get(scriptId);
        if (count == null) {
            scriptActiveCount.putIfAbsent(scriptId, new java.util.concurrent.atomic.AtomicInteger(0));
            count = scriptActiveCount.get(scriptId);
        }
        return count;
    }

    private java.util.concurrent.ConcurrentLinkedQueue<PendingRun> getPendingQueue(String scriptId) {
        java.util.concurrent.ConcurrentLinkedQueue<PendingRun> queue = scriptPendingQueue.get(scriptId);
        if (queue == null) {
            scriptPendingQueue.putIfAbsent(scriptId, new java.util.concurrent.ConcurrentLinkedQueue<PendingRun>());
            queue = scriptPendingQueue.get(scriptId);
        }
        return queue;
    }

    public List<ScriptInfo> listScripts() {
        return listScripts(false);
    }

    public List<ScriptInfo> listScripts(boolean includeDeleted) {
        List<ScriptInfo> scripts = scriptRegistry.listScripts(includeDeleted);
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
        return scriptRegistry.registerVersion(scriptId, version, content, description, labels, activate, null);
    }

    public ScriptInfo registerScriptVersion(String scriptId,
                                            String version,
                                            String content,
                                            String description,
                                            List<String> labels,
                                            boolean activate,
                                            List<OutputPublishRule> outputRules) {
        return scriptRegistry.registerVersion(scriptId, version, content, description, labels, activate, outputRules);
    }

    public ScriptInfo updateScriptVersionContent(String scriptId, String version, String content) {
        return scriptRegistry.updateVersionContent(scriptId, version, content, null);
    }

    public ScriptInfo updateScriptVersionContent(String scriptId, String version, String content, List<OutputPublishRule> outputRules) {
        return scriptRegistry.updateVersionContent(scriptId, version, content, outputRules);
    }

    public ScriptInfo updateScriptSettings(String scriptId, int maxConcurrentRuns, boolean immediate) {
        return scriptRegistry.updateScriptSettings(scriptId, maxConcurrentRuns, immediate);
    }

    public boolean deleteScript(String scriptId) {
        return scriptRegistry.deleteScript(scriptId);
    }

    public boolean restoreScript(String scriptId) {
        return scriptRegistry.restoreScript(scriptId);
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

    public boolean isDraining() {
        return draining;
    }

    public long getDrainStartedAt() {
        return drainStartedAt;
    }

    public int getPendingScriptRunsCount() {
        int total = 0;
        for (java.util.concurrent.ConcurrentLinkedQueue<PendingRun> q : scriptPendingQueue.values()) {
            total += q.size();
        }
        return total;
    }

    /**
     * Initiate graceful shutdown: reject new runs, wait for all in-flight to complete,
     * then exit the JVM. Returns immediately; drain happens on a background thread.
     *
     * @param maxWaitMs timeout after which shutdown is forced even if runs are still pending
     */
    public synchronized void startDraining(final long maxWaitMs) {
        if (draining) return;
        draining = true;
        drainStartedAt = System.currentTimeMillis();
        TeeBoxLog.info("RunManager", "Draining started — new runs will be rejected");

        Thread drainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + maxWaitMs;
                while (System.currentTimeMillis() < deadline) {
                    int active = runExecutor.getActiveCount() + immediateExecutor.getActiveCount();
                    int queued = runExecutor.getQueue().size() + immediateExecutor.getQueue().size();
                    int pending = getPendingScriptRunsCount();
                    if (active == 0 && queued == 0 && pending == 0) {
                        TeeBoxLog.info("RunManager", "Drain complete — shutting down");
                        System.exit(0);
                        return;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                TeeBoxLog.warn("RunManager", "Drain timeout exceeded — forcing shutdown");
                System.exit(0);
            }
        }, "teebox-drain");
        drainThread.setDaemon(false);
        drainThread.start();
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
        outputWatchers.clear();
        maintenanceScheduler.shutdownNow();
        runRegistry.flushDirty();
        managedTaskEngine.shutdown();
        runExecutor.shutdown();
        immediateExecutor.shutdown();
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
                    scanOutputWatchers();
                } catch (Exception e) {
                    TeeBoxLog.warn("RunManager", "Flush failed", e);
                }
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        maintenanceScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    maintainRuns();
                    maintainTasks();
                    maintainScripts();
                } catch (Exception e) {
                    TeeBoxLog.warn("RunManager", "Maintenance failed", e);
                }
            }
        }, maintenanceIntervalMs, maintenanceIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void executeRun(final RunInfo run, File scriptFile) {
        final List<OutputPublishRule> outputRules = getOutputRulesForScript(run.scriptId, run.version);
        try {
            runRegistry.markStarted(run);
            // Wrap task engine to auto-register watchers on task creation
            final ManagedTaskEngine engine = managedTaskEngine;
            com.propertee.task.TaskRunner taskRunner = (outputRules != null && !outputRules.isEmpty())
                ? new OutputWatchingTaskRunner(engine, run.runId, outputRules, this)
                : (com.propertee.task.TaskRunner) managedTaskEngine;
            ScriptExecutor.ExecutionResult result = scriptExecutor.execute(
                scriptFile,
                run.properties,
                run.maxIterations,
                run.iterationLimitBehavior,
                run.runId,
                taskRunner,
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
            // Flush watchers for this run before marking complete
            flushWatchersForRun(run.runId);
            runRegistry.flushDirty();
            if (result.success) {
                runRegistry.markCompleted(run, result.hasExplicitReturn, result.resultData);
            } else {
                runRegistry.markFailed(run, result.errorMessage);
            }
        } catch (Throwable error) {
            TeeBoxLog.error("RunManager", "Run failed: " + run.runId, error);
            flushWatchersForRun(run.runId);
            runRegistry.markFailed(run, error != null ? error.getMessage() : "Unknown error");
        } finally {
            activeRuns.remove(run.runId);
            dequeueNextRun(run.scriptId);
        }
    }

    private void dequeueNextRun(String scriptId) {
        if (scriptId == null) return;
        java.util.concurrent.atomic.AtomicInteger count = scriptActiveCount.get(scriptId);
        // If no counter exists, this script had unlimited concurrency — nothing to decrement
        if (count == null) return;

        synchronized (count) {
            java.util.concurrent.ConcurrentLinkedQueue<PendingRun> queue = scriptPendingQueue.get(scriptId);
            PendingRun next = queue != null ? queue.poll() : null;
            if (next != null) {
                // Slot transferred to pending run — count stays the same
                TeeBoxLog.info("RunManager", "Dequeuing run " + next.run.runId + " for " + scriptId);
                submitToExecutor(next.run, next.scriptFile, runExecutor);
            } else {
                // No pending runs — release slot (never go below 0)
                int current = count.get();
                if (current > 0) count.decrementAndGet();
            }
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
            return DurationParser.parseMillis(raw);
        } catch (RuntimeException e) {
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

    private void maintainScripts() {
        long retention = parseDurationProperty("scriptRetentionMs", DEFAULT_SCRIPT_RETENTION_MS);
        List<String> purged = scriptRegistry.purgeExpiredScripts(retention);
        for (String scriptId : purged) {
            TeeBoxLog.info("RunManager", "Purged soft-deleted script: " + scriptId);
        }
    }

    // --- Output publish watcher ---

    public void registerOutputWatcher(String taskId, String runId, File taskDir, List<OutputPublishRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        TaskOutputWatcher watcher = new TaskOutputWatcher(taskId, runId, taskDir, rules);
        outputWatchers.put(taskId, watcher);
        TeeBoxLog.info("OutputWatcher", "Registered watcher for task=" + taskId + " run=" + runId + " rules=" + rules.size());
    }

    /** Immediately flush all watchers belonging to a run. Called when run completes. */
    private void flushWatchersForRun(String runId) {
        List<String> toRemove = new ArrayList<String>();
        for (Map.Entry<String, TaskOutputWatcher> entry : outputWatchers.entrySet()) {
            TaskOutputWatcher watcher = entry.getValue();
            if (runId.equals(watcher.getRunId())) {
                Map<String, Object> finalMatches = watcher.finalScan();
                applyWatcherMatches(watcher, finalMatches);
                toRemove.add(entry.getKey());
            }
        }
        for (String taskId : toRemove) {
            outputWatchers.remove(taskId);
        }
    }

    private void scanOutputWatchers() {
        List<String> toRemove = new ArrayList<String>();
        for (Map.Entry<String, TaskOutputWatcher> entry : outputWatchers.entrySet()) {
            TaskOutputWatcher watcher = entry.getValue();
            Map<String, Object> matches = watcher.scan();
            applyWatcherMatches(watcher, matches);

            // Remove if all rules matched or task is no longer alive
            if (watcher.isAllMatched()) {
                toRemove.add(entry.getKey());
            } else {
                TaskObservation obs = managedTaskEngine.observe(entry.getKey());
                if (obs == null || !obs.alive) {
                    // Task terminated — flush remainder and do final match
                    Map<String, Object> finalMatches = watcher.finalScan();
                    applyWatcherMatches(watcher, finalMatches);
                    toRemove.add(entry.getKey());
                }
            }
        }
        for (String taskId : toRemove) {
            outputWatchers.remove(taskId);
        }
    }

    private void applyWatcherMatches(TaskOutputWatcher watcher, Map<String, Object> matches) {
        if (matches.isEmpty()) return;
        RunInfo run = runRegistry.getRawRun(watcher.getRunId());
        if (run == null) return;
        synchronized (run) {
            if (run.published == null) {
                run.published = new LinkedHashMap<String, Object>();
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Object> match : matches.entrySet()) {
                String key = match.getKey();
                if (!run.published.containsKey(key)) {
                    run.published.put(key, match.getValue());
                    run.published.put(key + ".detectedAt", now);
                    TeeBoxLog.info("OutputWatcher", "Published " + key + "=" + match.getValue() + " for run=" + run.runId);
                }
            }
        }
        runRegistry.markDirty(run);
    }

    // --- Accessors for watcher integration ---

    public List<OutputPublishRule> getOutputRulesForScript(String scriptId, String version) {
        ScriptInfo script = scriptRegistry.loadScript(scriptId);
        if (script == null) return null;
        ScriptVersionInfo vi = null;
        String resolvedVersion = version != null ? version : script.activeVersion;
        if (resolvedVersion == null) return null;
        for (ScriptVersionInfo v : script.versions) {
            if (resolvedVersion.equals(v.version)) {
                vi = v;
                break;
            }
        }
        return vi != null ? vi.outputRules : null;
    }

    public File getTaskDir(String taskId) {
        return managedTaskEngine.getTaskDir(taskId);
    }

    private static class ResolvedRunTarget {
        File scriptFile;
        String displayPath;
        String scriptId;
        String version;
    }
}
