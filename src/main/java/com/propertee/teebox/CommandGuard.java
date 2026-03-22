package com.propertee.teebox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates task commands before execution.
 *
 * Rejects control characters, shell operators, bare commands, non-.sh files,
 * and scripts outside allowed roots.
 */
public class CommandGuard {

    private static final String SHELL_OPERATORS = ";|&><`";
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
     * Rejects control characters, shell operators, bare commands,
     * non-.sh files, missing files, and scripts outside allowed roots.
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

        // 3. Executable must be a file path (contain '/')
        if (executable.indexOf('/') < 0) {
            throw new CommandGuardException(command, "bare-command:" + executable);
        }

        // 4. Resolve and verify the script file exists
        File scriptFile = resolveScriptFile(executable, cwd);
        if (!scriptFile.isFile()) {
            throw new CommandGuardException(command, "file-not-found:" + scriptFile.getPath());
        }

        // 5. Enforce .sh extension
        if (!scriptFile.getName().endsWith(".sh")) {
            throw new CommandGuardException(command, "not-shell-script:" + scriptFile.getName());
        }

        // 6. Enforce allowed roots (if configured)
        if (!allowedRoots.isEmpty()) {
            validateWithinRoots(scriptFile, command);
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
}
