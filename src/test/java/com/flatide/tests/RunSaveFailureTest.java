package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a run's file and its record in memory look like after the disk refused a read or a write.
 * The file is the only copy of an archived run's input, so nothing may replace it on a guess, and
 * a step that could not be saved has to be taken again rather than counted as done.
 *
 * <p>The disk is made to refuse by taking a permission away, which needs POSIX and a user other
 * than root.
 */
public class RunSaveFailureTest {

    private static final long DAY_MS = 24L * 3600_000L;

    /**
     * An archived run's file could not be read when the run was saved again. The save used to take
     * "could not read" for "no input" and wrote the record back with an empty one — over the only
     * copy there was.
     */
    @Test
    public void anUnreadableFileIsNotReplacedByARecordWithoutItsInput() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        Map<String, Object> input = input();
        registry.register(endedRun("r1", input));
        registry.maintainRuns();
        Assert.assertTrue(registry.getRun("r1").archived);
        File file = new File(new File(dataDir, "runs"), "r1.json");

        Assert.assertTrue(file.setReadable(false));
        try {
            registry.appendLog(registry.getRawRun("r1"), true, "a line after archiving");   // something to save
            registry.flushDirty();   // must neither throw nor write
            try {
                registry.getRunWithInput("r1");
                Assert.fail("a detail view must not show an unread input as an empty one");
            } catch (RunStore.RunFileUnreadableException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("r1"));
            }
        } finally {
            file.setReadable(true);
        }
        Assert.assertFalse("nothing was written over the file", text(file).contains("a line after archiving"));
        Assert.assertEquals("it still holds the input", input, registry.getRunWithInput("r1").properties);

        // The run was left waiting for its save: the next flush writes it, input and all.
        registry.flushDirty();
        Assert.assertTrue(text(file).contains("a line after archiving"));
        Assert.assertEquals(input, registry.getRunWithInput("r1").properties);
    }

    /**
     * The same for a file that is there and no longer a run record — cut short by something
     * outside the server. Its input may still be in what is left of it; "no input" would be a
     * guess, and the save that followed the guess wrote over the file.
     */
    @Test
    public void aDamagedFileIsNotReplacedByARecordWithoutItsInput() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        registry.register(endedRun("r1", input()));
        registry.maintainRuns();
        Assert.assertTrue(registry.getRun("r1").archived);
        File file = new File(new File(dataDir, "runs"), "r1.json");
        String whole = text(file);
        String cut = whole.substring(0, whole.indexOf("\"count\"") + 10);   // ends inside the input
        Files.write(file.toPath(), cut.getBytes("UTF-8"));

        registry.appendLog(registry.getRawRun("r1"), true, "a line after archiving");
        registry.flushDirty();
        Assert.assertEquals("the damaged file is left as it is", cut, text(file));
        try {
            registry.getRunWithInput("r1");
            Assert.fail("a detail view must not show an unread input as an empty one");
        } catch (RunStore.RunFileUnreadableException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("not JSON"));
        }

        // Put right by hand, the file is saved over again: the input was never lost.
        Files.write(file.toPath(), whole.getBytes("UTF-8"));
        registry.flushDirty();
        Assert.assertTrue(text(file).contains("a line after archiving"));
        Assert.assertEquals(input(), registry.getRunWithInput("r1").properties);
    }

    /**
     * A run file that cannot be deleted. The run used to be taken out of memory before its file
     * was deleted: the failure left the file behind until the next start, and ended the sweep.
     */
    @Test
    public void aPurgeThatCouldNotDeleteTheFileIsTakenAgainByTheNextSweep() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 0L);
        registry.register(endedRun("r1", input()));
        registry.register(endedRun("r2", input()));
        registry.maintainRuns();   // archived; a later sweep purges
        File runsDir = new File(dataDir, "runs");

        Assert.assertTrue(runsDir.setWritable(false));
        try {
            Assert.assertTrue("nothing was purged", registry.maintainRuns().isEmpty());
        } finally {
            runsDir.setWritable(true);
        }
        Assert.assertNotNull("a run whose file is still there is still registered", registry.getRun("r1"));
        Assert.assertNotNull(registry.getRun("r2"));

        Assert.assertEquals(2, registry.maintainRuns().size());
        Assert.assertNull(registry.getRun("r1"));
        Assert.assertNull(registry.getRun("r2"));
        Assert.assertFalse(new File(runsDir, "r1.json").exists());
        Assert.assertFalse(new File(runsDir, "r2.json").exists());
    }

    /**
     * The first save of an archived record failed. The run used to be marked archived in memory
     * before that save, so no later sweep tried again: its file went on saying "not archived",
     * while memory had already dropped its threads and trimmed its output, and kept holding the
     * input the file was meant to take over. The failed save also ended the sweep for every run
     * after it.
     */
    @Test
    public void anArchiveThatCouldNotBeSavedIsTakenAgainByTheNextSweep() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 2, 2, 0L, 7 * DAY_MS);
        Map<String, Object> input = input();
        RunInfo run = endedRun("r1", input);
        run.stdoutLines.addAll(Arrays.asList("one", "two", "three"));
        registry.register(run);
        registry.register(endedRun("r2", input));
        File runsDir = new File(dataDir, "runs");

        Assert.assertTrue(runsDir.setWritable(false));
        try {
            registry.maintainRuns();   // one run that cannot be saved must not stop the sweep
        } finally {
            runsDir.setWritable(true);
        }
        RunInfo unsaved = registry.getRawRun("r1");
        Assert.assertFalse("not archived until its archived record is on disk", unsaved.archived);
        Assert.assertEquals("memory keeps the input the file would have taken over", input, unsaved.properties);
        Assert.assertEquals(Arrays.asList("one", "two", "three"), unsaved.stdoutLines);
        Assert.assertFalse(registry.getRawRun("r2").archived);

        registry.maintainRuns();

        for (String runId : Arrays.asList("r1", "r2")) {
            Assert.assertTrue(runId, registry.getRawRun(runId).archived);
            Assert.assertTrue(runId, registry.getRawRun(runId).properties.isEmpty());
            Assert.assertEquals(runId, input, registry.getRunWithInput(runId).properties);
            Assert.assertTrue(runId, text(new File(runsDir, runId + ".json")).contains("\"archived\": true"));
        }
        Assert.assertEquals("trimmed to the archived tail only once it is saved",
                Arrays.asList("two", "three"), registry.getRawRun("r1").stdoutLines);
    }

    /**
     * A status that could not be saved is saved by a later flush. It used to stay in memory only:
     * the file kept the earlier status, which a restart then read as "the server stopped".
     */
    @Test
    public void aStatusThatCouldNotBeSavedIsSavedByALaterFlush() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);
        RunInfo run = new RunInfo();
        run.runId = "r1";
        run.scriptId = "s";
        run.status = RunStatus.QUEUED;
        run.createdAt = 1L;
        registry.register(run);
        File runsDir = new File(dataDir, "runs");

        Assert.assertTrue(runsDir.setWritable(false));
        try {
            registry.markCancelled(run, "Cancelled by test");   // the cancel stands; its save waits
            registry.flushDirty();                               // still refused: must not throw
        } finally {
            runsDir.setWritable(true);
        }
        Assert.assertEquals(RunStatus.CANCELLED, registry.getRun("r1").status);
        Assert.assertTrue(text(new File(runsDir, "r1.json")).contains("\"status\": \"QUEUED\""));

        registry.flushDirty();

        String saved = text(new File(runsDir, "r1.json"));
        Assert.assertTrue(saved, saved.contains("\"status\": \"CANCELLED\""));
        Assert.assertTrue(saved, saved.contains("Cancelled by test"));
    }

    /**
     * A debug run is executed again under the id it has: its record is reset to QUEUED for the
     * next attempt. That record is saved first, and a reset that cannot be saved is refused with
     * the run as it was — in memory and in its file. The record used to be reset in memory before
     * the save: the refused attempt left a QUEUED run that nothing would ever execute, so every
     * later attempt was refused as "still QUEUED", and the next flush wrote that over the result
     * the file still had.
     */
    @Test
    public void aDebugResetThatCouldNotBeSavedLeavesTheRunAsItEnded() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);
        Map<String, Object> input = input();
        registry.register(endedDebugRun("d1", input));
        File runsDir = new File(dataDir, "runs");
        File file = new File(runsDir, "d1.json");
        String asItEnded = text(file);

        Assert.assertTrue(runsDir.setWritable(false));
        try {
            registry.saveDebugReset(registry.findDebugRun("source"), nextAttempt("d1"));
            Assert.fail("a reset that cannot be saved is refused");
        } catch (RuntimeException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("d1"));
        } finally {
            runsDir.setWritable(true);
        }
        registry.flushDirty();   // the disk takes writes again: nothing may be waiting to go over the file

        RunInfo kept = registry.getRun("d1");
        Assert.assertEquals(RunStatus.COMPLETED, kept.status);
        Assert.assertEquals(Integer.valueOf(42), kept.resultData);
        Assert.assertEquals("42", kept.resultSummary);
        Assert.assertEquals(Arrays.asList("the first attempt"), kept.stdoutLines);
        Assert.assertEquals(input, kept.properties);
        Assert.assertNotNull(kept.endedAt);
        Assert.assertEquals("a refused reset starts no attempt", 0L, kept.attempt);
        Assert.assertEquals("the file is as the first attempt left it", asItEnded, text(file));

        // The run is still one that can be taken for another attempt: saved first, memory as it
        // was until the second step.
        RunInfo existing = registry.findDebugRun("source");
        registry.saveDebugReset(existing, nextAttempt("d1"));
        Assert.assertTrue(text(file), text(file).contains("\"status\": \"QUEUED\""));
        Assert.assertEquals("memory waits for the second step", RunStatus.COMPLETED, registry.getRun("d1").status);
        Assert.assertEquals(0L, registry.getRun("d1").attempt);

        RunInfo reset = registry.completeDebugReset(existing, nextAttempt("d1"));
        Assert.assertSame("the registry's own run", existing, reset);
        Assert.assertEquals(RunStatus.QUEUED, reset.status);
        Assert.assertNull(reset.resultData);
        Assert.assertTrue(reset.stdoutLines.isEmpty());
        Assert.assertEquals("the reset that is completed starts the next attempt", 1L, reset.attempt);
        registry.flushDirty();
        String saved = text(file);
        Assert.assertTrue(saved, saved.contains("\"status\": \"QUEUED\""));
        Assert.assertFalse(saved, saved.contains("the first attempt"));
        Assert.assertFalse("which attempt a record is at is not part of what is saved: " + saved,
                saved.contains("\"attempt\""));
    }

    /**
     * The record of the next attempt is on the disk, and the attempt is then called off (the ended
     * attempt's tasks could not be cleared, say). The file must say again what the run still is.
     * When that save is refused as well, the flush makes up for it.
     */
    @Test
    public void aDebugResetThatIsCalledOffPutsTheFileBack() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);
        registry.register(endedDebugRun("d1", input()));
        File runsDir = new File(dataDir, "runs");
        File file = new File(runsDir, "d1.json");
        String asItEnded = text(file);
        RunInfo existing = registry.findDebugRun("source");

        registry.saveDebugReset(existing, nextAttempt("d1"));
        Assert.assertNotEquals(asItEnded, text(file));
        registry.abandonDebugReset(existing);
        Assert.assertEquals("the file says again what the run is", asItEnded, text(file));
        Assert.assertEquals(RunStatus.COMPLETED, registry.getRun("d1").status);
        Assert.assertEquals(0L, registry.getRun("d1").attempt);

        // The same, with the disk refusing the save that puts the file back.
        registry.saveDebugReset(existing, nextAttempt("d1"));
        String reset = text(file);
        Assert.assertTrue(runsDir.setWritable(false));
        try {
            registry.abandonDebugReset(existing);   // must not throw
        } finally {
            runsDir.setWritable(true);
        }
        Assert.assertEquals(reset, text(file));
        Assert.assertEquals("memory was never anything else", RunStatus.COMPLETED, registry.getRun("d1").status);
        registry.flushDirty();
        Assert.assertEquals("the flush puts the file back", asItEnded, text(file));

        // An archived debug run: its input is in the file only, and comes back with the record.
        RunRegistry archiving = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        archiving.maintainRuns();
        RunInfo archived = archiving.findDebugRun("source");
        Assert.assertTrue(archived.archived);
        Assert.assertTrue(archived.properties.isEmpty());
        String asArchived = text(file);
        archiving.saveDebugReset(archived, nextAttempt("d1"));
        archiving.abandonDebugReset(archived);
        Assert.assertEquals(asArchived, text(file));
        Assert.assertEquals(input(), archiving.getRunWithInput("d1").properties);
    }

    // ===================== helpers =====================

    /** A debug run of the Run "source" that has ended with a result and a line of output. */
    private static RunInfo endedDebugRun(String runId, Map<String, Object> input) {
        RunInfo ended = endedRun(runId, input);
        ended.debug = true;
        ended.debugOf = "source";
        ended.origin = "debug";
        ended.hasExplicitReturn = true;
        ended.resultData = Integer.valueOf(42);
        ended.resultSummary = "42";
        ended.stdoutLines.add("the first attempt");
        return ended;
    }

    /** What the next attempt of a debug run is prepared as: the same id, nothing run yet. */
    private static RunInfo nextAttempt(String runId) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.QUEUED;
        run.createdAt = System.currentTimeMillis();
        run.maxIterations = 1000;
        run.iterationLimitBehavior = "error";
        run.origin = "debug";
        run.debug = true;
        run.debugOf = "source";
        run.properties.putAll(input());
        return run;
    }

    private static void assumePermissionsApply() {
        Assume.assumeFalse("needs POSIX permission semantics",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-run-save-failure").toFile();
    }

    private static Map<String, Object> input() {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("customer", "홍길동");   // a Korean name
        input.put("count", 5);
        return input;
    }

    private static RunInfo endedRun(String runId, Map<String, Object> input) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.COMPLETED;
        run.createdAt = 1L;
        run.endedAt = Long.valueOf(System.currentTimeMillis() - 60_000L);   // due for archiving, far from purge
        run.properties.putAll(input);
        return run;
    }

    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }
}
