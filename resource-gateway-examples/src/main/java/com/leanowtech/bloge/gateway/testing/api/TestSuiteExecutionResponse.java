package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;

/**
 * Public response for one idempotent immutable suite execution.
 *
 * @param schemaVersion suite-execution response protocol version
 * @param suiteRunId persistent aggregate run identifier
 * @param evidenceFingerprint canonical terminal evidence fingerprint; blank while still running
 * @param evidence aggregate evidence without duplicated child payloads
 * @param attestation signed checkpoint or terminal aggregate closure
 */
public record TestSuiteExecutionResponse(
        String schemaVersion,
        String suiteRunId,
        String evidenceFingerprint,
        TestSuiteRunEvidenceProtocol evidence,
        TestSuiteRunAttestation attestation
) {
    /** Historical response protocol without a suite-run attestation. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteExecutionResponse.v1";
    /** Current suite-execution response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteExecutionResponse.v2";
    /** Suite-execution response carrying semantic aggregate evidence v2. */
    public static final String SCHEMA_VERSION_V3 = "bloge.testSuiteExecutionResponse.v3";
    /** Suite-execution response carrying schema-admission aggregate evidence v3. */
    public static final String SCHEMA_VERSION_V4 = "bloge.testSuiteExecutionResponse.v4";
    /** Suite-execution response carrying bounded-property aggregate evidence v4. */
    public static final String SCHEMA_VERSION_V5 = "bloge.testSuiteExecutionResponse.v5";

    /** Applies current protocol defaults to service-created responses. */
    public TestSuiteExecutionResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? versionFor(evidence)
                : schemaVersion.trim();
        suiteRunId = suiteRunId == null ? "" : suiteRunId.trim();
        evidenceFingerprint = evidenceFingerprint == null ? "" : evidenceFingerprint.trim();
        attestation = attestation == null ? TestSuiteRunAttestation.unsigned() : attestation;
        boolean v1 = SCHEMA_VERSION_V1.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidence structural
                && TestSuiteRunEvidence.SCHEMA_VERSION.equals(structural.schemaVersion())
                && attestation.signatureStatus() == TestSuiteRunAttestation.SignatureStatus.UNSIGNED;
        boolean v2 = SCHEMA_VERSION.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidence structural
                && TestSuiteRunEvidence.SCHEMA_VERSION.equals(structural.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION.equals(attestation.schemaVersion());
        boolean v3 = SCHEMA_VERSION_V3.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidenceV2 semantic
                && TestSuiteRunEvidenceV2.SCHEMA_VERSION.equals(semantic.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION_V2.equals(attestation.schemaVersion());
        boolean v4 = SCHEMA_VERSION_V4.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidenceV3 admission
                && TestSuiteRunEvidenceV3.SCHEMA_VERSION.equals(admission.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION_V3.equals(attestation.schemaVersion())
                && attestation.childEvidenceRefs().isEmpty();
        boolean v5 = SCHEMA_VERSION_V5.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidenceV4 property
                && TestSuiteRunEvidenceV4.SCHEMA_VERSION.equals(property.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION_V4.equals(attestation.schemaVersion());
        if (!v1 && !v2 && !v3 && !v4 && !v5) {
            throw new IllegalArgumentException(
                    "Suite execution response, evidence, and attestation generations must match");
        }
    }

    /** Creates a migration-compatible v1 response without an attestation. */
    public TestSuiteExecutionResponse(String schemaVersion, String suiteRunId,
                                      String evidenceFingerprint, TestSuiteRunEvidence evidence) {
        this(schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION_V1 : schemaVersion,
                suiteRunId, evidenceFingerprint, evidence, TestSuiteRunAttestation.unsigned());
    }

    private static String versionFor(TestSuiteRunEvidenceProtocol evidence) {
        if (evidence instanceof TestSuiteRunEvidenceV4) {
            return SCHEMA_VERSION_V5;
        }
        if (evidence instanceof TestSuiteRunEvidenceV3) {
            return SCHEMA_VERSION_V4;
        }
        return evidence instanceof TestSuiteRunEvidenceV2 ? SCHEMA_VERSION_V3 : SCHEMA_VERSION;
    }
}
