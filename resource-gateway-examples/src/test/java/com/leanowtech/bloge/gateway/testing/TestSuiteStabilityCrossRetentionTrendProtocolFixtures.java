package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures.CaseMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerRange;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerEntryIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerHeadIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerRangeIntegrity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared signed compact-observation ledger fixtures for cross-retention protocol tests. */
public final class TestSuiteStabilityCrossRetentionTrendProtocolFixtures {
    /** Stable fixture time after every source execution. */
    public static final Instant OBSERVED_AT = Instant.parse("2026-07-19T00:10:00Z");

    private TestSuiteStabilityCrossRetentionTrendProtocolFixtures() {
    }

    /**
     * Creates one complete rollout-floor range containing the requested signed sources.
     *
     * @param mapper canonical mapper
     * @param sourceAttestations source stability signer
     * @param observationAttestations compact observation signer
     * @param identities hexadecimal source identities in ledger order
     * @return exact range plus source records and observations
     */
    public static Fixture range(
            ObjectMapper mapper,
            TestSuiteStabilityAttestationService sourceAttestations,
            TestSuiteStabilityObservationAttestationService observationAttestations,
            char... identities) {
        if (identities == null || identities.length == 0 || identities.length > 100) {
            throw new IllegalArgumentException("One to one hundred fixture identities required");
        }
        List<TestSuiteStabilityRunRecord> sources = new ArrayList<>();
        List<TestSuiteStabilityObservationLedgerEntry> entries = new ArrayList<>();
        String scopeFingerprint = ProtocolFingerprint.of(mapper, new ScopeIdentity(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF));
        String predecessor = "";
        for (int index = 0; index < identities.length; index++) {
            char identity = identities[index];
            Instant createdAt = Instant.parse("2026-07-19T00:00:00Z")
                    .plusSeconds(index * 60L);
            TestSuiteStabilityRunRecord source =
                    TestSuiteStabilityTrendProtocolFixtures.record(
                            mapper, sourceAttestations, identity, createdAt,
                            OBSERVED_AT.plusSeconds(3_600),
                            TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                            CaseMode.STABLE, CaseMode.STABLE, '1', '2');
            TestSuiteStabilityObservation observation = observationAttestations.seal(source)
                    .observation();
            Instant appendedAt = createdAt.plusSeconds(30);
            long sequence = index + 1L;
            TestSuiteStabilityObservationLedgerEntry unsignedEntry =
                    new TestSuiteStabilityObservationLedgerEntry(
                            TestSuiteStabilityObservationLedgerEntry.SCHEMA_VERSION,
                            scopeFingerprint, sequence, predecessor, observation,
                            appendedAt, TestSuiteStabilityProtocolFixtures.fingerprint('0'));
            TestSuiteStabilityObservationLedgerEntry entry =
                    new TestSuiteStabilityObservationLedgerEntry(
                            unsignedEntry.schemaVersion(), unsignedEntry.scopeFingerprint(),
                            unsignedEntry.sequence(), unsignedEntry.previousObservationId(),
                            unsignedEntry.observation(), unsignedEntry.appendedAt(),
                            TestSuiteStabilityObservationLedgerEntryIntegrity.fingerprint(
                                    mapper, unsignedEntry));
            sources.add(source);
            entries.add(entry);
            predecessor = observation.evidence().observationId();
        }
        TestSuiteStabilityObservationLedgerEntry first = entries.getFirst();
        TestSuiteStabilityObservationLedgerEntry last = entries.getLast();
        Instant coverageFrom = first.appendedAt();
        TestSuiteStabilityObservationLedgerHead unsignedHead =
                new TestSuiteStabilityObservationLedgerHead(
                        TestSuiteStabilityObservationLedgerHead.SCHEMA_VERSION,
                        scopeFingerprint, TestSuiteStabilityProtocolFixtures.SUITE_REF,
                        coverageFrom, last.sequence(),
                        last.observation().evidence().observationId(),
                        last.entryFingerprint(), last.appendedAt(),
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerHead head =
                new TestSuiteStabilityObservationLedgerHead(
                        unsignedHead.schemaVersion(), unsignedHead.scopeFingerprint(),
                        unsignedHead.suiteRef(), unsignedHead.coverageFrom(),
                        unsignedHead.latestSequence(), unsignedHead.latestObservationId(),
                        unsignedHead.latestEntryFingerprint(), unsignedHead.updatedAt(),
                        TestSuiteStabilityObservationLedgerHeadIntegrity.fingerprint(
                                mapper, unsignedHead));
        TestSuiteStabilityObservationLedgerRange unsigned =
                new TestSuiteStabilityObservationLedgerRange(
                        TestSuiteStabilityObservationLedgerRange.SCHEMA_VERSION,
                        scopeFingerprint, TestSuiteStabilityProtocolFixtures.SUITE_REF,
                        1, "", "", first.observation().evidence().observationId(),
                        first.entryFingerprint(), head, 0, "", "", entries, false,
                        OBSERVED_AT,
                        TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        TestSuiteStabilityObservationLedgerRange range =
                new TestSuiteStabilityObservationLedgerRange(
                        unsigned.schemaVersion(), unsigned.scopeFingerprint(),
                        unsigned.suiteRef(), unsigned.floorSequence(),
                        unsigned.floorPreviousObservationId(),
                        unsigned.floorPreviousEntryFingerprint(),
                        unsigned.floorObservationId(), unsigned.floorEntryFingerprint(),
                        unsigned.head(), unsigned.afterSequence(),
                        unsigned.previousObservationId(), unsigned.previousEntryFingerprint(),
                        unsigned.entries(), unsigned.hasMore(), unsigned.observedAt(),
                        TestSuiteStabilityObservationLedgerRangeIntegrity.fingerprint(
                                mapper, unsigned));
        return new Fixture(List.copyOf(sources), List.copyOf(entries), range);
    }

    /** Complete cross-retention fixture material. */
    public record Fixture(
            List<TestSuiteStabilityRunRecord> sources,
            List<TestSuiteStabilityObservationLedgerEntry> entries,
            TestSuiteStabilityObservationLedgerRange range) {
        /** Freezes all fixture lists. */
        public Fixture {
            sources = List.copyOf(sources);
            entries = List.copyOf(entries);
        }
    }

    private record ScopeIdentity(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
    }

}
