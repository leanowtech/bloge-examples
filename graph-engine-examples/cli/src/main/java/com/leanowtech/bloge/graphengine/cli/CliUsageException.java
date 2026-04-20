package com.leanowtech.bloge.graphengine.cli;

/**
 * Signals invalid CLI usage or an explicit usage/help request.
 */
public final class CliUsageException extends Exception {

    private final boolean usageOnly;

    /**
     * Creates an exception for invalid CLI usage.
     *
     * @param message human-readable usage error
     */
    public CliUsageException(String message) {
        this(message, false);
    }

    private CliUsageException(String message, boolean usageOnly) {
        super(message);
        this.usageOnly = usageOnly;
    }

    /**
     * Creates an exception that indicates the caller requested usage output only.
     *
     * @return usage-only exception instance
     */
    public static CliUsageException forUsageOnly() {
        return new CliUsageException("", true);
    }

    /**
     * Returns whether the exception represents a help/usage request instead of an error.
     *
     * @return {@code true} when only usage output should be printed
     */
    public boolean usageOnly() {
        return usageOnly;
    }
}
