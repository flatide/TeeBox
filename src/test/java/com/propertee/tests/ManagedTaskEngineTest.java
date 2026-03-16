package com.propertee.tests;

import com.propertee.task.Task;
import com.propertee.task.TaskRequest;
import com.propertee.task.TaskStatus;
import com.propertee.teebox.ManagedTaskEngine;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
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

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);

        TaskRequest request = new TaskRequest();
        request.command = "sleep 60";
        request.runId = "run-kill-test";

        Task task = engine1.execute(request);
        Assert.assertNotNull(task.taskId);
        Assert.assertTrue(task.pid > 0);
        Assert.assertTrue("Task should be alive", isProcessAlive(task.pid));

        // Simulate server restart
        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        // Kill via the restarted engine (disk-loaded task path)
        boolean killed = engine2.killTask(task.taskId);
        Assert.assertTrue("killTask should succeed for restored task", killed);

        Thread.sleep(1500L);

        Assert.assertFalse("Process should be terminated after kill", isProcessAlive(task.pid));

        // Verify KILLED status persisted to disk
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

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);

        TaskRequest request = new TaskRequest();
        request.command = "sleep 60 & echo $! > '" + shellEscape(childPidFile.getAbsolutePath()) + "'; wait";
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
        String hostId = "host-init-test";

        ManagedTaskEngine engine1 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);

        TaskRequest request = new TaskRequest();
        request.command = "sleep 30";
        request.runId = "run-init-test";

        Task task = engine1.execute(request);
        String taskId = task.taskId;

        // Simulate restart with same host
        ManagedTaskEngine engine2 = new ManagedTaskEngine(baseDir.getAbsolutePath(), hostId);
        engine2.init();

        Task recovered = engine2.getTask(taskId);
        Assert.assertNotNull("Task should be recoverable after restart", recovered);
        Assert.assertEquals(TaskStatus.RUNNING, recovered.status);
        Assert.assertTrue(recovered.alive);

        engine2.killTask(taskId);
        engine1.shutdown();
        engine2.shutdown();
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
}
