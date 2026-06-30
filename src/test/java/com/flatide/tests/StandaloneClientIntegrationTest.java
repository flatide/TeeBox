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
import java.net.InetSocketAddress;
import java.nio.file.Files;
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
