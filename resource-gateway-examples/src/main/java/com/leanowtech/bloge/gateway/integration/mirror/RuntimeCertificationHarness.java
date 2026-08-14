package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Portable plan-first orchestrator for customer-owned runtime fault-injection Adapters.
 *
 * <p>{@link #plan(RuntimeCertificationManifest, RuntimeCertificationEnvironmentAdapter)} never
 * invokes the Adapter. {@link #execute(ExecutionCommand)} requires canonical external approval,
 * current regional data-plane trust, a durable single-use journal, an independently safe Adapter,
 * and an external report signer before any scenario can start.</p>
 */
public final class RuntimeCertificationHarness {
    private static final Duration JOURNAL_LEASE = Duration.ofHours(3);

    private final RuntimeCertificationIntegrity integrity;
    private final VisualEvidenceSigner authorizationAuthority;
    private final VisualEvidenceSigner reportSigner;
    private final RegionalDataPlaneCertificationAuthority regionalAuthority;
    private final RuntimeCertificationExecutionJournal journal;
    private final Clock clock;
    private final String ownerId;

    /** Creates a harness with no implicit Adapter, journal, signer, or trust fallback. */
    public RuntimeCertificationHarness(
            RuntimeCertificationIntegrity integrity,
            VisualEvidenceSigner authorizationAuthority,
            VisualEvidenceSigner reportSigner,
            RegionalDataPlaneCertificationAuthority regionalAuthority,
            RuntimeCertificationExecutionJournal journal,
            Clock clock,
            String ownerId) {
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.authorizationAuthority = Objects.requireNonNull(
                authorizationAuthority, "authorizationAuthority");
        this.reportSigner = Objects.requireNonNull(reportSigner, "reportSigner");
        this.regionalAuthority = Objects.requireNonNull(regionalAuthority, "regionalAuthority");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerId = RegionalDataPlaneDeploymentContract.identifier(ownerId, "ownerId");
    }

    /**
     * Produces a non-executing preflight plan and complete scenario denominator.
     *
     * @return plan status and bounded findings without applying any fault
     */
    public Plan plan(
            RuntimeCertificationManifest manifest,
            RuntimeCertificationEnvironmentAdapter adapter) {
        List<String> findings = new ArrayList<>();
        if (!integrity.canonicalManifestVerified(manifest)) {
            findings.add("MANIFEST_FINGERPRINT_INVALID");
        }
        RuntimeCertificationReport.AdapterDescriptor descriptor = descriptor(adapter, findings);
        if (manifest != null && manifest.environmentClass()
                == RuntimeCertificationManifest.EnvironmentClass.PRODUCTION) {
            findings.add("PRODUCTION_EXECUTION_FORBIDDEN");
        }
        if (manifest != null && descriptor != null) {
            findings.addAll(adapterFindings(manifest, descriptor));
        }
        List<ScenarioPlan> scenarios = manifest == null ? List.of()
                : manifest.scenarios().stream()
                .map(value -> new ScenarioPlan(value.scenario(),
                        value.maximumExecutionSeconds(), value.maximumRecoverySeconds(),
                        value.requiredInvariantCodes()))
                .toList();
        return new Plan("", manifest == null ? null : manifest.artifactRef(),
                descriptor, findings.isEmpty() ? PlanStatus.READY : PlanStatus.BLOCKED,
                List.copyOf(findings), scenarios);
    }

    /**
     * Runs, resumes, or exact-replays one externally authorized certification.
     *
     * @return complete signed report; never a partial success projection
     */
    public RuntimeCertificationReport execute(ExecutionCommand command) {
        Objects.requireNonNull(command, "command");
        Instant startedAt = clock.instant();
        RuntimeCertificationIntegrity.VerificationResult authorization =
                integrity.verifyAuthorization(command.manifest(), command.authorization(),
                        authorizationAuthority, startedAt);
        if (!authorization.verified()) {
            throw rejected(authorization.reasonCode());
        }
        RuntimeCertificationReport.AdapterDescriptor descriptor =
                Objects.requireNonNull(command.adapter().descriptor(), "adapter descriptor");
        List<String> findings = adapterFindings(command.manifest(), descriptor);
        if (!findings.isEmpty()) {
            throw rejected(findings.getFirst());
        }
        requireRegional(command, startedAt, startedAt);

        RuntimeCertificationExecutionJournal.RunIdentity identity =
                new RuntimeCertificationExecutionJournal.RunIdentity(
                        command.reportId(), command.manifest().artifactRef(),
                        command.authorization().artifactRef(),
                        command.authorization().nonceFingerprint(),
                        command.manifest().environmentFingerprint());
        RuntimeCertificationExecutionJournal.Claim claim;
        try {
            claim = journal.claimOrResume(identity, ownerId, JOURNAL_LEASE, startedAt);
        } catch (RuntimeException unavailable) {
            throw rejected("JOURNAL_UNAVAILABLE");
        }
        if (claim.status() == RuntimeCertificationExecutionJournal.ClaimStatus.COMPLETED) {
            RuntimeCertificationReport completed = claim.completedReport();
            requireVerifiedReport(command, completed);
            return completed;
        }
        if (claim.status() != RuntimeCertificationExecutionJournal.ClaimStatus.ACQUIRED
                && claim.status() != RuntimeCertificationExecutionJournal.ClaimStatus.RESUMED) {
            throw rejected("JOURNAL_" + claim.reasonCode());
        }
        RuntimeCertificationExecutionJournal.Lease lease = claim.lease();
        List<RuntimeCertificationReport.ScenarioResult> results =
                validatedPrefix(command.manifest(), claim.savedResults());
        boolean halted = results.stream().anyMatch(value -> value.status()
                != RuntimeCertificationReport.ScenarioStatus.PASSED);

        for (int index = results.size(); index < command.manifest().scenarios().size(); index++) {
            RuntimeCertificationManifest.ScenarioRequirement requirement =
                    command.manifest().scenarios().get(index);
            lease = heartbeat(lease);
            RuntimeCertificationReport.ScenarioResult result;
            if (halted) {
                result = unobserved(requirement,
                        RuntimeCertificationReport.ScenarioStatus.ABORTED,
                        "PRIOR_SCENARIO_NOT_PASSED", claim.authorizationConsumptionRef());
            } else {
                result = executeScenario(command, requirement, lease,
                        claim.authorizationConsumptionRef());
            }
            append(lease, result);
            results.add(result);
            lease = heartbeat(lease);
            halted = halted || result.status()
                    != RuntimeCertificationReport.ScenarioStatus.PASSED;
        }

        Instant completedAt = results.stream()
                .map(RuntimeCertificationReport.ScenarioResult::completedAt)
                .max(Instant::compareTo).orElseGet(clock::instant);
        if (completedAt.isBefore(startedAt)) {
            completedAt = startedAt;
        }
        requireRegional(command, startedAt, completedAt);
        RuntimeCertificationReport.Verdict verdict = results.stream().allMatch(
                value -> value.status() == RuntimeCertificationReport.ScenarioStatus.PASSED)
                ? RuntimeCertificationReport.Verdict.CERTIFIED
                : results.stream().anyMatch(value -> value.status()
                == RuntimeCertificationReport.ScenarioStatus.FAILED)
                ? RuntimeCertificationReport.Verdict.FAILED
                : RuntimeCertificationReport.Verdict.BLOCKED;
        long writeAttempts = results.stream().mapToLong(
                RuntimeCertificationReport.ScenarioResult
                        ::externalBusinessWriteAttemptCount).sum();
        long writeEscapes = results.stream().mapToLong(
                RuntimeCertificationReport.ScenarioResult::writeEscapeCount).sum();
        RuntimeCertificationReport report = integrity.sealReport(
                new RuntimeCertificationIntegrity.ReportMaterial(
                        command.reportId(), command.revision(),
                        command.manifest().artifactRef(), command.authorization().artifactRef(),
                        claim.authorizationConsumptionRef(),
                        command.regionalDataPlaneCertificationRef(),
                        command.isolationDecisionRef(), command.isolationAttestationRef(),
                        command.manifest().scope(), command.manifest().region(),
                        command.manifest().deployment(), command.manifest().environmentClass(),
                        command.manifest().environmentFingerprint(), descriptor,
                        command.manifest().components(), startedAt, completedAt, results, verdict,
                        writeAttempts, writeEscapes, command.issuer(), command.proofRefs()),
                reportSigner);
        requireVerifiedReport(command, report);
        try {
            journal.complete(lease, report);
        } catch (RuntimeCertificationExecutionJournal.LeaseLostException lost) {
            throw rejected("JOURNAL_LEASE_LOST");
        } catch (RuntimeException unavailable) {
            throw rejected("JOURNAL_COMPLETE_FAILED");
        }
        return report;
    }

    private RuntimeCertificationReport.ScenarioResult executeScenario(
            ExecutionCommand command,
            RuntimeCertificationManifest.ScenarioRequirement requirement,
            RuntimeCertificationExecutionJournal.Lease lease,
            MirrorArtifactRef consumptionRef) {
        Instant requestedAt = clock.instant();
        RuntimeCertificationEnvironmentAdapter.ScenarioExecution request =
                new RuntimeCertificationEnvironmentAdapter.ScenarioExecution(
                        command.reportId(), lease.epoch(), command.manifest(),
                        command.authorization(), command.regionalDataPlaneCertificationRef(),
                        command.isolationDecisionRef(), command.isolationAttestationRef(),
                        requirement, requestedAt, requestedAt.plusSeconds(
                        requirement.maximumExecutionSeconds()));
        RuntimeCertificationReport.ScenarioResult result;
        try {
            result = command.adapter().execute(request);
        } catch (RuntimeException unavailable) {
            return unobserved(requirement,
                    RuntimeCertificationReport.ScenarioStatus.BLOCKED,
                    "ADAPTER_EXECUTION_FAILED", consumptionRef);
        }
        return scenarioValid(requirement, result, requestedAt)
                ? result : unobserved(requirement,
                RuntimeCertificationReport.ScenarioStatus.BLOCKED,
                "ADAPTER_RESULT_INVALID", consumptionRef);
    }

    private boolean scenarioValid(
            RuntimeCertificationManifest.ScenarioRequirement requirement,
            RuntimeCertificationReport.ScenarioResult result,
            Instant requestedAt) {
        if (result == null || result.scenario() != requirement.scenario()
                || result.startedAt().isBefore(requestedAt)
                || Duration.between(result.startedAt(), result.completedAt())
                .compareTo(Duration.ofSeconds(requirement.maximumExecutionSeconds())) > 0
                || result.faultRemovedAt() != null && result.recoveryObservedAt() != null
                && Duration.between(result.faultRemovedAt(), result.recoveryObservedAt())
                .compareTo(Duration.ofSeconds(requirement.maximumRecoverySeconds())) > 0) {
            return false;
        }
        return requirement.requiredInvariantCodes().equals(result.invariantObservations().stream()
                .map(RuntimeCertificationReport.InvariantObservation::code).toList());
    }

    private List<RuntimeCertificationReport.ScenarioResult> validatedPrefix(
            RuntimeCertificationManifest manifest,
            List<RuntimeCertificationReport.ScenarioResult> saved) {
        if (saved.size() > manifest.scenarios().size()) {
            throw rejected("JOURNAL_SCENARIO_PREFIX_INVALID");
        }
        List<RuntimeCertificationReport.ScenarioResult> exact = new ArrayList<>(saved);
        Set<RuntimeCertificationManifest.Scenario> unique = new HashSet<>();
        for (int index = 0; index < exact.size(); index++) {
            RuntimeCertificationReport.ScenarioResult result = exact.get(index);
            RuntimeCertificationManifest.ScenarioRequirement requirement =
                    manifest.scenarios().get(index);
            if (result == null || result.scenario() != requirement.scenario()
                    || !unique.add(result.scenario())
                    || !requirement.requiredInvariantCodes().equals(
                    result.invariantObservations().stream()
                            .map(RuntimeCertificationReport.InvariantObservation::code).toList())) {
                throw rejected("JOURNAL_SCENARIO_PREFIX_INVALID");
            }
        }
        return exact;
    }

    private RuntimeCertificationReport.ScenarioResult unobserved(
            RuntimeCertificationManifest.ScenarioRequirement requirement,
            RuntimeCertificationReport.ScenarioStatus status,
            String reasonCode,
            MirrorArtifactRef proofRef) {
        Instant now = clock.instant();
        List<RuntimeCertificationReport.InvariantObservation> invariants =
                requirement.requiredInvariantCodes().stream()
                .map(code -> new RuntimeCertificationReport.InvariantObservation(code,
                        RuntimeCertificationReport.InvariantStatus.NOT_OBSERVED,
                        List.of(proofRef)))
                .toList();
        return new RuntimeCertificationReport.ScenarioResult(
                requirement.scenario(), "attempt:" + requirement.scenario().name().toLowerCase(),
                status, now, null, null, null, now, false, false, 0, 0,
                sha256(reasonCode + ":command"), sha256(reasonCode + ":observation"),
                invariants, List.of(proofRef), reasonCode);
    }

    private RuntimeCertificationExecutionJournal.Lease heartbeat(
            RuntimeCertificationExecutionJournal.Lease lease) {
        try {
            return journal.heartbeat(lease, JOURNAL_LEASE, clock.instant());
        } catch (RuntimeCertificationExecutionJournal.LeaseLostException lost) {
            throw rejected("JOURNAL_LEASE_LOST");
        } catch (RuntimeException unavailable) {
            throw rejected("JOURNAL_HEARTBEAT_FAILED");
        }
    }

    private void append(
            RuntimeCertificationExecutionJournal.Lease lease,
            RuntimeCertificationReport.ScenarioResult result) {
        try {
            journal.appendScenario(lease, result);
        } catch (RuntimeCertificationExecutionJournal.LeaseLostException lost) {
            throw rejected("JOURNAL_LEASE_LOST");
        } catch (RuntimeException unavailable) {
            throw rejected("JOURNAL_APPEND_FAILED");
        }
    }

    private void requireRegional(ExecutionCommand command, Instant start, Instant end) {
        try {
            regionalAuthority.require(command.manifest().scope(),
                    command.isolationDecisionRef(), command.isolationAttestationRef(), start, end);
        } catch (RuntimeException denied) {
            throw rejected("REGIONAL_CERTIFICATION_REJECTED");
        }
    }

    private void requireVerifiedReport(
            ExecutionCommand command, RuntimeCertificationReport report) {
        RuntimeCertificationIntegrity.VerificationResult verification = integrity.verifyReport(
                command.manifest(), command.authorization(), report,
                command.regionalDataPlaneCertificationRef(), command.isolationDecisionRef(),
                command.isolationAttestationRef(), authorizationAuthority, reportSigner);
        if (!verification.verified()
                && verification.outcome() != RuntimeCertificationIntegrity.Outcome.NOT_CERTIFIED) {
            throw rejected(verification.reasonCode());
        }
    }

    private static RuntimeCertificationReport.AdapterDescriptor descriptor(
            RuntimeCertificationEnvironmentAdapter adapter, List<String> findings) {
        if (adapter == null) {
            findings.add("ADAPTER_UNAVAILABLE");
            return null;
        }
        try {
            RuntimeCertificationReport.AdapterDescriptor descriptor = adapter.descriptor();
            if (descriptor == null) {
                findings.add("ADAPTER_DESCRIPTOR_UNAVAILABLE");
            }
            return descriptor;
        } catch (RuntimeException unavailable) {
            findings.add("ADAPTER_DESCRIPTOR_UNAVAILABLE");
            return null;
        }
    }

    private static List<String> adapterFindings(
            RuntimeCertificationManifest manifest,
            RuntimeCertificationReport.AdapterDescriptor descriptor) {
        List<String> findings = new ArrayList<>();
        if (!descriptor.available()) {
            findings.add("ADAPTER_UNAVAILABLE");
        }
        if (descriptor.environmentClass() != manifest.environmentClass()
                || !descriptor.environmentFingerprint().equals(
                manifest.environmentFingerprint())) {
            findings.add("ADAPTER_ENVIRONMENT_MISMATCH");
        }
        List<RuntimeCertificationManifest.Scenario> expected = manifest.scenarios().stream()
                .map(RuntimeCertificationManifest.ScenarioRequirement::scenario).toList();
        if (!expected.equals(descriptor.supportedScenarios())) {
            findings.add("ADAPTER_SCENARIO_INVENTORY_MISMATCH");
        }
        if (!descriptor.isolatedControlPlane()
                || !descriptor.productionExecutionDenied()
                || !descriptor.externalAuthorizationRequired()
                || !descriptor.durableReplayProtection()) {
            findings.add("ADAPTER_SAFETY_CONTROLS_REJECTED");
        }
        return List.copyOf(findings);
    }

    private static RuntimeCertificationRejectedException rejected(String reasonCode) {
        return new RuntimeCertificationRejectedException(reasonCode);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Plan-only status. */
    public enum PlanStatus {
        READY,
        BLOCKED
    }

    /** Payload-free plan that never authorizes execution. */
    public record Plan(
            String schemaVersion,
            MirrorArtifactRef manifestRef,
            RuntimeCertificationReport.AdapterDescriptor adapter,
            PlanStatus status,
            List<String> findings,
            List<ScenarioPlan> scenarios
    ) {
        /** Current plan protocol. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.runtimeCertificationPlan.v1";

        /** Normalizes a plan without turning it into an execution credential. */
        public Plan {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            status = Objects.requireNonNull(status, "status");
            findings = findings == null ? List.of() : List.copyOf(findings);
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    /** One non-executing scenario preview. */
    public record ScenarioPlan(
            RuntimeCertificationManifest.Scenario scenario,
            long maximumExecutionSeconds,
            long maximumRecoverySeconds,
            List<String> requiredInvariantCodes
    ) {
        /** Validates a bounded copy of a manifest requirement. */
        public ScenarioPlan {
            scenario = Objects.requireNonNull(scenario, "scenario");
            requiredInvariantCodes = RuntimeCertificationManifest.invariantCodes(
                    requiredInvariantCodes);
        }
    }

    /** Complete command required for destructive execution. */
    public record ExecutionCommand(
            String reportId,
            long revision,
            RuntimeCertificationManifest manifest,
            RuntimeCertificationExecutionAuthorization authorization,
            MirrorArtifactRef regionalDataPlaneCertificationRef,
            MirrorArtifactRef isolationDecisionRef,
            MirrorArtifactRef isolationAttestationRef,
            RuntimeCertificationEnvironmentAdapter adapter,
            String issuer,
            List<MirrorArtifactRef> proofRefs
    ) {
        /** Validates stable command coordinates without performing trust decisions. */
        public ExecutionCommand {
            reportId = RegionalDataPlaneDeploymentContract.identifier(reportId, "reportId");
            if (revision < 1) {
                throw new IllegalArgumentException("report revision must be positive");
            }
            manifest = Objects.requireNonNull(manifest, "manifest");
            authorization = Objects.requireNonNull(authorization, "authorization");
            regionalDataPlaneCertificationRef =
                    RuntimeCertificationExecutionAuthorization.requireKind(
                            regionalDataPlaneCertificationRef,
                            RegionalDataPlaneCertification.ARTIFACT_KIND,
                            "regionalDataPlaneCertificationRef");
            isolationDecisionRef = RuntimeCertificationExecutionAuthorization.requireKind(
                    isolationDecisionRef,
                    MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                    "isolationDecisionRef");
            isolationAttestationRef = RuntimeCertificationExecutionAuthorization.requireKind(
                    isolationAttestationRef,
                    MirrorDeploymentIsolationAttestation.ARTIFACT_KIND,
                    "isolationAttestationRef");
            adapter = Objects.requireNonNull(adapter, "adapter");
            issuer = RegionalDataPlaneDeploymentContract.identifier(issuer, "issuer");
            proofRefs = RuntimeCertificationExecutionAuthorization.orderedRefs(
                    proofRefs, "proofRefs");
        }
    }

    /** Stable fail-closed harness rejection. */
    public static final class RuntimeCertificationRejectedException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String reasonCode;

        /** @param reasonCode bounded machine-readable denial */
        public RuntimeCertificationRejectedException(String reasonCode) {
            super("runtime certification rejected: " + normalized(reasonCode));
            this.reasonCode = normalized(reasonCode);
            if (!this.reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("runtime certification reasonCode is invalid");
            }
        }

        /** @return stable machine-readable denial */
        public String reasonCode() {
            return reasonCode;
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
