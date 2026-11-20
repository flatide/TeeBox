package com.flatide.tests;

import com.flatide.teebox.RunManager;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxMain;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.UserStore;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A start that fails ends, and says why where an operator looks. It used to do neither in the
 * cases that come after the run manager is made — the port is taken, the users directory cannot
 * be written: the exception left the main thread, the run manager's threads stayed, and the
 * process went on without a listener, sweeping and flushing the data directory as a server that
 * nobody could reach. And of no start that failed was there a line in the log file: the reason
 * went to stderr alone.
 */
public class StartFailureTest {

    private static final long CHILD_WAIT_SECONDS = 60L;

    /** In this JVM: a server that is not made leaves no thread of its run manager behind. */
    @Test
    public void aServerThatIsNotMadeLeavesNoThreadBehind() throws Exception {
        ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        try {
            // The port is taken: an IOException.
            TeeBoxConfig config = serverConfig(tempDir(), taken.getLocalPort());
            Set<Thread> before = liveThreads();
            try {
                TeeBoxServer server = new TeeBoxServer(config);
                server.start();
                server.stop();
                Assert.fail("a server was made on a port that is taken");
            } catch (BindException expected) {
                // the port is taken
            }
            assertNoThreadLeftSince(before);
        } finally {
            taken.close();
        }

        // No such port: a RuntimeException, at the same place.
        TeeBoxConfig config = serverConfig(tempDir(), 70000);
        Set<Thread> before = liveThreads();
        try {
            TeeBoxServer server = new TeeBoxServer(config);
            server.start();
            server.stop();
            Assert.fail("a server was made on port 70000");
        } catch (IllegalArgumentException expected) {
            // the port is out of range
        }
        assertNoThreadLeftSince(before);
    }

    /**
     * A server asked for without a data directory is refused before any store is made: the first
     * store would otherwise make its directory wherever the process happens to stand.
     */
    @Test
    public void aServerWithoutADataDirectoryIsRefusedBeforeAnythingIsMade() throws Exception {
        TeeBoxConfig config = serverConfig(null, 0);
        try {
            TeeBoxServer server = new TeeBoxServer(config);
            server.start();
            server.stop();
            Assert.fail("a server was made without a data directory");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("dataDir is required"));
        }
    }

    /**
     * A run manager starts its own background work before its constructor is through, and can
     * fail after that. It stops what it started. (The file here is one way to fail it that late:
     * a null in a run's thread list fails the copy made for the webhook reconcile — that it does
     * is a defect of its own.)
     */
    @Test
    public void aRunManagerThatFailsLateLeavesNoThreadBehind() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        Files.write(new File(runsDir, "odd.json").toPath(),
                ("{\"runId\": \"odd\", \"scriptId\": \"s\", \"status\": \"COMPLETED\", \"createdAt\": 1,"
                 + " \"endedAt\": 2, \"threads\": [null]}").getBytes(StandardCharsets.UTF_8));
        TeeBoxConfig config = new TeeBoxConfig();
        config.dataDir = dataDir;
        config.webhookEnabled = true;

        Set<Thread> before = liveThreads();
        try {
            RunManager manager = new RunManager(dataDir, 1, config);
            manager.shutdown();
            Assert.fail("this file no longer fails the run manager late: the test needs another way to fail it there");
        } catch (RuntimeException expected) {
            // failed after the webhook scan was started
        }
        assertNoThreadLeftSince(before);
    }

    /** The process: the port is taken, and it ends with the reason in the log file. */
    @Test
    public void aStartOnATakenPortEndsTheProcessAndSaysWhyInTheLog() throws Exception {
        assumeNotRoot();
        File dataDir = tempDir();
        ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        try {
            Child child = startServerProcess(dataDir, taken.getLocalPort());
            child.assertEndedByItself(1);
            String log = child.log();
            Assert.assertTrue("the log file says the start failed:\n" + log, log.contains("Start failed"));
            Assert.assertTrue("... and why:\n" + log, log.contains(BindException.class.getName()));
            // A line of its own, beside the one the logging writes to the console (that one
            // begins with the time): what is seen where the logging has no console.
            Assert.assertTrue("stderr says it too, in a line of its own:\n" + child.output(),
                    Pattern.compile("(?m)^\\[TeeBox\\] Start failed: ").matcher(child.output()).find());
        } finally {
            taken.close();
        }
    }

    /**
     * The process ends whatever is still running in it. Every thread this server starts is
     * stopped when its start fails, so the tests above cannot tell an exit from a main thread
     * that merely ends; here another thread is alive in the JVM, as one left behind would be.
     */
    @Test
    public void aFailedStartEndsTheProcessWhateverIsStillRunning() throws Exception {
        assumeNotRoot();
        File dataDir = tempDir();
        Files.write(new File(dataDir, "runs").toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        Child child = startProcess(StartFailureProbe.class, dataDir, 0, null);
        child.assertEndedByItself(1);
        Assert.assertTrue(child.log(), child.log().contains("Start failed"));
    }

    /**
     * Kept as it was: a start as root is refused before anything is written — and a log file is
     * something written: one made by root would keep the server's own user from logging later.
     * (Root is played by an {@code id} that answers 0.)
     */
    @Test
    public void aStartAsRootLeavesNoLogFile() throws Exception {
        assumePermissionsApply();   // a shell script stands in for id
        File bin = Files.createTempDirectory("teebox-fake-id").toFile();
        File id = new File(bin, "id");
        Files.write(id.toPath(), "#!/bin/sh\necho 0\n".getBytes(StandardCharsets.UTF_8));
        Assert.assertTrue(id.setExecutable(true));

        Child child = startProcess(TeeBoxMain.class, tempDir(), 0, bin);
        child.assertEndedByItself(1);
        Assert.assertTrue(child.output(), child.output().contains("must not run as root"));
        Assert.assertFalse("a start as root made a log directory", child.logDir.exists());
    }

    /** The process: a start that is refused ends with the refusal in the log file. */
    @Test
    public void aRefusedStartEndsTheProcessAndSaysWhyInTheLog() throws Exception {
        assumeNotRoot();
        File dataDir = tempDir();
        Files.write(new File(dataDir, "runs").toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));

        Child child = startServerProcess(dataDir, 0);
        child.assertEndedByItself(1);
        String log = child.log();
        Assert.assertTrue("the log file says the start failed:\n" + log, log.contains("Start failed"));
        Assert.assertTrue("... and why:\n" + log, log.contains("is not a directory"));
        Assert.assertTrue(log, log.contains("refusing to start"));
    }

    /**
     * The process: over a users directory it cannot search, it used to start — with an admin UI
     * that asked nobody to log in. It ends.
     */
    @Test
    public void aStartOverAUsersDirectoryItCannotSearchEndsTheProcess() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN);
        File usersDir = new File(dataDir, "users");

        Assert.assertTrue(usersDir.setExecutable(false));
        try {
            Child child = startServerProcess(dataDir, 0);
            child.assertEndedByItself(1);
            Assert.assertTrue(child.log(), child.log().contains("cannot be searched"));
        } finally {
            usersDir.setExecutable(true);
        }
    }

    /** Kept as it was: a start that succeeds serves, and stays until it is told to stop. */
    @Test
    public void aStartThatSucceedsServes() throws Exception {
        assumeNotRoot();
        File dataDir = tempDir();
        Child child = startServerProcess(dataDir, 0);
        boolean ended;
        try {
            int port = child.awaitListeningPort();
            HttpURLConnection health = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
            health.setConnectTimeout(10000);
            health.setReadTimeout(10000);
            health.setRequestProperty("Connection", "close");   // no idle connection for the stop below to wait out
            int answered = health.getResponseCode();   // 503 when the disk is nearly full: served all the same
            Assert.assertTrue("GET /health answered " + answered, answered == 200 || answered == 503);
            health.disconnect();
            Assert.assertTrue("the server is still there", child.process.isAlive());
        } finally {
            child.process.destroy();
            ended = child.process.waitFor(CHILD_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!ended) {
                child.process.destroyForcibly();
            }
        }
        Assert.assertTrue("the server did not end when it was told to. It said:\n" + child.output(), ended);
        Assert.assertFalse("a start that succeeded is not said to have failed:\n" + child.log(),
                child.log().contains("Start failed"));
    }

    // ===================== helpers =====================

    /** A server started as its own process, the way the start script does it. */
    private static final class Child {
        final Process process;
        final File output;
        final File logDir;

        Child(Process process, File output, File logDir) {
            this.process = process;
            this.output = output;
            this.logDir = logDir;
        }

        String output() throws Exception {
            return new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        }

        String log() throws Exception {
            File log = new File(logDir, "teebox.log");
            return log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
        }

        void assertEndedByItself(int exitCode) throws Exception {
            if (!process.waitFor(CHILD_WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(CHILD_WAIT_SECONDS, TimeUnit.SECONDS);
                Assert.fail("the process was still there after " + CHILD_WAIT_SECONDS + " s. It said:\n" + output());
            }
            Assert.assertEquals("exit code; the process said:\n" + output(), exitCode, process.exitValue());
        }

        /** The port in the line a server prints once it listens. */
        int awaitListeningPort() throws Exception {
            Pattern banner = Pattern.compile("listening on http://127\\.0\\.0\\.1:(\\d+)/admin");
            long deadline = System.currentTimeMillis() + CHILD_WAIT_SECONDS * 1000L;
            while (true) {
                Matcher matcher = banner.matcher(output());
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
                Assert.assertTrue("the process ended before it listened. It said:\n" + output(), process.isAlive());
                Assert.assertTrue("no listening line after " + CHILD_WAIT_SECONDS + " s. The process said:\n" + output(),
                        System.currentTimeMillis() < deadline);
                Thread.sleep(50L);
            }
        }
    }

    private static Child startServerProcess(File dataDir, int port) throws Exception {
        return startProcess(TeeBoxMain.class, dataDir, port, null);
    }

    /** @param firstOnPath a directory to search for commands before all others, or null */
    private static Child startProcess(Class<?> main, File dataDir, int port, File firstOnPath) throws Exception {
        File work = Files.createTempDirectory("teebox-start-process").toFile();
        File output = new File(work, "output.txt");
        File logDir = new File(work, "logs");
        List<String> command = new ArrayList<String>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-Dpropertee.teebox.dataDir=" + dataDir.getAbsolutePath());
        command.add("-Dpropertee.teebox.bind=127.0.0.1");
        command.add("-Dpropertee.teebox.port=" + port);
        command.add("-Dpropertee.teebox.logDir=" + logDir.getAbsolutePath());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main.getName());
        ProcessBuilder builder = new ProcessBuilder(command).directory(work)
                .redirectErrorStream(true).redirectOutput(output);
        if (firstOnPath != null) {
            String path = builder.environment().get("PATH");
            builder.environment().put("PATH", firstOnPath.getAbsolutePath() + File.pathSeparator + (path != null ? path : ""));
        }
        return new Child(builder.start(), output, logDir);
    }

    private static TeeBoxConfig serverConfig(File dataDir, int port) {
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = port;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;
        return config;
    }

    private static Set<Thread> liveThreads() {
        return new HashSet<Thread>(Thread.getAllStackTraces().keySet());
    }

    private static void assertNoThreadLeftSince(Set<Thread> before) throws Exception {
        List<String> left = threadsLeftSince(before);
        Assert.assertTrue("threads of what was not made are still running: " + left, left.isEmpty());
    }

    /** Threads that were not there before, are no daemons, and are still alive after a moment to end in. */
    private static List<String> threadsLeftSince(Set<Thread> before) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (true) {
            List<String> left = new ArrayList<String>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && !thread.isDaemon() && !before.contains(thread)) {
                    left.add(thread.getName());
                }
            }
            if (left.isEmpty() || System.currentTimeMillis() >= deadline) {
                return left;
            }
            Thread.sleep(20L);
        }
    }

    /** A server does not start as root at all (see {@link #aStartAsRootLeavesNoLogFile}). */
    private static void assumeNotRoot() {
        Assume.assumeFalse("a server does not start as root", "root".equals(System.getProperty("user.name")));
    }

    private static void assumePermissionsApply() {
        Assume.assumeFalse("needs POSIX permission semantics",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-start-failure").toFile();
    }
}
