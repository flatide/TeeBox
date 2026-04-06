package com.propertee.teebox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates task commands before execution.
 *
 * Allows normal shell syntax, but blocks commands that are likely to cause
 * accidental catastrophic damage on the host system.
 */
public class CommandGuard {

    private static final Set<String> DENIED_COMMANDS = new HashSet<String>();
    private static final Set<String> DANGEROUS_RM_PATHS = new HashSet<String>();
    private static final Set<String> SHELL_WRAPPERS = new HashSet<String>();
    static {
        DENIED_COMMANDS.add("sudo");
        DENIED_COMMANDS.add("su");
        DENIED_COMMANDS.add("shutdown");
        DENIED_COMMANDS.add("reboot");
        DENIED_COMMANDS.add("poweroff");
        DENIED_COMMANDS.add("halt");
        DENIED_COMMANDS.add("init");
        DENIED_COMMANDS.add("telinit");
        DENIED_COMMANDS.add("mkfs");
        DENIED_COMMANDS.add("mkfs.ext2");
        DENIED_COMMANDS.add("mkfs.ext3");
        DENIED_COMMANDS.add("mkfs.ext4");
        DENIED_COMMANDS.add("mkfs.xfs");
        DENIED_COMMANDS.add("mkfs.apfs");

        DANGEROUS_RM_PATHS.addAll(Arrays.asList(
                "/",
                "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/opt", "/proc",
                "/root", "/sbin", "/srv", "/sys", "/usr", "/var",
                "/Applications", "/Library", "/System", "/Users",
                "~", "$HOME", "${HOME}"
        ));

        SHELL_WRAPPERS.addAll(Arrays.asList(
                "sh", "bash", "zsh", "dash", "ksh"
        ));
    }
    public CommandGuard() {
    }

    /**
     * Validate a command with optional cwd for script path resolution.
     *
     * Allows shell syntax, but blocks dangerous destructive commands,
     * privilege-escalation commands, and missing path executables.
     */
    public void validate(String command, String cwd) {
        if (command == null) {
            return;
        }

        // 0. Reject control characters (newline injection vector)
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0') {
                throw new CommandGuardException(command,
                        "control-char:0x" + String.format("%02x", (int) c));
            }
        }

        // 1. Inspect each command segment, allowing shell syntax but blocking
        // catastrophic operations.
        for (List<String> tokens : splitCommands(command)) {
            validateTokens(tokens, cwd, command);
        }
    }

    private static File resolveScriptFile(String path, String cwd) {
        File file = new File(path);
        if (!file.isAbsolute() && cwd != null && cwd.length() > 0) {
            file = new File(cwd, path);
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }

    private void validateTokens(List<String> tokens, String cwd, String originalCommand) {
        if (tokens.isEmpty()) {
            return;
        }

        int executableIndex = findExecutableIndex(tokens);
        if (executableIndex < 0) {
            return;
        }

        String executable = tokens.get(executableIndex);
        String commandName = baseName(executable);

        if (DENIED_COMMANDS.contains(commandName)) {
            throw new CommandGuardException(originalCommand, "denied-command:" + commandName);
        }

        if ("rm".equals(commandName) && isDangerousRm(tokens, executableIndex + 1)) {
            throw new CommandGuardException(originalCommand, "dangerous-rm-target");
        }

        if (isDangerousDd(commandName, tokens, executableIndex + 1)) {
            throw new CommandGuardException(originalCommand, "dangerous-dd-target");
        }

        if (executable.indexOf('/') >= 0) {
            File executableFile = resolveScriptFile(executable, cwd);
            if (!executableFile.isFile()) {
                throw new CommandGuardException(originalCommand, "file-not-found:" + executableFile.getPath());
            }
        }

        if (SHELL_WRAPPERS.contains(commandName)) {
            String payload = extractShellPayload(tokens, executableIndex + 1);
            if (payload != null && payload.length() > 0) {
                validate(payload, cwd);
            }
        }
    }

    static List<List<String>> splitCommands(String command) {
        List<List<String>> commands = new ArrayList<List<String>>();
        List<String> currentCommand = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaping = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\' && !inSingle) {
                escaping = true;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (!inSingle && !inDouble && isShellSeparator(command, i)) {
                if (current.length() > 0) {
                    currentCommand.add(current.toString());
                    current.setLength(0);
                }
                if (!currentCommand.isEmpty()) {
                    commands.add(currentCommand);
                    currentCommand = new ArrayList<String>();
                }
                if ((c == '&' || c == '|') && i + 1 < command.length() && command.charAt(i + 1) == c) {
                    i++;
                }
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    currentCommand.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            currentCommand.add(current.toString());
        }
        if (!currentCommand.isEmpty()) {
            commands.add(currentCommand);
        }
        return commands;
    }

    private static String baseName(String executable) {
        int lastSlash = executable.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < executable.length()) {
            return executable.substring(lastSlash + 1);
        }
        return executable;
    }

    private static int findExecutableIndex(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!isEnvAssignment(tokens.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEnvAssignment(String token) {
        int eq = token.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String name = token.substring(0, eq);
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isShellSeparator(String command, int index) {
        char c = command.charAt(index);
        if (c == ';' || c == '|' || c == '&') {
            return true;
        }
        return c == '$' && index + 1 < command.length() && command.charAt(index + 1) == '(';
    }

    private static String extractShellPayload(List<String> tokens, int startIndex) {
        for (int i = startIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("-c".equals(token) || "-lc".equals(token) || "-ic".equals(token)) {
                if (i + 1 < tokens.size()) {
                    return tokens.get(i + 1);
                }
                return null;
            }
        }
        return null;
    }

    private static boolean isDangerousRm(List<String> tokens, int argsStartIndex) {
        boolean recursive = false;
        boolean parsingOptions = true;
        for (int i = argsStartIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (parsingOptions && "--".equals(token)) {
                parsingOptions = false;
                continue;
            }
            if (parsingOptions && token.startsWith("-") && token.length() > 1) {
                if (token.indexOf('r') >= 0 || token.indexOf('R') >= 0) {
                    recursive = true;
                }
                continue;
            }
            if (isDangerousRmTarget(token, recursive)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangerousRmTarget(String target, boolean recursive) {
        String normalized = normalizeTarget(target);
        if (normalized.length() == 0) {
            return false;
        }
        if ("/".equals(normalized)) {
            return true;
        }
        if (!recursive) {
            return false;
        }
        if (DANGEROUS_RM_PATHS.contains(normalized)) {
            return true;
        }
        if ("/*".equals(normalized)) {
            return true;
        }
        if (normalized.endsWith("/*")) {
            String parent = normalizeTarget(normalized.substring(0, normalized.length() - 2));
            return "/".equals(parent) || DANGEROUS_RM_PATHS.contains(parent);
        }
        for (String root : DANGEROUS_RM_PATHS) {
            if (root.length() > 1 && normalized.startsWith(root + "/") && normalized.endsWith("/*")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeTarget(String target) {
        String trimmed = target != null ? target.trim() : "";
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isDangerousDd(String commandName, List<String> tokens, int argsStartIndex) {
        if (!"dd".equals(commandName)) {
            return false;
        }
        for (int i = argsStartIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("of=/dev/")) {
                return true;
            }
        }
        return false;
    }
}
