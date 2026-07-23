package com.flatide.teebox;

import com.flatide.propertee2.task.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TaskRunner wrapper that registers output watchers when tasks are created.
 * Delegates all operations to the underlying ManagedTaskEngine.
 *
 * Rule-to-task binding is by SHELL() execution order within the run: each successfully
 * created task gets the next index (0, 1, 2, ...) and a rule watches the task whose index
 * equals its taskIndex (default 0 = the run's first task, the legacy behavior). The counter
 * is atomic so parallel/multi blocks invoking execute() from multiple threads assign each
 * index exactly once — but note that under parallel SHELLs the creation ORDER itself is
 * scheduling-dependent, so order-based targeting is only deterministic for sequential calls.
 */
class OutputWatchingTaskRunner implements TaskRunner {
    private final ManagedTaskEngine delegate;
    private final String runId;
    private final List<OutputPublishRule> outputRules;
    private final RunManager runManager;
    private final AtomicInteger taskCounter = new AtomicInteger(0);

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
        // Index counts successfully created tasks only (a CommandGuard rejection throws
        // above and never consumes an index).
        int index = taskCounter.getAndIncrement();
        List<OutputPublishRule> rulesForTask = new ArrayList<OutputPublishRule>();
        for (OutputPublishRule rule : outputRules) {
            if (rule.taskIndex == index) {
                rulesForTask.add(rule);
            }
        }
        if (!rulesForTask.isEmpty()) {
            runManager.registerOutputWatcher(task.taskId, runId, delegate.getTaskDir(task.taskId), rulesForTask);
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
