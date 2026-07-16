package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestOwnerClaimRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestOwnerClaimResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionQueryResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestTerminalRecoveryRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestTerminalRecoveryResponse;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestabilityCapabilitiesTest {

    @Test
    void executionEndpointIsAdvertisedOnlyWhenProfileMarkerEnablesIt() {
        IntegrationCapabilities disabled = IntegrationCapabilities.current();
        IntegrationCapabilities enabled = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true);

        assertThat(disabled.testability().executionEndpointEnabled()).isFalse();
        assertThat(disabled.endpoints()).noneMatch(endpoint -> endpoint.path().startsWith("/api/testing/"));
        assertThat(disabled.features()).containsEntry("dynamicAttemptOccurrenceSelectors", false);
        assertThat(enabled.testability().executionEndpointEnabled()).isTrue();
        assertThat(enabled.supportedObjects()).containsKeys("testExecutionRequest", "testExecutionResponse",
                "testExecutionBatchRequest", "testExecutionBatchResponse", "fixtureBundleRegistrationRequest",
                "storedFixtureBundle", "testSuite", "testSuiteRegistrationRequest", "storedTestSuite",
                "replayPayloadCaptureRequest", "replayPayloadDescriptor", "storedReplayPayload",
                "testSuiteExecutionRequest", "testSuiteExecutionResponse", "testSuiteRunEvidence",
                "testSuiteRunAttestation", "testSuiteEvidenceBundle", "testSuiteRunReconciliation",
                "semanticCorrectnessWorkbookBundle",
                "testSuiteCatalogMaterialization",
                "fixtureBundle", "effectiveExecutionPlan", "executionServiceStateSnapshot",
                "testRunEvidence",
                "testEvidenceIntegrity",
                "testGraphTargetDescriptor", "testOperatorExecutionRequest", "testOperatorTargetDescriptor",
                "durableTestOwnerClaimRequest", "durableTestOwnerClaimResponse",
                "durableTestExecutionView",
                "durableTestRecoveryHeartbeatRequest", "durableTestRecoveryHeartbeatResponse",
                "durableTestTerminalRecoveryRequest", "durableTestTerminalRecoveryResponse");
        assertThat(enabled.features()).containsEntry("operatorMicroGraphExecution", true)
                .containsEntry("dynamicAttemptOccurrenceSelectors", true)
                .containsEntry("immutableTestSuiteRegistry", true)
                .containsEntry("immutableTestSuiteExecution", true)
                .containsEntry("suiteSemanticCoverageVerdict", true)
                .containsEntry("typedSemanticCoverageV2", true)
                .containsEntry("semanticCorrectnessWorkbookProjection", true)
                .containsEntry("suitePromotionEligibilityVerdict", true)
                .containsEntry("builtInGraphSuiteCatalogMaterialization", true)
                .containsEntry("suiteRunOwnerLease", true)
                .containsEntry("abandonedSuiteRunReconciliation", true)
                .containsEntry("governedTestReplayPayloadCapture", true)
                .containsEntry("testReplayBehavior", true)
                .containsEntry("durableTestOwnerClaim", true)
                .containsEntry("durableRecoveryDependencyReauthorization", true)
                .containsEntry("authenticatedDurableRecoveryHeartbeat", true)
                .containsEntry("authenticatedDurableTerminalRecovery", true)
                .containsEntry("signedTestRunEvidence", false)
                .containsEntry("suiteSignedChildEvidenceGate", false)
                .containsEntry("signedTestSuiteRunAttestation", false)
                .containsEntry("portableTestSuiteEvidenceBundle", false)
                .containsEntry("streamingOperatorTestExecution", false)
                .containsEntry("suspendableOperatorTestExecution", false);
        assertThat(enabled.supportedObjects().get("testExecutionResponse"))
                .containsExactly(TestExecutionApiResponse.SCHEMA_VERSION_V1,
                        TestExecutionApiResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteExecutionResponse"))
                .containsExactly(TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                        TestSuiteExecutionResponse.SCHEMA_VERSION,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V3);
        assertThat(enabled.supportedObjects().get("effectiveExecutionPlan"))
                .containsExactly(EffectiveExecutionPlan.SCHEMA_VERSION_V1,
                        EffectiveExecutionPlan.SCHEMA_VERSION_V2,
                        EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("executionServiceStateSnapshot"))
                .containsExactly(ExecutionServiceStateSnapshot.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testRunEvidence"))
                .containsExactly(TestRunEvidence.SCHEMA_VERSION_V1,
                        TestRunEvidence.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestOwnerClaimRequest"))
                .containsExactly(DurableTestOwnerClaimRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestOwnerClaimResponse"))
                .containsExactly(DurableTestOwnerClaimResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestExecutionView"))
                .containsExactly(DurableTestExecutionQueryResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoveryHeartbeatRequest"))
                .containsExactly(DurableTestRecoveryHeartbeatRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoveryHeartbeatResponse"))
                .containsExactly(DurableTestRecoveryHeartbeatResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestTerminalRecoveryRequest"))
                .containsExactly(DurableTestTerminalRecoveryRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestTerminalRecoveryResponse"))
                .containsExactly(DurableTestTerminalRecoveryResponse.SCHEMA_VERSION);
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.path().equals("/api/testing/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/owner-claims"));
        assertThat(enabled.features())
                .containsEntry("durableTestExecutionQuery", true);
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/heartbeats"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/terminal-recoveries"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("PUT") && endpoint.path().equals("/api/testing/suites/{suiteId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("GET") && endpoint.path().equals("/api/testing/suites/{suiteId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals("/api/testing/suites/{suiteId}/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals("/api/testing/suite-executions/{suiteRunId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/suite-executions/{suiteRunId}/evidence-bundle"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals("/api/integration/test-suites/{suiteId}/revisions/{revision}"
                + "/semantic-correctness-workbook"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("PUT")
                && endpoint.path().equals("/api/testing/catalogs/gateway-graph-contract-v1"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("PUT")
                && endpoint.path().equals("/api/testing/replay-payloads/{replayPayloadId}"));

        IntegrationCapabilities signed = IntegrationCapabilities.current(
                new InMemoryVisualEvidenceSigner().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true);
        assertThat(signed.features())
                .containsEntry("signedTestRunEvidence", true)
                .containsEntry("suiteSignedChildEvidenceGate", true)
                .containsEntry("signedTestSuiteRunAttestation", true)
                .containsEntry("portableTestSuiteEvidenceBundle", true);
    }
}
