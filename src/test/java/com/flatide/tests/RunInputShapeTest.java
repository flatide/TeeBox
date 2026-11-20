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
 * A run's input stays what the caller sent: a {@code null} stays a null and an integer stays an
 * integer — in the script, in the run's file and in what the API shows, before and after a
 * restart. The request's {@code null} used to reach the script as a value of no type and was left
 * out of the file, and a restart read every stored number back as a decimal ({@code 5} as
 * {@code 5.0}), which the next save then wrote down.
 */
public class RunInputShapeTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

    private static final String BODY =
        "{\"props\": {\"n\": 5, \"big\": 5000000000, \"ratio\": 0.5, \"nil\": null,"
        + " \"list\": [1, null], \"nested\": {\"inner\": null}}}";

    private static final String SCRIPT =
        "return {\n"
        + "    \"nilType\": TYPE_OF(_PROPS.nil),\n"
        + "    \"nilIsNull\": _PROPS.nil == null,\n"
        + "    \"hasNil\": HAS_KEY(_PROPS, \"nil\"),\n"
        + "    \"inList\": TYPE_OF(_PROPS.list.2),\n"
        + "    \"inObject\": _PROPS.nested.inner == null,\n"
        + "    \"n\": TO_STRING(_PROPS.n),\n"
        + "    \"big\": TO_STRING(_PROPS.big),\n"
        + "    \"ratio\": TO_STRING(_PROPS.ratio)\n"
        + "}\n";

    @Test
    public void anInputKeepsItsNullsAndItsIntegers() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-input-shape").toFile();
        String runId;

        TeeBoxServer server = start(dataDir, 24L * 3600_000L);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            TeeBoxClient client = new TeeBoxClient(baseUrl, null);
            client.registerScript("shape", "1", SCRIPT, "shape", Arrays.asList("test"), true);
            Map<String, Object> accepted = gson.fromJson(
                post(baseUrl + "/api/client/scripts/shape/runs", BODY, 202), mapType);
            runId = (String) accepted.get("runId");
            waitFor(baseUrl, runId, "\"status\": \"COMPLETED\"");

            Map<String, Object> expected = new LinkedHashMap<String, Object>();
            expected.put("nilType", "null");
            expected.put("nilIsNull", Boolean.TRUE);
            expected.put("hasNil", Boolean.TRUE);
            expected.put("inList", "null");
            expected.put("inObject", Boolean.TRUE);
            expected.put("n", "5");
            expected.put("big", "5000000000");
            expected.put("ratio", "0.5");
            Map<String, Object> detail = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId), mapType);
            Assert.assertEquals("what the script saw", expected, ((Map<?, ?>) detail.get("run")).get("resultData"));

            assertInput("the run's file", inputOf(text(runFile(dataDir, runId))));
            assertInput("the API", inputOf(get(baseUrl + "/api/admin/runs/" + runId)));
        } finally {
            server.stop();
        }

        // A restart reads the run back, and archives it at its first sweep.
        server = start(dataDir, 0L);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            waitFor(baseUrl, runId, "\"archived\": true");
            assertInput("the archived run's file", inputOf(text(runFile(dataDir, runId))));
            assertInput("the API after a restart", inputOf(get(baseUrl + "/api/admin/runs/" + runId)));
        } finally {
            server.stop();
        }
    }

    /** The input as it is written, with the layout taken out: numbers and nulls as they stand. */
    private static void assertInput(String where, String written) {
        Assert.assertEquals(where,
            "{\"n\":5,\"big\":5000000000,\"ratio\":0.5,\"nil\":null,\"list\":[1,null],\"nested\":{\"inner\":null}}",
            written);
    }

    /** The text of the {@code "properties"} object in a pretty-printed run, without whitespace. */
    private static String inputOf(String json) {
        int start = json.indexOf("\"properties\": {");
        Assert.assertTrue("no properties in:\n" + json, start >= 0);
        int open = json.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return json.substring(open, i + 1).replaceAll("\\s+", "");
            }
        }
        throw new AssertionError("unbalanced properties in:\n" + json);
    }

    private static TeeBoxServer start(File dataDir, long runRetentionMs) throws Exception {
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        config.runRetentionMs = runRetentionMs;
        config.maintenanceIntervalMs = 200;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return server;
    }

    private static File runFile(File dataDir, String runId) {
        return new File(new File(dataDir, "runs"), runId + ".json");
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }

    private void waitFor(String baseUrl, String runId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(baseUrl + "/api/admin/runs/" + runId);
            if (last.contains(expected)) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("run " + runId + " did not show " + expected + "; last:\n" + last);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        Assert.assertEquals(url, 200, conn.getResponseCode());
        try {
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String post(String url, String body, int expectedStatus) throws IOException {
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
        String response = input != null ? readAll(input) : "";
        conn.disconnect();
        Assert.assertEquals(response, expectedStatus, status);
        return response;
    }

    private static String readAll(InputStream input) throws IOException {
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
        }
    }
}
