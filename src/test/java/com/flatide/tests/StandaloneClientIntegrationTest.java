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
