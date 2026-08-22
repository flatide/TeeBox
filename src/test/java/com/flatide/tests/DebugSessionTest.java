package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive debug re-runs (1.25.0): a finished run re-executed under the engine debugger
 * (propertee2 0.26.0 façade hooks) on the dedicated debug executor, driven over the admin API —
 * auto-breakpoint on the failing line, pause/eval/step/continue, quit and cancel both ending as
 * CANCELLED (never FAILED), the capacity cap, and the idle sweep.
 */
public class DebugSessionTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

    /** Fails on line 2 with a positioned error — the auto-breakpoint target. Line 1 proves the
     *  debug re-run replays the source run's input properties. */
    private static final String FAILING_SCRIPT =
        "msg = \"hello \" + _PROPS.who\n" +
        "FAIL(\"upstream unreachable\")\n" +
        "PRINT(\"after\")\n";

    private static final String QUICK_SCRIPT =
        "a = 1\n" +
        "b = a + 1\n" +
        "PRINT(b)\n";

    @Test
    public void failedRunDebugRerunPausesAtTheFailingLineAndEvalReadsAndWritesTheScope() throws Exception {
        TestServer testServer = createServer(null);
        try {
            String sourceRunId = runFailingScript(testServer, "dbg_fail");

            // Open: no body — the breakpoint comes from the source run's positioned error.
            Map<String, Object> session = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            String sessionId = (String) session.get("sessionId");
            String debugRunId = (String) session.get("runId");
            Assert.assertEquals(Arrays.asList(2.0), session.get("breakpoints"));

            Map<String, Object> paused = waitForSessionState(testServer, sessionId, "PAUSED", 10000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) paused.get("paused");
            Assert.assertEquals(2.0, ((Number) frame.get("line")).doubleValue(), 0.0);
            Assert.assertEquals("BREAKPOINT", frame.get("reason"));
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

            // Commands against an ended session are a state error, not a crash.
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/debug/" + sessionId + "/command",
                "{\"op\":\"continue\"}", 409);
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

            // The one slot is held (a paused session occupies it for its whole lifetime).
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug",
                "{}", 409);
            // A still-running source is rejected; an unknown one is 404.
            client.registerScript("dbg_spin", "v1", "loop true infinite do\nend\n", "spin",
                Arrays.asList("test"), true);
            String spinning = (String) client.submitRun("dbg_spin", null,
                new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(client, spinning, "RUNNING", 10000L);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/" + spinning + "/debug",
                "{}", 409);
            postJsonExpectingStatus(testServer.baseUrl + "/api/admin/runs/run-nope/debug", "{}", 404);

            // Quit frees the slot; a new session opens.
            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
            Map<String, Object> second = postJson(
                testServer.baseUrl + "/api/admin/runs/" + sourceRunId + "/debug", "{}", 201);
            command(testServer, (String) second.get("sessionId"), "quit", null, 200);
            waitForSessionState(testServer, (String) second.get("sessionId"), "ENDED", 10000L);

            client.cancelRun(spinning);
            waitForRunStatus(client, spinning, "CANCELLED", 10000L);
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

            // The session-authed state endpoint the console polls.
            Map<String, Object> state = getJsonMap(
                testServer.baseUrl + "/admin/debug/" + sessionId + "/state", 200);
            Assert.assertEquals(sessionId, state.get("sessionId"));

            command(testServer, sessionId, "quit", null, 200);
            waitForSessionState(testServer, sessionId, "ENDED", 10000L);
        } finally {
            testServer.close();
        }
    }

    // ===================== helpers =====================

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
