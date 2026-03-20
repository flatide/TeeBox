package com.propertee.teebox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates task commands against destructive patterns before execution.
 *
 * Modes: block (default) — reject matching commands, warn — log only, off — skip validation.
 */
public class CommandGuard {

    public enum Mode { BLOCK, WARN, OFF }

    private static final List<String> DEFAULT_PATTERNS = Collections.unmodifiableList(Arrays.asList(
        "rm\\s+-[^\\s]*(rf|fr)\\s+/\\s*$",
        "rm\\s+-[^\\s]*(rf|fr)\\s+/[a-z]+/?(\\s|$)",
        "\\bmkfs[.\\s]",
        "\\bdd\\b.*\\bof=/dev/",
        "\\S+\\(\\)\\s*\\{[^}]*\\|\\s*\\S+\\s*&",
        "(^|[;&|]\\s*)(sudo\\s+)?(shutdown|reboot|poweroff|halt)\\b"
    ));

    private final Mode mode;
    private final List<Pattern> patterns;

    public CommandGuard(Mode mode, List<Pattern> patterns) {
        this.mode = mode;
        this.patterns = Collections.unmodifiableList(new ArrayList<>(patterns));
    }

    /**
     * Build a CommandGuard from configuration values.
     *
     * @param modeStr        "block", "warn", or "off" (null defaults to "block")
     * @param extraPatterns  comma-separated additional patterns (may be null)
     * @param patternsFile   path to external patterns file (may be null); replaces defaults if set
     */
    public static CommandGuard fromConfig(String modeStr, String extraPatterns, String patternsFile) {
        Mode mode = parseMode(modeStr);
        if (mode == Mode.OFF) {
            return new CommandGuard(mode, Collections.<Pattern>emptyList());
        }

        List<String> rawPatterns;
        if (patternsFile != null && patternsFile.trim().length() > 0) {
            rawPatterns = loadPatternsFile(new File(patternsFile.trim()));
        } else {
            rawPatterns = new ArrayList<>(DEFAULT_PATTERNS);
        }

        if (extraPatterns != null && extraPatterns.trim().length() > 0) {
            for (String p : extraPatterns.split(",")) {
                String trimmed = p.trim();
                if (trimmed.length() > 0) {
                    rawPatterns.add(trimmed);
                }
            }
        }

        List<Pattern> compiled = new ArrayList<>();
        for (String raw : rawPatterns) {
            try {
                compiled.add(Pattern.compile(raw));
            } catch (PatternSyntaxException e) {
                System.err.println("[CommandGuard] Invalid pattern skipped: " + raw + " — " + e.getMessage());
            }
        }

        return new CommandGuard(mode, compiled);
    }

    /**
     * Validate a command. In BLOCK mode, throws CommandGuardException on match.
     * In WARN mode, prints a warning. In OFF mode, does nothing.
     */
    public void validate(String command) {
        if (mode == Mode.OFF || command == null) {
            return;
        }
        for (Pattern p : patterns) {
            if (p.matcher(command).find()) {
                String patternStr = p.pattern();
                if (mode == Mode.BLOCK) {
                    System.err.println("[CommandGuard] BLOCKED command: " + command + " (pattern: " + patternStr + ")");
                    throw new CommandGuardException(command, patternStr);
                } else {
                    System.err.println("[CommandGuard] WARNING — dangerous command detected: " + command + " (pattern: " + patternStr + ")");
                    return;
                }
            }
        }
    }

    public Mode getMode() {
        return mode;
    }

    public List<Pattern> getPatterns() {
        return patterns;
    }

    static List<String> getDefaultPatterns() {
        return DEFAULT_PATTERNS;
    }

    private static Mode parseMode(String modeStr) {
        if (modeStr == null || modeStr.trim().length() == 0) {
            return Mode.BLOCK;
        }
        String normalized = modeStr.trim().toLowerCase();
        if ("warn".equals(normalized)) {
            return Mode.WARN;
        } else if ("off".equals(normalized)) {
            return Mode.OFF;
        } else {
            return Mode.BLOCK;
        }
    }

    private static List<String> loadPatternsFile(File file) {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("CommandGuard patterns file not found: " + file.getPath());
        }
        List<String> result = new ArrayList<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                    result.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read CommandGuard patterns file: " + file.getPath(), e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
        return result;
    }
}
