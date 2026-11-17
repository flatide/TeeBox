package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.RunRequest;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunThreadInfo;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

/**
 * A function that calls itself without end stops at 64 nested calls with a runtime error. The
 * engine had no limit: such a script ran until the Java stack overflowed — inside multi the run
 * then completed with the entry still "running" — or, where each call lets another thread run
 * first, until the server's heap was full.
 */
public class CallDepthLimitTest {
    private static final String LIMIT =
        "Function calls exceeded maximum depth (64). Check for a function that calls itself without end.";

    @Test
    public void inAWorkerItIsThatThreadsError() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-depth-worker").toFile();
        RunManager runManager = new RunManager(dataDir, 2);
        try {
            // A statement ahead of the call, with another worker ready: the shape that never
            // overflowed the stack and kept growing on the heap.
            runManager.registerScriptVersion("deep", "1",
                "function down(n) do\n" +
                "    next = n + 1\n" +
                "    return down(next)\n" +
                "end\n" +
                "function count(n) do\n" +
                "    i = 0\n" +
                "    loop i < n infinite do\n" +
                "        i = i + 1\n" +
                "    end\n" +
                "    return i\n" +
                "end\n" +
                "multi r do\n" +
                "    thread a: down(1)\n" +
                "    thread b: count(5000)\n" +
                "end\n" +
                "return r\n", "deep", Arrays.asList("test"), true);
            RunRequest request = new RunRequest();
            request.scriptId = "deep";
            String runId = runManager.submit(request).runId;

            RunInfo run = waitForEnd(runManager, runId, 30000L);
            Assert.assertEquals(RunStatus.COMPLETED, run.status);
            Map<?, ?> result = (Map<?, ?>) run.resultData;
            Map<?, ?> a = (Map<?, ?>) result.get("a");
            Assert.assertEquals("error", a.get("status"));
            Assert.assertEquals(Boolean.FALSE, a.get("ok"));
            Assert.assertEquals("Runtime Error at line 3:12: " + LIMIT, a.get("value"));
            Map<?, ?> b = (Map<?, ?>) result.get("b");
            Assert.assertEquals("done", b.get("status"));

            // The run's thread list ends with the worker failed, and says why.
            RunThreadInfo failed = thread(runManager, runId, "a");
            Assert.assertEquals("ERROR", failed.state);
            Assert.assertEquals(LIMIT, failed.errorMessage);
            Assert.assertEquals("COMPLETED", thread(runManager, runId, "b").state);
        } finally {
            runManager.shutdown();
        }
    }

    @Test
    public void inTheMainFlowItFailsTheRun() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-depth-main").toFile();
        RunManager runManager = new RunManager(dataDir, 2);
        try {
            runManager.registerScriptVersion("deep", "1",
                "function down(n) do\n" +
                "    return down(n + 1)\n" +
                "end\n" +
                "return down(1)\n", "deep", Arrays.asList("test"), true);
            RunRequest request = new RunRequest();
            request.scriptId = "deep";
            String runId = runManager.submit(request).runId;

            RunInfo run = waitForEnd(runManager, runId, 30000L);
            Assert.assertEquals(RunStatus.FAILED, run.status);
            Assert.assertEquals("Runtime Error at line 2:12: " + LIMIT, run.errorMessage);
        } finally {
            runManager.shutdown();
        }
    }

    private static RunThreadInfo thread(RunManager runManager, String runId, String key) {
        for (RunThreadInfo thread : runManager.listThreads(runId)) {
            if (key.equals(thread.resultKeyName)) {
                return thread;
            }
        }
        Assert.fail("no thread for key " + key + " in run " + runId);
        return null;
    }

    private static RunInfo waitForEnd(RunManager runManager, String runId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RunInfo run = runManager.getRun(runId);
        while (System.currentTimeMillis() < deadline) {
            run = runManager.getRun(runId);
            if (run.status == RunStatus.COMPLETED || run.status == RunStatus.FAILED || run.status == RunStatus.CANCELLED) {
                return run;
            }
            Thread.sleep(20L);
        }
        Assert.fail("run " + runId + " did not end within " + timeoutMs + "ms; status=" + run.status);
        return null;
    }
}
