package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, payload-free control-plane plan for one capability-mirror execution generation.
 *
 * <p>A mirror plan freezes the exact capability closure, fixture revision, external dependency
 * edges, resolver precedence, deterministic execution services, and isolation policy before a
 * graph is scheduled. Runtime code must not consult mutable registries or silently fall back to a
 * real external capability when this artifact is present.</p>
 *
 * @param schemaVersion mirror-plan protocol version
 * @param planId caller-scoped stable idempotency identity
 * @param planFingerprint canonical fingerprint with this field blanked
 * @param rootCapability exact composed capability being executed
 * @param capabilityClosureFingerprint exact sealed closure fingerprint
 * @param capabilityClosure complete root-plus-dependency snapshot closure
 * @param scope exact enterprise namespace in which every artifact is authorized
 * @param fixtureBundleRef exact existing FixtureBundle revision adapted by the compiler
 * @param externalBindings one binding for every external dependency edge
 * @param scenarioPackRef optional exact scenario pack
 * @param stateModelRefs exact state models required by capability contracts
 * @param executionServices deterministic ambient execution-service inputs
 * @param policy fail-closed mirror execution policy
 * @param compiledAt server compilation time
 * @param expiresAt hard plan expiry
 */
public record MirrorPlan(
        String schemaVersion,
        String planId,
        String planFingerprint,
        MirrorArtifactRef rootCapability,
        String capabilityClosureFingerprint,
        List<CapabilitySnapshot> capabilityClosure,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef fixtureBundleRef,
        List<ExternalBinding> externalBindings,
        MirrorArtifactRef scenarioPackRef,
        List<MirrorArtifactRef> stateModelRefs,
        ExecutionServices executionServices,
        ExecutionPolicy policy,
        Instant compiledAt,
        Instant expiresAt
) {
    /** Current mirror-plan protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorPlan.v1";
    /** Maximum external dependency edges admitted by v1. */
    public static final int MAXIMUM_EXTERNAL_BINDINGS = 10_000;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * Normalizes deterministic collections and rejects incomplete wire-level coordinates.
     * Cross-field closure, scope, policy, and expiry verification is performed by
     * {@link MirrorPlanIntegrity}.
     */
    public MirrorPlan {
        schemaVersion = version(schemaVersion);
        planId = required(planId, "planId");
        planFingerprint = normalized(planFingerprint);
        if (!planFingerprint.isBlank() && !FINGERPRINT.matcher(planFingerprint).matches()) {
            throw new IllegalArgumentException("planFingerprint must be blank or canonical SHA-256");
        }
        rootCapability = requireKind(rootCapability, "CAPABILITY", "rootCapability");
        capabilityClosureFingerprint = fingerprint(capabilityClosureFingerprint,
                "capabilityClosureFingerprint");
        capabilityClosure = capabilityClosure == null ? List.of() : capabilityClosure.stream()
                .sorted(Comparator.comparing(CapabilitySnapshot::capabilityId)
                        .thenComparingLong(CapabilitySnapshot::revision)
                        .thenComparing(CapabilitySnapshot::fingerprint))
                .toList();
        if (capabilityClosure.isEmpty()) {
            throw new IllegalArgumentException("capabilityClosure must not be empty");
        }
        if (capabilityClosure.size() > CapabilityClosure.MAXIMUM_SNAPSHOTS) {
            throw new IllegalArgumentException("capabilityClosure exceeds its snapshot limit");
        }
        scope = Objects.requireNonNull(scope, "scope");
        fixtureBundleRef = requireKind(fixtureBundleRef, "FIXTURE_BUNDLE", "fixtureBundleRef");
        externalBindings = externalBindings == null ? List.of() : externalBindings.stream()
                .sorted(Comparator.comparing(ExternalBinding::invocationSiteId)
                        .thenComparing(ExternalBinding::dependencyNodeId))
                .toList();
        if (externalBindings.size() > MAXIMUM_EXTERNAL_BINDINGS) {
            throw new IllegalArgumentException("externalBindings exceeds its limit");
        }
        if (scenarioPackRef != null) {
            scenarioPackRef = requireKind(scenarioPackRef, "SCENARIO_PACK", "scenarioPackRef");
        }
        stateModelRefs = normalizeRefs(stateModelRefs, "STATE_MODEL");
        executionServices = Objects.requireNonNull(executionServices, "executionServices");
        policy = Objects.requireNonNull(policy, "policy");
        compiledAt = Objects.requireNonNull(compiledAt, "compiledAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Returns identical plan material with a replacement canonical fingerprint.
     *
     * @param value canonical fingerprint, or blank fingerprint material
     * @return copied plan
     */
    public MirrorPlan withFingerprint(String value) {
        return new MirrorPlan(schemaVersion, planId, value, rootCapability,
                capabilityClosureFingerprint, capabilityClosure, scope, fixtureBundleRef,
                externalBindings, scenarioPackRef, stateModelRefs, executionServices, policy,
                compiledAt, expiresAt);
    }

    /**
     * Fixed resolver precedence. Enum order is the v1 precedence contract and cannot be changed
     * without a protocol revision.
     */
    public enum MirrorSource {
        SESSION_STATE,
        OWNER_SPECIFIED,
        RECORDED_EXACT,
        RECORDED_TRAJECTORY,
        RECORDED_CLUSTER,
        GOVERNED_REPLAY,
        SCHEMA_SYNTHESIZED,
        CONTRACT_MOCK,
        ABSTAINED
    }

    /** Runtime behavior when every admitted resolver declines an invocation. */
    public enum UnmatchedResolution {
        ABSTAINED
    }

    /**
     * Exact binding from one capability dependency edge to one BLOGE invocation site.
     *
     * @param parentCapabilityRef exact composed parent snapshot
     * @param dependencyNodeId dependency node id declared by the parent snapshot
     * @param capabilityRef exact external child snapshot
     * @param invocationSiteId frozen BLOGE invocation-site identity
     * @param graphPath stable path from the root runtime graph
     * @param sourceKind authoritative external source kind
     * @param sourceRef authoritative external source identifier
     * @param resolverOrder fixed-priority resolver sources admitted for this edge
     * @param fixtureRuleRefs ordered existing FixtureBundle rules used by this binding
     */
    public record ExternalBinding(
            MirrorArtifactRef parentCapabilityRef,
            String dependencyNodeId,
            MirrorArtifactRef capabilityRef,
            String invocationSiteId,
            String graphPath,
            CapabilitySnapshot.SourceKind sourceKind,
            String sourceRef,
            List<MirrorSource> resolverOrder,
            List<String> fixtureRuleRefs
    ) {
        /** Validates an exact external-edge binding and fixed resolver order. */
        public ExternalBinding {
            parentCapabilityRef = requireKind(parentCapabilityRef, "CAPABILITY", "parentCapabilityRef");
            dependencyNodeId = required(dependencyNodeId, "dependencyNodeId");
            capabilityRef = requireKind(capabilityRef, "CAPABILITY", "capabilityRef");
            invocationSiteId = required(invocationSiteId, "invocationSiteId");
            graphPath = normalizeGraphPath(graphPath);
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
            sourceRef = required(sourceRef, "sourceRef");
            resolverOrder = resolverOrder == null ? List.of() : List.copyOf(resolverOrder);
            if (resolverOrder.isEmpty() || resolverOrder.getLast() != MirrorSource.ABSTAINED) {
                throw new IllegalArgumentException("resolverOrder must end with ABSTAINED");
            }
            int previous = -1;
            Set<MirrorSource> sources = new HashSet<>();
            for (MirrorSource source : resolverOrder) {
                if (source == null || !sources.add(source) || source.ordinal() <= previous) {
                    throw new IllegalArgumentException(
                            "resolverOrder must be unique and follow the fixed v1 precedence");
                }
                previous = source.ordinal();
            }
            fixtureRuleRefs = orderedUnique(fixtureRuleRefs, "fixtureRuleRefs");
            if (fixtureRuleRefs.size() > MAXIMUM_EXTERNAL_BINDINGS) {
                throw new IllegalArgumentException("fixtureRuleRefs exceeds its limit");
            }
        }
    }

    /**
     * Deterministic ambient services controlled by the caller rather than by production state.
     *
     * @param logicalClock fixed logical clock at run start
     * @param randomSeed deterministic random and UUID seed
     * @param identityFixtureRef optional exact identity fixture
     * @param featureFlagFixtureRef optional exact feature-flag fixture
     */
    public record ExecutionServices(
            Instant logicalClock,
            long randomSeed,
            MirrorArtifactRef identityFixtureRef,
            MirrorArtifactRef featureFlagFixtureRef
    ) {
        /** Validates exact optional service fixtures. */
        public ExecutionServices {
            logicalClock = Objects.requireNonNull(logicalClock, "logicalClock");
            if (identityFixtureRef != null) {
                identityFixtureRef = requireKind(identityFixtureRef, "IDENTITY_FIXTURE",
                        "identityFixtureRef");
            }
            if (featureFlagFixtureRef != null) {
                featureFlagFixtureRef = requireKind(featureFlagFixtureRef, "FEATURE_FLAG_FIXTURE",
                        "featureFlagFixtureRef");
            }
        }
    }

    /**
     * Isolation, resource-budget, and evidence policy compiled into the immutable plan.
     *
     * @param authorizedPurpose server-authorized non-production purpose
     * @param realExternalCallsAllowed must be false for a mirror plan
     * @param externalCredentialsAllowed must be false for a mirror plan
     * @param networkEgressAllowed must be false for a mirror plan
     * @param schemaSynthesisAllowed whether schema-only synthesized results may be considered
     * @param certificationRequired whether non-certifiable sources fail the run
     * @param unmatchedResolution mandatory fail-closed resolver outcome
     * @param maximumInvocations positive total invocation budget
     * @param timeout positive whole-run logical timeout
     * @param maximumClassification highest classification authorized for this plan
     * @param allowedRegions explicit execution and data-residency allowlist
     * @param allowedLifecycles capability lifecycles admitted by this plan
     */
    public record ExecutionPolicy(
            String authorizedPurpose,
            boolean realExternalCallsAllowed,
            boolean externalCredentialsAllowed,
            boolean networkEgressAllowed,
            boolean schemaSynthesisAllowed,
            boolean certificationRequired,
            UnmatchedResolution unmatchedResolution,
            int maximumInvocations,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Duration timeout,
            CapabilityContract.DataClassification maximumClassification,
            List<String> allowedRegions,
            List<CapabilitySnapshot.Lifecycle> allowedLifecycles
    ) {
        /** Normalizes policy collections and enforces the mirror isolation boundary. */
        public ExecutionPolicy {
            authorizedPurpose = required(authorizedPurpose, "authorizedPurpose");
            if (authorizedPurpose.toUpperCase(Locale.ROOT).contains("PRODUCTION")) {
                throw new IllegalArgumentException("mirror purpose must not be a production purpose");
            }
            if (realExternalCallsAllowed || externalCredentialsAllowed || networkEgressAllowed) {
                throw new IllegalArgumentException(
                        "mirror policy must deny real calls, external credentials, and network egress");
            }
            unmatchedResolution = unmatchedResolution == null
                    ? UnmatchedResolution.ABSTAINED : unmatchedResolution;
            if (maximumInvocations < 1 || maximumInvocations > 1_000_000) {
                throw new IllegalArgumentException("maximumInvocations must be between 1 and 1000000");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("timeout must be positive and no longer than 24 hours");
            }
            maximumClassification = Objects.requireNonNull(maximumClassification,
                    "maximumClassification");
            allowedRegions = normalizedList(allowedRegions);
            if (allowedRegions.isEmpty()) {
                throw new IllegalArgumentException("allowedRegions must not be empty");
            }
            allowedLifecycles = allowedLifecycles == null ? List.of() : allowedLifecycles.stream()
                    .distinct().sorted().toList();
            if (allowedLifecycles.isEmpty()) {
                throw new IllegalArgumentException("allowedLifecycles must not be empty");
            }
        }
    }

    private static MirrorArtifactRef requireKind(MirrorArtifactRef ref, String kind, String field) {
        Objects.requireNonNull(ref, field);
        if (!kind.equals(ref.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return ref;
    }

    private static List<MirrorArtifactRef> normalizeRefs(List<MirrorArtifactRef> refs, String kind) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<MirrorArtifactRef> normalized = refs.stream()
                .map(ref -> requireKind(ref, kind, kind + " reference"))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (normalized.size() > MAXIMUM_EXTERNAL_BINDINGS) {
            throw new IllegalArgumentException(kind + " references exceed their limit");
        }
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(kind + " references must be unique");
        }
        return normalized;
    }

    private static List<String> orderedUnique(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String item = required(value, field + " item");
            if (!seen.add(item)) {
                throw new IllegalArgumentException(field + " must not contain duplicates");
            }
            normalized.add(item);
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(MirrorPlan::normalized).filter(value -> !value.isBlank())
                .distinct().sorted().toList();
    }

    private static String normalizeGraphPath(String value) {
        String path = required(value, "graphPath");
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String fingerprint(String value, String field) {
        String result = required(value, field);
        if (!FINGERPRINT.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
        return result;
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported mirror plan schemaVersion");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
