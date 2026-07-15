package com.leanowtech.bloge.gateway.testing.domain;

import java.util.List;
import java.util.Map;

/**
 * Immutable, server-authorized execution plan produced before any graph node is scheduled.
 *
 * @param schemaVersion effective-plan schema version
 * @param planId unique plan id
 * @param planFingerprint canonical plan fingerprint
 * @param authorizedPurpose purpose minted by endpoint, identity, and server policy
 * @param targetFingerprint frozen target fingerprint
 * @param fixtureBundleFingerprint frozen fixture fingerprint
 * @param resolvedSites selector-to-site resolutions
 * @param defaultPolicies fail-closed policy decisions applied to unmatched effects
 * @param diagnostics bounded preflight diagnostics
 */
public record EffectiveExecutionPlan(
        String schemaVersion,
        String planId,
        String planFingerprint,
        String authorizedPurpose,
        String targetFingerprint,
        String fixtureBundleFingerprint,
        List<ResolvedSite> resolvedSites,
        Map<String, String> defaultPolicies,
        List<String> diagnostics
) {
    /** Current effective-plan protocol version. */
    public static final String SCHEMA_VERSION = "bloge.effectiveExecutionPlan.v1";

    /** How a frozen invocation site resolves at execution time. */
    public enum Resolution {
        REAL,
        TEST_DOUBLE,
        DENIED
    }

    /** Creates immutable plan facts. */
    public EffectiveExecutionPlan {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        planId = trimmed(planId);
        planFingerprint = trimmed(planFingerprint);
        authorizedPurpose = trimmed(authorizedPurpose);
        targetFingerprint = trimmed(targetFingerprint);
        fixtureBundleFingerprint = trimmed(fixtureBundleFingerprint);
        resolvedSites = resolvedSites == null ? List.of() : List.copyOf(resolvedSites);
        defaultPolicies = defaultPolicies == null ? Map.of() : Map.copyOf(defaultPolicies);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Preflight resolution for one invocation site.
     *
     * @param invocationSiteId primary invocation-site identity
     * @param resolution real, doubled, or denied execution
     * @param behavior effective behavior
     * @param boundary node or transport double boundary
     * @param ruleRefs ordered fixture rules contributing to the resolution
     * @param fidelity expected evidence fidelity fact
     */
    public record ResolvedSite(
            String invocationSiteId,
            Resolution resolution,
            FixtureRule.BehaviorKind behavior,
            FixtureRule.DoubleBoundary boundary,
            List<String> ruleRefs,
            String fidelity
    ) {
        /** Creates immutable site-resolution facts. */
        public ResolvedSite {
            invocationSiteId = trimmed(invocationSiteId);
            resolution = resolution == null ? Resolution.REAL : resolution;
            behavior = behavior == null ? FixtureRule.BehaviorKind.REAL : behavior;
            boundary = boundary == null ? FixtureRule.DoubleBoundary.NODE : boundary;
            ruleRefs = ruleRefs == null ? List.of() : List.copyOf(ruleRefs);
            fidelity = trimmed(fidelity);
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
