package com.flatide.teebox;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskObservation;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskRunner;

import java.util.Map;

/**
 * Per-run view of a host-owned {@link TaskRunner}.
 *
 * ProperTee closes the runner attached to an interpreter when that interpreter finishes. TeeBox,
 * however, shares one {@link ManagedTaskEngine} across all concurrent runs and owns its lifecycle at
 * the server level. This wrapper delegates task operations while keeping per-run shutdown from
 * closing the shared engine.
 */
final class NonClosingTaskRunner implements TaskRunner {
    private final TaskRunner delegate;

    NonClosingTaskRunner(TaskRunner delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("TaskRunner delegate is required");
        }
        this.delegate = delegate;
    }

    @Override
    public Task execute(TaskRequest request) {
        return delegate.execute(request);
    }

    @Override
    public Task getTask(String taskId) {
        return delegate.getTask(taskId);
    }

    @Override
    public Task waitForCompletion(String taskId, long timeoutMs) throws InterruptedException {
        return delegate.waitForCompletion(taskId, timeoutMs);
    }

    @Override
    public boolean killTask(String taskId) {
        return delegate.killTask(taskId);
    }

    @Override
    public TaskObservation observe(String taskId) {
        return delegate.observe(taskId);
    }

    @Override
    public String getStdout(String taskId) {
        return delegate.getStdout(taskId);
    }

    @Override
    public String getStderr(String taskId) {
        return delegate.getStderr(taskId);
    }

    @Override
    public String getCombinedOutput(String taskId) {
        return delegate.getCombinedOutput(taskId);
    }

    @Override
    public String getCombinedOutput(String taskId, int maxBytes) {
        return delegate.getCombinedOutput(taskId, maxBytes);
    }

    @Override
    public Integer getExitCode(String taskId) {
        return delegate.getExitCode(taskId);
    }

    @Override
    public Map<String, Object> getStatusMap(String taskId) {
        return delegate.getStatusMap(taskId);
    }

    @Override
    public void releaseTask(String taskId) {
        delegate.releaseTask(taskId);
    }

    @Override
    public void shutdown() {
        // RunManager owns and closes the shared ManagedTaskEngine at server shutdown.
    }
}
