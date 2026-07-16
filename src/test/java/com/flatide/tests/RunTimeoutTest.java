package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import java.util.Map;

/**
 * Run execution timeout (1.16.0): the wall clock starts at RUNNING (queue wait excluded), the
 * per-run {@code timeoutMs} request field overrides the server-wide {@code runTimeoutMs} config,
 * 0/unset means off, and a timed-out run terminates as CANCELLED with the timeout reason.
 */
public class RunTimeoutTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

    private static final String SPIN_SCRIPT = "loop true infinite do\nend\n";

    @Test
    public void perRunTimeoutCancelsARunawayRun() throws Exception {
        TestServer testServer = createServer(2, 0L);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("spin_t", "v1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);

            String runId = submitWithTimeout(testServer.baseUrl, "spin_t", 500L);
            Map<String, Object> terminal = waitForStatus(client, runId, "CANCELLED", 10000L);
            String message = String.valueOf(terminal.get("errorMessage"));
            Assert.assertTrue("unexpected reason: " + message, message.contains("exceeded timeout (500 ms)"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverDefaultTimeoutAppliesWhenTheRunSetsNone() throws Exception {
        TestServer testServer = createServer(2, 500L);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("spin_d", "v1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("spin_d", null, new LinkedHashMap<String, Object>()).get("runId");
            Map<String, Object> terminal = waitForStatus(client, runId, "CANCELLED", 10000L);
            Assert.assertTrue(String.valueOf(terminal.get("errorMessage")).contains("exceeded timeout (500 ms)"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void noTimeoutMeansTheRunKeepsRunning() throws Exception {
        TestServer testServer = createServer(2, 0L);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("spin_off", "v1", SPIN_SCRIPT, "spin", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("spin_off", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, runId, "RUNNING", 8000L);
            Thread.sleep(1500L);
            Assert.assertEquals("RUNNING", String.valueOf(client.getRunStatus(runId).get("status")));
            client.cancelRun(runId);   // cleanup so server shutdown isn't held by the spinner
            waitForStatus(client, runId, "CANCELLED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void aGenerousTimeoutNeverCancelsAFastRun() throws Exception {
        TestServer testServer = createServer(2, 0L);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("quick_t", "v1", "return {\"n\": 1}\n", "quick", Arrays.asList("test"), true);

            String runId = submitWithTimeout(testServer.baseUrl, "quick_t", 30000L);
            waitForStatus(client, runId, "COMPLETED", 10000L);
            Thread.sleep(300L);   // a stray late cancel would flip the status
            Assert.assertEquals("COMPLETED", String.valueOf(client.getRunStatus(runId).get("status")));
        } finally {
            testServer.close();
        }
    }

    /** The timeout clock starts at RUNNING: a run whose queue wait exceeds its timeout still
     *  executes and completes (a submit-time clock would cancel it while QUEUED). */
    @Test
    public void queueWaitDoesNotCountTowardTheTimeout() throws Exception {
        TestServer testServer = createServer(1, 0L);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("holder", "v1", "SLEEP(2000)\nreturn 1\n", "holder", Arrays.asList("test"), true);
            client.registerScript("waiter", "v1", "return {\"n\": 2}\n", "waiter", Arrays.asList("test"), true);

            String holder = (String) client.submitRun("holder", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForStatus(client, holder, "RUNNING", 8000L);
            // Queued behind a ~2s run with a 1s timeout: only an execution-based clock lets it finish.
            String waiter = submitWithTimeout(testServer.baseUrl, "waiter", 1000L);
            Assert.assertEquals("QUEUED", String.valueOf(client.getRunStatus(waiter).get("status")));

            waitForStatus(client, waiter, "COMPLETED", 15000L);
        } finally {
            testServer.close();
        }
    }

    // ===================== helpers =====================

    private String submitWithTimeout(String baseUrl, String scriptId, long timeoutMs) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("props", new LinkedHashMap<String, Object>());
        payload.put("timeoutMs", timeoutMs);
        Map<String, Object> submitted = postJson(baseUrl + "/api/client/scripts/" + scriptId + "/runs", payload, 202);
        String runId = (String) submitted.get("runId");
        Assert.assertNotNull(runId);
        return runId;
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

    private Map<String, Object> postJson(String url, Map<String, Object> payload, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = gson.toJson(payload).getBytes("UTF-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body);
        } finally {
            out.close();
        }
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

    private String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private TestServer createServer(int maxConcurrentRuns, long runTimeoutMs) throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-timeout").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = maxConcurrentRuns;
        config.runTimeoutMs = runTimeoutMs;

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
