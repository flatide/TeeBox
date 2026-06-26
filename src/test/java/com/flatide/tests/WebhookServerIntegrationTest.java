package com.flatide.tests;

import com.flatide.teebox.TeeBoxClient;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * End-to-end webhook test through the live {@code /api/client/scripts/{id}/runs} endpoint:
 * submit with a callback, then assert the local receiver actually got the terminal POST.
 * Also covers submit-time rejection (webhooks disabled / host not on allowlist).
 */
public class WebhookServerIntegrationTest {

    private final Gson gson = new Gson();
    private HttpServer receiver;
    private int receiverPort;
    private final BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<Map<String, Object>>();
    private final AtomicReference<String> deliveryHeader = new AtomicReference<String>();

    @Before
    public void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/cb", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String body = readAll(exchange.getRequestBody());
                deliveryHeader.set(exchange.getRequestHeaders().getFirst("X-TeeBox-Delivery"));
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = gson.fromJson(body, Map.class);
                    received.add(parsed != null ? parsed : new LinkedHashMap<String, Object>());
                } catch (Exception e) {
                    received.add(new LinkedHashMap<String, Object>());
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        receiver.start();
        receiverPort = receiver.getAddress().getPort();
    }

    @After
    public void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    private TeeBoxServer startServer(boolean webhookEnabled, String allowlist) throws Exception {
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = Files.createTempDirectory("teebox-webhook-it").toFile();
        config.maxConcurrentRuns = 4;
        config.webhookEnabled = webhookEnabled;
        config.webhookUrlAllowlist = allowlist;
        config.webhookTimeoutMs = 2000;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return server;
    }

    @Test
    public void deliversWebhookOnTerminal() throws Exception {
        TeeBoxServer server = startServer(true, "127.0.0.1");
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            new TeeBoxClient(baseUrl, null).registerScript("wh_demo", "return {\"ok\": true}\n", true);

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("props", new LinkedHashMap<String, Object>());
            Map<String, Object> callback = new LinkedHashMap<String, Object>();
            callback.put("url", "http://127.0.0.1:" + receiverPort + "/cb");
            body.put("callback", callback);

            RawResponse submit = post(baseUrl + "/api/client/scripts/wh_demo/runs", body);
            assertEquals(202, submit.status);
            String runId = String.valueOf(submit.body.get("runId"));

            Map<String, Object> payload = received.poll(10, TimeUnit.SECONDS);
            assertNotNull("receiver never got a webhook", payload);
            assertEquals(runId, payload.get("runId"));
            assertEquals("wh_demo", payload.get("scriptId"));
            assertEquals("COMPLETED", payload.get("status"));
            assertEquals("run.terminal", payload.get("event"));
            assertEquals(runId, deliveryHeader.get());
        } finally {
            server.stop();
        }
    }

    @Test
    public void rejectsCallbackWhenWebhooksDisabled() throws Exception {
        TeeBoxServer server = startServer(false, null);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            new TeeBoxClient(baseUrl, null).registerScript("wh_off", "return {\"ok\": true}\n", true);
            RawResponse submit = post(baseUrl + "/api/client/scripts/wh_off/runs",
                callbackBody("http://127.0.0.1:" + receiverPort + "/cb"));
            assertEquals(400, submit.status);
        } finally {
            server.stop();
        }
    }

    @Test
    public void rejectsCallbackHostNotOnAllowlist() throws Exception {
        TeeBoxServer server = startServer(true, "allowed.internal");
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            new TeeBoxClient(baseUrl, null).registerScript("wh_deny", "return {\"ok\": true}\n", true);
            RawResponse submit = post(baseUrl + "/api/client/scripts/wh_deny/runs",
                callbackBody("http://127.0.0.1:" + receiverPort + "/cb"));
            assertEquals(400, submit.status);
            assertNull(received.poll(1, TimeUnit.SECONDS));
        } finally {
            server.stop();
        }
    }

    private Map<String, Object> callbackBody(String url) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("props", new LinkedHashMap<String, Object>());
        Map<String, Object> callback = new LinkedHashMap<String, Object>();
        callback.put("url", url);
        body.put("callback", callback);
        return body;
    }

    private static class RawResponse {
        final int status;
        final Map<String, Object> body;
        RawResponse(int status, Map<String, Object> body) {
            this.status = status;
            this.body = body;
        }
    }

    @SuppressWarnings("unchecked")
    private RawResponse post(String url, Map<String, Object> payload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(gson.toJson(payload).getBytes("UTF-8"));
        } finally {
            out.close();
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = in != null ? readAll(in) : "";
        Map<String, Object> map;
        try {
            map = gson.fromJson(body, Map.class);
        } catch (Exception e) {
            map = new LinkedHashMap<String, Object>();
        }
        return new RawResponse(status, map != null ? map : new LinkedHashMap<String, Object>());
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int len;
        while ((len = in.read(chunk)) != -1) {
            buf.write(chunk, 0, len);
        }
        return new String(buf.toByteArray(), Charset.forName("UTF-8"));
    }
}
