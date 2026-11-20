package com.flatide.teebox;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a cancel request is told. Once the reason is latched a worker can carry the cancel out by
 * itself — a run that leaves the queue in that moment aborts as it starts — and the request that
 * caused it must then be answered as a cancel, not as "the run had already finished" (the API's
 * 409). The answer used to depend on whether the caller had looked at the run before the worker
 * was done with it.
 */
public class CancelAnswerTest {

    @Test
    public void aCancelTheWorkerCarriedOutIsAnsweredAsACancel() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel-answer").toFile();
        final AtomicReference<String> target = new AtomicReference<String>();
        final CountDownLatch latched = new CountDownLatch(1);
        RunManager runManager = new RunManager(dataDir, 1) {
            @Override
            void afterCancelLatched(String runId) {
                if (!runId.equals(target.get())) {
                    return;
                }
                latched.countDown();
                // Held here until a worker has taken the run and ended it on the latched reason.
                long deadline = System.currentTimeMillis() + 10000L;
                while (System.currentTimeMillis() < deadline && getRun(runId).status != RunStatus.CANCELLED) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
        String blockerId = null;
        try {
            runManager.registerScriptVersion("spin", "1", "loop true infinite do\nend\n", "spin", Arrays.asList("test"), true);
            runManager.registerScriptVersion("sleeper", "1", "SLEEP(600000)\n", "sleeper", Arrays.asList("test"), true);

            // The one worker is busy, so the next run waits in the queue.
            blockerId = runManager.submit(request("spin")).runId;
            waitForStatus(runManager, blockerId, RunStatus.RUNNING);
            final String queuedId = runManager.submit(request("sleeper")).runId;
            Assert.assertEquals(RunStatus.QUEUED, runManager.getRun(queuedId).status);
            target.set(queuedId);

            final AtomicReference<Object> answer = new AtomicReference<Object>();
            final RunManager manager = runManager;
            Thread canceller = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        answer.set(Boolean.valueOf(manager.cancelRun(queuedId, "Cancelled by test")));
                    } catch (RuntimeException e) {
                        answer.set(e);
                    }
                }
            }, "canceller");
            canceller.start();
            Assert.assertTrue("the cancel never latched its reason", latched.await(10, TimeUnit.SECONDS));

            // Free the worker: it takes the queued run, finds the reason and ends the run as cancelled.
            Assert.assertTrue(runManager.cancelRun(blockerId, "Cancelled by test (blocker)"));
            canceller.join(20000L);
            Assert.assertFalse("the cancel call did not return", canceller.isAlive());

            RunInfo cancelled = runManager.getRun(queuedId);
            Assert.assertEquals(RunStatus.CANCELLED, cancelled.status);
            Assert.assertEquals("the run ended on this request's reason", "Cancelled by test", cancelled.errorMessage);
            Assert.assertEquals("and the request is told so", Boolean.TRUE, answer.get());
        } finally {
            if (blockerId != null) {
                runManager.cancelRun(blockerId, "cleanup");
            }
            if (target.get() != null) {
                runManager.cancelRun(target.get(), "cleanup");
            }
            runManager.shutdown();
        }
    }

    /** A run that had finished before the request is still answered as "nothing to cancel". */
    @Test
    public void aRunThatHadFinishedIsNotAnsweredAsACancel() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-cancel-answer").toFile();
        RunManager runManager = new RunManager(dataDir, 1);
        try {
            runManager.registerScriptVersion("quick", "1", "return 1\n", "quick", Arrays.asList("test"), true);
            String runId = runManager.submit(request("quick")).runId;
            waitForStatus(runManager, runId, RunStatus.COMPLETED);

            Assert.assertFalse(runManager.cancelRun(runId, "too late"));
            Assert.assertFalse(runManager.cancelRun("no-such-run", "unknown"));
            Assert.assertEquals(RunStatus.COMPLETED, runManager.getRun(runId).status);

            // The refused request left no reason behind for the script's next run to end on.
            String nextId = runManager.submit(request("quick")).runId;
            waitForStatus(runManager, nextId, RunStatus.COMPLETED);
        } finally {
            runManager.shutdown();
        }
    }

    private static RunRequest request(String scriptId) {
        RunRequest request = new RunRequest();
        request.scriptId = scriptId;
        return request;
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
