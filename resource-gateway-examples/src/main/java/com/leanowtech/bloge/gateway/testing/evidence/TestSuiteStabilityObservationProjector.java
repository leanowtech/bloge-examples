package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.CaseSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.RunObservation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Canonical payload-free projection shared by retained and cross-retention trend paths.
 *
 * <p>Keeping this derivation in one pure component prevents the compact ledger and retained-window
 * evaluator from disagreeing about outcome, fixture, plan, or execution-regime identities.</p>
 */
public final class TestSuiteStabilityObservationProjector {
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper canonical protocol mapper
     */
    public TestSuiteStabilityObservationProjector(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Projects one already verified terminal stability record.
     *
     * @param record exact signed terminal source
     * @return deterministic payload-free source observation
     */
    public RunObservation project(TestSuiteStabilityRunRecord record) {
        Objects.requireNonNull(record, "record");
        TestSuiteStabilityEvidence evidence = Objects.requireNonNull(
                record.evidence(), "record.evidence");
        List<CaseSnapshot> cases = evidence.caseResults().stream()
                .map(this::caseSnapshot)
                .sorted(Comparator.comparing(CaseSnapshot::caseId))
                .toList();
        String regimeFingerprint = ProtocolFingerprint.of(objectMapper,
                new RegimeMaterial(evidence.suiteRef().fingerprint(),
                        evidence.target().fingerprint(), cases.stream()
                        .map(value -> new CaseRegime(value.caseId(),
                                value.fixtureSetFingerprint(), value.planSetFingerprint()))
                        .toList()));
        return new RunObservation(
                record.stabilityRunId(), record.evidenceFingerprint(),
                ProtocolFingerprint.of(objectMapper, record.attestation()),
                evidence.schemaVersion(), evidence.target().fingerprint(), evidence.status(),
                evidence.promotion().status(), evidence.quarantine().status(),
                evidence.statisticalAssessment() == null ? null
                        : evidence.statisticalAssessment().status(),
                regimeFingerprint, cases, evidence.startedAt(), evidence.completedAt(),
                record.createdAt());
    }

    private CaseSnapshot caseSnapshot(TestSuiteStabilityEvidence.CaseStabilityResult value) {
        List<String> outcomes = value.observations().stream()
                .filter(observation -> observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::outcomeIdentity)
                .distinct().sorted().toList();
        List<String> fixtures = value.observations().stream()
                .filter(observation -> observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::fixtureBundleFingerprint)
                .distinct().sorted().toList();
        List<String> plans = value.observations().stream()
                .filter(observation -> observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::planFingerprint)
                .distinct().sorted().toList();
        return new CaseSnapshot(value.caseId(), value.status(),
                ProtocolFingerprint.of(objectMapper, outcomes),
                ProtocolFingerprint.of(objectMapper, fixtures),
                ProtocolFingerprint.of(objectMapper, plans));
    }

    private record RegimeMaterial(
            String suiteFingerprint,
            String targetFingerprint,
            List<CaseRegime> cases) {
    }

    private record CaseRegime(
            String caseId,
            String fixtureSetFingerprint,
            String planSetFingerprint) {
    }
}
