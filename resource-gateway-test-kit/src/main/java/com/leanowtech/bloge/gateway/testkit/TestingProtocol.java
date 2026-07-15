package com.leanowtech.bloge.gateway.testkit;

/** Public wire-version constants shared by the test-kit builders and response guards. */
public final class TestingProtocol {

    /** Single execution request wire version. */
    public static final String TEST_EXECUTION_REQUEST_V1 = "bloge.testExecutionRequest.v1";
    /** Single execution response wire version. */
    public static final String TEST_EXECUTION_RESPONSE_V1 = "bloge.testExecutionResponse.v1";
    /** Batch execution request wire version. */
    public static final String TEST_EXECUTION_BATCH_REQUEST_V1 = "bloge.testExecutionBatchRequest.v1";
    /** Batch execution response wire version. */
    public static final String TEST_EXECUTION_BATCH_RESPONSE_V1 = "bloge.testExecutionBatchResponse.v1";
    /** Fixture bundle wire version. */
    public static final String FIXTURE_BUNDLE_V1 = "bloge.fixtureBundle.v1";
    /** Fixture rule wire version. */
    public static final String FIXTURE_RULE_V1 = "bloge.fixtureRule.v1";
    /** Fixture registration request wire version. */
    public static final String FIXTURE_REGISTRATION_REQUEST_V1 =
            "bloge.fixtureBundleRegistrationRequest.v1";
    /** Stored fixture response wire version. */
    public static final String STORED_FIXTURE_BUNDLE_V1 = "bloge.storedFixtureBundle.v1";
    /** Graph target descriptor wire version. */
    public static final String GRAPH_TARGET_DESCRIPTOR_V1 = "bloge.testGraphTargetDescriptor.v1";
    /** Operator execution request wire version. */
    public static final String OPERATOR_EXECUTION_REQUEST_V1 = "bloge.testOperatorExecutionRequest.v1";
    /** Historical operator target descriptor without a formal composability manifest. */
    @Deprecated(forRemoval = false)
    public static final String OPERATOR_TARGET_DESCRIPTOR_V1 = "bloge.testOperatorTargetDescriptor.v1";
    /** Current operator target descriptor with fail-closed composability facts. */
    public static final String OPERATOR_TARGET_DESCRIPTOR_V2 = "bloge.testOperatorTargetDescriptor.v2";
    /** Test-run evidence wire version. */
    public static final String TEST_RUN_EVIDENCE_V1 = "bloge.testRunEvidence.v1";
    /** Immutable test-suite wire version. */
    public static final String TEST_SUITE_V1 = "bloge.testSuite.v1";
    /** Test-suite registration request wire version. */
    public static final String TEST_SUITE_REGISTRATION_REQUEST_V1 =
            "bloge.testSuiteRegistrationRequest.v1";
    /** Stored immutable test-suite response wire version. */
    public static final String STORED_TEST_SUITE_V1 = "bloge.storedTestSuite.v1";
    /** Exact immutable suite execution request wire version. */
    public static final String TEST_SUITE_EXECUTION_REQUEST_V1 =
            "bloge.testSuiteExecutionRequest.v1";
    /** Immutable suite execution response wire version. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V1 =
            "bloge.testSuiteExecutionResponse.v1";
    /** Aggregate immutable suite-run evidence wire version. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V1 = "bloge.testSuiteRunEvidence.v1";

    /** Classpath location of the authoritative JSON Schema packaged in the test-kit JAR. */
    public static final String SCHEMA_RESOURCE =
            "/schemas/resource-gateway-testing/testing-control-plane-v1.schema.json";

    private TestingProtocol() {
    }
}
