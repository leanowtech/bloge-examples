package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePageRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleAttestation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Canonical identities and transition checks for signed observation-ledger lifecycle pages. */
public final class TestSuiteStabilityObservationLedgerLifecyclePageIntegrity {
    private static final String PAGE_ID_PREFIX = "stability-observation-lifecycle-page-";

    private TestSuiteStabilityObservationLedgerLifecyclePageIntegrity() {
    }

    /**
     * Recomputes the complete page fingerprint excluding its own fingerprint field.
     *
     * @param objectMapper canonical protocol mapper
     * @param page complete lifecycle page
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String pageFingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerLifecyclePage page) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(page, "page");
        return ProtocolFingerprint.of(objectMapper, new PageMaterial(
                page.schemaVersion(), page.requestFingerprint(), page.request(),
                page.scopeFingerprint(), page.startingFloor(), page.retirements(),
                page.terminalFloor(), page.currentFloor(), page.head(), page.hasMore(),
                page.observedAt()));
    }

    /**
     * Derives the deterministic response identity from exact request and page fingerprints.
     *
     * @param objectMapper canonical protocol mapper
     * @param requestFingerprint canonical request identity
     * @param pageFingerprint complete page identity
     * @return deterministic lifecycle page id
     */
    public static String lifecyclePageId(
            ObjectMapper objectMapper,
            String requestFingerprint,
            String pageFingerprint) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        String fingerprint = ProtocolFingerprint.of(objectMapper, new PageIdentity(
                TestSuiteStabilityObservationLedgerLifecyclePage.SCHEMA_VERSION,
                requestFingerprint, pageFingerprint));
        return PAGE_ID_PREFIX + fingerprint.substring("sha256:".length());
    }

    /**
     * Projects ordered compact retirement references for detached signature material.
     *
     * @param retirements complete ordered retirement records
     * @return immutable ordered retirement references
     */
    public static List<TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef>
            retirementRefs(List<TestSuiteStabilityObservationFloorRetirement> retirements) {
        List<TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef> result =
                new ArrayList<>();
        for (TestSuiteStabilityObservationFloorRetirement retirement
                : List.copyOf(retirements)) {
            result.add(new TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef(
                    retirement.evidence().retirementGeneration(),
                    retirement.evidence().retirementId(),
                    retirement.retirementFingerprint()));
        }
        return List.copyOf(result);
    }

    /**
     * Validates canonical records, exact contiguous transitions, and snapshot closure.
     *
     * @param objectMapper canonical protocol mapper
     * @param page candidate lifecycle page
     * @return whether the complete page can be trusted before cryptographic verification
     */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerLifecyclePage page) {
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
            for (TestSuiteStabilityObservationFloorRetirement retirement : page.retirements()) {
                if (!TestSuiteStabilityObservationFloorRetirementIntegrity.valid(
                        objectMapper, retirement)
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
                    && (page.hasMore()
                    || page.terminalFloor().equals(page.currentFloor()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean sameScope(TestSuiteStabilityObservationLedgerLifecyclePage page) {
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
            TestSuiteStabilityObservationLedgerFloor terminalFloor,
            TestSuiteStabilityObservationLedgerFloor currentFloor,
            TestSuiteStabilityObservationLedgerHead head,
            boolean hasMore,
            Instant observedAt) {
    }
}
