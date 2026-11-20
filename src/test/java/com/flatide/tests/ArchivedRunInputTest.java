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
        TeeBoxServer server = startArchivingServer(dataDir);
        String baseUrl = "http://127.0.0.1:" + server.getPort();
        try {
            String runId = submitGreeting(baseUrl);

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

    /**
     * The run page's refresh leaves the input alone. The input cannot change, and for an archived
     * run showing it means reading and parsing the run's whole file: every open tab used to do
     * that every five seconds, under the lock that every run save needs.
     */
    @Test
    public void theRefreshOfARunPageDoesNotReadTheInputAgain() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-archived-refresh").toFile();
        TeeBoxServer server = startArchivingServer(dataDir);
        String baseUrl = "http://127.0.0.1:" + server.getPort();
        try {
            String runId = submitGreeting(baseUrl);
            waitForArchived(baseUrl, runId, 15000L);

            String page = get(baseUrl + "/admin/runs/" + runId);
            Assert.assertTrue("the page itself shows the input", page.contains("홍길동"));
            Assert.assertTrue("the card that holds the input is marked", page.contains("<div class='card' id='run-input' data-loaded='1'>"));
            Assert.assertTrue("the refresh asks for the detail without the input while it has that card",
                page.contains("fetchFragment('/admin/fragments/run-detail/" + runId + "'+(keep?'?input=keep':''),"));
            Assert.assertTrue("and puts the card back",
                page.contains("if(keep&&card){card.innerHTML=input;card.setAttribute('data-loaded','1');}"));

            String refreshed = get(baseUrl + "/admin/fragments/run-detail/" + runId + "?input=keep");
            Assert.assertTrue("the card is there to be filled", refreshed.contains("<div class='card' id='run-input'></div>"));
            // (the name itself is also in the run's result; the key is the input's alone)
            Assert.assertTrue(get(baseUrl + "/admin/fragments/run-detail/" + runId).contains("&quot;name&quot;"));
            Assert.assertFalse(refreshed.contains("&quot;name&quot;"));
            Assert.assertFalse(refreshed.contains("Input Properties"));
            Assert.assertTrue("the rest of the detail is there", refreshed.contains("Script Output"));

            // It reads no file: with the run's file gone the refresh answers as before.
            File file = new File(new File(dataDir, "runs"), runId + ".json");
            File aside = new File(dataDir, runId + ".json.aside");
            Assert.assertTrue(file.renameTo(aside));
            try {
                Assert.assertEquals(refreshed, get(baseUrl + "/admin/fragments/run-detail/" + runId + "?input=keep"));
            } finally {
                Assert.assertTrue(aside.renameTo(file));
            }
        } finally {
            server.stop();
        }
    }

    /**
     * An input that could not be read is not an empty input. The detail used to show "No
     * properties", and the API an empty {@code properties}, whenever the run's file could not be
     * read. (Made unreadable by taking the permission away: POSIX, and a user other than root.)
     */
    @Test
    public void anInputThatCannotBeReadIsNotShownAsEmpty() throws Exception {
        org.junit.Assume.assumeFalse("needs POSIX permission semantics",
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        org.junit.Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
        File dataDir = Files.createTempDirectory("propertee-teebox-archived-unreadable").toFile();
        TeeBoxServer server = startArchivingServer(dataDir);
        String baseUrl = "http://127.0.0.1:" + server.getPort();
        try {
            String runId = submitGreeting(baseUrl);
            waitForArchived(baseUrl, runId, 15000L);
            File file = new File(new File(dataDir, "runs"), runId + ".json");

            Assert.assertTrue(file.setReadable(false));
            try {
                Map<String, Object> detail = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId), mapType);
                Assert.assertEquals("The input could not be read from the run's file", detail.get("inputError"));
                Map<?, ?> run = (Map<?, ?>) detail.get("run");
                Assert.assertEquals("the rest of the run is answered from memory", "COMPLETED", run.get("status"));

                String fragment = get(baseUrl + "/admin/fragments/run-detail/" + runId);
                Assert.assertTrue(fragment.contains("The input could not be read from the run's file"));
                Assert.assertFalse(fragment.contains("No properties"));
                Assert.assertTrue("a card without the input is not one the refresh keeps",
                    fragment.contains("<div class='card' id='run-input'>"));
            } finally {
                file.setReadable(true);
            }

            Map<String, Object> detail = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId), mapType);
            Assert.assertFalse(detail.containsKey("inputError"));
            Assert.assertEquals("홍길동", ((Map<?, ?>) ((Map<?, ?>) detail.get("run")).get("properties")).get("name"));
        } finally {
            server.stop();
        }
    }

    /**
     * A run whose file is gone has an unknown input as well, not an empty one. The detail used to
     * show "No properties" in a card marked as holding the input, which the page's refresh then
     * kept: the input did not show even after the file had been put back.
     */
    @Test
    public void anInputWhoseFileIsGoneIsNotShownAsEmptyAndShowsOnceTheFileIsBack() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-archived-file-gone").toFile();
        TeeBoxServer server = startArchivingServer(dataDir);
        String baseUrl = "http://127.0.0.1:" + server.getPort();
        try {
            String runId = submitGreeting(baseUrl);
            waitForArchived(baseUrl, runId, 15000L);
            File file = new File(new File(dataDir, "runs"), runId + ".json");
            File aside = new File(dataDir, runId + ".json.aside");

            Assert.assertTrue(file.renameTo(aside));
            try {
                Map<String, Object> detail = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId), mapType);
                Assert.assertEquals("The input could not be read from the run's file", detail.get("inputError"));
                Assert.assertEquals("the rest of the run is answered from memory",
                    "COMPLETED", ((Map<?, ?>) detail.get("run")).get("status"));

                String fragment = get(baseUrl + "/admin/fragments/run-detail/" + runId);
                Assert.assertTrue(fragment.contains("The input could not be read from the run's file"));
                Assert.assertFalse(fragment.contains("No properties"));
                Assert.assertTrue("a card without the input is not one the refresh keeps",
                    fragment.contains("<div class='card' id='run-input'>"));
            } finally {
                Assert.assertTrue(aside.renameTo(file));
            }

            Map<String, Object> detail = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId), mapType);
            Assert.assertFalse(detail.containsKey("inputError"));
            Assert.assertEquals("홍길동", ((Map<?, ?>) ((Map<?, ?>) detail.get("run")).get("properties")).get("name"));
            String fragment = get(baseUrl + "/admin/fragments/run-detail/" + runId);
            Assert.assertTrue("the input shows once the file is back", fragment.contains("&quot;name&quot;"));
            Assert.assertTrue(fragment.contains("<div class='card' id='run-input' data-loaded='1'>"));
        } finally {
            server.stop();
        }
    }

    private TeeBoxServer startArchivingServer(File dataDir) throws Exception {
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        config.runRetentionMs = 0;              // archive at the first sweep after the run ends
        config.maintenanceIntervalMs = 200;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return server;
    }

    private String submitGreeting(String baseUrl) throws Exception {
        TeeBoxClient client = new TeeBoxClient(baseUrl, null);
        client.registerScript("greet", "1", "return {\"greeting\": \"hello \" + _PROPS.name}\n",
            "greet", Arrays.asList("test"), true);
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("name", "홍길동");   // a Korean name
        props.put("count", 5);
        return (String) client.submitRun("greet", null, props).get("runId");
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
