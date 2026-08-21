package com.flatide.tests;

import com.flatide.teebox.OutputPublishRule;
import com.flatide.teebox.TaskOutputWatcher;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for TaskOutputWatcher's single capture knob: maxCaptures 1 (first match
 * only, the default), 0 (unlimited), N (cap), and the final-scan drain.
 */
public class TaskOutputWatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File taskDir;

    private void writeStdout(String content, boolean append) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(taskDir, "stdout.log"), append), "UTF-8");
        try {
            w.write(content);
        } finally {
            w.close();
        }
    }

    private OutputPublishRule rule(String key, String pattern, int maxCaptures) {
        OutputPublishRule r = new OutputPublishRule();
        r.publishKey = key;
        r.pattern = pattern;
        r.maxCaptures = maxCaptures;
        return r;
    }

    private TaskOutputWatcher watcher(OutputPublishRule... rules) throws Exception {
        taskDir = tmp.newFolder();
        return new TaskOutputWatcher("task-1", "run-1", taskDir, new ArrayList<OutputPublishRule>(Arrays.asList(rules)));
    }

    @Test
    public void maxCapturesOneTakesTheFirstMatchAndCompletes() throws Exception {
        TaskOutputWatcher w = watcher(rule("id", "id:\\s*(\\S+)", 1));
        writeStdout("id: first\nid: second\n", false);

        Map<String, List<String>> matches = w.scan();
        Assert.assertEquals(Arrays.asList("first"), matches.get("id"));
        Assert.assertTrue("cap of 1 reached -> watcher complete", w.isAllMatched());
    }

    @Test
    public void continuousCapturesEveryMatchInAChunk() throws Exception {
        TaskOutputWatcher w = watcher(rule("item", "item:\\s*(\\S+)", 0));
        writeStdout("item: a1\nnoise\nitem: a2\nitem: a3\n", false);

        Map<String, List<String>> matches = w.scan();
        Assert.assertEquals(Arrays.asList("a1", "a2", "a3"), matches.get("item"));
        Assert.assertFalse("unlimited rule never completes on its own", w.isAllMatched());

        // Later output keeps getting captured
        writeStdout("item: a4\n", true);
        Assert.assertEquals(Arrays.asList("a4"), w.scan().get("item"));
    }

    @Test
    public void maxCapturesStopsTheRuleAtTheCap() throws Exception {
        TaskOutputWatcher w = watcher(rule("item", "item:\\s*(\\S+)", 2));
        writeStdout("item: a1\nitem: a2\nitem: a3\n", false);

        Map<String, List<String>> matches = w.scan();
        Assert.assertEquals("cap of 2 keeps the first two", Arrays.asList("a1", "a2"), matches.get("item"));
        Assert.assertTrue("cap reached -> watcher complete", w.isAllMatched());
        Assert.assertTrue(w.scan().isEmpty());
    }

    @Test
    public void maxCapturesSpansMultipleScans() throws Exception {
        TaskOutputWatcher w = watcher(rule("item", "item:\\s*(\\S+)", 3));
        writeStdout("item: a1\nitem: a2\n", false);
        Assert.assertEquals(Arrays.asList("a1", "a2"), w.scan().get("item"));
        Assert.assertFalse(w.isAllMatched());

        writeStdout("item: a3\nitem: a4\n", true);
        Assert.assertEquals("only one capture left under the cap", Arrays.asList("a3"), w.scan().get("item"));
        Assert.assertTrue(w.isAllMatched());
    }

    @Test
    public void cappedAndUnlimitedRulesCoexist() throws Exception {
        TaskOutputWatcher w = watcher(
            rule("id", "id:\\s*(\\S+)", 1),
            rule("progress", "progress:\\s*(\\S+)", 0));
        writeStdout("id: job-9\nprogress: 10\nid: ignored\nprogress: 20\n", false);

        Map<String, List<String>> matches = w.scan();
        Assert.assertEquals(Arrays.asList("job-9"), matches.get("id"));
        Assert.assertEquals(Arrays.asList("10", "20"), matches.get("progress"));
        Assert.assertFalse("the unlimited rule keeps the watcher alive", w.isAllMatched());
    }

    @Test
    public void finalScanDrainsBeyondThePerTickBudgetAndFlushesRemainder() throws Exception {
        TaskOutputWatcher w = watcher(rule("item", "item:\\s*(\\S+)", 0));

        // ~2MB of noise (above the 1MB per-tick budget), then matches near EOF, then an
        // unterminated final line (remainder path).
        StringBuilder sb = new StringBuilder();
        String noise = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n";
        while (sb.length() < 2 * 1024 * 1024) {
            sb.append(noise);
        }
        sb.append("item: tail1\n");
        sb.append("item: tail2");   // no trailing newline
        writeStdout(sb.toString(), false);

        Map<String, List<String>> matches = w.finalScan();
        Assert.assertEquals("final scan must reach EOF and flush the remainder",
            Arrays.asList("tail1", "tail2"), matches.get("item"));
    }

    @Test
    public void negativeCaptureGroupNormalizesToFullMatch() throws Exception {
        // A raw negative group would make matcher.group(g) throw on every match — and a throwing
        // watcher used to wedge the run's terminal transition. normalize() clamps it to 0.
        OutputPublishRule r = rule("id", "id:\\s*\\S+", 1);
        r.captureGroup = -1;
        r.normalize();
        Assert.assertEquals(0, r.captureGroup);

        TaskOutputWatcher w = watcher(r);
        writeStdout("id: abc\n", false);
        Map<String, List<String>> matches = w.scan();
        Assert.assertEquals("group 0 captures the full match", Arrays.asList("id: abc"), matches.get("id"));
    }

    @Test
    public void multiByteCharacterStraddlingTheReadBoundarySurvives() throws Exception {
        // Reads end at arbitrary 64KB byte boundaries. The remainder is kept as BYTES and decoding
        // stops at a newline, so a UTF-8 character split across two reads must decode intact —
        // the old String remainder corrupted it into replacement characters.
        TaskOutputWatcher w = watcher(rule("val", "키:\\s*(\\S+)", 0));

        // Position the line so the 3-byte '한' straddles byte offset 65536 (the first read size).
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 65529; i++) {
            prefix.append('x');
        }
        prefix.append('\n');                       // newline at byte 65529
        String line = "키: 한글값\n";   // "키: 한글값" — '한' spans bytes 65535..65537
        writeStdout(prefix + line, false);

        Map<String, List<String>> matches = w.scan();
        Assert.assertEquals("split multi-byte character must decode intact",
            Arrays.asList("한글값"), matches.get("val"));
    }

    @Test
    public void stderrRulesReadTheStderrStream() throws Exception {
        OutputPublishRule stderrRule = rule("err2", "err:\\s*(\\S+)", 0);
        stderrRule.stream = "stderr";
        taskDir = tmp.newFolder();
        TaskOutputWatcher w = new TaskOutputWatcher("task-2", "run-2", taskDir,
            new ArrayList<OutputPublishRule>(Arrays.asList(stderrRule)));

        Writer sw = new OutputStreamWriter(new FileOutputStream(new File(taskDir, "stderr.log")), "UTF-8");
        try {
            sw.write("err: e1\nerr: e2\n");
        } finally {
            sw.close();
        }
        Assert.assertEquals(Arrays.asList("e1", "e2"), w.scan().get("err2"));
    }
}
