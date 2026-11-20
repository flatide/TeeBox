package com.flatide.tests;

import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.teebox.ManagedTaskEngine;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A server asks for its port before it does anything to its data directory. A start that cannot
 * have the port — it is taken, as it is when the server is already running; or it is no port at
 * all — has then changed nothing there.
 *
 * <p>The port used to be the last thing a start asked for. By then the runs the server had
 * stopped on were saved as SERVER_RESTARTED, the runs due for it were purged, the tasks whose
 * process was gone were written down as ended, the outbox of the webhooks was scanned and added
 * to, the first admin was seeded — and then the start failed. Started a second time by mistake,
 * a server did all of that to the data directory of the one that was running.
 */
public class StartChangesNothingTest {

    @Test
    public void aStartOnATakenPortHasChangedNothing() throws Exception {
        assumeUnix();
        File dataDir = dataDirWithWorkToDo();
        SortedMap<String, String> before = tree(dataDir);
        ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        try {
            TeeBoxServer server = new TeeBoxServer(config(dataDir, taken.getLocalPort()));
            server.start();
            server.stop();
            Assert.fail("a server was made on a port that is taken");
        } catch (BindException expected) {
            // the port is taken
        } finally {
            taken.close();
        }
        Assert.assertEquals("the start that could not have its port has changed the data directory",
                before, tree(dataDir));
    }

    @Test
    public void aStartOnAPortThatIsNoneHasChangedNothing() throws Exception {
        assumeUnix();
        File dataDir = dataDirWithWorkToDo();
        SortedMap<String, String> before = tree(dataDir);
        try {
            TeeBoxServer server = new TeeBoxServer(config(dataDir, 70000));
            server.start();
            server.stop();
            Assert.fail("a server was made on port 70000");
        } catch (IllegalArgumentException expected) {
            // no such port
        }
        Assert.assertEquals("the start on a port that is none has changed the data directory",
                before, tree(dataDir));
    }

    // ===================== helpers =====================

    /**
     * A data directory that a start has something to do in, of every kind: a run the server
     * stopped on, a run due to be purged, a run whose callback is not in the outbox, a task whose
     * process is gone, the index files of an old version, and no user yet where one is configured.
     */
    private static File dataDirWithWorkToDo() throws Exception {
        File dataDir = tempDir();

        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "host-a");
        TaskRequest request = new TaskRequest();
        request.command = "sleep 300";
        request.runId = "left";
        Task task = engine.execute(request);
        ProcessHandle process = ProcessHandle.of(task.pid).orElse(null);
        if (process != null) {
            // Ended behind the engine's back: what is on the disk still says it is running.
            for (ProcessHandle child : process.descendants().toArray(ProcessHandle[]::new)) {
                child.destroyForcibly();
            }
            process.destroyForcibly();
            process.onExit().get();
        }
        engine.shutdown();
        write(new File(dataDir, "tasks/index.json"), "{}");

        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        write(new File(runsDir, "left.json"), "{\"runId\": \"left\", \"scriptId\": \"s\","
                + " \"status\": \"RUNNING\", \"createdAt\": 1, \"properties\": {}}");
        write(new File(runsDir, "old.json"), "{\"runId\": \"old\", \"scriptId\": \"s\","
                + " \"status\": \"COMPLETED\", \"archived\": true, \"createdAt\": 1, \"endedAt\": 2}");
        write(new File(runsDir, "done.json"), "{\"runId\": \"done\", \"scriptId\": \"s\","
                + " \"status\": \"COMPLETED\", \"createdAt\": 1, \"endedAt\": " + (System.currentTimeMillis() - 1000L)
                + ", \"callback\": {\"url\": \"http://127.0.0.1:1/hook\"}}");
        write(new File(runsDir, "index.json"), "[]");
        return dataDir;
    }

    private static TeeBoxConfig config(File dataDir, int port) {
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = port;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;
        config.adminUser = "admin";
        config.adminPassword = "first-password";
        config.webhookEnabled = true;
        config.webhookUrlAllowlist = "127.0.0.1:1";
        return config;
    }

    /** Every directory and every file under a directory, with what is in each file. */
    private static SortedMap<String, String> tree(File root) throws IOException {
        SortedMap<String, String> tree = new TreeMap<String, String>();
        Path base = root.toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                String name = base.relativize(path).toString();
                tree.put(name, Files.isDirectory(path) ? "<directory>"
                        : new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
            }
        }
        return tree;
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("teebox-start-changes-nothing").toFile();
    }

    private static void assumeUnix() {
        Assume.assumeFalse("launches a task with a Unix shell",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
    }
}
