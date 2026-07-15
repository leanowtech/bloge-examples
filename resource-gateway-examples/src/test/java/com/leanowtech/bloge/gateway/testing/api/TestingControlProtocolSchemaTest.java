package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.EvidenceVerificationKeySet;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TestingControlProtocolSchemaTest {

    @Test
    void schemaBundleTracksJavaProtocolVersionsAndAllTenTerminalStates() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json")));

        assertThat(schema.at("/$defs/testExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionApiRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionResponseV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionApiResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionResponseV1/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionApiResponse.SCHEMA_VERSION_V1);
        assertThat(schema.at("/$defs/testEvidenceIntegrity/properties/schemaVersion/const").asText())
                .isEqualTo(TestEvidenceIntegrity.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionBatchRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionBatchRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionBatchResponse/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionBatchResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/fixtureBundleRegistrationRequest/properties/schemaVersion/const").asText())
                .isEqualTo(FixtureBundleRegistrationRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/storedFixtureBundle/properties/schemaVersion/const").asText())
                .isEqualTo(StoredFixtureBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/replayPayloadCaptureRequest/properties/schemaVersion/const").asText())
                .isEqualTo(ReplayPayloadCaptureRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/replayPayloadDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(ReplayPayloadDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/storedReplayPayload/properties/schemaVersion/const").asText())
                .isEqualTo(StoredReplayPayload.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuite/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuite.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRegistrationRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRegistrationRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/storedTestSuite/properties/schemaVersion/const").asText())
                .isEqualTo(StoredTestSuite.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV1/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V1);
        assertThat(schema.at("/$defs/testSuiteRunAttestation/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteEvidenceBundle/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/evidenceVerificationKeySet/properties/schemaVersion/const").asText())
                .isEqualTo(EvidenceVerificationKeySet.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteCatalogMaterialization/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunEvidence/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunEvidence.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testGraphTargetDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(TestGraphTargetDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testOperatorExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestOperatorExecutionApiRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testOperatorTargetDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(TestOperatorTargetDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/fixtureBundle/properties/schemaVersion/const").asText())
                .isEqualTo(FixtureBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/effectivePlan/properties/schemaVersion/const").asText())
                .isEqualTo(EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testRunEvidence/properties/schemaVersion/const").asText())
                .isEqualTo(TestRunEvidence.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/runStatus/enum")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(TestRunEvidence.Status.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(TestRunEvidence.Status.values()).hasSize(10);
    }

    @Test
    void publicRequestRequiresExactlyOneFixtureSourceAndRejectsUnknownFields() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json")));
        JsonNode request = schema.at("/$defs/testExecutionRequest");

        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("oneOf")).hasSize(2);
        assertThat(request.path("required")).extracting(JsonNode::asText)
                .contains("target", "executionPurpose", "fixtureBundle", "fixtureBundleRef", "verbosity");
    }

    @Test
    void schemaBundleCoversEveryPublicTestingEndpointPayload() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json"))).path("$defs");

        assertThat(definitions.has("fixtureBundleRegistrationRequest")).isTrue();
        assertThat(definitions.has("storedFixtureBundle")).isTrue();
        assertThat(definitions.has("replayPayloadCaptureRequest")).isTrue();
        assertThat(definitions.has("replayPayloadDescriptor")).isTrue();
        assertThat(definitions.has("storedReplayPayload")).isTrue();
        assertThat(definitions.has("testSuiteRegistrationRequest")).isTrue();
        assertThat(definitions.has("storedTestSuite")).isTrue();
        assertThat(definitions.has("testSuiteExecutionRequest")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponse")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV1")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV2")).isTrue();
        assertThat(definitions.has("testSuiteRunAttestation")).isTrue();
        assertThat(definitions.has("testSuiteEvidenceBundle")).isTrue();
        assertThat(definitions.has("evidenceVerificationKeySet")).isTrue();
        assertThat(definitions.has("testSuiteCatalogMaterialization")).isTrue();
        assertThat(definitions.has("testSuiteRunEvidence")).isTrue();
        assertThat(definitions.has("testEvidenceIntegrity")).isTrue();
        assertThat(definitions.at("/fixtureBundleRegistrationRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/target");
        assertThat(definitions.at("/testExecutionRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/graphTarget");
        assertThat(definitions.at("/testOperatorExecutionRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/operatorTarget");
        assertThat(definitions.at("/testOperatorExecutionRequest/properties/executionPurpose/const").asText())
                .isEqualTo("OPERATOR_UNIT_TEST");
        assertThat(definitions.at("/testOperatorTargetDescriptor/required"))
                .extracting(JsonNode::asText)
                .contains("implementationFingerprint", "runtimeBindingStateFingerprint",
                        "schemaFingerprint", "composabilityFingerprint", "composabilityManifest",
                        "testabilityClass", "certificationEligible");
        assertThat(definitions.at("/operatorComposabilityManifest/properties/dependencyMode/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("NONE", "DECLARED", "OPAQUE");
        assertThat(definitions.at("/operatorComposabilityManifest/properties/executionServices/items/enum"))
                .extracting(JsonNode::asText)
                .contains("TIME", "RANDOM", "UUID", "IDENTITY", "FEATURE_FLAG");
        assertThat(definitions.at("/testExecutionBatchRequest/properties/executions/maxItems").asInt())
                .isEqualTo(TestExecutionBatchRequest.MAX_EXECUTIONS);
        assertThat(definitions.at("/testExecutionBatchResponse/properties/executions/items/$ref").asText())
                .isEqualTo("#/$defs/testExecutionResponse");
        assertThat(definitions.at("/replayPayloadCaptureRequest/properties/source/$ref").asText())
                .isEqualTo("#/$defs/replayPayloadCaptureSource");
        assertThat(definitions.at("/replayPayloadDescriptor/properties/redaction/$ref").asText())
                .isEqualTo("#/$defs/replayPayloadRedaction");
        assertThat(definitions.at("/behavior/properties/replayRef/pattern").asText())
                .contains("bloge-replay:");
        assertThat(definitions.at("/selector/properties/attempts/uniqueItems").asBoolean()).isTrue();
        assertThat(definitions.at("/selector/properties/attempts/items/minimum").asInt()).isEqualTo(1);
        assertThat(definitions.at("/selector/properties/attempts/items/maximum").asInt())
                .isEqualTo(100_000);
        assertThat(definitions.at("/selector/properties/occurrences/uniqueItems").asBoolean()).isTrue();
        assertThat(definitions.at("/selector/properties/occurrences/items/maximum").asInt())
                .isEqualTo(100_000);
        assertThat(definitions.at("/testSuite/properties/target/$ref").asText())
                .isEqualTo("#/$defs/exactTarget");
        assertThat(definitions.at("/testSuiteCase/properties/fixtureBundleRef/$ref").asText())
                .isEqualTo("#/$defs/governedFixtureBundleRef");
        assertThat(definitions.at("/governedFixtureBundleRef/properties/fingerprint/$ref").asText())
                .isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at("/testSuite/properties/cases/maxItems").asInt())
                .isEqualTo(TestSuiteRegistryService.MAX_CASES);
        assertThat(definitions.at("/testSuiteExecutionResponseV2/properties/attestation/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunAttestation");
        assertThat(definitions.at("/testSuiteEvidenceBundle/properties/payloadPolicy/const").asText())
                .isEqualTo("OMITTED");
        assertThat(definitions.at(
                "/testSuiteCoveragePolicy/properties/requiredInvocationSiteIds/items/type").asText())
                .isEqualTo("string");
        assertThat(definitions.at(
                "/testSuiteCoveragePolicy/properties/requiredEdgeTransfers/items/$ref").asText())
                .isEqualTo("#/$defs/testSuiteEdgeTransferRef");
        assertThat(definitions.at("/testSuiteEdgeTransferRef/required"))
                .extracting(JsonNode::asText)
                .containsExactly("fromInvocationSiteId", "toInvocationSiteId");
        assertThat(definitions.at("/testSuiteExecutionRequest/properties/suiteRef/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRef");
        assertThat(definitions.at(
                "/testSuiteCatalogMaterialization/properties/suites/items/$ref").asText())
                .isEqualTo("#/$defs/testSuiteCatalogSuiteAsset");
        assertThat(definitions.at(
                "/testSuiteCatalogSuiteAsset/properties/fixtureBundleRefs/items/$ref").asText())
                .isEqualTo("#/$defs/governedFixtureBundleRef");
        assertThat(definitions.at("/testSuiteExecutionRequest/properties/clientRequestId/minLength").asInt())
                .isEqualTo(1);
        assertThat(definitions.at("/testSuiteRunEvidence/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(Arrays.stream(TestSuiteRunEvidence.Status.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(definitions.at(
                "/testSuiteCoverageVerdict/properties/observedEdgeTransfers/items/$ref").asText())
                .isEqualTo("#/$defs/testSuiteEdgeTransferRef");
        assertThat(definitions.at("/testSuitePromotionVerdict/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("NOT_EVALUATED", "ELIGIBLE", "BLOCKED");
        assertThat(definitions.at("/effectivePlan/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/effectivePlan/required")).extracting(JsonNode::asText)
                .contains("replayDependencies");
        assertThat(definitions.at(
                "/effectivePlan/properties/replayDependencies/items/$ref").asText())
                .isEqualTo("#/$defs/replayDependency");
        assertThat(definitions.at("/replayDependency/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/replayDependency/properties/replayRef/pattern").asText())
                .contains("bloge-replay:");
        assertThat(definitions.at("/testRunEvidence/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testExecutionResponse/oneOf")).hasSize(2);
        assertThat(definitions.at("/testExecutionResponseV2/required"))
                .extracting(JsonNode::asText).contains("integrity", "evidence");
        assertThat(definitions.at("/testExecutionResponseV2/properties/integrity/$ref").asText())
                .isEqualTo("#/$defs/testEvidenceIntegrity");
        assertThat(definitions.at("/testEvidenceIntegrity/properties/signatureStatus/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("VERIFIED", "UNSIGNED", "VERIFICATION_UNAVAILABLE");
    }

    @Test
    void evidenceSchemaFreezesOccurrenceAttemptAndEdgeCoordinates() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json"))).path("$defs");

        assertThat(definitions.at("/testRunEvidence/properties/nodeTrace/items/$ref").asText())
                .isEqualTo("#/$defs/nodeTrace");
        assertThat(definitions.at("/testRunEvidence/properties/edgeTrace/items/$ref").asText())
                .isEqualTo("#/$defs/edgeTrace");
        assertThat(definitions.at("/nodeTrace/required")).extracting(JsonNode::asText)
                .contains("invocationSiteId", "graphPath", "correlationKey", "occurrence",
                        "graphOccurrence", "attempts");
        assertThat(definitions.at("/nodeTrace/properties/attempts/items/$ref").asText())
                .isEqualTo("#/$defs/attemptTrace");
        assertThat(definitions.at("/attemptTrace/properties/attempt/minimum").asInt()).isZero();
        assertThat(definitions.at("/edgeTrace/required")).extracting(JsonNode::asText)
                .contains("graphPath", "correlationKey", "graphOccurrence",
                        "fromInvocationSiteId", "toInvocationSiteId");
        assertThat(definitions.at("/edgeTrace/properties/status/enum")).extracting(JsonNode::asText)
                .containsExactly("TRANSFERRED", "SKIPPED", "NOT_TRANSFERRED");
    }
}
