package com.flatide.teebox;

import com.flatide.propertee2.task.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TaskRunner wrapper that registers output watchers when tasks are created.
 * Delegates all operations to the underlying ManagedTaskEngine.
 */
class OutputWatchingTaskRunner implements TaskRunner {
    private final ManagedTaskEngine delegate;
    private final String runId;
    private final List<OutputPublishRule> outputRules;
    private final RunManager runManager;
    private final AtomicBoolean firstTaskRegistered = new AtomicBoolean(false);

    OutputWatchingTaskRunner(ManagedTaskEngine delegate, String runId,
                             List<OutputPublishRule> outputRules, RunManager runManager) {
        this.delegate = delegate;
        this.runId = runId;
        this.outputRules = outputRules;
        this.runManager = runManager;
    }

    @Override
    public Task execute(TaskRequest request) {
        Task task = delegate.execute(request);
        // Register watcher for the first task only. CAS to make this safe when
        // parallel/multi blocks invoke execute() from multiple threads.
        if (firstTaskRegistered.compareAndSet(false, true)) {
            runManager.registerOutputWatcher(task.taskId, runId, delegate.getTaskDir(task.taskId), outputRules);
        }
        return task;
    }

    @Override public Task getTask(String taskId) { return delegate.getTask(taskId); }
    @Override public Task waitForCompletion(String taskId, long timeoutMs) throws InterruptedException { return delegate.waitForCompletion(taskId, timeoutMs); }
    @Override public boolean killTask(String taskId) { return delegate.killTask(taskId); }
    @Override public TaskObservation observe(String taskId) { return delegate.observe(taskId); }
    @Override public String getStdout(String taskId) { return delegate.getStdout(taskId); }
    @Override public String getStderr(String taskId) { return delegate.getStderr(taskId); }
    @Override public String getCombinedOutput(String taskId) { return delegate.getCombinedOutput(taskId); }
    @Override public String getCombinedOutput(String taskId, int maxBytes) { return delegate.getCombinedOutput(taskId, maxBytes); }
    @Override public Integer getExitCode(String taskId) { return delegate.getExitCode(taskId); }
    @Override public Map<String, Object> getStatusMap(String taskId) { return delegate.getStatusMap(taskId); }
    @Override public void releaseTask(String taskId) { delegate.releaseTask(taskId); }
    @Override public void shutdown() { delegate.shutdown(); }
}
