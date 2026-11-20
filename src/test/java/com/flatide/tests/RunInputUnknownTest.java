package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An archived run's input is in its file only. A file that is readable JSON can still fail to give
 * it: the file is gone, it holds another run's record, or what it has under {@code properties} is
 * no object. The input is then unknown, as it is for a file that cannot be read at all — not
 * empty, and not whatever the file happens to hold. Only the run's own record can say that the
 * input is empty: with {@code {}}, or by not having the field at all (a record from before runs
 * kept their input).
 *
 * <p>Each of these used to be read as an input: shown in the run's detail, and written into the
 * run's file by the next save.
 */
public class RunInputUnknownTest {

    private static final long DAY_MS = 24L * 3600_000L;

    /** The file was replaced by the record of another run, as a copy to the wrong name does. */
    @Test
    public void aFileThatHoldsAnotherRunsRecordDoesNotGiveThisRunItsInput() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        registry.register(endedRun("r1", "mine"));
        registry.register(endedRun("r2", "someone else's"));
        registry.maintainRuns();
        Assert.assertTrue(registry.getRun("r1").archived);
        File file = runFile(dataDir, "r1");
        String own = text(file);
        String other = text(runFile(dataDir, "r2"));
        Files.write(file.toPath(), other.getBytes("UTF-8"));

        try {
            RunInfo shown = registry.getRunWithInput("r1");
            Assert.fail("another run's input was shown as this run's: " + shown.properties);
        } catch (RunStore.RunFileUnreadableException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("r1"));
        }
        registry.appendLog(registry.getRawRun("r1"), true, "a line after archiving");   // something to save
        registry.flushDirty();
        Assert.assertEquals("the file is left as it was found", other, text(file));

        // Put right, the file is this run's again and the waiting save goes through.
        Files.write(file.toPath(), own.getBytes("UTF-8"));
        Assert.assertEquals("mine", registry.getRunWithInput("r1").properties.get("owner"));
        registry.flushDirty();
        Assert.assertTrue(text(file).contains("a line after archiving"));
        Assert.assertEquals("mine", registry.getRunWithInput("r1").properties.get("owner"));
    }

    /** What the file has under {@code properties} is a list, not an object. */
    @Test
    public void anInputOfTheWrongKindIsNotAnEmptyInput() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        registry.register(endedRun("r1", "mine"));
        registry.maintainRuns();
        File file = runFile(dataDir, "r1");
        String own = text(file);
        String odd = own.replaceFirst("\"properties\": \\{[^}]*\\}", "\"properties\": [\"mine\"]");
        Assert.assertNotEquals(own, odd);
        Files.write(file.toPath(), odd.getBytes("UTF-8"));

        try {
            RunInfo shown = registry.getRunWithInput("r1");
            Assert.fail("an input that is no object was shown as: " + shown.properties);
        } catch (RunStore.RunFileUnreadableException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("r1"));
        }
        registry.appendLog(registry.getRawRun("r1"), true, "a line after archiving");
        registry.flushDirty();
        Assert.assertEquals("the file is left as it was found", odd, text(file));
    }

    /** The file is not there: moved away, or deleted by something outside the server. */
    @Test
    public void aFileThatIsGoneIsNotAnEmptyInput() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        registry.register(endedRun("r1", "mine"));
        registry.maintainRuns();
        File file = runFile(dataDir, "r1");
        File aside = new File(dataDir, "r1.json.aside");
        Assert.assertTrue(file.renameTo(aside));

        try {
            RunInfo shown = registry.getRunWithInput("r1");
            Assert.fail("the input of a run without a file was shown as: " + shown.properties);
        } catch (RunStore.RunFileUnreadableException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("r1"));
        }
        Assert.assertEquals("the rest of the run is still answered", RunStatus.COMPLETED, registry.getRun("r1").status);
        registry.appendLog(registry.getRawRun("r1"), true, "a line after archiving");
        registry.flushDirty();
        Assert.assertFalse("no record without the input is written in its place", file.exists());

        // The file is put back: the input is there again, and the waiting save keeps it.
        Assert.assertTrue(aside.renameTo(file));
        Assert.assertEquals("mine", registry.getRunWithInput("r1").properties.get("owner"));
        registry.flushDirty();
        Assert.assertTrue(text(file).contains("a line after archiving"));
        Assert.assertEquals("mine", registry.getRunWithInput("r1").properties.get("owner"));
    }

    /** A record of this run without the field: a run from before runs kept their input. */
    @Test
    public void aRecordWithoutTheFieldHasAnEmptyInput() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        Files.write(new File(runsDir, "old.json").toPath(),
            ("{\"runId\": \"old\", \"scriptId\": \"s\", \"status\": \"COMPLETED\", \"archived\": true,"
             + " \"createdAt\": 1, \"endedAt\": 2}").getBytes("UTF-8"));

        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, -1L);

        Assert.assertTrue(registry.getRunWithInput("old").properties.isEmpty());
    }

    /**
     * A start does not write over such a file either. A run that was RUNNING when the server
     * stopped is saved as SERVER_RESTARTED by the start; with a list under {@code properties} the
     * file used to be read as a run without input, and that save put {@code {}} where the list was.
     * The file is no run record — like one with any other field of the wrong kind — and is left
     * alone.
     */
    @Test
    public void aStartLeavesAFileWhoseInputIsOfTheWrongKindAlone() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        String odd = "{\"runId\": \"odd\", \"scriptId\": \"s\", \"status\": \"RUNNING\","
            + " \"createdAt\": 1, \"properties\": [\"not\", \"an object\"]}";
        Files.write(new File(runsDir, "odd.json").toPath(), odd.getBytes("UTF-8"));
        String fine = "{\"runId\": \"fine\", \"scriptId\": \"s\", \"status\": \"RUNNING\","
            + " \"createdAt\": 1, \"properties\": {\"owner\": \"mine\"}}";
        Files.write(new File(runsDir, "fine.json").toPath(), fine.getBytes("UTF-8"));

        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);

        Assert.assertEquals("the file is as it was", odd, text(new File(runsDir, "odd.json")));
        Assert.assertNull("and is not taken for a run", registry.getRun("odd"));
        // The run beside it is recovered as before.
        Assert.assertEquals(RunStatus.SERVER_RESTARTED, registry.getRun("fine").status);
        Assert.assertEquals("mine", registry.getRun("fine").properties.get("owner"));
        Assert.assertTrue(text(new File(runsDir, "fine.json")).contains("\"owner\": \"mine\""));
    }

    /**
     * A number past the double range — the literal {@code 1e999} reads as Infinity — is one no
     * JSON writer takes back, so a record that holds one can be neither saved again nor sent in an
     * answer. A start saves every run it finds unfinished: with such a number in the input of one
     * of them that save failed, and the server did not start. No run writes such a file; like any
     * other file that is no run record it is skipped, and left as it is.
     */
    @Test
    public void aStartIsNotStoppedByARecordWithANumberOutOfRange() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        Map<String, String> odd = new LinkedHashMap<String, String>();
        odd.put("odd-input", "{\"runId\": \"odd-input\", \"scriptId\": \"s\", \"status\": \"RUNNING\","
            + " \"createdAt\": 1, \"properties\": {\"rate\": 1e999}}");
        odd.put("odd-result", "{\"runId\": \"odd-result\", \"scriptId\": \"s\", \"status\": \"COMPLETED\","
            + " \"createdAt\": 1, \"endedAt\": 2, \"resultData\": {\"rows\": [1, -1e999]}}");
        odd.put("odd-published", "{\"runId\": \"odd-published\", \"scriptId\": \"s\", \"status\": \"COMPLETED\","
            + " \"createdAt\": 1, \"endedAt\": 2, \"published\": {\"port.count\": 1e999}}");
        for (Map.Entry<String, String> file : odd.entrySet()) {
            Files.write(new File(runsDir, file.getKey() + ".json").toPath(), file.getValue().getBytes("UTF-8"));
        }
        Files.write(new File(runsDir, "fine.json").toPath(),
            ("{\"runId\": \"fine\", \"scriptId\": \"s\", \"status\": \"RUNNING\", \"createdAt\": 1,"
             + " \"properties\": {\"rate\": 1e300}}").getBytes("UTF-8"));

        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);

        for (Map.Entry<String, String> file : odd.entrySet()) {
            Assert.assertNull(file.getKey() + " is not taken for a run", registry.getRun(file.getKey()));
            Assert.assertEquals(file.getKey() + " is as it was", file.getValue(),
                    text(new File(runsDir, file.getKey() + ".json")));
        }
        Assert.assertEquals("the run beside them is recovered", RunStatus.SERVER_RESTARTED, registry.getRun("fine").status);
        Assert.assertEquals(Double.valueOf(1e300), registry.getRun("fine").properties.get("rate"));
        Assert.assertEquals(1, registry.countRuns(null));
    }

    /**
     * What a start takes for this run's record, the input is read from as well. The file's name
     * is the run id; a number under {@code runId} is that id to the start (Gson maps it to its
     * text), and a null where a list belongs is a list of nothing. Reading the input more
     * strictly than the start refused the input of a run the start had accepted — and for a run
     * the server had stopped on, whose record the start saves, it kept the server from starting,
     * as the null lists did.
     */
    @Test
    public void aStartAndTheInputReadTheSameRecord() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        Files.write(new File(runsDir, "123.json").toPath(),
            ("{\"runId\": 123, \"scriptId\": \"s\", \"status\": \"RUNNING\", \"archived\": true,"
             + " \"createdAt\": 1, \"properties\": {\"k\": 1}}").getBytes("UTF-8"));
        Files.write(new File(runsDir, "456.json").toPath(),
            ("{\"runId\": 456, \"scriptId\": \"s\", \"status\": \"COMPLETED\", \"archived\": true,"
             + " \"createdAt\": 1, \"endedAt\": 2, \"properties\": {\"k\": 2}}").getBytes("UTF-8"));
        Files.write(new File(runsDir, "nulls.json").toPath(),
            ("{\"runId\": \"nulls\", \"scriptId\": \"s\", \"status\": \"RUNNING\", \"createdAt\": 1,"
             + " \"imports\": null, \"threads\": null, \"stdoutLines\": null, \"stderrLines\": null,"
             + " \"properties\": null}").getBytes("UTF-8"));

        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, -1L);   // used to throw

        Assert.assertEquals(RunStatus.SERVER_RESTARTED, registry.getRun("123").status);
        Assert.assertEquals(Integer.valueOf(1), registry.getRunWithInput("123").properties.get("k"));
        Assert.assertTrue("the start's save kept the input", text(new File(runsDir, "123.json")).contains("\"k\": 1"));
        Assert.assertEquals(Integer.valueOf(2), registry.getRunWithInput("456").properties.get("k"));

        RunInfo nulls = registry.getRun("nulls");
        Assert.assertEquals(RunStatus.SERVER_RESTARTED, nulls.status);
        Assert.assertTrue(nulls.threads.isEmpty());
        Assert.assertTrue(nulls.stdoutLines.isEmpty());
        Assert.assertTrue(nulls.stderrLines.isEmpty());
        Assert.assertTrue(nulls.properties.isEmpty());
        Assert.assertTrue(registry.listThreads("nulls").isEmpty());
        registry.appendLog(registry.getRawRun("nulls"), true, "a line");
        registry.flushDirty();
        Assert.assertTrue(text(new File(runsDir, "nulls.json")).contains("a line"));
    }

    /**
     * A run the server had stopped on is saved as ended by the start. When that record cannot be
     * made — the run is marked archived, and what its file has for an input cannot be read as
     * one — the start goes on without that run, and leaves its file alone.
     */
    @Test
    public void aStartIsNotStoppedByAnUnfinishedRunItCannotSave() throws Exception {
        File dataDir = tempDir();
        File runsDir = new File(dataDir, "runs");
        Assert.assertTrue(runsDir.mkdirs());
        String odd = "{\"runId\": \"odd\", \"scriptId\": \"s\", \"status\": \"RUNNING\", \"archived\": true,"
            + " \"createdAt\": 1, \"properties\": {\"rate\": 1e999}}";
        Files.write(new File(runsDir, "odd.json").toPath(), odd.getBytes("UTF-8"));
        Files.write(new File(runsDir, "fine.json").toPath(),
            ("{\"runId\": \"fine\", \"scriptId\": \"s\", \"status\": \"RUNNING\", \"createdAt\": 1,"
             + " \"properties\": {\"owner\": \"mine\"}}").getBytes("UTF-8"));

        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, DAY_MS, 7 * DAY_MS);   // used to throw

        Assert.assertNull(registry.getRun("odd"));
        Assert.assertEquals("the file is as it was", odd, text(new File(runsDir, "odd.json")));
        Assert.assertEquals(RunStatus.SERVER_RESTARTED, registry.getRun("fine").status);
    }

    /** The same number in the file of an archived run, met when its input is asked for. */
    @Test
    public void anInputWithANumberOutOfRangeIsNotReadAsAnInput() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        registry.register(endedRun("r1", "mine"));
        registry.maintainRuns();
        File file = runFile(dataDir, "r1");
        String own = text(file);
        String odd = own.replace("\"count\": 5", "\"count\": 1e999");
        Assert.assertNotEquals(own, odd);
        Files.write(file.toPath(), odd.getBytes("UTF-8"));

        try {
            RunInfo shown = registry.getRunWithInput("r1");
            Assert.fail("an input no answer can carry was read as: " + shown.properties);
        } catch (RunStore.RunFileUnreadableException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("properties.count"));
        }
        registry.appendLog(registry.getRawRun("r1"), true, "a line after archiving");
        registry.flushDirty();
        Assert.assertEquals("the file is left as it was found", odd, text(file));
    }

    /**
     * An input that cannot be read is reported in the server's log once, not at every look. A run
     * page left open asks for it again every five seconds — its card holds no input to keep — and
     * each of those requests used to write its own warning.
     */
    @Test
    public void anInputThatCannotBeReadIsReportedOnceNotAtEveryLook() throws Exception {
        File dataDir = tempDir();
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 0L, 7 * DAY_MS);
        registry.register(endedRun("r1", "mine"));
        registry.maintainRuns();
        File file = runFile(dataDir, "r1");
        File aside = new File(dataDir, "r1.json.aside");

        final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        // Heard at the root of the logging configuration, where the registry's lines are picked
        // out: an appender put on the "RunRegistry" logger itself gives that logger a
        // configuration of its own, which stays behind when the appender is taken off and keeps
        // the logger's lines out of the log for the rest of the JVM.
        org.apache.logging.log4j.core.LoggerContext logging =
            (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.LoggerConfig root = logging.getConfiguration().getRootLogger();
        org.apache.logging.log4j.core.Appender listener = new org.apache.logging.log4j.core.appender.AbstractAppender(
                "run-input-unknown-test", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                String said = event.getMessage().getFormattedMessage();
                if ("RunRegistry".equals(event.getLoggerName())
                        && event.getLevel() == org.apache.logging.log4j.Level.WARN
                        && said.contains("The input of run r1")) {
                    warnings.add(said);
                }
            }
        };
        listener.start();
        root.addAppender(listener, org.apache.logging.log4j.Level.WARN, null);
        logging.updateLoggers();
        try {
            Assert.assertTrue(file.renameTo(aside));
            for (int look = 0; look < 5; look++) {
                assertInputUnknown(registry, "r1");
            }
            Assert.assertEquals("five looks, one report: " + warnings, 1, warnings.size());
            Assert.assertTrue(warnings.get(0), warnings.get(0).contains("the file is not there"));

            // Readable again, and then not: that is another thing to report.
            Assert.assertTrue(aside.renameTo(file));
            Assert.assertEquals("mine", registry.getRunWithInput("r1").properties.get("owner"));
            Assert.assertTrue(file.renameTo(aside));
            assertInputUnknown(registry, "r1");
            assertInputUnknown(registry, "r1");
            Assert.assertEquals(warnings.toString(), 2, warnings.size());
        } finally {
            root.removeAppender(listener.getName());
            logging.updateLoggers();
            listener.stop();
        }
    }

    // ===================== helpers =====================

    private static void assertInputUnknown(RunRegistry registry, String runId) {
        try {
            RunInfo shown = registry.getRunWithInput(runId);
            Assert.fail("an input that cannot be read was shown as: " + shown.properties);
        } catch (RunStore.RunFileUnreadableException expected) {
            // unknown, as it should be
        }
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("teebox-run-input-unknown").toFile();
    }

    private static File runFile(File dataDir, String runId) {
        return new File(new File(dataDir, "runs"), runId + ".json");
    }

    private static RunInfo endedRun(String runId, String owner) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "s";
        run.status = RunStatus.COMPLETED;
        run.createdAt = 1L;
        run.endedAt = Long.valueOf(System.currentTimeMillis() - 60_000L);   // due for archiving, far from purge
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("owner", owner);
        input.put("count", 5);
        run.properties.putAll(input);
        return run;
    }

    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }
}
