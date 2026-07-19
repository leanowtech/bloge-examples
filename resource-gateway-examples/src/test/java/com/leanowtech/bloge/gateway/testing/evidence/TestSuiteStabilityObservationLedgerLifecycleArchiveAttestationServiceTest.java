package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationServiceTest {
    private TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.Fixture fixture;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        fixture = TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.page(
                mapper, new InMemoryVisualEvidenceSigner());
    }

    @Test
    void sealsAndVerifiesExactArchiveReferenceClosure() {
        var response = fixture.v2Response();

        assertThat(fixture.archiveAttestations().verify(
                response.page(), response.attestation()))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void rejectsReceiptSetReferenceRebindingEvenWithValidShape() {
        var response = fixture.v2Response();
        var attestation = response.attestation();
        var ref = attestation.archiveRefs().getFirst();
        var reboundRef = new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation
                .ArchiveRef(ref.retirementGeneration(), ref.retirementId(),
                ref.retirementFingerprint(), ref.receiptSetId(),
                "sha256:" + "f".repeat(64), ref.requiredCopies(), ref.receiptCount());
        var rebound = new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation(
                attestation.schemaVersion(), attestation.signatureStatus(),
                attestation.lifecyclePageId(), attestation.requestFingerprint(),
                attestation.pageFingerprint(), attestation.scopeFingerprint(),
                attestation.startingFloorFingerprint(), attestation.terminalFloorFingerprint(),
                attestation.currentFloorFingerprint(), attestation.headFingerprint(),
                List.of(reboundRef), attestation.signedAt(), attestation.keyId(),
                attestation.algorithm(), attestation.signature(),
                attestation.independentlyVerifiable());

        assertThat(fixture.archiveAttestations().verify(response.page(), rebound))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                        .Verification.INVALID);
    }

    @Test
    void unavailableSignerProducesNoPartialAttestation() {
        var unavailable =
                new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
                        new ObjectMapper().findAndRegisterModules(),
                        VisualEvidenceSigner.unavailable());

        var result = unavailable.seal(fixture.v2Response().page());
        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                        .SIGNER_UNAVAILABLE);
    }
}
