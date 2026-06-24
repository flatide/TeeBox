package com.flatide.tests;

import com.flatide.teebox.StreamResultSupport;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.client.TeeBoxClient;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stream-result feature: a script returning {@code STREAM_FILE(path)} stores only a small descriptor,
 * and {@code GET /result-stream} streams the file bytes directly (no engine/heap materialization),
 * confined to the allowed roots.
 */
public class StreamResultTest {

    @Test
    public void describeRejectsPathsOutsideAllowedRoots() throws Exception {
        File root = Files.createTempDirectory("stream-root").toFile();
        File inside = new File(root, "ok.json");
        new FileOutputStream(inside).close();
        StreamResultSupport support = new StreamResultSupport(Arrays.asList(root));

        Map<String, Object> desc = support.describe(inside.getAbsolutePath(), null);
        Assert.assertTrue(StreamResultSupport.isStreamDescriptor(desc));
        Assert.assertEquals("application/json", desc.get("contentType"));
        Assert.assertNotNull(support.resolveForStreaming(desc));

        try {
            support.describe("/etc/hosts", null);
            Assert.fail("expected rejection of a path outside the allowed roots");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("outside the allowed stream roots"));
        }
    }

    @Test
    public void contentTypeIsSanitizedAgainstHeaderInjection() {
        // CRLF injection / empty / non-MIME -> fall back to the extension guess.
        Assert.assertEquals("application/json",
            StreamResultSupport.sanitizeContentType("evil\r\nX-Injected: 1", "f.json"));
        Assert.assertEquals("text/csv", StreamResultSupport.sanitizeContentType("", "f.csv"));
        Assert.assertEquals("text/plain", StreamResultSupport.sanitizeContentType("not a mime", "f.txt"));
        Assert.assertEquals("application/xml", StreamResultSupport.sanitizeContentType(null, "f.xml"));
        // valid types pass through
        Assert.assertEquals("application/json",
            StreamResultSupport.sanitizeContentType("application/json", "f"));
        Assert.assertEquals("text/csv; charset=utf-8",
            StreamResultSupport.sanitizeContentType("text/csv; charset=utf-8", "f"));
    }

    @Test
    public void summaryRedactsServerPath() throws Exception {
        File root = Files.createTempDirectory("stream-summary").toFile();
        File f = new File(root, "data.json");
        FileOutputStream fos = new FileOutputStream(f);
        fos.write("{}".getBytes("UTF-8"));
        fos.close();
        StreamResultSupport support = new StreamResultSupport(Arrays.asList(root));
        Map<String, Object> desc = support.describe(f.getAbsolutePath(), null);
        String summary = StreamResultSupport.summaryFor(desc);
        Assert.assertEquals("STREAM_FILE(application/json, 2 bytes)", summary);
        Assert.assertFalse("summary must not contain the server path", summary.contains(f.getAbsolutePath()));
    }

    @Test
    public void scriptStreamFileIsServedAsRawBytes() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-stream-it").toFile();
        // a payload large enough to matter, written under dataDir (the default allowed root)
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 50000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"i\":").append(i).append(",\"v\":\"xxxxxxxxxxxxxxxxxxxx\"}");
        }
        sb.append("]}");
        String payload = sb.toString();
        File bigFile = new File(dataDir, "big.json");
        FileOutputStream fos = new FileOutputStream(bigFile);
        fos.write(payload.getBytes("UTF-8"));
        fos.close();

        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            TeeBoxClient client = new TeeBoxClient(baseUrl);
            client.registerScript("streamer",
                "return STREAM_FILE(\"" + bigFile.getAbsolutePath().replace("\\", "\\\\") + "\", \"application/json\")\n",
                true);
            Map<String, Object> props = new LinkedHashMap<String, Object>();
            String runId = (String) client.submitRun("streamer", props).get("runId");
            client.waitForRunTerminal(runId, 30000L);

            // /result returns a redacted descriptor (no server path)
            Map<String, Object> result = client.getRunResult(runId);
            Assert.assertEquals(Boolean.TRUE, result.get("stream"));
            Map<?, ?> rd = (Map<?, ?>) result.get("resultData");
            Assert.assertEquals(Boolean.TRUE, rd.get("stream"));
            Assert.assertNull("server path must not be exposed", rd.get("path"));

            // /result-stream returns the exact file bytes
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long n = client.streamRunResult(runId, out);
            Assert.assertEquals(payload.getBytes("UTF-8").length, n);
            Assert.assertArrayEquals(payload.getBytes("UTF-8"), out.toByteArray());
        } finally {
            server.stop();
        }
    }
}
