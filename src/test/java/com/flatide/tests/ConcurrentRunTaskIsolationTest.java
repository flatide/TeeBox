package com.flatide.tests;

import com.flatide.propertee2.task.TaskInfo;
import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.RunRequest;
import com.flatide.teebox.RunStatus;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ConcurrentRunTaskIsolationTest {
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

    @Test
    public void completedRunShouldNotCloseAnotherRunsShellTask() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires a real long-running shell process", IS_WINDOWS);
        File dataDir = Files.createTempDirectory("teebox-concurrent-task-isolation").toFile();
        RunManager manager = new RunManager(dataDir, 2);
        String longRunId = null;

        try {
            manager.registerScriptVersion(
                    "long_shell",
                    "v1",
                    "result = SHELL(\"sleep 2; echo long-finished\")\n",
                    "long shell",
                    Collections.<String>emptyList(),
                    true);
            manager.registerScriptVersion(
                    "quick_run",
                    "v1",
                    "return \"quick-finished\"\n",
                    "quick run",
                    Collections.<String>emptyList(),
                    true);

            RunInfo longRun = submit(manager, "long_shell");
            longRunId = longRun.runId;
            waitForRunningTask(manager, longRunId, 8000L);

            RunInfo quickRun = submit(manager, "quick_run");
            Assert.assertEquals(RunStatus.COMPLETED,
                    waitForTerminalRun(manager, quickRun.runId, 8000L).status);

            RunInfo completedLongRun = waitForTerminalRun(manager, longRunId, 8000L);
            Assert.assertEquals(RunStatus.COMPLETED, completedLongRun.status);
            Assert.assertTrue(completedLongRun.resultData instanceof Map);
            Map<?, ?> shellResult = (Map<?, ?>) completedLongRun.resultData;
            Assert.assertEquals(Boolean.TRUE, shellResult.get("ok"));
            Assert.assertEquals("long-finished", shellResult.get("value"));
        } finally {
            if (longRunId != null) manager.killRunTasks(longRunId);
            manager.shutdown();
        }
    }

    private static RunInfo submit(RunManager manager, String scriptId) {
        RunRequest request = new RunRequest();
        request.scriptId = scriptId;
        return manager.submit(request);
    }

    private static TaskInfo waitForRunningTask(RunManager manager, String runId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<TaskInfo> tasks = manager.listTasksForRun(runId);
            if (!tasks.isEmpty() && tasks.get(0).alive) {
                return tasks.get(0);
            }
            Thread.sleep(50L);
        }
        Assert.fail("Timed out waiting for a running task for run " + runId);
        return null;
    }

    private static RunInfo waitForTerminalRun(RunManager manager, String runId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RunInfo run = manager.getRun(runId);
            if (run != null && isTerminal(run.status)) {
                return run;
            }
            Thread.sleep(50L);
        }
        Assert.fail("Timed out waiting for terminal run " + runId);
        return null;
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED
                || status == RunStatus.FAILED
                || status == RunStatus.SERVER_RESTARTED;
    }
}
