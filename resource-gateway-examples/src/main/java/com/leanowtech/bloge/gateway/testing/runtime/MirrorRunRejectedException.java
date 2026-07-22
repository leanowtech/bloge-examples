package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.List;

/** Stable, bounded, payload-free rejection raised before a mirror node is scheduled. */
public final class MirrorRunRejectedException extends IllegalArgumentException {
    private static final int MAXIMUM_DIAGNOSTICS = 20;
    private static final int MAXIMUM_DIAGNOSTIC_LENGTH = 300;

    private final String code;
    private final List<String> diagnostics;

    /**
     * Creates a runtime admission rejection.
     *
     * @param code stable {@code RG.MIRROR.*} machine code
     * @param diagnostics bounded structural diagnostics without business values
     */
    public MirrorRunRejectedException(String code, List<String> diagnostics) {
        super(normalizedCode(code));
        this.code = normalizedCode(code);
        this.diagnostics = diagnostics == null ? List.of() : diagnostics.stream()
                .limit(MAXIMUM_DIAGNOSTICS).map(MirrorRunRejectedException::bounded).toList();
    }

    /** @return stable machine-readable rejection code */
    public String code() {
        return code;
    }

    /** @return immutable payload-free diagnostics */
    public List<String> diagnostics() {
        return diagnostics;
    }

    private static String normalizedCode(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? "RG.MIRROR.RUN_REJECTED" : normalized;
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= MAXIMUM_DIAGNOSTIC_LENGTH
                ? normalized : normalized.substring(0, MAXIMUM_DIAGNOSTIC_LENGTH);
    }
}
