package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/**
 * A failed run carries no result. markFailed clears it, which also lets the failure be saved after
 * a completion that could not be: a result Gson cannot write (Infinity) used to fail both saves and
 * leave the run file at RUNNING. ScriptExecutor now fails such a run before it completes; this pins
 * the backstop for anything else a save rejects.
 */
public class RunRegistryFailedRunTest {

    @Test
    public void failureAfterAnUnsavableCompletionIsSaved() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-runregistry-failed").toFile();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 24L * 3600_000L, 7L * 24 * 3600_000L);
        RunInfo run = new RunInfo();
        run.runId = "r1";
        run.scriptId = "s";
        run.status = RunStatus.QUEUED;
        run.createdAt = 1;
        registry.register(run);
        registry.markStarted(run);

        try {
            registry.markCompleted(run, true, Double.valueOf(Double.POSITIVE_INFINITY));
            Assert.fail("Gson should refuse to save Infinity");
        } catch (IllegalArgumentException expected) {
            // what RunManager's catch arm sees before it calls markFailed
        }
        Assert.assertFalse("no partial file is left", new File(new File(dataDir, "runs"), "r1.json.tmp").exists());
        registry.markFailed(run, "could not save the result");

        Assert.assertNull("a failed run keeps no result", registry.getRun("r1").resultData);
        String runJson = new String(Files.readAllBytes(new File(new File(dataDir, "runs"), "r1.json").toPath()), "UTF-8");
        Assert.assertTrue("saved as FAILED, got: " + runJson, runJson.contains("\"status\": \"FAILED\""));
        Assert.assertTrue(runJson.contains("could not save the result"));
        Assert.assertFalse(runJson.contains("resultData"));
    }

    @Test
    public void aRunThatCannotBeSavedIsNotRegistered() throws Exception {
        // RunManager refuses such props before it registers a run; this pins the backstop
        File dataDir = Files.createTempDirectory("teebox-runregistry-register").toFile();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 24L * 3600_000L, 7L * 24 * 3600_000L);
        RunInfo run = new RunInfo();
        run.runId = "r2";
        run.scriptId = "s";
        run.status = RunStatus.QUEUED;
        run.createdAt = 1;
        run.properties = new LinkedHashMap<String, Object>();
        run.properties.put("x", Double.valueOf(Double.NEGATIVE_INFINITY));
        try {
            registry.register(run);
            Assert.fail("Gson should refuse to save -Infinity");
        } catch (IllegalArgumentException expected) {
        }
        Assert.assertNull("not left behind, QUEUED forever", registry.getRun("r2"));
        String[] files = new File(dataDir, "runs").list();
        for (String name : files != null ? files : new String[0]) {
            Assert.assertFalse("no file is left for the run: " + name, name.startsWith("r2"));
        }
    }
}
