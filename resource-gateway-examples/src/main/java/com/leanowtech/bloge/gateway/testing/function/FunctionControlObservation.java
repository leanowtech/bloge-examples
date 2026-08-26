package com.leanowtech.bloge.gateway.testing.function;

import java.util.Objects;

/** Payload-free observation of one controlled function invocation. */
public record FunctionControlObservation(
        FunctionInvocationSite site,
        String ruleId,
        FunctionControlRule.Behavior behavior,
        String invocationScopeFingerprint,
        String argumentsFingerprint,
        String resultFingerprint,
        String errorFingerprint,
        long occurrence,
        long logicalDurationMillis
) implements Comparable<FunctionControlObservation> {

    public FunctionControlObservation {
        if (site == null || ruleId == null || ruleId.isBlank() || behavior == null
                || occurrence < 1 || logicalDurationMillis < 0
                || !validFingerprint(invocationScopeFingerprint)
                || !validFingerprint(argumentsFingerprint)
                || !emptyOrFingerprint(resultFingerprint)
                || !emptyOrFingerprint(errorFingerprint)) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        ruleId = FunctionValueSupport.text(ruleId, true,
                FunctionControlException.Code.INVALID_INPUT);
        invocationScopeFingerprint = invocationScopeFingerprint.trim();
        argumentsFingerprint = argumentsFingerprint.trim();
        resultFingerprint = normalize(resultFingerprint);
        errorFingerprint = normalize(errorFingerprint);
        if (behavior == FunctionControlRule.Behavior.THROW && errorFingerprint.isBlank()) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        if ((behavior == FunctionControlRule.Behavior.THROW
                || behavior == FunctionControlRule.Behavior.TIMEOUT)
                && !resultFingerprint.isBlank()) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
    }

    @Override
    public int compareTo(FunctionControlObservation other) {
        Objects.requireNonNull(other, "other");
        int siteOrder = site.structuralKey().compareTo(other.site.structuralKey());
        if (siteOrder != 0) return siteOrder;
        int ruleOrder = ruleId.compareTo(other.ruleId);
        if (ruleOrder != 0) return ruleOrder;
        int occurrenceOrder = Long.compare(occurrence, other.occurrence);
        if (occurrenceOrder != 0) return occurrenceOrder;
        int scopeOrder = invocationScopeFingerprint.compareTo(other.invocationScopeFingerprint);
        if (scopeOrder != 0) return scopeOrder;
        return argumentsFingerprint.compareTo(other.argumentsFingerprint);
    }

    String semanticMaterial() {
        return site.structuralKey() + "|" + ruleId + "|" + behavior.name() + "|"
                + invocationScopeFingerprint + "|"
                + argumentsFingerprint + "|" + resultFingerprint + "|" + errorFingerprint
                + "|" + occurrence + "|" + logicalDurationMillis;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validFingerprint(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static boolean emptyOrFingerprint(String value) {
        return value == null || value.isBlank() || value.matches("sha256:[0-9a-f]{64}");
    }
}
