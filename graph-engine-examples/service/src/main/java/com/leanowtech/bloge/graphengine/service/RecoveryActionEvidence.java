package com.leanowtech.bloge.graphengine.service;

/**
 * Human and automation evidence attached to an operations recovery action.
 *
 * @param reason human-readable reason supplied by the operator or automation
 * @param sourceActionCode operations action code that suggested this recovery, when known
 * @param sourceIndicatorCode SLO indicator code that triggered this recovery, when known
 * @param actor operator, service account, or automation identity that initiated the recovery
 */
public record RecoveryActionEvidence(
        String reason,
        String sourceActionCode,
        String sourceIndicatorCode,
        String actor
) {
    private static final RecoveryActionEvidence EMPTY = new RecoveryActionEvidence("", "", "", "");

    public RecoveryActionEvidence {
        reason = normalize(reason);
        sourceActionCode = normalize(sourceActionCode);
        sourceIndicatorCode = normalize(sourceIndicatorCode);
        actor = normalize(actor);
    }

    /**
     * Returns an empty evidence value for backward-compatible callers.
     */
    public static RecoveryActionEvidence empty() {
        return EMPTY;
    }

    /**
     * Returns {@code true} when no evidence fields were supplied.
     */
    public boolean emptyEvidence() {
        return reason.isEmpty()
                && sourceActionCode.isEmpty()
                && sourceIndicatorCode.isEmpty()
                && actor.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
