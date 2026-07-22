package com.flatide.teebox;

import com.flatide.propertee2.task.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TaskRunner wrapper that registers output watchers when tasks are created.
 * Delegates all operations to the underlying ManagedTaskEngine.
 *
 * Rule-to-task binding: rules with no taskKey watch the run's FIRST task (legacy
 * behavior); rules with a taskKey watch the first task launched with a matching
 * TEEBOX_TASK_KEY env value (set by the script: SHELL(cmd, {"env": {"TEEBOX_TASK_KEY":
 * "worker1"}})). "First" is CAS-guarded per binding so parallel/multi blocks invoking
 * execute() from multiple threads register exactly one watcher per binding.
 */
class OutputWatchingTaskRunner implements TaskRunner {
    /** Reserved env var a script sets on SHELL to tag the task for output-capture rules. */
    static final String TASK_KEY_ENV = "TEEBOX_TASK_KEY";

    private final ManagedTaskEngine delegate;
    private final String runId;
    private final List<OutputPublishRule> outputRules;
    private final RunManager runManager;
    private final AtomicBoolean firstTaskRegistered = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Boolean> registeredTaskKeys = new ConcurrentHashMap<String, Boolean>();

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
        List<OutputPublishRule> rulesForTask = new ArrayList<OutputPublishRule>();

        // Legacy binding: keyless rules attach to the run's first task.
        if (hasKeylessRules() && firstTaskRegistered.compareAndSet(false, true)) {
            for (OutputPublishRule rule : outputRules) {
                if (rule.taskKey == null || rule.taskKey.length() == 0) {
                    rulesForTask.add(rule);
                }
            }
        }

        // Keyed binding: rules whose taskKey matches this task's TEEBOX_TASK_KEY env value.
        String taskKey = request.env != null ? request.env.get(TASK_KEY_ENV) : null;
        if (taskKey != null && taskKey.length() > 0
                && registeredTaskKeys.putIfAbsent(taskKey, Boolean.TRUE) == null) {
            for (OutputPublishRule rule : outputRules) {
                if (taskKey.equals(rule.taskKey)) {
                    rulesForTask.add(rule);
                }
            }
        }

        if (!rulesForTask.isEmpty()) {
            runManager.registerOutputWatcher(task.taskId, runId, delegate.getTaskDir(task.taskId), rulesForTask);
        }
        return task;
    }

    private boolean hasKeylessRules() {
        for (OutputPublishRule rule : outputRules) {
            if (rule.taskKey == null || rule.taskKey.length() == 0) {
                return true;
            }
        }
        return false;
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
