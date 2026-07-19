package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecycleArchivePage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePageRequest;
import com.leanowtech.bloge.gateway.testing.domain
        .TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Canonical identities and proof-paired transitions for receipt-aware lifecycle pages. */
public final class TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity {
    private static final String PAGE_ID_PREFIX = "stability-observation-lifecycle-page-";

    private TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity() {
    }

    /**
     * Recomputes the complete v2 page fingerprint excluding its self field.
     *
     * @param objectMapper canonical protocol mapper
     * @param page complete receipt-aware lifecycle page
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String pageFingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(page, "page");
        return ProtocolFingerprint.of(objectMapper, new PageMaterial(
                page.schemaVersion(), page.requestFingerprint(), page.request(),
                page.scopeFingerprint(), page.startingFloor(), page.retirements(),
                page.externalArchiveReceiptSets(), page.terminalFloor(), page.currentFloor(),
                page.head(), page.hasMore(), page.observedAt()));
    }

    /**
     * Derives the v2 response identity from exact request and page fingerprints.
     *
     * @param objectMapper canonical protocol mapper
     * @param requestFingerprint canonical cursor request identity
     * @param pageFingerprint complete v2 page identity
     * @return deterministic lifecycle page id
     */
    public static String lifecyclePageId(
            ObjectMapper objectMapper,
            String requestFingerprint,
            String pageFingerprint) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        String fingerprint = ProtocolFingerprint.of(objectMapper, new PageIdentity(
                TestSuiteStabilityObservationLedgerLifecycleArchivePage.SCHEMA_VERSION,
                requestFingerprint, pageFingerprint));
        return PAGE_ID_PREFIX + fingerprint.substring("sha256:".length());
    }

    /**
     * Projects ordered retirement and receipt-set references for detached v2 signature material.
     *
     * @param retirements complete ordered retirements
     * @param receiptSets exact ordered external proof sets
     * @return immutable pairwise archive references
     */
    public static List<
            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.ArchiveRef>
            archiveRefs(
                    List<TestSuiteStabilityObservationFloorRetirement> retirements,
                    List<TestSuiteStabilityObservationExternalArchiveReceiptSet> receiptSets) {
        List<TestSuiteStabilityObservationFloorRetirement> exactRetirements =
                List.copyOf(Objects.requireNonNull(retirements, "retirements"));
        List<TestSuiteStabilityObservationExternalArchiveReceiptSet> exactReceiptSets =
                List.copyOf(Objects.requireNonNull(receiptSets, "receiptSets"));
        if (exactRetirements.size() != exactReceiptSets.size()) {
            throw new IllegalArgumentException(
                    "Lifecycle retirements and receipt sets must have equal size");
        }
        List<TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.ArchiveRef> result =
                new ArrayList<>();
        for (int index = 0; index < exactRetirements.size(); index++) {
            TestSuiteStabilityObservationFloorRetirement retirement =
                    exactRetirements.get(index);
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                    exactReceiptSets.get(index);
            result.add(new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation
                    .ArchiveRef(retirement.evidence().retirementGeneration(),
                    retirement.evidence().retirementId(), retirement.retirementFingerprint(),
                    receiptSet.receiptSetId(), receiptSet.receiptSetFingerprint(),
                    receiptSet.requiredCopies(), receiptSet.receipts().size()));
        }
        return List.copyOf(result);
    }

    /**
     * Validates canonical records, pairwise external proof, transitions, and snapshot closure.
     *
     * <p>This method validates receipt structure and canonical identities but deliberately does not
     * resolve external authority keys. Independent consumers verify those signatures against a
     * trust policy pinned outside the Gateway response.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param page candidate receipt-aware lifecycle page
     * @return whether all non-cryptographic v2 closure checks pass
     */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page) {
        try {
            if (page == null
                    || !page.requestFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, page.request()))
                    || !page.pageFingerprint().equals(pageFingerprint(objectMapper, page))
                    || !page.request().suiteRef().equals(page.startingFloor().suiteRef())
                    || !page.request().suiteRef().equals(page.terminalFloor().suiteRef())
                    || !page.request().suiteRef().equals(page.currentFloor().suiteRef())
                    || !page.request().suiteRef().equals(page.head().suiteRef())
                    || !sameScope(page)
                    || (!page.request().expectedCurrentFloorFingerprint().isBlank()
                    && !page.request().expectedCurrentFloorFingerprint().equals(
                    page.currentFloor().floorFingerprint()))
                    || (!page.request().expectedHeadFingerprint().isBlank()
                    && !page.request().expectedHeadFingerprint().equals(
                    page.head().headFingerprint()))
                    || page.request().afterRetirementGeneration()
                    != page.startingFloor().retirementGeneration()
                    || page.retirements().size() > page.request().maximumRetirements()
                    || page.retirements().size() != page.externalArchiveReceiptSets().size()
                    || !TestSuiteStabilityObservationLedgerFloorIntegrity.valid(
                    objectMapper, page.startingFloor())
                    || !TestSuiteStabilityObservationLedgerFloorIntegrity.valid(
                    objectMapper, page.terminalFloor())
                    || !TestSuiteStabilityObservationLedgerFloorIntegrity.valid(
                    objectMapper, page.currentFloor())
                    || !TestSuiteStabilityObservationLedgerHeadIntegrity.valid(
                    objectMapper, page.head())
                    || !page.head().coverageFrom().equals(page.currentFloor().coverageFrom())
                    || page.head().latestSequence() < page.currentFloor().floorSequence()
                    || page.observedAt().isBefore(page.currentFloor().updatedAt())
                    || page.observedAt().isBefore(page.head().updatedAt())) {
                return false;
            }
            TestSuiteStabilityObservationLedgerFloor cursor = page.startingFloor();
            long expectedGeneration = cursor.retirementGeneration() + 1;
            for (int index = 0; index < page.retirements().size(); index++) {
                TestSuiteStabilityObservationFloorRetirement retirement =
                        page.retirements().get(index);
                TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                        page.externalArchiveReceiptSets().get(index);
                if (!TestSuiteStabilityObservationFloorRetirementIntegrity.valid(
                        objectMapper, retirement)
                        || !TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                        objectMapper, receiptSet)
                        || !retirement.equals(receiptSet.request().retirement())
                        || receiptSet.confirmedAt().isAfter(page.observedAt())
                        || retirement.evidence().retirementGeneration() != expectedGeneration
                        || !retirement.evidence().previousFloor().equals(cursor)
                        || !retirement.evidence().scopeFingerprint().equals(
                        page.scopeFingerprint())
                        || !retirement.evidence().suiteRef().equals(page.request().suiteRef())) {
                    return false;
                }
                cursor = TestSuiteStabilityObservationFloorRetirementIntegrity.successorFloor(
                        objectMapper, retirement);
                expectedGeneration++;
            }
            if (!cursor.equals(page.terminalFloor())) {
                return false;
            }
            boolean moreByGeneration = page.terminalFloor().retirementGeneration()
                    < page.currentFloor().retirementGeneration();
            return page.hasMore() == moreByGeneration
                    && (page.hasMore() || page.terminalFloor().equals(page.currentFloor()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean sameScope(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page) {
        return page.scopeFingerprint().equals(page.startingFloor().scopeFingerprint())
                && page.scopeFingerprint().equals(page.terminalFloor().scopeFingerprint())
                && page.scopeFingerprint().equals(page.currentFloor().scopeFingerprint())
                && page.scopeFingerprint().equals(page.head().scopeFingerprint());
    }

    private record PageIdentity(
            String schemaVersion,
            String requestFingerprint,
            String pageFingerprint) {
    }

    private record PageMaterial(
            String schemaVersion,
            String requestFingerprint,
            TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
            String scopeFingerprint,
            TestSuiteStabilityObservationLedgerFloor startingFloor,
            List<TestSuiteStabilityObservationFloorRetirement> retirements,
            List<TestSuiteStabilityObservationExternalArchiveReceiptSet>
                    externalArchiveReceiptSets,
            TestSuiteStabilityObservationLedgerFloor terminalFloor,
            TestSuiteStabilityObservationLedgerFloor currentFloor,
            TestSuiteStabilityObservationLedgerHead head,
            boolean hasMore,
            Instant observedAt) {
    }
}
