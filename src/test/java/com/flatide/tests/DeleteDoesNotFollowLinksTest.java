package com.flatide.tests;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.teebox.ManagedTaskEngine;
import com.flatide.teebox.ScriptRegistry;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a purge deletes is what is in the directory it purges. A link in it is a name like any
 * file's: the link goes, what it points to stays. The deletes used to go down through a link to
 * a directory and delete what was on the other side — outside what the server keeps.
 */
public class DeleteDoesNotFollowLinksTest {

    @Test
    public void aTaskPurgeDeletesNothingThroughALinkInTheTasksDirectory() throws Exception {
        assumeUnix();
        File dataDir = tempDir();
        File precious = outside(dataDir, "outside");
        // Archived at the first sweep, purged at the second.
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a", 0L, 0L);
        try {
            String taskId = ended(engine, "echo linked");
            link(new File(engine.getTaskDir(taskId), "elsewhere"), precious.getParentFile());
            engine.archiveExpiredTasks();
            engine.archiveExpiredTasks();
            Assert.assertFalse("the task was purged", engine.getTaskDir(taskId).exists());
            Assert.assertTrue("deleted through a link inside the task's directory", precious.isFile());
        } finally {
            engine.shutdown();
        }
    }

    /** The task's directory itself a link: only the link is removed, nothing under the name it leads to. */
    @Test
    public void aTaskPurgeRemovesOnlyTheLinkWhenTheTasksDirectoryIsOne() throws Exception {
        assumeUnix();
        File dataDir = tempDir();
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a", 0L, 0L);
        try {
            String taskId = ended(engine, "echo moved");
            engine.archiveExpiredTasks();   // archived: archive.json is what the task is now
            File taskDir = engine.getTaskDir(taskId);
            File moved = new File(dataDir, "moved-away");
            Assert.assertTrue(taskDir.renameTo(moved));
            File precious = new File(moved, "precious.txt");
            Files.write(precious.toPath(), "kept".getBytes(StandardCharsets.UTF_8));
            link(taskDir, moved);

            engine.archiveExpiredTasks();   // purged
            Assert.assertFalse("the link is gone", Files.exists(taskDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            Assert.assertTrue(precious.isFile());
            Assert.assertTrue("a file of the directory it led to was deleted through it",
                    new File(moved, "archive.json").isFile());
        } finally {
            engine.shutdown();
        }
    }

    /**
     * The same before the task was ever archived: archiving writes archive.json and deletes the
     * task's own files by name, and through a directory that is a link it did both in the
     * directory the link leads to. Such a task is left as it is — not archived, and so not purged.
     */
    @Test
    public void aTaskWhoseDirectoryIsALinkIsNotArchivedThroughIt() throws Exception {
        assumeUnix();
        File dataDir = tempDir();
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a", 0L, 0L);
        try {
            String taskId = ended(engine, "echo kept-output");
            File taskDir = engine.getTaskDir(taskId);
            File moved = new File(dataDir, "moved-away");
            Assert.assertTrue(taskDir.renameTo(moved));
            link(taskDir, moved);
            SortedMap<String, String> before = tree(moved);
            Assert.assertTrue(before.toString(), before.containsKey("meta.json"));

            engine.archiveExpiredTasks();
            engine.archiveExpiredTasks();
            Assert.assertEquals("the directory the link leads to was changed by the archive", before, tree(moved));
            Assert.assertTrue("the link is left where it is", Files.isSymbolicLink(taskDir.toPath()));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void aScriptDeleteDeletesNothingThroughALink() throws Exception {
        File dataDir = tempDir();
        File precious = outside(dataDir, "outside");
        ScriptRegistry registry = new ScriptRegistry(dataDir);
        registry.registerVersion("job", "1", "PRINT(\"one\")\n", "v1", Arrays.asList("test"), true);
        link(new File(dataDir, "script-registry/job/versions/elsewhere"), precious.getParentFile());

        Assert.assertTrue(registry.deleteScript("job"));
        Assert.assertEquals(Arrays.asList("job"), registry.purgeExpiredScripts(0L));
        Assert.assertFalse(new File(dataDir, "script-registry/job").exists());
        Assert.assertTrue("deleted through a link inside the script's directory", precious.isFile());
    }

    // ===================== helpers =====================

    private static String ended(ManagedTaskEngine engine, String command) throws Exception {
        TaskRequest request = new TaskRequest();
        request.command = command;
        request.runId = "run-1";
        Task task = engine.execute(request);
        engine.waitForCompletion(task.taskId, 10_000L);
        return task.taskId;
    }

    /** A file in a directory that is none of the server's. */
    private static File outside(File dataDir, String name) throws IOException {
        File directory = new File(dataDir, name);
        Assert.assertTrue(directory.mkdirs());
        File precious = new File(directory, "precious.txt");
        Files.write(precious.toPath(), "kept".getBytes(StandardCharsets.UTF_8));
        return precious;
    }

    private static void link(File name, File target) throws IOException {
        try {
            Files.createSymbolicLink(name.toPath(), target.toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assume.assumeNoException("symbolic links cannot be made here", e);
        }
    }

    /** Every file of a directory, with what is in it. */
    private static SortedMap<String, String> tree(File directory) throws IOException {
        SortedMap<String, String> tree = new TreeMap<String, String>();
        File[] files = directory.listFiles();
        Assert.assertNotNull(files);
        for (File file : files) {
            tree.put(file.getName(), file.isFile()
                    ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1) : "<directory>");
        }
        return tree;
    }

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("teebox-delete-links").toFile();
    }

    private static void assumeUnix() {
        Assume.assumeFalse("launches a task with a Unix shell",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
    }
}
