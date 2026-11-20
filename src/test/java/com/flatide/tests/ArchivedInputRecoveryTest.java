package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An archived run's input is in its file and not in memory, and a server start keeps it that way:
 * it reads one run file at a time and keeps no archived input. The start used to read every run
 * file into one list and only then let the archived inputs go, so for a moment it needed the heap
 * for all of them together: with enough archived input on disk the server could not start at all,
 * though it had been running on that very data.
 */
public class ArchivedInputRecoveryTest {

    private static final long DAY_MS = 24L * 3600_000L;
    private static final int RUNS = 96;
    private static final int INPUT_CHARS = 512 * 1024;

    @Test
    public void aStartKeepsNoArchivedInput() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-archived-recovery").toFile();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        RunInfo archived = endedRun("archived", "kept in the file");
        registry.register(archived);
        registry.maintainRuns();
        RunRegistry later = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);
        later.register(endedRun("recent", "still in memory"));

        List<String> seen = new ArrayList<String>();
        for (RunInfo run : new RunStore(dataDir).loadAll()) {
            seen.add(run.runId + (run.archived ? " archived " : " ") + run.properties);
        }
        java.util.Collections.sort(seen);
        Assert.assertEquals(Arrays.asList("archived archived {}", "recent {blob=still in memory}"), seen);
        Assert.assertEquals("the file is where a detail view finds it", "kept in the file",
                new RunStore(dataDir).loadProperties("archived").get("blob"));
    }

    /**
     * A run file can say "archived" beside a status that is not terminal — nothing here writes
     * that, but a file can. The start ends such a run as SERVER_RESTARTED and saves it: with the
     * input the file holds, which the start itself no longer has in memory.
     */
    @Test
    public void aStartThatSavesAnArchivedRunKeepsItsInput() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-archived-recovery-save").toFile();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        Files.write(new File(runsDir, "odd.json").toPath(),
            ("{\"runId\": \"odd\", \"scriptId\": \"s\", \"status\": \"RUNNING\", \"archived\": true,"
             + " \"createdAt\": 1, \"properties\": {\"blob\": \"kept\", \"n\": 5}}").getBytes("UTF-8"));

        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);

        Assert.assertEquals(RunStatus.SERVER_RESTARTED, registry.getRun("odd").status);
        Assert.assertTrue(registry.getRawRun("odd").properties.isEmpty());
        String saved = new String(Files.readAllBytes(new File(runsDir, "odd.json").toPath()), "UTF-8");
        Assert.assertTrue(saved, saved.contains("\"status\": \"SERVER_RESTARTED\""));
        Assert.assertEquals("kept", registry.getRunWithInput("odd").properties.get("blob"));
        Assert.assertEquals(Integer.valueOf(5), registry.getRunWithInput("odd").properties.get("n"));
    }

    /** 96 archived runs of 512 KiB input each: 48 MiB on disk, started on a 32 MiB heap. */
    @Test
    public void aStartDoesNotNeedTheHeapForAllArchivedInputAtOnce() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-archived-recovery-heap").toFile();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        char[] chars = new char[INPUT_CHARS];
        Arrays.fill(chars, 'x');
        String blob = new String(chars);
        for (int i = 0; i < RUNS; i++) {
            registry.register(endedRun("run-" + i, blob));
            registry.maintainRuns();   // archived at once, so this JVM holds one input at a time too
        }
        Assert.assertEquals(RUNS, registry.countRuns(null));
        Assert.assertTrue(registry.getRawRun("run-0").archived);

        List<String> command = new ArrayList<String>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.addAll(Arrays.asList("-Xmx32m", "-cp", System.getProperty("java.class.path"),
            RunRecoveryProbe.class.getName(), dataDir.getPath(), "run-" + (RUNS - 1)));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, len);
        }
        String output = bytes.toString("UTF-8");
        Assert.assertEquals("probe output:\n" + output, 0, process.waitFor());
        List<String> lines = Arrays.asList(output.split("\\R"));
        Assert.assertTrue(output, lines.contains("maxHeapMb=32"));
        Assert.assertTrue(output, lines.contains("runs=" + RUNS));
        Assert.assertTrue("a detail view still reads one input from its file:\n" + output,
                lines.contains("inputChars=" + INPUT_CHARS));
    }

    private static RunInfo endedRun(String runId, String blob) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.COMPLETED;
        run.createdAt = 1L;
        run.endedAt = Long.valueOf(System.currentTimeMillis() - 60_000L);   // due for archiving, far from purge
        run.properties.put("blob", blob);
        return run;
    }
}
