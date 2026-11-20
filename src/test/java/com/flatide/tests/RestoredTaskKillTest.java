package com.flatide.tests;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskStatus;
import com.flatide.teebox.ManagedTaskEngine;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * A task that a restarted server found still running is ended as a whole when it is killed. Its
 * process group was asked to end and then judged by its first process alone — the shell that
 * runs the command, which ends at once: a child that takes its time over SIGTERM, or ignores it,
 * was never made to end, and went on running under a task recorded as killed.
 *
 * <p>For tasks that lead a process group of their own, as {@code setsid} gives them (production).
 * Where there is no {@code setsid} these tests are skipped.
 */
public class RestoredTaskKillTest {

    @Test
    public void aRestoredTaskThatIsKilledIsEndedWithItsChildren() throws Exception {
        killARestoredTask("sh -c 'trap \"\" TERM; exec sleep 300' & echo $! > child.pid; wait");
    }

    /**
     * The second it is given to end is the group's, not its first process's. A child that takes
     * a moment to finish what it is doing when it is asked — and then ends by itself — is left
     * that moment: it was made to end the instant the first process was gone.
     */
    @Test
    public void aChildThatFinishesWithinTheGraceIsNotMadeToEnd() throws Exception {
        File work = killARestoredTask("sh -c 'trap \"sleep 0.4; echo finished > finished.txt; exit 0\" TERM;"
                + " while :; do sleep 0.1; done' & echo $! > child.pid; wait");
        if (work != null) {
            Assert.assertTrue("the child was made to end before it had finished", new File(work, "finished.txt").isFile());
        }
    }

    /** What stays as it was: a restored task whose processes all end on being asked is killed. */
    @Test
    public void aRestoredTaskThatEndsOnBeingAskedIsKilled() throws Exception {
        killARestoredTask("sleep 300 & echo $! > child.pid; wait");
    }

    /** @return the directory the command ran in */
    private static File killARestoredTask(String command) throws Exception {
        Assume.assumeFalse("launches tasks with a Unix shell",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        File dataDir = Files.createTempDirectory("teebox-restored-task-kill").toFile();
        File work = new File(dataDir, "work");
        Assert.assertTrue(work.mkdirs());
        ManagedTaskEngine first = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a");
        TaskRequest request = new TaskRequest();
        request.runId = "run-1";
        request.cwd = work.getAbsolutePath();
        request.command = command;
        Task task = first.execute(request);
        ProcessHandle child = childOf(work);
        ManagedTaskEngine restarted = null;
        try {
            Assume.assumeTrue("tasks have no process group of their own here (no setsid)",
                    task.pgid > 0 && task.pgid == task.pid);
            first.shutdown();   // the server stops; the task goes on
            restarted = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");
            restarted.init();
            Assert.assertTrue("found running by the server that started", restarted.getTask(task.taskId).alive);

            Assert.assertTrue(restarted.killTask(task.taskId));
            long deadline = System.currentTimeMillis() + 3000L;
            while (child.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            Assert.assertFalse("the task was recorded as killed while a process of it went on running", child.isAlive());
            Assert.assertEquals(TaskStatus.KILLED, restarted.getTask(task.taskId).status);
            return work;
        } finally {
            child.destroyForcibly();
            ProcessHandle.of(task.pid).ifPresent(process -> {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            });
            if (restarted != null) {
                restarted.shutdown();
            }
        }
    }

    /** The child whose pid the command wrote down. */
    private static ProcessHandle childOf(File work) throws Exception {
        File pidFile = new File(work, "child.pid");
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (pidFile.isFile()) {
                String text = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    ProcessHandle child = ProcessHandle.of(Long.parseLong(text)).orElse(null);
                    if (child != null) {
                        Thread.sleep(200L);   // past its exec: it ignores the signal from here on
                        return child;
                    }
                }
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("the command did not start its child");
    }
}
