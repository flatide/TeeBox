package com.flatide.tests;

import com.flatide.task.Task;
import com.flatide.task.TaskStatus;
import com.flatide.teebox.ManagedTaskEngine;
import java.io.File;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression: a persisted task whose meta.json has no status and no lifecycle (older data or an
 * interrupted write) used to crash disk recovery with
 * "Cannot mark persisted: not terminal" — isTransientStatus(null) was false, so recovery treated the
 * task as terminal-persistable, but normalizeFromRunner(null) is ACTIVE, and markPersisted rejects it.
 */
public class ManagedTaskEngineNullStatusTest {

    @Test
    public void initLoadsTaskWithNullStatusAndNoLifecycleWithoutCrashing() throws Exception {
        File baseDir = Files.createTempDirectory("managed-task-nullstatus").toFile();
        File taskDir = new File(new File(baseDir, "tasks"), "task-nullstatus-1");
        Assert.assertTrue(taskDir.mkdirs());
        // No "status", no "phase" lifecycle — the shape that triggered the crash.
        String meta = "{\"taskId\":\"nullstatus-1\",\"runId\":\"run-x\",\"command\":\"/bin/true\",\"pid\":0}";
        Files.write(new File(taskDir, "meta.json").toPath(), meta.getBytes("UTF-8"));

        ManagedTaskEngine engine = new ManagedTaskEngine(baseDir.getAbsolutePath(), "host-nullstatus");
        engine.init();   // must not throw "Cannot mark persisted: not terminal"
        Assert.assertNotNull("task with null status should still load", engine.getTask("nullstatus-1"));
        engine.shutdown();
    }

    /**
     * v1 metadata stored the lowercase wire form ("status":"completed"); v2's TaskStatus has no gson
     * @SerializedName, so without the TaskStatusJsonAdapter gson would read it as null and recovery would
     * re-finalize the task to a different terminal state — losing the original status. Lock that compat:
     * a legacy terminal status must round-trip to the correct enum, not null.
     */
    @Test
    public void recoversV1LowercaseTerminalStatus() throws Exception {
        File baseDir = Files.createTempDirectory("managed-task-legacystatus").toFile();
        File taskDir = new File(new File(baseDir, "tasks"), "task-legacy-1");
        Assert.assertTrue(taskDir.mkdirs());
        // v1-style metadata: lowercase status, no lifecycle/phase.
        String meta = "{\"taskId\":\"legacy-1\",\"runId\":\"run-x\",\"command\":\"/bin/true\",\"pid\":0,"
                + "\"status\":\"completed\"}";
        Files.write(new File(taskDir, "meta.json").toPath(), meta.getBytes("UTF-8"));

        ManagedTaskEngine engine = new ManagedTaskEngine(baseDir.getAbsolutePath(), "host-legacystatus");
        engine.init();
        Task recovered = engine.getTask("legacy-1");
        Assert.assertNotNull("legacy task should load", recovered);
        Assert.assertEquals("lowercase 'completed' must deserialize to COMPLETED, not null",
                TaskStatus.COMPLETED, recovered.status);
        engine.shutdown();
    }
}
