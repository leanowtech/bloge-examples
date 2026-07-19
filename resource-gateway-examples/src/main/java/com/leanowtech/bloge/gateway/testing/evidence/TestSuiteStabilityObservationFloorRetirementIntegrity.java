package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationArchiveSegment;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical identities and nested integrity checks for signed floor retirement. */
public final class TestSuiteStabilityObservationFloorRetirementIntegrity {
    private static final String ARCHIVE_ID_PREFIX = "stability-observation-archive-";
    private static final String RETIREMENT_ID_PREFIX = "stability-observation-retirement-";

    private TestSuiteStabilityObservationFloorRetirementIntegrity() {
    }

    /**
     * Derives the deterministic archive id from all non-self-referential segment material.
     *
     * @param objectMapper canonical protocol mapper
     * @param scopeFingerprint exact-suite scope
     * @param suiteRef exact immutable suite revision
     * @param retirementGeneration successor floor generation
     * @param previousObservationId predecessor before the segment
     * @param previousEntryFingerprint predecessor entry identity
     * @param retiredEntries complete retired prefix
     * @param successorEntry immediate surviving successor
     * @param archivedAt producer database planning time
     * @return deterministic archive id
     */
    public static String archiveId(
            ObjectMapper objectMapper,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long retirementGeneration,
            String previousObservationId,
            String previousEntryFingerprint,
            List<TestSuiteStabilityObservationLedgerEntry> retiredEntries,
            TestSuiteStabilityObservationLedgerEntry successorEntry,
            Instant archivedAt) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        String fingerprint = ProtocolFingerprint.of(objectMapper, new ArchiveIdentity(
                TestSuiteStabilityObservationArchiveSegment.SCHEMA_VERSION,
                scopeFingerprint, suiteRef, retirementGeneration,
                previousObservationId, previousEntryFingerprint,
                refs(retiredEntries), ref(successorEntry), archivedAt));
        return ARCHIVE_ID_PREFIX + fingerprint.substring("sha256:".length());
    }

    /**
     * Recomputes an archive segment fingerprint excluding its fingerprint field.
     *
     * @param objectMapper canonical protocol mapper
     * @param segment complete archive segment
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String archiveFingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationArchiveSegment segment) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(segment, "segment");
        return ProtocolFingerprint.of(objectMapper, new ArchiveMaterial(
                segment.schemaVersion(), segment.segmentId(), segment.scopeFingerprint(),
                segment.suiteRef(), segment.retirementGeneration(),
                segment.previousObservationId(), segment.previousEntryFingerprint(),
                segment.retiredEntries(), segment.successorEntry(), segment.archivedAt()));
    }

    /**
     * Derives the deterministic retirement id excluding only the id itself.
     *
     * @param objectMapper canonical protocol mapper
     * @param evidence candidate evidence carrying a syntactically valid placeholder id
     * @return deterministic retirement id
     */
    public static String retirementId(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationFloorRetirementEvidence evidence) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(evidence, "evidence");
        String fingerprint = ProtocolFingerprint.of(objectMapper, new RetirementIdentity(
                evidence.schemaVersion(), evidence.scopeFingerprint(), evidence.suiteRef(),
                evidence.retirementGeneration(), evidence.previousFloor(), evidence.pinnedHead(),
                evidence.archiveSegment(), evidence.cutoffExclusive(),
                evidence.minimumRetainedEntries(), evidence.maximumRetiredEntries(),
                evidence.retentionPolicyFingerprint(), evidence.reason(), evidence.retiredAt()));
        return RETIREMENT_ID_PREFIX + fingerprint.substring("sha256:".length());
    }

    /**
     * Recomputes the complete retirement record identity excluding its own fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param retirement complete signed retirement
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String retirementFingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationFloorRetirement retirement) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(retirement, "retirement");
        return ProtocolFingerprint.of(objectMapper, new RetirementMaterial(
                retirement.evidenceFingerprint(), retirement.evidence(),
                retirement.attestationFingerprint(), retirement.attestation()));
    }

    /**
     * Validates every canonical fingerprint, deterministic id, and nested ledger record.
     *
     * @param objectMapper canonical protocol mapper
     * @param retirement candidate complete retirement
     * @return whether the complete local retirement closure is canonical
     */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationFloorRetirement retirement) {
        try {
            if (retirement == null) {
                return false;
            }
            TestSuiteStabilityObservationFloorRetirementEvidence evidence = retirement.evidence();
            TestSuiteStabilityObservationArchiveSegment archive = evidence.archiveSegment();
            return retirement.evidenceFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, evidence))
                    && retirement.attestationFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, retirement.attestation()))
                    && retirement.retirementFingerprint().equals(
                    retirementFingerprint(objectMapper, retirement))
                    && evidence.retirementId().equals(retirementId(objectMapper, evidence))
                    && archive.segmentId().equals(archiveId(objectMapper,
                    archive.scopeFingerprint(), archive.suiteRef(),
                    archive.retirementGeneration(), archive.previousObservationId(),
                    archive.previousEntryFingerprint(), archive.retiredEntries(),
                    archive.successorEntry(), archive.archivedAt()))
                    && archive.segmentFingerprint().equals(
                    archiveFingerprint(objectMapper, archive))
                    && TestSuiteStabilityObservationLedgerFloorIntegrity.valid(
                    objectMapper, evidence.previousFloor())
                    && TestSuiteStabilityObservationLedgerHeadIntegrity.valid(
                    objectMapper, evidence.pinnedHead())
                    && archive.retiredEntries().stream().allMatch(entry ->
                    TestSuiteStabilityObservationLedgerEntryIntegrity.valid(objectMapper, entry))
                    && TestSuiteStabilityObservationLedgerEntryIntegrity.valid(
                    objectMapper, archive.successorEntry());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static List<EntryRef> refs(List<TestSuiteStabilityObservationLedgerEntry> entries) {
        return List.copyOf(entries).stream()
                .map(TestSuiteStabilityObservationFloorRetirementIntegrity::ref).toList();
    }

    private static EntryRef ref(TestSuiteStabilityObservationLedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return new EntryRef(entry.sequence(),
                entry.observation().evidence().observationId(), entry.entryFingerprint());
    }

    private record EntryRef(long sequence, String observationId, String entryFingerprint) {
    }

    private record ArchiveIdentity(
            String schemaVersion,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long retirementGeneration,
            String previousObservationId,
            String previousEntryFingerprint,
            List<EntryRef> retiredEntries,
            EntryRef successorEntry,
            Instant archivedAt) {
    }

    private record ArchiveMaterial(
            String schemaVersion,
            String segmentId,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long retirementGeneration,
            String previousObservationId,
            String previousEntryFingerprint,
            List<TestSuiteStabilityObservationLedgerEntry> retiredEntries,
            TestSuiteStabilityObservationLedgerEntry successorEntry,
            Instant archivedAt) {
    }

    private record RetirementIdentity(
            String schemaVersion,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long retirementGeneration,
            TestSuiteStabilityObservationLedgerFloor previousFloor,
            TestSuiteStabilityObservationLedgerHead pinnedHead,
            TestSuiteStabilityObservationArchiveSegment archiveSegment,
            Instant cutoffExclusive,
            int minimumRetainedEntries,
            int maximumRetiredEntries,
            String retentionPolicyFingerprint,
            TestSuiteStabilityObservationFloorRetirementEvidence.Reason reason,
            Instant retiredAt) {
    }

    private record RetirementMaterial(
            String evidenceFingerprint,
            TestSuiteStabilityObservationFloorRetirementEvidence evidence,
            String attestationFingerprint,
            TestSuiteStabilityObservationFloorRetirementAttestation attestation) {
    }
}
