package com.leanowtech.bloge.gateway.testing.runtime;

/**
 * Stable payload-free rejection raised by the stateful mirror transaction kernel.
 *
 * <p>The exception message is exactly the public reason code. Business inputs, entity values, and
 * underlying storage messages are intentionally excluded.</p>
 */
public final class MirrorStateException extends RuntimeException {
    private final String code;

    /**
     * Creates a stable stateful-mirror rejection.
     *
     * @param code public reason code
     */
    public MirrorStateException(String code) {
        super(valid(code));
        this.code = code.trim();
    }

    /** @return stable payload-free reason code */
    public String code() {
        return code;
    }

    private static String valid(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,191}")) {
            throw new IllegalArgumentException("state rejection code is invalid");
        }
        return normalized;
    }
}
