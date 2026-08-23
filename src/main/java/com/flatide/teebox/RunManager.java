package com.flatide.teebox;

import com.flatide.propertee2.runtime.TypeChecker;
import com.flatide.propertee2.scheduler.ThreadContext;
import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskInfo;
import com.flatide.propertee2.task.TaskObservation;
import com.flatide.teebox.lifecycle.TaskLifecycle;
import com.flatide.teebox.webhook.WebhookDispatcher;
import com.flatide.teebox.webhook.WebhookHttpClient;
import com.flatide.teebox.webhook.WebhookStore;

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
    private final StreamResultSupport streamSupport;
    private final SystemInfoCollector systemInfoCollector;
    private final ScheduledExecutorService maintenanceScheduler;
    private final long maintenanceIntervalMs;
    private final WebhookDispatcher webhookDispatcher;   // null when webhooks are disabled
    private final java.util.concurrent.ConcurrentHashMap<String, Future<?>> activeRuns = new java.util.concurrent.ConcurrentHashMap<String, Future<?>>();
    // --- run cancel/timeout state (per runId; entries live only while the run is active) ---
    /** Engine abort handles, registered by executeRun's onCancelHandle once the engine exists. */
    private final java.util.concurrent.ConcurrentHashMap<String, Runnable> abortHandles = new java.util.concurrent.ConcurrentHashMap<String, Runnable>();
    /** Cancel-latch: reason keyed by runId. Set by cancelRun BEFORE acting, re-checked at handle
     *  registration and at run completion — closes every register/complete race. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> cancelReasons = new java.util.concurrent.ConcurrentHashMap<String, String>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> timeoutTasks = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>>();
    private final ScheduledExecutorService timeoutScheduler;
    private final long defaultRunTimeoutMs;
    private final java.util.List<Runnable> extraMaintenanceTasks = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();
    private final java.util.concurrent.ConcurrentHashMap<String, TaskOutputWatcher> outputWatchers = new java.util.concurrent.ConcurrentHashMap<String, TaskOutputWatcher>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> scriptActiveCount = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<PendingRun>> scriptPendingQueue = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<PendingRun>>();
    private final ThreadPoolExecutor immediateExecutor;
    private final long startTimeMs = System.currentTimeMillis();
    private volatile boolean shutdownRequested = false;
    /** Serializes the shutdown flag flip + executor close against submit()'s final enqueue/park
     *  decision: without it, a submit that passed the entry check could park a PENDING run or hit
     *  a closed executor after shutdown began (TOCTOU). Lock order: lifecycleLock -> per-script
     *  count monitor -> run monitor; nothing takes them in reverse. */
    private final Object lifecycleLock = new Object();
    private volatile boolean draining = false;
    private volatile long drainStartedAt = 0;

    private static class PendingRun {
        final RunInfo run;
        final File scriptFile;
        final boolean immediate;
        PendingRun(RunInfo run, File scriptFile) {
            this(run, scriptFile, false);
        }
        PendingRun(RunInfo run, File scriptFile, boolean immediate) {
            this.run = run;
            this.scriptFile = scriptFile;
            this.immediate = immediate;
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
        int maxLogLines = (teeBoxConfig != null && teeBoxConfig.runOutputMaxLines > 0)
            ? teeBoxConfig.runOutputMaxLines : MAX_LOG_LINES;
        this.defaultRunTimeoutMs = (teeBoxConfig != null && teeBoxConfig.runTimeoutMs > 0)
            ? teeBoxConfig.runTimeoutMs : 0L;
        this.runRegistry = new RunRegistry(this.dataDir, maxLogLines, ARCHIVED_STDOUT_LINES, ARCHIVED_STDERR_LINES, runRetentionMs, runArchiveRetentionMs);
        this.scriptRegistry = new ScriptRegistry(this.dataDir);
        this.managedTaskEngine = new ManagedTaskEngine(this.dataDir.getAbsolutePath(), createHostInstanceId());
        this.managedTaskEngine.init();
        this.managedTaskEngine.archiveExpiredTasks();
        String streamRootsSetting = teeBoxConfig != null ? teeBoxConfig.streamRoots : null;
        this.streamSupport = new StreamResultSupport(resolveStreamRoots(this.dataDir, streamRootsSetting));
        this.scriptExecutor = new ScriptExecutor(new TeeBoxPlatformProvider(this.dataDir), this.streamSupport);
        this.systemInfoCollector = teeBoxConfig != null ? new SystemInfoCollector(teeBoxConfig) : null;
        this.maintenanceIntervalMs = parseDurationProperty("maintenanceIntervalMs", DEFAULT_MAINTENANCE_INTERVAL_MS);
        this.runExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, maxConcurrentRuns));
        this.immediateExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor();
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "teebox-run-timeout");
                t.setDaemon(true);
                return t;
            }
        });
        if (teeBoxConfig != null && teeBoxConfig.webhookEnabled) {
            // Keep webhook tombstones longer than the run purge horizon so a delivered record always
            // outlives its run; reconcile then never re-enqueues an already-delivered run.
            long tombstoneRetentionMs = runArchiveRetentionMs + DEFAULT_RUN_RETENTION_MS;
            this.webhookDispatcher = new WebhookDispatcher(
                new WebhookStore(this.dataDir), new WebhookHttpClient(),
                teeBoxConfig.webhookUrlAllowlist, teeBoxConfig.webhookTimeoutMs, 4, tombstoneRetentionMs);
            this.webhookDispatcher.start();
            // Startup reconcile: consider ALL non-purged terminal runs (incl. SERVER_RESTARTED) so a
            // crash in the enqueue gap is recovered no matter how long TeeBox was down (within run
            // retention). Periodic reconcile only needs a recent slice (runtime gaps end "just now").
            this.webhookDispatcher.reconcile(runRegistry.listCachedTerminalRuns());
            addMaintenanceTask(new Runnable() {
                @Override
                public void run() {
                    reconcileWebhooksRecent();
                }
            });
            TeeBoxLog.info("RunManager", "Webhook delivery enabled");
        } else {
            this.webhookDispatcher = null;
        }
        startMaintenanceScheduler();
        maintainRuns();
    }

    /** The webhook dispatcher, or null when webhooks are disabled (used for submit-time validation). */
    public WebhookDispatcher getWebhookDispatcher() {
        return webhookDispatcher;
    }

    // Periodic reconcile only needs to catch runtime enqueue failures, which happen at run
    // completion — i.e. "just now". A 1h slice is a generous margin over the maintenance interval.
    private static final long WEBHOOK_RECENT_RECONCILE_MS = 60L * 60L * 1000L;

    private void reconcileWebhooksRecent() {
        if (webhookDispatcher == null) {
            return;
        }
        long since = System.currentTimeMillis() - WEBHOOK_RECENT_RECONCILE_MS;
        webhookDispatcher.reconcile(runRegistry.listCachedRunsEndedSince(since));
    }

    public RunInfo submit(final RunRequest request) {
        if (draining || shutdownRequested) {
            throw new IllegalStateException("Server is shutting down; new runs are rejected");
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
        run.timeoutMs = request.timeoutMs > 0 ? request.timeoutMs : 0;
        run.properties = sanitizeProperties(request.props);
        run.callback = request.callback;
        run.submittedBy = request.userId;
        run.origin = request.origin;
        run.submittedFrom = request.clientAddress;

        // Resolve the script's execution settings BEFORE registering so run.immediate is persisted
        // (and indexed) from the first write — the Runs UI/API filter on it.
        ScriptInfo scriptInfo = scriptRegistry.loadScript(target.scriptId);
        boolean isImmediate = scriptInfo != null && scriptInfo.immediate;
        int maxPerScript = scriptInfo != null ? scriptInfo.maxConcurrentRuns : 0;
        run.immediate = isImmediate;
        runRegistry.register(run);

        // The park/submit decision runs under the lifecycle lock with an authoritative re-check:
        // shutdown() flips the flag and closes the executors under the same lock, so a submit
        // that passed the entry check can neither park a PENDING run after shutdown began nor
        // reach a closed executor (which would strand the run in a non-terminal QUEUED).
        synchronized (lifecycleLock) {
            if (shutdownRequested || draining) {
                runRegistry.markFailed(run, "Server is shutting down; run rejected before start");
                throw new IllegalStateException("Server is shutting down; new runs are rejected");
            }
            // Check per-script concurrency limit (applies to both immediate and normal)
            if (maxPerScript > 0) {
                java.util.concurrent.atomic.AtomicInteger count = getScriptActiveCount(target.scriptId);
                synchronized (count) {
                    int current = count.get();
                    if (current >= maxPerScript) {
                        // Mark as PENDING and enqueue for later
                        runRegistry.markPending(run);
                        getPendingQueue(target.scriptId).add(new PendingRun(run, target.scriptFile, isImmediate));
                        TeeBoxLog.info("RunManager", "Pending run " + run.runId + " for " + target.scriptId
                            + " (active=" + current + " max=" + maxPerScript + ")");
                        return run.copy();
                    }
                    count.incrementAndGet();
                }
            }

            // Immediate scripts bypass global queue; normal scripts use global executor
            submitToExecutor(run, target.scriptFile, isImmediate ? immediateExecutor : runExecutor);
        }
        return run.copy();
    }

    private void submitToExecutor(final RunInfo run, final File scriptFile, ThreadPoolExecutor executor) {
        // The worker's cleanup removes this entry when the run finishes. A very fast run could
        // finish (and remove) BEFORE the put, re-inserting a completed Future that lingers until
        // purge. The shared run monitor orders the two: the worker's remove blocks until the put
        // has completed (executeRun's cleanup takes the same lock).
        synchronized (run) {
            Future<?> future = executor.submit(new Runnable() {
                @Override
                public void run() {
                    executeRun(run, scriptFile);
                }
            });
            activeRuns.put(run.runId, future);
        }
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

    /** Registration variant that records {@code owner} when the call creates the script (see ScriptRegistry). */
    public ScriptInfo registerScriptVersion(String scriptId,
                                            String version,
                                            String content,
                                            String description,
                                            List<String> labels,
                                            boolean activate,
                                            List<OutputPublishRule> outputRules,
                                            String owner) {
        return scriptRegistry.registerVersion(scriptId, version, content, description, labels, activate, outputRules, owner);
    }

    /** Registration variant reporting the version label actually assigned (explicit or
     *  auto-incremented) — for callers that must point at the new version afterwards. */
    public ScriptRegistry.RegisteredVersion registerScriptVersionDetailed(String scriptId,
                                            String version,
                                            String content,
                                            String description,
                                            List<String> labels,
                                            boolean activate,
                                            List<OutputPublishRule> outputRules,
                                            String owner) {
        return scriptRegistry.registerVersionDetailed(scriptId, version, content, description, labels, activate, outputRules, owner);
    }

    /** Create an empty script shell (no versions, not active); code is added later. See ScriptRegistry. */
    public ScriptInfo createScript(String scriptId, String owner) {
        return scriptRegistry.createScript(scriptId, owner);
    }

    public ScriptInfo updateScriptVersionContent(String scriptId, String version, String content) {
        return scriptRegistry.updateVersionContent(scriptId, version, content, null);
    }

    public ScriptInfo updateScriptVersionContent(String scriptId, String version, String content, List<OutputPublishRule> outputRules) {
        return scriptRegistry.updateVersionContent(scriptId, version, content, outputRules);
    }

    /** @param description tri-state: null = keep, "" = clear, text = replace. */
    public ScriptInfo updateScriptVersionContent(String scriptId, String version, String content,
                                                 List<OutputPublishRule> outputRules, String description) {
        return scriptRegistry.updateVersionContent(scriptId, version, content, outputRules, description);
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

    public ScriptInfo deleteScriptVersion(String scriptId, String version) {
        return scriptRegistry.deleteVersion(scriptId, version);
    }

    public ScriptInfo duplicateScript(String sourceId, String newId, String owner) {
        return scriptRegistry.duplicateScript(sourceId, newId, owner);
    }

    /**
     * Editor pre-check: parse errors plus the unknown-builtin lint (ALL-CAPS calls that no builtin,
     * host function, or interpreter-dispatched name matches — guaranteed call-time failures). The
     * save paths themselves validate syntax only, so a lint hit blocks the UI save but never makes
     * the API reject scripts it previously accepted.
     */
    public List<String> validateScriptContent(String content) {
        return ScriptLint.check(content, scriptExecutor.knownFunctionNames());
    }

    /** The callable ALL-CAPS name set enumerated from the executing runtime (engine catalog +
     *  TeeBox host builtins) — the same set the server-side lint checks against. */
    public java.util.Set<String> getKnownFunctionNames() {
        return scriptExecutor.knownFunctionNames();
    }

    /** Register an additional maintenance task to run on every maintenance cycle. */
    public void addMaintenanceTask(Runnable task) {
        if (task != null) {
            extraMaintenanceTasks.add(task);
        }
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

    /** Filtered count; {@code immediate}: null=all, TRUE=instant only, FALSE=exclude instant.
     *  {@code search}: case-insensitive substring on runId or scriptId. */
    public int countRuns(String status, Boolean immediate, String search) {
        return runRegistry.countRuns(status, immediate, search);
    }

    /** Filtered count with an optional run origin ({@code api/ui/debug}). */
    public int countRuns(String status, Boolean immediate, String search, String origin) {
        return runRegistry.countRuns(status, immediate, search, origin);
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

    /** Filtered listing; see {@link #countRuns(String, Boolean, String)} for filter semantics. */
    public List<RunInfo> listRuns(String status, Boolean immediate, String search, int offset, int limit) {
        return runRegistry.listRuns(status, null, immediate, search, offset, limit);
    }

    /** Filtered listing with an optional run origin ({@code api/ui/debug}). */
    public List<RunInfo> listRuns(String status, Boolean immediate, String search, String origin,
                                  int offset, int limit) {
        return runRegistry.listRuns(status, null, immediate, search, origin, offset, limit);
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

    /** Task status strings per run, straight from the in-memory task index (no disk, no task
     *  materialization) — for list rows that only need counts/killed/lost tallies. */
    public java.util.Map<String, List<String>> getTaskStatusesByRun(java.util.Collection<String> runIds) {
        return managedTaskEngine.taskStatusesByRun(runIds);
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

    /** Bounded tail read; preferred for admin UI / API to avoid OOM on huge outputs. */
    public String getTaskStdoutTail(String taskId, int maxBytes) {
        return managedTaskEngine.getStdoutTail(taskId, maxBytes);
    }

    public String getTaskStderrTail(String taskId, int maxBytes) {
        return managedTaskEngine.getStderrTail(taskId, maxBytes);
    }

    public boolean killTask(String taskId) {
        return managedTaskEngine.killTask(taskId);
    }

    public int killRunTasks(String runId) {
        return managedTaskEngine.killRun(runId);
    }

    /**
     * Cancel a run in any non-terminal state. PENDING/QUEUED runs are cancelled immediately;
     * a RUNNING run is aborted cooperatively (this returns as soon as the abort is requested —
     * the terminal CANCELLED transition happens when the engine unwinds, so callers poll).
     * The run's SHELL tasks are killed on a background thread (a kill blocks seconds per task).
     *
     * <p>Implemented as a state-re-judging loop: every arm can lose a race with the run moving
     * to its next state (pending→queued promotion, executor pickup, engine-handle registration),
     * so a failed arm re-reads the status and retries instead of giving up.
     *
     * @return true if a cancel was applied or requested; false if the run is unknown or already terminal.
     */
    public boolean cancelRun(String runId, String reason) {
        final RunInfo run = runRegistry.getRawRun(runId);
        if (run == null) {
            return false;
        }
        String cancelReason = reason != null ? reason : "Cancelled";
        // Latch FIRST: executeRun re-checks this map when it registers the abort handle and again
        // when the engine returns, so a cancel that races either point still lands.
        cancelReasons.putIfAbsent(runId, cancelReason);
        long deadline = System.currentTimeMillis() + 5000L;
        while (true) {
            RunStatus status;
            synchronized (run) {
                status = run.status;
            }
            if (status == RunStatus.PENDING) {
                if (removeFromPendingQueue(run)) {
                    runRegistry.markCancelled(run, cancelReason);
                    cancelReasons.remove(runId);
                    if (webhookDispatcher != null) {
                        webhookDispatcher.onRunTerminal(run);
                    }
                    TeeBoxLog.info("RunManager", "Cancelled pending run " + runId);
                    return true;
                }
                // promoted to QUEUED concurrently — re-judge
            } else if (status == RunStatus.QUEUED) {
                Future<?> future = activeRuns.get(runId);
                if (future != null && future.cancel(false)) {
                    runRegistry.markCancelled(run, cancelReason);
                    cancelReasons.remove(runId);
                    activeRuns.remove(runId);
                    // The run took a per-script slot at submit but executeRun will never release it.
                    dequeueNextRun(run.scriptId);
                    if (webhookDispatcher != null) {
                        webhookDispatcher.onRunTerminal(run);
                    }
                    TeeBoxLog.info("RunManager", "Cancelled queued run " + runId);
                    return true;
                }
                // future not yet published, or already picked up by the executor — re-judge
            } else if (status == RunStatus.RUNNING) {
                Runnable handle = abortHandles.get(runId);
                if (handle != null) {
                    handle.run();
                }
                // handle == null ⇒ executeRun hasn't registered it yet; the cancelReasons latch
                // makes onCancelHandle self-abort the moment it does. Either way the run unwinds.
                killRunTasksInBackground(runId);   // unblocks SHELL waits + kills children
                TeeBoxLog.info("RunManager", "Cancel requested for running run " + runId);
                return true;
            } else {
                // Terminal (or the run completed while we were latching): drop the latch so it
                // doesn't leak into a later state, and report "nothing to cancel".
                cancelReasons.remove(runId);
                return false;
            }
            if (System.currentTimeMillis() >= deadline) {
                // States normally settle in milliseconds; if we are still losing races after 5s,
                // fall back on the latch (the run WILL be cancelled at handle registration or at
                // completion) and report the request as accepted.
                TeeBoxLog.warn("RunManager", "cancelRun(" + runId + ") still racing after 5s — relying on the cancel latch");
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;   // latch is set; the run will still be cancelled
            }
        }
    }

    /** Remove a PENDING run from its script's pending queue; false if it was already promoted. */
    private boolean removeFromPendingQueue(RunInfo run) {
        if (run.scriptId == null) {
            return false;
        }
        java.util.concurrent.atomic.AtomicInteger count = getScriptActiveCount(run.scriptId);
        synchronized (count) {   // same lock discipline as submit/dequeueNextRun
            java.util.concurrent.ConcurrentLinkedQueue<PendingRun> queue = scriptPendingQueue.get(run.scriptId);
            if (queue == null) {
                return false;
            }
            java.util.Iterator<PendingRun> it = queue.iterator();
            while (it.hasNext()) {
                if (run.runId.equals(it.next().run.runId)) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    /** Kill a run's SHELL tasks off the caller's thread — a kill holds per-task locks through
     *  bounded waits (seconds per task, serial), which must not stall cancel endpoints. */
    private void killRunTasksInBackground(final String runId) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int killed = managedTaskEngine.killRun(runId);
                    if (killed > 0) {
                        TeeBoxLog.info("RunManager", "Cancel killed " + killed + " task(s) for run " + runId);
                    }
                } catch (Exception e) {
                    TeeBoxLog.warn("RunManager", "Cancel task-kill failed for run " + runId, e);
                }
            }
        }, "run-cancel-kill");
        thread.setDaemon(true);
        thread.start();
    }

    /** Schedule the wall-clock execution timeout (per-run value, else the server default; 0 = off).
     *  Called at RUNNING transition — queue wait deliberately does not count. */
    private void scheduleRunTimeout(final RunInfo run) {
        final long timeoutMs = run.timeoutMs > 0 ? run.timeoutMs : defaultRunTimeoutMs;
        if (timeoutMs <= 0) {
            return;
        }
        java.util.concurrent.ScheduledFuture<?> future = timeoutScheduler.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (cancelRun(run.runId, "Cancelled: run exceeded timeout (" + timeoutMs + " ms)")) {
                        TeeBoxLog.warn("RunManager", "Run " + run.runId + " exceeded timeout (" + timeoutMs + " ms) — cancelling");
                    }
                } catch (Exception e) {
                    TeeBoxLog.warn("RunManager", "Run timeout cancel failed for " + run.runId, e);
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        timeoutTasks.put(run.runId, future);
    }

    public int getQueuedCount() {
        return runExecutor.getQueue().size() + immediateExecutor.getQueue().size() + getPendingScriptRunsCount();
    }

    public int getActiveCount() {
        return runExecutor.getActiveCount() + immediateExecutor.getActiveCount();
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

    /** JVM exit hook used by the drain path. Injectable so a test can drain without killing its own JVM. */
    public interface ExitHandler {
        void exit(int status);
    }

    private volatile ExitHandler exitHandler = new ExitHandler() {
        @Override
        public void exit(int status) {
            System.exit(status);
        }
    };

    /**
     * Replace the JVM exit call the drain thread makes (production: {@code System.exit}). A test that
     * exercises {@link #startDraining} MUST inject a no-op here — a real {@code System.exit(0)} kills
     * the test fork mid-suite, and Gradle treats the clean exit as success, silently skipping every
     * test scheduled after it (this truncated the suite for a long time before it was caught).
     */
    public void setExitHandler(ExitHandler handler) {
        if (handler != null) {
            this.exitHandler = handler;
        }
    }

    /**
     * Initiate graceful shutdown: reject new runs, wait for all in-flight to complete,
     * then exit the JVM. Returns immediately; drain happens on a background thread.
     *
     * @param maxWaitMs timeout after which shutdown is forced even if runs are still pending
     */
    public void startDraining(final long maxWaitMs) {
        // Flag check-and-set under the SAME lifecycle lock submit() re-checks under (this method
        // used to synchronize on `this` — a different monitor, so a submit holding lifecycleLock
        // could see draining=false while the drain thread, seeing nothing enqueued yet, declared
        // the drain complete and exited). Linearized: a submit that got the lock first finishes
        // its enqueue before the flag flips (the drain poll then counts it); a drain that got
        // the lock first is seen by submit's authoritative re-check, which rejects.
        synchronized (lifecycleLock) {
            if (draining) return;
            draining = true;
            drainStartedAt = System.currentTimeMillis();
        }
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
                        exitHandler.exit(0);
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
                exitHandler.exit(0);
            }
        }, "teebox-drain");
        drainThread.setDaemon(false);
        drainThread.start();
    }

    public HealthStatus getHealthStatus() {
        HealthStatus health = new HealthStatus();
        health.healthy = true;
        health.uptimeMs = System.currentTimeMillis() - startTimeMs;
        health.activeRuns = runExecutor.getActiveCount() + immediateExecutor.getActiveCount();
        health.queuedRuns = runExecutor.getQueue().size() + immediateExecutor.getQueue().size() + getPendingScriptRunsCount();
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
        // Flag flip + executor close under the lifecycle lock: submit()'s final enqueue re-checks
        // the flag under the same lock, so no run parks or submits after this block.
        synchronized (lifecycleLock) {
            shutdownRequested = true;
            // Order matters: let in-flight runs finish BEFORE tearing anything down. The task engine
            // shutdown clears the shared runner's task map, so a still-running SHELL() would die with
            // "Unknown task"; and clearing watchers early loses the runs' final output captures. The
            // flush/maintenance and timeout schedulers stay alive while we wait (periodic capture scans
            // and run timeouts keep working during the drain).
            runExecutor.shutdown();
            immediateExecutor.shutdown();
        }
        awaitExecutorTermination(runExecutor, 30);
        awaitExecutorTermination(immediateExecutor, 30);
        // Runs are terminal (or force-interrupted): their completion flushes have run. Now stop the
        // background machinery and tear down the engine last.
        maintenanceScheduler.shutdownNow();
        timeoutScheduler.shutdownNow();
        if (webhookDispatcher != null) {
            webhookDispatcher.shutdown();
        }
        outputWatchers.clear();
        runRegistry.flushDirty();
        managedTaskEngine.shutdown();
    }

    private void awaitExecutorTermination(ThreadPoolExecutor executor, long seconds) {
        try {
            if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                // Wait for the interrupted runs to actually unwind: the callers tear down the
                // watchers and the shared task engine next, and an unwinding run still uses both.
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    // A run is ignoring the interrupt (e.g. spinning in native/blocking code the
                    // engine cannot preempt). The teardown proceeds — an embedded stop() cannot
                    // wait forever — so the "all completion flushes ran" assumption is void for
                    // these runs: their captures may be partial and their SHELL tasks die with
                    // "Unknown task" once the engine closes. Standalone exit is unaffected (the
                    // JVM ends the threads); embedded callers see this error with the run IDs.
                    TeeBoxLog.error("RunManager", "Executor did not terminate after interrupt; "
                            + "proceeding with teardown. Still-active runs: " + activeRuns.keySet());
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
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
                    for (Runnable task : extraMaintenanceTasks) {
                        try {
                            task.run();
                        } catch (Exception e) {
                            TeeBoxLog.warn("RunManager", "Extra maintenance task failed", e);
                        }
                    }
                } catch (Exception e) {
                    TeeBoxLog.warn("RunManager", "Maintenance failed", e);
                }
            }
        }, maintenanceIntervalMs, maintenanceIntervalMs, TimeUnit.MILLISECONDS);
    }

    // --- debug re-run support (engine 0.26.0 debug hooks; sessions owned by DebugSessionManager) ---

    /** Debug-session wiring for a debug re-run — implemented by DebugSessionManager. */
    public interface DebugAttach {
        /** The debug handler to arm on the run (runs ON the paused engine fiber at each break). */
        com.flatide.propertee2.interp.DebugHandler handler();
        /** Receives the engine's live breakpoint set before the run starts (seed + keep). */
        void onDebugReady(java.util.Set<Integer> liveBreakpoints);
        /** Cancel was requested: wake a session paused at a break (an engine abort alone cannot —
         *  the fiber is blocked in the handler, not at a cooperative checkpoint). */
        void onCancelWake();
    }

    /** A prepared debug re-run: the registered/reset RunInfo plus the resolved script to execute. */
    public static class DebugTarget {
        public final RunInfo run;
        public final File scriptFile;
        /** Immutable content used by both the debug editor and the engine for this attempt. */
        public final String sourceCode;
        DebugTarget(RunInfo run, File scriptFile, String sourceCode) {
            this.run = run;
            this.scriptFile = scriptFile;
            this.sourceCode = sourceCode;
        }
    }

    /**
     * Build and register a debug re-run of a terminal run: the same script <b>version</b>, the
     * source run's input properties, and its iteration settings. One canonical debug runId is
     * reused for every execution of the same source Run; each execution completely resets its
     * prior lifecycle/output/result/task state. The version executes by its <b>current stored
     * content</b> — deliberately (user decision): editing the version after the failure and
     * re-debugging the fix is a supported workflow. The trade-off is that the failing-line auto
     * breakpoint assumes the text has not moved; a future step is editing the source from within
     * a debug session and re-running it.
     *
     * <p>Deliberately exempt from the run timeout (a debug session pauses for as long as the
     * operator thinks; DebugSessionManager's idle timeout owns abandonment) and from per-script
     * concurrency (operator investigation must not queue behind production runs — the debug
     * executor's own small cap bounds it). The caller submits the returned target to the debug
     * executor.
     *
     * @throws IllegalArgumentException unknown run
     * @throws IllegalStateException    non-terminal or archived source (archiving drops the input
     *                                  properties, so a faithful re-run is no longer possible)
     */
    public DebugTarget prepareDebugRun(String sourceRunId, String requestedBy) {
        if (draining || shutdownRequested) {
            throw new IllegalStateException("Server is shutting down; new runs are rejected");
        }
        RunInfo source = runRegistry.getRawRun(sourceRunId);
        if (source == null) {
            throw new IllegalArgumentException("Run not found: " + sourceRunId);
        }
        Map<String, Object> sourceProperties;
        int sourceMaxIterations;
        String sourceIterationLimitBehavior;
        synchronized (source) {
            if (source.status == RunStatus.QUEUED || source.status == RunStatus.PENDING
                    || source.status == RunStatus.RUNNING) {
                throw new IllegalStateException("Run " + sourceRunId + " is still " + source.status
                    + " — debug re-run needs a finished run");
            }
            if (source.archived) {
                throw new IllegalStateException("Run " + sourceRunId + " is archived — its input "
                    + "properties were dropped, so a faithful debug re-run is no longer possible");
            }
            // Copy the replay inputs under the SAME monitor as the archive check: the maintenance
            // archiver clears properties under this monitor (RunRegistry.archiveRunLocked), so a
            // check-then-copy without it could pass and still replay empty inputs.
            sourceProperties = sanitizeProperties(source.properties);
            sourceMaxIterations = source.maxIterations;
            sourceIterationLimitBehavior = source.iterationLimitBehavior;
        }
        // The failed version, not the active one — but by its current content (see javadoc).
        ScriptRegistry.ResolvedScript resolved = scriptRegistry.resolve(source.scriptId, source.version);
        // One launch snapshot: editing this version remains allowed, but cannot move the source/
        // entry mapping underneath an already-open debug session.
        String sourceCode = scriptRegistry.readVersionContent(resolved.scriptId, resolved.version);
        if (sourceCode == null) {
            sourceCode = "";
        }
        RunInfo previous = runRegistry.findDebugRun(sourceRunId);
        RunInfo run = new RunInfo();
        run.runId = previous != null ? previous.runId : createRunId();
        run.scriptPath = resolved.displayPath;
        run.scriptId = resolved.scriptId;
        run.version = resolved.version;
        run.scriptAbsolutePath = resolved.file.getAbsolutePath();
        run.status = RunStatus.QUEUED;
        run.createdAt = System.currentTimeMillis();
        run.maxIterations = sourceMaxIterations;
        run.iterationLimitBehavior = sourceIterationLimitBehavior;
        run.timeoutMs = 0;
        run.properties = sourceProperties;
        run.submittedBy = requestedBy;
        run.origin = "debug";
        run.debug = true;
        run.debugOf = sourceRunId;
        if (previous != null) {
            synchronized (previous) {
                if (previous.status == RunStatus.QUEUED || previous.status == RunStatus.PENDING
                        || previous.status == RunStatus.RUNNING) {
                    throw new IllegalStateException("Debug run " + previous.runId + " is still "
                        + previous.status + " and cannot be re-used yet");
                }
            }
            // A debug run may have spawned detached tasks that outlived the interpreter. Kill and
            // remove the complete previous task set before the shared runId becomes QUEUED again.
            managedTaskEngine.clearRunTasks(run.runId);
            run = runRegistry.resetDebugRun(previous, run);
            TeeBoxLog.info("RunManager", "Reset debug run " + run.runId
                + " for source Run " + sourceRunId);
        } else {
            runRegistry.register(run);
        }
        return new DebugTarget(run, resolved.file, sourceCode);
    }

    /**
     * Prepare a debugger attempt directly from the script page's Run Script panel. The currently
     * edited source (possibly unsaved) and caller-supplied input properties/iteration settings are
     * captured for the attempt, and there is deliberately no parent Run. A null
     * {@code sourceSnapshot} is the no-JS fallback (load the selected saved version); Restart
     * supplies the previous session's immutable snapshot.
     * The RunRegistry entry exists only while the session executes: it is neither persisted nor
     * returned by run list/count APIs.
     */
    public DebugTarget prepareScriptDebug(String scriptId, String version, String sourceSnapshot,
                                          Map<String, Object> properties, int maxIterations,
                                          boolean warnLoops, String requestedBy) {
        if (draining || shutdownRequested) {
            throw new IllegalStateException("Server is shutting down; new runs are rejected");
        }
        ScriptRegistry.ResolvedScript resolved = scriptRegistry.resolve(scriptId, version);
        String sourceCode = sourceSnapshot != null ? sourceSnapshot
            : scriptRegistry.readVersionContent(resolved.scriptId, resolved.version);
        List<String> errors = scriptRegistry.validateContent(sourceCode);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Cannot debug invalid script: " + errors.get(0));
        }
        RunInfo run = new RunInfo();
        run.runId = createRunId();
        run.scriptPath = resolved.displayPath;
        run.scriptId = resolved.scriptId;
        run.version = resolved.version;
        run.scriptAbsolutePath = resolved.file.getAbsolutePath();
        run.status = RunStatus.QUEUED;
        run.createdAt = System.currentTimeMillis();
        run.maxIterations = maxIterations > 0 ? maxIterations : 1000;
        run.iterationLimitBehavior = warnLoops ? "warn" : "error";
        run.timeoutMs = 0;
        run.properties = sanitizeProperties(properties);
        run.submittedBy = requestedBy;
        run.origin = "debug";
        run.debug = true;
        run.debugOf = null;
        runRegistry.registerTransientDebug(run);
        return new DebugTarget(run, resolved.file, sourceCode);
    }

    /** Remove every trace of a script-editor debug attempt after its Session copied the terminal
     *  output/result. Detached tasks are stopped as part of the same cleanup. */
    public void discardTransientDebugRun(String runId) {
        try {
            managedTaskEngine.clearRunTasks(runId);
        } finally {
            runRegistry.discardTransientDebug(runId);
        }
    }

    /** Terminalize a prepared-but-never-started debug run (its submit raced a shutdown): without
     *  this the registered run would sit QUEUED forever with nothing to ever execute it. */
    public void abandonDebugRun(RunInfo run, String reason) {
        runRegistry.markCancelled(run, reason);
    }

    /**
     * Execute a prepared debug run with the session's debug wiring attached — the same path as a
     * normal run (SHELL task runner, output/thread callbacks, terminal marking) minus the debug
     * exemptions. Blocks until the run is terminal; call from the debug executor.
     */
    public void executeDebugRun(RunInfo run, File scriptFile, String sourceCode, DebugAttach debug) {
        executeRun(run, scriptFile, debug, sourceCode);
    }

    private void executeRun(final RunInfo run, File scriptFile) {
        executeRun(run, scriptFile, null, null);
    }

    private void executeRun(final RunInfo run, File scriptFile, final DebugAttach debugAttach,
                            String sourceSnapshot) {
        final List<OutputPublishRule> outputRules = getOutputRulesForScript(run.scriptId, run.version);
        try {
            runRegistry.markStarted(run);
            if (debugAttach == null) {
                scheduleRunTimeout(run);
            }
            // Wrap task engine to auto-register watchers on task creation
            final ManagedTaskEngine engine = managedTaskEngine;
            com.flatide.propertee2.task.TaskRunner taskRunner = (outputRules != null && !outputRules.isEmpty())
                ? new OutputWatchingTaskRunner(engine, run.runId, outputRules, this)
                : (com.flatide.propertee2.task.TaskRunner) managedTaskEngine;
            ScriptExecutor.ExecutionResult result = scriptExecutor.execute(
                scriptFile,
                run.properties,
                run.maxIterations,
                run.iterationLimitBehavior,
                run.runId,
                run.scriptId,
                run.version,
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

                    @Override
                    public void onDebugReady(java.util.Set<Integer> liveBreakpoints) {
                        if (debugAttach != null) {
                            debugAttach.onDebugReady(liveBreakpoints);
                        }
                    }

                    @Override
                    public void onCancelHandle(final Runnable cancelHandle) {
                        Runnable handle = cancelHandle;
                        if (debugAttach != null) {
                            // Composite: latch the engine abort AND wake the session — a run
                            // paused at a break sits in the handler, not at a checkpoint, so
                            // only the session wake (a QUIT command) can unwind it.
                            handle = new Runnable() {
                                @Override
                                public void run() {
                                    cancelHandle.run();
                                    debugAttach.onCancelWake();
                                }
                            };
                        }
                        abortHandles.put(run.runId, handle);
                        // A cancel that arrived before the engine existed latched a reason;
                        // apply it now that there is something to abort.
                        if (cancelReasons.containsKey(run.runId)) {
                            handle.run();
                        }
                    }
                },
                debugAttach != null ? debugAttach.handler() : null,
                sourceSnapshot
            );
            // Flush watchers for this run before marking complete
            flushWatchersForRun(run.runId);
            runRegistry.flushDirty();
            if (result.cancelled || (!result.success && cancelReasons.containsKey(run.runId))) {
                // Cancelled — either the engine unwound with ProperTeeAborted, or the cancel raced
                // a failure it caused (e.g. the task kill made SHELL error out first). A successful
                // completion always wins over a late cancel.
                runRegistry.markCancelled(run, cancelReasons.get(run.runId));
                // Final sweep: a SHELL statement that had already passed its abort checkpoint may
                // have spawned a process after cancelRun's kill — close the orphan window.
                killRunTasksInBackground(run.runId);
            } else if (result.success) {
                runRegistry.markCompleted(run, result.hasExplicitReturn, result.resultData);
            } else {
                runRegistry.markFailed(run, result.errorMessage);
            }
        } catch (Throwable error) {
            flushWatchersForRun(run.runId);
            if (cancelReasons.containsKey(run.runId)) {
                runRegistry.markCancelled(run, cancelReasons.get(run.runId));
                killRunTasksInBackground(run.runId);
            } else {
                TeeBoxLog.error("RunManager", "Run failed: " + run.runId, error);
                runRegistry.markFailed(run, error != null ? error.getMessage() : "Unknown error");
            }
        } finally {
            java.util.concurrent.ScheduledFuture<?> timeout = timeoutTasks.remove(run.runId);
            if (timeout != null) {
                timeout.cancel(false);
            }
            abortHandles.remove(run.runId);
            cancelReasons.remove(run.runId);
            if (webhookDispatcher != null) {
                webhookDispatcher.onRunTerminal(run);
            }
            // Same lock as submitToExecutor's put: guarantees the put happened before this
            // remove, so a fast run can never re-insert its own completed Future.
            synchronized (run) {
                activeRuns.remove(run.runId);
            }
            // A debug run never took a per-script slot (prepareDebugRun bypasses the limit), so
            // releasing one here would over-admit: it would decrement a counter it never
            // incremented, or promote a pending run beyond the script's max.
            if (!run.debug) {
                dequeueNextRun(run.scriptId);
            }
        }
    }

    private void dequeueNextRun(String scriptId) {
        if (scriptId == null) return;
        java.util.concurrent.atomic.AtomicInteger count = scriptActiveCount.get(scriptId);
        // If no counter exists, this script had unlimited concurrency — nothing to decrement
        if (count == null) return;

        synchronized (count) {
            // No promotions while shutting down: the executors are closing, the submit would be
            // rejected, and the run would strand in a non-terminal QUEUED. Left PENDING, startup
            // recovery reports it honestly as SERVER_RESTARTED.
            if (shutdownRequested) {
                return;
            }
            java.util.concurrent.ConcurrentLinkedQueue<PendingRun> queue = scriptPendingQueue.get(scriptId);
            PendingRun next = queue != null ? queue.poll() : null;
            if (next != null) {
                // Slot transferred to pending run — count stays the same
                TeeBoxLog.info("RunManager", "Dequeuing run " + next.run.runId + " for " + scriptId);
                runRegistry.markQueued(next.run);
                try {
                    submitToExecutor(next.run, next.scriptFile, next.immediate ? immediateExecutor : runExecutor);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // Raced the shutdown flag: revert so the run is not stuck QUEUED.
                    runRegistry.markPending(next.run);
                    queue.add(next);
                    TeeBoxLog.warn("RunManager", "Dequeue rejected during shutdown; run "
                            + next.run.runId + " stays PENDING");
                }
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

    /**
     * Allowed roots for STREAM_FILE / stream-result responses: the system property
     * {@code propertee.teebox.streamRoots} (a {@code File.pathSeparator}-separated list), defaulting
     * to the data directory when unset. A streamable file must canonicalize within one of these.
     */
    private static List<File> resolveStreamRoots(File dataDir, String configValue) {
        // Prefer the TeeBoxConfig setting (system property -> teebox.properties), then fall back to a
        // bare system property (for hosts that build RunManager without a TeeBoxConfig), else dataDir.
        String raw = (configValue != null && configValue.trim().length() > 0)
            ? configValue : System.getProperty("propertee.teebox.streamRoots");
        List<File> roots = new ArrayList<File>();
        if (raw != null && raw.trim().length() > 0) {
            for (String part : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                String p = part.trim();
                if (p.length() > 0) roots.add(new File(p));
            }
        }
        if (roots.isEmpty()) {
            roots.add(dataDir);
        }
        return roots;
    }

    /**
     * If the run's result is a stream descriptor, re-validate it against the allowed roots and
     * return the file to stream; otherwise null. Used by the {@code /result-stream} endpoint.
     */
    public File resolveStreamFile(String runId) {
        RunInfo run = getRun(runId);
        if (run == null || !StreamResultSupport.isStreamDescriptor(run.resultData)) {
            return null;
        }
        return streamSupport.resolveForStreaming(run.resultData);
    }

    /** True if the run's result is a stream descriptor (vs an inline value). */
    public boolean isStreamResult(String runId) {
        RunInfo run = getRun(runId);
        return run != null && StreamResultSupport.isStreamDescriptor(run.resultData);
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
            // Clean up concurrency tracking for purged scripts
            cleanupScriptTracking(scriptId);
        }
        // Also clean up tracking entries for scripts that are idle and unknown
        // (script was deleted but never had active runs, or all runs completed long ago)
        cleanupIdleScriptTracking();
    }

    /** Remove concurrency tracking entries for a specific script, if safe. */
    private void cleanupScriptTracking(String scriptId) {
        java.util.concurrent.atomic.AtomicInteger count = scriptActiveCount.get(scriptId);
        if (count != null) {
            synchronized (count) {
                java.util.concurrent.ConcurrentLinkedQueue<PendingRun> q = scriptPendingQueue.get(scriptId);
                boolean queueEmpty = q == null || q.isEmpty();
                if (count.get() == 0 && queueEmpty) {
                    scriptActiveCount.remove(scriptId);
                    scriptPendingQueue.remove(scriptId);
                }
            }
        }
    }

    /** Sweep tracking maps for entries that are idle and whose scripts no longer exist. */
    private void cleanupIdleScriptTracking() {
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> entry : scriptActiveCount.entrySet()) {
            String scriptId = entry.getKey();
            java.util.concurrent.atomic.AtomicInteger count = entry.getValue();
            synchronized (count) {
                java.util.concurrent.ConcurrentLinkedQueue<PendingRun> q = scriptPendingQueue.get(scriptId);
                boolean queueEmpty = q == null || q.isEmpty();
                if (count.get() == 0 && queueEmpty) {
                    // Only remove if the script doesn't exist anymore — otherwise
                    // leaving the entry costs little and avoids recreation churn for active scripts
                    if (scriptRegistry.loadScript(scriptId) == null) {
                        scriptActiveCount.remove(scriptId);
                        scriptPendingQueue.remove(scriptId);
                    }
                }
            }
        }
    }

    // --- Output publish watcher ---

    public void registerOutputWatcher(String taskId, String runId, File taskDir, List<OutputPublishRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        TaskOutputWatcher watcher = new TaskOutputWatcher(taskId, runId, taskDir, rules);
        outputWatchers.put(taskId, watcher);
        TeeBoxLog.info("OutputWatcher", "Registered watcher for task=" + taskId + " run=" + runId + " rules=" + rules.size());
    }

    /**
     * Immediately flush all watchers belonging to a run. Called when run completes — on BOTH the
     * normal and the error path, so it must never throw: a watcher failure here used to re-throw
     * from the catch block and leave the run permanently RUNNING (no terminal transition). Each
     * watcher is isolated and always removed (completion is its last chance regardless).
     */
    private void flushWatchersForRun(String runId) {
        List<String> toRemove = new ArrayList<String>();
        for (Map.Entry<String, TaskOutputWatcher> entry : outputWatchers.entrySet()) {
            TaskOutputWatcher watcher = entry.getValue();
            if (runId.equals(watcher.getRunId())) {
                try {
                    // scan + apply under the watcher monitor as ONE unit: with only the scans
                    // serialized, the periodic tick could read older content, lose the CPU, and
                    // apply it AFTER this final flush applied newer content — captures out of
                    // order and the "latest" value rolled back.
                    synchronized (watcher) {
                        Map<String, List<String>> finalMatches = watcher.finalScan();
                        applyWatcherMatches(watcher, finalMatches);
                    }
                } catch (RuntimeException e) {
                    TeeBoxLog.warn("OutputWatcher", "Final flush failed for task=" + entry.getKey()
                            + " run=" + runId + ": " + e.getMessage());
                }
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
            try {
                // Same one-unit locking as flushWatchersForRun (see there for the ordering race).
                synchronized (watcher) {
                    Map<String, List<String>> matches = watcher.scan();
                    applyWatcherMatches(watcher, matches);
                }

                // Remove if all rules matched or task is no longer alive
                if (watcher.isAllMatched()) {
                    toRemove.add(entry.getKey());
                } else {
                    TaskObservation obs = managedTaskEngine.observe(entry.getKey());
                    if (obs == null || !obs.alive) {
                        // Task terminated — flush remainder and do final match
                        synchronized (watcher) {
                            Map<String, List<String>> finalMatches = watcher.finalScan();
                            applyWatcherMatches(watcher, finalMatches);
                        }
                        toRemove.add(entry.getKey());
                    }
                }
            } catch (RuntimeException e) {
                // One failing watcher must not kill the shared flush tick for every other run —
                // and retrying a deterministic failure every 2s is noise. Drop the watcher.
                TeeBoxLog.warn("OutputWatcher", "Scan failed for task=" + entry.getKey()
                        + " run=" + watcher.getRunId() + " (watcher removed): " + e.getMessage());
                toRemove.add(entry.getKey());
            }
        }
        for (String taskId : toRemove) {
            outputWatchers.remove(taskId);
        }
    }

    /**
     * Merge a scan's captures into the run's published map. Every key publishes the same
     * shape: {@code key} = latest value, {@code key.values} = capture list (bounded by the
     * rule's maxCaptures — the watcher stops capturing at the cap, so a default rule holds
     * exactly one value), {@code key.count} = total captures, {@code key.detectedAt} = last
     * capture time.
     */
    @SuppressWarnings("unchecked")
    private void applyWatcherMatches(TaskOutputWatcher watcher, Map<String, List<String>> matches) {
        if (matches.isEmpty()) return;
        RunInfo run = runRegistry.getRawRun(watcher.getRunId());
        if (run == null) return;
        synchronized (run) {
            if (run.published == null) {
                run.published = new LinkedHashMap<String, Object>();
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<String, List<String>> match : matches.entrySet()) {
                String key = match.getKey();
                List<String> values = match.getValue();
                if (values.isEmpty()) continue;
                List<String> all = (List<String>) run.published.get(key + ".values");
                if (all == null) {
                    all = new ArrayList<String>();
                    run.published.put(key + ".values", all);
                }
                all.addAll(values);
                run.published.put(key, values.get(values.size() - 1));
                run.published.put(key + ".count", Long.valueOf(all.size()));
                run.published.put(key + ".detectedAt", now);
                TeeBoxLog.info("OutputWatcher", "Published " + key + " +" + values.size()
                        + " (total " + all.size() + ", latest=" + values.get(values.size() - 1)
                        + ") for run=" + run.runId);
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
