package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityObservationAdmissionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityObservationIntegrity observationIntegrity =
            new CapabilityObservationIntegrity(mapper);
    private final CapabilityObservationAdmissionIntegrity admissionIntegrity =
            new CapabilityObservationAdmissionIntegrity(mapper);
    private final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private CapabilitySnapshot capability;
    private CapabilityObservationEnvelope envelope;
    private CapabilityObservationIntegrity.AuthorityKey authorityKey;
    private MutablePolicyProvider policies;
    private MutablePayloadVerifier payloads;
    private RecordingObservationRepository observations;
    private RecordingAudit audit;
    private CapabilityObservationAdmissionService service;

    @BeforeEach
    void setUp() {
        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        capability = CapabilityObservationTestFixtures.capability(mapper, scope);
        envelope = CapabilityObservationTestFixtures.envelope(
                mapper, signer, capability, "observation-a");
        authorityKey = CapabilityObservationTestFixtures.authorityKey(
                envelope, signer, CapabilityObservationIntegrity.KeyState.ACTIVE);
        policies = new MutablePolicyProvider(
                CapabilityObservationTestFixtures.policy(envelope, authorityKey));
        payloads = new MutablePayloadVerifier(
                CapabilityObservationPayloadReferenceVerifier.VerificationResult
                        .verified());
        observations = new RecordingObservationRepository();
        audit = new RecordingAudit();
        service = service(new SingleCapabilityRepository(capability));
    }

    @Test
    void admitsVerifiedObservationAndExactRetryDoesNotReconsultMutableProviders() {
        CapabilityObservationRepository.StoredObservation first =
                service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));
        policies.available = false;
        payloads.available = false;

        CapabilityObservationRepository.StoredObservation retried =
                service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));

        assertThat(first.admission().state())
                .isEqualTo(CapabilityObservationAdmission.State.ADMITTED);
        assertThat(first.admission().reason())
                .isEqualTo(CapabilityObservationAdmission.Reason.ACCEPTED);
        assertThat(retried).isEqualTo(first);
        assertThat(policies.calls).hasValue(1);
        assertThat(payloads.calls).hasValue(1);
        assertThat(observations.values).hasSize(1);
        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::operation)
                .containsOnly(MirrorOperationAuditEvent.Operation.OBSERVATION_INGEST);
        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::outcome)
                .containsOnly(MirrorOperationAuditEvent.Outcome.SUCCEEDED);
    }

    @Test
    void invalidProducerSignatureBecomesDurableQuarantineWithoutPayloadLookup() {
        InMemoryVisualEvidenceSigner wrongSigner = new InMemoryVisualEvidenceSigner();
        VisualEvidenceSigner.VerificationKey wrongKey = wrongSigner.key(
                wrongSigner.descriptor().activeKeyId()).orElseThrow();
        CapabilityObservationIntegrity.AuthorityKey mismatchedMaterial =
                new CapabilityObservationIntegrity.AuthorityKey(
                        new MirrorArtifactRef(
                                "OBSERVATION_AUTHORITY_KEY",
                                envelope.seal().keyId(),
                                1,
                                CapabilityObservationTestFixtures.fingerprint('8')),
                        "Ed25519",
                        wrongKey.encodedPublicKey(),
                        CapabilityObservationTestFixtures.ISSUER,
                        envelope.seal().signedAt().minusSeconds(10),
                        envelope.seal().signedAt().plus(Duration.ofDays(1)),
                        CapabilityObservationIntegrity.KeyState.ACTIVE);
        policies.policy = CapabilityObservationTestFixtures.policy(
                envelope, mismatchedMaterial);

        CapabilityObservationRepository.StoredObservation stored =
                service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));

        assertThat(stored.admission().state())
                .isEqualTo(CapabilityObservationAdmission.State.QUARANTINED);
        assertThat(stored.admission().reason())
                .isEqualTo(
                        CapabilityObservationAdmission.Reason.INTEGRITY_REJECTED);
        assertThat(payloads.calls).hasValue(0);
        assertThat(observations.values).hasSize(1);
    }

    @Test
    void externalSanitizationProofRejectionBecomesDurableQuarantine() {
        payloads.result =
                CapabilityObservationPayloadReferenceVerifier.VerificationResult
                        .rejected("SANITIZATION_PROOF_REJECTED");

        CapabilityObservationRepository.StoredObservation stored =
                service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));

        assertThat(stored.admission().state())
                .isEqualTo(CapabilityObservationAdmission.State.QUARANTINED);
        assertThat(stored.admission().reason())
                .isEqualTo(
                        CapabilityObservationAdmission.Reason
                                .PAYLOAD_REFERENCE_REJECTED);
    }

    @Test
    void missingCapabilityAndMissingPolicyAreExplicitQuarantineReasons() {
        CapabilityObservationAdmissionService withoutCapability =
                service(new SingleCapabilityRepository(null));

        CapabilityObservationRepository.StoredObservation missingCapability =
                withoutCapability.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));
        assertThat(missingCapability.admission().reason())
                .isEqualTo(
                        CapabilityObservationAdmission.Reason
                                .CAPABILITY_NOT_ELIGIBLE);

        observations.values.clear();
        policies.policy = null;
        CapabilityObservationRepository.StoredObservation missingPolicy =
                service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));
        assertThat(missingPolicy.admission().reason())
                .isEqualTo(
                        CapabilityObservationAdmission.Reason
                                .ADMISSION_POLICY_NOT_FOUND);
    }

    @Test
    void providerUnavailabilityReturns503AndPersistsNoFalseDecision() {
        policies.available = false;

        assertProblem(
                () -> service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a")),
                503,
                "RG.MIRROR.OBSERVATION_POLICY_UNAVAILABLE");
        assertThat(observations.values).isEmpty();

        policies.available = true;
        payloads.available = false;
        assertProblem(
                () -> service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a")),
                503,
                "RG.MIRROR.OBSERVATION_PAYLOAD_AUTHORITY_UNAVAILABLE");
        assertThat(observations.values).isEmpty();
    }

    @Test
    void crossScopeIdentityIsRejectedBeforePolicyCapabilityOrPersistenceLookup() {
        SingleCapabilityRepository capabilityRepository =
                new SingleCapabilityRepository(capability);
        service = service(capabilityRepository);

        assertProblem(
                () -> service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-b")),
                403,
                "RG.MIRROR.OBSERVATION_SCOPE_MISMATCH");

        assertThat(observations.findCalls).hasValue(0);
        assertThat(policies.calls).hasValue(0);
        assertThat(capabilityRepository.findCalls).hasValue(0);
        assertThat(payloads.calls).hasValue(0);
    }

    @Test
    void tooOldObservationIsQuarantinedBeforeExternalPayloadLookup() {
        policies.policy = new CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy(
                policies.policy.scope(),
                policies.policy.capabilityRef(),
                policies.policy.policyRef(),
                policies.policy.grantRef(),
                policies.policy.authorityKey(),
                policies.policy.allowedClassifications(),
                policies.policy.allowedVaultRegions(),
                policies.policy.requiredUses(),
                Duration.ofSeconds(1),
                policies.policy.maximumFutureSkew(),
                policies.policy.maximumPayloadBytes(),
                policies.policy.minimumRemainingRetention());

        CapabilityObservationRepository.StoredObservation stored =
                service.ingest(
                        envelope,
                        CapabilityObservationTestFixtures.identity("org-a"));

        assertThat(stored.admission().reason())
                .isEqualTo(
                        CapabilityObservationAdmission.Reason
                                .OBSERVATION_WINDOW_REJECTED);
        assertThat(payloads.calls).hasValue(0);
    }

    private CapabilityObservationAdmissionService service(
            CapabilitySnapshotRepository capabilityRepository) {
        Instant now = envelope.seal().signedAt().plusSeconds(1);
        return new CapabilityObservationAdmissionService(
                observations,
                capabilityRepository,
                policies,
                payloads,
                observationIntegrity,
                admissionIntegrity,
                new MirrorOperationObservability(
                        audit, MirrorOperationTelemetry.noop(), () -> 0),
                mapper,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void assertProblem(
            Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private static final class RecordingObservationRepository
            implements CapabilityObservationRepository {
        private final Map<String, StoredObservation> values = new LinkedHashMap<>();
        private final AtomicInteger findCalls = new AtomicInteger();

        @Override
        public StoredObservation append(StoredObservation candidate) {
            String key = key(
                    candidate.envelope().material().scope(),
                    candidate.envelope().material().observationId());
            StoredObservation existing = values.putIfAbsent(key, candidate);
            if (existing == null
                    || existing.envelope().observationFingerprint().equals(
                    candidate.envelope().observationFingerprint())) {
                return existing == null ? candidate : existing;
            }
            throw new Violation(Reason.OBSERVATION_ID_CONFLICT);
        }

        @Override
        public Optional<StoredObservation> find(
                CapabilitySnapshot.Scope scope, String observationId) {
            findCalls.incrementAndGet();
            return Optional.ofNullable(values.get(key(scope, observationId)));
        }

        private static String key(
                CapabilitySnapshot.Scope scope, String observationId) {
            return scope.toString() + '\0' + observationId;
        }
    }

    private static final class SingleCapabilityRepository
            implements CapabilitySnapshotRepository {
        private final CapabilitySnapshot capability;
        private final AtomicInteger findCalls = new AtomicInteger();

        private SingleCapabilityRepository(CapabilitySnapshot capability) {
            this.capability = capability;
        }

        @Override
        public CapabilitySnapshot create(CapabilitySnapshot snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CapabilitySnapshot> find(
                CapabilitySnapshot.Scope scope,
                String capabilityId,
                long revision) {
            findCalls.incrementAndGet();
            if (capability == null
                    || !capability.scope().equals(scope)
                    || !capability.capabilityId().equals(capabilityId)
                    || capability.revision() != revision) {
                return Optional.empty();
            }
            return Optional.of(capability);
        }

        @Override
        public Optional<CapabilitySnapshot> findLatest(
                CapabilitySnapshot.Scope scope, String capabilityId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutablePolicyProvider
            implements CapabilityObservationAdmissionPolicyProvider {
        private volatile boolean available = true;
        private volatile AdmissionPolicy policy;
        private final AtomicInteger calls = new AtomicInteger();

        private MutablePolicyProvider(AdmissionPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<AdmissionPolicy> resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef capabilityRef,
                MirrorArtifactRef grantRef,
                String keyId) {
            calls.incrementAndGet();
            return Optional.ofNullable(policy);
        }
    }

    private static final class MutablePayloadVerifier
            implements CapabilityObservationPayloadReferenceVerifier {
        private volatile boolean available = true;
        private volatile VerificationResult result;
        private final AtomicInteger calls = new AtomicInteger();

        private MutablePayloadVerifier(VerificationResult result) {
            this.result = result;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public VerificationResult verify(
                CapabilityObservationEnvelope envelope,
                CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
                Instant verificationTime) {
            calls.incrementAndGet();
            return result;
        }
    }

    private static final class RecordingAudit
            implements MirrorOperationAuditRepository {
        private final List<MirrorOperationAuditEvent> events = new ArrayList<>();

        @Override
        public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return List.copyOf(events);
        }
    }
}
