package com.leanowtech.bloge.gateway.testing.planning;

import java.util.List;

/**
 * Stable, payload-free rejection emitted before a mirror graph schedules any node.
 *
 * <p>Codes are suitable for API and evidence projection. Diagnostics are bounded structural or
 * governance facts and must never contain fixture values, replay payloads, credentials, or business
 * inputs.</p>
 */
public final class MirrorPlanRejectedException extends IllegalArgumentException {
    private final String code;
    private final List<String> diagnostics;

    /**
     * Creates one fail-closed compilation rejection.
     *
     * @param code stable {@code RG.MIRROR.*} machine code
     * @param diagnostics bounded payload-free diagnostics
     */
    public MirrorPlanRejectedException(String code, List<String> diagnostics) {
        super(code == null || code.isBlank() ? "RG.MIRROR.PLAN_REJECTED" : code.trim());
        this.code = code == null || code.isBlank() ? "RG.MIRROR.PLAN_REJECTED" : code.trim();
        this.diagnostics = diagnostics == null ? List.of() : diagnostics.stream()
                .limit(20).map(MirrorPlanRejectedException::bounded).toList();
    }

    /** @return stable machine-readable rejection code */
    public String code() {
        return code;
    }

    /** @return immutable payload-free diagnostics */
    public List<String> diagnostics() {
        return diagnostics;
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }
}
