package com.propertee.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.propertee.task.DefaultTaskRunner;
import com.propertee.task.Task;
import com.propertee.task.TaskObservation;
import com.propertee.task.TaskRequest;
import com.propertee.task.TaskRunner;
import com.propertee.task.TaskStatus;

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
import java.util.List;
import java.util.Map;

/**
 * Managed task engine that wraps a DefaultTaskRunner and adds persistence,
 * indexing, archival, multi-instance management, and querying.
 */
public class ManagedTaskEngine implements TaskRunner {
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    private static final long DEFAULT_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_ARCHIVE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;

    private final DefaultTaskRunner runner;
    private final File taskBaseDir;
    private final File tasksDir;
    private final String hostInstanceId;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object indexLock = new Object();
    private final File indexFile;
    private final File indexTmpFile;
    private final long retentionMs;
    private final long archiveRetentionMs;

    public ManagedTaskEngine(String baseDir, String hostInstanceId) {
        this.taskBaseDir = new File(baseDir);
        this.tasksDir = new File(taskBaseDir, "tasks");
        this.hostInstanceId = hostInstanceId;
        if (!tasksDir.exists() && !tasksDir.mkdirs()) {
            throw new IllegalStateException("Failed to create tasks directory: " + tasksDir.getAbsolutePath());
        }
        this.indexFile = new File(tasksDir, "index.json");
        this.indexTmpFile = new File(tasksDir, "index.json.tmp");
        this.retentionMs = parseDurationProperty("propertee.task.retentionMs", DEFAULT_RETENTION_MS);
        this.archiveRetentionMs = parseDurationProperty("propertee.task.archiveRetentionMs", DEFAULT_ARCHIVE_RETENTION_MS);
        this.runner = new DefaultTaskRunner(baseDir);
    }

    // ---- TaskRunner interface methods ----

    @Override
    public Task execute(TaskRequest request) {
        Task task = runner.execute(request);
        task.hostInstanceId = hostInstanceId;
        // Record pidStartTime via ProcessHandle for future init() ownership verification
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
        saveMeta(task);
        return task;
    }

    @Override
    public Task getTask(String taskId) {
        // First check in-memory (runner)
        Task task = runner.getTask(taskId);
        if (task != null) {
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
                saveMeta(task);
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
            refreshDiskTask(task);
            if (!task.alive) {
                saveMeta(task);
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
        // Try runner first (in-memory tasks from current session)
        boolean killed = runner.killTask(taskId);
        if (killed) {
            Task task = runner.getTask(taskId);
            if (task != null) {
                task.status = TaskStatus.KILLED;
                task.alive = false;
                if (task.endTime == null) {
                    task.endTime = Long.valueOf(System.currentTimeMillis());
                }
                saveMeta(task);
            }
            return true;
        }

        // Fallback: disk-loaded task (restored by init() after restart)
        Task task = getTaskFromDisk(taskId);
        if (task == null || !task.alive) {
            return false;
        }

        if (task.pid > 0) {
            terminateRestoredTask(task);
        }

        task.status = TaskStatus.KILLED;
        task.alive = false;
        task.exitCode = Integer.valueOf(-9);
        task.endTime = Long.valueOf(System.currentTimeMillis());
        saveMeta(task);
        return true;
    }

    @Override
    public TaskObservation observe(String taskId) {
        // Try in-memory first
        TaskObservation obs = runner.observe(taskId);
        if (obs != null) {
            return obs;
        }
        // Fallback to disk
        Task task = getTask(taskId);
        if (task == null) return null;
        TaskStatus before = task.status;
        refreshDiskTask(task);
        if (task.status != before) {
            saveMeta(task);
        }
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
            return runner.getExitCode(taskId);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return null;
        return task.exitCode;
    }

    @Override
    public Map<String, Object> getStatusMap(String taskId) {
        Task inMemory = runner.getTask(taskId);
        if (inMemory != null) {
            return runner.getStatusMap(taskId);
        }
        Task task = getTaskFromDisk(taskId);
        if (task == null) return null;
        TaskStatus before = task.status;
        refreshDiskTask(task);
        if (task.status != before) {
            saveMeta(task);
        }
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
                continue;
            }

            refreshOutputTimestamps(task);

            if (task.pid > 0) {
                java.util.Optional<ProcessHandle> handleOpt = ProcessHandle.of(task.pid);
                if (handleOpt.isPresent()) {
                    ProcessHandle handle = handleOpt.get();
                    if (handle.isAlive()) {
                        java.util.Optional<Instant> startInstantOpt = handle.info().startInstant();
                        if (startInstantOpt.isPresent()) {
                            long currentStartMs = startInstantOpt.get().toEpochMilli();
                            if (task.pidStartTime > 0 && Math.abs(currentStartMs - task.pidStartTime) < 1000) {
                                // startInstant matches
                                if (task.hostInstanceId != null && task.hostInstanceId.equals(hostInstanceId)) {
                                    task.status = TaskStatus.RUNNING;
                                } else {
                                    task.status = TaskStatus.DETACHED;
                                }
                                task.alive = true;
                            } else {
                                // startInstant mismatch -- PID reuse detected
                                task.status = TaskStatus.LOST;
                                task.alive = false;
                                if (task.endTime == null) {
                                    task.endTime = Long.valueOf(System.currentTimeMillis());
                                }
                            }
                        } else {
                            // startInstant unavailable but process alive
                            if (task.hostInstanceId != null && task.hostInstanceId.equals(hostInstanceId)) {
                                task.status = TaskStatus.RUNNING;
                                task.alive = true;
                            } else if (task.hostInstanceId != null) {
                                task.status = TaskStatus.DETACHED;
                                task.alive = true;
                            } else {
                                // no hostInstanceId recorded
                                task.status = TaskStatus.LOST;
                                task.alive = false;
                                if (task.endTime == null) {
                                    task.endTime = Long.valueOf(System.currentTimeMillis());
                                }
                            }
                        }
                    } else {
                        // Process dead
                        finalizeExitedTask(task);
                    }
                } else {
                    // ProcessHandle not found -- process dead
                    finalizeExitedTask(task);
                }
            } else {
                // No PID recorded
                finalizeExitedTask(task);
            }
            saveMeta(task);
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
                TaskStatus before = task.status;
                refreshDiskTask(task);
                if (task.status != before) {
                    saveMeta(task);
                }
            }
            tasks.add(task);
        }
        return tasks;
    }

    public List<Task> listTasks() {
        return queryTasks(null, null, 0, -1);
    }

    public List<Task> listDetachedTasks() {
        return queryTasks(null, "detached", 0, -1);
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
            Process process = new ProcessBuilder("kill", "-" + signal, "-" + pgid).start();
            process.waitFor();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (Exception e) {
            // best-effort
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
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(task.metaFile), "UTF-8");
            gson.toJson(task, writer);
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
            TaskIndexEntry updated = TaskIndexEntry.fromTask(task);
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
            entries.add(TaskIndexEntry.fromTask(task));
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
            System.err.println("[ManagedTaskEngine] Failed to write task index: " + e.getMessage());
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
            gson.toJson(task, writer);
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
        if (!task.alive && task.status != TaskStatus.STARTING && task.status != TaskStatus.RUNNING) {
            return;
        }
        refreshOutputTimestamps(task);

        if (task.pid > 0) {
            java.util.Optional<ProcessHandle> handleOpt = ProcessHandle.of(task.pid);
            if (handleOpt.isPresent() && handleOpt.get().isAlive()) {
                task.alive = true;
                if (task.status == TaskStatus.STARTING) {
                    task.status = TaskStatus.RUNNING;
                }
                return;
            }
        }

        finalizeExitedTask(task);
    }

    private void finalizeExitedTask(Task task) {
        task.alive = false;
        refreshOutputTimestamps(task);

        if (task.status == TaskStatus.KILLED) {
            if (task.endTime == null) {
                task.endTime = Long.valueOf(System.currentTimeMillis());
            }
            return;
        }

        Integer exitCode = readExitCodeWithGrace(task, 500L);
        if (exitCode != null) {
            task.exitCode = exitCode;
            if (task.endTime == null) {
                task.endTime = Long.valueOf(System.currentTimeMillis());
            }
            task.status = exitCode.intValue() == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED;
            return;
        }

        if (task.endTime == null) {
            task.endTime = Long.valueOf(System.currentTimeMillis());
        }
        task.status = TaskStatus.LOST;
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
        observation.status = task.status != null ? task.status.value() : null;
        observation.alive = task.alive;
        observation.elapsedMs = (task.endTime != null ? task.endTime.longValue() : System.currentTimeMillis()) - task.startTime;
        observation.lastStdoutAt = task.lastStdoutAt;
        observation.lastStderrAt = task.lastStderrAt;
        observation.lastOutputAgeMs = getLastOutputAge(task);
        observation.timeoutExceeded = task.timeoutMs > 0 && observation.elapsedMs > task.timeoutMs;

        if (observation.timeoutExceeded) {
            observation.healthHints.add("TIMEOUT_EXCEEDED");
        }
        if (task.status == TaskStatus.LOST) {
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
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
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

        static TaskIndexEntry fromTask(Task task) {
            TaskIndexEntry entry = new TaskIndexEntry();
            entry.taskId = task.taskId;
            entry.runId = task.runId;
            entry.status = task.status != null ? task.status.value() : null;
            entry.startTime = task.startTime;
            entry.endTime = task.endTime;
            entry.archived = task.archived;
            return entry;
        }
    }
}
