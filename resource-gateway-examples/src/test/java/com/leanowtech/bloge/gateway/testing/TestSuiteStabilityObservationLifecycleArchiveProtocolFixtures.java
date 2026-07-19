package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecycleArchivePage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePageResponse;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecyclePageIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Duration;
import java.util.List;

/** Canonical one-transition receipt-aware lifecycle fixture for server protocol tests. */
public final class TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures {
    private TestSuiteStabilityObservationLifecycleArchiveProtocolFixtures() {
    }

    /**
     * Builds paired v1 and signed v2 lifecycle responses using one lifecycle signing authority.
     *
     * @param mapper canonical protocol mapper
     * @param signer shared lifecycle and retirement test signer
     * @return complete receipt-aware fixture
     */
    public static Fixture page(ObjectMapper mapper, VisualEvidenceSigner signer) {
        TestSuiteStabilityObservationLifecycleProtocolFixtures.Fixture base =
                TestSuiteStabilityObservationLifecycleProtocolFixtures.page(mapper, signer);
        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                TestSuiteStabilityObservationExternalArchiveProtocolFixtures.receiptSet(
                        mapper, base.page().retirements().getFirst(),
                        base.page().retirements().getFirst().evidence().retiredAt()
                                .plus(Duration.ofDays(30)));
        var observedAt = receiptSet.confirmedAt().plusSeconds(1);
        TestSuiteStabilityObservationLedgerLifecyclePage v1Unsigned =
                new TestSuiteStabilityObservationLedgerLifecyclePage(
                        base.page().schemaVersion(), base.page().requestFingerprint(),
                        base.page().request(), base.page().scopeFingerprint(),
                        base.page().startingFloor(), base.page().retirements(),
                        base.page().terminalFloor(), base.page().currentFloor(),
                        base.page().head(), base.page().hasMore(), observedAt,
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerLifecyclePage v1Page =
                new TestSuiteStabilityObservationLedgerLifecyclePage(
                        v1Unsigned.schemaVersion(), v1Unsigned.requestFingerprint(),
                        v1Unsigned.request(), v1Unsigned.scopeFingerprint(),
                        v1Unsigned.startingFloor(), v1Unsigned.retirements(),
                        v1Unsigned.terminalFloor(), v1Unsigned.currentFloor(),
                        v1Unsigned.head(), v1Unsigned.hasMore(), v1Unsigned.observedAt(),
                        TestSuiteStabilityObservationLedgerLifecyclePageIntegrity
                                .pageFingerprint(mapper, v1Unsigned));
        var v1Seal = new TestSuiteStabilityObservationLedgerLifecycleAttestationService(
                mapper, signer).seal(v1Page);
        if (!v1Seal.verified()) {
            throw new IllegalStateException("Lifecycle v1 fixture could not be signed");
        }
        String v1PageId = TestSuiteStabilityObservationLedgerLifecyclePageIntegrity
                .lifecyclePageId(mapper, v1Page.requestFingerprint(),
                        v1Page.pageFingerprint());
        TestSuiteStabilityObservationLedgerLifecyclePageResponse v1Response =
                new TestSuiteStabilityObservationLedgerLifecyclePageResponse(
                        TestSuiteStabilityObservationLedgerLifecyclePageResponse.SCHEMA_VERSION,
                        v1PageId, v1Page.pageFingerprint(), v1Page,
                        v1Seal.attestation());
        TestSuiteStabilityObservationLedgerLifecycleArchivePage unsigned =
                new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                        TestSuiteStabilityObservationLedgerLifecycleArchivePage.SCHEMA_VERSION,
                        v1Page.requestFingerprint(), v1Page.request(),
                        v1Page.scopeFingerprint(), v1Page.startingFloor(),
                        v1Page.retirements(), List.of(receiptSet),
                        v1Page.terminalFloor(), v1Page.currentFloor(),
                        v1Page.head(), v1Page.hasMore(), observedAt,
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerLifecycleArchivePage archivePage =
                new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                        unsigned.schemaVersion(), unsigned.requestFingerprint(),
                        unsigned.request(), unsigned.scopeFingerprint(),
                        unsigned.startingFloor(), unsigned.retirements(),
                        unsigned.externalArchiveReceiptSets(), unsigned.terminalFloor(),
                        unsigned.currentFloor(), unsigned.head(), unsigned.hasMore(),
                        unsigned.observedAt(),
                        TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                                .pageFingerprint(mapper, unsigned));
        var archiveAttestations =
                new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
                        mapper, signer);
        var v2Seal = archiveAttestations.seal(archivePage);
        if (!v2Seal.verified()) {
            throw new IllegalStateException("Lifecycle v2 fixture could not be signed");
        }
        String v2PageId = TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                .lifecyclePageId(mapper, archivePage.requestFingerprint(),
                        archivePage.pageFingerprint());
        TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse v2Response =
                new TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse(
                        TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse
                                .SCHEMA_VERSION,
                        v2PageId, archivePage.pageFingerprint(), archivePage,
                        v2Seal.attestation());
        return new Fixture(v1Response, v2Response, receiptSet, archiveAttestations);
    }

    /** Complete paired lifecycle fixture and v2 verification boundary. */
    public record Fixture(
            TestSuiteStabilityObservationLedgerLifecyclePageResponse v1Response,
            TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse v2Response,
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet,
            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                    archiveAttestations) {
    }
}
