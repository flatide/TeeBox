package com.propertee.tests;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.propertee.task.Task;
import com.propertee.task.TaskRequest;
import com.propertee.task.TaskStatus;
import com.propertee.teebox.ManagedTaskEngine;
import com.propertee.teebox.lifecycle.TaskLifecycle;
import com.propertee.teebox.lifecycle.TaskTerminalState;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public class ManagedTaskEngineTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

    @Test
    public void killAfterRestartShouldTerminateProcess() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix process control", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-kill").toFile();
        String hostId = "host-restart-kill";
        File script = writeScript(baseDir, "sleep60.sh", "sleep 60");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-kill-test";

        Task task = engine1.execute(request);
        Assert.assertNotNull(task.taskId);
        Assert.assertTrue(task.pid > 0);
        Assert.assertTrue("Task should be alive", isProcessAlive(task.pid));

        // Simulate server restart
        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        boolean killed = engine2.killTask(task.taskId);
        Assert.assertTrue("killTask should succeed for restored task", killed);

        Thread.sleep(1500L);

        Assert.assertFalse("Process should be terminated after kill", isProcessAlive(task.pid));

        Task reloaded = engine2.getTask(task.taskId);
        Assert.assertNotNull(reloaded);
        Assert.assertEquals(TaskStatus.KILLED, reloaded.status);
        Assert.assertFalse(reloaded.alive);

        engine1.shutdown();
        engine2.shutdown();
    }

    @Test
    public void killAfterRestartShouldTerminateChildProcesses() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix shell syntax", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-kill-children").toFile();
        File childPidFile = new File(baseDir, "child.pid");
        String hostId = "host-restart-kill-children";

        File script = writeScript(baseDir, "child.sh",
                "sleep 60 & echo $! > '" + shellEscape(childPidFile.getAbsolutePath()) + "'; wait");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-kill-children-test";

        Task task = engine1.execute(request);

        waitForFile(childPidFile, 3000L);
        int childPid = Integer.parseInt(readFile(childPidFile).trim());
        Assert.assertTrue("Child should be alive", isProcessAlive(childPid));

        // Simulate restart
        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        boolean killed = engine2.killTask(task.taskId);
        Assert.assertTrue("killTask should succeed", killed);

        Thread.sleep(2000L);

        Assert.assertFalse("Parent should be dead", isProcessAlive(task.pid));
        Assert.assertFalse("Child should be dead", isProcessAlive(childPid));

        engine1.shutdown();
        engine2.shutdown();
    }

    @Test
    public void initShouldRecoverRunningTaskFromDisk() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix process control", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-init").toFile();
        File script = writeScript(baseDir, "sleep30.sh", "sleep 30");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), "host-init-1");

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-init-test";

        Task task = engine1.execute(request);
        String taskId = task.taskId;

        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), "host-init-2");
        engine2.init();

        Task recovered = engine2.getTask(taskId);
        Assert.assertNotNull("Task should be recoverable after restart", recovered);
        Assert.assertEquals(TaskStatus.RUNNING, recovered.status);
        Assert.assertTrue(recovered.alive);

        engine2.killTask(taskId);
        engine1.shutdown();
        engine2.shutdown();
    }

    @Test
    public void initShouldRecoverTaskWithMissingPidStartTime() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix process control", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-init-nopst").toFile();
        File script = writeScript(baseDir, "sleep30.sh", "sleep 30");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), "host-nopst-1");

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-nopst-test";

        Task task = engine1.execute(request);
        String taskId = task.taskId;
        Assert.assertTrue("pidStartTime should have been recorded", task.pidStartTime > 0);

        // Tamper with meta.json to simulate pidStartTime not being recorded
        File metaFile = new File(new File(new File(baseDir, "tasks"), "task-" + taskId), "meta.json");
        Assert.assertTrue("meta.json should exist", metaFile.exists());
        String metaJson = readFile(metaFile);
        JsonObject metaObj = new Gson().fromJson(metaJson, JsonObject.class);
        metaObj.addProperty("pidStartTime", 0);
        Files.write(metaFile.toPath(), new Gson().toJson(metaObj).getBytes("UTF-8"));

        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), "host-nopst-2");
        engine2.init();

        Task recovered = engine2.getTask(taskId);
        Assert.assertNotNull("Task should be recoverable even without pidStartTime", recovered);
        Assert.assertEquals("Task should be RUNNING, not LOST", TaskStatus.RUNNING, recovered.status);
        Assert.assertTrue("Task should be alive", recovered.alive);

        engine2.killTask(taskId);
        engine1.shutdown();
        engine2.shutdown();
    }

    @Test
    public void persistedCompletedShouldBlockKillAfterRestart() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix process control", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-persisted-kill").toFile();
        String hostId = "host-persisted-test";
        File script = writeScript(baseDir, "done.sh", "echo done");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine1.init();

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-persisted-test";

        Task task = engine1.execute(request);
        String taskId = task.taskId;

        Task afterComplete = waitForTerminal(engine1, taskId, 5000L);
        Assert.assertNotNull("Task should complete", afterComplete);
        Assert.assertEquals(TaskStatus.COMPLETED, afterComplete.status);

        TaskLifecycle lc1 = engine1.getLifecycle(taskId);
        Assert.assertNotNull("Lifecycle should exist", lc1);
        Assert.assertTrue("Lifecycle should be terminal", lc1.isTerminal());
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc1.getTerminalState());
        Assert.assertTrue("Lifecycle should be persisted", lc1.isPersisted());

        File taskDir = new File(new File(baseDir, "tasks"), "task-" + taskId);
        File metaFile = new File(taskDir, "meta.json");
        Assert.assertTrue("meta.json should exist", metaFile.exists());
        String metaJson = readFile(metaFile);
        JsonObject metaObj = new Gson().fromJson(metaJson, JsonObject.class);
        Assert.assertTrue("meta.json should contain persisted=true",
                metaObj.has("persisted") && metaObj.get("persisted").getAsBoolean());

        engine1.shutdown();

        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        TaskLifecycle lc2 = engine2.getLifecycle(taskId);
        Assert.assertNotNull("Lifecycle should be reloaded", lc2);
        Assert.assertTrue("Reloaded lifecycle should be persisted", lc2.isPersisted());
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc2.getTerminalState());

        boolean killed = engine2.killTask(taskId);
        Assert.assertFalse("kill should not succeed on persisted COMPLETED", killed);

        Task afterKillAttempt = engine2.getTask(taskId);
        Assert.assertNotNull(afterKillAttempt);
        Assert.assertEquals("Status should remain COMPLETED after kill attempt",
                TaskStatus.COMPLETED, afterKillAttempt.status);

        engine2.shutdown();
    }

    @Test
    public void persistedNonKilledTerminalShouldBlockKillAfterRestart() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix process control", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-persisted-terminal").toFile();
        String hostId = "host-persisted-terminal";
        File script = writeScript(baseDir, "fail.sh", "sleep 0.5; exit 1");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine1.init();

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-persisted-terminal";

        Task task = engine1.execute(request);
        String taskId = task.taskId;

        Task afterExit = waitForTerminal(engine1, taskId, 8000L);
        Assert.assertNotNull("Task should reach terminal", afterExit);
        TaskStatus originalStatus = afterExit.status;
        Assert.assertNotEquals("Should not be KILLED", TaskStatus.KILLED, originalStatus);

        TaskLifecycle lc1 = engine1.getLifecycle(taskId);
        Assert.assertTrue("Should be persisted", lc1.isPersisted());
        TaskTerminalState originalTerminal = lc1.getTerminalState();

        engine1.shutdown();

        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        TaskLifecycle lc2 = engine2.getLifecycle(taskId);
        Assert.assertTrue("Reloaded should be persisted", lc2.isPersisted());
        Assert.assertEquals("Terminal state should survive restart", originalTerminal, lc2.getTerminalState());

        boolean killed = engine2.killTask(taskId);
        Assert.assertFalse("kill should not succeed on persisted non-KILLED terminal", killed);

        Task afterKill = engine2.getTask(taskId);
        Assert.assertEquals("Status should remain unchanged after kill attempt",
                originalStatus, afterKill.status);

        engine2.shutdown();
    }

    @Test
    public void killedTaskShouldRemainKilledAfterRestart() throws Exception {
        Assume.assumeFalse("Skipped on Windows: requires Unix process control", IS_WINDOWS);
        File baseDir = Files.createTempDirectory("managed-task-killed-restart").toFile();
        String hostId = "host-killed-restart";
        File script = writeScript(baseDir, "sleep60.sh", "sleep 60");

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine1.init();

        TaskRequest request = new TaskRequest();
        request.command = script.getAbsolutePath();
        request.runId = "run-killed-restart";

        Task task = engine1.execute(request);
        String taskId = task.taskId;
        Assert.assertTrue("Process should be alive", isProcessAlive(task.pid));

        boolean killed = engine1.killTask(taskId);
        Assert.assertTrue("kill should succeed", killed);

        Thread.sleep(1500L);
        Assert.assertFalse("Process should be dead", isProcessAlive(task.pid));

        File metaFile = new File(new File(new File(baseDir, "tasks"), "task-" + taskId), "meta.json");
        String metaJson = readFile(metaFile);
        JsonObject metaObj = new Gson().fromJson(metaJson, JsonObject.class);
        Assert.assertEquals("KILLED", metaObj.get("terminalState").getAsString());
        Assert.assertTrue(metaObj.get("persisted").getAsBoolean());

        engine1.shutdown();

        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        Task reloaded = engine2.getTask(taskId);
        Assert.assertEquals(TaskStatus.KILLED, reloaded.status);

        boolean killed2 = engine2.killTask(taskId);
        Assert.assertTrue("repeated kill on KILLED should return true", killed2);

        engine2.shutdown();
    }

    private static Task waitForTerminal(ManagedTaskEngine engine, String taskId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            Task task = engine.getTask(taskId);
            if (task == null) return null;
            if (!task.alive && task.status != TaskStatus.STARTING && task.status != TaskStatus.RUNNING) {
                return task;
            }
            Thread.sleep(100L);
        }
        Assert.fail("Timed out waiting for task to reach terminal state: " + taskId);
        return null;
    }

    private static boolean isProcessAlive(int pid) {
        if (IS_WINDOWS) {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
        try {
            Process process = new ProcessBuilder("kill", "-0", String.valueOf(pid)).start();
            boolean alive = process.waitFor() == 0;
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            return alive;
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitForFile(File file, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!file.exists() && (System.currentTimeMillis() - start) < timeoutMs) {
            Thread.sleep(50);
        }
        if (!file.exists()) {
            Assert.fail("Timed out waiting for file: " + file.getAbsolutePath());
        }
    }

    private static String readFile(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }

    private static String shellEscape(String value) {
        return value.replace("'", "'\"'\"'");
    }

    private static File writeScript(File dir, String name, String body) throws Exception {
        File script = new File(dir, name);
        FileOutputStream out = new FileOutputStream(script);
        try {
            out.write(("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        script.setExecutable(true);
        return script;
    }
}
