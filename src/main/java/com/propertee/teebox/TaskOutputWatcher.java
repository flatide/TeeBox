package com.propertee.teebox;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incrementally reads a task's stdout/stderr file and matches regex patterns.
 * On match, publishes captured values to the parent run's metadata.
 */
public class TaskOutputWatcher {
    private final String taskId;
    private final String runId;
    private final File stdoutFile;
    private final File stderrFile;
    private final List<CompiledRule> stdoutRules = new ArrayList<CompiledRule>();
    private final List<CompiledRule> stderrRules = new ArrayList<CompiledRule>();
    private final Set<String> matchedKeys = new HashSet<String>();
    private long stdoutOffset = 0;
    private long stderrOffset = 0;
    private String stdoutRemainder = "";
    private String stderrRemainder = "";
    private boolean allMatched = false;

    private static class CompiledRule {
        final OutputPublishRule rule;
        final Pattern pattern;

        CompiledRule(OutputPublishRule rule, Pattern pattern) {
            this.rule = rule;
            this.pattern = pattern;
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

    public boolean isAllMatched() {
        return allMatched;
    }

    /**
     * Scan for new output and return any matches found.
     * Each stream is read once, then all rules for that stream are applied.
     */
    public Map<String, Object> scan() {
        if (allMatched) return Collections.emptyMap();

        Map<String, Object> matches = new LinkedHashMap<String, Object>();

        // Read stdout once, apply all stdout rules
        if (!stdoutRules.isEmpty() && hasUnmatchedFirstOnlyRules(stdoutRules)) {
            ReadResult rr = readIncremental(stdoutFile, stdoutOffset, stdoutRemainder);
            stdoutOffset = rr.newOffset;
            stdoutRemainder = rr.remainder;
            if (rr.content.length() > 0) {
                matchRules(stdoutRules, rr.content, matches);
            }
        }

        // Read stderr once, apply all stderr rules
        if (!stderrRules.isEmpty() && hasUnmatchedFirstOnlyRules(stderrRules)) {
            ReadResult rr = readIncremental(stderrFile, stderrOffset, stderrRemainder);
            stderrOffset = rr.newOffset;
            stderrRemainder = rr.remainder;
            if (rr.content.length() > 0) {
                matchRules(stderrRules, rr.content, matches);
            }
        }

        // Check if all firstOnly rules are matched
        allMatched = !hasUnmatchedFirstOnlyRules(stdoutRules) && !hasUnmatchedFirstOnlyRules(stderrRules);

        return matches;
    }

    /**
     * Final scan that flushes remainder buffers.
     * Call when task is known to be terminated — no more output will arrive.
     */
    public Map<String, Object> finalScan() {
        // Normal scan first to pick up any new complete lines
        Map<String, Object> matches = new LinkedHashMap<String, Object>(scan());

        // Flush stdout remainder as content
        if (stdoutRemainder.length() > 0 && !stdoutRules.isEmpty()) {
            matchRules(stdoutRules, stdoutRemainder, matches);
            stdoutRemainder = "";
        }

        // Flush stderr remainder as content
        if (stderrRemainder.length() > 0 && !stderrRules.isEmpty()) {
            matchRules(stderrRules, stderrRemainder, matches);
            stderrRemainder = "";
        }

        return matches;
    }

    /**
     * Returns true if there are firstOnly rules that haven't matched yet.
     * Non-firstOnly (continuous) rules don't block allMatched — they run
     * until the task terminates regardless.
     */
    private boolean hasUnmatchedFirstOnlyRules(List<CompiledRule> rules) {
        for (CompiledRule cr : rules) {
            if (cr.rule.firstOnly && !matchedKeys.contains(cr.rule.publishKey)) {
                return true;
            }
        }
        return false;
    }

    private void matchRules(List<CompiledRule> rules, String content, Map<String, Object> matches) {
        for (CompiledRule cr : rules) {
            if (cr.rule.firstOnly && matchedKeys.contains(cr.rule.publishKey)) {
                continue;
            }
            Matcher matcher = cr.pattern.matcher(content);
            if (matcher.find()) {
                int group = cr.rule.captureGroup;
                String value = group <= matcher.groupCount() ? matcher.group(group) : matcher.group(0);
                if (value != null) {
                    matches.put(cr.rule.publishKey, value);
                    matchedKeys.add(cr.rule.publishKey);
                }
            }
        }
    }

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
            } else {
                // No complete line yet — keep as remainder
                return new ReadResult(newOffset, "", chunk);
            }
        } catch (Exception e) {
            return new ReadResult(offset, "", remainder);
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignore) {}
            }
        }
    }
}
