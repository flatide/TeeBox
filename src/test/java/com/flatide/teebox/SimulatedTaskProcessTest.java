package com.flatide.teebox;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * A simulated task — what a platform without a Unix shell gets — has no process: the pid it
 * carries is a number its runner made up. Killing such a task must not signal whatever real
 * process has that number. After a restart it could: the kill of a task read from the disk goes
 * by the pid written down — and so does a debug Restart, which ends the earlier attempt's tasks.
 *
 * <p>Run here, on a platform where a process can be made and watched, with the simulating runner
 * given to the engine.
 */
public class SimulatedTaskProcessTest {

    @Test
    public void killingASimulatedTaskSignalsNoProcess() throws Exception {
        Assume.assumeFalse("makes a process with a Unix shell",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        File dataDir = Files.createTempDirectory("teebox-simulated-task").toFile();
        // A process of somebody else's, whose number the simulated tasks will carry.
        Process bystander = new ProcessBuilder("sh", "-c", "sleep 300 & wait").start();
        try {
            Thread.sleep(300L);
            ProcessHandle process = bystander.toHandle();
            ProcessHandle child = process.children().findFirst().orElseThrow(AssertionError::new);

            ManagedTaskEngine first = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a", -1L, -1L,
                    new SimulatedTaskRunner(dataDir.getAbsolutePath()));
            String killed = simulatedTaskWithThePidOf(process, first, "run-1");
            String cleared = simulatedTaskWithThePidOf(process, first, "run-2");
            first.shutdown();

            // A server that started again: it knows these tasks from the disk only.
            ManagedTaskEngine restarted = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b", -1L, -1L,
                    new SimulatedTaskRunner(dataDir.getAbsolutePath()));
            try {
                restarted.killTask(killed);
                restarted.clearRunTasks("run-2");   // what a debug Restart does with the earlier attempt's tasks
                Thread.sleep(300L);
                Assert.assertTrue("a process that only had a simulated task's number was signalled", process.isAlive());
                Assert.assertTrue("and what it had started", child.isAlive());
                Assert.assertNull(restarted.getTask(cleared));
            } finally {
                restarted.shutdown();
            }
        } finally {
            bystander.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            bystander.destroyForcibly();
        }
    }

    /** A simulated task as it is on the disk when its made-up number is that of a real process. */
    private static String simulatedTaskWithThePidOf(ProcessHandle process, ManagedTaskEngine engine, String runId)
            throws Exception {
        TaskRequest request = new TaskRequest();
        request.command = "echo simulated";
        request.runId = runId;
        Task task = engine.execute(request);
        File meta = new File(engine.getTaskDir(task.taskId), "meta.json");
        String text = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(text, text.contains("\"pid\": " + task.pid + ","));
        // The pid only. (A simulated task's group number is its pid; the process here is no
        // group's first, so the number is left: the kill then goes by the pid, as it would
        // wherever a group cannot be signalled.)
        text = text.replace("\"pid\": " + task.pid + ",", "\"pid\": " + process.pid() + ",");
        Files.write(meta.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return task.taskId;
    }
}
