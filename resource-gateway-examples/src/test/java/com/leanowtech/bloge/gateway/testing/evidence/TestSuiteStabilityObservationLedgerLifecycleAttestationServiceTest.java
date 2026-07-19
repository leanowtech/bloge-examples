package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleAttestation;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityObservationLedgerLifecycleAttestationServiceTest {
    private ObjectMapper mapper;
    private TestSuiteStabilityObservationLifecycleProtocolFixtures.Fixture fixture;
    private TestSuiteStabilityObservationLedgerLifecycleAttestationService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        fixture = TestSuiteStabilityObservationLifecycleProtocolFixtures.page(mapper, signer);
        service = new TestSuiteStabilityObservationLedgerLifecycleAttestationService(
                mapper, signer, Clock.fixed(
                fixture.page().observedAt().plusSeconds(1), ZoneOffset.UTC));
    }

    @Test
    void signsAndImmediatelyVerifiesTheCompleteOrderedLifecycleClosure() {
        var sealed = service.seal(fixture.page());

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.attestation().requestFingerprint())
                .isEqualTo(fixture.page().requestFingerprint());
        assertThat(sealed.attestation().startingFloorFingerprint())
                .isEqualTo(fixture.page().startingFloor().floorFingerprint());
        assertThat(sealed.attestation().terminalFloorFingerprint())
                .isEqualTo(fixture.page().terminalFloor().floorFingerprint());
        assertThat(sealed.attestation().currentFloorFingerprint())
                .isEqualTo(fixture.page().currentFloor().floorFingerprint());
        assertThat(sealed.attestation().retirementRefs()).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.retirementGeneration()).isEqualTo(1);
                    assertThat(ref.retirementId()).isEqualTo(
                            fixture.page().retirements().getFirst().evidence().retirementId());
                });
        assertThat(service.verify(fixture.page(), sealed.attestation()))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void detachedSignatureCannotBeReboundToAnotherCurrentFloorPin() {
        var sealed = service.seal(fixture.page());
        var value = sealed.attestation();
        var rebound = new TestSuiteStabilityObservationLedgerLifecycleAttestation(
                value.schemaVersion(), value.signatureStatus(), value.lifecyclePageId(),
                value.requestFingerprint(), value.pageFingerprint(), value.scopeFingerprint(),
                value.startingFloorFingerprint(), value.terminalFloorFingerprint(),
                TestSuiteStabilityProtocolFixtures.fingerprint('f'), value.headFingerprint(),
                value.retirementRefs(), value.signedAt(), value.keyId(), value.algorithm(),
                value.signature(), true);

        assertThat(service.verify(fixture.page(), rebound))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleAttestationService
                        .Verification.INVALID);
    }

    @Test
    void signatureTimeBeforeTheDatabaseSnapshotIsRejected() {
        var sealed = service.seal(fixture.page());
        var value = sealed.attestation();
        var rebound = new TestSuiteStabilityObservationLedgerLifecycleAttestation(
                value.schemaVersion(), value.signatureStatus(), value.lifecyclePageId(),
                value.requestFingerprint(), value.pageFingerprint(), value.scopeFingerprint(),
                value.startingFloorFingerprint(), value.terminalFloorFingerprint(),
                value.currentFloorFingerprint(), value.headFingerprint(),
                value.retirementRefs(), fixture.page().observedAt().minusNanos(1), value.keyId(),
                value.algorithm(), value.signature(), true);

        assertThat(service.verify(fixture.page(), rebound))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleAttestationService
                        .Verification.INVALID);
    }

    @Test
    void unavailableSignerReturnsNoPartialLifecycleAttestation() {
        var unavailable = new TestSuiteStabilityObservationLedgerLifecycleAttestationService(
                mapper, VisualEvidenceSigner.unavailable(), Clock.systemUTC());

        var result = unavailable.seal(fixture.page());

        assertThat(result.verified()).isFalse();
        assertThat(result.attestation()).isNull();
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleAttestationService
                        .SIGNER_UNAVAILABLE);
    }
}
