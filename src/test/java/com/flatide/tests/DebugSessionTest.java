package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.flatide.propertee2.task.TaskInfo;
import com.flatide.teebox.RunInfo;
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
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive debug re-runs (1.25.0): a finished run re-executed under the engine debugger
 * (propertee2 0.28.0 façade hooks and worker frames) on the dedicated debug executor, driven over
 * the admin API — entry pause, live breakpoints, main/worker identity, eval/step/continue/restart,
 * current-attempt error markers, quit and cancel both ending as CANCELLED (never FAILED), the
 * capacity cap, and the idle sweep.
 */
public class DebugSessionTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

    /** Fails on line 2 with a positioned error marker. Line 1 proves the debug re-run replays the
     *  source run's input properties. */
    private static final String FAILING_SCRIPT =
        "msg = \"hello \" + _PROPS.who\n" +
        "FAIL(\"upstream unreachable\")\n" +
        "PRINT(\"after\")\n";

    private static final String QUICK_SCRIPT =
        "a = 1\n" +
        "b = a + 1\n" +
        "PRINT(b)\n";

    @Test
    public void debugStartsAtEntryAndKeepsTheSourceErrorAsASeparateMarker() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_fail");

            // No body: no user breakpoint. The positioned source failure is a red marker only;
            // the private entry stop pauses before line 1 executes and is not exposed in the list.
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            String debugRunId = (String) session.get("runId");
            Assert.assertEquals(java.util.Collections.emptyList(), session.get("breakpoints"));
            Assert.assertEquals(2.0, ((Number) session.get("errorLine")).doubleValue(), 0.0);

            Map<String, Object> paused = waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) paused.get("paused");
            Assert.assertEquals(1.0, ((Number) frame.get("line")).doubleValue(), 0.0);
            Assert.assertEquals("ENTRY", frame.get("reason"));
            Assert.assertTrue(String.valueOf(frame.get("statement")).contains("_PROPS.who"));
            @SuppressWarnings("unchecked")
            Map<String, Object> entryGlobals = (Map<String, Object>) frame.get("globals");
            Assert.assertTrue("host _PROPS is missing from debugger Globals",
                entryGlobals.containsKey("_PROPS"));
            Assert.assertTrue(String.valueOf(entryGlobals.get("_PROPS")).contains("ops"));

            // Step from entry: line 1 executes, then line 2 pauses before FAIL.
            command(testServer, sessionId, "stepOver", null, 200);
            paused = waitForPausedAtLine(testServer, sessionId, 2, 10000L);
            frame = (Map<String, Object>) paused.get("paused");
            Assert.assertEquals("STEP", frame.get("reason"));
            Assert.assertTrue(String.valueOf(frame.get("statement")).contains("FAIL"));
            // The pause snapshot exposes the paused scope in display form — including the
            // property-derived global, proving the re-run replayed the source run's props.
            @SuppressWarnings("unchecked")
            Map<String, Object> globals = (Map<String, Object>) frame.get("globals");
            Assert.assertEquals("hello ops", globals.get("msg"));

            // eval reads the paused scope...
            Map<String, Object> read = command(testServer, sessionId, "eval", "msg", 200);
            Assert.assertEquals("hello ops", read.get("result"));
            // ...and writes it (the whole point of a debug re-run: try the fix in place).
            command(testServer, sessionId, "eval", "msg = \"patched\"", 200);
            Assert.assertEquals("patched", command(testServer, sessionId, "eval", "msg", 200).get("result"));
            // An eval error leaves the session paused and usable.
            Map<String, Object> bad = command(testServer, sessionId, "eval", "nope.field", 200);
            Assert.assertNotNull(bad.get("error"));

            // Continue: the FAIL statement runs, the debug run fails like the original.
            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> ended = waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("FAILED", ended.get("runStatus"));

            RunInfo debugRun = testServer.server.getRunManager().getRun(debugRunId);
            Assert.assertTrue(debugRun.debug);
            Assert.assertEquals(sourceRunId, debugRun.debugOf);
            Assert.assertEquals("debug", debugRun.origin);
            Assert.assertEquals(RunStatus.FAILED, debugRun.status);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void failedDebugAttemptMovesTheRedMarkerToItsActualErrorLine() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_moved_error");
            // The source Run failed on line 2, but the current version now fails on line 3.
            testServer.server.getRunManager().updateScriptVersionContent("dbg_moved_error", "v1",
                "a = 1\n" +
                "b = 2\n" +
                "FAIL(\"moved failure\")\n");
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            Assert.assertEquals("the source failure is the initial marker", 2.0,
                ((Number) session.get("errorLine")).doubleValue(), 0.0);
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> ended =
                waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("FAILED", ended.get("runStatus"));
            Assert.assertEquals("the current attempt's failure must replace the old marker", 3.0,
                ((Number) ended.get("errorLine")).doubleValue(), 0.0);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void quitEndsTheDebugRunAsCancelledNeverFailed() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_quit");
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            String debugRunId = (String) session.get("runId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);

            Assert.assertEquals(Boolean.TRUE,
                command(testServer, sessionId, "quit", null, 200).get("accepted"));
            Map<String, Object> ended = waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("CANCELLED", ended.get("runStatus"));
            Assert.assertTrue(String.valueOf(ended.get("errorMessage")).contains("Ended from the debugger"));
            Assert.assertNull("a stopped attempt must not keep the source error marker",
                ended.get("errorLine"));

            // Commands against an ended session are a state error, not a crash.
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{\"op\":\"continue\"}", 409);

            // A CANCELLED source has no positioned failure: debug still waits at entry, but must
            // not invent a red error marker from the cancellation message.
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_cancelled_source", "v1", "loop true infinite do\nend\n",
                "cancelled source", Arrays.asList("test"), true);
            String cancelledSource = (String) client.submitRun("dbg_cancelled_source", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, cancelledSource, "RUNNING", 10000L);
            client.cancelRun(cancelledSource);
            waitForRunStatus(client, cancelledSource, "CANCELLED", 10000L);
            Map<String, Object> cancelledDebug = postJson(testServer.baseUrl
                + "/api/admin/runs/" + cancelledSource + "/debug", "{}", 201);
            Assert.assertNull(cancelledDebug.get("errorLine"));
            String cancelledSession = (String) cancelledDebug.get("sessionId");
            Map<String, Object> atEntry = waitForSessionState(
                testServer, cancelledSession, "PAUSED", 10000L);
            Assert.assertEquals("ENTRY", pausedReason(atEntry));
            command(testServer, cancelledSession, "quit", null, 200);
            waitForSessionState(testServer, cancelledSession, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void breakpointsAndSteppingWalkACompletedRun() throws Exception {
        TestServer testServer = createServer(null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_step", "v1", QUICK_SCRIPT, "step", Arrays.asList("test"), true);
            String sourceRunId = (String) client.submitRun("dbg_step", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, sourceRunId, "COMPLETED", 10000L);

            // A COMPLETED run is debuggable too (any terminal run); breakpoints from the body.
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{\"breakpoints\":[2]}", 201);
            String sessionId = (String) session.get("sessionId");

            Map<String, Object> paused = waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            Assert.assertEquals(1.0, pausedLine(paused), 0.0);
            Assert.assertEquals("ENTRY", pausedReason(paused));
            Assert.assertNull("a completed source has no error marker", paused.get("errorLine"));
            command(testServer, sessionId, "continue", null, 200);
            paused = waitForPausedAtLine(testServer, sessionId, 2, 10000L);
            Assert.assertEquals(2.0, pausedLine(paused), 0.0);
            Assert.assertEquals("BREAKPOINT", pausedReason(paused));

            command(testServer, sessionId, "stepOver", null, 200);
            paused = waitForPausedAtLine(testServer, sessionId, 3, 10000L);
            Assert.assertEquals("STEP", pausedReason(paused));
            Assert.assertEquals("2", command(testServer, sessionId, "eval", "b", 200).get("result"));

            // Live breakpoint replacement round-trips (sorted, mid-run).
            Map<String, Object> bps = postJson(
                testServer.baseUrl + "/api/admin/debug/" + sessionId + "/breakpoints",
                "{\"lines\":[9,1]}", 200);
            Assert.assertEquals(Arrays.asList(1.0, 9.0), bps.get("breakpoints"));

            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> ended = waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("COMPLETED", ended.get("runStatus"));
            Assert.assertEquals(Arrays.asList("2"), ended.get("stdoutLines"));
            Assert.assertEquals(1.0, ((Number) ended.get("stdoutTotalLines")).doubleValue(), 0.0);
            Assert.assertEquals(java.util.Collections.emptyList(), ended.get("stderrLines"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void workerBreaksExposeTheCurrentFrameAndAllLogicalThreads() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String source =
                "function worker(n) do\n" +
                "    value = n\n" +
                "    value = value + 1\n" +
                "    return value\n" +
                "end\n" +
                "multi results limit 1 do\n" +
                "    thread alpha: worker(7)\n" +
                "    thread beta: worker(10)\n" +
                "monitor 1\n" +
                "    debug\n" +
                "end\n" +
                "PRINT(results.alpha.value, results.beta.value)\n";
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_workers", "v1", source, "workers",
                Arrays.asList("test"), true);
            String sourceRunId = (String) client.submitRun("dbg_workers", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, sourceRunId, "COMPLETED", 10000L);

            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{\"breakpoints\":[3]}", 201);
            String sessionId = (String) session.get("sessionId");

            Map<String, Object> atEntry =
                waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            Map<?, ?> entryFrame = (Map<?, ?>) atEntry.get("paused");
            Assert.assertEquals(0.0, ((Number) entryFrame.get("threadId")).doubleValue(), 0.0);
            Assert.assertEquals("main", entryFrame.get("threadName"));

            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> first = waitForPausedAtLine(testServer, sessionId, 3, 10000L);
            Map<?, ?> firstFrame = (Map<?, ?>) first.get("paused");
            int firstWorkerId = ((Number) firstFrame.get("threadId")).intValue();
            Assert.assertTrue("a worker frame needs a positive logical id", firstWorkerId > 0);
            Assert.assertEquals("worker", firstFrame.get("threadName"));
            Map<?, ?> firstLocals = (Map<?, ?>) firstFrame.get("locals");
            Assert.assertEquals("7", firstLocals.get("n"));
            Assert.assertEquals("7", firstLocals.get("value"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> firstThreads =
                (List<Map<String, Object>>) first.get("threads");
            Assert.assertEquals("main plus both declared workers", 3, firstThreads.size());
            Map<String, Object> highlighted = null;
            int highlightedCount = 0;
            for (Map<String, Object> thread : firstThreads) {
                if (Boolean.TRUE.equals(thread.get("paused"))) {
                    highlighted = thread;
                    highlightedCount++;
                }
            }
            Assert.assertEquals("exactly one frame is inspectable", 1, highlightedCount);
            Assert.assertNotNull(highlighted);
            Assert.assertEquals(firstWorkerId,
                ((Number) highlighted.get("threadId")).intValue());
            Assert.assertEquals("alpha", highlighted.get("resultKeyName"));
            Assert.assertEquals("the lifecycle state is preserved separately from paused",
                "RUNNING", highlighted.get("state"));

            // A step armed in a worker stays on that worker. The limit keeps beta pending until
            // alpha completes, so no sibling breakpoint can legitimately pre-empt this step.
            command(testServer, sessionId, "stepOver", null, 200);
            Map<String, Object> stepped = waitForPausedAtLine(testServer, sessionId, 4, 10000L);
            Map<?, ?> steppedFrame = (Map<?, ?>) stepped.get("paused");
            Assert.assertEquals("STEP", steppedFrame.get("reason"));
            Assert.assertEquals(firstWorkerId,
                ((Number) steppedFrame.get("threadId")).intValue());

            // Continue lets beta reach the same source breakpoint. It must publish a new worker
            // identity even though line/column/reason are identical to alpha's earlier pause.
            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> second = waitForPausedAtLine(testServer, sessionId, 3, 10000L);
            Map<?, ?> secondFrame = (Map<?, ?>) second.get("paused");
            Assert.assertNotEquals(firstWorkerId,
                ((Number) secondFrame.get("threadId")).intValue());
            Assert.assertEquals("worker", secondFrame.get("threadName"));

            // The monitor's explicit debug statement is deliberately ignored by propertee2.
            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> ended =
                waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("COMPLETED", ended.get("runStatus"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> endedThreads =
                (List<Map<String, Object>>) ended.get("threads");
            for (Map<String, Object> thread : endedThreads) {
                Assert.assertEquals(Boolean.FALSE, thread.get("paused"));
            }

            String page = getBody(testServer.baseUrl + "/admin/debug/" + sessionId, 200);
            Assert.assertTrue(page.contains("Paused Thread"));
            Assert.assertTrue(page.contains("Logical Threads"));
            Assert.assertTrue(page.contains("<code>thread</code> workers"));
            Assert.assertFalse(page.contains("Breaks fire on the main thread only"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void restartStopsTheAttemptAndReentersWithTheSameRunAndBreakpoints() throws Exception {
        TestServer testServer = createServer(null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_restart", "v1", QUICK_SCRIPT, "restart",
                Arrays.asList("test"), true);
            String sourceRunId = (String) client.submitRun("dbg_restart", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, sourceRunId, "COMPLETED", 10000L);

            Map<String, Object> first = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{\"breakpoints\":[2]}", 201);
            String firstSessionId = (String) first.get("sessionId");
            String debugRunId = (String) first.get("runId");
            waitForSessionState(testServer, firstSessionId, "PAUSED", 10000L);

            Map<String, Object> restarted = postJson(testServer.baseUrl
                + "/admin/debug/" + firstSessionId + "/restart", "{}", 201);
            String restartedSessionId = (String) restarted.get("sessionId");
            Assert.assertNotEquals(firstSessionId, restartedSessionId);
            Assert.assertEquals("Restart must reset the canonical debug Run", debugRunId,
                restarted.get("runId"));
            Assert.assertEquals(Arrays.asList(2.0), restarted.get("breakpoints"));
            Assert.assertNull("the superseded session still exposes the reset Run",
                testServer.server.getDebugSessionManager().find(firstSessionId));

            Map<String, Object> atEntry =
                waitForSessionState(testServer, restartedSessionId, "PAUSED", 10000L);
            Assert.assertEquals("ENTRY", pausedReason(atEntry));
            Assert.assertEquals(1.0, pausedLine(atEntry), 0.0);
            command(testServer, restartedSessionId, "continue", null, 200);
            waitForPausedAtLine(testServer, restartedSessionId, 2, 10000L);
            command(testServer, restartedSessionId, "quit", null, 200);
            waitForSessionState(testServer, restartedSessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void capacityCapAndSourceStateGuards() throws Exception {
        TeeBoxConfig overrides = new TeeBoxConfig();
        overrides.debugMaxSessions = 1;
        TestServer testServer = createServer(overrides);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            String sourceRunId = runFailingScript(testServer, "dbg_cap");

            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);

            // Re-opening the SAME source is idempotent even at the capacity limit: one session,
            // one debug Run, and any newly requested breakpoints merge into the live set.
            Map<String, Object> reused = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{\"breakpoints\":[3]}", 201);
            Assert.assertEquals(sessionId, reused.get("sessionId"));
            Assert.assertEquals(session.get("runId"), reused.get("runId"));
            Assert.assertEquals(Arrays.asList(3.0), reused.get("breakpoints"));
            Assert.assertEquals(1, testServer.server.getDebugSessionManager().activeCount());

            // A DIFFERENT finished source is still rejected while the one slot is held.
            String otherSourceRunId = runFailingScript(testServer, "dbg_cap_other");
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/" + otherSourceRunId
                + "/debug", "{}", 409);
            // A still-running source is rejected; an unknown one is 404.
            client.registerScript("dbg_spin", "v1", "loop true infinite do\nend\n", "spin",
                Arrays.asList("test"), true);
            String spinning = (String) client.submitRun("dbg_spin", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, spinning, "RUNNING", 10000L);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/" + spinning + "/debug",
                "{}", 409);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/run-nope/debug", "{}", 404);

            // Quit frees the slot; a new console session opens, but the source's canonical debug
            // Run is reset and reused (there is still only one debug Run in the registry).
            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Map<String, Object> second = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            Assert.assertNotEquals("a new execution needs a fresh console session",
                sessionId, second.get("sessionId"));
            Assert.assertEquals("the same source must always reuse its debug Run",
                session.get("runId"), second.get("runId"));
            Assert.assertEquals(1, testServer.server.getRunManager()
                .listRuns(null, null, null, "debug", 0, -1).size());
            Assert.assertNull("the superseded ended console must not expose the reset Run",
                testServer.server.getDebugSessionManager().find(sessionId));
            waitForSessionState(testServer, (String) second.get("sessionId"), "PAUSED", 10000L);
            RunInfo reset = testServer.server.getRunManager().getRun((String) second.get("runId"));
            Assert.assertEquals(RunStatus.RUNNING, reset.status);
            Assert.assertNull("previous terminal timestamp leaked into the next attempt", reset.endedAt);
            Assert.assertNull("previous failure leaked into the next attempt", reset.errorMessage);
            Assert.assertNull("previous result leaked into the next attempt", reset.resultData);
            command(testServer, (String) second.get("sessionId"), "quit", null, 200);
            waitForSessionState(testServer, (String) second.get("sessionId"), "ENDED", 10000L);

            client.cancelRun(spinning);
            waitForRunStatus(client, spinning, "CANCELLED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void endedDebugRerunReusesItsRunAcrossRestart() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-debug-restart").toFile();
        TestServer first = createServer(dataDir, null);
        String sourceRunId;
        String debugRunId;
        try {
            sourceRunId = runFailingScript(first, "dbg_restart_reuse");
            Map<String, Object> opened = postJson(
                first.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            debugRunId = (String) opened.get("runId");
            String sessionId = (String) opened.get("sessionId");
            waitForSessionState(first, sessionId, "PAUSED", 10000L);
            command(first, sessionId, "quit", null, 200);
            waitForSessionState(first, sessionId, "ENDED", 10000L);
        } finally {
            first.close();
        }

        TestServer restarted = createServer(dataDir, null);
        try {
            Map<String, Object> reopened = postJson(
                restarted.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            Assert.assertEquals("persisted debug Run was not reused after restart",
                debugRunId, reopened.get("runId"));
            Assert.assertEquals(1, restarted.server.getRunManager()
                .listRuns(null, null, null, "debug", 0, -1).size());
            String sessionId = (String) reopened.get("sessionId");
            waitForSessionState(restarted, sessionId, "PAUSED", 10000L);
            command(restarted, sessionId, "quit", null, 200);
            waitForSessionState(restarted, sessionId, "ENDED", 10000L);
        } finally {
            restarted.close();
        }
    }

    @Test
    public void reusedDebugRunDoesNotAccumulateTasksFromEarlierAttempts() throws Exception {
        TestServer testServer = createServer(null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_task_reset", "v1",
                "result = SHELL(\"echo debug-attempt\")\n" +
                "FAIL(\"stop after task\")\n",
                "task reset", Arrays.asList("test"), true);
            String sourceRunId = (String) client.submitRun("dbg_task_reset", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, sourceRunId, "FAILED", 10000L);

            Map<String, Object> first = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String firstSessionId = (String) first.get("sessionId");
            String debugRunId = (String) first.get("runId");
            waitForSessionState(testServer, firstSessionId, "PAUSED", 10000L);
            command(testServer, firstSessionId, "stepOver", null, 200);
            waitForPausedAtLine(testServer, firstSessionId, 2, 10000L);
            List<TaskInfo> firstTasks = testServer.server.getRunManager().listTasksForRun(debugRunId);
            Assert.assertEquals(1, firstTasks.size());
            String firstTaskId = firstTasks.get(0).taskId;
            command(testServer, firstSessionId, "quit", null, 200);
            waitForSessionState(testServer, firstSessionId, "ENDED", 10000L);

            Map<String, Object> second = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            Assert.assertEquals(debugRunId, second.get("runId"));
            String secondSessionId = (String) second.get("sessionId");
            waitForSessionState(testServer, secondSessionId, "PAUSED", 10000L);
            command(testServer, secondSessionId, "stepOver", null, 200);
            waitForPausedAtLine(testServer, secondSessionId, 2, 10000L);
            List<TaskInfo> secondTasks = testServer.server.getRunManager().listTasksForRun(debugRunId);
            Assert.assertEquals("old and new task rows were mixed", 1, secondTasks.size());
            Assert.assertNotEquals("the old task row survived the reset",
                firstTaskId, secondTasks.get(0).taskId);
            command(testServer, secondSessionId, "quit", null, 200);
            waitForSessionState(testServer, secondSessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    /** The subtlest mechanism: a PAUSED run sits inside the debug handler, so a plain engine abort
     *  cannot end it — the run-cancel path must also wake the session (composite handle). */
    @Test
    public void adminRunCancelEndsAPausedSessionAndTheIdleSweepKillsAbandonedOnes() throws Exception {
        TeeBoxConfig overrides = new TeeBoxConfig();
        overrides.debugIdleTimeoutMs = 300L;
        TestServer testServer = createServer(overrides);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_kill");

            // Part A: cancel of the DEBUG RUN while paused ends the session as CANCELLED.
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            String debugRunId = (String) session.get("runId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/" + debugRunId + "/cancel",
                "{}", 202);
            Map<String, Object> ended = waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("CANCELLED", ended.get("runStatus"));

            // Part B: an abandoned paused session (no polling) dies at the idle sweep.
            Map<String, Object> second = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String secondId = (String) second.get("sessionId");
            waitForSessionState(testServer, secondId, "PAUSED", 10000L);
            Thread.sleep(400L);   // exceed the 300 ms idle timeout WITHOUT touching the session
            testServer.server.getDebugSessionManager().sweep();
            Map<String, Object> sweptEnd = waitForSessionState(testServer, secondId, "ENDED", 10000L);
            Assert.assertEquals("CANCELLED", sweptEnd.get("runStatus"));
            Assert.assertTrue(String.valueOf(sweptEnd.get("errorMessage")).contains("idle timeout"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void adminUiExposesTheDebugButtonAndConsolePage() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_ui");
            String runPage = getBody(testServer.baseUrl + "/admin/runs/" + sourceRunId, 200);
            Assert.assertTrue("missing Debug Re-run button", runPage.contains("Debug Re-run"));

            String location = postExpectingRedirect(testServer.baseUrl + "/admin/runs/" + sourceRunId + "/debug");
            Assert.assertTrue("unexpected redirect: " + location, location.startsWith("/admin/debug/"));
            String sessionId = location.substring("/admin/debug/".length());

            String consolePage = getBody(testServer.baseUrl + location, 200);
            Assert.assertTrue(consolePage.contains("Debug session"));
            Assert.assertTrue("missing side-effect warning", consolePage.contains("side effects happen again"));
            Assert.assertTrue("missing shared source editor", consolePage.contains("id='dbg-source'"));
            Assert.assertTrue("source editor is not upgraded", consolePage.contains("data-pt-editor"));
            Assert.assertTrue("source editor has no breakpoint gutter",
                consolePage.contains("data-pt-breakpoints"));
            Assert.assertTrue("debug source must be read-only",
                consolePage.contains("data-pt-breakpoints readonly"));
            Assert.assertTrue("debug source content is missing", consolePage.contains("_PROPS.who"));
            Assert.assertTrue("shared editor breakpoint wiring is missing",
                consolePage.contains("pt-breakpoints-change"));
            Assert.assertTrue("current-line highlighting is missing",
                consolePage.contains("setDebugLine(latestDebugLine,reveal)"));
            Assert.assertTrue("source-error highlighting is missing",
                consolePage.contains("setErrorLine(latestErrorLine,false)"));
            Assert.assertTrue("playground-style red error marker CSS is missing",
                consolePage.contains(".pt-editor-error-line"));
            Assert.assertTrue("line markers must render above syntax across the full code row",
                consolePage.contains("position:absolute; z-index:2; top:0; left:0; right:0; bottom:0"));
            Assert.assertTrue("the editor input stacking contract is missing",
                consolePage.contains("position:relative; z-index:3; display:block"));
            Assert.assertTrue("active code-row markers must override their display:none default",
                consolePage.contains("marker.style.display = 'block'"));
            int editorAt = consolePage.indexOf("id='dbg-source'");
            int workbenchAt = consolePage.indexOf("class='dbg-workbench'");
            int outputAt = consolePage.indexOf("class='dbg-pane dbg-output-pane'");
            int variablesAt = consolePage.indexOf("class='dbg-pane dbg-vars-pane'");
            Assert.assertTrue("Output workbench is not attached below the editor",
                editorAt >= 0 && editorAt < workbenchAt);
            Assert.assertTrue("Output and Variables are not vertically split",
                workbenchAt < outputAt && outputAt < variablesAt);
            Assert.assertTrue("debug toolbar is not above Output",
                outputAt < consolePage.indexOf("id='dbg-btn-continue'")
                    && consolePage.indexOf("id='dbg-btn-stepOut'")
                        < consolePage.indexOf("id='dbg-console'"));
            Assert.assertTrue("Stop control is missing", consolePage.contains(">Stop</button>"));
            Assert.assertTrue("Restart control is missing", consolePage.contains(">Restart</button>"));
            Assert.assertTrue("Restart endpoint is not wired",
                consolePage.contains("post(base+'/restart'"));
            Assert.assertFalse("old Quit Run label remains", consolePage.contains(">Quit Run</button>"));
            Assert.assertTrue("Run output is not wired into the console",
                consolePage.contains("syncRunOutput(s)"));
            Assert.assertFalse("pause diagnostics must not pollute script Output",
                consolePage.contains("-- paused at line"));
            Assert.assertFalse("generic session-end diagnostics must not pollute script Output",
                consolePage.contains("-- session ended"));
            Assert.assertFalse("Restart progress must stay in the toolbar, not Output",
                consolePage.contains("-- restarting from entry"));
            Assert.assertTrue("runtime failures must remain visible in Output",
                consolePage.contains("Runtime Error:"));
            Assert.assertTrue("script stderr must use the playground-style error prefix",
                consolePage.contains("'[ERROR] '"));
            Assert.assertFalse("old comma-separated breakpoint input remains",
                consolePage.contains("dbg-bp-input"));

            // The session-authed state endpoint the console polls.
            Map<String, Object> state = getJsonMap(
                testServer.baseUrl + "/admin/debug/" + sessionId + "/state", 200);
            Assert.assertEquals(sessionId, state.get("sessionId"));
            Assert.assertEquals(2.0, ((Number) state.get("errorLine")).doubleValue(), 0.0);
            Assert.assertEquals("ENTRY", ((Map<?, ?>) state.get("paused")).get("reason"));
            Assert.assertEquals(java.util.Collections.emptyList(), state.get("stdoutLines"));
            Assert.assertEquals(java.util.Collections.emptyList(), state.get("stderrLines"));
            String debugRunId = String.valueOf(state.get("runId"));

            // Runs defaults to API runs: the source is visible and its debug re-run is not.
            String defaultRunsPage = getBody(testServer.baseUrl + "/admin/runs", 200);
            Assert.assertTrue(defaultRunsPage.contains(sourceRunId));
            Assert.assertFalse(defaultRunsPage.contains(debugRunId));
            String debugRuns = getBody(
                testServer.baseUrl + "/admin/fragments/all-runs?origin=debug", 200);
            Assert.assertTrue(debugRuns.contains(debugRunId));
            Assert.assertFalse(debugRuns.contains(sourceRunId));

            // Leaving the console must not strand the session: the admin nav, dedicated session
            // list, source Run and debug Run all provide a way back to the same console.
            getBody(testServer.baseUrl + "/admin/scripts", 200);
            String dashboard = getBody(testServer.baseUrl + "/admin", 200);
            Assert.assertTrue("dashboard nav has no debugger entry",
                dashboard.contains("href='/admin/debug'"));
            String sessionsPage = getBody(testServer.baseUrl + "/admin/debug", 200);
            Assert.assertTrue("debug session missing from session list", sessionsPage.contains(sessionId));
            Assert.assertTrue("session list has no resume link",
                sessionsPage.contains("href='/admin/debug/" + sessionId + "'>Resume</a>"));
            String sourcePageAfterOpen = getBody(
                testServer.baseUrl + "/admin/runs/" + sourceRunId, 200);
            Assert.assertTrue("source Run has no resume-debug link",
                sourcePageAfterOpen.contains("href='/admin/debug/" + sessionId + "'"));
            Assert.assertFalse("source Run still offers a duplicate debug re-run",
                sourcePageAfterOpen.contains("Debug Re-run</button>"));
            Assert.assertEquals("UI reopen must redirect to the existing session", location,
                postExpectingRedirect(testServer.baseUrl + "/admin/runs/" + sourceRunId + "/debug"));
            Assert.assertEquals("UI reopen created another debug Run", 1,
                testServer.server.getRunManager()
                    .listRuns(null, null, null, "debug", 0, -1).size());
            String debugRunPage = getBody(
                testServer.baseUrl + "/admin/runs/" + debugRunId, 200);
            Assert.assertTrue("debug Run has no resume-debug link",
                debugRunPage.contains("href='/admin/debug/" + sessionId + "'"));
            String reopenedConsole = getBody(
                testServer.baseUrl + "/admin/debug/" + sessionId, 200);
            Assert.assertTrue("debug console could not be re-entered",
                reopenedConsole.contains("Debug session"));

            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void scriptEditorDebugUsesDedicatedPropsWithoutParentOrRunHistory() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-script-debug").toFile();
        TestServer testServer = createServer(dataDir, null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("editor_dbg", "v1", "PRINT(\"prop-\" + _PROPS.who)\n",
                "props source", Arrays.asList("test"), true);

            String scriptPage = getBody(
                testServer.baseUrl + "/admin/scripts/editor_dbg?version=v1", 200);
            int sourceCard = scriptPage.indexOf("id='version-source'");
            int runCard = scriptPage.indexOf("<h2>Run Script</h2>");
            int propsInput = scriptPage.indexOf("Debug Props (JSON)");
            int debugAction = scriptPage.indexOf("formaction='/admin/scripts/editor_dbg/debug'");
            Assert.assertTrue("Debug action and its Props are not in Version Source",
                sourceCard >= 0 && sourceCard < propsInput && propsInput < debugAction
                    && debugAction < runCard);
            Assert.assertEquals("Debug action still appears in Run Script", -1,
                scriptPage.substring(runCard).indexOf("/admin/scripts/editor_dbg/debug"));
            Assert.assertTrue("Debug does not identify the editor version",
                scriptPage.contains("name='debugVersion' value='v1'"));
            Assert.assertTrue("dedicated Debug Props input is missing",
                scriptPage.substring(sourceCard, runCard).contains("name='propsJson' value='{}'"));

            String draft = "PRINT(\"draft-\" + _PROPS.who)\n";
            String location = postFormExpectingRedirect(
                testServer.baseUrl + "/admin/scripts/editor_dbg/debug",
                "debugVersion=v1&content=" + URLEncoder.encode(draft, "UTF-8")
                    + "&propsJson="
                    + URLEncoder.encode("{\"who\":\"ops\"}", "UTF-8"));
            Assert.assertTrue("unexpected redirect: " + location,
                location.startsWith("/admin/debug/"));
            String sessionId = location.substring("/admin/debug/".length());

            Map<String, Object> paused =
                waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            Assert.assertEquals(Boolean.TRUE, paused.get("transientDebug"));
            Assert.assertNull("script-page debug must not have a parent Run", paused.get("sourceRunId"));
            Assert.assertEquals("editor_dbg", paused.get("scriptId"));
            Assert.assertEquals("v1", paused.get("version"));
            String transientRunId = String.valueOf(paused.get("runId"));
            Assert.assertNotNull("running transient attempt must be internally addressable",
                testServer.server.getRunManager().getRun(transientRunId));
            RunInfo liveRun = testServer.server.getRunManager().getRun(transientRunId);
            Assert.assertEquals("ops", liveRun.properties.get("who"));
            Assert.assertEquals(1000, liveRun.maxIterations);
            Assert.assertEquals("error", liveRun.iterationLimitBehavior);
            Assert.assertEquals("debug run timeout must remain disabled", 0L, liveRun.timeoutMs);
            Assert.assertEquals("transient debug leaked into Runs while active", 0,
                testServer.server.getRunManager()
                    .listRuns(null, null, null, "debug", 0, -1).size());
            Assert.assertFalse("transient debug was persisted",
                new File(new File(dataDir, "runs"), transientRunId + ".json").exists());

            String consolePage = getBody(testServer.baseUrl + location, 200);
            Assert.assertTrue(consolePage.contains("unsaved Version Source and Debug Props captured"));
            Assert.assertTrue(consolePage.contains("temporary; not retained in Runs"));
            Assert.assertFalse("no-parent debugger rendered a null Run link",
                consolePage.contains("/admin/runs/null"));

            @SuppressWarnings("unchecked")
            Map<String, Object> pausedFrame = (Map<String, Object>) paused.get("paused");
            @SuppressWarnings("unchecked")
            Map<String, Object> globals = (Map<String, Object>) pausedFrame.get("globals");
            Assert.assertTrue("Debug Props are missing from debugger Globals",
                globals.containsKey("_PROPS"));
            Assert.assertTrue(String.valueOf(globals.get("_PROPS")).contains("ops"));

            // Once eval shadows the builtin _PROPS into a real global, the live frame value must
            // win over the immutable session-input fallback used for the initial pause.
            command(testServer, sessionId, "eval", "_PROPS.who = \"debug\"", 200);
            Map<String, Object> afterEval = getJsonMap(
                testServer.baseUrl + "/api/admin/debug/" + sessionId, 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> evalFrame = (Map<String, Object>) afterEval.get("paused");
            @SuppressWarnings("unchecked")
            Map<String, Object> evalGlobals = (Map<String, Object>) evalFrame.get("globals");
            Assert.assertTrue("eval-updated _PROPS was replaced by the launch fallback",
                String.valueOf(evalGlobals.get("_PROPS")).contains("debug"));

            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> ended =
                waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("COMPLETED", ended.get("runStatus"));
            Assert.assertTrue("eval-updated _PROPS was not used by the script",
                ((List<?>) ended.get("stdoutLines")).contains("draft-debug"));
            Assert.assertNull("terminal transient Run was not removed",
                testServer.server.getRunManager().getRun(transientRunId));
            Assert.assertFalse("terminal transient Run was persisted",
                new File(new File(dataDir, "runs"), transientRunId + ".json").exists());

            String sessionsPage = getBody(testServer.baseUrl + "/admin/debug", 200);
            Assert.assertTrue("editor session missing from debugger list",
                sessionsPage.contains("editor_dbg@v1"));
            Assert.assertTrue("debugger list does not label temporary execution",
                sessionsPage.contains("temporary (not retained)"));
            Assert.assertFalse("debugger list rendered a null Run link",
                sessionsPage.contains("/admin/runs/null"));

            Map<String, Object> restarted = postJson(testServer.baseUrl + "/admin/debug/"
                + sessionId + "/restart", "{}", 201);
            String restartedId = String.valueOf(restarted.get("sessionId"));
            Assert.assertNotEquals(sessionId, restartedId);
            Assert.assertNotEquals("restart must get a fresh temporary Run identity",
                transientRunId, String.valueOf(restarted.get("runId")));
            Assert.assertNull("superseded transient session should not be retained",
                testServer.server.getDebugSessionManager().find(sessionId));
            waitForSessionState(testServer, restartedId, "PAUSED", 10000L);
            command(testServer, restartedId, "continue", null, 200);
            Map<String, Object> restartedEnd =
                waitForSessionState(testServer, restartedId, "ENDED", 10000L);
            Assert.assertTrue("Restart did not replay the captured source and Props",
                ((List<?>) restartedEnd.get("stdoutLines")).contains("draft-ops"));

            // A new script shell has no saved version yet. Version Source must still offer Debug
            // and execute the posted draft without creating a placeholder version file.
            testServer.server.getRunManager().createScript("shell_editor_dbg", null);
            String shellPage = getBody(
                testServer.baseUrl + "/admin/scripts/shell_editor_dbg", 200);
            Assert.assertTrue("versionless Version Source has no Debug action",
                shellPage.contains("formaction='/admin/scripts/shell_editor_dbg/debug'"));
            String shellLocation = postFormExpectingRedirect(
                testServer.baseUrl + "/admin/scripts/shell_editor_dbg/debug",
                "content=" + URLEncoder.encode("PRINT(_PROPS.message)\n", "UTF-8")
                    + "&propsJson=" + URLEncoder.encode("{\"message\":\"shell-draft\"}", "UTF-8"));
            String shellSessionId = shellLocation.substring("/admin/debug/".length());
            Map<String, Object> shellPaused =
                waitForSessionState(testServer, shellSessionId, "PAUSED", 10000L);
            Assert.assertEquals("<draft>", shellPaused.get("version"));
            command(testServer, shellSessionId, "continue", null, 200);
            Map<String, Object> shellEnded =
                waitForSessionState(testServer, shellSessionId, "ENDED", 10000L);
            Assert.assertTrue(((List<?>) shellEnded.get("stdoutLines")).contains("shell-draft"));
            Assert.assertFalse("synthetic editor context must not be written",
                new File(new File(new File(dataDir, "script-registry"), "shell_editor_dbg"),
                    ".editor-draft.tee").exists());
        } finally {
            testServer.close();
        }
    }

    /** User decision (1.25.1): a debug re-run executes the CURRENT content of the recorded script
     *  version — editing the version after the failure and re-debugging the fix is supported. */
    @Test
    public void debugRerunRunsTheVersionsCurrentContentByDesign() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_edit");
            // Fix the SAME version in place, then debug re-run: the fixed content runs.
            testServer.server.getRunManager().updateScriptVersionContent("dbg_edit", "v1",
                "msg = \"hello \" + _PROPS.who\n" +
                "PRINT(\"fixed\")\n" +
                "PRINT(msg)\n");
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            // Editing the same version remains allowed while paused, but this already-open session
            // must keep executing the exact source shown in its editor.
            testServer.server.getRunManager().updateScriptVersionContent("dbg_edit", "v1",
                "FAIL(\"edited after debug launch\")\n");
            // Start is always line 1. The OLD positioned failure remains a red marker on line 2,
            // then stepping executes line 1 and pauses on the edited line 2 statement.
            Map<String, Object> paused = waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            Assert.assertEquals(1.0, pausedLine(paused), 0.0);
            Assert.assertEquals(2.0, ((Number) paused.get("errorLine")).doubleValue(), 0.0);
            command(testServer, sessionId, "stepOver", null, 200);
            paused = waitForPausedAtLine(testServer, sessionId, 2, 10000L);
            Assert.assertEquals(2.0, pausedLine(paused), 0.0);
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) paused.get("paused");
            Assert.assertTrue(String.valueOf(frame.get("statement")).contains("fixed"));
            command(testServer, sessionId, "continue", null, 200);
            Map<String, Object> ended = waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("COMPLETED", ended.get("runStatus"));
            Assert.assertNull("a successful debug attempt must clear the source error marker",
                ended.get("errorLine"));
        } finally {
            testServer.close();
        }
    }

    /** A duplicate resume queued during one pause must NOT silently consume the next pause: the
     *  pump refuses commands issued against an earlier pause generation. */
    @Test
    public void staleResumeCommandFromAnEarlierPauseIsRefusedNotApplied() throws Exception {
        TestServer testServer = createServer(null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_stale", "v1",
                "a = 1\nb = 2\nc = 3\nPRINT(c)\n", "stale", Arrays.asList("test"), true);
            String sourceRunId = (String) client.submitRun("dbg_stale", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, sourceRunId, "COMPLETED", 10000L);

            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{\"breakpoints\":[2,3]}", 201);
            final String sessionId = (String) session.get("sessionId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            command(testServer, sessionId, "continue", null, 200);
            waitForPausedAtLine(testServer, sessionId, 2, 10000L);

            // Occupy the line-2 handler with a slow eval so two Continues can be queued here.
            final Map<String, Object>[] evalResult = newResultSlot();
            final Map<String, Object>[] firstContinue = newResultSlot();
            Thread evalThread = commandInBackground(testServer, sessionId, "eval", "SLEEP(1200)", evalResult);
            Thread.sleep(300L);   // the pump is now inside the eval; state stays PAUSED
            Thread continueThread = commandInBackground(testServer, sessionId, "continue", null, firstContinue);
            Thread.sleep(100L);
            // Second Continue: passes the PAUSED check (same pause), queued behind the first.
            Map<String, Object> second = command(testServer, sessionId, "continue", null, 200);
            evalThread.join(20000L);
            continueThread.join(20000L);

            // The first Continue resumed pause #1; the second was refused at pause #2 (line 3)
            // instead of silently consuming it.
            Assert.assertEquals(Boolean.TRUE, firstContinue[0].get("accepted"));
            Assert.assertEquals("stale continue not refused: " + second,
                Boolean.TRUE, second.get("conflict"));
            Map<String, Object> paused = waitForPausedAtLine(testServer, sessionId, 3, 10000L);
            Assert.assertNotNull(paused);

            // The slow eval's outcome is queryable by command id (retry-free recovery from a
            // command() wait timeout).
            String evalCommandId = (String) evalResult[0].get("commandId");
            Map<String, Object> outcome = getJsonMap(testServer.baseUrl
                + "/api/admin/debug/" + sessionId + "/command/" + evalCommandId, 200);
            Assert.assertEquals(Boolean.TRUE, outcome.get("done"));

            command(testServer, sessionId, "continue", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    /** The debug-open endpoint re-executes real side effects: only an ABSENT body may default to
     *  auto-breakpoints — a malformed body must be a 400, never treated as "no body". */
    @Test
    public void malformedDebugRequestBodiesAreRejectedNotSilentlyIgnored() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_body");
            String openUrl = testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug";
            postJsonExpectingStatus(openUrl, "{broken", 400);
            postJsonExpectingStatus(openUrl, "{\"breakpoints\":\"x\"}", 400);
            postJsonExpectingStatus(openUrl, "{\"breakpoints\":[2,\"x\"]}", 400);

            // An empty body IS legitimate (auto breakpoint only).
            Map<String, Object> session = postJson(openUrl, "", 201);
            String sessionId = (String) session.get("sessionId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);

            // Same strictness on the live endpoints: bad JSON / bad list shape are 400s and the
            // session state is untouched.
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{broken", 400);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/breakpoints",
                "{\"lines\":\"x\"}", 400);
            // A mistyped generation must not silently disable the stale-frame protection.
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{\"op\":\"continue\",\"generation\":\"3\"}", 400);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{\"op\":\"continue\",\"generation\":3.5}", 400);
            Assert.assertEquals("PAUSED",
                getJsonMap(testServer.baseUrl + "/api/admin/debug/" + sessionId, 200).get("state"));

            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    /** A remote client can pin a command to the pause it SAW ({@code generation} from the status
     *  payload): if the session paused somewhere else meanwhile, the command is refused instead
     *  of acting on a frame the caller never looked at. */
    @Test
    public void commandTargetingAnOutdatedPausedFrameIsRefused() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_gen");
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            Map<String, Object> paused = waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            command(testServer, sessionId, "stepOver", null, 200);
            paused = waitForPausedAtLine(testServer, sessionId, 2, 10000L);
            long generation = ((Number) paused.get("pauseGeneration")).longValue();

            // Matching generation: accepted.
            Map<String, Object> ok = postJson(
                testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{\"op\":\"eval\",\"source\":\"msg\",\"generation\":" + generation + "}", 200);
            Assert.assertEquals("hello ops", ok.get("result"));
            // Mismatched generation: refused as a state conflict, session untouched.
            postJsonExpectingStatus(
                testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{\"op\":\"continue\",\"generation\":" + (generation + 1) + "}", 409);
            Assert.assertEquals("PAUSED",
                getJsonMap(testServer.baseUrl + "/api/admin/debug/" + sessionId, 200).get("state"));

            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    /** The REAL command-wait timeout path (wait shortened via system property): a slow eval
     *  reports timedOut + commandId, keeps executing, and its outcome is retrievable from the
     *  session-authed console route the debug page polls. */
    @Test
    public void timedOutCommandOutcomeIsRetrievableViaTheConsoleRoute() throws Exception {
        System.setProperty("propertee.teebox.debugCommandWaitMs", "300");
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_timeout");
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);

            Map<String, Object> timedOut = command(testServer, sessionId, "eval", "SLEEP(1500)", 200);
            Assert.assertEquals("expected a timeout: " + timedOut, Boolean.TRUE, timedOut.get("timedOut"));
            String commandId = (String) timedOut.get("commandId");
            Assert.assertNotNull(commandId);

            // The console polls this session-authed route until the command reports done.
            long deadline = System.currentTimeMillis() + 10000L;
            Map<String, Object> outcome = null;
            while (System.currentTimeMillis() < deadline) {
                outcome = getJsonMap(testServer.baseUrl + "/admin/debug/" + sessionId
                    + "/command/" + commandId, 200);
                if (Boolean.TRUE.equals(outcome.get("done"))) {
                    break;
                }
                Thread.sleep(100L);
            }
            Assert.assertEquals("command never finished: " + outcome, Boolean.TRUE, outcome.get("done"));
            Assert.assertNull("unexpected eval error: " + outcome, outcome.get("error"));
            Assert.assertEquals("PAUSED",
                getJsonMap(testServer.baseUrl + "/api/admin/debug/" + sessionId, 200).get("state"));

            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
        } finally {
            System.clearProperty("propertee.teebox.debugCommandWaitMs");
            testServer.close();
        }
    }

    /** A duplicate Continue with NO later breakpoint: the run completes, the leftover command is
     *  drained at session end — and must report accepted=false (it never executed), not success. */
    @Test
    public void drainedDuplicateContinueReportsNotAccepted() throws Exception {
        TestServer testServer = createServer(null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("dbg_drain", "v1",
                "a = 1\nb = 2\nPRINT(b)\n", "drain", Arrays.asList("test"), true);
            String sourceRunId = (String) client.submitRun("dbg_drain", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, sourceRunId, "COMPLETED", 10000L);

            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{\"breakpoints\":[2]}", 201);   // ONE pause — nothing after it to refuse at
            final String sessionId = (String) session.get("sessionId");
            waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            command(testServer, sessionId, "continue", null, 200);
            waitForPausedAtLine(testServer, sessionId, 2, 10000L);

            // Occupy the only user-breakpoint pause with a slow eval so two Continues queue.
            final Map<String, Object>[] evalResult = newResultSlot();
            final Map<String, Object>[] firstContinue = newResultSlot();
            Thread evalThread = commandInBackground(testServer, sessionId, "eval", "SLEEP(1200)", evalResult);
            Thread.sleep(300L);
            Thread continueThread = commandInBackground(testServer, sessionId, "continue", null, firstContinue);
            Thread.sleep(100L);
            Map<String, Object> second = command(testServer, sessionId, "continue", null, 200);
            evalThread.join(20000L);
            continueThread.join(20000L);

            Assert.assertEquals(Boolean.TRUE, firstContinue[0].get("accepted"));
            Assert.assertEquals("drained continue reported as success: " + second,
                Boolean.FALSE, second.get("accepted"));
            Assert.assertTrue(String.valueOf(second.get("error")).contains("ended"));

            Map<String, Object> ended = waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Assert.assertEquals("COMPLETED", ended.get("runStatus"));
            // The by-id outcome marks it rejected too.
            Map<String, Object> outcome = getJsonMap(testServer.baseUrl + "/api/admin/debug/"
                + sessionId + "/command/" + second.get("commandId"), 200);
            Assert.assertEquals(Boolean.TRUE, outcome.get("done"));
            Assert.assertEquals(Boolean.TRUE, outcome.get("rejected"));
        } finally {
            testServer.close();
        }
    }

    // ===================== helpers =====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] newResultSlot() {
        return (Map<String, Object>[]) new Map[1];
    }

    /** Fire a command() call on a background thread, capturing its response into {@code slot[0]}. */
    private Thread commandInBackground(final TestServer testServer, final String sessionId,
                                       final String op, final String source,
                                       final Map<String, Object>[] slot) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    slot[0] = command(testServer, sessionId, op, source, 200);
                } catch (Throwable t) {
                    Map<String, Object> error = new LinkedHashMap<String, Object>();
                    error.put("threadError", String.valueOf(t));
                    slot[0] = error;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Register + run the failing script (with props) and wait for FAILED; returns the runId. */
    private String runFailingScript(TestServer testServer, String scriptId) throws Exception {
        TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
        client.registerScript(scriptId, "v1", FAILING_SCRIPT, "failing", Arrays.asList("test"), true);
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("who", "ops");
        String runId = (String) client.submitRun(scriptId, null, props).get("runId");
        Map<String, Object> failed = waitForRunStatus(client, runId, "FAILED", 10000L);
        Assert.assertTrue(String.valueOf(failed.get("errorMessage")).contains("at line 2:0"));
        return runId;
    }

    private Map<String, Object> command(TestServer testServer, String sessionId, String op,
                                        String source, int expectedStatus) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("op", op);
        if (source != null) {
            body.put("source", source);
        }
        return postJson(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
            gson.toJson(body), expectedStatus);
    }

    private double pausedLine(Map<String, Object> sessionState) {
        Map<?, ?> frame = (Map<?, ?>) sessionState.get("paused");
        return ((Number) frame.get("line")).doubleValue();
    }

    private String pausedReason(Map<String, Object> sessionState) {
        Map<?, ?> frame = (Map<?, ?>) sessionState.get("paused");
        return String.valueOf(frame.get("reason"));
    }

    private Map<String, Object> waitForSessionState(TestServer testServer, String sessionId,
                                                    String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getJsonMap(testServer.baseUrl + "/api/admin/debug/" + sessionId, 200);
            if (expected.equals(last.get("state"))) {
                return last;
            }
            Thread.sleep(50L);
        }
        Assert.fail("session " + sessionId + " did not reach " + expected + " within "
            + timeoutMs + "ms; last=" + last);
        return null;
    }

    private Map<String, Object> waitForPausedAtLine(TestServer testServer, String sessionId,
                                                    int line, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getJsonMap(testServer.baseUrl + "/api/admin/debug/" + sessionId, 200);
            if ("PAUSED".equals(last.get("state")) && last.get("paused") != null
                    && (int) pausedLine(last) == line) {
                return last;
            }
            Thread.sleep(50L);
        }
        Assert.fail("session " + sessionId + " did not pause at line " + line + " within "
            + timeoutMs + "ms; last=" + last);
        return null;
    }

    private Map<String, Object> waitForRunStatus(TeeBoxClient client, String runId, String expected,
                                                 long timeoutMs) throws IOException, InterruptedException {
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

    private Map<String, Object> postJson(String url, String body, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        int status = conn.getResponseCode();
        InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = input != null ? readAll(input) : "";
        conn.disconnect();
        Assert.assertEquals("body: " + text, expectedStatus, status);
        return gson.fromJson(text, mapType);
    }

    private void postJsonExpectingStatus(String url, String body, int expectedStatus) throws IOException {
        postJson(url, body, expectedStatus);
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

    private String postFormExpectingRedirect(String url, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = input != null ? readAll(input) : "";
        conn.disconnect();
        Assert.assertEquals("body: " + text, 302, code);
        Assert.assertNotNull(location);
        return location;
    }

    private Map<String, Object> getJsonMap(String url, int expectedStatus) throws IOException {
        return gson.fromJson(getBody(url, expectedStatus), mapType);
    }

    private String getBody(String url, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = input != null ? readAll(input) : "";
        conn.disconnect();
        Assert.assertEquals("body: " + text, expectedStatus, status);
        return text;
    }

    private String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = input.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    private TestServer createServer(TeeBoxConfig overrides) throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-debug").toFile();
        return createServer(dataDir, overrides);
    }

    private TestServer createServer(File dataDir, TeeBoxConfig overrides) throws Exception {
        TeeBoxConfig config = overrides != null ? overrides : new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return new TestServer(server, "http://127.0.0.1:" + server.getPort());
    }

    private static class TestServer {
        private final TeeBoxServer server;
        private final String baseUrl;

        private TestServer(TeeBoxServer server, String baseUrl) {
            this.server = server;
            this.baseUrl = baseUrl;
        }

        private void close() {
            server.stop();
        }
    }
}
