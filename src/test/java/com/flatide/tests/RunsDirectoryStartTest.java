package com.flatide.tests;

import com.flatide.teebox.DataDirectory;
import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A start reads the runs back from their directory. A directory that does not give them up —
 * closed to listing or to searching, or no directory at all — answers "nothing there", and a
 * server that started on that answer ran as if it had no runs: none shown, none marked as stopped
 * by the restart, none purged, with every file still on the disk. Such a start is refused.
 */
public class RunsDirectoryStartTest {

    private static final long DAY_MS = 24L * 3600_000L;

    /** The files can be listed by name, and none of them can be looked at. */
    @Test
    public void aStartIsRefusedWhenTheRunsDirectoryCannotBeSearched() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1", "r2");
        File runsDir = new File(dataDir, "runs");
        byte[] before = Files.readAllBytes(new File(runsDir, "r1.json").toPath());

        Assert.assertTrue(runsDir.setExecutable(false));
        try {
            assertStartRefused(dataDir, "cannot be searched");
        } finally {
            runsDir.setExecutable(true);
        }
        Assert.assertArrayEquals("the refused start changed nothing",
                before, Files.readAllBytes(new File(runsDir, "r1.json").toPath()));
        Assert.assertEquals("with the directory open again the runs are found",
                2, registry(dataDir).countRuns(null));
    }

    /** The files can be looked at by name, and the names cannot be had. */
    @Test
    public void aStartIsRefusedWhenTheRunsDirectoryCannotBeListed() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1", "r2");
        File runsDir = new File(dataDir, "runs");

        Assert.assertTrue(runsDir.setReadable(false));
        try {
            assertStartRefused(dataDir, "cannot be listed");
        } finally {
            runsDir.setReadable(true);
        }
        Assert.assertEquals(2, registry(dataDir).countRuns(null));
    }

    /**
     * No run to lose sight of yet — and none could be saved: a file is made in a directory by
     * searching it. The refusal is about the directory, not about what happens to be in it.
     */
    @Test
    public void aStartIsRefusedWhenAnEmptyRunsDirectoryCannotBeSearched() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());

        Assert.assertTrue(runsDir.setExecutable(false));
        try {
            assertStartRefused(dataDir, "cannot be searched");
        } finally {
            runsDir.setExecutable(true);
        }
        Assert.assertEquals(0, registry(dataDir).countRuns(null));
    }

    /**
     * Something else stands where the directory belongs. Said as that, and not found out by
     * trying to list it: what stands there may be something an open never returns from.
     */
    @Test
    public void aStartIsRefusedWhenRunsIsNoDirectory() throws Exception {
        File dataDir = tempDir();
        File runs = new File(dataDir, "runs");
        Files.write(runs.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        IllegalStateException refusal = assertStartRefused(dataDir, "is not a directory");
        Assert.assertFalse("permissions are not what is wrong here: " + refusal.getMessage(),
                refusal.getMessage().contains("rwx"));
        Assert.assertEquals("the file was left alone", "not a directory",
                new String(Files.readAllBytes(runs.toPath()), StandardCharsets.UTF_8));
    }

    /** The whole server: nothing comes up to listen over a directory it cannot read. */
    @Test
    public void aServerDoesNotComeUpOverARunsDirectoryItCannotSearch() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1");
        File runsDir = new File(dataDir, "runs");
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;

        Assert.assertTrue(runsDir.setExecutable(false));
        try {
            TeeBoxServer server = new TeeBoxServer(config);
            server.start();
            server.stop();
            Assert.fail("a server was made over a runs directory it cannot search");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
        } finally {
            runsDir.setExecutable(true);
        }

        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            Assert.assertNotNull("the run is there once the directory can be searched",
                    server.getRunManager().getRun("r1"));
        } finally {
            server.stop();
        }
    }

    /**
     * The store refuses by itself, and before it touches anything: a start it refuses when it is
     * made has changed nothing — not even the index file of an old version, which a store deletes
     * when it is made.
     */
    @Test
    public void aStoreIsNotMadeOverADirectoryItCannotRead() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1");
        File runsDir = new File(dataDir, "runs");
        File oldIndex = new File(runsDir, "index.json");
        Files.write(oldIndex.toPath(), "[]".getBytes(StandardCharsets.UTF_8));

        Assert.assertTrue(runsDir.setReadable(false));
        try {
            new RunStore(dataDir);
            Assert.fail("a store was made over a directory it cannot list");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("cannot be listed"));
        } finally {
            runsDir.setReadable(true);
        }
        Assert.assertTrue("the refused start deleted the old index", oldIndex.exists());

        Assert.assertTrue(runsDir.setExecutable(false));
        try {
            new RunStore(dataDir);
            Assert.fail("a store was made over a directory it cannot search");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("cannot be searched"));
            Assert.assertFalse("the file the search looked for is nobody's to go looking for: "
                    + expected.getMessage(), expected.getMessage().contains(DataDirectory.SEARCH_PROBE));
        } finally {
            runsDir.setExecutable(true);
        }
        Assert.assertTrue("the refused start deleted the old index", oldIndex.exists());

        new RunStore(dataDir);   // readable: made, and the old index goes as it always did
        Assert.assertFalse(oldIndex.exists());
        Assert.assertEquals(1, registry(dataDir).countRuns(null));
    }

    /**
     * The directory is asked by the scan too, not only when the store is made: one that was
     * closed between the two gave a scan that found no file to be a file, and so no run.
     */
    @Test
    public void aScanOfADirectoryThatWasClosedSinceIsRefusedToo() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1", "r2");
        File runsDir = new File(dataDir, "runs");
        RunStore store = new RunStore(dataDir);
        Assert.assertEquals(2, store.loadAll().size());

        Assert.assertTrue(runsDir.setExecutable(false));
        try {
            int found = store.loadAll().size();
            Assert.fail("the scan answered with " + found + " runs");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("cannot be searched"));
        } finally {
            runsDir.setExecutable(true);
        }

        Assert.assertTrue(runsDir.setReadable(false));
        try {
            int found = store.loadAll().size();
            Assert.fail("the scan answered with " + found + " runs");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("cannot be listed"));
        } finally {
            runsDir.setReadable(true);
        }
        Assert.assertEquals(2, store.loadAll().size());
    }

    /**
     * Closed while the scan goes: the files looked at from then on are all "no file", and the
     * runs found before that are not all there is. The directory is asked when the looks are
     * done, so such a scan is refused and not handed over short. (The scan is made to close the
     * directory itself here: at its warning about a file that is no run record.)
     */
    @Test
    public void aScanDuringWhichTheDirectoryWasClosedIsRefused() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1", "r2", "r3");
        final File runsDir = new File(dataDir, "runs");
        Files.write(new File(runsDir, "broken.json").toPath(), "not json".getBytes(StandardCharsets.UTF_8));
        RunStore store = new RunStore(dataDir);

        Heard heard = new Heard("RunStore") {
            @Override
            void heard(String warning) {
                if (warning.contains("broken.json")) {
                    runsDir.setExecutable(false);
                }
            }
        };
        try {
            int found = store.loadAll().size();
            Assert.fail("the scan answered with " + found + " runs");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
        } finally {
            heard.close();
            runsDir.setExecutable(true);
        }
        Assert.assertEquals("the scan came to the file that closed the directory",
                1, heard.warnings.size());
        Assert.assertEquals(3, store.loadAll().size());
    }

    /**
     * Kept as it was: one file that cannot be read is one run missing, said in the log, and no
     * reason not to start. The refusal is for a directory that hides all of them.
     */
    @Test
    public void oneFileThatCannotBeReadDoesNotKeepTheServerFromStarting() throws Exception {
        assumePermissionsApply();
        File dataDir = dataDirWithRuns("r1", "r2");
        File closed = new File(new File(dataDir, "runs"), "r2.json");
        byte[] before = Files.readAllBytes(closed.toPath());

        Assert.assertTrue(closed.setReadable(false));
        Heard heard = new Heard("RunStore");
        try {
            RunRegistry started = registry(dataDir);
            Assert.assertEquals(1, started.countRuns(null));
            Assert.assertNotNull(started.getRun("r1"));
            Assert.assertNull(started.getRun("r2"));
        } finally {
            heard.close();
            closed.setReadable(true);
        }
        Assert.assertEquals(heard.warnings.toString(), 1, heard.warnings.size());
        Assert.assertTrue(heard.warnings.get(0), heard.warnings.get(0).contains("cannot be read"));
        Assert.assertTrue(heard.warnings.get(0), heard.warnings.get(0).contains(closed.getAbsolutePath()));
        Assert.assertArrayEquals(before, Files.readAllBytes(closed.toPath()));
        Assert.assertEquals(2, registry(dataDir).countRuns(null));
    }

    /**
     * Kept as it was: what is in the directory and is no file is passed over without a word — a
     * directory with a run file's name, a link to nothing, a link that leads back to itself — and
     * a link to a run file is read as that file.
     */
    @Test
    public void whatIsNoFileInTheDirectoryIsPassedOver() throws Exception {
        File dataDir = dataDirWithRuns("r1");
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(new File(runsDir, "folder.json").mkdir());
        link(new File(runsDir, "nothing.json"), new File(runsDir, "no-such-file"));
        link(new File(runsDir, "loop.json"), new File(runsDir, "loop.json"));
        // A run's record kept elsewhere, and linked under its own name.
        File elsewhere = new File(tempDir(), "linked.json");
        Files.write(elsewhere.toPath(), new String(Files.readAllBytes(new File(runsDir, "r1.json").toPath()),
                StandardCharsets.UTF_8).replace("\"r1\"", "\"linked\"").getBytes(StandardCharsets.UTF_8));
        link(new File(runsDir, "linked.json"), elsewhere);

        Heard heard = new Heard("RunStore");
        RunRegistry started;
        try {
            started = registry(dataDir);
        } finally {
            heard.close();
        }
        Assert.assertEquals(2, started.countRuns(null));
        Assert.assertNotNull(started.getRun("r1"));
        Assert.assertNotNull(started.getRun("linked"));
        Assert.assertEquals("nothing is said of what is no file", Collections.emptyList(), heard.warnings);
    }

    /**
     * The directory is asked whether it can be searched by looking for a name in it. What stands
     * under that name is not the question: a link there that leads nowhere it can be followed to
     * says nothing about the directory, and is no reason to refuse the start.
     */
    @Test
    public void theDirectoryIsAskedNotThePlaceANameInItLeadsTo() throws Exception {
        File dataDir = dataDirWithRuns("r1");
        File runsDir = new File(dataDir, "runs");
        File asked = new File(runsDir, DataDirectory.SEARCH_PROBE);
        link(asked, asked);

        RunRegistry started = registry(dataDir);
        Assert.assertEquals(1, started.countRuns(null));
        Assert.assertNotNull(started.getRun("r1"));
    }

    /**
     * Kept as it was: a runs directory that is a link to a directory — on another volume, say —
     * is the directory it leads to. Only what stands under a name in it is not followed.
     */
    @Test
    public void aRunsDirectoryThatIsALinkToADirectoryIsReadAsOne() throws Exception {
        File elsewhere = new File(dataDirWithRuns("r1", "r2"), "runs");
        File dataDir = tempDir();
        link(new File(dataDir, "runs"), elsewhere);

        RunRegistry started = registry(dataDir);
        Assert.assertEquals(2, started.countRuns(null));
        Assert.assertNotNull(started.getRun("r1"));
    }

    // ===================== helpers =====================

    private static IllegalStateException assertStartRefused(File dataDir, String why) {
        File runsDir = new File(dataDir, "runs");
        try {
            RunRegistry started = registry(dataDir);
            throw new AssertionError("the server started, with " + started.countRuns(null) + " runs");
        } catch (IllegalStateException expected) {
            assertNamesTheDirectory(expected, runsDir);
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(why));
            return expected;
        }
    }

    private static void assertNamesTheDirectory(IllegalStateException refusal, File runsDir) {
        String message = String.valueOf(refusal.getMessage());
        Assert.assertTrue(message, message.contains(runsDir.getAbsolutePath()));
        Assert.assertTrue(message, message.contains("refusing to start"));
    }

    private static void assumePermissionsApply() {
        Assume.assumeFalse("needs POSIX permission semantics",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    /** Makes a link; a platform or an account that makes none skips the test. */
    private static void link(File link, File target) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (Exception e) {
            Assume.assumeNoException("needs symbolic links", e);
        }
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-runs-dir").toFile();
    }

    private static RunRegistry registry(File dataDir) {
        return new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);
    }

    /** A data directory as a server leaves it, with these ended runs in it. */
    private static File dataDirWithRuns(String... runIds) throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = registry(dataDir);
        for (String runId : runIds) {
            RunInfo run = new RunInfo();
            run.runId = runId;
            run.scriptId = "s";
            run.status = RunStatus.COMPLETED;
            run.createdAt = 1L;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            registry.register(run);
        }
        return dataDir;
    }

    /**
     * The warnings of one of the server's loggers, from when this is made until it is closed.
     * It listens at the root of the logging configuration and picks its logger's lines there: an
     * appender put on the logger itself gives that logger a configuration of its own, which
     * stays behind when the appender is taken off and keeps the logger's lines out of the log
     * for the rest of the JVM.
     */
    private static class Heard {
        final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        private final LoggerConfig root = context.getConfiguration().getRootLogger();
        private final AbstractAppender listener;

        Heard(final String logger) {
            listener = new AbstractAppender("runs-directory-start-test", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (logger.equals(event.getLoggerName()) && event.getLevel() == Level.WARN) {
                        String warning = event.getMessage().getFormattedMessage();
                        warnings.add(warning);
                        heard(warning);
                    }
                }
            };
            listener.start();
            root.addAppender(listener, Level.WARN, null);
            context.updateLoggers();
        }

        /** For a test that does something when a line is said. */
        void heard(String warning) {
        }

        void close() {
            root.removeAppender(listener.getName());
            context.updateLoggers();
            listener.stop();
        }
    }
}
