package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunRequest;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.TeeBoxClient;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run cancellation (1.16.0): cooperative abort of RUNNING runs (engine `abort()` + background
 * SHELL-task kill), immediate cancel of QUEUED/PENDING runs (with per-script slot release), the
 * CANCELLED terminal status across API/envelope/threads, and the cancel endpoints' status codes.
 * A spinning `loop true infinite do end` is the canonical runaway — it never yields at a
 * statement boundary, so only the engine's per-iteration checkpoint can stop it.
 */
public class RunCancelTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

    private static final String SPIN_MULTI_SCRIPT =
        "function spin() do\n" +
        "    loop true infinite do\n" +
        "    end\n" +
        "    return 1\n" +
        "end\n" +
        "multi r do\n" +
        "    thread a: spin()\n" +
        "end\n";

    private static final String SPIN_SCRIPT = "loop true infinite do\nend\n";

    @Test
    public void cancelStopsARunningSpinRunAndReclaimsThePoolThread() throws Exception {
        TestServer testServer = createServer(1, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("spin_multi", "1", SPIN_MULTI_SCRIPT, "spin", Arrays.asList("test"), true);
            client.registerScript("quick", "1", "return {\"n\": 1}\n", "quick", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("spin_multi", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, runId, "RUNNING", 8000L);
            // Cancel only after the worker thread was announced — otherwise the abort could land
            // during setup and never exercise the worker-teardown path this test pins.
            waitForThreadCount(testServer.baseUrl, runId, 2, 8000L);

            Map<String, Object> accepted = client.cancelRun(runId);
            Assert.assertEquals(Boolean.TRUE, accepted.get("cancelRequested"));

            Map<String, Object> terminal = waitForStatus(client, runId, "CANCELLED", 10000L);
            String message = String.valueOf(terminal.get("errorMessage"));
            Assert.assertTrue("unexpected reason: " + message, message.contains("Cancelled by client"));

            // Envelope: a cancelled run reads as an error Result, never as still-running.
            Map<String, Object> result = client.getRunResult(runId);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result.get("result");
            Assert.assertEquals("error", envelope.get("status"));
            Assert.assertEquals(Boolean.FALSE, envelope.get("ok"));

            // No thread entry may be left "running" — aborted workers still reach a terminal state.
            Map<String, Object> detail = getJsonMap(testServer.baseUrl + "/api/admin/runs/" + runId, 200);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> threads = (List<Map<String, Object>>) detail.get("threads");
            Assert.assertFalse("no threads recorded", threads.isEmpty());
            for (Map<String, Object> thread : threads) {
                Assert.assertNotEquals("thread left running after cancel: " + thread,
                    "RUNNING", thread.get("state"));
            }

            // maxConcurrentRuns=1: a follow-up run can only complete if the cancel actually
            // released the spinning run's pool thread.
            String quickRunId = (String) client.submitRun("quick", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, quickRunId, "COMPLETED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void cancelKillsTheRunsShellTask() throws Exception {
        TestServer testServer = createServer(2, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("shell_wait", "1",
                "result = SHELL(\"" + testServer.script("sleep30") + "\")\n" +
                "PRINT(result.ok)\n",
                "cancel shell", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("shell_wait", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForTaskCount(testServer.baseUrl, runId, 1, 8000L);

            client.cancelRun(runId);
            waitForStatus(client, runId, "CANCELLED", 15000L);

            // The blocking SHELL only returns because the cancel killed its process.
            Map<String, Object> detail = getJsonMap(testServer.baseUrl + "/api/admin/runs/" + runId, 200);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
            Assert.assertEquals(1, tasks.size());
            Assert.assertEquals("killed", tasks.get(0).get("status"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void cancelQueuedRunIsImmediateAndUnknownOrTerminalRunsAreRejected() throws Exception {
        TestServer testServer = createServer(1, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("spin", "1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);
            client.registerScript("quick", "1", "return {\"n\": 1}\n", "quick", Arrays.asList("test"), true);

            // Fill the single-slot pool, then queue a second run behind it.
            String spinning = (String) client.submitRun("spin", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, spinning, "RUNNING", 8000L);
            String queued = (String) client.submitRun("quick", null, new LinkedHashMap<String, Object>()).get("runId");
            Assert.assertEquals("QUEUED", String.valueOf(client.getRunStatus(queued).get("status")));

            client.cancelRun(queued);
            waitForStatus(client, queued, "CANCELLED", 5000L);

            // 404 for an unknown run.
            postExpectingStatus(testServer.baseUrl + "/api/client/runs/run-nope/cancel", 404);

            // 409 once a run is terminal.
            client.cancelRun(spinning);
            waitForStatus(client, spinning, "CANCELLED", 10000L);
            postExpectingStatus(testServer.baseUrl + "/api/client/runs/" + spinning + "/cancel", 409);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void adminApiAndAdminUiCancelWork() throws Exception {
        TestServer testServer = createServer(2, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("spin_admin", "1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);

            String apiRun = (String) client.submitRun("spin_admin", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, apiRun, "RUNNING", 8000L);
            Map<String, Object> accepted = client.cancelAdminRun(apiRun);
            Assert.assertEquals(Boolean.TRUE, accepted.get("cancelRequested"));
            Map<String, Object> terminal = waitForStatus(client, apiRun, "CANCELLED", 10000L);
            Assert.assertTrue(String.valueOf(terminal.get("errorMessage")).contains("Cancelled by admin"));

            // Admin UI: redirects immediately with the cancel-requested notice flag.
            String uiRun = (String) client.submitRun("spin_admin", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, uiRun, "RUNNING", 8000L);
            String location = postExpectingRedirect(testServer.baseUrl + "/admin/runs/" + uiRun + "/cancel");
            Assert.assertTrue("missing cancelRequested flag: " + location, location.endsWith("?cancelRequested=1"));
            String page = getHtml(testServer.baseUrl + location, 200);
            Assert.assertTrue(page.contains("Cancel requested"));
            waitForStatus(client, uiRun, "CANCELLED", 10000L);
        } finally {
            testServer.close();
        }
    }

    /** PENDING cancel + per-script slot release, at the RunManager level (per-script limits are
     *  a script setting, simplest to drive directly). A leaked slot would deadlock the script. */
    @Test
    public void pendingCancelReleasesThePerScriptSlot() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel").toFile();
        RunManager runManager = new RunManager(dataDir, 4);
        try {
            runManager.registerScriptVersion("limited", "1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);
            runManager.updateScriptSettings("limited", 1, false);

            RunInfo first = runManager.submit(request("limited"));
            waitForManagerStatus(runManager, first.runId, RunStatus.RUNNING, 8000L);
            RunInfo second = runManager.submit(request("limited"));
            Assert.assertEquals(RunStatus.PENDING, runManager.getRun(second.runId).status);

            Assert.assertTrue(runManager.cancelRun(second.runId, "Cancelled by test"));
            Assert.assertEquals(RunStatus.CANCELLED, runManager.getRun(second.runId).status);

            // Cancel the running run too; a fresh run on the same script must then get the slot —
            // this fails (stays PENDING forever) if either cancel path leaked the per-script slot.
            Assert.assertTrue(runManager.cancelRun(first.runId, "Cancelled by test"));
            waitForManagerStatus(runManager, first.runId, RunStatus.CANCELLED, 10000L);

            runManager.registerScriptVersion("limited", "2", "return 1\n", "quick", Arrays.asList("test"), true);
            RunInfo third = runManager.submit(request("limited"));
            waitForManagerStatus(runManager, third.runId, RunStatus.COMPLETED, 10000L);

            // The cancel latch must not leak into later runs (a leaked reason would mark them CANCELLED).
            Assert.assertFalse(runManager.cancelRun(third.runId, "too late"));
        } finally {
            runManager.shutdown();
        }
    }

    /**
     * One cancel ends a run inside {@code multi ... limit K} (engine 0.46.1). A worker waiting for
     * its turn used to be admitted by the cancelled one and run its first statement in full — here
     * a 4-second SLEEP each — so the run stayed RUNNING until a second cancel.
     */
    @Test
    public void oneCancelEndsARunInsideALimitedMulti() throws Exception {
        TestServer testServer = createServer(1, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("queue_of_three", "1",
                "function first() do\n" +
                "    PRINT(\"started\")\n" +
                "    SLEEP(4000)\n" +
                "    return 1\n" +
                "end\n" +
                "function later(n) do\n" +
                "    SLEEP(4000)\n" +
                "    PRINT(\"ran \" + n)\n" +
                "    return n\n" +
                "end\n" +
                "multi r limit 1 do\n" +
                "    thread a: first()\n" +
                "    thread b: later(2)\n" +
                "    thread c: later(3)\n" +
                "end\n", "limit", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("queue_of_three", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStdoutLine(testServer.baseUrl, runId, "started", 8000L);

            Assert.assertEquals(Boolean.TRUE, client.cancelRun(runId).get("cancelRequested"));
            // Well inside one SLEEP: the run ends without either waiting worker sleeping first.
            waitForStatus(client, runId, "CANCELLED", 2500L);

            Map<String, Object> out = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId + "/stdout", 200);
            Assert.assertEquals(Arrays.asList("started"), out.get("lines"));
        } finally {
            testServer.close();
        }
    }

    /** A queued run goes to the canceller or to the worker that picks it up, never to both. */
    @Test
    public void aQueuedRunGoesToExactlyOneOfCancelAndStart() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel-claim").toFile();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 24L * 3600_000L, 7L * 24 * 3600_000L);

        RunInfo cancelled = queuedRun("r-cancelled");
        registry.register(cancelled);
        Assert.assertTrue(registry.markCancelledIfQueued(cancelled, "Cancelled by test"));
        Assert.assertFalse("a worker must not start a run that was cancelled in the queue",
            registry.markStarted(cancelled));
        RunInfo afterCancel = registry.getRun("r-cancelled");
        Assert.assertEquals(RunStatus.CANCELLED, afterCancel.status);
        Assert.assertNotNull("the refused start must not clear the end time", afterCancel.endedAt);
        Assert.assertNull(afterCancel.startedAt);

        RunInfo started = queuedRun("r-started");
        registry.register(started);
        Assert.assertTrue(registry.markStarted(started));
        Assert.assertFalse("a started run is no longer cancelled as a queued one",
            registry.markCancelledIfQueued(started, "too late"));
        Assert.assertEquals(RunStatus.RUNNING, registry.getRun("r-started").status);
    }

    /**
     * A cancel sent right after the submit can reach the run in the instant a worker picks it up.
     * It used to be answered as a cancelled queued run while the worker went on to run the script,
     * un-abortable: the run read RUNNING again and held its pool thread to the script's end. The
     * delay between submit and cancel sweeps across that instant.
     */
    @Test
    public void cancelRightAfterSubmitNeverLeavesTheRunRunning() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel-race").toFile();
        RunManager runManager = new RunManager(dataDir, 64);
        List<String> runIds = new java.util.ArrayList<String>();
        try {
            runManager.registerScriptVersion("sleeper", "1", "SLEEP(600000)\n", "sleeper", Arrays.asList("test"), true);
            for (int i = 0; i < 600; i++) {
                RunInfo run = runManager.submit(request("sleeper"));
                runIds.add(run.runId);
                long resumeAt = System.nanoTime() + (i % 60) * 25_000L;   // 0 .. 1.5 ms
                while (System.nanoTime() < resumeAt) {
                    Thread.onSpinWait();
                }
                Assert.assertTrue(runManager.cancelRun(run.runId, "Cancelled by test"));
            }
            for (String runId : runIds) {
                waitForManagerStatus(runManager, runId, RunStatus.CANCELLED, 10000L);
            }
            // No worker may still be inside a script whose cancel was accepted.
            long deadline = System.currentTimeMillis() + 10000L;
            while (runManager.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            Assert.assertEquals("workers still running cancelled runs", 0, runManager.getActiveCount());
        } finally {
            for (String runId : runIds) {
                runManager.cancelRun(runId, "cleanup");   // frees the workers if the checks above failed
            }
            runManager.shutdown();
        }
    }

    /**
     * A queued run is cancelled while its file cannot be written. The cancel stands in memory, so
     * the run's place under its script's limit must go to the next run whatever the save did: the
     * failed save used to end the cancel half-way, and with the place never given back every later
     * run of that script waited for good.
     */
    @Test
    public void aQueuedCancelThatCannotBeSavedStillGivesItsPlaceToTheNextRun() throws Exception {
        assumePermissionsApply();
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel-unsaved").toFile();
        File runsDir = new File(dataDir, "runs");
        RunManager runManager = new RunManager(dataDir, 1);
        String blockerId = null;
        try {
            runManager.registerScriptVersion("blocker", "1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);
            runManager.registerScriptVersion("limited", "1", "return 1\n", "quick", Arrays.asList("test"), true);
            runManager.updateScriptSettings("limited", 1, false);

            // The one worker is busy: the first "limited" run waits in the queue holding the
            // script's only place, the second waits for that place.
            blockerId = runManager.submit(request("blocker")).runId;
            waitForManagerStatus(runManager, blockerId, RunStatus.RUNNING, 8000L);
            RunInfo queued = runManager.submit(request("limited"));
            RunInfo pending = runManager.submit(request("limited"));
            Assert.assertEquals(RunStatus.QUEUED, runManager.getRun(queued.runId).status);
            Assert.assertEquals(RunStatus.PENDING, runManager.getRun(pending.runId).status);

            Assert.assertTrue(runsDir.setWritable(false));
            try {
                Assert.assertTrue(runManager.cancelRun(queued.runId, "Cancelled by test"));
            } finally {
                runsDir.setWritable(true);
            }
            Assert.assertEquals(RunStatus.CANCELLED, runManager.getRun(queued.runId).status);

            Assert.assertTrue(runManager.cancelRun(blockerId, "Cancelled by test"));
            waitForManagerStatus(runManager, pending.runId, RunStatus.COMPLETED, 10000L);
            // The cancel reaches the file once the disk takes it.
            waitForFileText(new File(runsDir, queued.runId + ".json"), "\"status\": \"CANCELLED\"", 8000L);
        } finally {
            runsDir.setWritable(true);
            if (blockerId != null) {
                runManager.cancelRun(blockerId, "cleanup");
            }
            runManager.shutdown();
        }
    }

    /**
     * A run ends while run files cannot be written, and the next run of its script takes its
     * place. That hand-over used to stop at the save of the new status: the next run was out of
     * the waiting line and never given to a worker, and the place stayed taken for good.
     */
    @Test
    public void aHandOverThatCannotBeSavedStillStartsTheNextRun() throws Exception {
        assumePermissionsApply();
        File dataDir = Files.createTempDirectory("propertee-teebox-handover-unsaved").toFile();
        File runsDir = new File(dataDir, "runs");
        RunManager runManager = new RunManager(dataDir, 4);
        try {
            runManager.registerScriptVersion("limited", "1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);
            runManager.updateScriptSettings("limited", 1, false);
            RunInfo first = runManager.submit(request("limited"));
            waitForManagerStatus(runManager, first.runId, RunStatus.RUNNING, 8000L);
            RunInfo second = runManager.submit(request("limited"));
            Assert.assertEquals(RunStatus.PENDING, runManager.getRun(second.runId).status);

            Assert.assertTrue(runsDir.setWritable(false));
            try {
                Assert.assertTrue(runManager.cancelRun(first.runId, "Cancelled by test"));
                waitForManagerStatus(runManager, first.runId, RunStatus.CANCELLED, 10000L);
                // The second run reaches a worker. It cannot be recorded as started, so it is
                // not started: it fails, and gives the place back.
                waitForManagerStatus(runManager, second.runId, RunStatus.FAILED, 10000L);
            } finally {
                runsDir.setWritable(true);
            }
            String reason = runManager.getRun(second.runId).errorMessage;
            Assert.assertTrue(reason, reason.contains("Failed to save run"));

            runManager.registerScriptVersion("limited", "2", "return 1\n", "quick", Arrays.asList("test"), true);
            RunInfo third = runManager.submit(request("limited"));
            waitForManagerStatus(runManager, third.runId, RunStatus.COMPLETED, 10000L);
            // What could not be written then is written once the disk takes it.
            waitForFileText(new File(runsDir, first.runId + ".json"), "\"status\": \"CANCELLED\"", 8000L);
            waitForFileText(new File(runsDir, second.runId + ".json"), "\"status\": \"FAILED\"", 8000L);
        } finally {
            runsDir.setWritable(true);
            runManager.shutdown();
        }
    }

    @Test
    public void outputCapIsConfigurableAndTruncationIsSignalled() throws Exception {
        TeeBoxConfig overrides = new TeeBoxConfig();
        overrides.runOutputMaxLines = 100;
        TestServer testServer = createServer(2, overrides);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("chatty", "1",
                "i = 0\n" +
                "loop i < 500 do\n" +
                "    PRINT(\"line\", i)\n" +
                "    i = i + 1\n" +
                "end\n",
                "chatty", Arrays.asList("test"), true);
            client.registerScript("quiet", "1", "PRINT(\"one\")\nPRINT(\"two\")\n",
                "quiet", Arrays.asList("test"), true);

            String chattyRun = (String) client.submitRun("chatty", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, chattyRun, "COMPLETED", 15000L);
            Map<String, Object> out = getJsonMap(testServer.baseUrl + "/api/client/runs/" + chattyRun + "/stdout", 200);
            Assert.assertEquals(100.0, ((Number) out.get("lineCount")).doubleValue(), 0.0);
            Assert.assertEquals(500.0, ((Number) out.get("totalLineCount")).doubleValue(), 0.0);
            Assert.assertEquals(Boolean.TRUE, out.get("truncated"));
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) out.get("lines");
            Assert.assertEquals("line 499", lines.get(lines.size() - 1));   // the ring keeps the LAST N

            String quietRun = (String) client.submitRun("quiet", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, quietRun, "COMPLETED", 10000L);
            Map<String, Object> quietOut = getJsonMap(testServer.baseUrl + "/api/client/runs/" + quietRun + "/stdout", 200);
            Assert.assertEquals(2.0, ((Number) quietOut.get("totalLineCount")).doubleValue(), 0.0);
            Assert.assertEquals(Boolean.FALSE, quietOut.get("truncated"));
        } finally {
            testServer.close();
        }
    }

    // ===================== on error (spec v0.37.0) =====================

    /** A failed run keeps what its `on error` handler returned; the error was logged before the handler's output. */
    @Test
    public void aFailedRunKeepsItsHandlersValue() throws Exception {
        TestServer testServer = createServer(2, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("handled", "1",
                "on error do\n" +
                "    PRINT(\"handler:\", _ERROR.message)\n" +
                "    return {\"failed\": true, \"line\": _ERROR.line}\n" +
                "end\n" +
                "FAIL(\"boom\")\n",
                "handled", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("handled", null, new LinkedHashMap<String, Object>()).get("runId");
            Map<String, Object> terminal = waitForStatus(client, runId, "FAILED", 10000L);
            Assert.assertEquals("Runtime Error at line 5:1: boom", terminal.get("errorMessage"));

            Map<String, Object> result = client.getRunResult(runId);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("resultData");
            Assert.assertEquals(Boolean.TRUE, data.get("failed"));
            Assert.assertEquals(5.0, ((Number) data.get("line")).doubleValue(), 0.0);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result.get("result");
            Assert.assertEquals("error", envelope.get("status"));      // the run failed all the same

            Map<String, Object> out = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId + "/stdout", 200);
            Assert.assertEquals(Arrays.asList("handler: boom"), out.get("lines"));
            Map<String, Object> err = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId + "/stderr", 200);
            Assert.assertEquals(Arrays.asList("Runtime error: Runtime Error at line 5:1: boom"), err.get("lines"));
        } finally {
            testServer.close();
        }
    }

    /** A host builtin's error — STREAM_FILE's here — is a runtime error at its call, so the handler runs. */
    @Test
    public void aHostBuiltinsErrorRunsTheHandler() throws Exception {
        TestServer testServer = createServer(2, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("stream_bad", "1",
                "on error do\n" +
                "    return {\"message\": _ERROR.message}\n" +
                "end\n" +
                "return STREAM_FILE(123)\n",
                "stream bad", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("stream_bad", null, new LinkedHashMap<String, Object>()).get("runId");
            Map<String, Object> terminal = waitForStatus(client, runId, "FAILED", 10000L);
            Assert.assertEquals("Runtime Error at line 4:8: STREAM_FILE() requires (path, [contentType])",
                terminal.get("errorMessage"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) client.getRunResult(runId).get("resultData");
            Assert.assertEquals("STREAM_FILE() requires (path, [contentType])", data.get("message"));
        } finally {
            testServer.close();
        }
    }

    /** A stop asked for while the handler runs ends it; the run stays FAILED with the original error. */
    @Test
    public void aStopDuringTheHandlerLeavesTheRunFailed() throws Exception {
        TestServer testServer = createServer(2, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("slow_handler", "1",
                "on error do\n" +
                "    PRINT(\"handler\")\n" +
                "    SLEEP(60000)\n" +
                "    PRINT(\"never\")\n" +
                "end\n" +
                "FAIL(\"boom\")\n",
                "slow handler", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("slow_handler", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStdoutLine(testServer.baseUrl, runId, "handler", 8000L);
            client.cancelRun(runId);

            Map<String, Object> terminal = waitForStatus(client, runId, "FAILED", 10000L);
            Assert.assertEquals("Runtime Error at line 6:1: boom", terminal.get("errorMessage"));
            Map<String, Object> out = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId + "/stdout", 200);
            Assert.assertEquals(Arrays.asList("handler"), out.get("lines"));
        } finally {
            testServer.close();
        }
    }

    /**
     * A stop asked for before the error makes the run CANCELLED and skips the handler — here the
     * stop's own task kill makes SHELL fail, and UNWRAP turns that into the error.
     */
    @Test
    public void aStopBeforeTheErrorIsACancelWithoutTheHandler() throws Exception {
        TestServer testServer = createServer(2, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("shell_unwrap", "1",
                "on error do\n" +
                "    PRINT(\"the handler does not run\")\n" +
                "end\n" +
                "out = UNWRAP(SHELL(\"" + testServer.script("sleep30") + "\"))\n",
                "cancel before error", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("shell_unwrap", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForTaskCount(testServer.baseUrl, runId, 1, 8000L);
            client.cancelRun(runId);

            waitForStatus(client, runId, "CANCELLED", 15000L);
            Map<String, Object> out = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId + "/stdout", 200);
            Assert.assertEquals(Arrays.asList(), out.get("lines"));
        } finally {
            testServer.close();
        }
    }

    private void waitForStdoutLine(String baseUrl, String runId, String line, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Object seen = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> out = getJsonMap(baseUrl + "/api/client/runs/" + runId + "/stdout", 200);
            seen = out.get("lines");
            if (seen instanceof List && ((List<?>) seen).contains(line)) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " did not print " + line + " within " + timeoutMs + "ms; saw " + seen);
    }

    // ===================== helpers =====================

    /** Taking a permission away is how these tests make the disk refuse a write. */
    private static void assumePermissionsApply() {
        org.junit.Assume.assumeFalse("needs POSIX permission semantics",
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        org.junit.Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    private static void waitForFileText(File file, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = null;
        while (System.currentTimeMillis() < deadline) {
            text = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            if (text.contains(expected)) {
                return;
            }
            Thread.sleep(100L);
        }
        Assert.fail(file.getName() + " did not come to hold " + expected + " within " + timeoutMs + "ms:\n" + text);
    }

    private static RunRequest request(String scriptId) {
        RunRequest request = new RunRequest();
        request.scriptId = scriptId;
        return request;
    }

    private static RunInfo queuedRun(String runId) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.QUEUED;
        run.createdAt = 1;
        return run;
    }

    private Map<String, Object> waitForStatus(TeeBoxClient client, String runId, String expected, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = client.getRunStatus(runId);
            if (expected.equals(String.valueOf(last.get("status")))) {
                return last;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " did not reach " + expected + " within " + timeoutMs + "ms; last=" + last);
        return null;
    }

    private void waitForManagerStatus(RunManager runManager, String runId, RunStatus expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RunStatus last = null;
        while (System.currentTimeMillis() < deadline) {
            RunInfo run = runManager.getRun(runId);
            last = run != null ? run.status : null;
            if (expected == last) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " did not reach " + expected + " within " + timeoutMs + "ms; last=" + last);
    }

    private void waitForThreadCount(String baseUrl, String runId, int count, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int seen = -1;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/runs/" + runId, 200);
            List<?> threads = (List<?>) detail.get("threads");
            seen = threads != null ? threads.size() : 0;
            if (seen >= count) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " did not reach " + count + " thread(s) within " + timeoutMs + "ms; saw " + seen);
    }

    private void waitForTaskCount(String baseUrl, String runId, int count, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int seen = -1;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/runs/" + runId, 200);
            List<?> tasks = (List<?>) detail.get("tasks");
            seen = tasks != null ? tasks.size() : 0;
            if (seen >= count) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " did not reach " + count + " task(s) within " + timeoutMs + "ms; saw " + seen);
    }

    private void postExpectingStatus(String url, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write("{}".getBytes("UTF-8"));
        } finally {
            out.close();
        }
        int status = conn.getResponseCode();
        InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (input != null) {
            try {
                readAll(input);
            } finally {
                input.close();
            }
        }
        conn.disconnect();
        Assert.assertEquals(expectedStatus, status);
    }

    private String postExpectingRedirect(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        Assert.assertEquals(302, code);
        Assert.assertNotNull(location);
        return location;
    }

    private Map<String, Object> getJsonMap(String url, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        InputStream input = conn.getInputStream();
        try {
            return gson.fromJson(readAll(input), mapType);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private String getHtml(String url, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        InputStream input = conn.getInputStream();
        try {
            return readAll(input);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private TestServer createServer(int maxConcurrentRuns, TeeBoxConfig overrides) throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel").toFile();
        File scriptsDir = new File(dataDir, "test-scripts");
        scriptsDir.mkdirs();
        writeScript(scriptsDir, "sleep30", "sleep 30");

        TeeBoxConfig config = overrides != null ? overrides : new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = maxConcurrentRuns;

        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return new TestServer(server, "http://127.0.0.1:" + server.getPort(), scriptsDir);
    }

    private static class TestServer {
        private final TeeBoxServer server;
        private final String baseUrl;
        private final File scriptsDir;

        private TestServer(TeeBoxServer server, String baseUrl, File scriptsDir) {
            this.server = server;
            this.baseUrl = baseUrl;
            this.scriptsDir = scriptsDir;
        }

        private String script(String name) {
            return new File(scriptsDir, name + ".sh").getAbsolutePath();
        }

        private void close() {
            server.stop();
        }
    }

    private static void writeScript(File dir, String name, String body) throws IOException {
        File script = new File(dir, name + ".sh");
        java.io.FileOutputStream out = new java.io.FileOutputStream(script);
        try {
            out.write(("#!/bin/sh\n" + body + "\n").getBytes("UTF-8"));
        } finally {
            out.close();
        }
        script.setExecutable(true);
    }
}
