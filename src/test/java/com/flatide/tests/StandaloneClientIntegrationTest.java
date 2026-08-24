package com.flatide.tests;

import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.client.TeeBoxClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Integration coverage for the embeddable, zero-dependency client shipped in
 * {@code client/com/flatide/teebox/client/TeeBoxClient.java} (pulled into the test source set by
 * build.gradle). It is the artifact copied verbatim into host projects, so exercise it end-to-end
 * against a live server here — otherwise it can rot independently of the in-module client used by
 * {@link TeeBoxServerTest}.
 */
public class StandaloneClientIntegrationTest {

    @Test
    public void managesScriptAliasWithoutVersionLabelsThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);
            Map<String, Object> created = client.registerScriptWithAlias(
                "alias_client", "v1", "return 1\n", "first version", "Friendly Name", true);
            Assert.assertEquals("Friendly Name", created.get("alias"));
            Assert.assertFalse(((Map<?, ?>) ((List<?>) created.get("versions")).get(0))
                .containsKey("labels"));

            Map<String, Object> updated = client.updateScriptSettings(
                "alias_client", 3, true, "Updated Name");
            Assert.assertEquals("Updated Name", updated.get("alias"));
            Assert.assertEquals(3.0,
                ((Number) updated.get("maxConcurrentRuns")).doubleValue(), 0.0);

            // A pre-alias compiled caller still links to this method, but labels are discarded.
            Map<String, Object> second = client.registerScript(
                "alias_client", "v2", "return 2\n", "second version",
                Arrays.asList("legacy"), false);
            Assert.assertEquals("Updated Name", second.get("alias"));
            for (Object item : (List<?>) second.get("versions")) {
                Assert.assertFalse(((Map<?, ?>) item).containsKey("labels"));
            }
        } finally {
            server.close();
        }
    }

    @Test
    public void registersRunsAndReadsResultThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);

            String source = "return {\"ok\": true, \"sum\": a + b}\n";
            Map<String, Object> registered = client.registerScript("calc_sum", source, true);
            Assert.assertEquals("calc_sum", registered.get("scriptId"));
            Assert.assertEquals("1", String.valueOf(registered.get("activeVersion")));

            // listScripts / getScriptContent round-trip against the deployable client
            List<Object> scripts = client.listScripts();
            Assert.assertTrue("calc_sum should be listed", containsScriptId(scripts, "calc_sum"));
            String content = client.getScriptContent("calc_sum");
            Assert.assertNotNull("content", content);
            Assert.assertTrue("content round-trips", content.contains("\"sum\": a + b"));

            // synchronous run via the convenience helper: a=40, b=2 => sum 42
            Map<String, Object> props = new LinkedHashMap<String, Object>();
            props.put("a", 40);
            props.put("b", 2);
            Map<String, Object> result = client.runAndWait("calc_sum", null, props, 30000L);

            Assert.assertEquals("COMPLETED", String.valueOf(result.get("status")));
            Map<?, ?> data = (Map<?, ?>) result.get("resultData");
            Assert.assertEquals(42.0, ((Number) data.get("sum")).doubleValue(), 0.0001);
            Assert.assertEquals(Boolean.TRUE, data.get("ok"));
        } finally {
            server.close();
        }
    }

    @Test
    public void userIdIsSentAsHeaderAndRecordedOnTheRun() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);
            client.registerScript("audit_me", "return {\"done\": true}\n", true);

            // submitRun with a userId: the client sends X-TeeBox-User; the server records it and
            // returns it as submittedBy in run status/summaries.
            Map<String, Object> submitted = client.submitRun("audit_me", null,
                new LinkedHashMap<String, Object>(), null, "journey.kim");
            String runId = String.valueOf(submitted.get("runId"));
            Assert.assertEquals("journey.kim", submitted.get("submittedBy"));
            Map<String, Object> status = client.waitForRunTerminal(runId, 30000L);
            Assert.assertEquals("journey.kim", status.get("submittedBy"));

            // userId is nullable: without it the run is anonymous (submittedBy null/absent).
            Map<String, Object> anon = client.submitRun("audit_me", new LinkedHashMap<String, Object>());
            Assert.assertNull("null userId => anonymous run", anon.get("submittedBy"));

            // runAndWait overload threads the userId through its submit.
            Map<String, Object> result = client.runAndWait("audit_me", null,
                new LinkedHashMap<String, Object>(), 30000L, "batch-svc");
            Assert.assertEquals("COMPLETED", String.valueOf(result.get("status")));

            // The admin Runs list shows the submitter in a dedicated "By" column (dash when anonymous).
            String runsTable = readUrl(server.baseUrl + "/admin/fragments/all-runs");
            Assert.assertTrue("runs table has a By column", runsTable.contains("<th>By</th>"));
            Assert.assertTrue("submitter shown in the list", runsTable.contains("journey.kim"));
            Assert.assertTrue("second submitter shown too", runsTable.contains("batch-svc"));
            Assert.assertTrue("anonymous run shows a dash", runsTable.contains("&mdash;"));

            // The caller IP is recorded at submit time and shown on the run detail page ("From (IP)").
            // Local test traffic arrives from the loopback address.
            String detailPage = readUrl(server.baseUrl + "/admin/runs/" + runId);
            Assert.assertTrue("detail page has a From (IP) field", detailPage.contains("From (IP)"));
            Assert.assertTrue("caller IP recorded and displayed", detailPage.contains("127.0.0.1"));
            // The admin run-detail JSON (full RunInfo) carries it as submittedFrom.
            String adminJson = readUrl(server.baseUrl + "/api/admin/runs/" + runId);
            Assert.assertTrue("admin JSON carries submittedFrom", adminJson.contains("\"submittedFrom\""));
            Assert.assertTrue("admin JSON has the IP", adminJson.contains("127.0.0.1"));
        } finally {
            server.close();
        }
    }

    private static String readUrl(String url) throws Exception {
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        InputStream in = conn.getInputStream();
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    @Test
    public void submitAndPollTerminalThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);
            client.registerScript("noop", "return {\"done\": true}\n", true);

            Map<String, Object> submitted = client.submitRun("noop", new LinkedHashMap<String, Object>());
            Assert.assertNotNull("runId", submitted.get("runId"));
            String runId = String.valueOf(submitted.get("runId"));

            Map<String, Object> terminal = client.waitForRunTerminal(runId, 30000L);
            Assert.assertEquals("COMPLETED", String.valueOf(terminal.get("status")));
        } finally {
            server.close();
        }
    }

    /**
     * The run-result envelope (ProperTee design-draft-result-handling.md §5): every run outcome is
     * additionally exposed in the one ProperTee-Result shape {@code {status, ok, value}} — the run
     * viewed as "thread #0". Covers all four RunStatus rows reachable in a test (COMPLETED, FAILED,
     * not-yet-terminal) plus the two documented edges: deliberate double-wrapping (a script that
     * returns a Result nests it; the outer {@code ok} stays {@code true}) and a value-less run
     * ({@code value} is {@code {}}, the language's "no value").
     */
    @Test
    public void exposesRunResultEnvelopeThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);

            // ① COMPLETED, plain return → {status:"done", ok:true, value:42}
            client.registerScript("env_done", "return 40 + 2\n", true);
            String doneId = String.valueOf(
                client.submitRun("env_done", new LinkedHashMap<String, Object>()).get("runId"));
            client.waitForRunTerminal(doneId, 30000L);
            Map<String, Object> env = client.getRunEnvelope(doneId);
            Assert.assertEquals("done", env.get("status"));
            Assert.assertEquals(Boolean.TRUE, env.get("ok"));
            Assert.assertEquals(42.0, ((Number) env.get("value")).doubleValue(), 0.0001);

            // Additive: the envelope rides on getRunResult as the "result" field; legacy fields stay.
            Map<String, Object> result = client.getRunResult(doneId);
            Assert.assertEquals(42.0, ((Number) result.get("resultData")).doubleValue(), 0.0001);
            Assert.assertEquals(env, result.get("result"));

            // ② COMPLETED, script deliberately returns a ProperTee Result → nested; outer ok TRUE.
            // (This is exactly the case a conditional pass-through would misjudge — the inner run's
            // ok:false is the script's DATA, not this run's failure.)
            client.registerScript("env_nested", "return ERR(\"boom\")\n", true);
            String nestedId = String.valueOf(
                client.submitRun("env_nested", new LinkedHashMap<String, Object>()).get("runId"));
            client.waitForRunTerminal(nestedId, 30000L);
            env = client.getRunEnvelope(nestedId);
            Assert.assertEquals("done", env.get("status"));
            Assert.assertEquals(Boolean.TRUE, env.get("ok"));
            Map<?, ?> inner = (Map<?, ?>) env.get("value");
            Assert.assertEquals("error", inner.get("status"));
            Assert.assertEquals(Boolean.FALSE, inner.get("ok"));
            Assert.assertEquals("boom", inner.get("value"));

            // ③ FAILED via FAIL() → {status:"error", ok:false, value:<errorMessage>}
            client.registerScript("env_fail", "FAIL(\"fatal: db down\")\n", true);
            String failId = String.valueOf(
                client.submitRun("env_fail", new LinkedHashMap<String, Object>()).get("runId"));
            Map<String, Object> failTerminal = client.waitForRunTerminal(failId, 30000L);
            Assert.assertEquals("FAILED", String.valueOf(failTerminal.get("status")));
            env = client.getRunEnvelope(failId);
            Assert.assertEquals("error", env.get("status"));
            Assert.assertEquals(Boolean.FALSE, env.get("ok"));
            String message = String.valueOf(env.get("value"));
            Assert.assertTrue("envelope value carries the error message: " + message,
                message.contains("fatal: db down"));
            // v1 host contract (propertee-core 0.9.1): errorMessage carries the FAIL site's position
            Assert.assertTrue("errorMessage is positioned: " + message,
                message.startsWith("Runtime Error at line "));
            Assert.assertEquals(client.getRunResult(failId).get("errorMessage"), env.get("value"));

            // ④ no return, no result variable → value {} (the language's "no value", never null)
            client.registerScript("env_void", "x = 1\n", true);
            String voidId = String.valueOf(
                client.submitRun("env_void", new LinkedHashMap<String, Object>()).get("runId"));
            client.waitForRunTerminal(voidId, 30000L);
            env = client.getRunEnvelope(voidId);
            Assert.assertEquals("done", env.get("status"));
            Assert.assertEquals(Boolean.TRUE, env.get("ok"));
            Assert.assertTrue("value is {} for a value-less run", ((Map<?, ?>) env.get("value")).isEmpty());

            // ⑤ not terminal yet → {status:"running", ok:false, value:{}}
            client.registerScript("env_slow", "SLEEP(2000)\nreturn 1\n", true);
            String slowId = String.valueOf(
                client.submitRun("env_slow", new LinkedHashMap<String, Object>()).get("runId"));
            env = client.getRunEnvelope(slowId);
            Assert.assertEquals("running", env.get("status"));
            Assert.assertEquals(Boolean.FALSE, env.get("ok"));
            Assert.assertTrue("value is {} while running", ((Map<?, ?>) env.get("value")).isEmpty());
            client.waitForRunTerminal(slowId, 30000L);   // drain before teardown
        } finally {
            server.close();
        }
    }

    @Test
    public void mergesTaskStdoutIntoRunStdoutThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);

            // The script prints to its OWN stdout and also runs a SHELL task that writes to the
            // TASK stdout — two separately-captured streams the merged endpoint returns together.
            String source =
                    "PRINT(\"hello-from-script\")\n" +
                    "result = SHELL(\"echo hello-from-task\")\n" +
                    "return {\"ok\": true}\n";
            client.registerScript("merge_stdout", source, true);

            Map<String, Object> submitted = client.submitRun("merge_stdout", new LinkedHashMap<String, Object>());
            String runId = String.valueOf(submitted.get("runId"));
            Map<String, Object> terminal = client.waitForRunTerminal(runId, 30000L);
            Assert.assertEquals("COMPLETED", String.valueOf(terminal.get("status")));

            // Poll briefly so any task-stdout flush after the run goes terminal is tolerated.
            Map<String, Object> stdout = null;
            List<String> scriptLines = null;
            List<String> taskLines = null;
            long deadline = System.currentTimeMillis() + 10000L;
            do {
                stdout = client.getRunStdout(runId);
                scriptLines = client.getRunStdoutLines(runId);
                taskLines = client.getRunTaskStdoutLines(runId);
                if (containsLine(taskLines, "hello-from-task")) {
                    break;
                }
                Thread.sleep(100);
            } while (System.currentTimeMillis() < deadline);

            Assert.assertTrue("script PRINT output should be in lines: " + scriptLines,
                    containsLine(scriptLines, "hello-from-script"));
            Assert.assertTrue("SHELL task output should be in taskLines: " + taskLines,
                    containsLine(taskLines, "hello-from-task"));
            Assert.assertTrue("at least one task should be tracked",
                    ((Number) stdout.get("taskCount")).intValue() >= 1);
            // The streams stay distinct: SHELL output must not leak into the script lines.
            Assert.assertFalse("task output must not appear in script lines: " + scriptLines,
                    containsLine(scriptLines, "hello-from-task"));
        } finally {
            server.close();
        }
    }

    @Test
    public void tailsTaskStdoutToLineCapThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);

            // 500 lines of task output (1..500) to exercise the tail line cap.
            String source =
                    "result = SHELL(\"seq 1 500\")\n" +
                    "return {\"ok\": true}\n";
            client.registerScript("tail_task", source, true);

            Map<String, Object> submitted = client.submitRun("tail_task", new LinkedHashMap<String, Object>());
            String runId = String.valueOf(submitted.get("runId"));
            Assert.assertEquals("COMPLETED",
                    String.valueOf(client.waitForRunTerminal(runId, 30000L).get("status")));

            // Default cap = 200: the LAST 200 lines (301..500), flagged truncated.
            List<String> def = awaitTaskStdoutLines(client, runId);
            Assert.assertEquals(200, def.size());
            Assert.assertEquals("301", def.get(0));
            Assert.assertEquals("500", def.get(def.size() - 1));
            Assert.assertEquals(Boolean.TRUE, client.getRunStdout(runId).get("taskLinesTruncated"));

            // Explicit smaller cap = 50: the last 50 lines (451..500).
            List<String> fifty = client.getRunTaskStdoutLines(runId, 50);
            Assert.assertEquals(50, fifty.size());
            Assert.assertEquals("451", fifty.get(0));
            Assert.assertEquals("500", fifty.get(fifty.size() - 1));

            // No cap = 0: all 500 lines, not truncated.
            List<String> all = client.getRunTaskStdoutLines(runId, 0);
            Assert.assertEquals(500, all.size());
            Assert.assertEquals(Boolean.FALSE, client.getRunStdout(runId, 0).get("taskLinesTruncated"));
        } finally {
            server.close();
        }
    }

    @Test
    public void mergesMultipleSequentialShellTasksInSpawnOrderThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);

            // Two SHELL tasks run sequentially; the merged taskLines must carry BOTH, in spawn order.
            // The first task sleeps briefly so its start time is unambiguously before the second's
            // (the merge reverses the newest-first task index, so equal start times could otherwise flip).
            String source =
                    "r1 = SHELL(\"sleep 0.2; echo first-task-out\")\n" +
                    "r2 = SHELL(\"echo second-task-out\")\n" +
                    "return {\"ok\": true}\n";
            client.registerScript("multi_shell", source, true);

            Map<String, Object> submitted = client.submitRun("multi_shell", new LinkedHashMap<String, Object>());
            String runId = String.valueOf(submitted.get("runId"));
            Assert.assertEquals("COMPLETED",
                    String.valueOf(client.waitForRunTerminal(runId, 30000L).get("status")));

            // Poll until both tasks are tracked (index/flush after run-terminal is tolerated).
            Map<String, Object> stdout = client.getRunStdout(runId);
            long deadline = System.currentTimeMillis() + 10000L;
            while (taskCount(stdout) < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                stdout = client.getRunStdout(runId);
            }
            Assert.assertEquals("both SHELL tasks should be tracked", 2, taskCount(stdout));

            // Merged task output carries both lines, in spawn (chronological) order.
            List<String> taskLines = client.getRunTaskStdoutLines(runId);
            Assert.assertEquals(2, taskLines.size());
            Assert.assertEquals("first-task-out", taskLines.get(0));
            Assert.assertEquals("second-task-out", taskLines.get(1));

            // Per-task breakdown: one entry per task, same spawn order, lineCounts summing to taskLineCount.
            List<?> tasks = (List<?>) stdout.get("tasks");
            Assert.assertEquals(2, tasks.size());
            Assert.assertTrue("first breakdown entry should be the first SHELL",
                    String.valueOf(((Map<?, ?>) tasks.get(0)).get("command")).contains("first-task-out"));
            Assert.assertTrue("second breakdown entry should be the second SHELL",
                    String.valueOf(((Map<?, ?>) tasks.get(1)).get("command")).contains("second-task-out"));
            int sum = 0;
            for (Object t : tasks) {
                sum += ((Number) ((Map<?, ?>) t).get("lineCount")).intValue();
            }
            Assert.assertEquals(2, ((Number) stdout.get("taskLineCount")).intValue());
            Assert.assertEquals("per-task lineCounts must sum to taskLineCount",
                    ((Number) stdout.get("taskLineCount")).intValue(), sum);
            Assert.assertEquals(Boolean.FALSE, stdout.get("taskLinesTruncated"));
        } finally {
            server.close();
        }
    }

    @Test
    public void mergesParallelMultiThreadShellTasksThroughDeployableClient() throws Exception {
        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);

            // Three SHELL tasks run in PARALLEL via multi/thread. Their completion (hence spawn/start)
            // order races, so assert set membership — every task's output is present in the merged
            // taskLines — rather than a positional order.
            String source =
                    "function worker(name) do\n" +
                    "    return SHELL(\"echo parallel-\" + name)\n" +
                    "end\n" +
                    "multi result do\n" +
                    "    thread alpha: worker(\"alpha\")\n" +
                    "    thread beta: worker(\"beta\")\n" +
                    "    thread gamma: worker(\"gamma\")\n" +
                    "end\n" +
                    "return {\"ok\": true}\n";
            client.registerScript("multi_parallel_shell", source, true);

            Map<String, Object> submitted = client.submitRun("multi_parallel_shell", new LinkedHashMap<String, Object>());
            String runId = String.valueOf(submitted.get("runId"));
            Assert.assertEquals("COMPLETED",
                    String.valueOf(client.waitForRunTerminal(runId, 30000L).get("status")));

            // Poll until all three parallel tasks are tracked.
            Map<String, Object> stdout = client.getRunStdout(runId);
            long deadline = System.currentTimeMillis() + 10000L;
            while (taskCount(stdout) < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                stdout = client.getRunStdout(runId);
            }
            Assert.assertEquals("all three parallel SHELL tasks should be tracked", 3, taskCount(stdout));

            // Merged task output contains every worker's line (order is non-deterministic).
            List<String> taskLines = client.getRunTaskStdoutLines(runId);
            Assert.assertEquals("exactly the three task lines", 3, taskLines.size());
            Assert.assertTrue("alpha output present: " + taskLines, containsLine(taskLines, "parallel-alpha"));
            Assert.assertTrue("beta output present: " + taskLines, containsLine(taskLines, "parallel-beta"));
            Assert.assertTrue("gamma output present: " + taskLines, containsLine(taskLines, "parallel-gamma"));

            // Per-task breakdown stays consistent regardless of completion order.
            List<?> tasks = (List<?>) stdout.get("tasks");
            Assert.assertEquals(3, tasks.size());
            int sum = 0;
            for (Object t : tasks) {
                sum += ((Number) ((Map<?, ?>) t).get("lineCount")).intValue();
            }
            Assert.assertEquals(3, ((Number) stdout.get("taskLineCount")).intValue());
            Assert.assertEquals("per-task lineCounts must sum to taskLineCount",
                    ((Number) stdout.get("taskLineCount")).intValue(), sum);
        } finally {
            server.close();
        }
    }

    @Test
    public void runsHttpGetBuiltinThroughTeeBox() throws Exception {
        // Proves the restored ProperTee v2 HTTP builtins work end-to-end through TeeBox: a submitted
        // run calls HTTP_GET against a loopback target and returns the response. HTTP runs off the
        // cooperative baton (Coop.blocking) via TeeBoxPlatformProvider's inherited httpRequest.
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/hello", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                byte[] body = "hello-from-http".getBytes("UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        target.start();
        int targetPort = target.getAddress().getPort();

        TestServer server = startServer();
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);
            String source =
                    "r = HTTP_GET(\"http://127.0.0.1:" + targetPort + "/hello\")\n" +
                    "return {\"httpStatus\": r.value.status, \"body\": r.value.body, \"ok\": r.ok}\n";
            client.registerScript("http_get", source, true);

            Map<String, Object> result = client.runAndWait(
                    "http_get", null, new LinkedHashMap<String, Object>(), 30000L);

            Assert.assertEquals("COMPLETED", String.valueOf(result.get("status")));
            Map<?, ?> data = (Map<?, ?>) result.get("resultData");
            Assert.assertEquals(200.0, ((Number) data.get("httpStatus")).doubleValue(), 0.0001);
            Assert.assertEquals("hello-from-http", data.get("body"));
            Assert.assertEquals(Boolean.TRUE, data.get("ok"));
        } finally {
            server.close();
            target.stop(0);
        }
    }

    @Test
    public void submitWithCallbackUrlThroughDeployableClient() throws Exception {
        // Verifies the 4-arg submitRun(..., callbackUrl) overload actually puts the callback in the
        // request body: a webhook-enabled server delivers to our local receiver only if it parsed it.
        final BlockingQueue<String> delivered = new LinkedBlockingQueue<String>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/cb", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                drain(exchange.getRequestBody());
                String runId = exchange.getRequestHeaders().getFirst("X-TeeBox-Delivery");
                delivered.add(runId != null ? runId : "");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        receiver.start();
        int receiverPort = receiver.getAddress().getPort();

        TestServer server = startServer(true, "127.0.0.1");
        try {
            TeeBoxClient client = new TeeBoxClient(server.baseUrl);
            client.registerScript("wh_noop", "return {\"done\": true}\n", true);

            String callbackUrl = "http://127.0.0.1:" + receiverPort + "/cb";
            Map<String, Object> submitted = client.submitRun(
                "wh_noop", null, new LinkedHashMap<String, Object>(), callbackUrl);
            String runId = String.valueOf(submitted.get("runId"));
            Assert.assertNotNull("runId", submitted.get("runId"));

            String deliveredRunId = delivered.poll(15, TimeUnit.SECONDS);
            Assert.assertNotNull("receiver never got the webhook (callback body not sent?)", deliveredRunId);
            Assert.assertEquals(runId, deliveredRunId);
        } finally {
            server.close();
            receiver.stop(0);
        }
    }

    private static void drain(InputStream in) throws java.io.IOException {
        byte[] buf = new byte[2048];
        while (in.read(buf) != -1) {
            // discard
        }
        in.close();
    }

    private static int taskCount(Map<String, Object> stdout) {
        Object v = stdout != null ? stdout.get("taskCount") : null;
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static List<String> awaitTaskStdoutLines(TeeBoxClient client, String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 10000L;
        List<String> lines = client.getRunTaskStdoutLines(runId);
        while (lines.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            lines = client.getRunTaskStdoutLines(runId);
        }
        return lines;
    }

    private static boolean containsLine(List<String> lines, String needle) {
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            if (line != null && line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsScriptId(List<Object> scripts, String scriptId) {
        for (Object item : scripts) {
            if (item instanceof Map && scriptId.equals(((Map<?, ?>) item).get("scriptId"))) {
                return true;
            }
        }
        return false;
    }

    private static TestServer startServer() throws Exception {
        return startServer(false, null);
    }

    private static TestServer startServer(boolean webhookEnabled, String allowlist) throws Exception {
        File dataDir = Files.createTempDirectory("teebox-standalone-client-it").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        config.webhookEnabled = webhookEnabled;
        config.webhookUrlAllowlist = allowlist;
        config.webhookTimeoutMs = 2000;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return new TestServer(server, "http://127.0.0.1:" + server.getPort());
    }

    private static final class TestServer {
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
