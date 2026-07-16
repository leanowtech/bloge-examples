package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
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
 * @param replayDependencies payload-free exact replay dependencies frozen before execution
 * @param executionServiceBindings payload-free run-scoped service bindings frozen before execution
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
        List<ReplayDependency> replayDependencies,
        List<ExecutionServiceBinding> executionServiceBindings,
        Map<String, String> defaultPolicies,
        List<String> diagnostics
) {
    /** Current effective-plan protocol version. */
    public static final String SCHEMA_VERSION_V1 = "bloge.effectiveExecutionPlan.v1";
    /** Version adding payload-free governed replay dependency identity. */
    public static final String SCHEMA_VERSION_V2 = "bloge.effectiveExecutionPlan.v2";
    /** Current version adds governed run-scoped execution-service bindings. */
    public static final String SCHEMA_VERSION = "bloge.effectiveExecutionPlan.v3";

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
        replayDependencies = replayDependencies == null ? List.of() : List.copyOf(replayDependencies);
        executionServiceBindings = executionServiceBindings == null
                ? List.of() : List.copyOf(executionServiceBindings);
        defaultPolicies = defaultPolicies == null ? Map.of() : Map.copyOf(defaultPolicies);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** Backward-compatible constructor for plans without replay dependency projections. */
    public EffectiveExecutionPlan(String schemaVersion, String planId, String planFingerprint,
                                  String authorizedPurpose, String targetFingerprint,
                                  String fixtureBundleFingerprint, List<ResolvedSite> resolvedSites,
                                  List<ReplayDependency> replayDependencies,
                                  Map<String, String> defaultPolicies, List<String> diagnostics) {
        this(schemaVersion, planId, planFingerprint, authorizedPurpose, targetFingerprint,
                fixtureBundleFingerprint, resolvedSites, replayDependencies, List.of(),
                defaultPolicies, diagnostics);
    }

    /** Backward-compatible constructor for v2 plans without execution-service bindings. */
    public EffectiveExecutionPlan(String schemaVersion, String planId, String planFingerprint,
                                  String authorizedPurpose, String targetFingerprint,
                                  String fixtureBundleFingerprint, List<ResolvedSite> resolvedSites,
                                  Map<String, String> defaultPolicies, List<String> diagnostics) {
        this(schemaVersion, planId, planFingerprint, authorizedPurpose, targetFingerprint,
                fixtureBundleFingerprint, resolvedSites, List.of(), List.of(),
                defaultPolicies, diagnostics);
    }

    /**
     * Payload-free binding of one ambient authority or nondeterminism source.
     *
     * @param service stable service kind
     * @param mode effective provider mode
     * @param available whether calls can produce a value instead of failing closed
     * @param deterministic whether equivalent call scopes produce equivalent values
     * @param configurationFingerprint digest of non-payload provider configuration
     * @param consumers frozen invocation sites whose manifests require this service
     * @param certificationGaps reasons this binding cannot support certifiable evidence when used
     */
    public record ExecutionServiceBinding(
            String service,
            String mode,
            boolean available,
            boolean deterministic,
            String configurationFingerprint,
            List<String> consumers,
            List<String> certificationGaps
    ) {
        /** Normalizes labels and creates immutable evidence lists. */
        public ExecutionServiceBinding {
            service = trimmed(service);
            mode = trimmed(mode);
            configurationFingerprint = trimmed(configurationFingerprint);
            consumers = consumers == null ? List.of() : List.copyOf(consumers);
            certificationGaps = certificationGaps == null ? List.of() : List.copyOf(certificationGaps);
        }

        /** @return whether a declared consumer makes this binding relevant before execution */
        public boolean required() {
            return !consumers.isEmpty();
        }

        /** @return whether an observed use can contribute to certifiable evidence */
        public boolean certificationEligibleWhenUsed() {
            return available && deterministic && certificationGaps.isEmpty();
        }
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

    /**
     * Payload-free identity and lineage of one replay value frozen into this plan.
     *
     * @param replayRef exact canonical replay reference
     * @param replayPayloadId stable replay payload id
     * @param revision immutable revision
     * @param fingerprint digest covering descriptor and governed value
     * @param classification governed data classification
     * @param sourceRunId signed source run id
     * @param sourceNodeId exact source node id
     * @param sourceAttempt exact source attempt
     * @param sourceRunFingerprint signed source run material fingerprint
     * @param sourcePayloadFingerprint detached source payload fingerprint
     * @param expiresAt hard payload expiry observed during plan compilation
     * @param certificationEligible whether the source can support certifiable evidence
     * @param certificationGaps bounded reasons preventing certification
     */
    public record ReplayDependency(
            String replayRef,
            String replayPayloadId,
            long revision,
            String fingerprint,
            String classification,
            String sourceRunId,
            String sourceNodeId,
            int sourceAttempt,
            String sourceRunFingerprint,
            String sourcePayloadFingerprint,
            Instant expiresAt,
            boolean certificationEligible,
            List<String> certificationGaps
    ) {
        /** Normalizes payload-free lineage and rejects incomplete exact identity. */
        public ReplayDependency {
            replayRef = trimmed(replayRef);
            ReplayPayloadRef parsed = ReplayPayloadRef.parse(replayRef);
            replayPayloadId = trimmed(replayPayloadId);
            fingerprint = trimmed(fingerprint);
            classification = trimmed(classification).toUpperCase(java.util.Locale.ROOT);
            sourceRunId = trimmed(sourceRunId);
            sourceNodeId = trimmed(sourceNodeId);
            sourceRunFingerprint = trimmed(sourceRunFingerprint);
            sourcePayloadFingerprint = trimmed(sourcePayloadFingerprint);
            if (!parsed.replayPayloadId().equals(replayPayloadId)
                    || parsed.revision() != revision || !parsed.fingerprint().equals(fingerprint)) {
                throw new IllegalArgumentException("Replay dependency fields must match replayRef.");
            }
            if (sourceAttempt <= 0) {
                throw new IllegalArgumentException("sourceAttempt must be positive");
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("expiresAt must not be null");
            }
            certificationGaps = certificationGaps == null ? List.of() : List.copyOf(certificationGaps);
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
