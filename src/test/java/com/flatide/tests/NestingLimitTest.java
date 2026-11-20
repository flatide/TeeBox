package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunRequest;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;
import com.flatide.teebox.TeeBoxClient;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.ValueDepth;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values of a run nest at most 1000 levels deep — what the language's JSON_PARSE reads. One
 * level more is refused where the value enters: a result when its run ends, an input when it is
 * submitted, a run file when it is loaded. (The language does not bound it, and a value some
 * thousand levels deep could be saved and then neither shown nor archived, and could keep the
 * server from starting: see ValueDepth.)
 */
public class NestingLimitTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static File dataDir;
    private static TeeBoxServer server;
    private static TeeBoxClient client;
    private static String base;

    @BeforeClass
    public static void startServer() throws Exception {
        dataDir = Files.createTempDirectory("teebox-nesting-limit").toFile();
        written.add(dataDir);
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        server = new TeeBoxServer(config);
        server.start();
        base = "http://127.0.0.1:" + server.getPort();
        client = new TeeBoxClient(base, null);
    }

    @AfterClass
    public static void stopServer() throws IOException {
        if (server != null) {
            server.stop();
        }
        // Results at the bound are megabytes each: what this class wrote is not left behind.
        for (File directory : written) {
            deleteTree(directory);
        }
    }

    private static final List<File> written = new ArrayList<File>();

    private static void deleteTree(File root) throws IOException {
        if (root == null || !root.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            for (java.nio.file.Path path : walk.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void theBoundIsTheOneOfJsonParse() {
        Assert.assertEquals(1000, ValueDepth.MAX);
        Assert.assertEquals(com.flatide.propertee2.value.JsonParser.MAX_DEPTH, ValueDepth.MAX);
    }

    // ===================== a result =====================

    @Test
    public void aResultAThousandLevelsDeepIsARunsResult() throws Exception {
        client.registerScript("deep_1000", "1", nesting(27, 37) + "return x\n", "999 wraps of {}", Arrays.asList("test"), true);
        Map<String, Object> run = runToItsEnd("deep_1000", new LinkedHashMap<String, Object>());
        Assert.assertEquals(String.valueOf(run.get("errorMessage")), "COMPLETED", run.get("status"));
        Assert.assertEquals(1000, levelsOf(run.get("resultData")));
        // And it is a run like any other afterwards: read from its file, written again, archived.
        // (From a copy of its file: a second registry over the directory of the server that is
        // running would go about recovering that server's runs.)
        String runId = (String) run.get("runId");
        File elsewhere = Files.createTempDirectory("teebox-nesting-limit-reload").toFile();
        written.add(elsewhere);
        Assert.assertTrue(new File(elsewhere, "runs").mkdirs());
        Files.copy(new File(dataDir, "runs/" + runId + ".json").toPath(),
                new File(elsewhere, "runs/" + runId + ".json").toPath());
        RunRegistry reloaded = new RunRegistry(elsewhere, 200, 50, 20, 0L, 7 * DAY_MS);
        Assert.assertEquals(RunStatus.COMPLETED, reloaded.getRun(runId).status);
        Assert.assertEquals(1000, levelsOf(reloaded.getRun(runId).resultData));
        reloaded.maintainRuns();
        Assert.assertTrue(reloaded.getRawRun(runId).archived);
    }

    @Test
    public void aResultOneLevelDeeperFailsTheRun() throws Exception {
        client.registerScript("deep_1001", "1", nesting(25, 40) + "return x\n", "1000 wraps of {}", Arrays.asList("test"), true);
        Map<String, Object> run = runToItsEnd("deep_1001", new LinkedHashMap<String, Object>());
        Assert.assertEquals("FAILED", run.get("status"));
        Assert.assertEquals("The result is nested more than 1000 levels deep", run.get("errorMessage"));
        Assert.assertNull(run.get("resultData"));

        // The same of a result that is left in the variable of that name.
        client.registerScript("deep_1001_variable", "1", nesting(25, 40) + "result = x\n", "result variable", Arrays.asList("test"), true);
        run = runToItsEnd("deep_1001_variable", new LinkedHashMap<String, Object>());
        Assert.assertEquals("FAILED", run.get("status"));
        Assert.assertEquals("The result is nested more than 1000 levels deep", run.get("errorMessage"));

        File file = new File(dataDir, "runs/" + run.get("runId") + ".json");
        Assert.assertTrue("its file is a small one: " + file.length(), file.length() < 100_000L);
    }

    /** A run that fails keeps the value its on error handler returned — unless that is too deep. */
    @Test
    public void aTooDeepValueOfTheOnErrorHandlerIsNotKept() throws Exception {
        String handler = "on error do\n    return x\nend\n";
        client.registerScript("handler_1000", "1", handler + nesting(27, 37) + "FAIL(\"as planned\")\n",
                "handler, 1000 deep", Arrays.asList("test"), true);
        Map<String, Object> run = runToItsEnd("handler_1000", new LinkedHashMap<String, Object>());
        Assert.assertEquals("FAILED", run.get("status"));
        Assert.assertTrue(String.valueOf(run.get("errorMessage")), String.valueOf(run.get("errorMessage")).contains("as planned"));
        Assert.assertFalse(String.valueOf(run.get("errorMessage")), String.valueOf(run.get("errorMessage")).contains("not kept"));
        Assert.assertEquals(1000, levelsOf(run.get("resultData")));

        client.registerScript("handler_1001", "1", handler + nesting(25, 40) + "FAIL(\"as planned\")\n",
                "handler, 1001 deep", Arrays.asList("test"), true);
        run = runToItsEnd("handler_1001", new LinkedHashMap<String, Object>());
        Assert.assertEquals("FAILED", run.get("status"));
        String message = String.valueOf(run.get("errorMessage"));
        Assert.assertTrue(message, message.contains("as planned"));
        Assert.assertTrue(message, message.contains("the value the 'on error' handler returned is nested more than 1000 levels deep, and was not kept"));
        Assert.assertNull(run.get("resultData"));
    }

    // ===================== an input =====================

    @Test
    public void anInputAThousandLevelsDeepIsGivenToTheRun() throws Exception {
        client.registerScript("echo_input", "1", "return _PROPS\n", "returns its input", Arrays.asList("test"), true);
        RunRequest request = new RunRequest();
        request.scriptId = "echo_input";
        request.props = nested(1000);   // the input itself is the first level
        RunInfo submitted = server.getRunManager().submit(request);
        Map<String, Object> run = ended(submitted.runId);
        Assert.assertEquals(String.valueOf(run.get("errorMessage")), "COMPLETED", run.get("status"));
        Assert.assertEquals(1000, levelsOf(run.get("resultData")));
    }

    @Test
    public void anInputOneLevelDeeperIsRefused() throws Exception {
        client.registerScript("echo_input_2", "1", "return _PROPS\n", "returns its input", Arrays.asList("test"), true);
        int runsBefore = server.getRunManager().listRuns(null, 0, 1000).size();
        RunRequest request = new RunRequest();
        request.scriptId = "echo_input_2";
        request.props = nested(1001);
        try {
            server.getRunManager().submit(request);
            Assert.fail("an input 1001 levels deep was taken");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("props is nested more than 1000 levels deep", expected.getMessage());
        }

        // Over HTTP: a request that is wrong, answered as one — one level too deep, or far too deep.
        for (int levels : new int[] {1001, 200_000}) {
            HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/client/scripts/echo_input_2/runs").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Connection", "close");
            connection.setReadTimeout(20_000);   // a request that gets no answer fails here, and does not wait for good
            connection.getOutputStream().write(("{\"props\": " + objects(levels) + "}").getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().close();
            Assert.assertEquals(levels + " levels", 400, connection.getResponseCode());
            InputStream body = connection.getErrorStream();
            String said = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            body.close();
            Assert.assertTrue(said, said.contains("props is nested more than 1000 levels deep"));
        }

        // The form of the admin UI, likewise — far too deep too.
        for (int levels : new int[] {1001, 200_000}) {
            HttpURLConnection form = (HttpURLConnection) new URL(base + "/admin/submit").openConnection();
            form.setRequestMethod("POST");
            form.setDoOutput(true);
            form.setInstanceFollowRedirects(false);
            form.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            form.setRequestProperty("Connection", "close");
            form.setReadTimeout(20_000);
            form.getOutputStream().write(("scriptId=echo_input_2&propsJson="
                    + java.net.URLEncoder.encode(objects(levels), "UTF-8")).getBytes(StandardCharsets.UTF_8));
            form.getOutputStream().close();
            int status = form.getResponseCode();
            InputStream page = status >= 400 ? form.getErrorStream() : form.getInputStream();
            String shown = page != null ? new String(page.readAllBytes(), StandardCharsets.UTF_8) : "";
            if (page != null) {
                page.close();
            }
            Assert.assertTrue(levels + " levels: " + status + " " + shown, status == 400
                    && shown.contains("props is nested more than 1000 levels deep"));
        }

        // The props of a debug attempt from the script editor, likewise.
        try {
            server.getRunManager().prepareScriptDebug("echo_input_2", "1", null, nested(1001), 1000, false, "tester");
            Assert.fail("debug props 1001 levels deep were taken");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("props is nested more than 1000 levels deep", expected.getMessage());
        }
        Assert.assertEquals("no run was made of any of them", runsBefore, server.getRunManager().listRuns(null, 0, 1000).size());
    }

    // ===================== a file =====================

    @Test
    public void aRunFileIsARunUpToAThousandLevelsAndNoneBeyond() throws Exception {
        String[][] fields = {
            {"resultData", "a result"},
            {"properties", "an input"},
            {"published", "what was published"},
        };
        for (String[] field : fields) {
            File dir = Files.createTempDirectory("teebox-nesting-limit-files").toFile();
            written.add(dir);
            File runsDir = new File(dir, "runs");
            Assert.assertTrue(runsDir.mkdirs());
            write(new File(runsDir, "within.json"), "{\"runId\": \"within\", \"scriptId\": \"s\", \"status\": \"COMPLETED\","
                    + " \"createdAt\": 1, \"endedAt\": 2, \"" + field[0] + "\": " + objects(1000) + "}");
            File beyondFile = new File(runsDir, "beyond.json");
            write(beyondFile, "{\"runId\": \"beyond\", \"scriptId\": \"s\", \"status\": \"RUNNING\","
                    + " \"createdAt\": 1, \"" + field[0] + "\": " + objects(1001) + "}");
            String beyondAsItWas = text(beyondFile);

            RunRegistry registry = new RunRegistry(dir, 200, 50, 20, 0L, 7 * DAY_MS);
            Assert.assertNotNull(field[1] + " 1000 levels deep is a run's", registry.getRun("within"));
            Assert.assertNull(field[1] + " 1001 levels deep was taken for a run's", registry.getRun("beyond"));
            Assert.assertEquals("and its file is left as it is", beyondAsItWas, text(beyondFile));
            registry.maintainRuns();
            Assert.assertTrue("the run within the bound is archived like any other", registry.getRawRun("within").archived);
            Assert.assertEquals(Arrays.asList("beyond.json", "within.json"), namesIn(runsDir));

            RunStore store = new RunStore(dir);
            if ("properties".equals(field[0])) {
                Assert.assertEquals(1000, levelsOf(store.loadProperties("within")));
                try {
                    store.loadProperties("beyond");
                    Assert.fail("an input 1001 levels deep was read from a file");
                } catch (RunStore.RunFileUnreadableException expected) {
                    Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("nested more than 1000 levels deep"));
                }
            }
        }
    }

    // ===================== helpers =====================

    /** A script that leaves in {@code x} an object nested {@code outer * inner + 1} levels deep. */
    private static String nesting(int outer, int inner) {
        return "x = {}\n"
             + "i = 0\n"
             + "loop i < " + outer + " do\n"
             + "    j = 0\n"
             + "    loop j < " + inner + " do\n"
             + "        x = {\"a\": x}\n"
             + "        j = j + 1\n"
             + "    end\n"
             + "    i = i + 1\n"
             + "end\n";
    }

    private static Map<String, Object> runToItsEnd(String scriptId, Map<String, Object> props) throws Exception {
        return ended((String) client.submitRun(scriptId, null, props).get("runId"));
    }

    /** The run as the server holds it once it has ended, taken from the run manager (no HTTP: a deep result is big). */
    private static Map<String, Object> ended(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            RunInfo run = server.getRunManager().getRun(runId);
            if (run != null && run.status != RunStatus.QUEUED && run.status != RunStatus.PENDING
                    && run.status != RunStatus.RUNNING) {
                Map<String, Object> seen = new LinkedHashMap<String, Object>();
                seen.put("runId", run.runId);
                seen.put("status", run.status.name());
                seen.put("errorMessage", run.errorMessage);
                seen.put("resultData", run.resultData);
                return seen;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("run " + runId + " did not end");
    }

    /** How many objects and arrays deep a value goes, counted without going down by calls. */
    private static int levelsOf(Object value) {
        int levels = 0;
        List<Object> level = new ArrayList<Object>();
        level.add(value);
        while (true) {
            List<Object> next = new ArrayList<Object>();
            boolean container = false;
            for (Object each : level) {
                if (each instanceof Map) {
                    container = true;
                    next.addAll(((Map<?, ?>) each).values());
                } else if (each instanceof List) {
                    container = true;
                    next.addAll((List<?>) each);
                }
            }
            if (!container) {
                return levels;
            }
            levels++;
            level = next;
        }
    }

    /** An object nested {@code levels} levels deep (itself included). */
    private static Map<String, Object> nested(int levels) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        for (int level = 1; level < levels; level++) {
            Map<String, Object> outer = new LinkedHashMap<String, Object>();
            outer.put("a", value);
            value = outer;
        }
        return value;
    }

    /** JSON text of an object nested {@code levels} levels deep. */
    private static String objects(int levels) {
        StringBuilder text = new StringBuilder(levels * 7);
        for (int i = 1; i < levels; i++) {
            text.append("{\"a\":");
        }
        text.append("{}");
        for (int i = 1; i < levels; i++) {
            text.append('}');
        }
        return text.toString();
    }

    private static List<String> namesIn(File directory) {
        String[] names = directory.list();
        List<String> sorted = new ArrayList<String>(Arrays.asList(names != null ? names : new String[0]));
        java.util.Collections.sort(sorted);
        return sorted;
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
