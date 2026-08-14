package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Content-addressed industrial runtime-certification profile for one exact deployment.
 *
 * <p>The profile is intentionally non-negotiable at execution time: every failure boundary in
 * {@link Scenario} is present exactly once and every scenario retains its mandatory invariants.
 * A deployment may add stricter invariants but cannot create a weaker certificate by removing a
 * difficult scenario.</p>
 */
public record RuntimeCertificationManifest(
        String schemaVersion,
        String manifestFingerprint,
        String manifestId,
        long revision,
        CapabilitySnapshot.Scope scope,
        String region,
        MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
        EnvironmentClass environmentClass,
        String environmentFingerprint,
        List<ComponentCoordinate> components,
        List<ScenarioRequirement> scenarios,
        Instant validFrom,
        Instant expiresAt,
        String owner
) {
    /** Current runtime-certification profile version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.runtimeCertificationManifest.v1";
    /** Artifact kind used by execution authorizations and reports. */
    public static final String ARTIFACT_KIND = "RUNTIME_CERTIFICATION_MANIFEST";
    /** Longest period in which one frozen profile may be executed. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofDays(31);

    /** Validates exact deployment, component, scenario, and validity closure. */
    public RuntimeCertificationManifest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported runtime certification manifest version");
        }
        manifestFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                manifestFingerprint, "manifestFingerprint");
        manifestId = RegionalDataPlaneDeploymentContract.identifier(manifestId, "manifestId");
        if (revision < 1) {
            throw new IllegalArgumentException("manifest revision must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        region = RegionalDataPlaneDeploymentContract.identifier(region, "region");
        deployment = Objects.requireNonNull(deployment, "deployment");
        environmentClass = Objects.requireNonNull(environmentClass, "environmentClass");
        environmentFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                environmentFingerprint, "environmentFingerprint");
        components = orderedComponents(components);
        scenarios = orderedScenarios(scenarios);
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(validFrom)
                || Duration.between(validFrom, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("runtime certification manifest window is invalid");
        }
        owner = RegionalDataPlaneDeploymentContract.identifier(owner, "owner");
    }

    /** @return exact immutable manifest reference */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, manifestId, revision, manifestFingerprint);
    }

    /** Environment classification used by the execution safety guard. */
    public enum EnvironmentClass {
        /** Disposable certification environment with no production traffic. */
        SANDBOX,
        /** Production-shaped but traffic-isolated release environment. */
        PRE_PRODUCTION,
        /** Live production environment; executable certification is always forbidden. */
        PRODUCTION
    }

    /** Required runtime components whose exact builds must be reported. */
    public enum ComponentKind {
        RESOURCE_GATEWAY,
        BLOGE_ENGINE,
        DATABASE,
        JVM
    }

    /**
     * Exact software or infrastructure component under certification.
     *
     * @param kind component class
     * @param componentId stable product or deployment component id
     * @param version exact version string
     * @param buildFingerprint immutable binary, image, or configuration digest
     */
    public record ComponentCoordinate(
            ComponentKind kind,
            String componentId,
            String version,
            String buildFingerprint
    ) {
        /** Validates one immutable component coordinate. */
        public ComponentCoordinate {
            kind = Objects.requireNonNull(kind, "kind");
            componentId = RegionalDataPlaneDeploymentContract.identifier(
                    componentId, "componentId");
            version = RegionalDataPlaneDeploymentContract.identifier(version, "version");
            buildFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    buildFingerprint, "buildFingerprint");
        }
    }

    /**
     * Industrial failure scenarios required by the v1 certification profile.
     *
     * <p>The mandatory invariants are part of the protocol rather than UI defaults. A producer
     * that removes one cannot create a valid manifest.</p>
     */
    public enum Scenario {
        POSTGRES_PRIMARY_KILL_BEFORE_STAGE(
                "NO_PARTIAL_VISIBILITY", "EXACT_REPLAY", "NO_COMMITTED_STATE_LOSS"),
        POSTGRES_PRIMARY_KILL_AFTER_STAGE(
                "NO_PARTIAL_VISIBILITY", "EXACT_REPLAY", "NO_COMMITTED_STATE_LOSS"),
        POSTGRES_PRIMARY_KILL_AFTER_APPLY(
                "NO_PARTIAL_VISIBILITY", "EXACT_REPLAY", "NO_COMMITTED_STATE_LOSS"),
        POSTGRES_PRIMARY_KILL_AFTER_COMMIT(
                "COMMITTED_STATE_VISIBLE", "EXACT_REPLAY", "NO_DUPLICATE_EFFECT"),
        NETWORK_PARTITION(
                "SINGLE_ACTIVE_OWNER", "OLD_EPOCH_FENCED", "EVENTUAL_RECOVERY"),
        LEASE_TAKEOVER(
                "SINGLE_ACTIVE_OWNER", "OLD_EPOCH_FENCED", "EVENTUAL_RECOVERY"),
        ROLLING_UPGRADE(
                "MIXED_VERSION_COMPATIBLE", "NO_COMMITTED_STATE_LOSS", "EVENTUAL_RECOVERY"),
        BACKUP_RESTORE(
                "RESTORE_CONTINUITY", "NO_COMMITTED_STATE_LOSS", "MONOTONIC_FENCE_PRESERVED"),
        KMS_KEY_ROTATION(
                "KEY_ROTATION_CONTINUITY", "OLD_GENERATION_REJECTED", "RESTART_FREE"),
        MTLS_CA_ROTATION(
                "CA_ROTATION_CONTINUITY", "OLD_GENERATION_REJECTED", "RESTART_FREE"),
        VAULT_UNAVAILABLE(
                "FAIL_CLOSED", "NO_SECRET_FALLBACK", "EVENTUAL_RECOVERY"),
        WRITE_ESCAPE_PROBE(
                "ZERO_EXTERNAL_BUSINESS_WRITES", "FAIL_CLOSED");

        private final List<String> mandatoryInvariantCodes;

        Scenario(String... mandatoryInvariantCodes) {
            this.mandatoryInvariantCodes = List.of(mandatoryInvariantCodes);
        }

        /** @return protocol-owned invariants that cannot be removed by a manifest */
        public List<String> mandatoryInvariantCodes() {
            return mandatoryInvariantCodes;
        }
    }

    /**
     * Bounded requirement for one failure scenario.
     *
     * @param scenario exact injected failure
     * @param maximumExecutionSeconds hard scenario deadline
     * @param maximumRecoverySeconds recovery SLO after the fault is removed
     * @param requiredInvariantCodes mandatory plus deployment-specific invariant codes
     */
    public record ScenarioRequirement(
            Scenario scenario,
            long maximumExecutionSeconds,
            long maximumRecoverySeconds,
            List<String> requiredInvariantCodes
    ) {
        /** Rejects weakened, unbounded, duplicate, or non-canonical requirements. */
        public ScenarioRequirement {
            scenario = Objects.requireNonNull(scenario, "scenario");
            if (maximumExecutionSeconds < 1 || maximumExecutionSeconds > 7_200
                    || maximumRecoverySeconds < 1
                    || maximumRecoverySeconds > maximumExecutionSeconds) {
                throw new IllegalArgumentException("runtime scenario deadlines are invalid");
            }
            requiredInvariantCodes = invariantCodes(requiredInvariantCodes);
            if (!requiredInvariantCodes.containsAll(scenario.mandatoryInvariantCodes())) {
                throw new IllegalArgumentException(
                        "runtime scenario cannot remove mandatory invariants");
            }
        }
    }

    private static List<ComponentCoordinate> orderedComponents(
            List<ComponentCoordinate> values) {
        if (values == null || values.size() != ComponentKind.values().length) {
            throw new IllegalArgumentException(
                    "every runtime certification component must be present exactly once");
        }
        List<ComponentCoordinate> exact = new ArrayList<>(values);
        exact.sort(Comparator.comparing(ComponentCoordinate::kind));
        EnumSet<ComponentKind> kinds = EnumSet.noneOf(ComponentKind.class);
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().anyMatch(value -> !kinds.add(value.kind()))) {
            throw new IllegalArgumentException("runtime certification components are invalid");
        }
        return List.copyOf(exact);
    }

    private static List<ScenarioRequirement> orderedScenarios(
            List<ScenarioRequirement> values) {
        if (values == null || values.size() != Scenario.values().length) {
            throw new IllegalArgumentException(
                    "every runtime certification scenario must be required exactly once");
        }
        List<ScenarioRequirement> exact = new ArrayList<>(values);
        exact.sort(Comparator.comparing(ScenarioRequirement::scenario));
        EnumSet<Scenario> scenarios = EnumSet.noneOf(Scenario.class);
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().anyMatch(value -> !scenarios.add(value.scenario()))) {
            throw new IllegalArgumentException("runtime certification scenarios are invalid");
        }
        return List.copyOf(exact);
    }

    static List<String> invariantCodes(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException(
                    "runtime invariant codes must contain between 1 and 64 values");
        }
        List<String> exact = values.stream()
                .map(value -> value == null ? "" : value.trim())
                .sorted()
                .toList();
        Set<String> unique = new HashSet<>();
        if (exact.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]{0,127}"))
                || exact.stream().anyMatch(value -> !unique.add(value))) {
            throw new IllegalArgumentException("runtime invariant codes are invalid");
        }
        return List.copyOf(exact);
    }
}
