package com.flatide.teebox;

import com.flatide.propertee2.task.TaskInfo;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Stop is for the attempt it was asked of. A debug run keeps its run id from one attempt to the
 * next, so a Restart puts another attempt under the id a Stop is still holding — and the Stop, sent
 * to one session, must not end the session that replaced it. Cancelling went by the run id alone:
 * a Stop that had latched its reason and then came back to the run found the new attempt running
 * there and aborted that.
 *
 * <p>In these tests the Stop of the first session is held on its way — before it first looks at
 * the run, or right after it has latched its reason — and let go once the second session is there.
 */
public class DebugStopTargetTest {

    private static final String SCRIPT =
        "a = 1\n" +
        "b = a + 1\n" +
        "return b\n";

    /** The Stop comes back while the second attempt is paused: that attempt goes on to its end. */
    @Test
    public void aStopForOneAttemptDoesNotEndTheAttemptThatReplacedIt() throws Exception {
        Fixture fixture = new Fixture("propertee-teebox-debug-stop-target");
        try {
            DebugSessionManager.Session first = fixture.debug.open(fixture.sourceRunId, "tester", null);
            waitForState(first, DebugSessionManager.Session.PAUSED);
            Thread stop = fixture.stopHeldAfterItsLatch(first);

            // Restart: the first attempt ends, the run is reset under its id, the second attempt
            // pauses at its entry.
            DebugSessionManager.Session second = fixture.debug.restart(first.sessionId, "tester");
            Assert.assertEquals("the same run, executed again", first.runId, second.runId);
            Assert.assertNotEquals(first.sessionId, second.sessionId);
            waitForState(second, DebugSessionManager.Session.PAUSED);
            Assert.assertEquals("the first attempt ended on the Stop that was asked of it",
                "Ended from the debugger", first.terminalRunSnapshot.errorMessage);

            Assert.assertEquals("the Stop is answered as carried out: its attempt ended on it",
                Boolean.TRUE, fixture.letGo(stop));

            // The second attempt goes on to its end. (Were it ended by the first session's Stop,
            // this command would be refused or dropped, and the run would end CANCELLED.)
            try {
                fixture.debug.command(second.sessionId, "continue", null);
            } catch (IllegalStateException alreadyStopped) {
                // told below, by how the run ended
            }
            waitForState(second, DebugSessionManager.Session.ENDED);
            RunInfo ended = fixture.runManager.getRun(second.runId);
            Assert.assertEquals("the second attempt was ended by the Stop of the first: " + ended.errorMessage,
                RunStatus.COMPLETED, ended.status);
            Assert.assertEquals(Integer.valueOf(2), ended.resultData);
        } finally {
            fixture.close();
        }
    }

    /**
     * The Stop comes back after the second attempt has ended. What it is told is about its own
     * attempt, which ended on it — not about the run as it is now, which shows how the second
     * attempt ended.
     */
    @Test
    public void aStopIsAnsweredForItsOwnAttempt() throws Exception {
        Fixture fixture = new Fixture("propertee-teebox-debug-stop-answer");
        try {
            DebugSessionManager.Session first = fixture.debug.open(fixture.sourceRunId, "tester", null);
            waitForState(first, DebugSessionManager.Session.PAUSED);
            Thread stop = fixture.stopHeldAfterItsLatch(first);

            DebugSessionManager.Session second = fixture.debug.restart(first.sessionId, "tester");
            waitForState(second, DebugSessionManager.Session.PAUSED);
            fixture.debug.command(second.sessionId, "continue", null);
            waitForState(second, DebugSessionManager.Session.ENDED);
            Assert.assertEquals(RunStatus.COMPLETED, fixture.runManager.getRun(second.runId).status);

            Assert.assertEquals("the first attempt ended on this Stop, whatever the run shows now",
                Boolean.TRUE, fixture.letGo(stop));
            RunInfo shown = fixture.runManager.getRun(second.runId);
            Assert.assertEquals(RunStatus.COMPLETED, shown.status);
            Assert.assertEquals(Integer.valueOf(2), shown.resultData);
        } finally {
            fixture.close();
        }
    }

    /**
     * The first attempt finishes by itself while the Stop is held — a completion wins over a late
     * cancel — and is then restarted. The Stop was not carried out and is told so; the second
     * attempt is not touched.
     */
    @Test
    public void aStopWhoseAttemptFinishedByItselfIsNotAnsweredAsCarriedOut() throws Exception {
        Fixture fixture = new Fixture("propertee-teebox-debug-stop-late");
        try {
            DebugSessionManager.Session first = fixture.debug.open(fixture.sourceRunId, "tester", null);
            waitForState(first, DebugSessionManager.Session.PAUSED);
            Thread stop = fixture.stopHeldAfterItsLatch(first);

            fixture.debug.command(first.sessionId, "continue", null);
            waitForState(first, DebugSessionManager.Session.ENDED);
            Assert.assertEquals("the first attempt ran to its end", RunStatus.COMPLETED, first.terminalRunSnapshot.status);

            DebugSessionManager.Session second = fixture.debug.restart(first.sessionId, "tester");
            waitForState(second, DebugSessionManager.Session.PAUSED);

            Assert.assertEquals("its attempt had finished: nothing was cancelled",
                Boolean.FALSE, fixture.letGo(stop));
            fixture.debug.command(second.sessionId, "continue", null);
            waitForState(second, DebugSessionManager.Session.ENDED);
            RunInfo ended = fixture.runManager.getRun(second.runId);
            Assert.assertEquals("the second attempt was ended by the Stop of the first: " + ended.errorMessage,
                RunStatus.COMPLETED, ended.status);
        } finally {
            fixture.close();
        }
    }

    /**
     * A cancel that comes for an attempt after the run has been reset is refused at once; a cancel
     * for the attempt the run is at ends that one, on its own reason.
     */
    @Test
    public void aCancelIsForTheAttemptItNames() throws Exception {
        Fixture fixture = new Fixture("propertee-teebox-debug-attempt-cancel");
        try {
            RunManager runManager = fixture.runManager;
            DebugSessionManager.Session first = fixture.debug.open(fixture.sourceRunId, "tester", null);
            waitForState(first, DebugSessionManager.Session.PAUSED);
            DebugSessionManager.Session second = fixture.debug.restart(first.sessionId, "tester");
            waitForState(second, DebugSessionManager.Session.PAUSED);
            Assert.assertEquals(first.runId, second.runId);
            Assert.assertNotEquals("each execution under the run id is an attempt of its own",
                first.attempt, second.attempt);

            Assert.assertFalse("the first attempt is over: nothing to cancel",
                runManager.cancelRunAttempt(first.runId, first.attempt, "for the first attempt"));
            Assert.assertEquals(DebugSessionManager.Session.PAUSED, second.state);
            Assert.assertEquals(RunStatus.RUNNING, runManager.getRun(second.runId).status);

            Assert.assertTrue(runManager.cancelRunAttempt(second.runId, second.attempt, "for the second attempt"));
            waitForState(second, DebugSessionManager.Session.ENDED);
            RunInfo ended = runManager.getRun(second.runId);
            Assert.assertEquals(RunStatus.CANCELLED, ended.status);
            Assert.assertEquals("for the second attempt", ended.errorMessage);

            // By the run id, a cancel is for whatever attempt the run is at — here none is running.
            Assert.assertFalse(runManager.cancelRun(second.runId, "nothing runs"));
        } finally {
            fixture.close();
        }
    }

    /**
     * The Stop has passed its session's own check — the session had not ended — and gets to the
     * run only after the Restart. What it is for is the attempt its session ran, which the run is
     * no longer at: it ends nothing, and is told so. (Asked by the run id, it would take the
     * attempt it finds there for its own.)
     */
    @Test
    public void aStopThatReachesTheRunAfterTheRestartEndsNothing() throws Exception {
        Fixture fixture = new Fixture("propertee-teebox-debug-stop-arrives-late");
        try {
            DebugSessionManager.Session first = fixture.debug.open(fixture.sourceRunId, "tester", null);
            waitForState(first, DebugSessionManager.Session.PAUSED);
            Thread stop = fixture.stopHeldBeforeItsFirstLook(first);

            DebugSessionManager.Session second = fixture.debug.restart(first.sessionId, "tester");
            waitForState(second, DebugSessionManager.Session.PAUSED);

            Assert.assertEquals("its attempt is over: nothing was cancelled", Boolean.FALSE, fixture.letGo(stop));
            try {
                fixture.debug.command(second.sessionId, "continue", null);
            } catch (IllegalStateException alreadyStopped) {
                // told below, by how the run ended
            }
            waitForState(second, DebugSessionManager.Session.ENDED);
            RunInfo ended = fixture.runManager.getRun(second.runId);
            Assert.assertEquals("the second attempt was ended by the Stop of the first: " + ended.errorMessage,
                RunStatus.COMPLETED, ended.status);
        } finally {
            fixture.close();
        }
    }

    /**
     * The kill of an attempt's tasks runs on a thread of its own, when that thread gets to it.
     * Getting to it only after the run has gone on to its next attempt, it must kill nothing:
     * tasks go by the run id, and the tasks there now are the next attempt's.
     */
    @Test
    public void theKillOfAnAttemptsTasksLeavesTheNextAttemptsTasksAlone() throws Exception {
        Assume.assumeFalse("needs a Unix shell",
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win"));
        Fixture fixture = new Fixture("propertee-teebox-debug-late-task-kill");
        try {
            RunManager runManager = fixture.runManager;
            runManager.registerScriptVersion("sleeper", "1", "r = SHELL(\"sleep 30\")\nreturn r.ok\n",
                "sleeper", Arrays.asList("test"), true);
            RunRequest request = new RunRequest();
            request.scriptId = "sleeper";
            String sourceRunId = runManager.submit(request).runId;
            waitForLiveTask(runManager, sourceRunId);
            Assert.assertTrue(runManager.cancelRun(sourceRunId, "only its record is needed"));
            waitForStatus(runManager, sourceRunId, RunStatus.CANCELLED);

            DebugSessionManager.Session first = fixture.debug.open(sourceRunId, "tester", null);
            waitForState(first, DebugSessionManager.Session.PAUSED);
            DebugSessionManager.Session second = fixture.debug.restart(first.sessionId, "tester");
            waitForState(second, DebugSessionManager.Session.PAUSED);
            fixture.debug.command(second.sessionId, "continue", null);
            TaskInfo running = waitForLiveTask(runManager, second.runId);

            Assert.assertEquals("the first attempt's kill, come late, finds nothing of its own",
                0, runManager.killTasksOfAttempt(second.runId, first.attempt));
            Assert.assertTrue("the second attempt's command still runs",
                ProcessHandle.of(running.pid).map(ProcessHandle::isAlive).orElse(Boolean.FALSE).booleanValue());

            Assert.assertEquals("the kill of the attempt the run is at takes its task",
                1, runManager.killTasksOfAttempt(second.runId, second.attempt));
            waitForState(second, DebugSessionManager.Session.ENDED);
            RunInfo ended = runManager.getRun(second.runId);
            Assert.assertEquals(RunStatus.COMPLETED, ended.status);
            Assert.assertEquals("the script saw its command fail", Boolean.FALSE, ended.resultData);
        } finally {
            fixture.close();
        }
    }

    // ===================== helpers =====================

    /**
     * A run manager whose cancel, on the one thread chosen for it, waits — before it first looks
     * at the run, or right after its reason is latched; a debug session manager on it; and a
     * finished run to debug.
     */
    private static final class Fixture {
        private final AtomicReference<Thread> held = new AtomicReference<Thread>();
        private final AtomicReference<Thread> heldBeforeTheLook = new AtomicReference<Thread>();
        private final AtomicReference<Object> answer = new AtomicReference<Object>();
        private final CountDownLatch latched = new CountDownLatch(1);
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        final RunManager runManager;
        final DebugSessionManager debug;
        final String sourceRunId;

        Fixture(String name) throws Exception {
            File dataDir = Files.createTempDirectory(name).toFile();
            runManager = new RunManager(dataDir, 1) {
                // Whichever way the session asks for its cancel, it waits here first.
                @Override
                public boolean cancelRun(String runId, String reason) {
                    holdBeforeTheLook();
                    return super.cancelRun(runId, reason);
                }

                @Override
                public boolean cancelRunAttempt(String runId, long attempt, String reason) {
                    holdBeforeTheLook();
                    return super.cancelRunAttempt(runId, attempt, reason);
                }

                private void holdBeforeTheLook() {
                    if (Thread.currentThread() == heldBeforeTheLook.get()) {
                        arrived.countDown();
                        await(release);
                    }
                }

                @Override
                void afterCancelLatched(String runId) {
                    if (Thread.currentThread() == held.get()) {
                        latched.countDown();
                        await(release);
                    }
                }

                private void await(CountDownLatch latch) {
                    try {
                        latch.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            debug = new DebugSessionManager(runManager, 2, 0L);
            boolean ready = false;
            try {
                runManager.registerScriptVersion("steps", "1", SCRIPT, "steps", Arrays.asList("test"), true);
                RunRequest request = new RunRequest();
                request.scriptId = "steps";
                sourceRunId = runManager.submit(request).runId;
                waitForStatus(runManager, sourceRunId, RunStatus.COMPLETED);
                ready = true;
            } finally {
                if (!ready) {
                    close();
                }
            }
        }

        /** Sends the session's Stop on a thread of its own; returns once its reason is latched. */
        Thread stopHeldAfterItsLatch(DebugSessionManager.Session session) throws InterruptedException {
            return stop(session, held, latched, "the Stop never latched its reason");
        }

        /** As above, held earlier: the Stop is past its session's check and has not looked at the run. */
        Thread stopHeldBeforeItsFirstLook(DebugSessionManager.Session session) throws InterruptedException {
            return stop(session, heldBeforeTheLook, arrived, "the Stop never got to the run manager");
        }

        private Thread stop(final DebugSessionManager.Session session, AtomicReference<Thread> where,
                            CountDownLatch there, String never) throws InterruptedException {
            Thread stop = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        answer.set(debug.command(session.sessionId, "quit", null));
                    } catch (RuntimeException e) {
                        answer.set(e);
                    }
                }
            }, "held-stop");
            where.set(stop);
            stop.start();
            Assert.assertTrue(never, there.await(10, TimeUnit.SECONDS));
            return stop;
        }

        /** Lets the held Stop go on; returns what it was told ({@code accepted}). */
        Object letGo(Thread stop) throws InterruptedException {
            release.countDown();
            stop.join(20000L);
            Assert.assertFalse("the Stop did not return", stop.isAlive());
            Assert.assertTrue(String.valueOf(answer.get()), answer.get() instanceof Map);
            return ((Map<?, ?>) answer.get()).get("accepted");
        }

        void close() {
            release.countDown();
            debug.shutdown();
            runManager.shutdown();
        }
    }

    private static void waitForState(DebugSessionManager.Session session, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(session.state)) {
                return;
            }
            Thread.sleep(10L);
        }
        Assert.fail("session " + session.sessionId + " did not reach " + expected + "; last=" + session.state);
    }

    /** Waits until the run has a task whose process is running, and returns it. */
    private static TaskInfo waitForLiveTask(RunManager runManager, String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            for (TaskInfo task : runManager.listTasksForRun(runId)) {
                if (task.alive && task.pid > 0) {
                    return task;
                }
            }
            Thread.sleep(20L);
        }
        Assert.fail("run " + runId + " never had a running task");
        return null;
    }

    private static void waitForStatus(RunManager runManager, String runId, RunStatus expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        RunStatus last = null;
        while (System.currentTimeMillis() < deadline) {
            RunInfo run = runManager.getRun(runId);
            last = run != null ? run.status : null;
            if (expected == last) {
                return;
            }
            Thread.sleep(20L);
        }
        Assert.fail("run " + runId + " did not reach " + expected + "; last=" + last);
    }
}
