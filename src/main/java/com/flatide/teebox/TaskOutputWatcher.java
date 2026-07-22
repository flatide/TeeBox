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
 * Two rule modes:
 * - firstOnly=true (default): the rule completes on its first match; one value per key.
 * - firstOnly=false (continuous): every match is captured (all matches in a chunk, not
 *   just the first), until the rule's maxCaptures is reached (0 = unlimited, i.e. until
 *   the task terminates).
 */
public class TaskOutputWatcher {
    private final String taskId;
    private final String runId;
    private final File stdoutFile;
    private final File stderrFile;
    private final List<CompiledRule> stdoutRules = new ArrayList<CompiledRule>();
    private final List<CompiledRule> stderrRules = new ArrayList<CompiledRule>();
    private final Set<String> continuousKeys = new HashSet<String>();
    private long stdoutOffset = 0;
    private long stderrOffset = 0;
    private String stdoutRemainder = "";
    private String stderrRemainder = "";
    private boolean allComplete = false;

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
            if (rule.firstOnly) return captureCount > 0;
            return rule.maxCaptures > 0 && captureCount >= rule.maxCaptures;
        }
    }

    private static class ReadResult {
        final long newOffset;
        final String content;
        final String remainder;

        ReadResult(long newOffset, String content, String remainder) {
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
            if (!rule.firstOnly) {
                continuousKeys.add(rule.publishKey);
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

    /** True when every rule is complete (firstOnly matched / maxCaptures reached) —
     *  the watcher has nothing left to capture. Continuous rules with maxCaptures=0
     *  never complete, so a watcher holding one lives until its task terminates. */
    public boolean isAllMatched() {
        return allComplete;
    }

    /** True if this key belongs to a continuous (firstOnly=false) rule — the publish
     *  layer overwrites/accumulates these instead of first-wins. */
    public boolean isContinuousKey(String key) {
        return continuousKeys.contains(key);
    }

    /**
     * Scan for new output and return any matches found, in capture order per key.
     * Each stream is read once (up to the per-tick byte budget), then all rules for
     * that stream are applied.
     */
    public Map<String, List<String>> scan() {
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
    public Map<String, List<String>> finalScan() {
        Map<String, List<String>> matches = new LinkedHashMap<String, List<String>>(scan(Long.MAX_VALUE));

        if (stdoutRemainder.length() > 0 && !stdoutRules.isEmpty()) {
            matchRules(stdoutRules, stdoutRemainder, matches);
            stdoutRemainder = "";
        }

        if (stderrRemainder.length() > 0 && !stderrRules.isEmpty()) {
            matchRules(stderrRules, stderrRemainder, matches);
            stderrRemainder = "";
        }

        return matches;
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
                if (cr.rule.firstOnly || cr.isComplete()) {
                    break;
                }
            }
        }
    }

    /** Max size of the unflushed remainder buffer. Beyond this, the buffer is treated
     *  as a "long line" — matched as-is then cleared to prevent unbounded growth. */
    private static final int MAX_REMAINDER_BYTES = 1024 * 1024;

    private static ReadResult readIncremental(File file, long offset, String remainder) {
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
            String chunk = remainder + new String(buf, 0, read, "UTF-8");

            // Split into complete lines + remainder
            int lastNewline = chunk.lastIndexOf('\n');
            if (lastNewline >= 0) {
                return new ReadResult(newOffset, chunk.substring(0, lastNewline + 1), chunk.substring(lastNewline + 1));
            }
            // No complete line yet — keep as remainder, but cap to prevent unbounded growth
            if (chunk.length() > MAX_REMAINDER_BYTES) {
                // Treat oversized buffer as content (flush) and drop remainder
                return new ReadResult(newOffset, chunk, "");
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
