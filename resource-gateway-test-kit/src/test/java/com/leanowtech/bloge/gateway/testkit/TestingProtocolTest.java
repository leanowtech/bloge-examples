package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestingProtocolTest {

    @Test
    void packagedSchemaTracksEveryTestKitWireVersion() throws Exception {
        try (InputStream input = TestingProtocolTest.class.getResourceAsStream(
                TestingProtocol.SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            JsonNode definitions = new ObjectMapper().readTree(input).path("$defs");

            assertConstant(definitions, "testExecutionRequest", TestingProtocol.TEST_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testExecutionResponseV1",
                    TestingProtocol.TEST_EXECUTION_RESPONSE_V1);
            assertConstant(definitions, "testExecutionResponseV2",
                    TestingProtocol.TEST_EXECUTION_RESPONSE_V2);
            assertConstant(definitions, "testEvidenceIntegrity",
                    TestingProtocol.TEST_EVIDENCE_INTEGRITY_V1);
            assertThat(definitions.at("/testExecutionResponse/oneOf")).hasSize(2);
            assertConstant(definitions, "testExecutionBatchRequest",
                    TestingProtocol.TEST_EXECUTION_BATCH_REQUEST_V1);
            assertConstant(definitions, "testExecutionBatchResponse",
                    TestingProtocol.TEST_EXECUTION_BATCH_RESPONSE_V1);
            assertConstant(definitions, "fixtureBundle", TestingProtocol.FIXTURE_BUNDLE_V1);
            assertConstant(definitions, "fixtureRule", TestingProtocol.FIXTURE_RULE_V1);
            assertConstant(definitions, "fixtureBundleRegistrationRequest",
                    TestingProtocol.FIXTURE_REGISTRATION_REQUEST_V1);
            assertConstant(definitions, "storedFixtureBundle", TestingProtocol.STORED_FIXTURE_BUNDLE_V1);
            assertConstant(definitions, "testGraphTargetDescriptor",
                    TestingProtocol.GRAPH_TARGET_DESCRIPTOR_V1);
            assertConstant(definitions, "testOperatorExecutionRequest",
                    TestingProtocol.OPERATOR_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testOperatorTargetDescriptor",
                    TestingProtocol.OPERATOR_TARGET_DESCRIPTOR_V2);
            assertThat(TestingProtocol.OPERATOR_TARGET_DESCRIPTOR_V1)
                    .isEqualTo("bloge.testOperatorTargetDescriptor.v1");
            assertConstant(definitions, "testRunEvidenceV1", TestingProtocol.TEST_RUN_EVIDENCE_V1);
            assertConstant(definitions, "testRunEvidenceV2", TestingProtocol.TEST_RUN_EVIDENCE_V2);
            assertConstant(definitions, "executionServiceStateSnapshot",
                    TestingProtocol.EXECUTION_SERVICE_STATE_SNAPSHOT_V1);
            assertConstant(definitions, "durableTestWorkerAcquisitionRequest",
                    TestingProtocol.DURABLE_WORKER_ACQUISITION_REQUEST_V1);
            assertConstant(definitions, "durableTestWorkerAcquisitionResponse",
                    TestingProtocol.DURABLE_WORKER_ACQUISITION_RESPONSE_V1);
            assertConstant(definitions, "durableTestRecoveryStepRequest",
                    TestingProtocol.DURABLE_RECOVERY_STEP_REQUEST_V1);
            assertConstant(definitions, "durableTestRecoveryStepResponse",
                    TestingProtocol.DURABLE_RECOVERY_STEP_RESPONSE_V1);
            assertConstant(definitions, "durableTestRecoverySequenceRequest",
                    TestingProtocol.DURABLE_RECOVERY_SEQUENCE_REQUEST_V1);
            assertConstant(definitions, "durableTestRecoverySequenceResponse",
                    TestingProtocol.DURABLE_RECOVERY_SEQUENCE_RESPONSE_V1);
            assertThat(definitions.at("/testRunEvidence/oneOf")).hasSize(2);
            assertConstant(definitions, "testSuite", TestingProtocol.TEST_SUITE_V1);
            assertConstant(definitions, "testSuiteV2", TestingProtocol.TEST_SUITE_V2);
            assertConstant(definitions, "testSuiteV3", TestingProtocol.TEST_SUITE_V3);
            assertConstant(definitions, "testSuiteV4", TestingProtocol.TEST_SUITE_V4);
            assertConstant(definitions, "testSuiteV5", TestingProtocol.TEST_SUITE_V5);
            assertThat(definitions.at("/testSuiteProtocol/oneOf")).hasSize(5);
            assertConstant(definitions, "testPropertyCasePlan",
                    TestingProtocol.TEST_PROPERTY_CASE_PLAN_V1);
            assertConstant(definitions, "testMutationCasePlan",
                    TestingProtocol.TEST_MUTATION_CASE_PLAN_V1);
            assertConstant(definitions, "testPropertySuiteMaterializationRequest",
                    TestingProtocol.TEST_PROPERTY_SUITE_MATERIALIZATION_REQUEST_V1);
            assertConstant(definitions, "testPropertySuiteMaterialization",
                    TestingProtocol.TEST_PROPERTY_SUITE_MATERIALIZATION_V1);
            assertConstant(definitions, "testMutationSuiteMaterializationRequest",
                    TestingProtocol.TEST_MUTATION_SUITE_MATERIALIZATION_REQUEST_V1);
            assertConstant(definitions, "testMutationSuiteMaterialization",
                    TestingProtocol.TEST_MUTATION_SUITE_MATERIALIZATION_V1);
            assertConstant(definitions, "testSuiteRegistrationRequest",
                    TestingProtocol.TEST_SUITE_REGISTRATION_REQUEST_V1);
            assertConstant(definitions, "storedTestSuite", TestingProtocol.STORED_TEST_SUITE_V1);
            assertConstant(definitions, "testSuiteExecutionRequest",
                    TestingProtocol.TEST_SUITE_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testMutationSuiteExecutionRequest",
                    TestingProtocol.TEST_MUTATION_SUITE_EXECUTION_REQUEST_V1);
            assertThat(definitions.at(
                    "/testSuiteStabilityExecutionRequest/properties/schemaVersion/enum"))
                    .extracting(JsonNode::asText).containsExactly(
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V1,
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V2);
            assertThat(definitions.at(
                    "/testSuiteStabilityEvidence/properties/schemaVersion/enum"))
                    .extracting(JsonNode::asText).containsExactly(
                            TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V1,
                            TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V2,
                            TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V3);
            assertThat(definitions.at(
                    "/testSuiteStabilityAttestation/properties/schemaVersion/enum"))
                    .extracting(JsonNode::asText).containsExactly(
                            TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V1,
                            TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V2,
                            TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V3);
            assertThat(definitions.at(
                    "/testSuiteStabilityExecutionResponse/properties/schemaVersion/enum"))
                    .extracting(JsonNode::asText).containsExactly(
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V1,
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V2,
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V3);
            assertConstant(definitions, "testSuiteExecutionResponseV1",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V1);
            assertConstant(definitions, "testSuiteExecutionResponseV2",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V2);
            assertConstant(definitions, "testSuiteExecutionResponseV3",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V3);
            assertConstant(definitions, "testSuiteExecutionResponseV4",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V4);
            assertConstant(definitions, "testSuiteExecutionResponseV5",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V5);
            assertConstant(definitions, "testSuiteExecutionResponseV6",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V6);
            assertThat(definitions.at("/testSuiteExecutionResponse/oneOf")).hasSize(6);
            assertConstant(definitions, "testSuiteRunEvidence",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V1);
            assertConstant(definitions, "testSuiteRunEvidenceV2",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V2);
            assertConstant(definitions, "testSuiteRunEvidenceV3",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V3);
            assertConstant(definitions, "testSuiteRunEvidenceV4",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V4);
            assertConstant(definitions, "testSuiteRunEvidenceV5",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V5);
            assertConstant(definitions, "testSuiteRunAttestation",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1);
            assertConstant(definitions, "testSuiteRunAttestationV2",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V2);
            assertConstant(definitions, "testSuiteRunAttestationV3",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V3);
            assertConstant(definitions, "testSuiteRunAttestationV4",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V4);
            assertConstant(definitions, "testSuiteRunAttestationV5",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V5);
            assertConstant(definitions, "testSuiteEvidenceBundleV1",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1);
            assertConstant(definitions, "testSuiteEvidenceBundleV2",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V2);
            assertConstant(definitions, "testSuiteEvidenceBundleV3",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V3);
            assertConstant(definitions, "testSuiteEvidenceBundleV4",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V4);
            assertConstant(definitions, "testSuiteEvidenceBundleV5",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V5);
            assertThat(definitions.at("/testSuiteEvidenceBundle/oneOf")).hasSize(5);
            assertConstant(definitions, "evidenceVerificationKeySet",
                    TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
            assertConstant(definitions, "testSuiteCatalogMaterialization",
                    TestingProtocol.TEST_SUITE_CATALOG_MATERIALIZATION_V1);
        }
        try (InputStream input = TestingProtocolTest.class.getResourceAsStream(
                TestingProtocol.EVIDENCE_TRUST_BUNDLE_SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            JsonNode schema = new ObjectMapper().readTree(input);
            assertThat(schema.at("/properties/schemaVersion/const").asText())
                    .isEqualTo(TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1);
            assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                    .isEqualTo(TestingProtocol.EVIDENCE_KEY_SET_TRUST_PUBLICATION_V1);
            assertThat(schema.at("/properties/publications/maxItems").asInt()).isEqualTo(256);
        }
    }

    @Test
    void packagedSchemaAcceptsAdmissionEvidenceAndRejectsBusinessChildClosure() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(schemaAdmissionSuiteResponse());

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                response, "testSuiteExecutionResponse"));

        JsonNode invalid = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) invalid
                .at("/attestation/childEvidenceRefs")).addObject()
                .put("caseId", "baseline")
                .put("runId", "business-run-must-not-exist")
                .put("evidenceFingerprint", "sha256:" + "f".repeat(64));

        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                invalid, "testSuiteExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");

    }

    @Test
    void packagedSchemaAcceptsPropertyEvidenceButRejectsUnprovenGlobalMinimality()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(TestSuiteRunAssertionsTest.propertySuiteResponse());

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                response, "testSuiteExecutionResponse"));

        JsonNode invalid = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid
                .at("/evidence/propertyCoverage")).put("globallyMinimal", true);

        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                invalid, "testSuiteExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");

        JsonNode incompleteChild = response.deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) incompleteChild
                        .at("/evidence/propertyTrialResults/0/rootResult");
        root.put("status", "EVIDENCE_INCOMPLETE");
        root.putNull("evidenceStatus");

        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                incompleteChild, "testSuiteExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
    }

    @Test
    void packagedSchemaAcceptsMutationEvidenceAndRejectsFalseKillClaims() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(TestSuiteRunAssertionsTest.mutationSuiteResponse());

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                response, "testSuiteExecutionResponse"));

        JsonNode invalid = response.deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode killed =
                (com.fasterxml.jackson.databind.node.ObjectNode) invalid
                        .at("/evidence/mutantResults/0/caseResults/0");
        killed.put("evidenceStatus", "TIMED_OUT");

        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                invalid, "testSuiteExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema")
                .hasMessageNotContaining("mutant-run-killed");
    }

    @Test
    void packagedSchemaAcceptsStableEvidenceAndRejectsAmplificationPayloadAndUnsignedClaims()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(stabilityResponse());

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                response, "testSuiteStabilityExecutionResponse"));

        JsonNode amplified = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) amplified.at("/evidence"))
                .put("requestedAttempts", 21);
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                amplified, "testSuiteStabilityExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");

        JsonNode payloadLeak = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payloadLeak.at(
                "/evidence/caseResults/0/observations/0"))
                .putObject("input").put("accountNumber", "raw-business-payload");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                payloadLeak, "testSuiteStabilityExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema")
                .hasMessageNotContaining("accountNumber");

        JsonNode nestedMetadata = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) nestedMetadata.at("/evidence/metadata"))
                .putObject("pipeline").put("name", "nightly");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                nestedMetadata, "testSuiteStabilityExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");

        JsonNode unsigned = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned.at("/attestation"))
                .put("signatureStatus", "UNSIGNED")
                .put("independentlyVerifiable", false)
                .put("keyId", "")
                .put("algorithm", "")
                .put("signature", "");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                unsigned, "testSuiteStabilityExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
    }

    @Test
    void packagedSchemaRejectsStabilityRequestOutsideThreeToTwentyAttempts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("""
                {"schemaVersion":"bloge.testSuiteStabilityExecutionRequest.v1",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                   "fingerprint":"sha256:%s"},
                 "clientRequestId":"stability-ci-42","attempts":3,
                 "metadata":{"pipeline":"nightly","build":42,"protected":true}}
                """.formatted("a".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityExecutionRequest"));
        for (int attempts : new int[]{2, 21}) {
            JsonNode invalid = request.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("attempts", attempts);
            assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                    invalid, "testSuiteStabilityExecutionRequest"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authoritative schema");
        }
    }

    @Test
    void packagedSchemaAcceptsStatisticalRequestV2AndRejectsCrossFieldV3Contradictions()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("""
                {"schemaVersion":"bloge.testSuiteStabilityExecutionRequest.v2",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                   "fingerprint":"sha256:%s"},
                 "clientRequestId":"stability-ci-42","attempts":29,
                 "statisticalPolicy":{"model":"ZERO_INSTABILITY_EXACT_BINOMIAL",
                   "claimScope":"SUITE_ATTEMPT_ANY_CASE",
                   "stoppingRule":"PRECOMMITTED_FIXED_HORIZON",
                   "censoringPolicy":"FAIL_CLOSED","confidenceLevelBps":9500,
                   "maximumInstabilityRateBps":1000},"metadata":{}}
                """.formatted("a".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityExecutionRequest"));

        com.fasterxml.jackson.databind.node.ObjectNode contradictory =
                TestSuiteStabilityTestFixtures.statisticalFixture().copyResponse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) contradictory
                .at("/evidence/promotion")).put("statisticalConfidenceSatisfied", false);
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                contradictory, "testSuiteStabilityExecutionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
    }

    private static String stabilityResponse() {
        String suite = "a".repeat(64);
        String target = "b".repeat(64);
        String fixture = "c".repeat(64);
        String plan = "d".repeat(64);
        String semantic = "e".repeat(64);
        String evidence = "f".repeat(64);
        String request = "1".repeat(64);
        String runId = "stability-" + "2".repeat(64);
        return """
                {"schemaVersion":"bloge.testSuiteStabilityExecutionResponse.v1",
                 "stabilityRunId":"%8$s","evidenceFingerprint":"sha256:%6$s",
                 "evidence":{"schemaVersion":"bloge.testSuiteStabilityEvidence.v1",
                   "stabilityRunId":"%8$s","clientRequestId":"stability-ci-42",
                   "suiteRef":{"suiteId":"orders-suite","revision":7,"fingerprint":"sha256:%1$s"},
                   "target":{"kind":"GRAPH","id":"orders","fingerprint":"sha256:%2$s"},
                   "requestedAttempts":3,"status":"STABLE","attempts":[
                     {"attempt":1,"status":"VERIFIED","suiteRunId":"suite-run-1",
                      "aggregateEvidenceFingerprint":"sha256:%7$s","suiteStatus":"PASSED",
                      "startedAt":"2026-07-18T01:00:00Z","completedAt":"2026-07-18T01:00:01Z",
                      "diagnosticCode":""},
                     {"attempt":2,"status":"VERIFIED","suiteRunId":"suite-run-2",
                      "aggregateEvidenceFingerprint":"sha256:%3$s","suiteStatus":"PASSED",
                      "startedAt":"2026-07-18T01:01:00Z","completedAt":"2026-07-18T01:01:01Z",
                      "diagnosticCode":""},
                     {"attempt":3,"status":"VERIFIED","suiteRunId":"suite-run-3",
                      "aggregateEvidenceFingerprint":"sha256:%4$s","suiteStatus":"PASSED",
                      "startedAt":"2026-07-18T01:02:00Z","completedAt":"2026-07-18T01:02:01Z",
                      "diagnosticCode":""}],
                   "caseResults":[{"caseId":"golden","caseType":"GOLDEN",
                     "fixtureBundleRef":{"fixtureBundleId":"orders-fixture","revision":2,
                       "fingerprint":"sha256:%3$s"},"status":"STABLE_PASS","observations":[
                       {"attempt":1,"status":"VERIFIED","runId":"child-run-1",
                        "evidenceFingerprint":"sha256:%6$s","evidenceStatus":"PASSED",
                        "evidenceClass":"CERTIFIABLE","fixtureBundleFingerprint":"sha256:%3$s",
                        "planFingerprint":"sha256:%4$s","semanticResultFingerprint":"sha256:%5$s",
                        "diagnosticCode":""},
                       {"attempt":2,"status":"VERIFIED","runId":"child-run-2",
                        "evidenceFingerprint":"sha256:%6$s","evidenceStatus":"PASSED",
                        "evidenceClass":"CERTIFIABLE","fixtureBundleFingerprint":"sha256:%3$s",
                        "planFingerprint":"sha256:%4$s","semanticResultFingerprint":"sha256:%5$s",
                        "diagnosticCode":""},
                       {"attempt":3,"status":"VERIFIED","runId":"child-run-3",
                        "evidenceFingerprint":"sha256:%6$s","evidenceStatus":"PASSED",
                        "evidenceClass":"CERTIFIABLE","fixtureBundleFingerprint":"sha256:%3$s",
                        "planFingerprint":"sha256:%4$s","semanticResultFingerprint":"sha256:%5$s",
                        "diagnosticCode":""}],"distinctVerifiedOutcomes":1,"diagnosticCodes":[]}],
                   "promotion":{"status":"ELIGIBLE","reasons":[],"stableCases":1,
                     "flakyCases":0,"consistentFailureCases":0,"inconclusiveCases":0,
                     "allAttemptsVerified":true},
                   "quarantine":{"status":"NOT_REQUIRED","caseIds":[],"reason":""},
                   "startedAt":"2026-07-18T01:00:00Z","completedAt":"2026-07-18T01:02:01Z",
                   "diagnostics":[],"metadata":{"pipeline":"nightly"}},
                 "attestation":{"schemaVersion":"bloge.testSuiteStabilityAttestation.v1",
                   "signatureStatus":"VERIFIED","stabilityRunId":"%8$s",
                   "suiteRef":{"suiteId":"orders-suite","revision":7,"fingerprint":"sha256:%1$s"},
                   "requestFingerprint":"sha256:%7$s","evidenceFingerprint":"sha256:%6$s",
                   "sourceSuiteEvidenceRefs":[
                     {"attempt":1,"suiteRunId":"suite-run-1","aggregateEvidenceFingerprint":"sha256:%7$s"},
                     {"attempt":2,"suiteRunId":"suite-run-2","aggregateEvidenceFingerprint":"sha256:%3$s"},
                     {"attempt":3,"suiteRunId":"suite-run-3","aggregateEvidenceFingerprint":"sha256:%4$s"}],
                   "signedAt":"2026-07-18T01:02:02Z","keyId":"test-key","algorithm":"Ed25519",
                   "signature":"c2lnbmF0dXJl","independentlyVerifiable":true}}
                """.formatted(suite, target, fixture, plan, semantic, evidence, request, runId);
    }

    private static String schemaAdmissionSuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v4","suiteRunId":"suite-run-admission",
                 "evidenceFingerprint":"sha256:%1$s","evidence":{
                   "schemaVersion":"bloge.testSuiteRunEvidence.v3","suiteRunId":"suite-run-admission",
                   "clientRequestId":"admission-ci-1","status":"PASSED",
                   "executionPurpose":"SCHEMA_ADMISSION_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"suite-boundary","revision":3,"fingerprint":"sha256:%1$s"},
                   "target":{"kind":"OPERATOR","id":"customer.normalize/v2","fingerprint":"sha256:%2$s"},
                   "startedAt":"2026-07-17T01:00:00Z","completedAt":"2026-07-17T01:00:01Z",
                   "caseResults":[{"caseId":"baseline","caseType":"BOUNDARY",
                     "fixtureBundleRef":{"fixtureBundleId":"boundary-baseline","revision":1,
                       "fingerprint":"sha256:%3$s"},"status":"PASSED","runId":"",
                     "evidenceStatus":null,"evidenceClass":null,"assertionsEvaluated":0,
                     "assertionsPassed":0,"diagnosticCode":"","diagnostic":""}],
                   "coverage":{"status":"NOT_EVALUATED","minimumCases":0,"completedCases":0,
                     "requiredCaseTypes":[],"observedCaseTypes":[],"missingCaseTypes":[],
                     "requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],
                     "observedEdgeTransfers":[],"missingEdgeTransfers":[],
                     "minimumAssertionsPerCase":0,"assertionDensityViolations":[],
                     "fixtureConsumptionViolations":[],"allCasesCompleted":false},
                   "promotion":{"status":"BLOCKED","reasons":["BUSINESS_EXECUTION_NOT_PERFORMED",
                     "SCHEMA_ADMISSION_ONLY"],"allCasesPassed":true,"certifiableCases":0,
                     "minimumCertifiableCases":0,"targetCertificationEligible":false,
                     "coverageSatisfied":false,"allCasesCompleted":true},
                   "evaluationMode":"SCHEMA_ADMISSION","boundaryPlanFingerprint":"sha256:%4$s",
                   "inputSchemaFingerprint":"sha256:%5$s","generatorVersion":"boundary-generator.v1",
                   "verificationMode":"EXACT_SHARED_VALIDATOR","sourcePlanStatus":"GENERATED",
                   "sourceCoverageGapCount":0,"coverageGapsAccepted":false,
                   "admissionResults":[{"caseId":"baseline","status":"MATCHED",
                     "expectedOutcome":"ACCEPTED","observedOutcome":"ACCEPTED",
                     "expectedValidationCodes":[],"observedValidationCodes":[],"diagnosticCode":""}],
                   "admissionCoverage":{"status":"SATISFIED","requiredCases":1,
                     "evaluatedCases":1,"matchedCases":1,"expectationMismatchCaseIds":[],
                     "provenanceMismatchCaseIds":[],"incompleteCaseIds":[],"allCasesCompleted":true},
                   "diagnostics":[],"metadata":{"businessTargetInvoked":false,"childRunCount":0}},
                 "attestation":{"schemaVersion":"bloge.testSuiteRunAttestation.v3",
                   "signatureStatus":"VERIFIED","scope":"TERMINAL","suiteRunId":"suite-run-admission",
                   "suiteRef":{"suiteId":"suite-boundary","revision":3,"fingerprint":"sha256:%1$s"},
                   "requestFingerprint":"sha256:%6$s","aggregateEvidenceFingerprint":"sha256:%1$s",
                   "childEvidenceRefs":[],"signedAt":"2026-07-17T01:00:01Z","keyId":"test-key-1",
                   "algorithm":"Ed25519","signature":"AA==","independentlyVerifiable":true}}
                """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "e".repeat(64), "f".repeat(64));
    }

    @Test
    void packagedSchemaEnforcesExecutionServiceRestoreEligibilityInvariant() throws Exception {
        String value = """
                {
                  "schemaVersion":"bloge.executionServiceStateSnapshot.v1",
                  "planFingerprint":"sha256:%s",
                  "bindingSetFingerprint":"sha256:%s",
                  "logicalTime":null,
                  "randomScopeCursors":{},
                  "uuidScopeCursors":{},
                  "usages":[],
                  "restorable":true,
                  "restoreGaps":[],
                  "snapshotFingerprint":"sha256:%s"
                }
                """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64));
        ObjectMapper mapper = new ObjectMapper();

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                mapper.readTree(value), "executionServiceStateSnapshot"));

        JsonNode invalid = mapper.readTree(value);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid)
                .putArray("restoreGaps").add("RANDOM requires a seed.");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                invalid, "executionServiceStateSnapshot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionServiceStateSnapshot")
                .hasMessageNotContaining("RANDOM requires");
    }

    @Test
    void packagedSchemaEnforcesMutuallyExclusiveWorkerAcquisitionOutcomes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode noWork = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestWorkerAcquisitionResponse.v1",
                  "outcome":"NO_WORK",
                  "observedAt":"2026-07-17T00:00:00Z",
                  "assignment":null,
                  "idempotentReplay":false
                }
                """);
        JsonNode acquired = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestWorkerAcquisitionResponse.v1",
                  "outcome":"ACQUIRED",
                  "observedAt":"2026-07-17T00:00:00Z",
                  "assignment":{
                    "runId":"run-a",
                    "status":"RESUMING",
                    "ownerId":"worker-a",
                    "leaseEpoch":2,
                    "revision":3,
                    "leaseExpiresAt":"2026-07-17T00:02:00Z",
                    "checkpointFingerprint":"sha256:%s",
                    "target":{"kind":"GRAPH","id":"graph-a","fingerprint":"sha256:%s"}
                  },
                  "idempotentReplay":false
                }
                """.formatted("a".repeat(64), "b".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                noWork, "durableTestWorkerAcquisitionResponse"));
        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                acquired, "durableTestWorkerAcquisitionResponse"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) noWork)
                .set("assignment", acquired.path("assignment"));
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                noWork, "durableTestWorkerAcquisitionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestWorkerAcquisitionResponse")
                .hasMessageNotContaining("run-a");
    }

    @Test
    void packagedSchemaBindsRecoveryStepOutcomeToItsTerminalReceipt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode suspended = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestRecoveryStepResponse.v1",
                  "runId":"run-a",
                  "outcome":"SUSPENDED",
                  "status":"SUSPENDED",
                  "ownerId":"worker-a",
                  "leaseEpoch":2,
                  "revision":3,
                  "observedAt":"2026-07-17T00:00:00Z",
                  "checkpointFingerprint":"sha256:%s",
                  "boundary":{
                    "nodeId":"approval-2",
                    "boundaryType":"SUSPEND",
                    "boundarySequence":4,
                    "stateVersion":3
                  },
                  "terminal":null,
                  "idempotentReplay":false
                }
                """.formatted("a".repeat(64)));
        JsonNode completed = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestRecoveryStepResponse.v1",
                  "runId":"run-a",
                  "outcome":"COMPLETED",
                  "status":"TERMINAL",
                  "ownerId":"worker-a",
                  "leaseEpoch":2,
                  "revision":4,
                  "observedAt":"2026-07-17T00:01:00Z",
                  "checkpointFingerprint":"sha256:%s",
                  "boundary":{
                    "nodeId":"complete",
                    "boundaryType":"NODE_BOUNDARY",
                    "boundarySequence":5,
                    "stateVersion":4
                  },
                  "terminal":{
                    "executionOutcome":"COMPLETED",
                    "completedAt":"2026-07-17T00:01:00Z",
                    "receiptFingerprint":"sha256:%s",
                    "evidenceStatus":"EVIDENCE_INCOMPLETE",
                    "evidenceGapCodes":["PRE_CHECKPOINT_TRACE_UNAVAILABLE"]
                  },
                  "idempotentReplay":false
                }
                """.formatted("b".repeat(64), "c".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                suspended, "durableTestRecoveryStepResponse"));
        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                completed, "durableTestRecoveryStepResponse"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) completed.path("terminal"))
                .put("executionOutcome", "FAILED");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                completed, "durableTestRecoveryStepResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestRecoveryStepResponse")
                .hasMessageNotContaining("run-a");
    }

    @Test
    void packagedSchemaBindsRecoverySequenceStopReasonToItsFinalStatus() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestRecoverySequenceResponse.v1",
                  "runId":"run-a",
                  "outcome":"COMPLETED",
                  "status":"TERMINAL",
                  "stopReason":"TERMINAL",
                  "providedSignalCount":2,
                  "consumedSignalCount":1,
                  "steps":[{
                    "schemaVersion":"bloge.durableTestRecoveryStepResponse.v1",
                    "runId":"run-a",
                    "outcome":"COMPLETED",
                    "status":"TERMINAL",
                    "ownerId":"worker-a",
                    "leaseEpoch":2,
                    "revision":4,
                    "observedAt":"2026-07-17T00:01:00Z",
                    "checkpointFingerprint":"sha256:%s",
                    "boundary":{
                      "nodeId":"complete",
                      "boundaryType":"NODE_BOUNDARY",
                      "boundarySequence":5,
                      "stateVersion":4
                    },
                    "terminal":{
                      "executionOutcome":"COMPLETED",
                      "completedAt":"2026-07-17T00:01:00Z",
                      "receiptFingerprint":"sha256:%s",
                      "evidenceStatus":"EVIDENCE_INCOMPLETE",
                      "evidenceGapCodes":["PRE_CHECKPOINT_TRACE_UNAVAILABLE"]
                    },
                    "idempotentReplay":false
                  }],
                  "idempotentReplay":false
                }
                """.formatted("a".repeat(64), "b".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                response, "durableTestRecoverySequenceResponse"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) response)
                .put("stopReason", "SIGNALS_EXHAUSTED");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                response, "durableTestRecoverySequenceResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestRecoverySequenceResponse")
                .hasMessageNotContaining("run-a");
    }

    @Test
    void packagedSchemaRejectsUnboundedOrExtensibleRecoverySequencePrograms()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestRecoverySequenceRequest.v1",
                  "clientRequestId":"sequence-a",
                  "expectedFence":{"ownerId":"worker-a","leaseEpoch":2,"revision":3},
                  "expectedCheckpointFingerprint":"sha256:%s",
                  "signals":[{"nodeId":"approval-1","data":"approved"}]
                }
                """.formatted("a".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                request, "durableTestRecoverySequenceRequest"));

        com.fasterxml.jackson.databind.node.ArrayNode signals =
                (com.fasterxml.jackson.databind.node.ArrayNode) request.path("signals");
        while (signals.size() < 17) {
            signals.add(signals.get(0).deepCopy());
        }
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                request, "durableTestRecoverySequenceRequest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestRecoverySequenceRequest")
                .hasMessageNotContaining("approval-1");

        signals.removeAll();
        com.fasterxml.jackson.databind.node.ObjectNode forged =
                mapper.createObjectNode().put("nodeId", "approval-1").put("data", "approved")
                        .put("retry", true);
        signals.add(forged);
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                request, "durableTestRecoverySequenceRequest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestRecoverySequenceRequest")
                .hasMessageNotContaining("approval-1");
    }

    @Test
    void packagedSchemaEnforcesMutationPlanningSafetyAndCompleteness() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode valid = mapper.readTree("""
                {"schemaVersion":"bloge.testMutationCasePlan.v1",
                 "target":{"kind":"GRAPH","id":"graph-a","fingerprint":"sha256:%1$s"},
                 "sourceFormat":"bloge-dsl.ast.v1","sourceFingerprint":"sha256:%1$s",
                 "graphArtifactFingerprint":"sha256:%1$s","planFingerprint":"sha256:%1$s",
                 "status":"GENERATED",
                 "policy":{"plannerVersion":"pure-dsl-mutations-v1","maxMutants":16,
                   "sourceFormat":"bloge-dsl.ast.v1",
                   "verificationMode":"BLOGE_DSL_AST_RECOMPILE_PROOF",
                   "externalOperatorMutation":false,"equivalentMutantDetection":false},
                 "mutants":[{"mutantId":"mutant-001","kind":"FALLBACK_REMOVED",
                   "astPath":"/members/0/fallback","sourceLine":2,"sourceColumn":3,
                   "mutantSourceFingerprint":"sha256:%1$s",
                   "mutantGraphArtifactFingerprint":"sha256:%1$s",
                   "mutantTargetFingerprint":"sha256:%1$s",
                   "equivalenceClassification":"UNKNOWN"}],"gaps":[]}
                """.formatted("a".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                valid, "testMutationCasePlan"));

        JsonNode externalMutation = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) externalMutation.path("policy"))
                .put("externalOperatorMutation", true);
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                externalMutation, "testMutationCasePlan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testMutationCasePlan");

        JsonNode falseComplete = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) falseComplete.path("gaps"))
                .addObject().put("code", "MUTANT_LIMIT_REACHED")
                .put("astPath", "/members/1").put("mutationKind", "FALLBACK_REMOVED");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                falseComplete, "testMutationCasePlan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testMutationCasePlan");

        JsonNode extensible = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) extensible).put("executableSource", "");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                extensible, "testMutationCasePlan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testMutationCasePlan");

        JsonNode wrongTargetKind = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongTargetKind.path("target"))
                .put("kind", "OPERATOR");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                wrongTargetKind, "testMutationCasePlan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testMutationCasePlan");

        JsonNode mismatchedSource = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) mismatchedSource)
                .put("sourceFormat", "legacy-dsl.v0");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                mismatchedSource, "testMutationCasePlan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testMutationCasePlan");
    }

    private static void assertConstant(JsonNode definitions, String definition, String expected) {
        assertThat(definitions.at("/" + definition + "/properties/schemaVersion/const").asText())
                .as(definition + " schema version")
                .isEqualTo(expected);
    }
}
