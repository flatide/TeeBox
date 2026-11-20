package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * A purged run is gone from memory and from the disk together. A run that is out of memory while
 * its file is still there is one no sweep looks at again: the file stays until the next start,
 * which reads it back as a run.
 */
public class RunPurgeTest {

    private static final long DAY_MS = 24L * 3600_000L;

    /**
     * A flush that has picked a run up and then has to wait for the purge of that same run used to
     * save the run when its turn came: the file the purge had just deleted was written again, for
     * a run that was no longer registered. The flush now looks again once it has the run, and a
     * run that is gone is not saved — nor is the save tried, to fail on the missing file and
     * leave the run reported as one that waits to be saved.
     */
    @Test
    public void aSaveThatWaitedForThePurgeDoesNotBringTheFileBack() throws Exception {
        File dataDir = tempDir();
        final RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 0L);
        registry.register(endedRun("r1"));
        registry.maintainRuns();   // archived; a later sweep purges
        RunInfo run = registry.getRawRun("r1");
        Assert.assertTrue(run.archived);
        registry.appendLog(run, true, "a line still to be saved");
        File file = new File(new File(dataDir, "runs"), "r1.json");
        Assert.assertTrue(file.exists());

        Thread flush = new Thread(new Runnable() {
            @Override
            public void run() {
                registry.flushDirty();
            }
        }, "flush");
        // This thread holds the run's monitor. The flush picks the run up and waits for it; the
        // purge runs on this thread, which enters the monitor again, and is done before the flush
        // gets its turn.
        synchronized (run) {
            flush.start();
            awaitBlocked(flush);
            Assert.assertEquals(Arrays.asList("r1"), registry.maintainRuns());
            Assert.assertFalse(file.exists());
        }
        flush.join(10000L);
        Assert.assertFalse("the flush did not return", flush.isAlive());

        Assert.assertNull(registry.getRun("r1"));
        Assert.assertFalse("the file of a purged run was written again", file.exists());
        Assert.assertTrue("a save of the purged run was tried, and left it waiting for another: "
                + runIds(registry, "dirtyRunIds"), runIds(registry, "dirtyRunIds").isEmpty());
        Assert.assertTrue("a save of the purged run was tried, and it was reported as unsaved: "
                + runIds(registry, "unsavedRunIds"), runIds(registry, "unsavedRunIds").isEmpty());
        Assert.assertEquals("a start finds no such run", 0,
                new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS).countRuns(null));
    }

    /**
     * The runs directory can no longer be searched, so the file cannot even be looked at. Asking
     * whether the file exists answers "no" then, which the purge took for "already gone": the run
     * left memory with its file still on the disk. (Taking write permission away is another case:
     * the file is seen, and the delete fails — see {@code RunSaveFailureTest}.)
     */
    @Test
    public void aPurgeThatCannotReachTheFileIsNotCountedAsDone() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 0L);
        registry.register(endedRun("r1"));
        registry.register(endedRun("r2"));
        registry.maintainRuns();   // archived; a later sweep purges
        File runsDir = new File(dataDir, "runs");

        Assert.assertTrue(runsDir.setExecutable(false));
        try {
            Assert.assertTrue("nothing was purged", registry.maintainRuns().isEmpty());
        } finally {
            runsDir.setExecutable(true);
        }
        Assert.assertNotNull("a run whose file is still there is still registered", registry.getRun("r1"));
        Assert.assertNotNull(registry.getRun("r2"));
        Assert.assertTrue(new File(runsDir, "r1.json").exists());
        Assert.assertTrue(new File(runsDir, "r2.json").exists());

        Assert.assertEquals(2, registry.maintainRuns().size());
        Assert.assertNull(registry.getRun("r1"));
        Assert.assertNull(registry.getRun("r2"));
        Assert.assertFalse(new File(runsDir, "r1.json").exists());
        Assert.assertFalse(new File(runsDir, "r2.json").exists());
    }

    // ===================== helpers =====================

    private static void assumePermissionsApply() {
        Assume.assumeFalse("needs POSIX permission semantics",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-run-purge").toFile();
    }

    private static RunInfo endedRun(String runId) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.COMPLETED;
        run.createdAt = 1L;
        run.endedAt = Long.valueOf(System.currentTimeMillis() - 60_000L);
        run.properties.put("customer", "홍길동");   // a Korean name
        return run;
    }

    /** One of the registry's own sets of run ids (what it has waiting, what it has reported). */
    private static Set<?> runIds(RunRegistry registry, String field) throws Exception {
        Field declared = RunRegistry.class.getDeclaredField(field);
        declared.setAccessible(true);
        return (Set<?>) declared.get(registry);
    }

    /** Waits until the thread stands at a monitor another thread holds. */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (thread.getState() != Thread.State.BLOCKED) {
            Assert.assertTrue("the thread never came to wait for the monitor: " + thread.getState(),
                    System.currentTimeMillis() < deadline);
            Thread.sleep(2L);
        }
    }
}
