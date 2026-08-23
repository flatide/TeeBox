package com.flatide.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskObservation;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskRunner;
import com.flatide.propertee2.task.TaskStatus;
import com.flatide.teebox.lifecycle.TaskLifecycle;
import com.flatide.teebox.lifecycle.TaskLossReason;
import com.flatide.teebox.lifecycle.TaskPhase;
import com.flatide.teebox.lifecycle.TaskTerminalState;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Managed task engine that wraps a platform TaskRunner and adds persistence,
 * indexing, archival, and querying.
 *
 * Task lifecycle is managed via TaskLifecycle (4-axis model) as the single
 * source of truth. The core Task.status field is derived from lifecycle state.
 */
public class ManagedTaskEngine implements TaskRunner {
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    private static final long DEFAULT_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_ARCHIVE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final Set<String> DENIED_ENV_VARS = new HashSet<String>(Arrays.asList(
            "LD_PRELOAD", "LD_LIBRARY_PATH",
            "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH"));
    private static final String[] DENIED_ENV_PREFIXES = {"DYLD_"};

    private final TaskRunner runner;
    private final CommandGuard commandGuard;
    private final File taskBaseDir;
    private final File tasksDir;
    private final String hostInstanceId;

    public File getTaskDir(String taskId) {
        return new File(tasksDir, "task-" + taskId);
    }
    // TaskStatus adapter restores v1 metadata compat: v2's TaskStatus has no gson @SerializedName, so
    // without it gson reads legacy lowercase "status":"running" as null (see TaskStatusJsonAdapter).
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(TaskStatus.class, new TaskStatusJsonAdapter())
            .setPrettyPrinting()
            .create();
    private final Object indexLock = new Object();
    // In-memory task index (taskId → entry) — the only index since the on-disk tasks/index.json
    // was dropped (1.14): that file was re-read and rewritten wholesale on every task save, an
    // O(all retained tasks) write per state change. Built from a directory scan on first use
    // (archived tasks live only on disk, so the runner's map alone cannot seed it; init() primes
    // it from the scan it already does), then kept incrementally correct by saveMeta/archiveTask/
    // deleteArchivedTask. Both fields are guarded by indexLock.
    private final Map<String, TaskIndexEntry> indexEntries = new java.util.HashMap<>();
    private boolean indexLoaded = false;
    private final long retentionMs;
    private final long archiveRetentionMs;

    private final ConcurrentHashMap<String, TaskLifecycle> lifecycles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    public ManagedTaskEngine(String baseDir, String hostInstanceId) {
        this.taskBaseDir = new File(baseDir);
        this.tasksDir = new File(taskBaseDir, "tasks");
        this.hostInstanceId = hostInstanceId;
        this.commandGuard = new CommandGuard();
        if (!tasksDir.exists() && !tasksDir.mkdirs()) {
            throw new IllegalStateException("Failed to create tasks directory: " + tasksDir.getAbsolutePath());
        }
        deleteLegacyIndexFiles();
        this.retentionMs = parseDurationProperty("propertee.task.retentionMs", DEFAULT_RETENTION_MS);
        this.archiveRetentionMs = parseDurationProperty("propertee.task.archiveRetentionMs", DEFAULT_ARCHIVE_RETENTION_MS);
        this.runner = createRunner(baseDir);
    }

    private TaskRunner createRunner(String baseDir) {
        if (IS_WINDOWS) {
            return new SimulatedTaskRunner(baseDir);
        }
        return new UnixTaskRunner(baseDir);
    }

    // ---- Per-task locking ----

    private <T> T withTaskLock(String taskId, Supplier<T> action) {
        Object lock = taskLocks.computeIfAbsent(taskId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    private void withTaskLockVoid(String taskId, Runnable action) {
        Object lock = taskLocks.computeIfAbsent(taskId, k -> new Object());
        synchronized (lock) {
            action.run();
        }
    }

    // ---- Lifecycle <-> Task status sync ----

    private void syncStatusFromLifecycle(Task task) {
        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc == null) return;
        String legacyStatus = lc.deriveLegacyStatus();
        task.status = statusFromString(legacyStatus);
        if (lc.isTerminal()) {
            task.alive = false;
        }
    }

    /**
     * For in-memory tasks: if the runner shows the task as dead but our
     * lifecycle is still ACTIVE, finalize the lifecycle and persist.
     */
    private void ensureLifecycleSynced(Task task) {
        if (task == null) return;
        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc != null && !task.alive && lc.isActive()) {
            withTaskLockVoid(task.taskId, () -> {
                // Re-check under lock
                TaskLifecycle lcInner = lifecycles.get(task.taskId);
                if (lcInner != null && lcInner.isActive()) {
                    finalizeInMemoryTask(task);
                    saveMeta(task);
                }
            });
        } else if (lc != null) {
            syncStatusFromLifecycle(task);
        }
    }

    private TaskStatus statusFromString(String value) {
        if (value == null) return null;
        switch (value) {
            case "starting":
            case "running": return TaskStatus.RUNNING;
            case "completed": return TaskStatus.COMPLETED;
            case "failed": return TaskStatus.FAILED;
            case "killed": return TaskStatus.KILLED;
            case "lost": return TaskStatus.LOST;
            default: return null;
        }
    }

    public TaskLifecycle getLifecycle(String taskId) {
        if (taskId == null) return null;
        TaskLifecycle lc = lifecycles.get(taskId);
        if (lc != null) return lc;
        // Fall back to archive.json for archived tasks (lifecycle is stripped
        // from in-memory map at archive time to avoid leaks)
        return loadLifecycleFromArchive(taskId);
    }

    /** Read lifecycle data from archive.json without populating the in-memory cache.
     *  Used for archived task queries; returns null if archive doesn't exist or
     *  doesn't contain lifecycle info. */
    private TaskLifecycle loadLifecycleFromArchive(String taskId) {
        File taskDir = getTaskDir(taskId);
        File archiveFile = new File(taskDir, "archive.json");
        if (!archiveFile.exists()) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(archiveFile);
            String json = readStream(fis);
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            return TaskLifecycle.readFromJson(obj);
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(fis);
        }
    }

    // ---- TaskRunner interface methods ----

    @Override
    public Task execute(TaskRequest request) {
        try {
            validateEnv(request.env, request.command);
            commandGuard.validate(request.command, request.cwd);
        } catch (CommandGuardException e) {
            TeeBoxLog.warn("AUDIT", "BLOCKED runId=" + request.runId
                    + " command=" + request.command + " reason=" + e.getMatchedPattern());
            throw e;
        }
        TeeBoxLog.info("AUDIT", "ALLOWED runId=" + request.runId
                + " command=" + request.command);
        Task task = runner.execute(request);
        task.hostInstanceId = hostInstanceId;
        // Record pidStartTime via ProcessHandle for future init() identity verification
        if (task.pid > 0) {
            try {
                java.util.Optional<ProcessHandle> handle = ProcessHandle.of(task.pid);
                if (handle.isPresent()) {
                    java.util.Optional<Instant> startInstant = handle.get().info().startInstant();
                    if (startInstant.isPresent()) {
                        task.pidStartTime = startInstant.get().toEpochMilli();
                    }
                }
            } catch (Exception e) {
                // best-effort
            }
        }
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        lifecycles.put(task.taskId, lc);
        syncStatusFromLifecycle(task);
        saveMeta(task);
        return task;
    }

    private static void validateEnv(Map<String, String> env, String command) {
        if (env == null || env.isEmpty()) {
            return;
        }
        for (String key : env.keySet()) {
            if (DENIED_ENV_VARS.contains(key)) {
                throw new CommandGuardException(command, "denied-env-var:" + key);
            }
            for (String prefix : DENIED_ENV_PREFIXES) {
                if (key.startsWith(prefix)) {
                    throw new CommandGuardException(command, "denied-env-var:" + key);
                }
            }
        }
    }

    @Override
    public Task getTask(String taskId) {
        // First check in-memory (runner)
        Task task = runner.getTask(taskId);
        if (task != null) {
            ensureLifecycleSynced(task);
            return task;
        }
        // Fallback to disk
        if (taskId == null) return null;
        File taskDir = new File(tasksDir, "task-" + taskId);
        if (!taskDir.exists()) return null;
        return loadTask(taskDir);
    }

    @Override
    public Task waitForCompletion(String taskId, long timeoutMs) throws InterruptedException {
        // Try runner first (in-memory tasks)
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            Task task = runner.waitForCompletion(taskId, timeoutMs);
            if (task != null && !task.alive) {
                withTaskLockVoid(taskId, () -> {
                    finalizeInMemoryTask(task);
                    saveMeta(task);
                });
            }
            return task;
        }

        // Fallback: disk-loaded task (restored by init() after restart)
        long start = System.currentTimeMillis();
        long pollMs = 50L;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for task");
            }
            Task task = getTaskFromDisk(taskId);
            if (task == null) {
                return null;
            }
            withTaskLockVoid(taskId, () -> {
                refreshDiskTask(task);
                if (!task.alive) {
                    saveMeta(task);
                }
            });
            if (!task.alive) {
                return task;
            }
            if (timeoutMs > 0 && (System.currentTimeMillis() - start) > timeoutMs) {
                return task;
            }
            long sleepMs = pollMs;
            if (timeoutMs > 0) {
                long remainingMs = timeoutMs - (System.currentTimeMillis() - start);
                if (remainingMs <= 0) return task;
                sleepMs = Math.min(sleepMs, remainingMs);
            }
            Thread.sleep(sleepMs);
            pollMs = Math.min(1000L, pollMs * 2L);
        }
    }

    @Override
    public boolean killTask(String taskId) {
        return withTaskLock(taskId, () -> {
            // Check lifecycle first — already KILLED means success
            TaskLifecycle lc = lifecycles.get(taskId);
            if (lc != null && lc.isTerminal() && lc.getTerminalState() == TaskTerminalState.KILLED) {
                return true;
            }

            // Try runner first (in-memory tasks from current session)
            boolean killed = runner.killTask(taskId);
            if (killed) {
                Task task = runner.getTask(taskId);
                if (task != null) {
                    lc = lifecycles.get(taskId);
                    if (lc == null) {
                        lc = TaskLifecycle.normalizeFromRunner(task);
                        lifecycles.put(taskId, lc);
                    }
                    lc.tryTransitionToKilled();
                    syncStatusFromLifecycle(task);
                    task.alive = false;
                    if (task.endTime == null) {
                        task.endTime = Long.valueOf(System.currentTimeMillis());
                    }
                    saveMeta(task);
                }
                return true;
            }

            // Check if already terminal via lifecycle
            lc = lifecycles.get(taskId);
            if (lc != null && lc.isTerminal()) {
                // Already terminal but not KILLED — try kill-wins override (pre-persist)
                if (lc.tryTransitionToKilled()) {
                    Task task = runner.getTask(taskId);
                    if (task == null) task = getTaskFromDisk(taskId);
                    if (task != null) {
                        syncStatusFromLifecycle(task);
                        task.alive = false;
                        if (task.endTime == null) {
                            task.endTime = Long.valueOf(System.currentTimeMillis());
                        }
                        saveMeta(task);
                    }
                    return true;
                }
                // persisted terminal — can't override
                return lc.getTerminalState() == TaskTerminalState.KILLED;
            }

            // Fallback: disk-loaded task (restored by init() after restart)
            Task task = getTaskFromDisk(taskId);
            if (task == null || !task.alive) {
                // Check if disk task is already killed
                if (task != null) {
                    lc = lifecycles.get(taskId);
                    if (lc != null && lc.getTerminalState() == TaskTerminalState.KILLED) {
                        return true;
                    }
                }
                return false;
            }

            if (task.pid > 0) {
                terminateRestoredTask(task);
            }

            lc = lifecycles.get(taskId);
            if (lc == null) {
                lc = TaskLifecycle.normalizeFromRunner(task);
                lifecycles.put(taskId, lc);
            }
            lc.tryTransitionToKilled();
            syncStatusFromLifecycle(task);
            task.alive = false;
            task.exitCode = Integer.valueOf(-9);
            task.endTime = Long.valueOf(System.currentTimeMillis());
            saveMeta(task);
            return true;
        });
    }

    @Override
    public TaskObservation observe(String taskId) {
        // Try in-memory first
        TaskObservation obs = runner.observe(taskId);
        if (obs != null) {
            Task task = runner.getTask(taskId);
            if (task != null) {
                TaskLifecycle lc = lifecycles.get(taskId);
                if (lc != null) {
                    ensureLifecycleSynced(task);
                    return toObservation(task);
                }
            }
            return obs;
        }
        // Fallback to disk
        Task task = getTask(taskId);
        if (task == null) return null;
        withTaskLockVoid(taskId, () -> {
            String before = deriveLegacyStatusForTask(task);
            refreshDiskTask(task);
            String after = deriveLegacyStatusForTask(task);
            if (!equalsValue(before, after)) {
                saveMeta(task);
            }
        });
        return toObservation(task);
    }

    @Override
    public String getStdout(String taskId) {
        // Try in-memory first
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return runner.getStdout(taskId);
        }
        // Fallback to disk
        Task task = getTaskFromDisk(taskId);
        if (task == null) return "";
        if (task.archived) return task.stdoutTail != null ? task.stdoutTail : "";
        return readFile(task.stdoutFile);
    }

    @Override
    public String getStderr(String taskId) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return runner.getStderr(taskId);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return "";
        if (task.archived) return task.stderrTail != null ? task.stderrTail : "";
        return readFile(task.stderrFile);
    }

    @Override
    public String getCombinedOutput(String taskId) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return runner.getCombinedOutput(taskId);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return "";
        if (task.archived) {
            String stdout = task.stdoutTail != null ? task.stdoutTail : "";
            String stderr = task.stderrTail != null ? task.stderrTail : "";
            if (stderr.length() == 0) return stdout;
            if (stdout.length() == 0) return stderr;
            return stdout + "\n" + stderr;
        }
        String stdout = readFile(task.stdoutFile);
        String stderr = readFile(task.stderrFile);
        if (stderr.length() == 0) return stdout;
        if (stdout.length() == 0) return stderr;
        return stdout + "\n" + stderr;
    }

    @Override
    public String getCombinedOutput(String taskId, int maxBytes) {
        if (maxBytes <= 0) return "";
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return combineBounded(inMemory.stdoutFile, inMemory.stderrFile, maxBytes);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return "";
        if (task.archived) {
            String stdout = task.stdoutTail != null ? task.stdoutTail : "";
            String stderr = task.stderrTail != null ? task.stderrTail : "";
            if (stderr.length() == 0) return trimTail(stdout, maxBytes);
            if (stdout.length() == 0) return trimTail(stderr, maxBytes);
            return trimTail(stdout + "\n" + stderr, maxBytes);
        }
        return combineBounded(task.stdoutFile, task.stderrFile, maxBytes);
    }

    private String combineBounded(File stdoutFile, File stderrFile, int maxBytes) {
        // Split the byte budget between stdout and stderr (stdout typically larger).
        int stdoutBudget = (int) ((long) maxBytes * 3L / 4L);
        int stderrBudget = maxBytes - stdoutBudget;
        String stdout = readFileTail(stdoutFile, stdoutBudget);
        String stderr = readFileTail(stderrFile, stderrBudget);
        if (stderr.length() == 0) return stdout;
        if (stdout.length() == 0) return stderr;
        return stdout + "\n" + stderr;
    }

    private static String trimTail(String s, int maxBytes) {
        if (s == null || maxBytes <= 0) return "";
        if (s.length() <= maxBytes) return s;
        return s.substring(s.length() - maxBytes);
    }

    /** Bounded tail read for admin UI / API. Avoids loading multi-GB stdout into heap. */
    public String getStdoutTail(String taskId, int maxBytes) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return readFileTail(inMemory.stdoutFile, maxBytes);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return "";
        if (task.archived) return task.stdoutTail != null ? task.stdoutTail : "";
        return readFileTail(task.stdoutFile, maxBytes);
    }

    /** Bounded tail read for admin UI / API. */
    public String getStderrTail(String taskId, int maxBytes) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return readFileTail(inMemory.stderrFile, maxBytes);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return "";
        if (task.archived) return task.stderrTail != null ? task.stderrTail : "";
        return readFileTail(task.stderrFile, maxBytes);
    }

    @Override
    public Integer getExitCode(String taskId) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            ensureLifecycleSynced(inMemory);
            return inMemory.exitCode;
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return null;
        return task.exitCode;
    }

    @Override
    public Map<String, Object> getStatusMap(String taskId) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            TaskLifecycle lc = lifecycles.get(taskId);
            if (lc != null) {
                ensureLifecycleSynced(inMemory);
                TaskObservation observation = toObservation(inMemory);
                Map<String, Object> map = observation.toMap();
                map.put("runId", inMemory.runId);
                map.put("threadId", inMemory.threadId);
                map.put("threadName", inMemory.threadName);
                map.put("pid", Integer.valueOf(inMemory.pid));
                map.put("pgid", Integer.valueOf(inMemory.pgid));
                map.put("exitCode", inMemory.exitCode);
                map.put("cwd", inMemory.cwd);
                map.put("hostInstanceId", inMemory.hostInstanceId);
                return map;
            }
            return runner.getStatusMap(taskId);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return null;
        withTaskLockVoid(taskId, () -> {
            String before = deriveLegacyStatusForTask(task);
            refreshDiskTask(task);
            String after = deriveLegacyStatusForTask(task);
            if (!equalsValue(before, after)) {
                saveMeta(task);
            }
        });
        TaskObservation observation = toObservation(task);
        Map<String, Object> map = observation.toMap();
        map.put("runId", task.runId);
        map.put("threadId", task.threadId);
        map.put("threadName", task.threadName);
        map.put("pid", Integer.valueOf(task.pid));
        map.put("pgid", Integer.valueOf(task.pgid));
        map.put("exitCode", task.exitCode);
        map.put("cwd", task.cwd);
        map.put("hostInstanceId", task.hostInstanceId);
        return map;
    }

    @Override
    public void releaseTask(String taskId) {
        // Admin UI/API needs to inspect task history; eviction happens via
        // archiveTask() based on retention policy.
    }

    @Override

    public void shutdown() {
        runner.shutdown();
    }

    // ---- Additional methods (not in TaskRunner) ----

    public void init() {
        List<Task> all = loadAllTasks();
        // Prime the in-memory index from the scan recovery is about to walk (one scan per start);
        // the per-task saveMeta below then refreshes entries with the recovered state.
        synchronized (indexLock) {
            if (!indexLoaded) {
                for (Task task : all) {
                    indexEntries.put(task.taskId, TaskIndexEntry.fromTask(task, lifecycles.get(task.taskId)));
                }
                indexLoaded = true;
            }
        }
        for (Task task : all) {
            if (!isTransientStatus(task.status)) {
                // For terminal tasks loaded from disk, restore lifecycle
                TaskLifecycle lc = lifecycles.get(task.taskId);
                if (lc == null) {
                    lc = TaskLifecycle.normalizeFromRunner(task);
                    if (lc.isTerminal() && !lc.isPersisted()) lc.markPersisted();   // guard on the invariant, not status
                    lifecycles.put(task.taskId, lc);
                }
                continue;
            }

            withTaskLockVoid(task.taskId, () -> {
                refreshOutputTimestamps(task);

                // Ensure lifecycle exists (migrate if needed)
                TaskLifecycle lc = lifecycles.get(task.taskId);
                if (lc == null) {
                    lc = TaskLifecycle.normalizeFromRunner(task);
                    lifecycles.put(task.taskId, lc);
                }

                if (task.pid > 0) {
                    java.util.Optional<ProcessHandle> handleOpt = ProcessHandle.of(task.pid);
                    if (handleOpt.isPresent() && handleOpt.get().isAlive()) {
                        // Process is alive — verify identity via pidStartTime
                        java.util.Optional<Instant> startInstantOpt = handleOpt.get().info().startInstant();
                        if (startInstantOpt.isPresent() && task.pidStartTime > 0) {
                            long currentStartMs = startInstantOpt.get().toEpochMilli();
                            if (Math.abs(currentStartMs - task.pidStartTime) < 1000) {
                                // Identity confirmed
                                syncStatusFromLifecycle(task);
                                task.alive = true;
                            } else {
                                // PID reuse detected
                                lc.tryTransitionToLost(TaskLossReason.PID_REUSED);
                                syncStatusFromLifecycle(task);
                                task.alive = false;
                                if (task.endTime == null) {
                                    task.endTime = Long.valueOf(System.currentTimeMillis());
                                }
                            }
                        } else {
                            // pidStartTime absent or startInstant unavailable — cannot verify identity,
                            // but process is alive. Treat as RUNNING (unverified) rather than LOST,
                            // since false-LOST (losing visibility) is costlier than false-RUNNING.
                            syncStatusFromLifecycle(task);
                            task.alive = true;
                        }
                    } else {
                        // Process dead
                        finalizeExitedTask(task);
                    }
                } else {
                    // No PID recorded
                    finalizeExitedTask(task);
                }
                saveMeta(task);
            });
        }
    }

    public void archiveExpiredTasks() {
        long now = System.currentTimeMillis();
        // Candidates come from the in-memory index (entries carry status/times/archived), so this
        // periodic sweep no longer re-reads every retained task's JSON from disk. Only actionable
        // tasks are materialized, and their fresh state is re-checked before acting.
        for (TaskIndexEntry entry : snapshotIndexEntries()) {
            if (isTransientStatus(statusFromString(entry.status))) {
                // A transient task this runner does not own is a restart-restored task, and nothing
                // else notices its process exiting anymore (the runs tables stopped materializing
                // tasks — taskStatusesByRun serves them from entries): without this refresh it
                // would show "running" forever and never age into archive/purge. Tasks the runner
                // owns are skipped (they finalize via waitForCompletion/getTask), so the disk cost
                // is bounded by the number of restored still-active tasks.
                if (runner.getTask(entry.taskId) == null) {
                    refreshRestoredTask(entry.taskId);
                }
                continue;
            }
            long completedAt = entry.endTime != null ? entry.endTime.longValue() : entry.startTime;
            long ageMs = now - completedAt;
            boolean archive = !entry.archived && retentionMs >= 0 && ageMs >= retentionMs;
            boolean purge = entry.archived && archiveRetentionMs >= 0 && ageMs >= archiveRetentionMs;
            if (!archive && !purge) {
                continue;
            }
            Task task = getTask(entry.taskId);
            if (task == null) {
                // Ghost entry: the task dir vanished (or its JSON is unreadable). Drop it from the
                // index — if the task is ever saved again, updateTaskIndex re-adds it.
                removeTaskIndex(entry.taskId);
                continue;
            }
            if (task.alive || isTransientStatus(task.status)) {
                continue;
            }
            if (task.archived) {
                if (purge) {
                    deleteArchivedTask(task);
                }
            } else if (archive) {
                archiveTask(task);
            }
        }
    }

    /**
     * Reload a restored (runner-unowned) task from disk and re-derive its status from process
     * liveness, persisting a change — which also refreshes its index entry. The index entry of a
     * task whose directory vanished is dropped.
     */
    private void refreshRestoredTask(String taskId) {
        Task task = getTaskFromDisk(taskId);
        if (task == null) {
            removeTaskIndex(taskId);
            return;
        }
        withTaskLockVoid(taskId, () -> {
            String before = deriveLegacyStatusForTask(task);
            refreshDiskTask(task);
            String after = deriveLegacyStatusForTask(task);
            if (!equalsValue(before, after)) {
                saveMeta(task);
            }
        });
    }

    public List<Task> queryTasks(String runId, String status, int offset, int limit) {
        List<TaskIndexEntry> entries = queryTaskIndex(runId, status, offset, limit);
        List<Task> tasks = new ArrayList<>();
        for (TaskIndexEntry entry : entries) {
            Task task = getTask(entry.taskId);
            if (task == null) {
                continue;
            }
            // Refresh disk-loaded tasks that may have stale status
            if (runner.getTask(entry.taskId) == null) {
                withTaskLockVoid(entry.taskId, () -> {
                    String before = deriveLegacyStatusForTask(task);
                    refreshDiskTask(task);
                    String after = deriveLegacyStatusForTask(task);
                    if (!equalsValue(before, after)) {
                        saveMeta(task);
                    }
                });
            }
            tasks.add(task);
        }
        return tasks;
    }

    public List<Task> listTasks() {
        return queryTasks(null, null, 0, -1);
    }

    public int killRun(String runId) {
        int killed = 0;
        List<Task> tasks = queryTasks(runId, null, 0, -1);
        for (Task task : tasks) {
            if (killTask(task.taskId)) {
                killed++;
            }
        }
        return killed;
    }

    /**
     * Remove every task owned by a terminal Run before that same runId is executed again by the
     * debugger. Reusing the Run record without this reset would mix old and new task rows (and a
     * detached process from the previous attempt could keep running under the shared identity).
     */
    public void clearRunTasks(String runId) {
        if (runId == null) {
            return;
        }
        List<String> taskIds = new ArrayList<>();
        for (TaskIndexEntry entry : snapshotIndexEntries()) {
            if (runId.equals(entry.runId)) {
                taskIds.add(entry.taskId);
            }
        }
        for (String taskId : taskIds) {
            // killTask is also the restored-process path. A terminal task simply returns false
            // (or true when already KILLED), after which it is safe to remove its persisted row.
            killTask(taskId);
            withTaskLockVoid(taskId, () -> {
                Task task = getTask(taskId);
                if (task == null) {
                    removeTaskIndex(taskId);
                    return;
                }
                if (task.alive || isTransientStatus(task.status)) {
                    throw new IllegalStateException("Task " + taskId + " for debug run " + runId
                        + " is still active after termination");
                }
                removeRunnerTask(taskId);
                lifecycles.remove(taskId);
                deleteQuietly(task.taskDir);
                if (task.taskDir != null && task.taskDir.exists()) {
                    throw new IllegalStateException("Failed to clear task " + taskId
                        + " before reusing debug run " + runId);
                }
                removeTaskIndex(taskId);
            });
            taskLocks.remove(taskId);
        }
    }

    // ---- Process termination for disk-loaded tasks ----

    /**
     * Terminate a task restored from disk after server restart.
     * Uses PGID-based kill only when the task is its own process group leader
     * (pgid == pid), which is the case for nohup-launched processes.
     * If the task shares a group with another process (e.g., the TeeBox JVM),
     * falls back to individual process + descendant kill to avoid killing
     * unrelated processes.
     */
    private void terminateRestoredTask(Task task) {
        if (!isProcessAlive(task.pid)) {
            return;
        }

        // Only use PGID kill when task IS the group leader (pgid == pid).
        // This is safe because the process group contains only the task's tree.
        if (task.pgid > 0 && task.pgid == task.pid) {
            sendSignalToGroup(task.pgid, "TERM");
            waitForProcessExit(task.pid, 1000L);
            if (isProcessAlive(task.pid)) {
                sendSignalToGroup(task.pgid, "KILL");
                waitForProcessExit(task.pid, 1000L);
            }
            return;
        }

        // Task shares a process group with others — kill descendants first, then parent.
        // Descendants must be killed before parent, because killing the parent first
        // causes children to be re-parented to init (PID 1) and lost from the process tree.
        ProcessHandle.of(task.pid).ifPresent(h ->
            h.descendants().forEach(d -> d.destroy())
        );
        sendSignal(task.pid, "TERM");
        waitForProcessExit(task.pid, 1000L);
        if (isProcessAlive(task.pid)) {
            ProcessHandle.of(task.pid).ifPresent(h ->
                h.descendants().forEach(ProcessHandle::destroyForcibly)
            );
            sendSignal(task.pid, "KILL");
            waitForProcessExit(task.pid, 1000L);
        }
    }

    private boolean isProcessAlive(int pid) {
        if (IS_WINDOWS) {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
        try {
            Process process = new ProcessBuilder("kill", "-0", String.valueOf(pid)).start();
            boolean alive = process.waitFor() == 0;
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            return alive;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendSignal(int pid, String signal) {
        if (IS_WINDOWS) {
            ProcessHandle.of(pid).ifPresent(h -> {
                if ("KILL".equals(signal)) {
                    h.destroyForcibly();
                } else {
                    h.destroy();
                }
            });
            return;
        }
        try {
            Process process = new ProcessBuilder("kill", "-" + signal, String.valueOf(pid)).start();
            process.waitFor();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (Exception e) {
            // best-effort
        }
    }

    private void sendSignalToGroup(int pgid, String signal) {
        if (IS_WINDOWS) {
            // Windows has no process groups; emulate by killing the leader and its descendants
            ProcessHandle.of(pgid).ifPresent(h -> {
                h.descendants().forEach(d -> {
                    if ("KILL".equals(signal)) {
                        d.destroyForcibly();
                    } else {
                        d.destroy();
                    }
                });
                if ("KILL".equals(signal)) {
                    h.destroyForcibly();
                } else {
                    h.destroy();
                }
            });
            return;
        }
        try {
            Process process = new ProcessBuilder("kill", "-" + signal, "--", "-" + pgid).start();
            process.waitFor();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (Exception e) {
            TeeBoxLog.warn("TaskEngine", "Failed to signal process group " + pgid, e);
        }
    }

    private void waitForProcessExit(int pid, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (isProcessAlive(pid) && (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- Persistence methods ----

    private void saveMeta(Task task) {
        if (task.metaFile == null) {
            File taskDir = new File(tasksDir, "task-" + task.taskId);
            task.bindFiles(taskDir);
        }
        // Mark persisted before writing so the JSON on disk reflects the true state.
        // If the write fails we throw RuntimeException, so a stale in-memory flag
        // is the safer direction (blocks kill-wins rather than allowing it).
        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc != null && lc.isTerminal() && !lc.isPersisted()) {
            lc.markPersisted();
        }
        try {
            JsonObject obj = gson.toJsonTree(task).getAsJsonObject();
            if (lc != null) lc.writeToJson(obj);
            // Atomic: a truncated meta.json would lose the running process's tracking record
            // (pid/startInstant) across a restart.
            AtomicFiles.write(task.metaFile, gson.toJson(obj));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write task metadata: " + e.getMessage(), e);
        }
        updateTaskIndex(task);
    }

    private void updateTaskIndex(Task task) {
        if (task == null || task.taskId == null) {
            return;
        }
        synchronized (indexLock) {
            ensureIndexLoadedLocked();
            indexEntries.put(task.taskId, TaskIndexEntry.fromTask(task, lifecycles.get(task.taskId)));
        }
    }

    private List<TaskIndexEntry> queryTaskIndex(String runId, String status, int offset, int limit) {
        List<TaskIndexEntry> entries = snapshotIndexEntries();
        sortTaskIndexEntries(entries);
        List<TaskIndexEntry> filtered = new ArrayList<>();
        for (TaskIndexEntry entry : entries) {
            if (runId != null && !equalsValue(runId, entry.runId)) {
                continue;
            }
            if (status != null && !equalsIgnoreCase(status, entry.status)) {
                continue;
            }
            filtered.add(entry);
        }
        return applyTaskPagination(filtered, offset, limit);
    }

    /**
     * Status strings of every task of each given run, served from the in-memory index alone — no
     * task materialization, no disk. Runs with no tasks are absent from the map. Used by the admin
     * runs tables, which only need per-run task counts and killed/lost tallies; materializing each
     * run's tasks there cost a disk read per archived task per row.
     */
    public Map<String, List<String>> taskStatusesByRun(java.util.Collection<String> runIds) {
        Set<String> wanted = new HashSet<>(runIds);
        Map<String, List<String>> result = new java.util.HashMap<>();
        for (TaskIndexEntry entry : snapshotIndexEntries()) {
            if (entry.runId == null || !wanted.contains(entry.runId)) {
                continue;
            }
            List<String> statuses = result.get(entry.runId);
            if (statuses == null) {
                statuses = new ArrayList<>();
                result.put(entry.runId, statuses);
            }
            statuses.add(entry.status);
        }
        return result;
    }

    private List<TaskIndexEntry> snapshotIndexEntries() {
        synchronized (indexLock) {
            ensureIndexLoadedLocked();
            return new ArrayList<>(indexEntries.values());
        }
    }

    // First index access builds the map from a full directory scan; afterwards every index
    // mutation is incremental. init() primes it from the scan recovery already walks, so a normal
    // server start scans the tasks directory exactly once.
    private void ensureIndexLoadedLocked() {
        if (indexLoaded) {
            return;
        }
        for (Task task : loadAllTasks()) {
            indexEntries.put(task.taskId, TaskIndexEntry.fromTask(task, lifecycles.get(task.taskId)));
        }
        indexLoaded = true;
    }

    private List<TaskIndexEntry> applyTaskPagination(List<TaskIndexEntry> entries, int offset, int limit) {
        int safeOffset = offset < 0 ? 0 : offset;
        if (safeOffset >= entries.size()) {
            return new ArrayList<>();
        }
        int end = limit <= 0 ? entries.size() : Math.min(entries.size(), safeOffset + limit);
        return new ArrayList<>(entries.subList(safeOffset, end));
    }

    private void sortTaskIndexEntries(List<TaskIndexEntry> entries) {
        Collections.sort(entries, new Comparator<TaskIndexEntry>() {
            @Override
            public int compare(TaskIndexEntry a, TaskIndexEntry b) {
                if (a.startTime == b.startTime) {
                    String aId = a.taskId != null ? a.taskId : "";
                    String bId = b.taskId != null ? b.taskId : "";
                    return aId.compareTo(bId);
                }
                return a.startTime < b.startTime ? 1 : -1;
            }
        });
    }

    private void removeTaskIndex(String taskId) {
        if (taskId == null) {
            return;
        }
        synchronized (indexLock) {
            ensureIndexLoadedLocked();
            indexEntries.remove(taskId);
        }
    }

    // Pre-1.14 TeeBox kept a tasks/index.json rewritten on every task save. Nothing reads it
    // anymore; delete a leftover so a rollback to an older version rebuilds a fresh index from the
    // task dirs instead of trusting a stale one that would hide (and never archive/purge) tasks
    // written since. Same fail-fast contract as RunStore's legacy run index: if the file cannot be
    // deleted after retries, refuse to start rather than arm that rollback hazard.
    private void deleteLegacyIndexFiles() {
        File legacyIndex = new File(tasksDir, "index.json");
        if (legacyIndex.isFile()) {
            deleteInsistently(legacyIndex);
            if (legacyIndex.exists()) {
                throw new IllegalStateException("Failed to delete legacy task index "
                        + legacyIndex.getAbsolutePath() + " — refusing to start: task listing no"
                        + " longer maintains this file, and a stale copy would make a rolled-back"
                        + " (pre-1.14) TeeBox hide tasks written since. Remove the file manually.");
            }
            TeeBoxLog.info("TaskEngine", "Removed legacy task index (tasks are indexed in memory now): "
                    + legacyIndex.getAbsolutePath());
        }
        File legacyTmp = new File(tasksDir, "index.json.tmp");
        if (legacyTmp.isFile()) {
            legacyTmp.delete();
        }
    }

    // Same transient-hold reasoning as RunStore.deleteInsistently: an external scanner may briefly
    // hold the file on Windows — retry with backoff (20/40/80/160ms between 5 attempts, no sleep
    // after the last), then let the caller re-check existence.
    private void deleteInsistently(File file) {
        long delayMs = 20L;
        for (int attempt = 1; ; attempt++) {
            if (file.delete() || !file.exists()) {
                return;
            }
            if (attempt >= 5) {
                return;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            delayMs *= 2;
        }
    }

    // ---- Task loading ----

    private List<Task> loadAllTasks() {
        File[] dirs = tasksDir.listFiles();
        if (dirs == null) return new ArrayList<>();

        Arrays.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        List<Task> tasks = new ArrayList<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Task task = loadTask(dir);
            if (task != null) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private Task loadTask(File taskDir) {
        File metaFile = new File(taskDir, "meta.json");
        File archiveFile = new File(taskDir, "archive.json");
        if (!metaFile.exists() && !archiveFile.exists()) return null;

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(metaFile.exists() ? metaFile : archiveFile);
            String json = readStream(fis);
            Task task = gson.fromJson(json, Task.class);
            if (task == null) return null;
            task.bindFiles(taskDir);
            if (!metaFile.exists() && archiveFile.exists()) {
                task.archived = true;
            }
            // Parse lifecycle from JSON (if present) or migrate from task status.
            // Skip populating in-memory lifecycles for archived tasks — they are
            // terminal and persisted on disk; keeping them in memory would
            // re-leak after archiveTask() removes them.
            if (!task.archived) {
                try {
                    JsonObject obj = gson.fromJson(json, JsonObject.class);
                    TaskLifecycle lc = TaskLifecycle.readFromJson(obj);
                    if (lc != null) {
                        lifecycles.put(task.taskId, lc);
                    } else if (!lifecycles.containsKey(task.taskId)) {
                        lc = TaskLifecycle.normalizeFromRunner(task);
                        // markPersisted only when the rebuilt lifecycle is actually terminal — never infer
                        // "terminal" from status alone (an unknown/null status normalizes to ACTIVE).
                        if (lc.isTerminal() && !lc.isPersisted()) {
                            lc.markPersisted();
                        }
                        lifecycles.put(task.taskId, lc);
                    }
                } catch (Exception e) {
                    TeeBoxLog.warn("TaskEngine", "Failed to parse lifecycle for task " + task.taskId, e);
                    if (!lifecycles.containsKey(task.taskId)) {
                        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
                        if (lc.isTerminal() && !lc.isPersisted()) {   // invariant, not status-based
                            lc.markPersisted();
                        }
                        lifecycles.put(task.taskId, lc);
                    }
                }
            }
            return task;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(fis);
        }
    }

    private Task getTaskFromDisk(String taskId) {
        if (taskId == null) return null;
        File taskDir = new File(tasksDir, "task-" + taskId);
        if (!taskDir.exists()) return null;
        return loadTask(taskDir);
    }

    // ---- Archival ----

    /** Remove the in-memory task entry from the underlying runner.
     *  Called after archive so the runner's tasks map doesn't grow unboundedly. */
    private void removeRunnerTask(String taskId) {
        if (runner instanceof UnixTaskRunner) {
            ((UnixTaskRunner) runner).removeTask(taskId);
        } else if (runner instanceof SimulatedTaskRunner) {
            ((SimulatedTaskRunner) runner).removeTask(taskId);
        }
    }

    private void archiveTask(Task task) {
        if (task == null || task.archived) {
            return;
        }
        task.archived = true;
        task.alive = false;
        // Read only the tail of each file to avoid OOM on huge outputs.
        // 256KB is generous for ~50 lines / ~20 lines of typical output.
        task.stdoutTail = tailLines(readFileTail(task.stdoutFile, 256 * 1024), 50);
        task.stderrTail = tailLines(readFileTail(task.stderrFile, 256 * 1024), 20);

        try {
            JsonObject obj = gson.toJsonTree(task).getAsJsonObject();
            TaskLifecycle lc = lifecycles.get(task.taskId);
            if (lc != null) lc.writeToJson(obj);
            AtomicFiles.write(task.archiveFile, gson.toJson(obj));
        } catch (IOException e) {
            throw new RuntimeException("Failed to archive task " + task.taskId + ": " + e.getMessage(), e);
        }

        deleteQuietly(task.metaFile);
        deleteQuietly(task.stdoutFile);
        deleteQuietly(task.stderrFile);
        deleteQuietly(task.exitCodeFile);
        deleteQuietly(task.commandPidFile);
        deleteQuietly(task.commandFile);
        updateTaskIndex(task);

        // Release in-memory state once the task is durably archived to disk.
        // Subsequent queries will load from archive.json on demand.
        removeRunnerTask(task.taskId);
        lifecycles.remove(task.taskId);
        taskLocks.remove(task.taskId);
    }

    private void deleteArchivedTask(Task task) {
        if (task == null || task.taskDir == null) {
            return;
        }
        removeTaskIndex(task.taskId);
        lifecycles.remove(task.taskId);
        taskLocks.remove(task.taskId);
        removeRunnerTask(task.taskId);
        deleteQuietly(task.archiveFile);
        deleteQuietly(task.metaFile);
        deleteQuietly(task.stdoutFile);
        deleteQuietly(task.stderrFile);
        deleteQuietly(task.exitCodeFile);
        deleteQuietly(task.commandPidFile);
        deleteQuietly(task.commandFile);
        deleteQuietly(task.taskDir);
    }

    // ---- Task refresh for disk-loaded tasks ----

    private void refreshDiskTask(Task task) {
        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc != null && lc.isTerminal()) {
            syncStatusFromLifecycle(task);
            return;
        }
        if (!task.alive && task.status != TaskStatus.STARTING && task.status != TaskStatus.RUNNING) {
            return;
        }
        refreshOutputTimestamps(task);

        if (task.pid > 0) {
            java.util.Optional<ProcessHandle> handleOpt = ProcessHandle.of(task.pid);
            if (handleOpt.isPresent() && handleOpt.get().isAlive()) {
                task.alive = true;
                if (lc != null) {
                    syncStatusFromLifecycle(task);
                } else {
                    task.status = TaskStatus.RUNNING;
                }
                return;
            }
        }

        finalizeExitedTask(task);
    }

    /**
     * Sync lifecycle for an in-memory task whose runner has already finalized it
     * (alive=false, exitCode/status set). Unlike finalizeExitedTask(), does not
     * try to read exit code from disk — the runner already has the authoritative state.
     */
    /**
     * Sync lifecycle for an in-memory task whose runner has already finalized it
     * (alive=false, status/exitCode set). The runner's status is authoritative
     * for in-memory tasks since it manages the process directly.
     */
    private void finalizeInMemoryTask(Task task) {
        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc == null) {
            lc = TaskLifecycle.normalizeFromRunner(task);
            lifecycles.put(task.taskId, lc);
            return;
        }
        if (lc.isTerminal()) {
            syncStatusFromLifecycle(task);
            return;
        }
        if (task.endTime == null) {
            task.endTime = Long.valueOf(System.currentTimeMillis());
        }
        // The runner has already determined the terminal status — use it directly.
        // For LOST, attempt exit code recovery with a grace period before accepting.
        if (task.status == TaskStatus.COMPLETED) {
            lc.tryTransitionToCompleted();
        } else if (task.status == TaskStatus.FAILED) {
            lc.tryTransitionToFailed();
        } else if (task.status == TaskStatus.KILLED) {
            lc.tryTransitionToKilled();
        } else {
            // Runner returned LOST or unknown — try to read exit code ourselves
            Integer exitCode = task.exitCode;
            if (exitCode == null) {
                exitCode = readExitCodeWithGrace(task, 500L);
                if (exitCode != null) {
                    task.exitCode = exitCode;
                }
            }
            if (exitCode != null) {
                if (exitCode.intValue() == 0) {
                    lc.tryTransitionToCompleted();
                } else {
                    lc.tryTransitionToFailed();
                }
            } else {
                lc.tryTransitionToLost(TaskLossReason.PROCESS_MISSING);
            }
        }
        syncStatusFromLifecycle(task);
    }

    private void finalizeExitedTask(Task task) {
        task.alive = false;
        refreshOutputTimestamps(task);

        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc != null && lc.isTerminal()) {
            // Already terminal (e.g. KILLED) — preserve
            if (task.endTime == null) {
                task.endTime = Long.valueOf(System.currentTimeMillis());
            }
            syncStatusFromLifecycle(task);
            return;
        }

        Integer exitCode = readExitCodeWithGrace(task, 500L);
        if (exitCode != null) {
            task.exitCode = exitCode;
            if (task.endTime == null) {
                task.endTime = Long.valueOf(System.currentTimeMillis());
            }
            if (lc != null) {
                if (exitCode.intValue() == 0) {
                    lc.tryTransitionToCompleted();
                } else {
                    lc.tryTransitionToFailed();
                }
                syncStatusFromLifecycle(task);
            } else {
                task.status = exitCode.intValue() == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED;
            }
            return;
        }

        if (task.endTime == null) {
            task.endTime = Long.valueOf(System.currentTimeMillis());
        }
        if (lc != null) {
            lc.tryTransitionToLost(TaskLossReason.PROCESS_MISSING);
            syncStatusFromLifecycle(task);
        } else {
            task.status = TaskStatus.LOST;
        }
    }

    private void refreshOutputTimestamps(Task task) {
        if (task.stdoutFile != null) {
            task.lastStdoutAt = task.stdoutFile.exists() ? Long.valueOf(task.stdoutFile.lastModified()) : null;
        }
        if (task.stderrFile != null) {
            task.lastStderrAt = task.stderrFile.exists() ? Long.valueOf(task.stderrFile.lastModified()) : null;
        }
    }

    private Integer readExitCode(Task task) {
        if (task.exitCodeFile == null || !task.exitCodeFile.exists()) return null;
        try {
            String value = readFile(task.exitCodeFile).trim();
            if (value.length() == 0) return null;
            return Integer.valueOf(Integer.parseInt(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer readExitCodeWithGrace(Task task, long graceMs) {
        long start = System.currentTimeMillis();
        Integer exitCode = readExitCode(task);
        while (exitCode == null && (System.currentTimeMillis() - start) < graceMs) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            exitCode = readExitCode(task);
        }
        return exitCode;
    }

    // ---- Observation ----

    private static final long LARGE_OUTPUT_THRESHOLD = 10L * 1024L * 1024L;

    private TaskObservation toObservation(Task task) {
        TaskObservation observation = new TaskObservation();
        observation.taskId = task.taskId;
        // Use lifecycle-derived status when available
        TaskLifecycle lc = lifecycles.get(task.taskId);
        observation.status = lc != null ? lc.deriveLegacyStatus()
                : (task.status != null ? task.status.value() : null);
        observation.alive = task.alive;
        observation.elapsedMs = (task.endTime != null ? task.endTime.longValue() : System.currentTimeMillis()) - task.startTime;
        observation.lastStdoutAt = task.lastStdoutAt;
        observation.lastStderrAt = task.lastStderrAt;
        observation.lastOutputAgeMs = getLastOutputAge(task);
        observation.timeoutExceeded = task.timeoutMs > 0 && observation.elapsedMs > task.timeoutMs;

        if (observation.timeoutExceeded) {
            observation.healthHints.add("TIMEOUT_EXCEEDED");
        }
        if (lc != null && lc.getTerminalState() == TaskTerminalState.LOST) {
            observation.healthHints.add("PROCESS_NOT_FOUND");
        } else if (lc == null && task.status == TaskStatus.LOST) {
            observation.healthHints.add("PROCESS_NOT_FOUND");
        }
        if (task.alive && task.pidStartTime <= 0) {
            observation.healthHints.add("IDENTITY_UNVERIFIED");
        }
        if (!task.archived) {
            long outputSize = getFileSize(task.stdoutFile) + getFileSize(task.stderrFile);
            if (outputSize > LARGE_OUTPUT_THRESHOLD) {
                observation.healthHints.add("LARGE_OUTPUT");
            }
        }
        return observation;
    }

    private long getFileSize(File file) {
        return file != null && file.exists() ? file.length() : 0L;
    }

    private Long getLastOutputAge(Task task) {
        Long mostRecent = null;
        if (task.lastStdoutAt != null) {
            mostRecent = task.lastStdoutAt;
        }
        if (task.lastStderrAt != null && (mostRecent == null || task.lastStderrAt.longValue() > mostRecent.longValue())) {
            mostRecent = task.lastStderrAt;
        }
        if (mostRecent == null) {
            return Long.valueOf(System.currentTimeMillis() - task.startTime);
        }
        return Long.valueOf(System.currentTimeMillis() - mostRecent.longValue());
    }

    // ---- Utility methods ----

    private boolean isTransientStatus(TaskStatus status) {
        // A null/unknown status is not a settled terminal status, so treat it as transient (not-yet-
        // terminal). Recovery then routes such a task through liveness reconciliation instead of
        // force-marking it persisted-terminal — which threw "Cannot mark persisted: not terminal",
        // since normalizeFromRunner maps a null status to ACTIVE, not TERMINAL.
        return status == null || status.isTransient();
    }

    private String deriveLegacyStatusForTask(Task task) {
        TaskLifecycle lc = lifecycles.get(task.taskId);
        if (lc != null) return lc.deriveLegacyStatus();
        return task.status != null ? task.status.value() : null;
    }

    private boolean equalsValue(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private boolean equalsIgnoreCase(String a, String b) {
        if (a == null) return b == null;
        if (b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private String tailLines(String text, int maxLines) {
        if (text == null || text.length() == 0 || maxLines <= 0) {
            return "";
        }
        String[] lines = text.split("\\r?\\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private long parseDurationProperty(String name, long defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.trim().length() == 0) {
            return defaultValue;
        }
        try {
            return DurationParser.parseMillis(raw);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private String readFile(File file) {
        if (file == null || !file.exists()) return "";
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            return readStream(fis);
        } catch (IOException e) {
            return "";
        } finally {
            closeQuietly(fis);
        }
    }

    /** Read only the last `maxBytes` bytes of a file. Returns the suffix as a UTF-8 string.
     *  Used during archive to avoid loading multi-GB stdout/stderr into heap. */
    private String readFileTail(File file, int maxBytes) {
        if (file == null || !file.exists() || maxBytes <= 0) return "";
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(file, "r");
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            int toRead = (int) Math.min(maxBytes, len - start);
            byte[] buf = new byte[toRead];
            int total = 0;
            while (total < toRead) {
                int n = raf.read(buf, total, toRead - total);
                if (n <= 0) break;
                total += n;
            }
            return new String(buf, 0, total, "UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (IOException ignore) {}
            }
        }
    }

    private String readStream(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteQuietly(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            // ignore best-effort cleanup
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore
        }
    }

    // ---- Index entry ----

    private static class TaskIndexEntry {
        String taskId;
        String runId;
        String status;
        long startTime;
        Long endTime;
        boolean archived;

        static TaskIndexEntry fromTask(Task task, TaskLifecycle lifecycle) {
            TaskIndexEntry entry = new TaskIndexEntry();
            entry.taskId = task.taskId;
            entry.runId = task.runId;
            entry.status = lifecycle != null ? lifecycle.deriveLegacyStatus()
                    : (task.status != null ? task.status.value() : null);
            entry.startTime = task.startTime;
            entry.endTime = task.endTime;
            entry.archived = task.archived;
            return entry;
        }
    }
}
