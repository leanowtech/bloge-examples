package com.leanowtech.bloge.gateway.integration;

import java.util.Set;
import java.util.Locale;

/** Central operation-to-purpose policy for the Tool Studio integration surface. */
public enum IntegrationOperation {
    DRAFT_EXPORT(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "CHANGE_SYNC")),
    WORKBOOK_EXPORT(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "WORKBOOK_SYNC")),
    RUN_EVIDENCE_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION")),
    SIDE_EFFECT_RECONCILIATION_READ(Set.of(
            "GOVERNANCE_EVIDENCE_INGESTION", "SIDE_EFFECT_RECONCILIATION")),
    SIDE_EFFECT_RECONCILIATION_EXECUTE(Set.of("SIDE_EFFECT_RECONCILIATION")),
    RECORDED_PAYLOAD_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "PAYLOAD_REPLAY")),
    RECORDED_REPLAY(Set.of("PAYLOAD_REPLAY")),
    PAYLOAD_RETENTION_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "PAYLOAD_RETENTION_ADMIN", "LEGAL_HOLD")),
    PAYLOAD_RETENTION_ADMIN(Set.of("PAYLOAD_RETENTION_ADMIN")),
    PAYLOAD_LEGAL_HOLD(Set.of("LEGAL_HOLD")),
    GATE_RESULT_WRITE(Set.of("GOVERNANCE_GATE_FEEDBACK")),
    GATE_RESULT_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "GOVERNANCE_GATE_FEEDBACK")),
    CHANGE_SYNC(Set.of("CHANGE_SYNC")),
    TEST_EXECUTION(Set.of("TEST_EXECUTION")),
    TEST_TARGET_READ(Set.of("TEST_EXECUTION", "TEST_FIXTURE_READ", "TEST_FIXTURE_WRITE")),
    TEST_FIXTURE_READ(Set.of("TEST_FIXTURE_READ")),
    TEST_FIXTURE_WRITE(Set.of("TEST_FIXTURE_WRITE"));

    private final Set<String> acceptedPurposes;

    IntegrationOperation(Set<String> acceptedPurposes) {
        this.acceptedPurposes = Set.copyOf(acceptedPurposes);
    }

    public boolean accepts(String purpose) {
        return acceptedPurposes.contains(purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT));
    }

    public Set<String> acceptedPurposes() {
        return acceptedPurposes;
    }
}
