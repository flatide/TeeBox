package com.flatide.tests;

import com.flatide.propertee2.value.JsonNull;
import com.flatide.teebox.ResolvedModuleInfo;
import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disk round-trip fidelity of {@code resultData}: what the engine produced must read back identical
 * after a save/load cycle (i.e. across a server restart). Two corruptions guarded here:
 * <ul>
 *   <li>the engine's first-class {@code null} ({@link JsonNull#NULL}, spec v0.8.0 — {@code null != {}})
 *       must survive as itself, not collapse to a Java null (key silently dropped when served) or
 *       to {@code {}} ("absence");</li>
 *   <li>numbers must keep their engine shape — Gson's generic Object mapping turns every number into
 *       a {@code Double}, so {@code "n": 1} would be served as {@code 1.0} after a restart.</li>
 * </ul>
 */
public class RunStoreTest {

    private static RunInfo terminalRun(String runId, Object resultData) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.COMPLETED;
        run.createdAt = 1L;
        run.resultData = resultData;
        return run;
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-runstore-test").toFile();
    }

    @Test
    public void resultDataValueShapesSurviveTheDiskRoundTrip() throws Exception {
        File dataDir = tempDir();

        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("b", JsonNull.NULL);
        List<Object> list = new ArrayList<Object>();
        list.add(1);
        list.add(JsonNull.NULL);
        list.add(3);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("coupon", JsonNull.NULL);
        data.put("n", 1);
        data.put("pi", 2.5);
        data.put("whole", 2.0);
        data.put("ok", Boolean.TRUE);
        data.put("name", "a\"b\nc");
        data.put("empty", new LinkedHashMap<String, Object>());
        data.put("a", nested);
        data.put("list", list);

        new RunStore(dataDir).save(terminalRun("r1", data));
        RunInfo loaded = new RunStore(dataDir).load("r1");   // fresh instance = a restarted server

        Assert.assertNotNull(loaded);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) loaded.resultData;
        Assert.assertSame("first-class null survives", JsonNull.NULL, r.get("coupon"));
        Assert.assertEquals("integers stay Integer", Integer.valueOf(1), r.get("n"));
        Assert.assertEquals("decimals stay Double", Double.valueOf(2.5), r.get("pi"));
        Assert.assertEquals("a written 2.0 stays Double", Double.valueOf(2.0), r.get("whole"));
        Assert.assertEquals(Boolean.TRUE, r.get("ok"));
        Assert.assertEquals("a\"b\nc", r.get("name"));
        Assert.assertEquals("empty object stays {} (distinct from null)",
                new LinkedHashMap<String, Object>(), r.get("empty"));
        Assert.assertSame(JsonNull.NULL, ((Map<?, ?>) r.get("a")).get("b"));
        List<?> loadedList = (List<?>) r.get("list");
        Assert.assertEquals(Integer.valueOf(1), loadedList.get(0));
        Assert.assertSame(JsonNull.NULL, loadedList.get(1));
        Assert.assertEquals("insertion order preserved",
                "[coupon, n, pi, whole, ok, name, empty, a, list]", r.keySet().toString());
    }

    @Test
    public void topLevelNullResultSurvivesAndValuelessRunStaysNull() throws Exception {
        File dataDir = tempDir();
        RunStore store = new RunStore(dataDir);
        store.save(terminalRun("null_top", JsonNull.NULL));   // script: return null
        store.save(terminalRun("no_value", null));            // legacy value-less run (field omitted)

        RunStore reloaded = new RunStore(dataDir);
        Assert.assertSame(JsonNull.NULL, reloaded.load("null_top").resultData);
        Assert.assertNull(reloaded.load("no_value").resultData);
    }

    @Test
    public void importedModulePinsSurviveTheDiskRoundTrip() throws Exception {
        File dataDir = tempDir();
        RunInfo run = terminalRun("imports", new LinkedHashMap<String, Object>());
        ResolvedModuleInfo module = new ResolvedModuleInfo();
        module.scriptId = "util.file";
        module.version = "7";
        module.sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        run.imports.add(module);

        new RunStore(dataDir).save(run);
        RunInfo loaded = new RunStore(dataDir).load("imports");

        Assert.assertEquals(1, loaded.imports.size());
        Assert.assertEquals("util.file", loaded.imports.get(0).scriptId);
        Assert.assertEquals("7", loaded.imports.get(0).version);
        Assert.assertEquals(module.sha256, loaded.imports.get(0).sha256);
    }

    /**
     * On Windows an external scanner (antivirus, search indexer) can transiently hold a just-written
     * run file, failing the tmp -> final rename with a sharing violation ("Failed to save run ...:
     * being used by another process"). The store must retry the move with backoff before giving up.
     * A held target cannot be simulated on POSIX, so a move that always fails (nonexistent source)
     * stands in: the retries are observed as elapsed backoff time, and the last failure propagates.
     */
    @Test
    public void failingMoveIsRetriedWithBackoffBeforeGivingUp() throws Exception {
        File dataDir = tempDir();
        RunStore store = new RunStore(dataDir);
        java.lang.reflect.Method move = RunStore.class.getDeclaredMethod(
                "moveAtomically", java.nio.file.Path.class, java.nio.file.Path.class);
        move.setAccessible(true);
        File runsDir = new File(dataDir, "runs");
        long start = System.nanoTime();
        try {
            move.invoke(store,
                    new File(runsDir, "missing.json.tmp").toPath(),
                    new File(runsDir, "missing.json").toPath());
            Assert.fail("move of a nonexistent source must fail");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assert.assertTrue("the last IOException propagates",
                    e.getCause() instanceof java.io.IOException);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        Assert.assertTrue("backoff sleeps (20+40+80+160ms) ran before the final failure, got "
                + elapsedMs + "ms", elapsedMs >= 290);
    }

    /** Files written before the adapter existed carry {} where null was — they must still load. */
    @Test
    public void legacyFileWithCollapsedNullStillLoads() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        runsDir.mkdirs();
        String legacy = "{\n  \"runId\": \"old\",\n  \"scriptId\": \"s\",\n  \"status\": \"COMPLETED\",\n"
                + "  \"createdAt\": 1,\n  \"resultData\": {\n    \"coupon\": {},\n    \"n\": 1\n  }\n}";
        Files.write(new File(runsDir, "old.json").toPath(), legacy.getBytes(Charset.forName("UTF-8")));

        RunInfo loaded = new RunStore(dataDir).load("old");
        Assert.assertNotNull(loaded);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) loaded.resultData;
        Assert.assertEquals("pre-fix files keep their (already collapsed) {}",
                new LinkedHashMap<String, Object>(), r.get("coupon"));
        Assert.assertEquals(Integer.valueOf(1), r.get("n"));
    }
}
