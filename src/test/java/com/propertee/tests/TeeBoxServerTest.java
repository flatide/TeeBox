package com.propertee.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.propertee.teebox.TeeBoxClient;
import com.propertee.teebox.TeeBoxServer;
import com.propertee.teebox.TeeBoxConfig;

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
                "    taskId = START_TASK(\"sleep 2; echo \" + name)\n" +
                "    return WAIT_TASK(taskId, 5000)\n" +
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
                "taskId = START_TASK(\"sleep 30\")\n" +
                "result = WAIT_TASK(taskId, 60000)\n" +
                "PRINT(result.status)\n",
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
                "taskId = START_TASK(\"sleep 30\")\n" +
                "result = WAIT_TASK(taskId, 60000)\n" +
                "PRINT(result.status)\n",
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

            String returnRunId = (String) client.submitRun("return_result", null, new LinkedHashMap<String, Object>()).get("runId");
            String variableRunId = (String) client.submitRun("variable_result", null, new LinkedHashMap<String, Object>()).get("runId");

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
                "taskA = START_TASK(\"echo a1\")\n" +
                "taskB = START_TASK(\"echo a2\")\n" +
                "result = [WAIT_TASK(taskA, 5000), WAIT_TASK(taskB, 5000)]\n",
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
                "taskId = START_TASK(\"sleep 1\", {\"timeout\": 10})\n" +
                "PRINT(taskId)\n",
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
        System.setProperty("propertee.teebox.runRetentionMs", "0");
        System.setProperty("propertee.teebox.runArchiveRetentionMs", "86400000");
        System.setProperty("propertee.teebox.maintenanceIntervalMs", "500");
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
        System.setProperty("propertee.teebox.runRetentionMs", "0");
        System.setProperty("propertee.teebox.runArchiveRetentionMs", "100");
        System.setProperty("propertee.teebox.maintenanceIntervalMs", "500");
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
        return new TestServer(server, "http://127.0.0.1:" + server.getPort());
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

        private TestServer(TeeBoxServer server, String baseUrl) {
            this.server = server;
            this.baseUrl = baseUrl;
        }

        private void close() {
            server.stop();
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
