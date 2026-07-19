package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityCrossRetentionTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationArchiveSegment;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityObservationFloorRetirementAttestationServiceTest {
    private static final Instant RETIRED_AT =
            TestSuiteStabilityCrossRetentionTrendProtocolFixtures.OBSERVED_AT;

    private ObjectMapper mapper;
    private TestSuiteStabilityObservationFloorRetirementAttestationService service;
    private TestSuiteStabilityObservationFloorRetirementEvidence evidence;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        var sourceAttestations = new TestSuiteStabilityAttestationService(mapper, signer);
        var observationAttestations = new TestSuiteStabilityObservationAttestationService(
                mapper, signer, sourceAttestations);
        var fixture = TestSuiteStabilityCrossRetentionTrendProtocolFixtures.range(
                mapper, sourceAttestations, observationAttestations, '1', '2', '3');
        evidence = evidence(fixture, TestSuiteStabilityProtocolFixtures.fingerprint('a'));
        service = new TestSuiteStabilityObservationFloorRetirementAttestationService(
                mapper, signer, Clock.fixed(RETIRED_AT.plusSeconds(1), ZoneOffset.UTC));
    }

    @Test
    void signsAndImmediatelyVerifiesTheExactFloorHeadAndArchiveClosure() {
        var sealed = service.seal(evidence);

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.attestation().retirementId()).isEqualTo(evidence.retirementId());
        assertThat(sealed.attestation().archiveSegmentFingerprint()).isEqualTo(
                evidence.archiveSegment().segmentFingerprint());
        assertThat(sealed.attestation().previousFloorFingerprint()).isEqualTo(
                evidence.previousFloor().floorFingerprint());
        assertThat(sealed.attestation().pinnedHeadFingerprint()).isEqualTo(
                evidence.pinnedHead().headFingerprint());
        assertThat(service.verify(evidence, sealed.attestation()))
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void rejectsAReboundPolicyEvenWhenTheEvidenceShapeRemainsValid() {
        var rebound = copyEvidence(
                evidence, evidence.retirementId(),
                TestSuiteStabilityProtocolFixtures.fingerprint('b'));

        assertThat(service.seal(rebound).failureCode()).isEqualTo(
                TestSuiteStabilityObservationFloorRetirementAttestationService
                        .EVIDENCE_INVALID);
    }

    @Test
    void detachedSignatureCannotBeReusedForAnotherCanonicalRetirement() {
        var sealed = service.seal(evidence);
        var changedUnsigned = copyEvidence(
                evidence, zeroRetirementId(),
                TestSuiteStabilityProtocolFixtures.fingerprint('b'));
        String changedId = TestSuiteStabilityObservationFloorRetirementIntegrity.retirementId(
                mapper, changedUnsigned);
        var changed = copyEvidence(changedUnsigned, changedId,
                changedUnsigned.retentionPolicyFingerprint());
        TestSuiteStabilityObservationFloorRetirementAttestation rebound =
                new TestSuiteStabilityObservationFloorRetirementAttestation(
                        sealed.attestation().schemaVersion(),
                        sealed.attestation().signatureStatus(), changed.retirementId(),
                        ProtocolFingerprint.of(mapper, changed),
                        changed.archiveSegment().segmentFingerprint(),
                        changed.previousFloor().floorFingerprint(),
                        changed.pinnedHead().headFingerprint(),
                        sealed.attestation().signedAt(), sealed.attestation().keyId(),
                        sealed.attestation().algorithm(), sealed.attestation().signature(), true);

        assertThat(service.verify(changed, rebound))
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementAttestationService
                        .Verification.INVALID);
    }

    @Test
    void unavailableSignerReturnsNoPartialAttestation() {
        var unavailable = new TestSuiteStabilityObservationFloorRetirementAttestationService(
                mapper, VisualEvidenceSigner.unavailable(),
                Clock.fixed(RETIRED_AT.plusSeconds(1), ZoneOffset.UTC));

        var result = unavailable.seal(evidence);

        assertThat(result.verified()).isFalse();
        assertThat(result.attestation()).isNull();
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationFloorRetirementAttestationService
                        .SIGNER_UNAVAILABLE);
    }

    private TestSuiteStabilityObservationFloorRetirementEvidence evidence(
            TestSuiteStabilityCrossRetentionTrendProtocolFixtures.Fixture fixture,
            String policyFingerprint) {
        var first = fixture.entries().getFirst();
        TestSuiteStabilityObservationLedgerFloor unsignedFloor =
                new TestSuiteStabilityObservationLedgerFloor(
                        TestSuiteStabilityObservationLedgerFloor.SCHEMA_VERSION,
                        first.scopeFingerprint(), TestSuiteStabilityProtocolFixtures.SUITE_REF,
                        1, "", "", first.observation().evidence().observationId(),
                        first.entryFingerprint(), first.appendedAt(), 0, "", "",
                        first.appendedAt(), TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerFloor floor =
                new TestSuiteStabilityObservationLedgerFloor(
                        unsignedFloor.schemaVersion(), unsignedFloor.scopeFingerprint(),
                        unsignedFloor.suiteRef(), unsignedFloor.floorSequence(),
                        unsignedFloor.previousObservationId(),
                        unsignedFloor.previousEntryFingerprint(),
                        unsignedFloor.floorObservationId(),
                        unsignedFloor.floorEntryFingerprint(), unsignedFloor.coverageFrom(),
                        unsignedFloor.retirementGeneration(),
                        unsignedFloor.latestRetirementId(),
                        unsignedFloor.latestRetirementFingerprint(), unsignedFloor.updatedAt(),
                        TestSuiteStabilityObservationLedgerFloorIntegrity.fingerprint(
                                mapper, unsignedFloor));
        var retired = fixture.entries().subList(0, 2);
        var successor = fixture.entries().get(2);
        String archiveId = TestSuiteStabilityObservationFloorRetirementIntegrity.archiveId(
                mapper, first.scopeFingerprint(), TestSuiteStabilityProtocolFixtures.SUITE_REF,
                1, "", "", retired, successor, RETIRED_AT);
        TestSuiteStabilityObservationArchiveSegment unsignedArchive =
                new TestSuiteStabilityObservationArchiveSegment(
                        TestSuiteStabilityObservationArchiveSegment.SCHEMA_VERSION,
                        archiveId, first.scopeFingerprint(),
                        TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, "", "", retired,
                        successor, RETIRED_AT,
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationArchiveSegment archive =
                new TestSuiteStabilityObservationArchiveSegment(
                        unsignedArchive.schemaVersion(), unsignedArchive.segmentId(),
                        unsignedArchive.scopeFingerprint(), unsignedArchive.suiteRef(),
                        unsignedArchive.retirementGeneration(),
                        unsignedArchive.previousObservationId(),
                        unsignedArchive.previousEntryFingerprint(),
                        unsignedArchive.retiredEntries(), unsignedArchive.successorEntry(),
                        unsignedArchive.archivedAt(),
                        TestSuiteStabilityObservationFloorRetirementIntegrity
                                .archiveFingerprint(mapper, unsignedArchive));
        TestSuiteStabilityObservationFloorRetirementEvidence unsigned =
                new TestSuiteStabilityObservationFloorRetirementEvidence(
                        TestSuiteStabilityObservationFloorRetirementEvidence.SCHEMA_VERSION,
                        zeroRetirementId(), first.scopeFingerprint(),
                        TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, floor,
                        fixture.range().head(), archive, successor.appendedAt(), 1, 2,
                        policyFingerprint,
                        TestSuiteStabilityObservationFloorRetirementEvidence.Reason
                                .RETENTION_POLICY,
                        RETIRED_AT);
        return copyEvidence(unsigned,
                TestSuiteStabilityObservationFloorRetirementIntegrity.retirementId(
                        mapper, unsigned),
                policyFingerprint);
    }

    private static TestSuiteStabilityObservationFloorRetirementEvidence copyEvidence(
            TestSuiteStabilityObservationFloorRetirementEvidence value,
            String retirementId,
            String policyFingerprint) {
        return new TestSuiteStabilityObservationFloorRetirementEvidence(
                value.schemaVersion(), retirementId, value.scopeFingerprint(), value.suiteRef(),
                value.retirementGeneration(), value.previousFloor(), value.pinnedHead(),
                value.archiveSegment(), value.cutoffExclusive(), value.minimumRetainedEntries(),
                value.maximumRetiredEntries(), policyFingerprint, value.reason(), value.retiredAt());
    }

    private static String zeroRetirementId() {
        return "stability-observation-retirement-" + "0".repeat(64);
    }
}
