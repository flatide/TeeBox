package com.flatide.teebox;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incrementally reads a task's stdout/stderr file and matches regex patterns.
 * On match, publishes captured values to the parent run's metadata.
 *
 * A rule captures up to its maxCaptures matches (1 = the first match only — the default,
 * 0 = unlimited, i.e. every match until the task terminates). All matches in a chunk are
 * taken, not just the first, up to the remaining cap. Rules must be normalize()d before
 * reaching the watcher.
 */
public class TaskOutputWatcher {
    private final String taskId;
    private final String runId;
    private final File stdoutFile;
    private final File stderrFile;
    private final List<CompiledRule> stdoutRules = new ArrayList<CompiledRule>();
    private final List<CompiledRule> stderrRules = new ArrayList<CompiledRule>();
    private long stdoutOffset = 0;
    private long stderrOffset = 0;
    /** Undecoded bytes after the last newline seen. Kept as BYTES, not String: chunks end at
     *  arbitrary 64KB boundaries, and decoding a chunk that splits a multi-byte UTF-8 character
     *  would corrupt it into replacement characters (missing Unicode captures). Decoding happens
     *  only up to a newline — a hard character boundary in UTF-8. */
    private byte[] stdoutRemainder = EMPTY;
    private byte[] stderrRemainder = EMPTY;
    private boolean allComplete = false;

    private static final byte[] EMPTY = new byte[0];

    /** Byte budget per scan() tick, so one chatty task can't monopolize the shared flush
     *  thread. finalScan() ignores it and drains to EOF — completion must not lose matches. */
    private static final long SCAN_BYTES_PER_TICK = 1024 * 1024;

    private static class CompiledRule {
        final OutputPublishRule rule;
        final Pattern pattern;
        int captureCount = 0;

        CompiledRule(OutputPublishRule rule, Pattern pattern) {
            this.rule = rule;
            this.pattern = pattern;
        }

        boolean isComplete() {
            return rule.maxCaptures > 0 && captureCount >= rule.maxCaptures;
        }
    }

    private static class ReadResult {
        final long newOffset;
        final String content;      // decoded complete lines (ends at a newline — safe to decode)
        final byte[] remainder;    // undecoded bytes after the last newline

        ReadResult(long newOffset, String content, byte[] remainder) {
            this.newOffset = newOffset;
            this.content = content;
            this.remainder = remainder;
        }
    }

    public TaskOutputWatcher(String taskId, String runId, File taskDir, List<OutputPublishRule> rules) {
        this.taskId = taskId;
        this.runId = runId;
        this.stdoutFile = new File(taskDir, "stdout.log");
        this.stderrFile = new File(taskDir, "stderr.log");
        for (OutputPublishRule rule : rules) {
            if (rule.pattern == null || rule.pattern.length() == 0 || rule.publishKey == null) continue;
            Pattern compiled = Pattern.compile(rule.pattern);
            CompiledRule cr = new CompiledRule(rule, compiled);
            if ("stderr".equals(rule.stream)) {
                stderrRules.add(cr);
            } else {
                stdoutRules.add(cr);
            }
        }
    }

    /** Validate all patterns at registration time. Throws PatternSyntaxException on bad regex. */
    public static void validateRules(List<OutputPublishRule> rules) {
        if (rules == null) return;
        for (OutputPublishRule rule : rules) {
            if (rule.pattern != null && rule.pattern.length() > 0) {
                Pattern.compile(rule.pattern);
            }
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public String getRunId() {
        return runId;
    }

    /** True when every rule is complete (maxCaptures reached) — the watcher has nothing
     *  left to capture. Rules with maxCaptures=0 never complete, so a watcher holding one
     *  lives until its task terminates. */
    public boolean isAllMatched() {
        return allComplete;
    }

    /**
     * Scan for new output and return any matches found, in capture order per key.
     * Each stream is read once (up to the per-tick byte budget), then all rules for
     * that stream are applied.
     *
     * <p>Synchronized (as is {@link #finalScan()}): the periodic flush tick (maintenance scheduler
     * thread) and the run-completion flush (run executor thread) can hit the same watcher
     * concurrently, and offsets/remainders/capture counts are plain mutable state — unserialized,
     * captures could duplicate or overrun maxCaptures.
     */
    public synchronized Map<String, List<String>> scan() {
        return scan(SCAN_BYTES_PER_TICK);
    }

    private Map<String, List<String>> scan(long byteBudget) {
        if (allComplete) return Collections.emptyMap();

        Map<String, List<String>> matches = new LinkedHashMap<String, List<String>>();

        if (hasIncompleteRules(stdoutRules)) {
            long budget = byteBudget;
            while (budget > 0) {
                ReadResult rr = readIncremental(stdoutFile, stdoutOffset, stdoutRemainder);
                if (rr.newOffset == stdoutOffset) break;   // caught up
                budget -= (rr.newOffset - stdoutOffset);
                stdoutOffset = rr.newOffset;
                stdoutRemainder = rr.remainder;
                if (rr.content.length() > 0) {
                    matchRules(stdoutRules, rr.content, matches);
                }
                if (!hasIncompleteRules(stdoutRules)) break;
            }
        }

        if (hasIncompleteRules(stderrRules)) {
            long budget = byteBudget;
            while (budget > 0) {
                ReadResult rr = readIncremental(stderrFile, stderrOffset, stderrRemainder);
                if (rr.newOffset == stderrOffset) break;
                budget -= (rr.newOffset - stderrOffset);
                stderrOffset = rr.newOffset;
                stderrRemainder = rr.remainder;
                if (rr.content.length() > 0) {
                    matchRules(stderrRules, rr.content, matches);
                }
                if (!hasIncompleteRules(stderrRules)) break;
            }
        }

        allComplete = !hasIncompleteRules(stdoutRules) && !hasIncompleteRules(stderrRules);

        return matches;
    }

    /**
     * Final scan that drains both streams to EOF and flushes remainder buffers.
     * Call when task is known to be terminated — no more output will arrive. Unbudgeted:
     * the incremental scan may be behind a fast writer, and completion is the last chance
     * to see the tail.
     */
    public synchronized Map<String, List<String>> finalScan() {
        Map<String, List<String>> matches = new LinkedHashMap<String, List<String>>(scan(Long.MAX_VALUE));

        if (stdoutRemainder.length > 0 && !stdoutRules.isEmpty()) {
            matchRules(stdoutRules, decodeUtf8(stdoutRemainder), matches);
            stdoutRemainder = EMPTY;
        }

        if (stderrRemainder.length > 0 && !stderrRules.isEmpty()) {
            matchRules(stderrRules, decodeUtf8(stderrRemainder), matches);
            stderrRemainder = EMPTY;
        }

        return matches;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);   // UTF-8 is always present
        }
    }

    /** Returns true if any rule on this stream still has captures left to make. */
    private boolean hasIncompleteRules(List<CompiledRule> rules) {
        for (CompiledRule cr : rules) {
            if (!cr.isComplete()) {
                return true;
            }
        }
        return false;
    }

    private void matchRules(List<CompiledRule> rules, String content, Map<String, List<String>> matches) {
        for (CompiledRule cr : rules) {
            if (cr.isComplete()) {
                continue;
            }
            Matcher matcher = cr.pattern.matcher(content);
            while (matcher.find()) {
                int group = cr.rule.captureGroup;
                String value = group <= matcher.groupCount() ? matcher.group(group) : matcher.group(0);
                if (value != null) {
                    List<String> values = matches.get(cr.rule.publishKey);
                    if (values == null) {
                        values = new ArrayList<String>();
                        matches.put(cr.rule.publishKey, values);
                    }
                    values.add(value);
                    cr.captureCount++;
                }
                if (cr.isComplete()) {
                    break;
                }
            }
        }
    }

    /** Max size of the unflushed remainder buffer. Beyond this, the buffer is treated
     *  as a "long line" — matched as-is then cleared to prevent unbounded growth. */
    private static final int MAX_REMAINDER_BYTES = 1024 * 1024;

    private static ReadResult readIncremental(File file, long offset, byte[] remainder) {
        if (!file.exists() || file.length() <= offset) {
            return new ReadResult(offset, "", remainder);
        }

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            long available = raf.length() - offset;
            if (available <= 0) {
                return new ReadResult(offset, "", remainder);
            }
            byte[] buf = new byte[(int) Math.min(available, 64 * 1024)];
            int read = raf.read(buf);
            if (read <= 0) {
                return new ReadResult(offset, "", remainder);
            }
            long newOffset = offset + read;
            byte[] chunk = new byte[remainder.length + read];
            System.arraycopy(remainder, 0, chunk, 0, remainder.length);
            System.arraycopy(buf, 0, chunk, remainder.length, read);

            // Split at the last newline — 0x0A is never part of a multi-byte UTF-8 sequence, so
            // everything before it decodes cleanly even when the raw read ended mid-character.
            int lastNewline = -1;
            for (int i = chunk.length - 1; i >= 0; i--) {
                if (chunk[i] == (byte) '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline >= 0) {
                String content = new String(chunk, 0, lastNewline + 1, "UTF-8");
                byte[] rest = new byte[chunk.length - lastNewline - 1];
                System.arraycopy(chunk, lastNewline + 1, rest, 0, rest.length);
                return new ReadResult(newOffset, content, rest);
            }
            // No complete line yet — keep as remainder, but cap to prevent unbounded growth
            if (chunk.length > MAX_REMAINDER_BYTES) {
                // Treat oversized buffer as content (flush) and drop remainder
                return new ReadResult(newOffset, new String(chunk, "UTF-8"), EMPTY);
            }
            return new ReadResult(newOffset, "", chunk);
        } catch (Exception e) {
            return new ReadResult(offset, "", remainder);
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignore) {}
            }
        }
    }
}
