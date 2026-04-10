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
    private final List<CompiledRule> rules;
    private final Set<String> matchedKeys = new HashSet<String>();
    private long stdoutOffset = 0;
    private long stderrOffset = 0;
    private String stdoutRemainder = "";
    private String stderrRemainder = "";
    private boolean allMatched = false;

    private static class CompiledRule {
        final OutputPublishRule rule;
        final Pattern pattern;
        final boolean isStdout;

        CompiledRule(OutputPublishRule rule) {
            this.rule = rule;
            this.pattern = Pattern.compile(rule.pattern);
            this.isStdout = !"stderr".equals(rule.stream);
        }
    }

    public TaskOutputWatcher(String taskId, String runId, File taskDir, List<OutputPublishRule> rules) {
        this.taskId = taskId;
        this.runId = runId;
        this.stdoutFile = new File(taskDir, "stdout.log");
        this.stderrFile = new File(taskDir, "stderr.log");
        this.rules = new ArrayList<CompiledRule>();
        for (OutputPublishRule rule : rules) {
            if (rule.pattern != null && rule.pattern.length() > 0 && rule.publishKey != null) {
                this.rules.add(new CompiledRule(rule));
            }
        }
    }

    public String getRunId() {
        return runId;
    }

    public boolean isAllMatched() {
        return allMatched;
    }

    /**
     * Scan for new output and return any matches found.
     * Returns empty map if no new matches.
     */
    public Map<String, Object> scan() {
        if (allMatched) return Collections.emptyMap();

        Map<String, Object> matches = new LinkedHashMap<String, Object>();

        for (CompiledRule cr : rules) {
            if (cr.rule.firstOnly && matchedKeys.contains(cr.rule.publishKey)) {
                continue;
            }

            String newContent;
            if (cr.isStdout) {
                long[] result = readIncremental(stdoutFile, stdoutOffset, stdoutRemainder);
                stdoutOffset = result[0];
                newContent = extractContent(result);
                stdoutRemainder = extractRemainder(result);
            } else {
                long[] result = readIncremental(stderrFile, stderrOffset, stderrRemainder);
                stderrOffset = result[0];
                newContent = extractContent(result);
                stderrRemainder = extractRemainder(result);
            }

            if (newContent.length() == 0) continue;

            Matcher matcher = cr.pattern.matcher(newContent);
            if (matcher.find()) {
                int group = cr.rule.captureGroup;
                String value = group <= matcher.groupCount() ? matcher.group(group) : matcher.group(0);
                if (value != null) {
                    matches.put(cr.rule.publishKey, value);
                    matchedKeys.add(cr.rule.publishKey);
                }
            }
        }

        // Check if all firstOnly rules are matched
        if (!rules.isEmpty()) {
            boolean all = true;
            for (CompiledRule cr : rules) {
                if (cr.rule.firstOnly && !matchedKeys.contains(cr.rule.publishKey)) {
                    all = false;
                    break;
                }
            }
            allMatched = all;
        }

        return matches;
    }

    // Incremental file read. Returns [newOffset, contentChars..., remainderChars...]
    // encoded in a way we can extract. We use a simpler approach with instance fields.

    private String lastReadContent = "";
    private String lastReadRemainder = "";

    private long[] readIncremental(File file, long offset, String remainder) {
        lastReadContent = "";
        lastReadRemainder = "";

        if (!file.exists() || file.length() <= offset) {
            return new long[]{offset};
        }

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            long available = raf.length() - offset;
            if (available <= 0) {
                return new long[]{offset};
            }
            byte[] buf = new byte[(int) Math.min(available, 64 * 1024)];
            int read = raf.read(buf);
            if (read <= 0) {
                return new long[]{offset};
            }
            long newOffset = offset + read;
            String chunk = remainder + new String(buf, 0, read, "UTF-8");

            // Split into complete lines + remainder
            int lastNewline = chunk.lastIndexOf('\n');
            if (lastNewline >= 0) {
                lastReadContent = chunk.substring(0, lastNewline + 1);
                lastReadRemainder = chunk.substring(lastNewline + 1);
            } else {
                // No complete line yet — keep as remainder
                lastReadRemainder = chunk;
            }
            return new long[]{newOffset};
        } catch (Exception e) {
            return new long[]{offset};
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignore) {}
            }
        }
    }

    private String extractContent(long[] result) {
        return lastReadContent;
    }

    private String extractRemainder(long[] result) {
        return lastReadRemainder;
    }
}
