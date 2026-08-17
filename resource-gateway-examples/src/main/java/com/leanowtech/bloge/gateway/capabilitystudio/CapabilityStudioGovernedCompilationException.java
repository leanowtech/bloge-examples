package com.leanowtech.bloge.gateway.capabilitystudio;

/**
 * Safe, fail-closed error raised before a Capability Studio compilation can be published.
 *
 * <p>The message contains only a stable code and source path. In particular, it never includes
 * scenario inputs, fixture values, mock responses, or exception messages originating from a
 * payload-bearing adapter.</p>
 */
public final class CapabilityStudioGovernedCompilationException extends RuntimeException {

    private static final int MAX_FIELD_LENGTH = 512;

    private final String code;
    private final String path;

    public CapabilityStudioGovernedCompilationException(String code, String path) {
        super(safeMessage(code, path));
        this.code = normalized(code);
        this.path = normalized(path);
    }

    /** @return stable machine-readable rejection code */
    public String code() {
        return code;
    }

    /** @return bounded source coordinate, never a payload value */
    public String path() {
        return path;
    }

    private static String safeMessage(String code, String path) {
        return normalized(code) + " at " + normalized(path);
    }

    private static String normalized(String value) {
        String normalized = value == null || value.isBlank() ? "UNKNOWN" : value.trim();
        return normalized.length() <= MAX_FIELD_LENGTH
                ? normalized : normalized.substring(0, MAX_FIELD_LENGTH);
    }
}
