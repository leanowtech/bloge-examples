package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence
        .TestSuiteStabilityObservationExternalArchiveIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence
        .TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence
        .TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authorized v2 assembler exporting exact external archive proof for each lifecycle transition.
 *
 * <p>The established v1 service remains the suite authorization, classification, snapshot,
 * retirement-signature, and transition-verification boundary. This assembler then resolves the
 * immutable receipt set for each already verified retirement, requires exact pairwise canonical
 * closure, and signs a new v2 page. It does not choose external archive trust for consumers;
 * independent clients must verify receipt signatures against policy pinned outside Gateway.</p>
 */
public final class TestSuiteStabilityObservationLedgerLifecycleArchivePageService {
    private final TestSuiteStabilityObservationLedgerLifecyclePageService lifecyclePages;
    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
            archiveAttestations;

    /**
     * @param lifecyclePages authorized and cryptographically verified v1 snapshot reader
     * @param repository immutable external receipt-set reader
     * @param objectMapper canonical protocol mapper
     * @param archiveAttestations v2 page-closing signature boundary
     */
    public TestSuiteStabilityObservationLedgerLifecycleArchivePageService(
            TestSuiteStabilityObservationLedgerLifecyclePageService lifecyclePages,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                    archiveAttestations) {
        this.lifecyclePages = Objects.requireNonNull(lifecyclePages, "lifecyclePages");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.archiveAttestations = Objects.requireNonNull(
                archiveAttestations, "archiveAttestations");
    }

    /**
     * Produces one independently verifiable receipt-aware lifecycle page.
     *
     * @param suiteId path-bound suite identity
     * @param request exact bounded generation cursor and continuation pins
     * @param identity verified test-runtime identity
     * @return signed v2 lifecycle page carrying exact external receipt sets
     */
    public TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse read(
            String suiteId,
            TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
            IntegrationRequestContext identity) {
        TestSuiteStabilityObservationLedgerLifecyclePageResponse verified =
                lifecyclePages.read(suiteId, request, identity);
        TestSuiteStabilityObservationLedgerLifecyclePage page = verified.page();
        List<TestSuiteStabilityObservationExternalArchiveReceiptSet> receiptSets =
                new ArrayList<>();
        try {
            for (TestSuiteStabilityObservationFloorRetirement retirement : page.retirements()) {
                TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet = repository
                        .findObservationExternalArchiveReceiptSet(
                                retirement.evidence().retirementId())
                        .orElseThrow(() -> conflict(identity,
                                "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PROOF_MISSING",
                                "A floor retirement has no committed external archive proof."));
                if (!retirement.equals(receiptSet.request().retirement())
                        || !TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                        objectMapper, receiptSet)) {
                    throw conflict(identity,
                            "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PROOF_INVALID",
                            "An external archive receipt set contradicts its retirement.");
                }
                receiptSets.add(receiptSet);
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity,
                    "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PROOF_UNAVAILABLE",
                    "External archive proof could not be read safely.");
        }

        TestSuiteStabilityObservationLedgerLifecycleArchivePage archivePage;
        try {
            TestSuiteStabilityObservationLedgerLifecycleArchivePage unsigned =
                    new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                            TestSuiteStabilityObservationLedgerLifecycleArchivePage
                                    .SCHEMA_VERSION,
                            page.requestFingerprint(), page.request(), page.scopeFingerprint(),
                            page.startingFloor(), page.retirements(), receiptSets,
                            page.terminalFloor(), page.currentFloor(), page.head(),
                            page.hasMore(), page.observedAt(), zeroFingerprint());
            archivePage = new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                    unsigned.schemaVersion(), unsigned.requestFingerprint(), unsigned.request(),
                    unsigned.scopeFingerprint(), unsigned.startingFloor(), unsigned.retirements(),
                    unsigned.externalArchiveReceiptSets(), unsigned.terminalFloor(),
                    unsigned.currentFloor(), unsigned.head(), unsigned.hasMore(),
                    unsigned.observedAt(),
                    TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                            .pageFingerprint(objectMapper, unsigned));
        } catch (RuntimeException invalid) {
            throw conflict(identity, "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PAGE_INVALID",
                    "The receipt-aware lifecycle page failed whole-record verification.");
        }
        if (!TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.valid(
                objectMapper, archivePage)) {
            throw conflict(identity, "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_PAGE_INVALID",
                    "The receipt-aware lifecycle page failed whole-record verification.");
        }
        TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService.SealResult sealed =
                archiveAttestations.seal(archivePage);
        if (!sealed.verified()) {
            throw unavailable(identity,
                    "RG.TEST.STABILITY_LIFECYCLE_ARCHIVE_ATTESTATION_UNAVAILABLE",
                    "Signed receipt-aware lifecycle evidence could not be produced.");
        }
        String pageId = TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                .lifecyclePageId(objectMapper, archivePage.requestFingerprint(),
                        archivePage.pageFingerprint());
        return new TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse(
                TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse.SCHEMA_VERSION,
                pageId, archivePage.pageFingerprint(), archivePage, sealed.attestation());
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }
}
