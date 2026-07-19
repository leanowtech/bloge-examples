package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationArchiveSegment;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePageRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerFloorIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerHeadIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecyclePageIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Instant;
import java.util.List;

/** Reusable canonical one-retirement lifecycle fixture for server protocol tests. */
public final class TestSuiteStabilityObservationLifecycleProtocolFixtures {
    private TestSuiteStabilityObservationLifecycleProtocolFixtures() {
    }

    /**
     * Builds one canonical lifecycle page whose retirement and observations use the supplied key.
     *
     * @param mapper canonical protocol mapper
     * @param signer shared test signing authority
     * @return complete unsigned-at-page-level fixture
     */
    public static Fixture page(ObjectMapper mapper, VisualEvidenceSigner signer) {
        var sourceAttestations = new TestSuiteStabilityAttestationService(mapper, signer);
        var observationAttestations = new TestSuiteStabilityObservationAttestationService(
                mapper, signer, sourceAttestations);
        var rangeFixture = TestSuiteStabilityCrossRetentionTrendProtocolFixtures.range(
                mapper, sourceAttestations, observationAttestations, '1', '2', '3');
        var entries = rangeFixture.entries();
        Instant retiredAt = TestSuiteStabilityCrossRetentionTrendProtocolFixtures.OBSERVED_AT;
        TestSuiteStabilityObservationLedgerFloor rollout = rolloutFloor(mapper, entries.getFirst());
        TestSuiteStabilityObservationArchiveSegment archive = archive(
                mapper, entries.subList(0, 2), entries.get(2), retiredAt);
        TestSuiteStabilityObservationFloorRetirementEvidence unsignedEvidence =
                new TestSuiteStabilityObservationFloorRetirementEvidence(
                        TestSuiteStabilityObservationFloorRetirementEvidence.SCHEMA_VERSION,
                        zeroRetirementId(), entries.getFirst().scopeFingerprint(),
                        TestSuiteStabilityProtocolFixtures.SUITE_REF, 1, rollout,
                        rangeFixture.range().head(), archive, entries.get(2).appendedAt(),
                        1, 2, TestSuiteStabilityProtocolFixtures.fingerprint('a'),
                        TestSuiteStabilityObservationFloorRetirementEvidence.Reason
                                .RETENTION_POLICY,
                        retiredAt);
        String retirementId = TestSuiteStabilityObservationFloorRetirementIntegrity.retirementId(
                mapper, unsignedEvidence);
        TestSuiteStabilityObservationFloorRetirementEvidence evidence =
                new TestSuiteStabilityObservationFloorRetirementEvidence(
                        unsignedEvidence.schemaVersion(), retirementId,
                        unsignedEvidence.scopeFingerprint(), unsignedEvidence.suiteRef(),
                        unsignedEvidence.retirementGeneration(), unsignedEvidence.previousFloor(),
                        unsignedEvidence.pinnedHead(), unsignedEvidence.archiveSegment(),
                        unsignedEvidence.cutoffExclusive(),
                        unsignedEvidence.minimumRetainedEntries(),
                        unsignedEvidence.maximumRetiredEntries(),
                        unsignedEvidence.retentionPolicyFingerprint(), unsignedEvidence.reason(),
                        unsignedEvidence.retiredAt());
        var retirementAttestations =
                new TestSuiteStabilityObservationFloorRetirementAttestationService(mapper, signer);
        var sealed = retirementAttestations.seal(evidence);
        if (!sealed.verified()) {
            throw new IllegalStateException("Lifecycle fixture retirement could not be signed");
        }
        String evidenceFingerprint = ProtocolFingerprint.of(mapper, evidence);
        String attestationFingerprint = ProtocolFingerprint.of(mapper, sealed.attestation());
        TestSuiteStabilityObservationFloorRetirement unsignedRetirement =
                new TestSuiteStabilityObservationFloorRetirement(
                        evidenceFingerprint, evidence, attestationFingerprint,
                        sealed.attestation(), TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationFloorRetirement retirement =
                new TestSuiteStabilityObservationFloorRetirement(
                        unsignedRetirement.evidenceFingerprint(), unsignedRetirement.evidence(),
                        unsignedRetirement.attestationFingerprint(),
                        unsignedRetirement.attestation(),
                        TestSuiteStabilityObservationFloorRetirementIntegrity
                                .retirementFingerprint(mapper, unsignedRetirement));
        TestSuiteStabilityObservationLedgerFloor currentFloor =
                TestSuiteStabilityObservationFloorRetirementIntegrity.successorFloor(
                        mapper, retirement);
        TestSuiteStabilityObservationLedgerHead currentHead = adjustedHead(
                mapper, rangeFixture.range().head(), currentFloor);
        var request = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 10, "", "");
        String requestFingerprint = ProtocolFingerprint.of(mapper, request);
        TestSuiteStabilityObservationLedgerLifecyclePage unsignedPage =
                new TestSuiteStabilityObservationLedgerLifecyclePage(
                        TestSuiteStabilityObservationLedgerLifecyclePage.SCHEMA_VERSION,
                        requestFingerprint, request, rollout.scopeFingerprint(), rollout,
                        List.of(retirement), currentFloor, currentFloor, currentHead, false,
                        retiredAt.plusSeconds(1),
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerLifecyclePage page =
                new TestSuiteStabilityObservationLedgerLifecyclePage(
                        unsignedPage.schemaVersion(), unsignedPage.requestFingerprint(),
                        unsignedPage.request(), unsignedPage.scopeFingerprint(),
                        unsignedPage.startingFloor(), unsignedPage.retirements(),
                        unsignedPage.terminalFloor(), unsignedPage.currentFloor(),
                        unsignedPage.head(), unsignedPage.hasMore(), unsignedPage.observedAt(),
                        TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.pageFingerprint(
                                mapper, unsignedPage));
        return new Fixture(page, retirementAttestations);
    }

    private static TestSuiteStabilityObservationLedgerFloor rolloutFloor(
            ObjectMapper mapper,
            com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry
                    first) {
        TestSuiteStabilityObservationLedgerFloor unsigned =
                new TestSuiteStabilityObservationLedgerFloor(
                        TestSuiteStabilityObservationLedgerFloor.SCHEMA_VERSION,
                        first.scopeFingerprint(), TestSuiteStabilityProtocolFixtures.SUITE_REF,
                        1, "", "", first.observation().evidence().observationId(),
                        first.entryFingerprint(), first.appendedAt(), 0, "", "",
                        first.appendedAt(), TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        return new TestSuiteStabilityObservationLedgerFloor(
                unsigned.schemaVersion(), unsigned.scopeFingerprint(), unsigned.suiteRef(),
                unsigned.floorSequence(), unsigned.previousObservationId(),
                unsigned.previousEntryFingerprint(), unsigned.floorObservationId(),
                unsigned.floorEntryFingerprint(), unsigned.coverageFrom(),
                unsigned.retirementGeneration(), unsigned.latestRetirementId(),
                unsigned.latestRetirementFingerprint(), unsigned.updatedAt(),
                TestSuiteStabilityObservationLedgerFloorIntegrity.fingerprint(mapper, unsigned));
    }

    private static TestSuiteStabilityObservationArchiveSegment archive(
            ObjectMapper mapper,
            List<com.leanowtech.bloge.gateway.testing.api
                    .TestSuiteStabilityObservationLedgerEntry> retired,
            com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry
                    successor,
            Instant retiredAt) {
        String scope = retired.getFirst().scopeFingerprint();
        String archiveId = TestSuiteStabilityObservationFloorRetirementIntegrity.archiveId(
                mapper, scope, TestSuiteStabilityProtocolFixtures.SUITE_REF,
                1, "", "", retired, successor, retiredAt);
        TestSuiteStabilityObservationArchiveSegment unsigned =
                new TestSuiteStabilityObservationArchiveSegment(
                        TestSuiteStabilityObservationArchiveSegment.SCHEMA_VERSION,
                        archiveId, scope, TestSuiteStabilityProtocolFixtures.SUITE_REF,
                        1, "", "", retired, successor, retiredAt,
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        return new TestSuiteStabilityObservationArchiveSegment(
                unsigned.schemaVersion(), unsigned.segmentId(), unsigned.scopeFingerprint(),
                unsigned.suiteRef(), unsigned.retirementGeneration(),
                unsigned.previousObservationId(), unsigned.previousEntryFingerprint(),
                unsigned.retiredEntries(), unsigned.successorEntry(), unsigned.archivedAt(),
                TestSuiteStabilityObservationFloorRetirementIntegrity.archiveFingerprint(
                        mapper, unsigned));
    }

    private static TestSuiteStabilityObservationLedgerHead adjustedHead(
            ObjectMapper mapper,
            TestSuiteStabilityObservationLedgerHead head,
            TestSuiteStabilityObservationLedgerFloor floor) {
        TestSuiteStabilityObservationLedgerHead unsigned =
                new TestSuiteStabilityObservationLedgerHead(
                        head.schemaVersion(), head.scopeFingerprint(), head.suiteRef(),
                        floor.coverageFrom(), head.latestSequence(), head.latestObservationId(),
                        head.latestEntryFingerprint(), head.updatedAt(),
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        return new TestSuiteStabilityObservationLedgerHead(
                unsigned.schemaVersion(), unsigned.scopeFingerprint(), unsigned.suiteRef(),
                unsigned.coverageFrom(), unsigned.latestSequence(),
                unsigned.latestObservationId(), unsigned.latestEntryFingerprint(),
                unsigned.updatedAt(),
                TestSuiteStabilityObservationLedgerHeadIntegrity.fingerprint(mapper, unsigned));
    }

    private static String zeroRetirementId() {
        return "stability-observation-retirement-" + "0".repeat(64);
    }

    /** Complete canonical page plus the authority needed to verify its nested retirement. */
    public record Fixture(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            TestSuiteStabilityObservationFloorRetirementAttestationService
                    retirementAttestations) {
    }
}
