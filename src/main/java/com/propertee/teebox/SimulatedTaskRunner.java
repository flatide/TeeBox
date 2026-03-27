package com.propertee.teebox;

import com.propertee.task.Task;
import com.propertee.task.TaskObservation;
import com.propertee.task.TaskRequest;
import com.propertee.task.TaskRunner;
import com.propertee.task.TaskStatus;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows-friendly TaskRunner that simulates task lifecycle without launching
 * external processes. Intended for development and UI/API testing only.
 */
public class SimulatedTaskRunner implements TaskRunner {
    private static final long WAIT_POLL_INITIAL_MS = 25L;
    private static final long WAIT_POLL_MAX_MS = 250L;
    private static final long DEFAULT_COMPLETION_DELAY_MS = 250L;

    private final File taskBaseDir;
    private final File tasksDir;
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private final AtomicInteger syntheticPidCounter = new AtomicInteger(10000);
    private final String taskIdPrefix;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<String, Task>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> completions = new ConcurrentHashMap<String, ScheduledFuture<?>>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "teebox-simulated-task-runner");
            thread.setDaemon(true);
            return thread;
        }
    });

    public SimulatedTaskRunner(String baseDir) {
        this.taskBaseDir = new File(baseDir);
        this.tasksDir = new File(taskBaseDir, "tasks");
        if (!tasksDir.exists()) {
            tasksDir.mkdirs();
        }
        this.taskIdPrefix = "sim" + new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.ENGLISH).format(new Date())
                + Integer.toHexString((int) (System.nanoTime() & 0xffff));
    }

    @Override
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
        task.status = TaskStatus.RUNNING;
        task.alive = true;
        task.startTime = System.currentTimeMillis();
        task.timeoutMs = request.timeoutMs;
        task.cwd = request.cwd;
        task.bindFiles(taskDir);
        task.pid = syntheticPidCounter.incrementAndGet();
        task.pgid = task.pid;
        task.pidStartTime = 0L;

        writeFile(task.commandFile, "# simulated task\n" + request.command + "\n");
        writeFile(task.stdoutFile,
                "[simulated-windows] command not executed: " + request.command + System.lineSeparator());
        if (!request.mergeErrorToStdout) {
            writeFile(task.stderrFile, "");
        }

        tasks.put(taskId, task);
        scheduleCompletion(task);
        refreshOutputTimestamps(task);
        return task;
    }

    @Override
    public Task getTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null) {
            refreshOutputTimestamps(task);
        }
        return task;
    }

    @Override
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
            refreshOutputTimestamps(task);
            if (!task.alive) {
                return task;
            }
            if (timeoutMs > 0 && (System.currentTimeMillis() - start) > timeoutMs) {
                return task;
            }
            long sleepMs = timeoutMs > 0
                    ? Math.min(pollMs, Math.max(1L, timeoutMs - (System.currentTimeMillis() - start)))
                    : pollMs;
            Thread.sleep(sleepMs);
            pollMs = Math.min(WAIT_POLL_MAX_MS, pollMs * 2L);
        }
    }

    @Override
    public boolean killTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || !task.alive) {
            return false;
        }
        cancelCompletion(taskId);
        synchronized (task) {
            if (!task.alive) {
                return false;
            }
            task.alive = false;
            task.status = TaskStatus.KILLED;
            task.exitCode = Integer.valueOf(-9);
            task.endTime = Long.valueOf(System.currentTimeMillis());
            writeFile(task.exitCodeFile, "-9\n");
            appendFile(task.stdoutFile, "[simulated-windows] task killed\n");
            refreshOutputTimestamps(task);
        }
        return true;
    }

    @Override
    public TaskObservation observe(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            return null;
        }
        refreshOutputTimestamps(task);
        TaskObservation observation = new TaskObservation();
        observation.taskId = task.taskId;
        observation.status = task.status != null ? task.status.value() : null;
        observation.alive = task.alive;
        observation.elapsedMs = (task.endTime != null ? task.endTime.longValue() : System.currentTimeMillis()) - task.startTime;
        observation.lastStdoutAt = task.lastStdoutAt;
        observation.lastStderrAt = task.lastStderrAt;
        observation.lastOutputAgeMs = task.lastStdoutAt != null
                ? Long.valueOf(System.currentTimeMillis() - task.lastStdoutAt.longValue())
                : Long.valueOf(System.currentTimeMillis() - task.startTime);
        observation.timeoutExceeded = task.timeoutMs > 0 && observation.elapsedMs > task.timeoutMs;
        if (observation.timeoutExceeded) {
            observation.healthHints.add("TIMEOUT_EXCEEDED");
        }
        observation.healthHints.add("SIMULATED_WINDOWS_TASK");
        return observation;
    }

    @Override
    public String getStdout(String taskId) {
        Task task = tasks.get(taskId);
        return task != null ? readFile(task.stdoutFile) : "";
    }

    @Override
    public String getStderr(String taskId) {
        Task task = tasks.get(taskId);
        return task != null ? readFile(task.stderrFile) : "";
    }

    @Override
    public String getCombinedOutput(String taskId) {
        String stdout = getStdout(taskId);
        String stderr = getStderr(taskId);
        if (stderr.length() == 0) {
            return stdout;
        }
        if (stdout.length() == 0) {
            return stderr;
        }
        return stdout + "\n" + stderr;
    }

    @Override
    public Integer getExitCode(String taskId) {
        Task task = tasks.get(taskId);
        return task != null ? task.exitCode : null;
    }

    @Override
    public Map<String, Object> getStatusMap(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            return null;
        }
        TaskObservation observation = observe(taskId);
        Map<String, Object> map = new LinkedHashMap<String, Object>(observation.toMap());
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
        for (Map.Entry<String, ScheduledFuture<?>> entry : completions.entrySet()) {
            entry.getValue().cancel(false);
        }
        completions.clear();
        scheduler.shutdownNow();
    }

    private String nextTaskId() {
        return taskIdPrefix + "-" + String.format(Locale.ENGLISH, "%04d", Integer.valueOf(taskCounter.incrementAndGet()));
    }

    private void scheduleCompletion(final Task task) {
        ScheduledFuture<?> future = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (task) {
                    if (!task.alive) {
                        return;
                    }
                    task.alive = false;
                    task.status = TaskStatus.COMPLETED;
                    task.exitCode = Integer.valueOf(0);
                    task.endTime = Long.valueOf(System.currentTimeMillis());
                    writeFile(task.exitCodeFile, "0\n");
                    refreshOutputTimestamps(task);
                }
                completions.remove(task.taskId);
            }
        }, DEFAULT_COMPLETION_DELAY_MS, TimeUnit.MILLISECONDS);
        completions.put(task.taskId, future);
    }

    private void cancelCompletion(String taskId) {
        ScheduledFuture<?> future = completions.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void refreshOutputTimestamps(Task task) {
        task.lastStdoutAt = task.stdoutFile.exists() ? Long.valueOf(task.stdoutFile.lastModified()) : null;
        task.lastStderrAt = task.stderrFile.exists() ? Long.valueOf(task.stderrFile.lastModified()) : null;
    }

    private void appendFile(File file, String content) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append file: " + file.getAbsolutePath(), e);
        } finally {
            closeQuietly(writer);
        }
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
        if (file == null || !file.exists()) {
            return "";
        }
        FileInputStream input = null;
        try {
            byte[] bytes = new byte[(int) file.length()];
            input = new FileInputStream(file);
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new String(bytes, 0, offset, "UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            closeQuietly(input);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignore) {
        }
    }
}
