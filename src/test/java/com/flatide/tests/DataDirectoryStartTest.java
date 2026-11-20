package com.flatide.tests;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.teebox.ManagedTaskEngine;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.ScriptRegistry;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.UserStore;
import com.flatide.teebox.webhook.WebhookStore;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * What holds for the runs directory (see {@link RunsDirectoryStartTest}) holds for the other
 * directories a server keeps under its data directory: one that does not give up what is in it —
 * closed to listing or to searching, or no directory at all — answers "nothing there", and a
 * server that started on that answer ran as if it had no tasks, no scripts, no deliveries waiting
 * — or no users, and without users the admin UI asks nobody to log in. Such a start is refused.
 */
public class DataDirectoryStartTest {

    // ===================== tasks/ =====================

    @Test
    public void aStartIsRefusedWhenTheTasksDirectoryCannotBeSearched() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithATask();
        File tasksDir = new File(dataDir, "tasks");

        Assert.assertTrue(tasksDir.setExecutable(false));
        try {
            ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");
            engine.init();
            Assert.fail("the task engine started, with " + engine.listTasks().size() + " tasks");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be searched");
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("must be able to list and search it"));
        } finally {
            tasksDir.setExecutable(true);
        }
        Assert.assertEquals("with the directory open again the task is found", 1, tasksAfterAStart(dataDir));
    }

    @Test
    public void aStartIsRefusedWhenTheTasksDirectoryCannotBeListed() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithATask();
        File tasksDir = new File(dataDir, "tasks");

        Assert.assertTrue(tasksDir.setReadable(false));
        try {
            ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");
            engine.init();
            Assert.fail("the task engine started, with " + engine.listTasks().size() + " tasks");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be listed");
        } finally {
            tasksDir.setReadable(true);
        }
        Assert.assertEquals(1, tasksAfterAStart(dataDir));
    }

    @Test
    public void aStartIsRefusedWhenTasksIsNoDirectory() throws Exception {
        File dataDir = tempDir();
        File tasks = new File(dataDir, "tasks");
        Files.write(tasks.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        try {
            ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");
            engine.init();
            Assert.fail("the task engine started, with " + engine.listTasks().size() + " tasks");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasks, "is not a directory");
        }
        Assert.assertEquals("the file was left alone", "not a directory",
                new String(Files.readAllBytes(tasks.toPath()), StandardCharsets.UTF_8));
    }

    /** Asked by the scan too: an engine made over an open directory does not read a closed one as empty. */
    @Test
    public void aTaskScanOfADirectoryThatWasClosedSinceIsRefused() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithATask();
        File tasksDir = new File(dataDir, "tasks");
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");

        Assert.assertTrue(tasksDir.setExecutable(false));
        try {
            engine.init();
            Assert.fail("the scan answered with " + engine.listTasks().size() + " tasks");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be searched");
        } finally {
            tasksDir.setExecutable(true);
        }

        ManagedTaskEngine another = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-c");
        Assert.assertTrue(tasksDir.setReadable(false));
        try {
            another.init();
            Assert.fail("the scan answered with " + another.listTasks().size() + " tasks");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be listed");
        } finally {
            tasksDir.setReadable(true);
        }
        Assert.assertEquals(1, tasksAfterAStart(dataDir));
    }

    /**
     * The engine refuses by itself, without a scan, and before it touches anything: not even the
     * index file of an old version, which an engine deletes when it is made.
     */
    @Test
    public void aTaskEngineIsNotMadeOverADirectoryItCannotRead() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithATask();
        File tasksDir = new File(dataDir, "tasks");
        File oldIndex = new File(tasksDir, "index.json");
        Files.write(oldIndex.toPath(), "[]".getBytes(StandardCharsets.UTF_8));

        Assert.assertTrue(tasksDir.setReadable(false));
        try {
            new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");
            Assert.fail("a task engine was made over a directory it cannot list");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be listed");
        } finally {
            tasksDir.setReadable(true);
        }
        Assert.assertTrue("the refused start deleted the old index", oldIndex.exists());

        Assert.assertTrue(tasksDir.setExecutable(false));
        try {
            new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");
            Assert.fail("a task engine was made over a directory it cannot search");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be searched");
        } finally {
            tasksDir.setExecutable(true);
        }
        Assert.assertTrue("the refused start deleted the old index", oldIndex.exists());

        Assert.assertEquals(1, tasksAfterAStart(dataDir));   // readable: made, and the old index goes
        Assert.assertFalse(oldIndex.exists());
    }

    /** The whole run manager: it is not made over a tasks directory it cannot read. */
    @Test
    public void aRunManagerIsNotMadeOverATasksDirectoryItCannotSearch() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithATask();
        File tasksDir = new File(dataDir, "tasks");

        Assert.assertTrue(tasksDir.setExecutable(false));
        try {
            RunManager manager = new RunManager(dataDir, 1);
            manager.shutdown();
            Assert.fail("a run manager was made over a tasks directory it cannot search");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, tasksDir, "cannot be searched");
        } finally {
            tasksDir.setExecutable(true);
        }
    }

    // ===================== script-registry/ =====================

    @Test
    public void aStartIsRefusedWhenTheScriptRegistryCannotBeSearched() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithAScript();
        File registryDir = new File(dataDir, "script-registry");

        Assert.assertTrue(registryDir.setExecutable(false));
        try {
            ScriptRegistry registry = new ScriptRegistry(dataDir);
            Assert.fail("the script registry started, with " + registry.listScripts().size() + " scripts");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, registryDir, "cannot be searched");
        } finally {
            registryDir.setExecutable(true);
        }
        Assert.assertEquals("with the directory open again the script is found",
                1, new ScriptRegistry(dataDir).listScripts().size());
    }

    @Test
    public void aStartIsRefusedWhenTheScriptRegistryCannotBeListed() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithAScript();
        File registryDir = new File(dataDir, "script-registry");

        Assert.assertTrue(registryDir.setReadable(false));
        try {
            ScriptRegistry registry = new ScriptRegistry(dataDir);
            Assert.fail("the script registry started, with " + registry.listScripts().size() + " scripts");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, registryDir, "cannot be listed");
        } finally {
            registryDir.setReadable(true);
        }
        Assert.assertEquals(1, new ScriptRegistry(dataDir).listScripts().size());
    }

    @Test
    public void aStartIsRefusedWhenTheScriptRegistryIsNoDirectory() throws Exception {
        File dataDir = tempDir();
        File registry = new File(dataDir, "script-registry");
        Files.write(registry.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        try {
            ScriptRegistry made = new ScriptRegistry(dataDir);
            Assert.fail("the script registry started, with " + made.listScripts().size() + " scripts");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, registry, "is not a directory");
        }
    }

    // ===================== webhooks/ =====================

    @Test
    public void aStartIsRefusedWhenTheWebhooksDirectoryCannotBeRead() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        new WebhookStore(dataDir);
        File webhooksDir = new File(dataDir, "webhooks");

        Assert.assertTrue(webhooksDir.setExecutable(false));
        try {
            new WebhookStore(dataDir);
            Assert.fail("a webhook store was made over a directory it cannot search");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, webhooksDir, "cannot be searched");
        } finally {
            webhooksDir.setExecutable(true);
        }

        Assert.assertTrue(webhooksDir.setReadable(false));
        try {
            new WebhookStore(dataDir);
            Assert.fail("a webhook store was made over a directory it cannot list");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, webhooksDir, "cannot be listed");
        } finally {
            webhooksDir.setReadable(true);
        }
        new WebhookStore(dataDir);   // open again: made as before
    }

    @Test
    public void aStartIsRefusedWhenWebhooksIsNoDirectory() throws Exception {
        File dataDir = tempDir();
        File webhooks = new File(dataDir, "webhooks");
        Files.write(webhooks.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        try {
            new WebhookStore(dataDir);
            Assert.fail("a webhook store was made over something that is no directory");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, webhooks, "is not a directory");
        }
    }

    /** A server that sends webhooks does not come up over an outbox it cannot read. */
    @Test
    public void aRunManagerWithWebhooksIsNotMadeOverAnOutboxItCannotSearch() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        new WebhookStore(dataDir);
        File webhooksDir = new File(dataDir, "webhooks");
        TeeBoxConfig config = new TeeBoxConfig();
        config.dataDir = dataDir;
        config.webhookEnabled = true;

        Assert.assertTrue(webhooksDir.setExecutable(false));
        try {
            RunManager manager = new RunManager(dataDir, 1, config);
            manager.shutdown();
            Assert.fail("a run manager was made over a webhooks directory it cannot search");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, webhooksDir, "cannot be searched");
        } finally {
            webhooksDir.setExecutable(true);
        }
    }

    // ===================== users/ =====================

    /**
     * The one that matters most. Login is asked for when there is a roster file, and a roster in a
     * directory that cannot be searched is "not there": the server started, and its admin UI
     * was open to anyone.
     */
    @Test
    public void aStartIsRefusedWhenTheUsersDirectoryCannotBeSearched() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithAUser();
        File usersDir = new File(dataDir, "users");

        Assert.assertTrue(usersDir.setExecutable(false));
        try {
            UserStore store = new UserStore(dataDir);
            Assert.fail("a user store was made over a directory it cannot search; it says a roster is "
                    + (store.hasRoster() ? "there" : "not there, so nobody is asked to log in"));
        } catch (IllegalStateException expected) {
            assertRefusal(expected, usersDir, "cannot be searched");
            // What it says the server needs is what this directory is asked for: it is never listed.
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("must be able to search it"));
        } finally {
            usersDir.setExecutable(true);
        }
        Assert.assertTrue("with the directory open again the roster is there", new UserStore(dataDir).hasRoster());
    }

    @Test
    public void aStartIsRefusedWhenUsersIsNoDirectory() throws Exception {
        File dataDir = tempDir();
        File users = new File(dataDir, "users");
        Files.write(users.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        try {
            new UserStore(dataDir);
            Assert.fail("a user store was made over something that is no directory");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, users, "is not a directory");
        }
    }

    /**
     * Kept as it was: the users directory is never listed — its two files are asked for by name
     * — so one that cannot be listed is no reason to refuse.
     */
    @Test
    public void aUsersDirectoryThatCannotBeListedIsReadAsBefore() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithAUser();
        File usersDir = new File(dataDir, "users");

        Assert.assertTrue(usersDir.setReadable(false));
        try {
            UserStore store = new UserStore(dataDir);
            Assert.assertTrue(store.hasRoster());
            Assert.assertNotNull(store.findUser("alice"));
        } finally {
            usersDir.setReadable(true);
        }
    }

    /** The whole server: nothing comes up to serve an admin UI over a roster it cannot see. */
    @Test
    public void aServerDoesNotComeUpOverAUsersDirectoryItCannotSearch() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithAUser();
        File usersDir = new File(dataDir, "users");
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;

        Assert.assertTrue(usersDir.setExecutable(false));
        try {
            TeeBoxServer server = new TeeBoxServer(config);
            server.start();
            server.stop();
            Assert.fail("a server was made over a users directory it cannot search");
        } catch (IllegalStateException expected) {
            assertRefusal(expected, usersDir, "cannot be searched");
        } finally {
            usersDir.setExecutable(true);
        }
    }

    // ===================== a start that is refused has not begun =====================

    /**
     * Every directory is asked before anything is loaded, recovered or swept. A start refused
     * over one of them has not acted on the others: here, the run the server had stopped on is
     * not yet saved as ended by a restart, which is the first thing a start does with it.
     */
    @Test
    public void aStartRefusedOverOneDirectoryHasNotActedOnTheOthers() throws Exception {
        assumePermissionsApply();
        for (String closed : new String[] {"tasks", "script-registry", "webhooks", "users"}) {
            File dataDir = tempDir();
            new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a").shutdown();
            new ScriptRegistry(dataDir);
            new WebhookStore(dataDir);
            new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN);
            File runsDir = new File(dataDir, "runs");
            Assert.assertTrue(runsDir.mkdirs());
            File unfinished = new File(runsDir, "left.json");
            Files.write(unfinished.toPath(), ("{\"runId\": \"left\", \"scriptId\": \"s\", \"status\": \"RUNNING\","
                    + " \"createdAt\": 1, \"properties\": {}}").getBytes(StandardCharsets.UTF_8));
            byte[] before = Files.readAllBytes(unfinished.toPath());
            TeeBoxConfig config = new TeeBoxConfig();
            config.bindAddress = "127.0.0.1";
            config.port = 0;
            config.dataDir = dataDir;
            config.maxConcurrentRuns = 1;
            config.webhookEnabled = true;
            File closedDir = new File(dataDir, closed);

            Assert.assertTrue(closedDir.setExecutable(false));
            try {
                TeeBoxServer server = new TeeBoxServer(config);
                server.start();
                server.stop();
                Assert.fail("a server was made over a " + closed + " directory it cannot search");
            } catch (IllegalStateException expected) {
                assertRefusal(expected, closedDir, "cannot be searched");
            } finally {
                closedDir.setExecutable(true);
            }
            Assert.assertEquals("the start refused over " + closed + " had already acted on the runs",
                    new String(before, StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(unfinished.toPath()), StandardCharsets.UTF_8));
        }
    }

    /**
     * Kept as it was: an engine that is used without the scan of a start reads its directory when
     * it first needs to, and reads one that is closed by then as it always did — as empty. Only a
     * start is refused; nothing that happens later is.
     */
    @Test
    public void anEngineUsedWithoutAStartReadsAClosedDirectoryAsBefore() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithATask();
        File tasksDir = new File(dataDir, "tasks");
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-b");

        Assert.assertTrue(tasksDir.setReadable(false));
        try {
            Assert.assertEquals(0, engine.listTasks().size());
        } finally {
            tasksDir.setReadable(true);
        }
    }

    // ===================== helpers =====================

    private static void assertRefusal(IllegalStateException refusal, File directory, String why) {
        String message = String.valueOf(refusal.getMessage());
        Assert.assertTrue(message, message.contains(directory.getAbsolutePath()));
        Assert.assertTrue(message, message.contains("refusing to start"));
        Assert.assertTrue(message, message.contains(why));
    }

    private static void assumePermissionsApply() {
        Assume.assumeFalse("needs POSIX permission semantics",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-data-dir").toFile();
    }

    /** A data directory with one finished task in it. */
    private static File dataDirWithATask() throws Exception {
        File dataDir = tempDir();
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a");
        TaskRequest request = new TaskRequest();
        request.command = "echo hello";
        request.runId = "run-1";
        Task task = engine.execute(request);
        engine.waitForCompletion(task.taskId, 10_000L);
        engine.shutdown();
        return dataDir;
    }

    private static int tasksAfterAStart(File dataDir) {
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-z");
        engine.init();
        return engine.queryTasks("run-1", null, 0, -1).size();
    }

    private static File dataDirWithAScript() throws Exception {
        File dataDir = tempDir();
        new ScriptRegistry(dataDir).registerVersion("report", "1", "return 1\n", "a script", Arrays.asList("t"), true);
        return dataDir;
    }

    private static File dataDirWithAUser() throws Exception {
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN);
        return dataDir;
    }
}
