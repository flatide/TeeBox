package com.propertee.teebox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates task commands before execution.
 *
 * Rejects control characters, shell operators, denied privilege-escalation
 * commands, missing executable paths, and path executables outside allowed
 * roots.
 */
public class CommandGuard {

    private static final String SHELL_OPERATORS = ";|&><`";
    private static final Set<String> DENIED_COMMANDS = new HashSet<String>();
    static {
        DENIED_COMMANDS.add("sudo");
        DENIED_COMMANDS.add("su");
    }
    private final List<File> allowedRoots;

    public CommandGuard() {
        this(Collections.<File>emptyList());
    }

    public CommandGuard(List<File> allowedRoots) {
        this.allowedRoots = allowedRoots != null ? canonicalize(allowedRoots) : Collections.<File>emptyList();
    }

    /**
     * Validate a command with optional cwd for script path resolution.
     *
     * Rejects control characters, shell operators, denied privilege-escalation
     * commands, missing path executables, and path executables outside allowed
     * roots.
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

        // 1. Reject shell operators outside quotes
        String operator = findShellOperator(command);
        if (operator != null) {
            throw new CommandGuardException(command, "shell-operator:" + operator);
        }

        // 2. Extract executable from tokenized command
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return;
        }
        String executable = tokens.get(0);

        // 3. Explicitly block privilege-escalation commands.
        String commandName = baseName(executable);
        if (DENIED_COMMANDS.contains(commandName)) {
            throw new CommandGuardException(command, "denied-command:" + commandName);
        }

        // 4. If the executable is a path, resolve it and enforce allowed roots.
        if (executable.indexOf('/') >= 0) {
            File executableFile = resolveScriptFile(executable, cwd);
            if (!executableFile.isFile()) {
                throw new CommandGuardException(command, "file-not-found:" + executableFile.getPath());
            }
            if (!allowedRoots.isEmpty()) {
                validateWithinRoots(executableFile, command);
            }
        }
    }

    /**
     * Validate that a working directory is within allowed roots.
     */
    public void validateCwd(String cwd) {
        if (cwd == null || cwd.length() == 0 || allowedRoots.isEmpty()) {
            return;
        }
        String canonicalCwd = canonicalPath(new File(cwd));
        for (File root : allowedRoots) {
            String rootPath = root.getPath();
            if (canonicalCwd.equals(rootPath) || canonicalCwd.startsWith(rootPath + File.separator)) {
                return;
            }
        }
        throw new CommandGuardException(cwd, "cwd-outside-allowed-root:" + canonicalCwd);
    }

    private void validateWithinRoots(File scriptFile, String command) {
        String canonicalScript = canonicalPath(scriptFile);
        for (File root : allowedRoots) {
            String rootPath = root.getPath();
            if (canonicalScript.equals(rootPath) || canonicalScript.startsWith(rootPath + File.separator)) {
                return;
            }
        }
        throw new CommandGuardException(command, "outside-allowed-root:" + canonicalPath(scriptFile));
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

    static String findShellOperator(String command) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaping = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaping) {
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
            if (inSingle || inDouble) {
                continue;
            }
            if (SHELL_OPERATORS.indexOf(c) >= 0) {
                return String.valueOf(c);
            }
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                return "$(";
            }
        }
        return null;
    }

    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<String>();
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
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static List<File> canonicalize(List<File> roots) {
        List<File> result = new ArrayList<File>();
        for (File root : roots) {
            try {
                result.add(root.getCanonicalFile());
            } catch (IOException e) {
                result.add(root.getAbsoluteFile());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static String baseName(String executable) {
        int lastSlash = executable.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < executable.length()) {
            return executable.substring(lastSlash + 1);
        }
        return executable;
    }
}
