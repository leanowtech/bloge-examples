package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Signed, payload-free result of one complete industrial runtime certification run.
 *
 * <p>The report has no aggregate score. It is {@link Verdict#CERTIFIED} only when every required
 * scenario passed every required invariant with zero external-business writes. Blocked,
 * unobserved, and aborted work remain explicit and cannot disappear from the denominator.</p>
 */
public record RuntimeCertificationReport(
        String schemaVersion,
        String reportFingerprint,
        String reportId,
        long revision,
        MirrorArtifactRef manifestRef,
        MirrorArtifactRef authorizationRef,
        MirrorArtifactRef authorizationConsumptionRef,
        MirrorArtifactRef regionalDataPlaneCertificationRef,
        MirrorArtifactRef isolationDecisionRef,
        MirrorArtifactRef isolationAttestationRef,
        CapabilitySnapshot.Scope scope,
        String region,
        MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
        RuntimeCertificationManifest.EnvironmentClass environmentClass,
        String environmentFingerprint,
        AdapterDescriptor adapter,
        List<RuntimeCertificationManifest.ComponentCoordinate> observedComponents,
        Instant startedAt,
        Instant completedAt,
        List<ScenarioResult> scenarioResults,
        Verdict verdict,
        long externalBusinessWriteAttemptCount,
        long writeEscapeCount,
        String issuer,
        List<MirrorArtifactRef> proofRefs,
        VisualRunEvidenceSeal reportSeal
) {
    /** Current certification-report protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.runtimeCertificationReport.v1";
    /** Artifact kind used by release evidence and offline consumers. */
    public static final String ARTIFACT_KIND = "RUNTIME_CERTIFICATION_REPORT";

    /** Validates complete scenario coverage and fail-closed verdict semantics. */
    public RuntimeCertificationReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported runtime certification report version");
        }
        reportFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                reportFingerprint, "reportFingerprint");
        reportId = RegionalDataPlaneDeploymentContract.identifier(reportId, "reportId");
        if (revision < 1) {
            throw new IllegalArgumentException("report revision must be positive");
        }
        manifestRef = RuntimeCertificationExecutionAuthorization.requireKind(manifestRef,
                RuntimeCertificationManifest.ARTIFACT_KIND, "manifestRef");
        authorizationRef = RuntimeCertificationExecutionAuthorization.requireKind(
                authorizationRef, RuntimeCertificationExecutionAuthorization.ARTIFACT_KIND,
                "authorizationRef");
        authorizationConsumptionRef = RuntimeCertificationExecutionAuthorization.requireKind(
                authorizationConsumptionRef,
                "RUNTIME_CERTIFICATION_AUTHORIZATION_CONSUMPTION",
                "authorizationConsumptionRef");
        regionalDataPlaneCertificationRef =
                RuntimeCertificationExecutionAuthorization.requireKind(
                        regionalDataPlaneCertificationRef,
                        RegionalDataPlaneCertification.ARTIFACT_KIND,
                        "regionalDataPlaneCertificationRef");
        isolationDecisionRef = RuntimeCertificationExecutionAuthorization.requireKind(
                isolationDecisionRef, MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                "isolationDecisionRef");
        isolationAttestationRef = RuntimeCertificationExecutionAuthorization.requireKind(
                isolationAttestationRef, MirrorDeploymentIsolationAttestation.ARTIFACT_KIND,
                "isolationAttestationRef");
        scope = Objects.requireNonNull(scope, "scope");
        region = RegionalDataPlaneDeploymentContract.identifier(region, "region");
        deployment = Objects.requireNonNull(deployment, "deployment");
        environmentClass = Objects.requireNonNull(environmentClass, "environmentClass");
        if (environmentClass == RuntimeCertificationManifest.EnvironmentClass.PRODUCTION) {
            throw new IllegalArgumentException(
                    "production runtime certification reports are forbidden");
        }
        environmentFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                environmentFingerprint, "environmentFingerprint");
        adapter = Objects.requireNonNull(adapter, "adapter");
        observedComponents = orderedComponents(observedComponents);
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("runtime certification report window is invalid");
        }
        scenarioResults = orderedResults(scenarioResults);
        verdict = Objects.requireNonNull(verdict, "verdict");
        if (verdict != derivedVerdict(scenarioResults)) {
            throw new IllegalArgumentException("runtime certification verdict is inconsistent");
        }
        if (externalBusinessWriteAttemptCount < 0 || writeEscapeCount < 0) {
            throw new IllegalArgumentException("runtime certification write counters are invalid");
        }
        if (verdict == Verdict.CERTIFIED
                && (externalBusinessWriteAttemptCount != 0 || writeEscapeCount != 0)) {
            throw new IllegalArgumentException("certified runtime report cannot contain writes");
        }
        issuer = RegionalDataPlaneDeploymentContract.identifier(issuer, "issuer");
        proofRefs = RuntimeCertificationExecutionAuthorization.orderedRefs(proofRefs, "proofRefs");
        reportSeal = Objects.requireNonNull(reportSeal, "reportSeal");
        if (!reportSeal.signed()) {
            throw new IllegalArgumentException("runtime certification report must be signed");
        }
    }

    /** @return exact immutable report reference */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, reportId, revision, reportFingerprint);
    }

    /** Non-numeric release decision derived from the complete result inventory. */
    public enum Verdict {
        CERTIFIED,
        FAILED,
        BLOCKED
    }

    /** Scenario-level terminal status. */
    public enum ScenarioStatus {
        PASSED,
        FAILED,
        BLOCKED,
        ABORTED
    }

    /** Invariant-level observation status. */
    public enum InvariantStatus {
        PASSED,
        FAILED,
        NOT_OBSERVED
    }

    /**
     * Customer-owned environment Adapter descriptor.
     *
     * @param schemaVersion descriptor version
     * @param adapterId stable Adapter identity
     * @param adapterFingerprint immutable Adapter configuration/build fingerprint
     * @param provider deployment or infrastructure provider name
     * @param environmentClass independently observed environment class
     * @param environmentFingerprint exact target environment
     * @param supportedScenarios complete executable scenario set
     * @param isolatedControlPlane whether fault control is isolated from business traffic
     * @param productionExecutionDenied whether the Adapter itself denies production
     * @param externalAuthorizationRequired whether the Adapter revalidates the signed authorization
     * @param durableReplayProtection whether an authorization nonce cannot execute twice
     * @param available current Adapter readiness
     */
    public record AdapterDescriptor(
            String schemaVersion,
            String adapterId,
            String adapterFingerprint,
            String provider,
            RuntimeCertificationManifest.EnvironmentClass environmentClass,
            String environmentFingerprint,
            List<RuntimeCertificationManifest.Scenario> supportedScenarios,
            boolean isolatedControlPlane,
            boolean productionExecutionDenied,
            boolean externalAuthorizationRequired,
            boolean durableReplayProtection,
            boolean available
    ) {
        /** Current Adapter descriptor protocol. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.runtimeCertificationAdapterDescriptor.v1";

        /** Rejects an Adapter that cannot enforce the harness safety boundary. */
        public AdapterDescriptor {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "unsupported runtime certification adapter descriptor version");
            }
            adapterId = RegionalDataPlaneDeploymentContract.identifier(adapterId, "adapterId");
            adapterFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    adapterFingerprint, "adapterFingerprint");
            provider = RegionalDataPlaneDeploymentContract.identifier(provider, "provider");
            environmentClass = Objects.requireNonNull(environmentClass, "environmentClass");
            environmentFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    environmentFingerprint, "environmentFingerprint");
            supportedScenarios = exactScenarios(supportedScenarios);
            // Negative booleans remain representable so plan/capability probes can explain why
            // an installed Adapter is blocked. Execution and report verification reject them.
        }
    }

    /**
     * Result for one fault injection and recovery cycle.
     *
     * @param scenario exact scenario
     * @param attemptId stable Adapter attempt identity
     * @param status terminal result
     * @param startedAt scenario start
     * @param faultAppliedAt independently observed fault application time
     * @param faultRemovedAt independently observed fault removal time
     * @param recoveryObservedAt independently observed recovery boundary time
     * @param completedAt scenario completion
     * @param faultApplied whether the requested failure was independently observed
     * @param recoveryObserved whether recovery reached its declared terminal boundary
     * @param externalBusinessWriteAttemptCount writes attempted against external business systems
     * @param writeEscapeCount writes that escaped policy
     * @param commandTranscriptFingerprint hash of the sanitized Adapter command transcript
     * @param observationFingerprint hash of the raw external observation bundle
     * @param invariantObservations exact invariant denominator and outcomes
     * @param proofRefs payload-free evidence references
     * @param reasonCode bounded terminal reason
     */
    public record ScenarioResult(
            RuntimeCertificationManifest.Scenario scenario,
            String attemptId,
            ScenarioStatus status,
            Instant startedAt,
            Instant faultAppliedAt,
            Instant faultRemovedAt,
            Instant recoveryObservedAt,
            Instant completedAt,
            boolean faultApplied,
            boolean recoveryObserved,
            long externalBusinessWriteAttemptCount,
            long writeEscapeCount,
            String commandTranscriptFingerprint,
            String observationFingerprint,
            List<InvariantObservation> invariantObservations,
            List<MirrorArtifactRef> proofRefs,
            String reasonCode
    ) {
        /** Validates a terminal, payload-free scenario observation. */
        public ScenarioResult {
            scenario = Objects.requireNonNull(scenario, "scenario");
            attemptId = RegionalDataPlaneDeploymentContract.identifier(attemptId, "attemptId");
            status = Objects.requireNonNull(status, "status");
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            if (completedAt.isBefore(startedAt)
                    || externalBusinessWriteAttemptCount < 0 || writeEscapeCount < 0) {
                throw new IllegalArgumentException("runtime scenario observation is invalid");
            }
            if (faultApplied != (faultAppliedAt != null)
                    || recoveryObserved != (recoveryObservedAt != null)
                    || faultRemovedAt != null && faultAppliedAt == null
                    || recoveryObservedAt != null && faultRemovedAt == null
                    || faultAppliedAt != null && (faultAppliedAt.isBefore(startedAt)
                    || faultAppliedAt.isAfter(completedAt))
                    || faultRemovedAt != null && (faultRemovedAt.isBefore(faultAppliedAt)
                    || faultRemovedAt.isAfter(completedAt))
                    || recoveryObservedAt != null
                    && (recoveryObservedAt.isBefore(faultRemovedAt)
                    || recoveryObservedAt.isAfter(completedAt))) {
                throw new IllegalArgumentException(
                        "runtime scenario fault/recovery timeline is invalid");
            }
            commandTranscriptFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    commandTranscriptFingerprint, "commandTranscriptFingerprint");
            observationFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    observationFingerprint, "observationFingerprint");
            invariantObservations = orderedInvariants(invariantObservations);
            proofRefs = RuntimeCertificationExecutionAuthorization.orderedRefs(
                    proofRefs, "proofRefs");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("runtime scenario reason code is invalid");
            }
            boolean allPassed = invariantObservations.stream()
                    .allMatch(value -> value.status() == InvariantStatus.PASSED);
            if (status == ScenarioStatus.PASSED
                    && (!faultApplied || !recoveryObserved || !allPassed
                    || faultRemovedAt == null
                    || externalBusinessWriteAttemptCount != 0 || writeEscapeCount != 0)) {
                throw new IllegalArgumentException(
                        "passed runtime scenario lacks complete zero-write evidence");
            }
        }
    }

    /**
     * One invariant observation.
     *
     * @param code stable invariant code
     * @param status observed result
     * @param proofRefs payload-free evidence references
     */
    public record InvariantObservation(
            String code,
            InvariantStatus status,
            List<MirrorArtifactRef> proofRefs
    ) {
        /** Validates one independently evidenced invariant. */
        public InvariantObservation {
            code = RuntimeCertificationManifest.invariantCodes(List.of(code)).getFirst();
            status = Objects.requireNonNull(status, "status");
            proofRefs = RuntimeCertificationExecutionAuthorization.orderedRefs(
                    proofRefs, "proofRefs");
        }
    }

    private static List<RuntimeCertificationManifest.ComponentCoordinate> orderedComponents(
            List<RuntimeCertificationManifest.ComponentCoordinate> values) {
        if (values == null || values.size()
                != RuntimeCertificationManifest.ComponentKind.values().length) {
            throw new IllegalArgumentException("runtime report components are incomplete");
        }
        List<RuntimeCertificationManifest.ComponentCoordinate> exact = new ArrayList<>(values);
        exact.sort(Comparator.comparing(
                RuntimeCertificationManifest.ComponentCoordinate::kind));
        EnumSet<RuntimeCertificationManifest.ComponentKind> unique =
                EnumSet.noneOf(RuntimeCertificationManifest.ComponentKind.class);
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().anyMatch(value -> !unique.add(value.kind()))) {
            throw new IllegalArgumentException("runtime report components are invalid");
        }
        return List.copyOf(exact);
    }

    private static List<ScenarioResult> orderedResults(List<ScenarioResult> values) {
        if (values == null || values.size()
                != RuntimeCertificationManifest.Scenario.values().length) {
            throw new IllegalArgumentException("runtime report scenario denominator is incomplete");
        }
        List<ScenarioResult> exact = new ArrayList<>(values);
        exact.sort(Comparator.comparing(ScenarioResult::scenario));
        EnumSet<RuntimeCertificationManifest.Scenario> unique =
                EnumSet.noneOf(RuntimeCertificationManifest.Scenario.class);
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().anyMatch(value -> !unique.add(value.scenario()))) {
            throw new IllegalArgumentException("runtime report scenario results are invalid");
        }
        return List.copyOf(exact);
    }

    private static List<InvariantObservation> orderedInvariants(
            List<InvariantObservation> values) {
        if (values == null || values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException("runtime invariant observations are incomplete");
        }
        List<InvariantObservation> exact = new ArrayList<>(values);
        exact.sort(Comparator.comparing(InvariantObservation::code));
        Set<String> unique = new HashSet<>();
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().anyMatch(value -> !unique.add(value.code()))) {
            throw new IllegalArgumentException("runtime invariant observations are invalid");
        }
        return List.copyOf(exact);
    }

    private static List<RuntimeCertificationManifest.Scenario> exactScenarios(
            List<RuntimeCertificationManifest.Scenario> values) {
        if (values == null || values.size()
                != RuntimeCertificationManifest.Scenario.values().length) {
            throw new IllegalArgumentException("runtime adapter scenario inventory is incomplete");
        }
        List<RuntimeCertificationManifest.Scenario> exact = new ArrayList<>(values);
        exact.sort(Comparator.naturalOrder());
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException("runtime adapter scenario inventory is invalid");
        }
        return List.copyOf(exact);
    }

    private static Verdict derivedVerdict(List<ScenarioResult> results) {
        if (results.stream().allMatch(value -> value.status() == ScenarioStatus.PASSED)) {
            return Verdict.CERTIFIED;
        }
        if (results.stream().anyMatch(value -> value.status() == ScenarioStatus.FAILED)) {
            return Verdict.FAILED;
        }
        return Verdict.BLOCKED;
    }
}
