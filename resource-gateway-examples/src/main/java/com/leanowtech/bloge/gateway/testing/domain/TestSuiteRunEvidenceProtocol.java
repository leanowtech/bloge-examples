package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only boundary for independently fingerprinted suite-run evidence generations.
 *
 * <p>The common view excludes v2-only semantic facts. Fingerprinting and persistence must always
 * use the concrete generation selected by {@code schemaVersion}.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "schemaVersion", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TestSuiteRunEvidence.class,
                name = TestSuiteRunEvidence.SCHEMA_VERSION),
        @JsonSubTypes.Type(value = TestSuiteRunEvidenceV2.class,
                name = TestSuiteRunEvidenceV2.SCHEMA_VERSION),
        @JsonSubTypes.Type(value = TestSuiteRunEvidenceV3.class,
                name = TestSuiteRunEvidenceV3.SCHEMA_VERSION),
        @JsonSubTypes.Type(value = TestSuiteRunEvidenceV4.class,
                name = TestSuiteRunEvidenceV4.SCHEMA_VERSION)
})
public sealed interface TestSuiteRunEvidenceProtocol
        permits TestSuiteRunEvidence, TestSuiteRunEvidenceV2, TestSuiteRunEvidenceV3,
        TestSuiteRunEvidenceV4 {
    /** @return exact wire schema version */
    String schemaVersion();
    /** @return durable suite run id */
    String suiteRunId();
    /** @return caller idempotency key */
    String clientRequestId();
    /** @return aggregate lifecycle state */
    TestSuiteRunEvidence.Status status();
    /** @return authorized execution purpose */
    String executionPurpose();
    /** @return exact suite revision */
    TestSuiteExecutionRequest.SuiteRef suiteRef();
    /** @return exact target snapshot */
    TestSuite.Target target();
    /** @return authoritative start time */
    Instant startedAt();
    /** @return terminal time or null */
    Instant completedAt();
    /** @return ordered case outcomes */
    List<TestSuiteRunEvidence.CaseResult> caseResults();
    /** @return structural coverage verdict */
    TestSuiteRunEvidence.CoverageVerdict coverage();
    /** @return promotion eligibility verdict */
    TestSuiteRunEvidence.PromotionVerdict promotion();
    /** @return bounded diagnostics */
    List<String> diagnostics();
    /** @return bounded scope provenance */
    Map<String, Object> metadata();
}
