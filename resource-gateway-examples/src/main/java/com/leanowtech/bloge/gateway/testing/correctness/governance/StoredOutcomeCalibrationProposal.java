package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

/** Integrity-addressed stored calibration proposal. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredOutcomeCalibrationProposal(
        String schemaVersion,
        String proposalFingerprint,
        OutcomeCalibrationProposal proposal
) {
    public static final String SCHEMA_VERSION = "bloge.storedOutcomeCalibrationProposal.v1";

    public StoredOutcomeCalibrationProposal {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || proposalFingerprint == null
                || !proposalFingerprint.matches("sha256:[0-9a-f]{64}")
                || proposal == null) {
            throw new IllegalArgumentException("Exact stored calibration proposal is required");
        }
    }

    public static StoredOutcomeCalibrationProposal verified(
            ObjectMapper mapper, OutcomeCalibrationProposal proposal) {
        return new StoredOutcomeCalibrationProposal(
                "", CorrectnessProtocolFingerprint.derivedFingerprint(mapper, proposal), proposal);
    }
}
