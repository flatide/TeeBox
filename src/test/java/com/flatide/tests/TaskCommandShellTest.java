package com.flatide.tests;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskStatus;
import com.flatide.teebox.ManagedTaskEngine;
import com.flatide.teebox.TeeBoxClient;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.UnixTaskRunner;
import com.flatide.teebox.lifecycle.TaskLifecycle;
import com.flatide.teebox.lifecycle.TaskLossReason;
import com.flatide.teebox.lifecycle.TaskTerminalState;

import org.junit.Assert;
import org.junit.Assume;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A task's command runs in a shell of its own, and what that shell exits with is the task's exit
 * code — whatever the command does to its shell: {@code exit}, {@code exec}, {@code set -e}, a
 * line the shell cannot parse.
 *
 * <p>The command used to be written into the middle of the script that records the exit code, so
 * a command that ended or broke that script left no code behind. A command ending in
 * {@code exit 0} was reported as failed. One that ended at once was not recorded as a task at
 * all: starting it failed after two seconds with "Detached task did not reach a controllable
 * running state", in place of what the command had printed.
 */
public class TaskCommandShellTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

    /** Commands that are over before the runner first looks at them. */
    @Test
    public void aCommandThatEndsItsOwnShellLeavesItsExitCode() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            assertEnded(runner, "echo out; exit 0", TaskStatus.COMPLETED, 0, "out\n");
            assertEnded(runner, "echo out; exit 3", TaskStatus.FAILED, 3, "out\n");
            assertEnded(runner, "exit 1", TaskStatus.FAILED, 1, "");   // the language spec's own example
            assertEnded(runner, "echo out; if true; then exit 5; fi", TaskStatus.FAILED, 5, "out\n");
            assertEnded(runner, "f() { exit 4; }; echo out; f; echo unreachable", TaskStatus.FAILED, 4, "out\n");
            assertEnded(runner, "exec echo out", TaskStatus.COMPLETED, 0, "out\n");
            assertEnded(runner, "set -e; echo out; false; echo unreachable", TaskStatus.FAILED, 1, "out\n");
            assertEnded(runner, "echo out \\", TaskStatus.COMPLETED, 0, "out\n");   // ends in a backslash

            // What never ended its shell is as it was.
            assertEnded(runner, "echo out", TaskStatus.COMPLETED, 0, "out\n");
            assertEnded(runner, "echo out; false", TaskStatus.FAILED, 1, "out\n");
        } finally {
            runner.shutdown();
        }
    }

    /**
     * The same for a command the runner has seen running. Such a task was recorded, but as
     * {@code lost}, without an exit code — also when the command had ended with {@code exit 0}.
     */
    @Test
    public void aCommandThatEndsItsOwnShellAfterAWhileLeavesItsExitCodeToo() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            for (String code : Arrays.asList("0", "3")) {
                String command = "sleep 2; echo out; exit " + code;
                Task started = runner.execute(request(command));
                Assert.assertTrue(command + " was not seen running", started.alive);
                Task ended = runner.waitForCompletion(started.taskId, 20_000L);
                Assert.assertEquals(command, "0".equals(code) ? TaskStatus.COMPLETED : TaskStatus.FAILED, ended.status);
                Assert.assertEquals(command, Integer.valueOf(code), ended.exitCode);
                Assert.assertEquals(command, "out\n", runner.getCombinedOutput(ended.taskId));
            }
        } finally {
            runner.shutdown();
        }
    }

    /** A line the shell cannot parse is that shell's failure: its exit code and its complaint. */
    @Test
    public void aLineTheShellCannotParseIsAFailedCommand() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            Task task = run(runner, "echo out; echo \"unterminated");
            Assert.assertEquals(TaskStatus.FAILED, task.status);
            Assert.assertNotNull("the shell's exit code", task.exitCode);
            Assert.assertTrue("exit code " + task.exitCode, task.exitCode.intValue() != 0);
            Assert.assertFalse("the shell's complaint is the task's output",
                    runner.getCombinedOutput(task.taskId).trim().isEmpty());
        } finally {
            runner.shutdown();
        }
    }

    /**
     * The shell that runs the command is not the one that records the exit code: a signal that
     * kills the first leaves the second to write it down, as 128 plus the signal.
     */
    @Test
    public void aCommandWhoseShellIsKilledIsAFailedCommand() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            Task task = run(runner, "echo out; kill -9 $$");
            Assert.assertEquals(TaskStatus.FAILED, task.status);
            Assert.assertEquals(Integer.valueOf(137), task.exitCode);
            String output = runner.getCombinedOutput(task.taskId);
            Assert.assertTrue(output, output.startsWith("out\n"));
        } finally {
            runner.shutdown();
        }
    }

    /** The task's command file is the command, as it was given. */
    @Test
    public void theCommandFileHoldsTheCommandAndNothingElse() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            Task task = run(runner, "echo out; exit 3");
            Assert.assertEquals("echo out; exit 3\n", text(task.commandFile));
        } finally {
            runner.shutdown();
        }
    }

    /** Where the command's two streams go is as it was, merged or apart. */
    @Test
    public void theCommandsOutputGoesWhereItWent() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            TaskRequest merged = request("echo out; echo err >&2; exit 3");
            merged.mergeErrorToStdout = true;
            Task one = runner.waitForCompletion(runner.execute(merged).taskId, 20_000L);
            Assert.assertEquals("out\nerr\n", text(one.stdoutFile));
            Assert.assertTrue("the error file is there, and empty", one.stderrFile.isFile());
            Assert.assertEquals("", text(one.stderrFile));

            TaskRequest apart = request("echo out; echo err >&2; exit 3");
            apart.mergeErrorToStdout = false;
            Task two = runner.waitForCompletion(runner.execute(apart).taskId, 20_000L);
            Assert.assertEquals("out\n", text(two.stdoutFile));
            Assert.assertEquals("err\n", text(two.stderrFile));
            Assert.assertEquals(Integer.valueOf(3), two.exitCode);
        } finally {
            runner.shutdown();
        }
    }

    /**
     * Killing a task ends the shell its command runs in, and what that shell started — one
     * process further from the task's own than before.
     */
    @Test
    public void killingATaskEndsTheShellItsCommandRunsIn() throws Exception {
        assumeUnix();
        File dir = tempDir();
        File pids = new File(dir, "pids");
        UnixTaskRunner runner = new UnixTaskRunner(dir.getAbsolutePath());
        // The command's shell writes down its own pid and its child's, then waits for the child.
        Task task = runner.execute(request("sleep 60 & echo $$ $! > '" + pids.getAbsolutePath() + "'; wait"));
        try {
            String[] written = waitForWords(pids, 2);
            int shellPid = Integer.parseInt(written[0]);
            int childPid = Integer.parseInt(written[1]);
            Assert.assertNotEquals("the command has a shell of its own", task.pid, shellPid);
            Assert.assertTrue(isAlive(task.pid) && isAlive(shellPid) && isAlive(childPid));

            Assert.assertTrue(runner.killTask(task.taskId));

            waitUntilGone("the task's process", task.pid);
            waitUntilGone("the command's shell", shellPid);
            waitUntilGone("the command's child", childPid);
            Assert.assertEquals(TaskStatus.KILLED, runner.getTask(task.taskId).status);
        } finally {
            runner.killTask(task.taskId);   // a failed assertion leaves nothing running for a minute
            runner.shutdown();
        }
    }

    /**
     * The task's process outlives a TERM, an INT and a HUP of its own: it has nothing to do but
     * wait for the command's shell, and while that one runs the task is running. Without that, a
     * signal that reached the task's process and not the command left the command running under a
     * task recorded as {@code lost}.
     */
    @Test
    public void aSignalToTheTasksProcessAloneDoesNotEndTheTask() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = new UnixTaskRunner(tempDir().getAbsolutePath());
        try {
            Task started = runner.execute(request("sleep 2; echo out; exit 7"));
            Assert.assertTrue("the command was not seen running", started.alive);
            for (String signal : Arrays.asList("TERM", "INT", "HUP")) {
                Process kill = new ProcessBuilder("kill", "-" + signal, String.valueOf(started.pid)).start();
                Assert.assertEquals("kill -" + signal, 0, kill.waitFor());
            }
            Task ended = runner.waitForCompletion(started.taskId, 20_000L);

            Assert.assertEquals(TaskStatus.FAILED, ended.status);
            Assert.assertEquals("the command's own exit code", Integer.valueOf(7), ended.exitCode);
            Assert.assertEquals("out\n", runner.getCombinedOutput(ended.taskId));
        } finally {
            runner.shutdown();
        }
    }

    /**
     * A task restored after a restart is killed with a TERM first, and with a KILL when its
     * process is still there a second later. A command that ignores TERM keeps its shell running,
     * so the task's process has to stay with it for that KILL to come — when it left at the TERM,
     * the task was recorded as killed and the command ran on.
     */
    @Test
    public void killingARestoredTaskEndsACommandThatIgnoresTerm() throws Exception {
        assumeUnix();
        File dir = tempDir();
        File pids = new File(dir, "pids");
        ManagedTaskEngine before = new ManagedTaskEngine(dir.getAbsolutePath(), "host-ignores-term");
        // The command's shell ignores TERM, and so does the child it starts.
        Task task = before.execute(request(
                "trap '' TERM; sleep 300 & echo $$ $! > '" + pids.getAbsolutePath() + "'; wait"));
        int shellPid = 0;
        int childPid = 0;
        ManagedTaskEngine restarted = null;
        try {
            String[] written = waitForWords(pids, 2);
            shellPid = Integer.parseInt(written[0]);
            childPid = Integer.parseInt(written[1]);

            restarted = new ManagedTaskEngine(dir.getAbsolutePath(), "host-ignores-term");
            restarted.init();
            Assert.assertTrue(restarted.killTask(task.taskId));

            waitUntilGone("the task's process", task.pid);
            waitUntilGone("the command's shell", shellPid);
            waitUntilGone("the command's child", childPid);
            Assert.assertEquals(TaskStatus.KILLED, restarted.getTask(task.taskId).status);
        } finally {
            // A failed assertion leaves nothing running for five minutes.
            for (int pid : new int[] {task.pid, shellPid, childPid}) {
                if (pid > 0) {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            before.shutdown();
            if (restarted != null) {
                restarted.shutdown();
            }
        }
    }

    /**
     * A task that ends without an exit code — here because the command puts a directory where the
     * code would be written — is {@code lost}: listed, saved, with the reason, and with what it
     * printed, the shell's complaint about the code it could not write included. That holds
     * whether the runner saw the process running or found it gone at its first look; starting a
     * task used to fail in the second case, with no task recorded. (Which of the two this command
     * meets is a race. {@code TaskGoneAtOnceTest} pins the second.)
     */
    @Test
    public void aTaskWithoutAnExitCodeIsListedAsLostWithItsReasonAndItsOutput() throws Exception {
        assumeUnix();
        ManagedTaskEngine engine = new ManagedTaskEngine(tempDir().getAbsolutePath(), "host-no-exit-code");
        try {
            TaskRequest request = request("echo out; mkdir \"$(dirname \"$0\")/exit.code\"");
            Task started = engine.execute(request);
            Task ended = engine.waitForCompletion(started.taskId, 20_000L);

            Assert.assertEquals(TaskStatus.LOST, ended.status);
            Assert.assertNull(ended.exitCode);
            TaskLifecycle lifecycle = engine.getLifecycle(ended.taskId);
            Assert.assertEquals(TaskTerminalState.LOST, lifecycle.getTerminalState());
            Assert.assertEquals(TaskLossReason.PROCESS_MISSING, lifecycle.getLossReason());
            Assert.assertTrue("saved", lifecycle.isPersisted());
            String output = engine.getCombinedOutput(ended.taskId);
            Assert.assertTrue(output, output.startsWith("out\n") && output.contains("exit.code"));
            List<Task> listed = engine.queryTasks(request.runId, null, 0, -1);
            Assert.assertEquals(1, listed.size());
            Assert.assertEquals(ended.taskId, listed.get(0).taskId);
            Assert.assertTrue(new File(engine.getTaskDir(ended.taskId), "meta.json").isFile());
        } finally {
            engine.shutdown();
        }
    }

    /** What a script gets from SHELL, and what the run's tasks then say. */
    @Test
    public void aScriptSeesACommandThatEndsInExitByItsExitCode() throws Exception {
        assumeUnix();
        Gson gson = new Gson();
        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();

        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = tempDir();
        config.maxConcurrentRuns = 2;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getPort();
            TeeBoxClient client = new TeeBoxClient(baseUrl, null);
            client.registerScript("exits", "1",
                "return {\n"
                + "    \"zero\": SHELL(\"echo out; exit 0\"),\n"
                + "    \"three\": SHELL(\"echo out; exit 3\"),\n"
                + "    \"bare\": SHELL(\"exit 1\")\n"
                + "}\n",
                "exits", Arrays.asList("test"), true);
            Map<String, Object> accepted = gson.fromJson(
                post(baseUrl + "/api/client/scripts/exits/runs", "{\"props\": {}}", 202), mapType);
            String runId = (String) accepted.get("runId");
            String detail = waitFor(baseUrl + "/api/admin/runs/" + runId, "\"status\": \"COMPLETED\"");

            Map<String, Object> expected = new LinkedHashMap<String, Object>();
            expected.put("zero", result("done", true, "out"));
            expected.put("three", result("error", false, "out"));
            expected.put("bare", result("error", false, "Task failed with exitCode 1"));
            Map<String, Object> run = gson.fromJson(detail, mapType);
            Assert.assertEquals(expected, ((Map<?, ?>) run.get("run")).get("resultData"));

            List<Map<String, Object>> tasks = gson.fromJson(get(baseUrl + "/api/admin/runs/" + runId + "/tasks"), listType);
            Map<String, String> ended = new LinkedHashMap<String, String>();
            for (Map<String, Object> task : tasks) {
                ended.put((String) task.get("command"),
                        task.get("status") + " " + ((Number) task.get("exitCode")).intValue());
            }
            Map<String, String> expectedTasks = new LinkedHashMap<String, String>();
            expectedTasks.put("echo out; exit 0", "completed 0");
            expectedTasks.put("echo out; exit 3", "failed 3");
            expectedTasks.put("exit 1", "failed 1");
            Assert.assertEquals("each command is a task, ended by its exit code", expectedTasks, sorted(ended, expectedTasks));
        } finally {
            server.stop();
        }
    }

    // ===================== helpers =====================

    private static void assumeUnix() {
        Assume.assumeFalse("needs a Unix shell and process control", IS_WINDOWS);
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-task-command-shell").toFile();
    }

    /** A request as SHELL makes it: the two streams merged. */
    private static TaskRequest request(String command) {
        TaskRequest request = new TaskRequest();
        request.command = command;
        request.runId = "run-command-shell";
        request.mergeErrorToStdout = true;
        return request;
    }

    private static Task run(UnixTaskRunner runner, String command) throws Exception {
        Task started = runner.execute(request(command));
        Task ended = runner.waitForCompletion(started.taskId, 20_000L);
        Assert.assertNotNull(command, ended);
        Assert.assertFalse(command + " is still running", ended.alive);
        return ended;
    }

    private static void assertEnded(UnixTaskRunner runner, String command, TaskStatus status, int exitCode,
                                    String output) throws Exception {
        Task task = run(runner, command);
        Assert.assertEquals(command, status, task.status);
        Assert.assertEquals(command, Integer.valueOf(exitCode), task.exitCode);
        Assert.assertEquals(command, output, runner.getCombinedOutput(task.taskId));
        Assert.assertNotNull(command + " is recorded", runner.getTask(task.taskId));
    }

    private static Map<String, Object> result(String status, boolean ok, String value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("ok", Boolean.valueOf(ok));
        result.put("value", value);
        return result;
    }

    /** {@code found} in the key order of {@code expected}, so a difference reads side by side. */
    private static Map<String, String> sorted(Map<String, String> found, Map<String, String> expected) {
        Map<String, String> sorted = new LinkedHashMap<String, String>();
        for (String key : expected.keySet()) {
            if (found.containsKey(key)) {
                sorted.put(key, found.get(key));
            }
        }
        sorted.putAll(found);
        return sorted;
    }

    private static boolean isAlive(int pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(Boolean.FALSE).booleanValue();
    }

    private static void waitUntilGone(String what, int pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (isAlive(pid) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        Assert.assertFalse(what + " (pid " + pid + ") is still there", isAlive(pid));
    }

    /** The words of a file another process is writing, once it holds that many. */
    private static String[] waitForWords(File file, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = file.isFile() ? text(file).trim() : "";
            String[] words = content.isEmpty() ? new String[0] : content.split("\\s+");
            if (words.length >= count) {
                return words;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError(file + " never held " + count + " words; last: " + content);
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }

    private static String waitFor(String url, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30000L;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(url);
            if (last.contains(expected)) {
                return last;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(url + " did not show " + expected + "; last:\n" + last);
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
