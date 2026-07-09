package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.TeeBoxClient;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.TeeBoxConfig;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TeeBoxServerTest {
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
    private final Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();

    @Test
    public void serverShouldExposeRunThreadsAndTasks() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("multi_tasks", "v1",
                "function worker(name) do\n" +
                "    return SHELL(\"" + testServer.script("sleep_echo") + " \" + name)\n" +
                "end\n\n" +
                "multi result do\n" +
                "    thread alpha: worker(\"alpha\")\n" +
                "    thread beta: worker(\"beta\")\n" +
                "end\n\n" +
                "PRINT(result.alpha.ok)\n" +
                "PRINT(result.beta.ok)\n",
                "multi tasks test", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("multi_tasks", null, new LinkedHashMap<String, Object>()).get("runId");
            Assert.assertNotNull(runId);

            Map<String, Object> detail = waitForRunWithTasks(testServer.baseUrl, runId, 2, 3, 8000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> threads = (List<Map<String, Object>>) detail.get("threads");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");

            Assert.assertTrue(hasThreadName(threads, "main"));
            Assert.assertTrue(hasThreadResultKey(threads, "alpha"));
            Assert.assertTrue(hasThreadResultKey(threads, "beta"));
            Assert.assertEquals(2, tasks.size());

            Map<String, Object> completed = waitForRunStatus(testServer.baseUrl, runId, "COMPLETED", 10000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> completedTasks = (List<Map<String, Object>>) completed.get("tasks");
            Assert.assertEquals(2, completedTasks.size());
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldAllowKillingTaskFromAdminApi() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("kill_task", "v1",
                "result = SHELL(\"" + testServer.script("sleep30") + "\")\n" +
                "PRINT(result.ok)\n",
                "kill task test", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("kill_task", null, new LinkedHashMap<String, Object>()).get("runId");
            Assert.assertNotNull(runId);

            Map<String, Object> detail = waitForRunWithTasks(testServer.baseUrl, runId, 1, 1, 8000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
            String taskId = (String) tasks.get(0).get("taskId");

            Map<String, Object> killResult = postJson(testServer.baseUrl + "/api/admin/tasks/" + taskId + "/kill", new LinkedHashMap<String, Object>(), 200);
            Assert.assertEquals(Boolean.TRUE, killResult.get("killed"));

            Map<String, Object> taskDetail = waitForTaskStatus(testServer.baseUrl, taskId, "killed", 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> taskInfo = (Map<String, Object>) taskDetail.get("task");
            Assert.assertEquals("killed", taskInfo.get("status"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldKeepKilledStatusAfterRepeatedKillRequests() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("kill_task_repeat", "v1",
                "result = SHELL(\"" + testServer.script("sleep30") + "\")\n" +
                "PRINT(result.ok)\n",
                "repeat kill task test", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("kill_task_repeat", null, new LinkedHashMap<String, Object>()).get("runId");
            Assert.assertNotNull(runId);

            Map<String, Object> detail = waitForRunWithTasks(testServer.baseUrl, runId, 1, 1, 8000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
            String taskId = (String) tasks.get(0).get("taskId");

            Map<String, Object> firstKillResult = postJson(testServer.baseUrl + "/api/admin/tasks/" + taskId + "/kill", new LinkedHashMap<String, Object>(), 200);
            Assert.assertEquals(Boolean.TRUE, firstKillResult.get("killed"));

            Map<String, Object> secondKillResult = postJson(testServer.baseUrl + "/api/admin/tasks/" + taskId + "/kill", new LinkedHashMap<String, Object>(), 200);
            Assert.assertEquals(Boolean.TRUE, secondKillResult.get("killed"));

            Map<String, Object> taskDetail = waitForTaskStatus(testServer.baseUrl, taskId, "killed", 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> taskInfo = (Map<String, Object>) taskDetail.get("task");
            Assert.assertEquals("killed", taskInfo.get("status"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldExposeStructuredResultContract() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("return_result", "v1",
                "return {\"ok\": true, \"value\": 42}\n",
                "return result test", Arrays.asList("test"), true);
            client.registerScript("variable_result", "v1",
                "value = 41\n" +
                "result = {\"ok\": true, \"value\": value + 1}\n",
                "variable result test", Arrays.asList("test"), true);
            // No return and no `result` variable: ProperTee's "no implicit null" means the result is {}
            // (empty object), never null — consistent with `return` / `return {}`.
            client.registerScript("no_return", "v1", "PRINT(\"test\")\n",
                "no-return result test", Arrays.asList("test"), true);

            String returnRunId = (String) client.submitRun("return_result", null, new LinkedHashMap<String, Object>()).get("runId");
            String variableRunId = (String) client.submitRun("variable_result", null, new LinkedHashMap<String, Object>()).get("runId");
            String noReturnRunId = (String) client.submitRun("no_return", null, new LinkedHashMap<String, Object>()).get("runId");

            Map<String, Object> returnDetail = waitForRunStatus(testServer.baseUrl, returnRunId, "COMPLETED", 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> returnRun = (Map<String, Object>) returnDetail.get("run");
            Assert.assertEquals(Boolean.TRUE, returnRun.get("hasExplicitReturn"));
            @SuppressWarnings("unchecked")
            Map<String, Object> returnData = (Map<String, Object>) returnRun.get("resultData");
            Assert.assertEquals(Boolean.TRUE, returnData.get("ok"));
            Assert.assertEquals(42.0, ((Number) returnData.get("value")).doubleValue(), 0.0);

            Map<String, Object> variableDetail = waitForRunStatus(testServer.baseUrl, variableRunId, "COMPLETED", 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> variableRun = (Map<String, Object>) variableDetail.get("run");
            Assert.assertEquals(Boolean.FALSE, variableRun.get("hasExplicitReturn"));
            @SuppressWarnings("unchecked")
            Map<String, Object> variableData = (Map<String, Object>) variableRun.get("resultData");
            Assert.assertEquals(Boolean.TRUE, variableData.get("ok"));
            Assert.assertEquals(42.0, ((Number) variableData.get("value")).doubleValue(), 0.0);

            Map<String, Object> noReturnDetail = waitForRunStatus(testServer.baseUrl, noReturnRunId, "COMPLETED", 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> noReturnRun = (Map<String, Object>) noReturnDetail.get("run");
            Assert.assertEquals(Boolean.FALSE, noReturnRun.get("hasExplicitReturn"));
            Object noReturnData = noReturnRun.get("resultData");
            Assert.assertNotNull("no-return result must be {} (no implicit null), not null", noReturnData);
            Assert.assertTrue("no-return result is an empty object", noReturnData instanceof Map);
            Assert.assertTrue("no-return result is empty", ((Map<?, ?>) noReturnData).isEmpty());
        } finally {
            testServer.close();
        }
    }

    @Test
    public void firstClassNullInResultSerializesAsJsonNullNotEmptyObject() throws Exception {
        // ProperTee's first-class null (spec v0.8.0) is null != {}. The engine's JsonNull singleton must
        // reach API consumers as JSON null, not {} (which means "absence") — a lossless-round-trip fix at
        // the host serialization boundary (JsonNullGsonAdapter), not in the language.
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("null_in_data", "v1", "return {\"coupon\": null, \"n\": 1}\n",
                "nested null", Arrays.asList("test"), true);
            client.registerScript("null_top", "v1", "return null\n",
                "top-level null", Arrays.asList("test"), true);

            String dataRunId = (String) client.submitRun("null_in_data", null, new LinkedHashMap<String, Object>()).get("runId");
            String topRunId = (String) client.submitRun("null_top", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(testServer.baseUrl, dataRunId, "COMPLETED", 8000L);
            waitForRunStatus(testServer.baseUrl, topRunId, "COMPLETED", 8000L);

            // Assert on the raw JSON so {} vs null is unambiguous (a parser would collapse both to a map/absent).
            String dataJson = getHtml(testServer.baseUrl + "/api/client/runs/" + dataRunId + "/result", 200);
            Assert.assertTrue("nested null preserved as JSON null, not {}\n" + dataJson,
                dataJson.contains("\"coupon\": null"));
            Assert.assertFalse("null must not become {}", dataJson.contains("\"coupon\": {}"));

            String topJson = getHtml(testServer.baseUrl + "/api/client/runs/" + topRunId + "/result", 200);
            Assert.assertTrue("top-level return null is JSON null\n" + topJson,
                topJson.contains("\"resultData\": null"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void resultDataSurvivesAServerRestartByteFaithfully() throws Exception {
        // The disk round-trip half of the first-class-null fix: RunStore persists the engine's null as
        // JSON null and reconstructs engine value shapes on load — without this, a restart turned
        // "coupon": null into a dropped key and "n": 1 into 1.0 (Gson's generic Object mapping).
        TestServer testServer = createServer();
        TeeBoxServer restarted = null;
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("restart_null", "v1", "return {\"coupon\": null, \"n\": 1}\n",
                "restart round-trip", Arrays.asList("test"), true);
            String runId = (String) client.submitRun("restart_null", null, new LinkedHashMap<String, Object>()).get("runId");
            waitForRunStatus(testServer.baseUrl, runId, "COMPLETED", 8000L);
            String before = getHtml(testServer.baseUrl + "/api/client/runs/" + runId + "/result", 200);

            testServer.server.stop();
            TeeBoxConfig config = new TeeBoxConfig();
            config.bindAddress = "127.0.0.1";
            config.port = 0;
            config.dataDir = testServer.dataDir;   // same data — a real restart
            config.maxConcurrentRuns = 2;
            restarted = new TeeBoxServer(config);
            restarted.start();
            String base = "http://127.0.0.1:" + restarted.getPort();

            String after = getHtml(base + "/api/client/runs/" + runId + "/result", 200);
            Assert.assertTrue("null survives the restart\n" + after, after.contains("\"coupon\": null"));
            Assert.assertFalse("null must not collapse to {} after reload", after.contains("\"coupon\": {}"));
            Assert.assertTrue("integers keep their shape (not 1.0)\n" + after, after.contains("\"n\": 1\n")
                || after.contains("\"n\": 1,"));
            Assert.assertFalse("no Double corruption on reload", after.contains("\"n\": 1.0"));
            Assert.assertEquals("the served result JSON is byte-identical across the restart", before, after);
        } finally {
            if (restarted != null) {
                restarted.stop();
            }
            testServer.close();
        }
    }

    @Test
    public void runsListShouldFilterByInstantAndSearch() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            // One normal script, one instant (immediate=true) script.
            client.registerScript("normal_calc", "v1", "return {\"n\": 1}\n",
                "normal", Arrays.asList("test"), true);
            client.registerScript("instant_ping", "v1", "return {\"pong\": true}\n",
                "instant", Arrays.asList("test"), true);
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            settings.put("maxConcurrentRuns", Double.valueOf(0));
            settings.put("immediate", Boolean.TRUE);
            assertStatus(testServer.baseUrl + "/api/publisher/scripts/instant_ping/settings", "PUT", settings, null, 200);

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("props", new LinkedHashMap<String, Object>());
            String normalRun = (String) postJson(testServer.baseUrl + "/api/client/scripts/normal_calc/runs", payload, 202).get("runId");
            String instantRun = (String) postJson(testServer.baseUrl + "/api/client/scripts/instant_ping/runs", payload, 202).get("runId");
            waitForRunStatus(testServer.baseUrl, normalRun, "COMPLETED", 8000L);
            waitForRunStatus(testServer.baseUrl, instantRun, "COMPLETED", 8000L);

            // Admin API: no param = all (backward compatible); instant=exclude / instant=only filter.
            List<Map<String, Object>> all = getJsonList(testServer.baseUrl + "/api/admin/runs", 200);
            Assert.assertEquals(2, all.size());
            List<Map<String, Object>> excl = getJsonList(testServer.baseUrl + "/api/admin/runs?instant=exclude", 200);
            Assert.assertEquals(1, excl.size());
            Assert.assertEquals(normalRun, excl.get(0).get("runId"));
            Assert.assertEquals(Boolean.FALSE, excl.get(0).get("immediate"));
            List<Map<String, Object>> only = getJsonList(testServer.baseUrl + "/api/admin/runs?instant=only", 200);
            Assert.assertEquals(1, only.size());
            Assert.assertEquals(instantRun, only.get(0).get("runId"));
            Assert.assertEquals(Boolean.TRUE, only.get(0).get("immediate"));

            // Search: scriptId substring is case-insensitive; a full runId matches exactly one run.
            List<Map<String, Object>> byScript = getJsonList(testServer.baseUrl + "/api/admin/runs?q=NORMAL_", 200);
            Assert.assertEquals(1, byScript.size());
            Assert.assertEquals(normalRun, byScript.get(0).get("runId"));
            List<Map<String, Object>> byRunId = getJsonList(testServer.baseUrl + "/api/admin/runs?q=" + instantRun, 200);
            Assert.assertEquals(1, byRunId.size());
            Assert.assertEquals(instantRun, byRunId.get(0).get("runId"));
            // Filters combine: searching the instant script while excluding instant runs finds nothing.
            List<Map<String, Object>> none = getJsonList(testServer.baseUrl + "/api/admin/runs?instant=exclude&q=instant_ping", 200);
            Assert.assertEquals(0, none.size());

            // The UI fragment (the Runs page's data path) honors the same params, and instant rows carry a badge.
            String fragExcl = getHtml(testServer.baseUrl + "/admin/fragments/all-runs?instant=exclude", 200);
            Assert.assertTrue(fragExcl.contains(normalRun));
            Assert.assertFalse(fragExcl.contains(instantRun));
            String fragOnly = getHtml(testServer.baseUrl + "/admin/fragments/all-runs?instant=only", 200);
            Assert.assertTrue(fragOnly.contains(instantRun));
            Assert.assertFalse(fragOnly.contains(normalRun));
            Assert.assertTrue("instant badge on the row", fragOnly.contains(">instant</span>"));

            // The Runs page default view excludes instant runs ("Include instant" checkbox, unchecked).
            String page = getHtml(testServer.baseUrl + "/admin/runs", 200);
            Assert.assertTrue(page.contains("Include instant"));
            Assert.assertFalse("checkbox must default to unchecked",
                page.contains("id='instant-filter' checked"));
            Assert.assertTrue(page.contains(normalRun));
            Assert.assertFalse("default view hides instant runs", page.contains(instantRun));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void scriptsListShouldShowInstantTagForImmediateScripts() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("plain_calc", "v1", "return {\"n\": 1}\n",
                "normal", Arrays.asList("test"), true);
            client.registerScript("quick_ping", "v1", "return {\"pong\": true}\n",
                "instant", Arrays.asList("test"), true);
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            settings.put("maxConcurrentRuns", Double.valueOf(0));
            settings.put("immediate", Boolean.TRUE);
            assertStatus(testServer.baseUrl + "/api/publisher/scripts/quick_ping/settings", "PUT", settings, null, 200);

            // The Script ID cell carries the instant tag for immediate scripts only.
            String page = getHtml(testServer.baseUrl + "/admin/scripts", 200);
            String instantRow = tableRow(page, "quick_ping");
            Assert.assertTrue("instant tag inside the Script ID cell",
                instantRow.substring(0, instantRow.indexOf("</td>")).contains(">instant</span>"));
            String normalRow = tableRow(page, "plain_calc");
            Assert.assertFalse("no instant tag on a non-immediate script", normalRow.contains(">instant</span>"));
        } finally {
            testServer.close();
        }
    }

    /** The scripts-table row (up to its closing tag) whose script link contains the given id. */
    private static String tableRow(String page, String scriptId) {
        int start = page.indexOf("scripts/" + scriptId);
        Assert.assertTrue("row for " + scriptId + " present", start >= 0);
        int end = page.indexOf("</tr>", start);
        Assert.assertTrue(end > start);
        return page.substring(start, end);
    }

    @Test
    public void clientRunAndWaitShouldReturnResultSynchronously() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("sync_result", "v1",
                "return {\"ok\": true, \"value\": 42}\n",
                "runAndWait happy path", Arrays.asList("test"), true);

            Map<String, Object> result = client.runAndWait("sync_result", null,
                new LinkedHashMap<String, Object>(), 8000L);

            Assert.assertEquals("COMPLETED", result.get("status"));
            Assert.assertEquals(Boolean.TRUE, result.get("hasExplicitReturn"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("resultData");
            Assert.assertEquals(Boolean.TRUE, data.get("ok"));
            Assert.assertEquals(42.0, ((Number) data.get("value")).doubleValue(), 0.0);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void clientRunAndWaitShouldThrowOnTimeout() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("sync_slow", "v1",
                "result = SHELL(\"sleep 5\")\n",
                "runAndWait timeout path", Arrays.asList("test"), true);

            long start = System.currentTimeMillis();
            try {
                client.runAndWait("sync_slow", null, new LinkedHashMap<String, Object>(), 500L);
                Assert.fail("Expected runAndWait to time out before the run finished");
            } catch (IOException expected) {
                // Client-side wait gives up; the run keeps executing server-side. The message
                // carries the runId so the caller can re-poll instead of losing the run.
                String message = expected.getMessage();
                Assert.assertNotNull(message);
                Assert.assertTrue("message should mention timeout: " + message, message.contains("Timed out"));
            }
            long elapsed = System.currentTimeMillis() - start;
            Assert.assertTrue("should give up near the 500ms budget, was " + elapsed + "ms", elapsed < 4000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void scriptVersionsShouldAutoIncrementAndResolveActive() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);

            // register without a version -> auto "1", activated
            Map<String, Object> reg = client.registerScript("autover", "return {\"v\": 1}\n", true);
            Assert.assertEquals("1", reg.get("activeVersion"));

            // update without a version -> auto "2", NOT activated (active stays "1")
            Map<String, Object> upd = client.addScriptVersion("autover", "return {\"v\": 2}\n", false);
            Assert.assertEquals("1", upd.get("activeVersion"));

            List<String> versions = versionLabels(client.getScript("autover"));
            Assert.assertEquals(2, versions.size());
            Assert.assertTrue(versions.contains("1") && versions.contains("2"));

            // version-less run resolves the ACTIVE version (1), not the latest (2)
            Map<String, Object> r1 = client.runAndWait("autover", null, new LinkedHashMap<String, Object>(), 8000L);
            Assert.assertEquals(1.0, resultValue(r1, "v"), 0.0);

            // promote 2, then version-less run resolves 2
            client.activateScript("autover", "2");
            Assert.assertEquals("2", client.getScript("autover").get("activeVersion"));
            Map<String, Object> r2 = client.runAndWait("autover", null, new LinkedHashMap<String, Object>(), 8000L);
            Assert.assertEquals(2.0, resultValue(r2, "v"), 0.0);

            // a third auto version continues the sequence -> "3"
            Map<String, Object> v3 = client.addScriptVersion("autover", "return {\"v\": 3}\n", true);
            Assert.assertEquals("3", v3.get("activeVersion"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void adminUiShouldSetActiveVersion() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("uiver", "return {\"v\": 1}\n", true);    // version "1", active
            client.addScriptVersion("uiver", "return {\"v\": 2}\n", false); // version "2", active still "1"
            Assert.assertEquals("1", client.getScript("uiver").get("activeVersion"));

            int code = postForm(testServer.baseUrl + "/admin/scripts/activate/uiver", "version=2", null);
            Assert.assertTrue("expected a redirect, got " + code, code >= 300 && code < 400);

            Assert.assertEquals("2", client.getScript("uiver").get("activeVersion"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void scriptListAndContentShouldBeRetrievable() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            String src1 = "return {\"v\": 1}\n";
            String src2 = "return {\"v\": 2}\n";
            client.registerScript("content_test", src1, true);   // auto "1", active
            client.addScriptVersion("content_test", src2, true);  // auto "2", active

            // list includes the script
            List<Map<String, Object>> scripts = client.listScripts();
            boolean found = false;
            for (Map<String, Object> s : scripts) {
                if ("content_test".equals(s.get("scriptId"))) found = true;
            }
            Assert.assertTrue("listScripts should include content_test", found);

            // content of the active version (2)
            Map<String, Object> active = client.getScriptContent("content_test", null);
            Assert.assertEquals("2", active.get("version"));
            Assert.assertEquals(src2, active.get("content"));

            // content of a specific version (1)
            Map<String, Object> v1 = client.getScriptContent("content_test", "1");
            Assert.assertEquals(src1, v1.get("content"));

            // unknown version -> 404 -> IOException
            try {
                client.getScriptContent("content_test", "999");
                Assert.fail("expected an error for unknown version");
            } catch (IOException expected) {
                // expected
            }
        } finally {
            testServer.close();
        }
    }

    @Test
    public void outputPublishShouldBeTrackableWithSplitTokens() throws Exception {
        // Distinct publisher/client tokens: register needs the publisher token, submit/track the client token.
        TestServer testServer = createServer(null, "client-secret", "publisher-secret", null);
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, "client-secret", "publisher-secret", null);

            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("stream", "stdout");
            rule.put("pattern", "jobid:\\s*(\\S+)");
            rule.put("captureGroup", Integer.valueOf(1));
            rule.put("publishKey", "jobId");
            rule.put("firstOnly", Boolean.TRUE);
            List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
            rules.add(rule);

            // register with a capture rule (publisher token); SHELL prints the id then lingers
            client.registerScript("published_job",
                "result = SHELL(\"echo jobid: ABC123; sleep 2\")\n", true, rules);

            // submit (client token) and wait for the published id to appear mid-run
            String runId = (String) client.submitRun("published_job", null, new LinkedHashMap<String, Object>()).get("runId");
            Object jobId = client.waitForPublished(runId, "jobId", 8000L);
            Assert.assertEquals("ABC123", jobId);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldRequireBearerTokenWhenConfigured() throws Exception {
        TestServer testServer = createServer("secret-token");
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, "secret-token");
            client.registerScript("auth_result", "v1",
                "result = {\"ok\": true}\n",
                "auth test", Arrays.asList("test"), true);

            assertStatus(testServer.baseUrl + "/api/client/runs", "GET", null, null, 401);

            Map<String, Object> created = client.submitRun("auth_result", null, new LinkedHashMap<String, Object>());
            String runId = (String) created.get("runId");
            Assert.assertNotNull(runId);

            Map<String, Object> detail = waitForRunStatus(testServer.baseUrl, runId, "COMPLETED", 8000L, "secret-token");
            @SuppressWarnings("unchecked")
            Map<String, Object> run = (Map<String, Object>) detail.get("run");
            Assert.assertEquals(Boolean.FALSE, run.get("hasExplicitReturn"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldRequireNamespaceSpecificTokensWhenConfigured() throws Exception {
        TestServer testServer = createServer(null, "client-secret", "publisher-secret", "admin-secret");
        try {
            assertStatus(testServer.baseUrl + "/api/client/runs", "GET", null, null, 401);
            assertStatus(testServer.baseUrl + "/api/publisher/scripts", "GET", null, null, 401);
            assertStatus(testServer.baseUrl + "/api/admin/runs", "GET", null, null, 401);

            assertStatus(testServer.baseUrl + "/api/client/runs", "GET", null, "publisher-secret", 401);
            assertStatus(testServer.baseUrl + "/api/publisher/scripts", "GET", null, "client-secret", 401);
            assertStatus(testServer.baseUrl + "/api/admin/runs", "GET", null, "client-secret", 401);

            TeeBoxClient upstreamClient = new TeeBoxClient(testServer.baseUrl, "client-secret", "publisher-secret", "admin-secret");
            Map<String, Object> registered = upstreamClient.registerScript(
                "secured_calc",
                "v1",
                "return {\"ok\": true, \"sum\": a + b}\n",
                "secured",
                Arrays.asList("secure"),
                true
            );
            Assert.assertEquals("secured_calc", registered.get("scriptId"));

            Map<String, Object> props = new LinkedHashMap<String, Object>();
            props.put("a", Integer.valueOf(40));
            props.put("b", Integer.valueOf(2));
            String runId = (String) upstreamClient.submitRun("secured_calc", null, props).get("runId");
            upstreamClient.waitForRunTerminal(runId, 8000L);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultData = (Map<String, Object>) upstreamClient.getRunResult(runId).get("resultData");
            Assert.assertEquals(42.0, ((Number) resultData.get("sum")).doubleValue(), 0.0);

            assertStatus(testServer.baseUrl + "/api/admin/runs/" + runId, "GET", null, "client-secret", 401);
            Map<String, Object> adminRun = getJsonMap(testServer.baseUrl + "/api/admin/runs/" + runId, 200, "admin-secret");
            @SuppressWarnings("unchecked")
            Map<String, Object> run = (Map<String, Object>) adminRun.get("run");
            Assert.assertEquals("secured_calc", run.get("scriptId"));

            assertStatus(testServer.baseUrl + "/api/runs", "GET", null, null, 404);
            assertStatus(testServer.baseUrl + "/api/tasks", "GET", null, null, 404);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldSupportPublisherClientAndAdminNamespaces() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);

            Map<String, Object> registered = client.registerScript(
                "calc_sum",
                "v1",
                "return {\"ok\": true, \"sum\": a + b}\n",
                "sum values",
                Arrays.asList("calc", "sum"),
                true
            );
            Assert.assertEquals("calc_sum", registered.get("scriptId"));
            Assert.assertEquals("v1", registered.get("activeVersion"));

            List<Map<String, Object>> scripts = client.listScripts();
            Assert.assertEquals(1, scripts.size());

            Map<String, Object> props = new LinkedHashMap<String, Object>();
            props.put("a", Integer.valueOf(40));
            props.put("b", Integer.valueOf(2));

            Map<String, Object> submitted = client.submitRun("calc_sum", "v1", props);
            String runId = (String) submitted.get("runId");
            Assert.assertNotNull(runId);

            Map<String, Object> status = client.waitForRunTerminal(runId, 8000L);
            Assert.assertEquals("COMPLETED", status.get("status"));

            Map<String, Object> result = client.getRunResult(runId);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultData = (Map<String, Object>) result.get("resultData");
            Assert.assertEquals(42.0, ((Number) resultData.get("sum")).doubleValue(), 0.0);

            Map<String, Object> taskSummary = client.getRunTaskSummary(runId);
            Assert.assertEquals(0.0, ((Number) taskSummary.get("total")).doubleValue(), 0.0);

            Map<String, Object> adminRun = client.getAdminRun(runId);
            @SuppressWarnings("unchecked")
            Map<String, Object> adminRunData = (Map<String, Object>) adminRun.get("run");
            Assert.assertEquals("calc_sum", adminRunData.get("scriptId"));
            Assert.assertEquals("v1", adminRunData.get("version"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldExposePlatformBuiltinsThroughRuns() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript(
                "platform_builtins",
                "v1",
                "MKDIR(baseDir + \"/sub\")\n" +
                "WRITE_FILE(baseDir + \"/sub/test.txt\", \"line1\\nline2\\n\")\n" +
                "lines = READ_LINES(baseDir + \"/sub/test.txt\")\n" +
                "info = FILE_INFO(baseDir + \"/sub/test.txt\")\n" +
                "entries = LIST_DIR(baseDir + \"/sub\")\n" +
                "return {\n" +
                "  \"exists\": FILE_EXISTS(baseDir + \"/sub/test.txt\"),\n" +
                "  \"envType\": TYPE_OF(ENV(\"PATH\", \"\")),\n" +
                "  \"line1\": lines.value.1,\n" +
                "  \"line2\": lines.value.2,\n" +
                "  \"fileType\": info.value.type,\n" +
                "  \"entryCount\": LEN(entries.value)\n" +
                "}\n",
                "platform builtins test",
                Arrays.asList("test"),
                true
            );

            Map<String, Object> props = new LinkedHashMap<String, Object>();
            props.put("baseDir", new File(testServer.dataDir, "platform-builtins").getAbsolutePath());
            String runId = (String) client.submitRun("platform_builtins", null, props).get("runId");
            client.waitForRunTerminal(runId, 8000L);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultData = (Map<String, Object>) client.getRunResult(runId).get("resultData");
            Assert.assertEquals(Boolean.TRUE, resultData.get("exists"));
            Assert.assertEquals("string", resultData.get("envType"));
            Assert.assertEquals("line1", resultData.get("line1"));
            Assert.assertEquals("line2", resultData.get("line2"));
            Assert.assertEquals("file", resultData.get("fileType"));
            Assert.assertEquals(1.0, ((Number) resultData.get("entryCount")).doubleValue(), 0.0);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldRunActivatedPublisherVersionByDefault() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);

            client.registerScript(
                "versioned_calc",
                "v1",
                "return {\"ok\": true, \"sum\": a + b}\n",
                "v1",
                Arrays.asList("calc"),
                true
            );
            client.registerScript(
                "versioned_calc",
                "v2",
                "return {\"ok\": true, \"sum\": a + b + 1}\n",
                "v2",
                Arrays.asList("calc"),
                false
            );

            Map<String, Object> beforeActivateProps = new LinkedHashMap<String, Object>();
            beforeActivateProps.put("a", Integer.valueOf(40));
            beforeActivateProps.put("b", Integer.valueOf(2));
            String runV1 = (String) client.submitRun("versioned_calc", null, beforeActivateProps).get("runId");
            client.waitForRunTerminal(runV1, 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultV1 = (Map<String, Object>) client.getRunResult(runV1).get("resultData");
            Assert.assertEquals(42.0, ((Number) resultV1.get("sum")).doubleValue(), 0.0);

            Map<String, Object> activated = client.activateScript("versioned_calc", "v2");
            Assert.assertEquals("v2", activated.get("activeVersion"));

            String runV2 = (String) client.submitRun("versioned_calc", null, beforeActivateProps).get("runId");
            client.waitForRunTerminal(runV2, 8000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultV2 = (Map<String, Object>) client.getRunResult(runV2).get("resultData");
            Assert.assertEquals(43.0, ((Number) resultV2.get("sum")).doubleValue(), 0.0);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldSupportRunAndTaskQueryParameters() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("query_a", "v1",
                "function run_task(arg) do\n" +
                "    return SHELL(\"" + testServer.script("echo_args") + " \" + arg)\n" +
                "end\n\n" +
                "multi result do\n" +
                "    thread t1: run_task(\"a1\")\n" +
                "    thread t2: run_task(\"a2\")\n" +
                "end\n",
                "query a", Arrays.asList("test"), true);
            client.registerScript("query_b", "v1",
                "result = {\"name\": \"b\"}\n",
                "query b", Arrays.asList("test"), true);

            String runA = (String) client.submitRun("query_a", null, new LinkedHashMap<String, Object>()).get("runId");
            String runB = (String) client.submitRun("query_b", null, new LinkedHashMap<String, Object>()).get("runId");

            waitForRunStatus(testServer.baseUrl, runA, "COMPLETED", 8000L);
            waitForRunStatus(testServer.baseUrl, runB, "COMPLETED", 8000L);

            List<Map<String, Object>> completedRuns = getJsonList(testServer.baseUrl + "/api/admin/runs?status=COMPLETED&offset=0&limit=1", 200);
            Assert.assertEquals(1, completedRuns.size());
            @SuppressWarnings("unchecked")
            String runStatus = String.valueOf(completedRuns.get(0).get("status"));
            Assert.assertEquals("COMPLETED", runStatus);

            Map<String, Object> filteredTasks = getJsonMap(testServer.baseUrl + "/api/admin/tasks?runId=" + runA + "&status=completed&offset=0&limit=1", 200);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) filteredTasks.get("tasks");
            Assert.assertEquals(1, tasks.size());
            Assert.assertNull("detached field should no longer exist", filteredTasks.get("detached"));
            Assert.assertEquals(runA, tasks.get(0).get("runId"));
            Assert.assertEquals("completed", tasks.get(0).get("status"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldExposeTimeoutExceededOnTask() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("timeout_task", "v1",
                "result = SHELL(\"" + testServer.script("sleep1") + "\", {\"timeout\": 10})\n" +
                "PRINT(result.ok)\n",
                "timeout task test", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("timeout_task", null, new LinkedHashMap<String, Object>()).get("runId");

            Map<String, Object> detail = waitForRunWithTasks(testServer.baseUrl, runId, 1, 1, 8000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
            String taskId = (String) tasks.get(0).get("taskId");

            Map<String, Object> taskDetail = waitForTaskTimeoutExceeded(testServer.baseUrl, taskId, 4000L);
            @SuppressWarnings("unchecked")
            Map<String, Object> taskInfo = (Map<String, Object>) taskDetail.get("task");
            @SuppressWarnings("unchecked")
            Map<String, Object> observation = (Map<String, Object>) taskDetail.get("observation");
            Assert.assertEquals(Boolean.TRUE, taskInfo.get("timeoutExceeded"));
            Assert.assertEquals(Boolean.TRUE, observation.get("timeoutExceeded"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void serverShouldArchiveOldRuns() throws Exception {
        String oldRunRetention = System.getProperty("propertee.teebox.runRetentionMs");
        String oldRunArchiveRetention = System.getProperty("propertee.teebox.runArchiveRetentionMs");
        String oldMaintenanceInterval = System.getProperty("propertee.teebox.maintenanceIntervalMs");
        System.setProperty("propertee.teebox.runRetentionMs", "0ms");
        System.setProperty("propertee.teebox.runArchiveRetentionMs", "24h");
        System.setProperty("propertee.teebox.maintenanceIntervalMs", "500ms");
        try {
            TestServer testServer = createServer();
            try {
                TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
                client.registerScript("archive_run", "v1",
                    "PRINT(\"line1\")\n" +
                    "PRINT(\"line2\")\n" +
                    "result = {\"ok\": true}\n",
                    "archive run test", Arrays.asList("test"), true);

                String runId = (String) client.submitRun("archive_run", null, new LinkedHashMap<String, Object>()).get("runId");

                waitForRunStatus(testServer.baseUrl, runId, "COMPLETED", 8000L);
                Map<String, Object> archivedDetail = waitForRunArchived(testServer.baseUrl, runId, 8000L);
                @SuppressWarnings("unchecked")
                Map<String, Object> completedRun = (Map<String, Object>) archivedDetail.get("run");
                Assert.assertEquals(Boolean.TRUE, completedRun.get("archived"));

                List<Map<String, Object>> runsAfterArchive = getJsonList(testServer.baseUrl + "/api/admin/runs", 200);
                Assert.assertTrue(containsRun(runsAfterArchive, runId));
            } finally {
                testServer.close();
            }
        } finally {
            restoreProperty("propertee.teebox.runRetentionMs", oldRunRetention);
            restoreProperty("propertee.teebox.runArchiveRetentionMs", oldRunArchiveRetention);
            restoreProperty("propertee.teebox.maintenanceIntervalMs", oldMaintenanceInterval);
        }
    }

    @Test
    public void serverShouldPurgeArchivedRuns() throws Exception {
        String oldRunRetention = System.getProperty("propertee.teebox.runRetentionMs");
        String oldRunArchiveRetention = System.getProperty("propertee.teebox.runArchiveRetentionMs");
        String oldMaintenanceInterval = System.getProperty("propertee.teebox.maintenanceIntervalMs");
        System.setProperty("propertee.teebox.runRetentionMs", "0ms");
        System.setProperty("propertee.teebox.runArchiveRetentionMs", "100ms");
        System.setProperty("propertee.teebox.maintenanceIntervalMs", "500ms");
        try {
            TestServer testServer = createServer();
            try {
                TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
                client.registerScript("purge_run", "v1",
                    "result = {\"ok\": true}\n",
                    "purge run test", Arrays.asList("test"), true);

                String runId = (String) client.submitRun("purge_run", null, new LinkedHashMap<String, Object>()).get("runId");

                waitForRunAbsentFromList(testServer.baseUrl, runId, 8000L);
            } finally {
                testServer.close();
            }
        } finally {
            restoreProperty("propertee.teebox.runRetentionMs", oldRunRetention);
            restoreProperty("propertee.teebox.runArchiveRetentionMs", oldRunArchiveRetention);
            restoreProperty("propertee.teebox.maintenanceIntervalMs", oldMaintenanceInterval);
        }
    }

    @Test
    public void serverShouldServeFragmentEndpoints() throws Exception {
        TestServer testServer = createServer();
        try {
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("frag_test", "v1",
                "result = SHELL(\"" + testServer.script("sleep2") + "\")\n",
                "fragment test", Arrays.asList("test"), true);

            String runId = (String) client.submitRun("frag_test", null, new LinkedHashMap<String, Object>()).get("runId");

            Map<String, Object> detail = waitForRunWithTasks(testServer.baseUrl, runId, 1, 1, 8000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
            String taskId = (String) tasks.get(0).get("taskId");

            // nav-counts fragment
            String navCounts = getHtml(testServer.baseUrl + "/admin/fragments/nav-counts", 200);
            Assert.assertTrue("nav-counts should contain active count", navCounts.contains("active"));
            Assert.assertTrue("nav-counts should contain queued count", navCounts.contains("queued"));

            // run-detail fragment
            String runDetail = getHtml(testServer.baseUrl + "/admin/fragments/run-detail/" + runId, 200);
            Assert.assertTrue("run-detail should contain runId", runDetail.contains(runId));

            // task-detail fragment
            String taskDetail = getHtml(testServer.baseUrl + "/admin/fragments/task-detail/" + taskId, 200);
            Assert.assertTrue("task-detail should contain taskId", taskDetail.contains(taskId));

            // unknown fragment should 404
            assertStatus(testServer.baseUrl + "/admin/fragments/nonexistent", "GET", null, null, 404);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void outputPublishShouldCaptureJobId() throws Exception {
        TestServer testServer = createServer();
        try {
            // Register script with outputRules
            Map<String, Object> registerPayload = new LinkedHashMap<String, Object>();
            registerPayload.put("scriptId", "publish_test");
            registerPayload.put("version", "v1");
            registerPayload.put("content",
                "result = SHELL(\"echo 'jobid: 12345'\")\n" +
                "PRINT(result.value)\n");
            registerPayload.put("activate", Boolean.TRUE);
            List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("stream", "stdout");
            rule.put("pattern", "jobid:\\s*(\\S+)");
            rule.put("captureGroup", Double.valueOf(1));
            rule.put("publishKey", "jobId");
            rule.put("firstOnly", Boolean.TRUE);
            rules.add(rule);
            registerPayload.put("outputRules", rules);
            postJson(testServer.baseUrl + "/api/publisher/scripts", registerPayload, 201);

            // Submit run
            Map<String, Object> runPayload = new LinkedHashMap<String, Object>();
            runPayload.put("props", new LinkedHashMap<String, Object>());
            Map<String, Object> submitResult = postJson(
                testServer.baseUrl + "/api/client/scripts/publish_test/runs", runPayload, 202);
            String runId = (String) submitResult.get("runId");

            // Wait for completion
            Map<String, Object> detail = waitForRunStatus(testServer.baseUrl, runId, "COMPLETED", 10000L);

            // Wait a bit for watcher scan cycle to publish
            // Check published field in client API
            Map<String, Object> clientRun = getJsonMap(
                testServer.baseUrl + "/api/client/runs/" + runId, 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> published = (Map<String, Object>) clientRun.get("published");
            Assert.assertNotNull("published should exist", published);
            Assert.assertEquals("jobId should be 12345", "12345", published.get("jobId"));
            Assert.assertNotNull("detectedAt should exist", published.get("jobId.detectedAt"));
        } finally {
            testServer.close();
        }
    }

    @Test
    public void outputPublishShouldRejectInvalidRegex() throws Exception {
        TestServer testServer = createServer();
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("scriptId", "bad_regex");
            payload.put("version", "v1");
            payload.put("content", "PRINT(\"hello\")\n");
            payload.put("activate", Boolean.TRUE);
            List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("stream", "stdout");
            rule.put("pattern", "[invalid(");
            rule.put("publishKey", "test");
            rules.add(rule);
            payload.put("outputRules", rules);

            // Should return 400
            assertStatus(testServer.baseUrl + "/api/publisher/scripts", "POST", payload, null, 400);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void outputPublishShouldOnlyWatchFirstTask() throws Exception {
        TestServer testServer = createServer();
        try {
            // First SHELL (sequential) outputs "no match here"
            // Second SHELL outputs "jobid: SECRET"
            // If only first task is watched → published should be empty
            // If all tasks are watched → published would contain SECRET
            Map<String, Object> registerPayload = new LinkedHashMap<String, Object>();
            registerPayload.put("scriptId", "first_only");
            registerPayload.put("version", "v1");
            registerPayload.put("content",
                "r1 = SHELL(\"echo 'no match here'\")\n" +
                "r2 = SHELL(\"echo 'jobid: SECRET'\")\n");
            registerPayload.put("activate", Boolean.TRUE);
            List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("stream", "stdout");
            rule.put("pattern", "jobid:\\s*(\\S+)");
            rule.put("captureGroup", Double.valueOf(1));
            rule.put("publishKey", "jobId");
            rule.put("firstOnly", Boolean.TRUE);
            rules.add(rule);
            registerPayload.put("outputRules", rules);
            postJson(testServer.baseUrl + "/api/publisher/scripts", registerPayload, 201);

            Map<String, Object> runPayload = new LinkedHashMap<String, Object>();
            runPayload.put("props", new LinkedHashMap<String, Object>());
            Map<String, Object> submitResult = postJson(
                testServer.baseUrl + "/api/client/scripts/first_only/runs", runPayload, 202);
            String runId = (String) submitResult.get("runId");

            waitForRunStatus(testServer.baseUrl, runId, "COMPLETED", 10000L);
            Thread.sleep(3000);

            Map<String, Object> clientRun = getJsonMap(
                testServer.baseUrl + "/api/client/runs/" + runId, 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> published = (Map<String, Object>) clientRun.get("published");
            // First task outputs "no match here" — no jobid pattern match
            // Second task outputs "jobid: SECRET" but should NOT be watched
            // Therefore published should be null or not contain jobId
            if (published != null) {
                Assert.assertNull("jobId should not be published from second task", published.get("jobId"));
            }
        } finally {
            testServer.close();
        }
    }

    private boolean hasThreadName(List<Map<String, Object>> threads, String name) {
        for (Map<String, Object> thread : threads) {
            if (name.equals(thread.get("name"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasThreadResultKey(List<Map<String, Object>> threads, String resultKeyName) {
        for (Map<String, Object> thread : threads) {
            if (resultKeyName.equals(thread.get("resultKeyName"))) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void perScriptConcurrencyLimitShouldQueueExcessRuns() throws Exception {
        TestServer testServer = createServer();
        try {
            // Register script with maxConcurrentRuns=1
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("limited", "v1",
                "SHELL(\"" + testServer.script("sleep2") + "\")\n",
                "limited test", Arrays.asList("test"), true);

            // Set maxConcurrentRuns=1 via settings API
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            settings.put("maxConcurrentRuns", Double.valueOf(1));
            settings.put("immediate", Boolean.FALSE);
            assertStatus(testServer.baseUrl + "/api/publisher/scripts/limited/settings", "PUT", settings, null, 200);

            // Submit 2 runs — first should run, second should be PENDING
            Map<String, Object> runPayload = new LinkedHashMap<String, Object>();
            runPayload.put("props", new LinkedHashMap<String, Object>());
            Map<String, Object> run1 = postJson(testServer.baseUrl + "/api/client/scripts/limited/runs", runPayload, 202);
            Map<String, Object> run2 = postJson(testServer.baseUrl + "/api/client/scripts/limited/runs", runPayload, 202);
            String runId1 = (String) run1.get("runId");
            String runId2 = (String) run2.get("runId");

            // Wait briefly for first run to start
            Thread.sleep(500);

            // Check statuses
            Map<String, Object> detail2 = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId2, 200);
            String status2 = (String) detail2.get("status");
            Assert.assertTrue("Second run should be PENDING or QUEUED, got " + status2,
                "PENDING".equals(status2) || "QUEUED".equals(status2));

            // Wait for both to complete
            waitForRunStatus(testServer.baseUrl, runId1, "COMPLETED", 10000L);
            waitForRunStatus(testServer.baseUrl, runId2, "COMPLETED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void immediateShouldRespectMaxConcurrentRuns() throws Exception {
        TestServer testServer = createServer();
        try {
            // Register immediate script with maxConcurrentRuns=1
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("imm_limited", "v1",
                "SHELL(\"" + testServer.script("sleep2") + "\")\n",
                "immediate limited test", Arrays.asList("test"), true);

            // Set immediate=true AND maxConcurrentRuns=1
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            settings.put("maxConcurrentRuns", Double.valueOf(1));
            settings.put("immediate", Boolean.TRUE);
            assertStatus(testServer.baseUrl + "/api/publisher/scripts/imm_limited/settings", "PUT", settings, null, 200);

            // Submit 2 runs
            Map<String, Object> runPayload = new LinkedHashMap<String, Object>();
            runPayload.put("props", new LinkedHashMap<String, Object>());
            Map<String, Object> run1 = postJson(testServer.baseUrl + "/api/client/scripts/imm_limited/runs", runPayload, 202);
            Map<String, Object> run2 = postJson(testServer.baseUrl + "/api/client/scripts/imm_limited/runs", runPayload, 202);
            String runId1 = (String) run1.get("runId");
            String runId2 = (String) run2.get("runId");

            // Wait briefly for first to start
            Thread.sleep(500);

            // Second should be PENDING even though immediate=true
            Map<String, Object> detail2 = getJsonMap(testServer.baseUrl + "/api/client/runs/" + runId2, 200);
            String status2 = (String) detail2.get("status");
            Assert.assertTrue("Immediate script should still be PENDING when over limit, got " + status2,
                "PENDING".equals(status2) || "QUEUED".equals(status2));

            // Both should complete eventually
            waitForRunStatus(testServer.baseUrl, runId1, "COMPLETED", 10000L);
            waitForRunStatus(testServer.baseUrl, runId2, "COMPLETED", 10000L);
        } finally {
            testServer.close();
        }
    }

    @Test
    public void drainShouldRejectNewRuns() throws Exception {
        TestServer testServer = createServer();
        try {
            // The drain thread ends by calling the JVM exit hook. Replace it with a recorder BEFORE
            // triggering drain — the production System.exit(0) kills this test fork, and Gradle treats
            // the clean exit as success, silently dropping every test scheduled after this one.
            final java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger exitStatus = new java.util.concurrent.atomic.AtomicInteger(-1);
            testServer.server.getRunManager().setExitHandler(new RunManager.ExitHandler() {
                @Override
                public void exit(int status) {
                    exitStatus.set(status);
                    exited.countDown();
                }
            });

            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("drain_test", "v1",
                "PRINT(\"hello\")\n",
                "drain test", Arrays.asList("test"), true);

            // Start drain
            postJson(testServer.baseUrl + "/api/admin/shutdown", new LinkedHashMap<String, Object>(), 200);

            // Verify drain status
            Map<String, Object> drainStatus = getJsonMap(testServer.baseUrl + "/api/admin/drain-status", 200);
            Assert.assertEquals("Should be draining", Boolean.TRUE, drainStatus.get("draining"));

            // New run should be rejected (409)
            Map<String, Object> runPayload = new LinkedHashMap<String, Object>();
            runPayload.put("props", new LinkedHashMap<String, Object>());
            assertStatus(testServer.baseUrl + "/api/client/scripts/drain_test/runs", "POST", runPayload, null, 409);

            // With nothing in flight the drain completes and requests JVM exit (status 0)
            Assert.assertTrue("drain thread should request JVM exit",
                exited.await(10, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(0, exitStatus.get());
        } finally {
            testServer.close();
        }
    }

    @Test
    public void scriptSoftDeleteAndRestore() throws Exception {
        TestServer testServer = createServer();
        try {
            // Register script
            TeeBoxClient client = new TeeBoxClient(testServer.baseUrl, null);
            client.registerScript("del_test", "v1",
                "PRINT(\"hello\")\n",
                "delete test", Arrays.asList("test"), true);

            // Verify it exists
            getJsonMap(testServer.baseUrl + "/api/publisher/scripts/del_test", 200);

            // Soft-delete
            assertStatus(testServer.baseUrl + "/api/publisher/scripts/del_test", "DELETE", null, null, 200);

            // Script should not be in active list
            List<Map<String, Object>> scripts = getJsonList(testServer.baseUrl + "/api/publisher/scripts", 200);
            Assert.assertFalse("Deleted script should not appear in list", containsScript(scripts, "del_test"));

            // Run should fail (script is deleted)
            Map<String, Object> runPayload = new LinkedHashMap<String, Object>();
            runPayload.put("props", new LinkedHashMap<String, Object>());
            assertStatus(testServer.baseUrl + "/api/client/scripts/del_test/runs", "POST", runPayload, null, 400);

            // Restore
            postJson(testServer.baseUrl + "/api/publisher/scripts/del_test/restore", new LinkedHashMap<String, Object>(), 200);

            // Should be back in list
            scripts = getJsonList(testServer.baseUrl + "/api/publisher/scripts", 200);
            Assert.assertTrue("Restored script should appear in list", containsScript(scripts, "del_test"));
        } finally {
            testServer.close();
        }
    }

    private boolean containsScript(List<Map<String, Object>> scripts, String scriptId) {
        for (Map<String, Object> s : scripts) {
            if (scriptId.equals(s.get("scriptId"))) return true;
        }
        return false;
    }

    private boolean containsRun(List<Map<String, Object>> runs, String runId) {
        for (Map<String, Object> run : runs) {
            if (runId.equals(run.get("runId"))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> waitForRunWithTasks(String baseUrl, String runId, int taskCount, int minThreads, long timeoutMs) throws Exception {
        return waitForRunWithTasks(baseUrl, runId, taskCount, minThreads, timeoutMs, null);
    }

    private Map<String, Object> waitForRunWithTasks(String baseUrl, String runId, int taskCount, int minThreads, long timeoutMs, String bearerToken) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/runs/" + runId, 200, bearerToken);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> threads = (List<Map<String, Object>>) detail.get("threads");
            if (tasks.size() >= taskCount && threads.size() >= minThreads) {
                return detail;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for run tasks: " + runId);
        return null;
    }

    private Map<String, Object> waitForRunStatus(String baseUrl, String runId, String status, long timeoutMs) throws Exception {
        return waitForRunStatus(baseUrl, runId, status, timeoutMs, null);
    }

    private Map<String, Object> waitForRunStatus(String baseUrl, String runId, String status, long timeoutMs, String bearerToken) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/runs/" + runId, 200, bearerToken);
            @SuppressWarnings("unchecked")
            Map<String, Object> run = (Map<String, Object>) detail.get("run");
            if (status.equals(run.get("status"))) {
                return detail;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for run status " + status + ": " + runId);
        return null;
    }

    private Map<String, Object> waitForTaskStatus(String baseUrl, String taskId, String status, long timeoutMs) throws Exception {
        return waitForTaskStatus(baseUrl, taskId, status, timeoutMs, null);
    }

    private Map<String, Object> waitForTaskStatus(String baseUrl, String taskId, String status, long timeoutMs, String bearerToken) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/tasks/" + taskId, 200, bearerToken);
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) detail.get("task");
            if (status.equals(task.get("status"))) {
                return detail;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for task status " + status + ": " + taskId);
        return null;
    }

    private Map<String, Object> waitForTaskTimeoutExceeded(String baseUrl, String taskId, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/tasks/" + taskId, 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) detail.get("task");
            if (Boolean.TRUE.equals(task.get("timeoutExceeded"))) {
                return detail;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for task timeoutExceeded: " + taskId);
        return null;
    }

    private Map<String, Object> waitForRunArchived(String baseUrl, String runId, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Map<String, Object> detail = getJsonMap(baseUrl + "/api/admin/runs/" + runId, 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> run = (Map<String, Object>) detail.get("run");
            if (Boolean.TRUE.equals(run.get("archived"))) {
                return detail;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for run archived: " + runId);
        return null;
    }

    private void waitForRunAbsentFromList(String baseUrl, String runId, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            List<Map<String, Object>> runs = getJsonList(baseUrl + "/api/admin/runs", 200);
            if (!containsRun(runs, runId)) {
                return;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for run purge: " + runId);
    }

    private TestServer createServer() throws Exception {
        return createServer(null);
    }

    private TestServer createServer(String apiToken) throws Exception {
        return createServer(apiToken, null, null, null);
    }

    private TestServer createServer(String apiToken,
                                    String clientApiToken,
                                    String publisherApiToken,
                                    String adminApiToken) throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-data").toFile();
        File scriptsDir = new File(dataDir, "test-scripts");
        scriptsDir.mkdirs();
        writeScript(scriptsDir, "sleep_echo", "sleep 2; echo \"$@\"");
        writeScript(scriptsDir, "sleep30", "sleep 30");
        writeScript(scriptsDir, "sleep2", "sleep 2");
        writeScript(scriptsDir, "sleep1", "sleep 1");
        writeScript(scriptsDir, "echo_args", "echo \"$@\"");

        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        config.apiToken = apiToken;
        config.clientApiToken = clientApiToken;
        config.publisherApiToken = publisherApiToken;
        config.adminApiToken = adminApiToken;

        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return new TestServer(server, "http://127.0.0.1:" + server.getPort(), scriptsDir, dataDir);
    }

    private Map<String, Object> postJson(String url, Map<String, Object> payload, int expectedStatus) throws IOException {
        return postJson(url, payload, expectedStatus, null);
    }

    private Map<String, Object> postJson(String url, Map<String, Object> payload, int expectedStatus, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        byte[] body = gson.toJson(payload).getBytes("UTF-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body);
        } finally {
            out.close();
        }
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        return readJsonMap(conn);
    }

    private int postForm(String url, String formBody, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        OutputStream out = conn.getOutputStream();
        try {
            out.write(formBody.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private List<String> versionLabels(Map<String, Object> scriptDetail) {
        List<String> out = new ArrayList<String>();
        Object versions = scriptDetail.get("versions");
        if (versions instanceof List) {
            for (Object item : (List<?>) versions) {
                if (item instanceof Map) {
                    out.add(String.valueOf(((Map<?, ?>) item).get("version")));
                }
            }
        }
        return out;
    }

    private double resultValue(Map<String, Object> runResult, String key) {
        Map<?, ?> data = (Map<?, ?>) runResult.get("resultData");
        return ((Number) data.get(key)).doubleValue();
    }

    private Map<String, Object> getJsonMap(String url, int expectedStatus) throws IOException {
        return getJsonMap(url, expectedStatus, null);
    }

    private Map<String, Object> getJsonMap(String url, int expectedStatus, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        return readJsonMap(conn);
    }

    private List<Map<String, Object>> getJsonList(String url, int expectedStatus) throws IOException {
        return getJsonList(url, expectedStatus, null);
    }

    private List<Map<String, Object>> getJsonList(String url, int expectedStatus, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        return readJsonList(conn);
    }

    private void assertStatus(String url, String method, Map<String, Object> payload, String bearerToken, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (payload != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = gson.toJson(payload).getBytes("UTF-8");
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
        }
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (input != null) {
            try {
                readAll(input);
            } finally {
                input.close();
            }
        }
        conn.disconnect();
    }

    private String getHtml(String url, int expectedStatus) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        Assert.assertEquals(expectedStatus, status);
        InputStream input = conn.getInputStream();
        try {
            return readAll(input);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private Map<String, Object> readJsonMap(HttpURLConnection conn) throws IOException {
        InputStream input = conn.getInputStream();
        try {
            String json = readAll(input);
            return gson.fromJson(json, mapType);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private List<Map<String, Object>> readJsonList(HttpURLConnection conn) throws IOException {
        InputStream input = conn.getInputStream();
        try {
            String json = readAll(input);
            return gson.fromJson(json, listType);
        } finally {
            input.close();
            conn.disconnect();
        }
    }

    private String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private static class TestServer {
        private final TeeBoxServer server;
        private final String baseUrl;
        private final File scriptsDir;
        private final File dataDir;

        private TestServer(TeeBoxServer server, String baseUrl, File scriptsDir, File dataDir) {
            this.server = server;
            this.baseUrl = baseUrl;
            this.scriptsDir = scriptsDir;
            this.dataDir = dataDir;
        }

        private String script(String name) {
            return new File(scriptsDir, name + ".sh").getAbsolutePath();
        }

        private void close() {
            server.stop();
        }
    }

    private static void writeScript(File dir, String name, String body) throws IOException {
        File script = new File(dir, name + ".sh");
        java.io.FileOutputStream out = new java.io.FileOutputStream(script);
        try {
            out.write(("#!/bin/sh\n" + body + "\n").getBytes("UTF-8"));
        } finally {
            out.close();
        }
        script.setExecutable(true);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
