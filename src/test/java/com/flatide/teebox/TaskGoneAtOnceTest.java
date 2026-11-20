package com.flatide.teebox;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskStatus;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

/**
 * A task whose process is gone before the runner first looks at it. A command that takes a few
 * milliseconds usually is, but it is a race; here the runner is held back until the process has
 * ended, so its first look always finds the process gone.
 *
 * <p>What the runner then makes of the task is what it makes of one that ends later: the exit
 * code if there is one, {@code lost} if there is none. Without an exit code, starting the task
 * used to fail after two seconds with "Detached task did not reach a controllable running state":
 * no task was recorded, and what the command had printed was dropped.
 */
public class TaskGoneAtOnceTest {

    @Test
    public void aTaskGoneAtOnceWithAnExitCodeIsEndedByIt() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = runnerThatLooksLate();
        try {
            Task task = runner.execute(request("echo out; exit 3"));

            Assert.assertFalse("found gone at the first look", task.alive);
            Assert.assertEquals(TaskStatus.FAILED, task.status);
            Assert.assertEquals(Integer.valueOf(3), task.exitCode);
            Assert.assertNotNull(task.endTime);
            Assert.assertEquals("out\n", runner.getCombinedOutput(task.taskId));
        } finally {
            runner.shutdown();
        }
    }

    /**
     * No exit code, because the command puts a directory where the code would be written
     * ({@code $0} is the task's command file; the exit code file is beside it).
     */
    @Test
    public void aTaskGoneAtOnceWithoutAnExitCodeIsLostNotRefused() throws Exception {
        assumeUnix();
        UnixTaskRunner runner = runnerThatLooksLate();
        try {
            Task task = runner.execute(request("echo out; mkdir \"$(dirname \"$0\")/exit.code\""));

            Assert.assertFalse("found gone at the first look", task.alive);
            Assert.assertEquals(TaskStatus.LOST, task.status);
            Assert.assertNull(task.exitCode);
            Assert.assertNotNull(task.endTime);
            Assert.assertSame("the task is recorded", task, runner.getTask(task.taskId));
            String output = runner.getCombinedOutput(task.taskId);
            Assert.assertTrue("what the command printed is kept: " + output, output.startsWith("out\n"));
            Assert.assertTrue("and what the shell said about the code it could not write: " + output,
                    output.contains("exit.code"));
        } finally {
            runner.shutdown();
        }
    }

    // ===================== helpers =====================

    private static void assumeUnix() {
        Assume.assumeFalse("needs a Unix shell and process control",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win"));
    }

    private static UnixTaskRunner runnerThatLooksLate() throws Exception {
        File dir = Files.createTempDirectory("teebox-task-gone-at-once").toFile();
        return new UnixTaskRunner(dir.getAbsolutePath()) {
            @Override
            void afterLaunch(Task task) {
                long deadline = System.currentTimeMillis() + 10000L;
                while (System.currentTimeMillis() < deadline
                        && ProcessHandle.of(task.pid).map(ProcessHandle::isAlive).orElse(Boolean.FALSE).booleanValue()) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
    }

    /** A request as SHELL makes it: the two streams merged. */
    private static TaskRequest request(String command) {
        TaskRequest request = new TaskRequest();
        request.command = command;
        request.runId = "run-gone-at-once";
        request.mergeErrorToStdout = true;
        return request;
    }
}
