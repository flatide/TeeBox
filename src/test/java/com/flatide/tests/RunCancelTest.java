package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunManager;
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

    // ===================== helpers =====================

    private static RunRequest request(String scriptId) {
        RunRequest request = new RunRequest();
        request.scriptId = scriptId;
        return request;
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
