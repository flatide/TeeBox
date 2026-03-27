package com.propertee.teebox;

import com.propertee.task.Task;
import com.propertee.task.TaskObservation;
import com.propertee.task.TaskRequest;
import com.propertee.task.TaskRunner;
import com.propertee.task.TaskStatus;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unix TaskRunner used by TeeBox for Linux and macOS execution.
 */
public class UnixTaskRunner implements TaskRunner {
    private static final long WAIT_POLL_INITIAL_MS = 50L;
    private static final long WAIT_POLL_MAX_MS = 1000L;
    private static final long START_READY_WAIT_MS = 2000L;

    private final File taskBaseDir;
    private final File tasksDir;
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private final String taskIdPrefix;
    private final boolean setsidAvailable;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<String, Task>();

    public UnixTaskRunner(String baseDir) {
        this.taskBaseDir = new File(baseDir);
        this.tasksDir = new File(taskBaseDir, "tasks");
        if (!tasksDir.exists()) {
            tasksDir.mkdirs();
        }
        this.taskIdPrefix = "t" + new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.ENGLISH).format(new Date())
                + Integer.toHexString((int) (System.nanoTime() & 0xffff));
        this.setsidAvailable = checkSetsidAvailable();
    }

    private static boolean checkSetsidAvailable() {
        Process p = null;
        try {
            p = new ProcessBuilder("/bin/sh", "-c", "command -v setsid").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                try { p.getInputStream().close(); } catch (IOException ignore) {}
                try { p.getErrorStream().close(); } catch (IOException ignore) {}
                try { p.getOutputStream().close(); } catch (IOException ignore) {}
            }
        }
    }

    public Task execute(TaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Task request is required");
        }
        if (request.command == null || request.command.trim().isEmpty()) {
            throw new IllegalArgumentException("Task command is required");
        }

        String taskId = nextTaskId();
        File taskDir = new File(tasksDir, "task-" + taskId);
        if (!taskDir.exists() && !taskDir.mkdirs()) {
            throw new RuntimeException("Failed to create task directory: " + taskDir.getAbsolutePath());
        }

        Task task = new Task();
        task.taskId = taskId;
        task.runId = request.runId;
        task.threadId = request.threadId;
        task.threadName = request.threadName;
        task.command = request.command;
        task.status = TaskStatus.STARTING;
        task.alive = false;
        task.startTime = System.currentTimeMillis();
        task.timeoutMs = request.timeoutMs;
        task.cwd = request.cwd;
        task.bindFiles(taskDir);
        writeCommandFiles(task, request);
        task.pid = launchDetached(task, request);
        task.pgid = getProcessGroupId(task.pid);
        task.status = TaskStatus.RUNNING;
        awaitStartReady(task);
        refreshOutputTimestamps(task);
        tasks.put(taskId, task);
        return task;
    }

    public Task getTask(String taskId) {
        if (taskId == null) return null;
        Task task = tasks.get(taskId);
        if (task != null) {
            refreshTask(task);
        }
        return task;
    }

    public Task waitForCompletion(String taskId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        long pollMs = WAIT_POLL_INITIAL_MS;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for task");
            }

            Task task = tasks.get(taskId);
            if (task == null) {
                return null;
            }

            refreshTask(task);
            if (!task.alive) {
                return task;
            }

            if (timeoutMs > 0 && (System.currentTimeMillis() - start) > timeoutMs) {
                return task;
            }

            long sleepMs = pollMs;
            if (timeoutMs > 0) {
                long remainingMs = timeoutMs - (System.currentTimeMillis() - start);
                if (remainingMs <= 0) {
                    return task;
                }
                sleepMs = Math.min(sleepMs, remainingMs);
            }
            Thread.sleep(sleepMs);
            pollMs = Math.min(WAIT_POLL_MAX_MS, pollMs * 2L);
        }
    }

    public boolean killTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return false;

        refreshTask(task);
        if (!task.alive) {
            return false;
        }

        terminateTask(task);
        refreshTask(task);
        if (task.alive) {
            return false;
        }

        task.status = TaskStatus.KILLED;
        task.exitCode = Integer.valueOf(-9);
        task.endTime = Long.valueOf(System.currentTimeMillis());
        return true;
    }

    public TaskObservation observe(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return null;
        refreshTask(task);
        return toObservation(task);
    }

    public String getStdout(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return "";
        return readFile(task.stdoutFile);
    }

    public String getStderr(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return "";
        return readFile(task.stderrFile);
    }

    public String getCombinedOutput(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return "";
        return combineOutputs(task);
    }

    public Integer getExitCode(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return null;
        refreshTask(task);
        return task.exitCode;
    }

    public Map<String, Object> getStatusMap(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return null;

        refreshTask(task);
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

    public void shutdown() {
        // no-op: in-memory state only
    }

    // ---- internal methods ----

    private String nextTaskId() {
        return taskIdPrefix + "-" + String.format(Locale.ENGLISH, "%04d", Integer.valueOf(taskCounter.incrementAndGet()));
    }

    private void refreshTask(Task task) {
        // Already finalized
        if (!task.alive && task.status != TaskStatus.STARTING && task.status != TaskStatus.RUNNING) {
            return;
        }

        refreshOutputTimestamps(task);

        if (isProcessAlive(task.pid)) {
            task.alive = true;
            if (task.status == TaskStatus.STARTING) {
                task.status = TaskStatus.RUNNING;
            }
            return;
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
        task.lastStdoutAt = task.stdoutFile.exists() ? Long.valueOf(task.stdoutFile.lastModified()) : null;
        task.lastStderrAt = task.stderrFile.exists() ? Long.valueOf(task.stderrFile.lastModified()) : null;
    }

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
        long outputSize = getFileSize(task.stdoutFile) + getFileSize(task.stderrFile);
        if (outputSize > LARGE_OUTPUT_THRESHOLD) {
            observation.healthHints.add("LARGE_OUTPUT");
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

    private void writeCommandFiles(Task task, TaskRequest request) {
        StringBuilder command = new StringBuilder();
        command.append("#!/bin/sh\n");
        if (request.mergeErrorToStdout) {
            command.append(": > ").append(shellQuote(task.stderrFile.getAbsolutePath())).append("\n");
        }
        command.append(request.command).append("\n");
        command.append("status=$?\n");
        command.append("printf '%s\\n' \"$status\" > ").append(shellQuote(task.exitCodeFile.getAbsolutePath())).append("\n");
        command.append("exit \"$status\"\n");

        writeFile(task.commandFile, command.toString());
    }

    private int launchDetached(Task task, TaskRequest request) {
        Process process = null;
        try {
            List<String> cmd = new ArrayList<String>();
            if (setsidAvailable) {
                cmd.add("setsid");
            }
            cmd.add("/bin/sh");
            cmd.add(task.commandFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (request.cwd != null && request.cwd.length() > 0) {
                pb.directory(new File(request.cwd));
            }
            if (request.env != null && !request.env.isEmpty()) {
                pb.environment().putAll(request.env);
            }
            pb.redirectOutput(task.stdoutFile);
            if (request.mergeErrorToStdout) {
                pb.redirectErrorStream(true);
            } else {
                pb.redirectError(task.stderrFile);
            }

            process = pb.start();
            int pid = (int) process.pid();
            writeFile(task.commandPidFile, pid + "\n");
            return pid;
        } catch (Exception e) {
            throw new RuntimeException("Failed to launch task: " + e.getMessage(), e);
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }


    private Integer readCommandPid(Task task) {
        if (!task.commandPidFile.exists()) return null;
        try {
            String value = readFile(task.commandPidFile).trim();
            if (value.length() == 0) return null;
            return Integer.valueOf(Integer.parseInt(value));
        } catch (Exception e) {
            return null;
        }
    }

    private void awaitStartReady(Task task) {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < START_READY_WAIT_MS) {
            task.pgid = getProcessGroupId(task.pid);
            if (isProcessAlive(task.pid)) {
                task.alive = true;
                return;
            }

            Integer exitCode = readExitCode(task);
            if (exitCode != null) {
                finalizeExitedTask(task);
                return;
            }

            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for task start readiness", e);
            }
        }

        task.pgid = getProcessGroupId(task.pid);
        if (isProcessAlive(task.pid)) {
            task.alive = true;
            return;
        }

        Integer exitCode = readExitCodeWithGrace(task, 200L);
        if (exitCode != null) {
            finalizeExitedTask(task);
            return;
        }

        throw new RuntimeException(
            "Detached task did not reach a controllable running state (pid=" + task.pid + ")"
        );
    }

    private void terminateTask(Task task) {
        // Prefer process group kill — atomic, no race between child processes.
        if (task.pgid > 0 && task.pgid == task.pid) {
            sendSignalToGroup(task.pgid, "KILL");
            waitForExit(task, 1000L);
            if (!isProcessAlive(task.pid)) return;
        }

        // Fallback: collect all PIDs and kill in a single command to minimise race window.
        List<Integer> allPids = new ArrayList<Integer>();
        allPids.add(Integer.valueOf(task.pid));
        allPids.addAll(listDescendantPids(task.pid));
        sendSignalToAll(allPids, "KILL");
        waitForExit(task, 1000L);
        if (isProcessAlive(task.pid)) {
            allPids = new ArrayList<Integer>();
            allPids.add(Integer.valueOf(task.pid));
            allPids.addAll(listDescendantPids(task.pid));
            sendSignalToAll(allPids, "KILL");
            waitForExit(task, 1000L);
        }
    }

    private void sendSignalToAll(List<Integer> pids, String signal) {
        if (pids.isEmpty()) return;
        List<String> args = new ArrayList<String>();
        args.add("kill");
        args.add("-" + signal);
        for (Integer pid : pids) {
            args.add(String.valueOf(pid));
        }
        Process process = null;
        try {
            process = new ProcessBuilder(args).start();
            process.waitFor();
        } catch (Exception e) {
            // ignore best-effort kill
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private void waitForExit(Task task, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (isProcessAlive(task.pid) && (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Integer readExitCode(Task task) {
        if (!task.exitCodeFile.exists()) return null;
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

    // ---- process utility methods ----

    private boolean isProcessAlive(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("kill", "-0", String.valueOf(pid)).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private int getProcessGroupId(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("ps", "-o", "pgid=", "-p", String.valueOf(pid)).start();
            String value = readStream(process.getInputStream()).trim();
            if (process.waitFor() != 0 || value.length() == 0) {
                return pid;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            return pid;
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private void sendSignalToGroup(int pgid, String signal) {
        Process process = null;
        try {
            process = new ProcessBuilder("kill", "-" + signal, "--", "-" + pgid).start();
            process.waitFor();
        } catch (Exception e) {
            // ignore best-effort kill
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private void sendSignal(int pid, String signal) {
        Process process = null;
        try {
            process = new ProcessBuilder("kill", "-" + signal, String.valueOf(pid)).start();
            process.waitFor();
        } catch (Exception e) {
            // ignore best-effort kill
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
    }

    private List<Integer> listDescendantPids(int rootPid) {
        Process process = null;
        Map<Integer, List<Integer>> children = new LinkedHashMap<Integer, List<Integer>>();
        try {
            process = new ProcessBuilder("ps", "-eo", "pid=,ppid=").start();
            String output = readStream(process.getInputStream());
            if (process.waitFor() != 0) {
                return new ArrayList<Integer>();
            }

            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.length() == 0) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) continue;
                try {
                    Integer pid = Integer.valueOf(Integer.parseInt(parts[0]));
                    Integer ppid = Integer.valueOf(Integer.parseInt(parts[1]));
                    List<Integer> childList = children.get(ppid);
                    if (childList == null) {
                        childList = new ArrayList<Integer>();
                        children.put(ppid, childList);
                    }
                    childList.add(pid);
                } catch (NumberFormatException e) {
                    // ignore malformed rows
                }
            }
        } catch (Exception e) {
            return new ArrayList<Integer>();
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }

        List<Integer> descendants = new ArrayList<Integer>();
        collectDescendants(rootPid, children, descendants, new HashSet<Integer>());
        return descendants;
    }

    private void collectDescendants(int pid, Map<Integer, List<Integer>> children, List<Integer> descendants, Set<Integer> visited) {
        List<Integer> childList = children.get(Integer.valueOf(pid));
        if (childList == null) return;

        for (Integer child : childList) {
            if (!visited.add(child)) continue;
            descendants.add(child);
            collectDescendants(child.intValue(), children, descendants, visited);
        }
    }

    // ---- file utility methods ----

    private String combineOutputs(Task task) {
        String stdout = readFile(task.stdoutFile);
        String stderr = readFile(task.stderrFile);

        if (stderr.length() == 0) {
            return stdout;
        }
        if (stdout.length() == 0) {
            return stderr;
        }
        return stdout + "\n" + stderr;
    }

    private void writeFile(File file, String content) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + file.getAbsolutePath(), e);
        } finally {
            closeQuietly(writer);
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

    private String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
