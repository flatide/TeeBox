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
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An archived run still shows its input. Archiving moves the input properties out of memory and
 * keeps them in the run's file until purge; the run detail (admin API and admin UI) reads them
 * from there. They used to be erased at archive time, so an archived run could no longer be read
 * with what it had been given.
 */
public class ArchivedRunInputTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
    private final Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();

    @Test
    public void runDetailShowsTheInputOfAnArchivedRun() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-archived-input").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        config.runRetentionMs = 0;              // archive at the first sweep after the run ends
        config.maintenanceIntervalMs = 200;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getPort();
        try {
            TeeBoxClient client = new TeeBoxClient(baseUrl, null);
            client.registerScript("greet", "1", "return {\"greeting\": \"hello \" + _PROPS.name}\n",
                "greet", Arrays.asList("test"), true);
            Map<String, Object> props = new LinkedHashMap<String, Object>();
            props.put("name", "홍길동");   // a Korean name
            props.put("count", 5);
            String runId = (String) client.submitRun("greet", null, props).get("runId");

            Map<String, Object> run = waitForArchived(baseUrl, runId, 15000L);
            Assert.assertEquals("COMPLETED", run.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) run.get("properties");
            Assert.assertEquals("홍길동", input.get("name"));
            Assert.assertEquals(5.0, ((Number) input.get("count")).doubleValue(), 0.0);

            // A listing answers from memory, where an archived run no longer holds its input.
            List<Map<String, Object>> listed = gson.fromJson(
                get(baseUrl + "/api/admin/runs?q=" + runId), listType);
            Assert.assertEquals(1, listed.size());
            Assert.assertEquals(Boolean.TRUE, listed.get(0).get("archived"));
            Assert.assertTrue(((Map<?, ?>) listed.get(0).get("properties")).isEmpty());

            // The admin UI's run page shows the same input.
            String page = get(baseUrl + "/admin/runs/" + runId);
            Assert.assertTrue("the run page must show the archived input", page.contains("홍길동"));
            Assert.assertFalse(page.contains("No properties"));
            String fragment = get(baseUrl + "/admin/fragments/run-detail/" + runId);
            Assert.assertTrue(fragment.contains("홍길동"));

            // And it is in the run's file, which is where purge will remove it.
            String file = new String(Files.readAllBytes(
                new File(new File(dataDir, "runs"), runId + ".json").toPath()), "UTF-8");
            Assert.assertTrue(file.contains("\"archived\": true"));
            Assert.assertTrue(file.contains("홍길동"));
        } finally {
            server.stop();
        }
    }

    private Map<String, Object> waitForArchived(String baseUrl, String runId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> run = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> detail = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId), mapType);
            @SuppressWarnings("unchecked")
            Map<String, Object> current = (Map<String, Object>) detail.get("run");
            run = current;
            if (Boolean.TRUE.equals(run.get("archived"))) {
                return run;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " was not archived within " + timeoutMs + "ms; last=" + run);
        return null;
    }

    private String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        Assert.assertEquals(url, 200, conn.getResponseCode());
        InputStream input = conn.getInputStream();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, len);
            }
            return bytes.toString("UTF-8");
        } finally {
            input.close();
            conn.disconnect();
        }
    }
}
