package com.flatide.tests;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskStatus;
import com.flatide.teebox.SimulatedTaskRunner;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class SimulatedTaskRunnerTest {

    @Test
    public void simulatedRunnerShouldCompleteTask() throws Exception {
        File baseDir = Files.createTempDirectory("sim-runner-complete").toFile();
        SimulatedTaskRunner runner = new SimulatedTaskRunner(baseDir.getAbsolutePath());

        TaskRequest request = new TaskRequest();
        request.command = "echo hello";
        request.runId = "run-sim-complete";

        Task task = runner.execute(request);
        Assert.assertTrue(task.pid > 0);
        Assert.assertEquals(task.pid, task.pgid);
        Assert.assertTrue(task.alive);

        Task finished = runner.waitForCompletion(task.taskId, 2000L);
        Assert.assertNotNull(finished);
        Assert.assertEquals(TaskStatus.COMPLETED, finished.status);
        Assert.assertFalse(finished.alive);
        Assert.assertEquals(Integer.valueOf(0), finished.exitCode);
        Assert.assertTrue(runner.getStdout(task.taskId).contains("simulated-windows"));

        runner.shutdown();
    }

    @Test
    public void simulatedRunnerShouldAllowKill() throws Exception {
        File baseDir = Files.createTempDirectory("sim-runner-kill").toFile();
        SimulatedTaskRunner runner = new SimulatedTaskRunner(baseDir.getAbsolutePath());

        TaskRequest request = new TaskRequest();
        request.command = "sleep 10";
        request.runId = "run-sim-kill";

        Task task = runner.execute(request);
        Assert.assertTrue(runner.killTask(task.taskId));

        Task killed = runner.getTask(task.taskId);
        Assert.assertNotNull(killed);
        Assert.assertEquals(TaskStatus.KILLED, killed.status);
        Assert.assertFalse(killed.alive);
        Assert.assertEquals(Integer.valueOf(-9), killed.exitCode);

        runner.shutdown();
    }
}
