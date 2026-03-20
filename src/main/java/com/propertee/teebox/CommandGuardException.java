package com.propertee.teebox;

/**
 * Thrown when a task command matches a blocked pattern in CommandGuard.
 */
public class CommandGuardException extends RuntimeException {
    private final String command;
    private final String matchedPattern;

    public CommandGuardException(String command, String matchedPattern) {
        super("Command blocked by safety guard: matched pattern [" + matchedPattern + "] — command: " + command);
        this.command = command;
        this.matchedPattern = matchedPattern;
    }

    public String getCommand() {
        return command;
    }

    public String getMatchedPattern() {
        return matchedPattern;
    }
}
