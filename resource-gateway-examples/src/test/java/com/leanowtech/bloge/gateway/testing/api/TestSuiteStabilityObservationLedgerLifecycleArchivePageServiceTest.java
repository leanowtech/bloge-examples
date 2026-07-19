package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationLedgerLifecycleArchivePageServiceTest {
    private ObjectMapper mapper;
    private TestSuiteStabilityObservationLedgerLifecyclePageService lifecyclePages;
    private TestSuiteStabilityRunRepository repository;
    private TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.Fixture fixture;
    private TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService attestations;
    private TestSuiteStabilityObservationLedgerLifecycleArchivePageService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var signer = new InMemoryVisualEvidenceSigner();
        fixture = TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures.page(
                mapper, signer);
        lifecyclePages = mock(TestSuiteStabilityObservationLedgerLifecyclePageService.class);
        repository = mock(TestSuiteStabilityRunRepository.class);
        attestations =
                new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
                        mapper, signer);
        service = new TestSuiteStabilityObservationLedgerLifecycleArchivePageService(
                lifecyclePages, repository, mapper, attestations);
        when(lifecyclePages.read("suite-a", fixture.v1Response().page().request(), identity()))
                .thenReturn(fixture.v1Response());
        when(repository.findObservationExternalArchiveReceiptSet(
                fixture.receiptSet().request().retirement().evidence().retirementId()))
                .thenReturn(Optional.of(fixture.receiptSet()));
    }

    @Test
    void addsExactReceiptProofOnlyAfterTheAuthorizedV1PagePasses() {
        var response = service.read(
                "suite-a", fixture.v1Response().page().request(), identity());

        assertThat(response.schemaVersion())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse
                        .SCHEMA_VERSION);
        assertThat(response.page().retirements())
                .isEqualTo(fixture.v1Response().page().retirements());
        assertThat(response.page().externalArchiveReceiptSets())
                .containsExactly(fixture.receiptSet());
        assertThat(attestations.verify(response.page(), response.attestation()))
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void missingOrCanonicallyInvalidReceiptProofFailsClosed() {
        String retirementId = fixture.receiptSet().request().retirement()
                .evidence().retirementId();
        when(repository.findObservationExternalArchiveReceiptSet(retirementId))
                .thenReturn(Optional.empty());
        assertCode(() -> service.read(
                        "suite-a", fixture.v1Response().page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PROOF_MISSING");

        var proof = fixture.receiptSet();
        var forged = new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                proof.schemaVersion(), proof.receiptSetId(), proof.request(),
                proof.requiredCopies(), proof.receipts(), proof.confirmedAt(),
                TestSuiteStabilityProtocolFixtures.fingerprint('f'));
        when(repository.findObservationExternalArchiveReceiptSet(retirementId))
                .thenReturn(Optional.of(forged));
        assertCode(() -> service.read(
                        "suite-a", fixture.v1Response().page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PROOF_INVALID");
    }

    @Test
    void repositoryOrV2SignerOutageCannotEmitPartialProof() {
        when(repository.findObservationExternalArchiveReceiptSet(any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertCode(() -> service.read(
                        "suite-a", fixture.v1Response().page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PROOF_UNAVAILABLE");

        reset(repository);
        when(repository.findObservationExternalArchiveReceiptSet(any()))
                .thenReturn(Optional.of(fixture.receiptSet()));
        var unavailable = new TestSuiteStabilityObservationLedgerLifecycleArchivePageService(
                lifecyclePages, repository, mapper,
                new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
                        mapper, VisualEvidenceSigner.unavailable()));
        assertCode(() -> unavailable.read(
                        "suite-a", fixture.v1Response().page().request(), identity()),
                "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_ATTESTATION_UNAVAILABLE");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "SERVICE", "actor-a", "", "TEST_EXECUTION", "corr-a",
                Set.of(), "INTERNAL", "");
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code()).isEqualTo(code));
    }
}
