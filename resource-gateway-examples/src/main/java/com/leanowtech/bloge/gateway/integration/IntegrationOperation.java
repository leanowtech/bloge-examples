package com.leanowtech.bloge.gateway.integration;

import java.util.Set;

/** Central operation-to-purpose policy for the Tool Studio integration surface. */
public enum IntegrationOperation {
    DRAFT_EXPORT(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "CHANGE_SYNC")),
    RUN_EVIDENCE_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION")),
    RECORDED_PAYLOAD_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "PAYLOAD_REPLAY")),
    RECORDED_REPLAY(Set.of("PAYLOAD_REPLAY")),
    GATE_RESULT_WRITE(Set.of("GOVERNANCE_GATE_FEEDBACK")),
    GATE_RESULT_READ(Set.of("GOVERNANCE_EVIDENCE_INGESTION", "GOVERNANCE_GATE_FEEDBACK")),
    CHANGE_SYNC(Set.of("CHANGE_SYNC"));

    private final Set<String> acceptedPurposes;

    IntegrationOperation(Set<String> acceptedPurposes) {
        this.acceptedPurposes = Set.copyOf(acceptedPurposes);
    }

    public boolean accepts(String purpose) {
        return acceptedPurposes.contains(purpose == null ? "" : purpose.trim().toUpperCase());
    }

    public Set<String> acceptedPurposes() {
        return acceptedPurposes;
    }
}
