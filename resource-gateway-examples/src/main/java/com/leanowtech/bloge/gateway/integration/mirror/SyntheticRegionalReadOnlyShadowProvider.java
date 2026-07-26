package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded in-process regional provider for online Shadow protocol certification.
 *
 * <p>This provider is a deterministic test/staging reference implementation, not a production
 * payload vault or production data connector. It turns pre-approved payload-free baseline
 * fixtures into signed online observations and delegates sealed candidate execution to an
 * injected {@link CandidateEvidenceFactory}. It nevertheless enforces the production protocol's
 * idempotency conflicts, exact content-addressed reads, scope isolation, independent evidence
 * verification, fixed capacity, and append-only artifact semantics.</p>
 *
 * <p>The class is never auto-configured. A deployment must explicitly expose
 * {@link #baselineAuthority()} and {@link #candidateAuthority()} in a non-production
 * certification composition.</p>
 */
public final class SyntheticRegionalReadOnlyShadowProvider {
    private final Map<MirrorArtifactRef, BaselineFixture>
            fixtures;
    private final CandidateEvidenceFactory candidateFactory;
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity;
    private final MirrorEvidenceIntegrityService candidateIntegrity;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maximumArtifacts;
    private final Object monitor = new Object();
    private final Map<String, String> baselineCommandByExecution =
            new LinkedHashMap<>();
    private final Map<String, OnlineReadOnlyShadowBaselineObservation>
            baselineByExecution =
            new LinkedHashMap<>();
    private final Map<MirrorArtifactRef,
            OnlineReadOnlyShadowBaselineObservation>
            baselines =
            new LinkedHashMap<>();
    private final Map<MirrorArtifactRef,
            OnlineReadOnlyShadowBaselineCommand>
            baselineCommands =
            new LinkedHashMap<>();
    private final Map<String, String> candidateCommandByExecution =
            new LinkedHashMap<>();
    private final Map<String, MirrorEvidenceBundle>
            candidateByExecution =
            new LinkedHashMap<>();
    private final Map<MirrorArtifactRef, MirrorEvidenceBundle>
            candidates =
            new LinkedHashMap<>();
    private final OnlineReadOnlyShadowBaselineAuthority
            baselineAuthority =
            new BaselineAuthority();
    private final OnlineReadOnlyShadowCandidateAuthority
            candidateAuthority =
            new CandidateAuthority();

    /**
     * Creates one bounded synthetic regional certification provider.
     *
     * @param fixtures immutable payload-free baseline fixture catalog
     * @param candidateFactory sealed candidate evidence factory
     * @param baselineIntegrity regional baseline signing and verification authority
     * @param candidateIntegrity independent Mirror evidence verification authority
     * @param mapper canonical protocol mapper
     * @param clock trusted deterministic certification clock
     * @param maximumArtifacts maximum baseline and candidate artifacts retained per kind
     */
    public SyntheticRegionalReadOnlyShadowProvider(
            List<BaselineFixture> fixtures,
            CandidateEvidenceFactory candidateFactory,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    baselineIntegrity,
            MirrorEvidenceIntegrityService candidateIntegrity,
            ObjectMapper mapper,
            Clock clock,
            int maximumArtifacts) {
        this.fixtures = fixtures(fixtures);
        this.candidateFactory = Objects.requireNonNull(
                candidateFactory, "candidateFactory");
        this.baselineIntegrity = Objects.requireNonNull(
                baselineIntegrity, "baselineIntegrity");
        this.candidateIntegrity = Objects.requireNonNull(
                candidateIntegrity, "candidateIntegrity");
        this.mapper = Objects.requireNonNull(
                mapper, "mapper");
        this.clock = Objects.requireNonNull(
                clock, "clock");
        if (maximumArtifacts < 1
                || maximumArtifacts > 100_000) {
            throw new IllegalArgumentException(
                    "synthetic Shadow artifact capacity is invalid");
        }
        this.maximumArtifacts = maximumArtifacts;
    }

    /**
     * Returns the baseline side of the regional provider.
     *
     * @return payload-free online baseline authority
     */
    public OnlineReadOnlyShadowBaselineAuthority
    baselineAuthority() {
        return baselineAuthority;
    }

    /**
     * Returns the sealed-candidate side of the regional provider.
     *
     * @return online candidate authority
     */
    public OnlineReadOnlyShadowCandidateAuthority
    candidateAuthority() {
        return candidateAuthority;
    }

    /**
     * Factory for one independently signed isolated Mirror candidate result.
     *
     * <p>The implementation must use {@link OnlineReadOnlyShadowCandidateCommand#commandFingerprint(ObjectMapper)}
     * as {@link MirrorRunEvidence#requestId()} and must not attach production runtime authority.</p>
     */
    @FunctionalInterface
    public interface CandidateEvidenceFactory {
        /**
         * Executes or constructs one exact sealed candidate result.
         *
         * @param command exact same-input candidate command
         * @return signed payload-free Mirror evidence
         */
        MirrorEvidenceBundle execute(
                OnlineReadOnlyShadowCandidateCommand command);
    }

    /**
     * One payload-free pre-approved synthetic baseline source.
     *
     * @param baselineBindingRef exact command-selectable baseline binding
     * @param workloadIdentityRef synthetic short-lived read-only identity
     * @param workloadIdentityAttestationRef identity capability proof
     * @param payloadVaultReceiptRef opaque synthetic vault receipt
     * @param transportAttestationRef read-only transport proof
     * @param requestContextFingerprint canonical synthetic request identity
     * @param semanticResultFingerprint canonical synthetic baseline result
     * @param sourceRequestFingerprint hash-only source request evidence
     * @param sourceResponseFingerprint hash-only source response evidence
     * @param responseSchemaRef exact response schema
     * @param normalizedFactFingerprints normalized facts by fidelity dimension
     * @param evidenceClass exploratory or certifiable fixture class
     * @param evidenceComplete whether the fixture contains every required fact
     */
    public record BaselineFixture(
            MirrorArtifactRef baselineBindingRef,
            MirrorArtifactRef workloadIdentityRef,
            MirrorArtifactRef workloadIdentityAttestationRef,
            MirrorArtifactRef payloadVaultReceiptRef,
            MirrorArtifactRef transportAttestationRef,
            String requestContextFingerprint,
            String semanticResultFingerprint,
            String sourceRequestFingerprint,
            String sourceResponseFingerprint,
            MirrorArtifactRef responseSchemaRef,
            Map<DomainFidelityProfile.Dimension, String>
                    normalizedFactFingerprints,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            boolean evidenceComplete
    ) {
        /** Validates bounded payload-free fixture coordinates. */
        public BaselineFixture {
            baselineBindingRef = kind(
                    baselineBindingRef,
                    "SHADOW_BASELINE_BINDING",
                    "baselineBindingRef");
            workloadIdentityRef = kind(
                    workloadIdentityRef,
                    "WORKLOAD_IDENTITY",
                    "workloadIdentityRef");
            workloadIdentityAttestationRef = kind(
                    workloadIdentityAttestationRef,
                    "WORKLOAD_IDENTITY_ATTESTATION",
                    "workloadIdentityAttestationRef");
            payloadVaultReceiptRef = kind(
                    payloadVaultReceiptRef,
                    "PAYLOAD_VAULT_RECEIPT",
                    "payloadVaultReceiptRef");
            transportAttestationRef = kind(
                    transportAttestationRef,
                    "READ_ONLY_TRANSPORT_ATTESTATION",
                    "transportAttestationRef");
            requestContextFingerprint = fingerprint(
                    requestContextFingerprint,
                    "requestContextFingerprint");
            semanticResultFingerprint = fingerprint(
                    semanticResultFingerprint,
                    "semanticResultFingerprint");
            sourceRequestFingerprint = fingerprint(
                    sourceRequestFingerprint,
                    "sourceRequestFingerprint");
            sourceResponseFingerprint = fingerprint(
                    sourceResponseFingerprint,
                    "sourceResponseFingerprint");
            responseSchemaRef = kind(
                    responseSchemaRef,
                    "JSON_SCHEMA",
                    "responseSchemaRef");
            normalizedFactFingerprints =
                    normalizedFactFingerprints == null
                            ? Map.of()
                            : Map.copyOf(
                            normalizedFactFingerprints);
            evidenceClass = Objects.requireNonNull(
                    evidenceClass, "evidenceClass");
            if (normalizedFactFingerprints.isEmpty()
                    || normalizedFactFingerprints
                    .size() > 4) {
                throw new IllegalArgumentException(
                        "synthetic baseline facts are empty or unbounded");
            }
            normalizedFactFingerprints.forEach(
                    (dimension, value) -> {
                        Objects.requireNonNull(
                                dimension, "dimension");
                        fingerprint(
                                value,
                                "normalizedFactFingerprint");
                    });
        }
    }

    private final class BaselineAuthority
            implements OnlineReadOnlyShadowBaselineAuthority {
        @Override
        public boolean ready() {
            return !fixtures.isEmpty()
                    && baselineIntegrity.available();
        }

        @Override
        public
        OnlineReadOnlyShadowBaselineObservation observe(
                OnlineReadOnlyShadowBaselineCommand command) {
            synchronized (monitor) {
                return observeLocked(command);
            }
        }

        private OnlineReadOnlyShadowBaselineObservation
        observeLocked(
                OnlineReadOnlyShadowBaselineCommand command) {
            OnlineReadOnlyShadowBaselineCommand exact =
                    Objects.requireNonNull(
                            command, "command");
            String commandFingerprint =
                    exact.commandFingerprint(mapper);
            OnlineReadOnlyShadowBaselineObservation existing =
                    baselineByExecution.get(
                            exact.executionId());
            if (existing != null) {
                if (!commandFingerprint.equals(
                        baselineCommandByExecution.get(
                                exact.executionId()))) {
                    throw baselineFailure(
                            OnlineReadOnlyShadowBaselineAuthority
                                    .Failure.REJECTED,
                            "SYNTHETIC_BASELINE_EXECUTION_ID_CONFLICT");
                }
                return existing;
            }
            BaselineFixture fixture =
                    fixtures.get(
                            exact.baselineBindingRef());
            if (fixture == null) {
                throw baselineFailure(
                        OnlineReadOnlyShadowBaselineAuthority
                                .Failure.REJECTED,
                        "SYNTHETIC_BASELINE_FIXTURE_NOT_ADMITTED");
            }
            if (baselines.size() >= maximumArtifacts) {
                throw baselineFailure(
                        OnlineReadOnlyShadowBaselineAuthority
                                .Failure.UNAVAILABLE,
                        "SYNTHETIC_BASELINE_CAPACITY_EXHAUSTED");
            }
            Instant observedAt = clock.instant();
            if (observedAt.isBefore(exact.admittedAt())
                    || !exact.deadlineAt()
                    .isAfter(observedAt)) {
                throw baselineFailure(
                        OnlineReadOnlyShadowBaselineAuthority
                                .Failure.REJECTED,
                        "SYNTHETIC_BASELINE_WINDOW_REJECTED");
            }
            OnlineReadOnlyShadowBaselineObservation unsigned =
                    new OnlineReadOnlyShadowBaselineObservation(
                            OnlineReadOnlyShadowBaselineObservation
                                    .SCHEMA_VERSION,
                            "",
                            OnlineReadOnlyShadowBaselineObservation
                                    .deterministicObservationId(
                                            mapper,
                                            exact.scope(),
                                            exact.executionId(),
                                            commandFingerprint,
                                            exact.baselineBindingRef()),
                            1,
                            exact.scope(),
                            exact.executionId(),
                            exact.requestId(),
                            commandFingerprint,
                            exact.scenarioCaseRef(),
                            exact.targetCapabilityRef(),
                            exact.baselineBindingRef(),
                            exact.comparisonPolicyRef(),
                            exact.accessGrant()
                                    .samplingGrantRef(),
                            exact.accessGrant()
                                    .egressAuthorityRef(),
                            exact.accessGrant()
                                    .killSwitchRef(),
                            fixture.workloadIdentityRef(),
                            fixture
                                    .workloadIdentityAttestationRef(),
                            fixture.payloadVaultReceiptRef(),
                            fixture.transportAttestationRef(),
                            fixture.requestContextFingerprint(),
                            fixture.semanticResultFingerprint(),
                            fixture.sourceRequestFingerprint(),
                            fixture.sourceResponseFingerprint(),
                            exact.idempotencyKeyFingerprint(
                                    mapper),
                            fixture.responseSchemaRef(),
                            fixture.normalizedFactFingerprints(),
                            OnlineReadOnlyShadowBaselineObservation
                                    .AccessMode.READ_ONLY,
                            observedAt,
                            observedAt,
                            exact.deadlineAt(),
                            exact.deadlineAt(),
                            fixture.evidenceClass(),
                            fixture.evidenceComplete(),
                            false,
                            0,
                            observedAt,
                            com.leanowtech.bloge.gateway.visual.runtime
                                    .VisualRunEvidenceSeal
                                    .unsigned());
            try {
                OnlineReadOnlyShadowBaselineObservation signed =
                        baselineIntegrity.sign(
                                unsigned);
                baselines.put(
                        signed.artifactRef(),
                        signed);
                baselineCommands.put(
                        signed.artifactRef(),
                        exact);
                baselineCommandByExecution.put(
                        exact.executionId(),
                        commandFingerprint);
                baselineByExecution.put(
                        exact.executionId(),
                        signed);
                return signed;
            } catch (RuntimeException unavailable) {
                throw baselineFailure(
                        OnlineReadOnlyShadowBaselineAuthority
                                .Failure.UNAVAILABLE,
                        "SYNTHETIC_BASELINE_SIGNING_UNAVAILABLE");
            }
        }

        @Override
        public
        OnlineReadOnlyShadowBaselineObservation resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef observationRef) {
            synchronized (monitor) {
                OnlineReadOnlyShadowBaselineObservation value =
                        baselines.get(
                                Objects.requireNonNull(
                                        observationRef,
                                        "observationRef"));
                if (value == null
                        || !value.scope().equals(
                        Objects.requireNonNull(
                                scope, "scope"))) {
                    throw baselineFailure(
                            OnlineReadOnlyShadowBaselineAuthority
                                    .Failure.NOT_FOUND,
                            "SYNTHETIC_BASELINE_NOT_FOUND");
                }
                return value;
            }
        }
    }

    private final class CandidateAuthority
            implements OnlineReadOnlyShadowCandidateAuthority {
        @Override
        public boolean ready() {
            return candidateIntegrity.available();
        }

        @Override
        public MirrorEvidenceBundle execute(
                OnlineReadOnlyShadowCandidateCommand command) {
            synchronized (monitor) {
                return executeLocked(command);
            }
        }

        private MirrorEvidenceBundle executeLocked(
                OnlineReadOnlyShadowCandidateCommand command) {
            OnlineReadOnlyShadowCandidateCommand exact =
                    Objects.requireNonNull(
                            command, "command");
            String commandFingerprint =
                    exact.commandFingerprint(mapper);
            MirrorEvidenceBundle existing =
                    candidateByExecution.get(
                            exact.executionId());
            if (existing != null) {
                if (!commandFingerprint.equals(
                        candidateCommandByExecution.get(
                                exact.executionId()))) {
                    throw candidateFailure(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .Failure.REJECTED,
                            "SYNTHETIC_CANDIDATE_EXECUTION_ID_CONFLICT");
                }
                return existing;
            }
            OnlineReadOnlyShadowBaselineObservation baseline =
                    baselines.get(
                            exact.baselineObservationRef());
            OnlineReadOnlyShadowBaselineCommand
                    baselineCommand =
                    baselineCommands.get(
                            exact.baselineObservationRef());
            if (baseline == null
                    || baselineCommand == null
                    || !baseline.scope().equals(exact.scope())
                    || !baseline.payloadVaultReceiptRef()
                    .equals(
                            exact.payloadVaultReceiptRef())
                    || !baseline.requestContextFingerprint()
                    .equals(
                            exact.requestContextFingerprint())
                    || !paired(
                            baselineCommand, exact)) {
                throw candidateFailure(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.REJECTED,
                        "SYNTHETIC_CANDIDATE_BASELINE_MISMATCH");
            }
            if (candidates.size() >= maximumArtifacts) {
                throw candidateFailure(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.UNAVAILABLE,
                        "SYNTHETIC_CANDIDATE_CAPACITY_EXHAUSTED");
            }
            MirrorEvidenceBundle bundle;
            try {
                bundle = candidateIntegrity.requireVerified(
                        candidateFactory.execute(
                                exact))
                        .bundle();
            } catch (IllegalStateException unavailable) {
                throw candidateFailure(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.UNAVAILABLE,
                        "SYNTHETIC_CANDIDATE_EVIDENCE_UNAVAILABLE");
            } catch (RuntimeException invalid) {
                throw candidateFailure(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.REJECTED,
                        "SYNTHETIC_CANDIDATE_EVIDENCE_INVALID");
            }
            MirrorRunEvidence evidence =
                    bundle.evidence();
            MirrorArtifactRef reference =
                    candidateRef(bundle);
            if (!evidence.requestId().equals(
                    commandFingerprint)
                    || !evidence.scope().equals(
                    exact.scope())
                    || !evidence.planId().equals(
                    exact.candidatePlanRef().id())
                    || !evidence.planFingerprint()
                    .equals(
                            exact.candidatePlanRef()
                                    .fingerprint())
                    || !evidence.rootCapability().equals(
                    exact.targetCapabilityRef())
                    || !evidence.requestContextFingerprint()
                    .equals(
                            exact.requestContextFingerprint())
                    || evidence.startedAt().isBefore(
                    baseline.completedAt())
                    || evidence.completedAt().isAfter(
                    exact.deadlineAt())
                    || bundle.attestation().signedAt()
                    .isAfter(exact.deadlineAt())) {
                throw candidateFailure(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.REJECTED,
                        "SYNTHETIC_CANDIDATE_COORDINATE_MISMATCH");
            }
            MirrorEvidenceBundle prior =
                    candidates.putIfAbsent(
                            reference,
                            bundle);
            if (prior != null
                    && !prior.equals(bundle)) {
                throw candidateFailure(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.REJECTED,
                        "SYNTHETIC_CANDIDATE_ARTIFACT_CONFLICT");
            }
            candidateCommandByExecution.put(
                    exact.executionId(),
                    commandFingerprint);
            candidateByExecution.put(
                    exact.executionId(),
                    bundle);
            return bundle;
        }

        @Override
        public MirrorEvidenceBundle resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef evidenceRef) {
            synchronized (monitor) {
                MirrorEvidenceBundle value =
                        candidates.get(
                                Objects.requireNonNull(
                                        evidenceRef,
                                        "evidenceRef"));
                if (value == null
                        || !value.evidence().scope().equals(
                        Objects.requireNonNull(
                                scope, "scope"))) {
                    throw candidateFailure(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .Failure.NOT_FOUND,
                            "SYNTHETIC_CANDIDATE_NOT_FOUND");
                }
                return value;
            }
        }
    }

    private static boolean paired(
            OnlineReadOnlyShadowBaselineCommand baseline,
            OnlineReadOnlyShadowCandidateCommand candidate) {
        return baseline.executionId().equals(
                candidate.executionId())
                && baseline.requestId().equals(
                candidate.requestId())
                && baseline.scope().equals(
                candidate.scope())
                && baseline.inventoryRef().equals(
                candidate.inventoryRef())
                && baseline.unitId().equals(
                candidate.unitId())
                && baseline.scenarioCaseRef().equals(
                candidate.scenarioCaseRef())
                && baseline.targetCapabilityRef()
                .equals(
                        candidate.targetCapabilityRef())
                && baseline.comparisonPolicyRef()
                .equals(
                        candidate.comparisonPolicyRef())
                && baseline.accessGrant().equals(
                candidate.accessGrant())
                && baseline.admissionFingerprint()
                .equals(
                        candidate.admissionFingerprint())
                && baseline.admittedAt().equals(
                candidate.admittedAt())
                && baseline.deadlineAt().equals(
                candidate.deadlineAt());
    }

    private static Map<MirrorArtifactRef, BaselineFixture>
    fixtures(
            List<BaselineFixture> supplied) {
        LinkedHashMap<MirrorArtifactRef, BaselineFixture>
                result =
                new LinkedHashMap<>();
        List<BaselineFixture> values =
                supplied == null
                        ? List.of()
                        : List.copyOf(supplied);
        if (values.size() > 10_000) {
            throw new IllegalArgumentException(
                    "synthetic baseline fixture catalog is unbounded");
        }
        for (BaselineFixture fixture : values) {
            BaselineFixture exact =
                    Objects.requireNonNull(
                            fixture, "fixture");
            if (result.put(
                    exact.baselineBindingRef(),
                    exact) != null) {
                throw new IllegalArgumentException(
                        "synthetic baseline fixture binding is duplicated");
            }
        }
        return Map.copyOf(result);
    }

    private static MirrorArtifactRef candidateRef(
            MirrorEvidenceBundle bundle) {
        return new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                bundle.evidence().runId(),
                1,
                bundle.bundleFingerprint());
    }

    private static OnlineReadOnlyShadowBaselineAuthority
            .AuthorityException baselineFailure(
            OnlineReadOnlyShadowBaselineAuthority.Failure failure,
            String reason) {
        return new OnlineReadOnlyShadowBaselineAuthority
                .AuthorityException(
                failure, reason);
    }

    private static OnlineReadOnlyShadowCandidateAuthority
            .AuthorityException candidateFailure(
            OnlineReadOnlyShadowCandidateAuthority.Failure failure,
            String reason) {
        return new OnlineReadOnlyShadowCandidateAuthority
                .AuthorityException(
                failure, reason);
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
