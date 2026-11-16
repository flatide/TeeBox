package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Run listing/counting is served from the registry's in-memory map (1.14): every non-purged run is
 * resident, so list/count/filter must touch no disk and there is no on-disk run index anymore.
 * Pins the query semantics the on-disk index used to provide (order, filters, pagination), startup
 * recovery via directory scan, and the removal of a legacy {@code runs/index.json}.
 */
public class RunRegistryListTest {

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-runregistry-test").toFile();
    }

    private static RunRegistry registry(File dataDir) {
        return new RunRegistry(dataDir, 200, 50, 20, 24L * 3600_000L, 7L * 24 * 3600_000L);
    }

    private static RunInfo run(String runId, String scriptId, RunStatus status,
                               long createdAt, boolean immediate) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = scriptId;
        run.status = status;
        run.createdAt = createdAt;
        run.immediate = immediate;
        return run;
    }

    private static List<String> ids(List<RunInfo> runs) {
        List<String> ids = new ArrayList<String>();
        for (RunInfo run : runs) {
            ids.add(run.runId);
        }
        return ids;
    }

    @Test
    public void listingFiltersSortsAndPaginatesFromMemory() throws Exception {
        RunRegistry registry = registry(tempDir());
        RunInfo r1 = run("r1", "alpha", RunStatus.COMPLETED, 1, false);
        r1.origin = "api";
        registry.register(r1);
        RunInfo r2 = run("r2", "alpha", RunStatus.RUNNING, 2, false);
        r2.origin = "ui";
        registry.register(r2);
        RunInfo r3 = run("r3", "beta", RunStatus.COMPLETED, 3, true);
        r3.origin = "debug";
        registry.register(r3);
        RunInfo r4 = run("r4", "beta", RunStatus.FAILED, 4, false);
        r4.origin = "API"; // case-insensitive filter
        registry.register(r4);
        // Same createdAt as r4: tie breaks by runId ascending. No origin models a run persisted
        // before the field existed; it must remain visible under the default API filter.
        registry.register(run("r0", "gamma", RunStatus.COMPLETED, 4, false));

        Assert.assertEquals("newest first, runId tiebreak",
                "[r0, r4, r3, r2, r1]", ids(registry.listRuns(null, null, null, null, 0, -1)).toString());

        Assert.assertEquals("status filter is case-insensitive",
                "[r0, r3, r1]", ids(registry.listRuns("completed", null, null, null, 0, -1)).toString());
        Assert.assertEquals(3, registry.countRuns("Completed", null, null));

        Assert.assertEquals("immediate=FALSE excludes instant runs",
                "[r0, r4, r2, r1]", ids(registry.listRuns(null, null, Boolean.FALSE, null, 0, -1)).toString());
        Assert.assertEquals("immediate=TRUE keeps only instant runs",
                "[r3]", ids(registry.listRuns(null, null, Boolean.TRUE, null, 0, -1)).toString());

        Assert.assertEquals("search matches scriptId, case-insensitive",
                "[r4, r3]", ids(registry.listRuns(null, null, null, "BET", 0, -1)).toString());
        Assert.assertEquals("search matches runId too",
                "[r2]", ids(registry.listRuns(null, null, null, "R2", 0, -1)).toString());
        Assert.assertEquals(2, registry.countRuns(null, null, "beta"));

        Assert.assertEquals("origin=api is case-insensitive",
                "[r0, r4, r1]", ids(registry.listRuns(null, null, null, null, "api", 0, -1)).toString());
        Assert.assertEquals("origin=ui",
                "[r2]", ids(registry.listRuns(null, null, null, null, "UI", 0, -1)).toString());
        Assert.assertEquals("origin=debug",
                "[r3]", ids(registry.listRuns(null, null, null, null, "debug", 0, -1)).toString());
        Assert.assertEquals(3, registry.countRuns(null, null, null, "api"));
        Assert.assertEquals("comma-separated origins form a case-insensitive union",
                "[r0, r4, r2, r1]",
                ids(registry.listRuns(null, null, null, null, "api,UI", 0, -1)).toString());
        Assert.assertEquals(4, registry.countRuns(null, null, null, "API,ui"));
        Assert.assertEquals("an empty checkbox sentinel matches no origin", 0,
                registry.countRuns(null, null, null, "none"));

        Assert.assertEquals("scriptId filter is exact",
                "[r2, r1]", ids(registry.listRuns(null, "alpha", 0, -1)).toString());

        Assert.assertEquals("offset+limit paginate the sorted result",
                "[r3, r2]", ids(registry.listRuns(null, null, null, null, 2, 2)).toString());
        Assert.assertEquals("offset past the end is empty",
                0, registry.listRuns(null, null, null, null, 99, 10).size());

        Assert.assertEquals("returned runs are copies",
                false, registry.listRuns(null, null, null, null, 0, 1).get(0)
                        == registry.getRawRun("r0"));
    }

    /**
     * Archival trims diagnostics (logs/threads/properties) but must keep {@code resultData}
     * intact — the result is the run's product and stays fetchable until purge (it used to be
     * dropped at archive time, leaving only the 300-char resultSummary), including across a
     * restart (the archived run file carries it).
     */
    @Test
    public void archivedRunKeepsItsResultDataWhileTrimmingTheRest() throws Exception {
        File dataDir = tempDir();
        // runRetentionMs=0: a terminal run archives on the first maintenance pass.
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7L * 24 * 3600_000L);
        RunInfo run = run("r1", "s", RunStatus.COMPLETED, 1, false);
        run.endedAt = Long.valueOf(1L);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("n", 1);
        run.resultData = result;
        run.resultSummary = "{\"n\": 1}";
        for (int i = 0; i < 60; i++) {
            run.stdoutLines.add("line" + i);
        }
        run.properties.put("input", "large-diagnostic-payload");
        registry.register(run);

        registry.maintainRuns();

        RunInfo archived = registry.getRun("r1");
        Assert.assertTrue("run is archived", archived.archived);
        Assert.assertNotNull("resultData survives archival", archived.resultData);
        Assert.assertEquals(Integer.valueOf(1),
                ((java.util.Map<?, ?>) archived.resultData).get("n"));
        Assert.assertEquals("stdout trimmed to the archive cap", 50, archived.stdoutLines.size());
        Assert.assertTrue("threads emptied", archived.threads.isEmpty());
        Assert.assertTrue("properties cleared", archived.properties.isEmpty());

        RunRegistry restarted = new RunRegistry(dataDir, 200, 50, 20, 0L, 7L * 24 * 3600_000L);
        RunInfo reloaded = restarted.getRun("r1");
        Assert.assertTrue(reloaded.archived);
        Assert.assertEquals("archived result survives a restart, engine-shaped",
                Integer.valueOf(1), ((java.util.Map<?, ?>) reloaded.resultData).get("n"));
    }

    /**
     * Archival takes the input properties out of memory but leaves them in the run's file until
     * purge, so a detail view can still show them ({@code getRunWithInput}). They used to be
     * erased from both at archive time. The input is long and Korean on purpose: the file is
     * read back across several 4 KB chunks, which used to break characters on the boundaries.
     */
    @Test
    public void archivedRunKeepsItsInputInItsFileUntilPurge() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7L * 24 * 3600_000L);
        RunInfo run = run("r1", "s", RunStatus.COMPLETED, 1, false);
        run.endedAt = Long.valueOf(1L);
        StringBuilder memo = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            memo.append("\uC785\uB825\uAC12 ").append(i).append(' ');   // "입력값 <i> "
        }
        java.util.Map<String, Object> nested = new java.util.LinkedHashMap<String, Object>();
        nested.put("ids", new ArrayList<Object>(java.util.Arrays.asList(1, 2, 3)));
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<String, Object>();
        input.put("count", 5);
        input.put("ratio", 0.5);
        input.put("memo", memo.toString());
        input.put("nested", nested);
        run.properties.putAll(input);
        registry.register(run);

        registry.maintainRuns();

        Assert.assertTrue(registry.getRun("r1").archived);
        Assert.assertTrue("memory lets the input go", registry.getRawRun("r1").properties.isEmpty());
        Assert.assertTrue("polling and listings read no file", registry.getRun("r1").properties.isEmpty());
        Assert.assertEquals("a detail view reads the input back, shapes intact",
                input, registry.getRunWithInput("r1").properties);

        // A later save of the archived record must not erase the input its file holds.
        registry.markDirty(registry.getRawRun("r1"));
        registry.flushDirty();
        Assert.assertEquals(input, registry.getRunWithInput("r1").properties);

        RunRegistry restarted = new RunRegistry(dataDir, 200, 50, 20, 0L, 7L * 24 * 3600_000L);
        Assert.assertTrue("a restart does not take the input back into memory",
                restarted.getRawRun("r1").properties.isEmpty());
        Assert.assertEquals(input, restarted.getRunWithInput("r1").properties);

        // Purge is where the input goes, with the rest of the run.
        RunRegistry purging = new RunRegistry(dataDir, 200, 50, 20, 0L, 0L);
        purging.maintainRuns();
        Assert.assertNull(purging.getRunWithInput("r1"));
        Assert.assertFalse(new File(new File(dataDir, "runs"), "r1.json").exists());
    }

    /** A run that is not archived answers a detail view from memory, as before. */
    @Test
    public void detailOfAnUnarchivedRunComesFromMemory() throws Exception {
        RunRegistry registry = registry(tempDir());
        RunInfo run = run("r1", "s", RunStatus.COMPLETED, 1, false);
        run.properties.put("count", 5);
        registry.register(run);

        RunInfo detail = registry.getRunWithInput("r1");
        Assert.assertFalse(detail.archived);
        Assert.assertEquals(Integer.valueOf(5), detail.properties.get("count"));
        Assert.assertNull(registry.getRunWithInput("nope"));
    }

    /** An id that is taken is refused: a clash must never let one run replace another. */
    @Test
    public void aRegisteredRunIdIsNeverReplaced() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = registry(dataDir);
        RunInfo first = run("same-id", "alpha", RunStatus.QUEUED, 1, false);
        registry.register(first);

        RunInfo second = run("same-id", "beta", RunStatus.QUEUED, 2, false);
        Assert.assertFalse(registry.registerIfAbsent(second));
        try {
            registry.register(second);
            Assert.fail("a second run under a registered id must be refused");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("same-id"));
        }
        Assert.assertSame(first, registry.getRawRun("same-id"));
        Assert.assertEquals("alpha", registry(dataDir).getRun("same-id").scriptId);   // the file too
    }

    @Test
    public void runsSurviveARestartWithoutAnOnDiskIndex() throws Exception {
        File dataDir = tempDir();
        RunRegistry first = registry(dataDir);
        first.register(run("done", "s", RunStatus.COMPLETED, 1, false));
        first.register(run("inflight", "s", RunStatus.RUNNING, 2, false));

        Assert.assertFalse("no run index is written anymore",
                new File(dataDir, "runs/index.json").exists());

        RunRegistry restarted = registry(dataDir);   // recovery = directory scan
        List<RunInfo> all = restarted.listRuns(null, null, null, null, 0, -1);
        Assert.assertEquals("[inflight, done]", ids(all).toString());
        Assert.assertEquals("a run non-terminal at shutdown is recovered as SERVER_RESTARTED",
                RunStatus.SERVER_RESTARTED, all.get(0).status);
        Assert.assertEquals(RunStatus.COMPLETED, all.get(1).status);
        Assert.assertEquals(1, restarted.countRuns("SERVER_RESTARTED", null, null));
    }

    /**
     * Startup recovery reads every {@code .json} in {@code runs/} (no index shields stray files
     * anymore), so one bad file must not block the whole server: invalid JSON, valid JSON of the
     * wrong shape ({@code []} throws from Gson's RunInfo mapping), a foreign object without a
     * {@code runId} (which would NPE on the registry's map key), and a file whose inner runId
     * disagrees with its filename are all skipped with a warning. The mismatch case doubles as a
     * path-traversal guard: the runId is reused as the write path, and its non-terminal status
     * would make recovery save it as SERVER_RESTARTED — without the filename check that write
     * would land at {@code dataDir/outside.json}.
     */
    @Test
    public void aCorruptOrForeignJsonFileDoesNotBlockStartup() throws Exception {
        File dataDir = tempDir();
        registry(dataDir).register(run("good", "s", RunStatus.COMPLETED, 1, false));
        File runsDir = new File(dataDir, "runs");
        Charset utf8 = Charset.forName("UTF-8");
        Files.write(new File(runsDir, "broken.json").toPath(), "not json".getBytes(utf8));
        Files.write(new File(runsDir, "array.json").toPath(), "[]".getBytes(utf8));
        Files.write(new File(runsDir, "foreign.json").toPath(), "{\"foo\": 1}".getBytes(utf8));
        Files.write(new File(runsDir, "mismatch.json").toPath(),
                "{\"runId\": \"../outside\", \"status\": \"RUNNING\", \"createdAt\": 9}".getBytes(utf8));

        RunRegistry restarted = registry(dataDir);
        Assert.assertEquals("the real run survives, bad files are skipped",
                "[good]", ids(restarted.listRuns(null, null, null, null, 0, -1)).toString());
        Assert.assertFalse("a mismatched runId must never be reused as a write path",
                new File(dataDir, "outside.json").exists());
    }

    /**
     * If the legacy index cannot be deleted, continuing would poison a later rollback (the old
     * version would trust the stale index and permanently hide runs written since) — startup must
     * refuse instead. POSIX-only: delete is made to fail by removing write permission on the
     * directory (unlink needs it); root bypasses permissions, so skipped when run as root.
     */
    @Test
    public void anUndeletableLegacyIndexFailsStartup() throws Exception {
        org.junit.Assume.assumeFalse("needs POSIX dir-permission semantics",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        org.junit.Assume.assumeFalse("root bypasses permissions",
                "root".equals(System.getProperty("user.name")));
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        Files.write(new File(runsDir, "index.json").toPath(), "[]".getBytes(Charset.forName("UTF-8")));
        Assert.assertTrue(runsDir.setWritable(false));
        try {
            registry(dataDir);
            Assert.fail("startup must refuse when the legacy index cannot be removed");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage(),
                    expected.getMessage().contains("legacy run index"));
        } finally {
            runsDir.setWritable(true);
        }
    }

    @Test
    public void staleLegacyIndexIsDeletedAtStartupAndNeverTrusted() throws Exception {
        File dataDir = tempDir();
        registry(dataDir).register(run("real", "s", RunStatus.COMPLETED, 1, false));

        // A pre-1.14 index left behind by an older version: stale (lists a run that no longer
        // exists, misses "real"). It must be deleted, not read — a rollback to an older TeeBox
        // should rebuild a fresh index from the run files.
        File legacyIndex = new File(dataDir, "runs/index.json");
        Files.write(legacyIndex.toPath(),
                ("[{\"runId\": \"ghost\", \"status\": \"COMPLETED\", \"createdAt\": 99}]")
                        .getBytes(Charset.forName("UTF-8")));

        RunRegistry restarted = registry(dataDir);
        Assert.assertFalse("legacy index is removed at startup", legacyIndex.exists());
        Assert.assertEquals("only real run files are loaded",
                "[real]", ids(restarted.listRuns(null, null, null, null, 0, -1)).toString());
    }
}
