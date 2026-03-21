package com.propertee.teebox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates task commands before execution.
 *
 * Only direct script file invocations are allowed. Bare commands (rm, echo, sleep, etc.)
 * and shell operators (;, |, &amp;, &gt;, etc.) are rejected.
 */
public class CommandGuard {

    private static final String SHELL_OPERATORS = ";|&><`";

    /**
     * Validate a command with optional cwd for script path resolution.
     *
     * Rejects shell operators, bare commands, and missing script files.
     */
    public void validate(String command, String cwd) {
        if (command == null) {
            return;
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
}
