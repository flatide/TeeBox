package com.flatide.teebox;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Assert;
import org.junit.Test;

/**
 * The server access-logs each API request/response on the {@code access} logger. Covers the line
 * format directly, and asserts a line is actually emitted for a live {@code /api} request.
 */
public class AccessLogTest {

    @Test
    public void accessLogLineFormatsRequestAndResponse() {
        Assert.assertEquals(
                "GET /api/client/runs/x?limit=10 from 127.0.0.1 -> 200 (4ms)",
                TeeBoxServer.accessLogLine("GET", "/api/client/runs/x?limit=10", "127.0.0.1", 200, 4L));
        // A handler that threw before sending headers has no status code yet.
        Assert.assertEquals(
                "POST /api/client/scripts/s/runs from 10.0.0.9 -> no-response (2ms)",
                TeeBoxServer.accessLogLine("POST", "/api/client/scripts/s/runs", "10.0.0.9", -1, 2L));
    }

    @Test
    public void emitsAccessLogLineForApiRequest() throws Exception {
        // Capture the "access" logger by attaching an appender to the root config (access has no
        // dedicated LoggerConfig, so it logs through root); filter to access events in the appender.
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        LoggerConfig root = cfg.getRootLogger();
        root.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();

        File dataDir = Files.createTempDirectory("teebox-accesslog-it").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getPort();
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/client/runs").openConnection();
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            drain(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            Assert.assertEquals(200, code);

            // The access line is emitted in the handler's finally, which may land just after the client
            // reads the response — poll briefly.
            String match = awaitLine(appender, "GET /api/client/runs", "-> 200");
            Assert.assertNotNull("expected an access line for GET /api/client/runs -> 200, got: " + appender.lines(), match);
            Assert.assertTrue("line should carry elapsed ms: " + match, match.contains("ms)"));
        } finally {
            server.stop();
            root.removeAppender("capture-access");
            ctx.updateLoggers();
            appender.stop();
        }
    }

    @Test
    public void adminUiContextIsNotAccessLogged() throws Exception {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        LoggerConfig root = cfg.getRootLogger();
        root.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();

        File dataDir = Files.createTempDirectory("teebox-accesslog-admin-it").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getPort();
            // Hit the /admin operator UI — its exact response is irrelevant; only that it is NOT logged.
            try {
                HttpURLConnection admin = (HttpURLConnection) new URL(base + "/admin").openConnection();
                admin.setRequestMethod("GET");
                admin.setInstanceFollowRedirects(false);
                int code = admin.getResponseCode();
                drain(code < 400 ? admin.getInputStream() : admin.getErrorStream());
            } catch (java.io.IOException ignore) {
                // the admin UI's own behavior is not what this test asserts
            }
            // ...and an /api request as a positive control (still access-logged).
            HttpURLConnection api = (HttpURLConnection) new URL(base + "/api/client/runs").openConnection();
            api.setRequestMethod("GET");
            int apiCode = api.getResponseCode();
            drain(apiCode < 400 ? api.getInputStream() : api.getErrorStream());

            String apiLine = awaitLine(appender, "GET /api/client/runs", "->");
            Assert.assertNotNull("an /api request should still be access-logged: " + appender.lines(), apiLine);
            for (String line : appender.lines()) {
                Assert.assertFalse("the /admin context must not be access-logged: " + line, line.contains(" /admin"));
            }
        } finally {
            server.stop();
            root.removeAppender("capture-access");
            ctx.updateLoggers();
            appender.stop();
        }
    }

    private static String awaitLine(CapturingAppender appender, String needleA, String needleB) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            for (String line : appender.lines()) {
                if (line.contains(needleA) && line.contains(needleB)) {
                    return line;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static void drain(InputStream in) throws java.io.IOException {
        if (in == null) {
            return;
        }
        byte[] buf = new byte[2048];
        while (in.read(buf) != -1) {
            // discard
        }
        in.close();
    }

    /** In-memory appender collecting formatted messages from the {@code access} logger only. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        CapturingAppender() {
            super("capture-access", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if ("access".equals(event.getLoggerName())) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> lines() {
            return new ArrayList<String>(lines);
        }
    }
}
