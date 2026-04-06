package com.propertee.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.propertee.task.Task;
import com.propertee.task.TaskObservation;
import com.propertee.task.TaskRequest;
import com.propertee.task.TaskRunner;
import com.propertee.task.TaskStatus;
import com.propertee.teebox.lifecycle.TaskLifecycle;
import com.propertee.teebox.lifecycle.TaskLossReason;
import com.propertee.teebox.lifecycle.TaskPhase;
import com.propertee.teebox.lifecycle.TaskTerminalState;

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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object indexLock = new Object();
    private final File indexFile;
    private final File indexTmpFile;
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
        this.indexFile = new File(tasksDir, "index.json");
        this.indexTmpFile = new File(tasksDir, "index.json.tmp");
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
        return taskId != null ? lifecycles.get(taskId) : null;
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
    public void shutdown() {
        runner.shutdown();
    }

    // ---- Additional methods (not in TaskRunner) ----

    public void init() {
        for (Task task : loadAllTasks()) {
            if (!isTransientStatus(task.status)) {
                // For terminal tasks loaded from disk, restore lifecycle
                TaskLifecycle lc = lifecycles.get(task.taskId);
                if (lc == null) {
                    lc = TaskLifecycle.normalizeFromRunner(task);
                    lc.markPersisted();
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
        for (Task task : loadAllTasks()) {
            if (task.alive || isTransientStatus(task.status)) {
                continue;
            }
            long completedAt = task.endTime != null ? task.endTime.longValue() : task.startTime;
            long ageMs = now - completedAt;
            if (!task.archived) {
                if (retentionMs >= 0 && ageMs >= retentionMs) {
                    archiveTask(task);
                }
                continue;
            }
            if (archiveRetentionMs >= 0 && ageMs >= archiveRetentionMs) {
                deleteArchivedTask(task);
            }
        }
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
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(task.metaFile), "UTF-8");
            JsonObject obj = gson.toJsonTree(task).getAsJsonObject();
            if (lc != null) lc.writeToJson(obj);
            gson.toJson(obj, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write task metadata: " + e.getMessage(), e);
        } finally {
            closeQuietly(writer);
        }
        updateTaskIndex(task);
    }

    private void updateTaskIndex(Task task) {
        if (task == null || task.taskId == null) {
            return;
        }
        synchronized (indexLock) {
            List<TaskIndexEntry> entries = loadTaskIndexEntriesLocked();
            TaskIndexEntry updated = TaskIndexEntry.fromTask(task, lifecycles.get(task.taskId));
            boolean replaced = false;
            for (int i = 0; i < entries.size(); i++) {
                if (equalsValue(entries.get(i).taskId, task.taskId)) {
                    entries.set(i, updated);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                entries.add(updated);
            }
            sortTaskIndexEntries(entries);
            writeTaskIndexLocked(entries);
        }
    }

    private List<TaskIndexEntry> queryTaskIndex(String runId, String status, int offset, int limit) {
        List<TaskIndexEntry> filtered = new ArrayList<>();
        List<TaskIndexEntry> entries;
        synchronized (indexLock) {
            entries = loadTaskIndexEntriesLocked();
        }
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

    private List<TaskIndexEntry> applyTaskPagination(List<TaskIndexEntry> entries, int offset, int limit) {
        int safeOffset = offset < 0 ? 0 : offset;
        if (safeOffset >= entries.size()) {
            return new ArrayList<>();
        }
        int end = limit <= 0 ? entries.size() : Math.min(entries.size(), safeOffset + limit);
        return new ArrayList<>(entries.subList(safeOffset, end));
    }

    private List<TaskIndexEntry> loadTaskIndexEntriesLocked() {
        if (!indexFile.exists()) {
            return rebuildTaskIndexLocked();
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(indexFile);
            String json = readStream(fis);
            TaskIndexEntry[] parsed = gson.fromJson(json, TaskIndexEntry[].class);
            if (parsed == null) {
                return rebuildTaskIndexLocked();
            }
            List<TaskIndexEntry> entries = new ArrayList<>(Arrays.asList(parsed));
            sortTaskIndexEntries(entries);
            return entries;
        } catch (Exception e) {
            return rebuildTaskIndexLocked();
        } finally {
            closeQuietly(fis);
        }
    }

    private List<TaskIndexEntry> rebuildTaskIndexLocked() {
        List<TaskIndexEntry> entries = new ArrayList<>();
        for (Task task : loadAllTasks()) {
            entries.add(TaskIndexEntry.fromTask(task, lifecycles.get(task.taskId)));
        }
        sortTaskIndexEntries(entries);
        writeTaskIndexLocked(entries);
        return entries;
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

    private void writeTaskIndexLocked(List<TaskIndexEntry> entries) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(indexTmpFile), "UTF-8");
            gson.toJson(entries, writer);
            writer.close();
            writer = null;
            moveAtomically(indexTmpFile.toPath(), indexFile.toPath());
        } catch (IOException e) {
            TeeBoxLog.error("TaskEngine", "Failed to write task index", e);
        } finally {
            closeQuietly(writer);
        }
    }

    private void removeTaskIndex(String taskId) {
        if (taskId == null) {
            return;
        }
        synchronized (indexLock) {
            List<TaskIndexEntry> entries = loadTaskIndexEntriesLocked();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (equalsValue(entries.get(i).taskId, taskId)) {
                    entries.remove(i);
                }
            }
            writeTaskIndexLocked(entries);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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
            // Parse lifecycle from JSON (if present) or migrate from task status
            try {
                JsonObject obj = gson.fromJson(json, JsonObject.class);
                TaskLifecycle lc = TaskLifecycle.readFromJson(obj);
                if (lc != null) {
                    lifecycles.put(task.taskId, lc);
                } else if (!lifecycles.containsKey(task.taskId)) {
                    lc = TaskLifecycle.normalizeFromRunner(task);
                    if (!isTransientStatus(task.status)) {
                        lc.markPersisted();
                    }
                    lifecycles.put(task.taskId, lc);
                }
            } catch (Exception e) {
                TeeBoxLog.warn("TaskEngine", "Failed to parse lifecycle for task " + task.taskId, e);
                if (!lifecycles.containsKey(task.taskId)) {
                    TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
                    if (!isTransientStatus(task.status)) {
                        lc.markPersisted();
                    }
                    lifecycles.put(task.taskId, lc);
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

    private void archiveTask(Task task) {
        if (task == null || task.archived) {
            return;
        }
        task.archived = true;
        task.alive = false;
        task.stdoutTail = tailLines(readFile(task.stdoutFile), 50);
        task.stderrTail = tailLines(readFile(task.stderrFile), 20);

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(task.archiveFile), "UTF-8");
            JsonObject obj = gson.toJsonTree(task).getAsJsonObject();
            TaskLifecycle lc = lifecycles.get(task.taskId);
            if (lc != null) lc.writeToJson(obj);
            gson.toJson(obj, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to archive task " + task.taskId + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(writer);
        }

        deleteQuietly(task.metaFile);
        deleteQuietly(task.stdoutFile);
        deleteQuietly(task.stderrFile);
        deleteQuietly(task.exitCodeFile);
        deleteQuietly(task.commandPidFile);
        deleteQuietly(task.commandFile);
        updateTaskIndex(task);
    }

    private void deleteArchivedTask(Task task) {
        if (task == null || task.taskDir == null) {
            return;
        }
        removeTaskIndex(task.taskId);
        lifecycles.remove(task.taskId);
        taskLocks.remove(task.taskId);
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
        return status != null && status.isTransient();
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
