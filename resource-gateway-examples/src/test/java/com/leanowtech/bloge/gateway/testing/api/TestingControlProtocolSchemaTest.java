package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.planning.TestBoundaryCasePlanner;
import com.leanowtech.bloge.gateway.testing.planning.TestDslMutationPlanner;
import com.leanowtech.bloge.gateway.testing.planning.TestPropertyCasePlanner;
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
        assertThat(schema.at("/$defs/testSuiteV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteV2.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteV3/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteV3.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteV4/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteV4.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteV5/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteV5.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRegistrationRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRegistrationRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/storedTestSuite/properties/schemaVersion/const").asText())
                .isEqualTo(StoredTestSuite.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityExecutionRequest/properties/schemaVersion/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V1,
                        TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V2,
                        TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V3,
                        TestSuiteStabilityExecutionRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteStabilityEvidence/properties/schemaVersion/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(TestSuiteStabilityEvidence.SCHEMA_VERSION_V1,
                        TestSuiteStabilityEvidence.SCHEMA_VERSION_V2,
                        TestSuiteStabilityEvidence.SCHEMA_VERSION_V3,
                        TestSuiteStabilityEvidence.SCHEMA_VERSION_V4,
                        TestSuiteStabilityEvidence.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityAttestation/properties/schemaVersion/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(TestSuiteStabilityAttestation.SCHEMA_VERSION_V1,
                        TestSuiteStabilityAttestation.SCHEMA_VERSION_V2,
                        TestSuiteStabilityAttestation.SCHEMA_VERSION_V3,
                        TestSuiteStabilityAttestation.SCHEMA_VERSION_V4,
                        TestSuiteStabilityAttestation.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityExecutionResponse/properties/schemaVersion/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V1,
                        TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V2,
                        TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V3,
                        TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V4,
                        TestSuiteStabilityExecutionResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityProgress/properties/schemaVersion/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(TestSuiteStabilityProgressResponse.SCHEMA_VERSION_V1,
                        TestSuiteStabilityProgressResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityTrendAnalysisRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityTrendAnalysisRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityTrendEvidence/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityTrendEvidence.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityTrendAttestation/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityTrendAttestation.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityTrendAnalysisResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityTrendAnalysisResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityCrossRetentionTrendAnalysisRequest/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAnalysisRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationEvidence/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationEvidence.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationAttestation/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationAttestation.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerEntry/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationLedgerEntry.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerHead/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationLedgerHead.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerFloor/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationLedgerFloor.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationArchiveSegment/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationArchiveSegment.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationFloorRetirementEvidence/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementEvidence.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationFloorRetirementAttestation/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementAttestation.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerLifecyclePageRequest/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerLifecyclePage/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecyclePage.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerLifecycleAttestation/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleAttestation.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerLifecyclePageResponse/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(
                TestSuiteStabilityObservationLedgerLifecyclePageResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityObservationLedgerRange/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityObservationLedgerRange.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityCrossRetentionTrendEvidence/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendEvidence.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityCrossRetentionTrendAttestation/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestation.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityCrossRetentionTrendAnalysisResponse/properties"
                        + "/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAnalysisResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityJobSubmitRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityJobCancelRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityJobCancelRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityJobView/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityJobView.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteStabilityJobSubmitResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(TestSuiteStabilityJobSubmitResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV1/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V1);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV3/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V3);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV4/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V4);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV5/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V5);
        assertThat(schema.at("/$defs/testSuiteExecutionResponseV6/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V6);
        assertThat(schema.at("/$defs/testSuiteRunAttestation/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunAttestationV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V2);
        assertThat(schema.at("/$defs/testSuiteRunAttestationV3/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V3);
        assertThat(schema.at("/$defs/testSuiteRunAttestationV4/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V4);
        assertThat(schema.at("/$defs/testSuiteRunAttestationV5/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V5);
        assertThat(schema.at("/$defs/testSuiteEvidenceBundleV1/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteEvidenceBundleV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V2);
        assertThat(schema.at("/$defs/testSuiteEvidenceBundleV3/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V3);
        assertThat(schema.at("/$defs/testSuiteEvidenceBundleV4/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V4);
        assertThat(schema.at("/$defs/testSuiteEvidenceBundleV5/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V5);
        assertThat(schema.at("/$defs/evidenceVerificationKeySet/properties/schemaVersion/const").asText())
                .isEqualTo(EvidenceVerificationKeySet.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testSuiteCatalogMaterialization/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunEvidence/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunEvidence.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunEvidenceV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunEvidenceV2.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunEvidenceV3/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunEvidenceV3.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunEvidenceV4/properties/schemaVersion/const").asText())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testSuiteRunEvidenceV5/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteRunEvidenceV5.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testGraphTargetDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(TestGraphTargetDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testBoundaryCasePlan/properties/schemaVersion/const").asText())
                .isEqualTo(TestBoundaryCasePlan.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testPropertyCasePlan/properties/schemaVersion/const").asText())
                .isEqualTo(TestPropertyCasePlan.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testMutationCasePlan/properties/schemaVersion/const").asText())
                .isEqualTo(TestMutationCasePlan.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testBoundarySuiteMaterializationRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testBoundarySuiteMaterialization/properties/schemaVersion/const")
                .asText()).isEqualTo(TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testPropertySuiteMaterializationRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(TestPropertySuiteMaterializationRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testPropertySuiteMaterialization/properties/schemaVersion/const")
                .asText()).isEqualTo(TestPropertySuiteMaterializationResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testMutationSuiteMaterializationRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(TestMutationSuiteMaterializationRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/testMutationSuiteMaterialization/properties/schemaVersion/const")
                .asText()).isEqualTo(TestMutationSuiteMaterializationResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testOperatorExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestOperatorExecutionApiRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testOperatorTargetDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(TestOperatorTargetDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/fixtureBundle/properties/schemaVersion/const").asText())
                .isEqualTo(FixtureBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/effectivePlan/properties/schemaVersion/const").asText())
                .isEqualTo(EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/executionServiceStateSnapshot/properties/schemaVersion/const").asText())
                .isEqualTo(ExecutionServiceStateSnapshot.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/executionServiceStateSnapshot/required"))
                .extracting(JsonNode::asText)
                .contains("restorable", "restoreGaps", "snapshotFingerprint");
        assertThat(schema.at(
                "/$defs/fixtureConsumptionStateSnapshot/properties/schemaVersion/const").asText())
                .isEqualTo(FixtureConsumptionStateSnapshot.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestExecutionCheckpointV2/properties/schemaVersion/const").asText())
                .isEqualTo(DurableTestExecutionCheckpoint.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestExecutionCheckpointV1/properties/schemaVersion/const").asText())
                .isEqualTo(DurableTestExecutionCheckpoint.SCHEMA_VERSION_V1);
        assertThat(schema.at(
                "/$defs/durableTestOwnerClaimRequest/properties/schemaVersion/const").asText())
                .isEqualTo(DurableTestOwnerClaimRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestOwnerClaimResponse/properties/schemaVersion/const").asText())
                .isEqualTo(DurableTestOwnerClaimResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestWorkerAcquisitionRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestWorkerAcquisitionRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestWorkerAcquisitionResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestWorkerAcquisitionResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableStateProjectionFindingsResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableStateProjectionFindingsResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableStateProjectionFindingClaimRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableStateProjectionFindingClaimRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableStateProjectionFindingClaimResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableStateProjectionFindingClaimResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableStateProjectionFindingResolutionRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableStateProjectionFindingResolutionRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableStateProjectionFindingResolutionResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableStateProjectionFindingResolutionResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableStateProjectionFindingResolutionResponse/properties")
                .has("claimToken")).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantinesResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableWorkerQuarantinesResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineHistoryResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableWorkerQuarantineHistoryResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineClaimRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableWorkerQuarantineClaimRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineClaimResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableWorkerQuarantineClaimResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineResolutionRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableWorkerQuarantineResolutionRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineResolutionResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableWorkerQuarantineResolutionResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineResolutionResponse/properties")
                .has("claimToken")).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineHistoryResponse/properties/history/items/properties")
                .has("claimToken")).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineDiscardApprovalRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableWorkerQuarantineDiscardApprovalRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineDiscardApprovalResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableWorkerQuarantineDiscardApprovalResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineApprovedDiscardRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableWorkerQuarantineApprovedDiscardRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineApprovedDiscardResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableWorkerQuarantineApprovedDiscardResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineApprovedDiscardHistoryResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableWorkerQuarantineApprovedDiscardHistoryResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineChangeAuthorization/properties/schemaVersion/const")
                .asText()).isEqualTo(WorkerQuarantineChangeAuthorization.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineChangeAuthorizationMaterial/properties/schemaVersion/const")
                .asText()).isEqualTo(
                WorkerQuarantineChangeAuthorization.Material.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineChangeAuthorizationScope/properties/schemaVersion/const")
                .asText()).isEqualTo(
                WorkerQuarantineChangeAuthorizationBinding.ScopeMaterial.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineChangeAuthorizationSubject/properties/schemaVersion/const")
                .asText()).isEqualTo(
                WorkerQuarantineChangeAuthorizationBinding.SubjectMaterial.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineChangeAuthorizationReference/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableWorkerQuarantineChangeAuthorizationReference.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineDiscardApprovalRequest/properties/changeAuthorization/$ref")
                .asText()).isEqualTo("#/$defs/workerQuarantineChangeAuthorization");
        assertThat(schema.at(
                "/$defs/workerQuarantineChangeAuthorization/properties/signatures/items/properties/algorithm/const")
                .asText()).isEqualTo("Ed25519");
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineDiscardApprovalResponse/properties/externalAuthorization/$ref")
                .asText()).isEqualTo(
                "#/$defs/durableWorkerQuarantineChangeAuthorizationReference");
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineChangeAuthorizationReference/properties")
                .has("signatures")).isFalse();
        assertThat(schema.at(
                "/$defs/workerQuarantineRequestIndexReplicaProofRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(
                WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineRequestIndexReplicaProof/properties/schemaVersion/const")
                .asText()).isEqualTo(WorkerQuarantineRequestIndexReplicaProof.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineRequestIndexReplicaProofMaterial/properties/schemaVersion/const")
                .asText()).isEqualTo(
                WorkerQuarantineRequestIndexReplicaProof.MATERIAL_SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/workerQuarantineRequestIndexReplicaProofRequest/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(schema.at(
                "/$defs/workerQuarantineRequestIndexReplicaProofMaterial/properties/inventory/$ref")
                .asText()).isEqualTo(
                "#/$defs/workerQuarantineRequestIndexInventory");
        assertThat(schema.at(
                "/$defs/workerQuarantineRequestIndexReplicaProof/properties/seal/properties/algorithm/const")
                .asText()).isEqualTo("Ed25519");
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineDiscardApprovalRequest/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineApprovedDiscardRequest/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineDiscardApprovalResponse/properties")
                .has("claimToken")).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineApprovedDiscardResponse/properties")
                .has("claimToken")).isFalse();
        assertThat(schema.at(
                "/$defs/durableWorkerQuarantineApprovedDiscardHistoryResponse/properties/history/items/properties")
                .has("claimToken")).isFalse();
        assertThat(schema.at(
                "/$defs/durableTestExecutionView/properties/schemaVersion/const").asText())
                .isEqualTo(DurableTestExecutionQueryResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestExecutionCreateRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestExecutionCreateRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableOperatorTestExecutionCreateRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DurableOperatorTestExecutionCreateRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestExecutionCreateResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestExecutionCreateResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestRecoveryHeartbeatRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestRecoveryHeartbeatRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestRecoveryHeartbeatResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestRecoveryHeartbeatResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestTerminalRecoveryRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestTerminalRecoveryRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestTerminalRecoveryResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestTerminalRecoveryResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestRecoveryStepRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestRecoveryStepRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestRecoveryStepResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestRecoveryStepResponse.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestRecoverySequenceRequest/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestRecoverySequenceRequest.SCHEMA_VERSION);
        assertThat(schema.at(
                "/$defs/durableTestRecoverySequenceResponse/properties/schemaVersion/const")
                .asText()).isEqualTo(DurableTestRecoverySequenceResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/durableTestExecutionCheckpointV2/required"))
                .extracting(JsonNode::asText)
                .contains("dependencies", "fixtureConsumptionState", "executionServiceState",
                        "engineState", "lifecycle", "checkpointFingerprint");
        assertThat(schema.at("/$defs/durableControlDependenciesV2/required"))
                .extracting(JsonNode::asText)
                .contains("target");
        assertThat(schema.at("/$defs/durableControlDependenciesV1/properties").has("target"))
                .isFalse();
        assertThat(schema.at("/$defs/durableExecutionTargetRef/properties/kind/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("GRAPH", "OPERATOR");
        assertThat(schema.at("/$defs/testRunEvidenceV2/properties/schemaVersion/const").asText())
                .isEqualTo(TestRunEvidence.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testRunEvidenceV1/properties/schemaVersion/const").asText())
                .isEqualTo(TestRunEvidence.SCHEMA_VERSION_V1);
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
        assertThat(definitions.has("testMutationSuiteExecutionRequest")).isTrue();
        assertThat(definitions.has("testSuiteStabilityExecutionRequest")).isTrue();
        assertThat(definitions.has("testSuiteStabilityStatisticalPolicy")).isTrue();
        assertThat(definitions.has("testSuiteStabilityStatisticalAssessment")).isTrue();
        assertThat(definitions.has("testSuiteStabilityEvidence")).isTrue();
        assertThat(definitions.has("testSuiteStabilityAttestation")).isTrue();
        assertThat(definitions.has("testSuiteStabilityExecutionResponse")).isTrue();
        assertThat(definitions.has("testSuiteStabilityProgress")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityCrossRetentionTrendAnalysisRequest")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationEvidence")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationAttestation")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservation")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationLedgerEntry")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationLedgerHead")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationLedgerFloor")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationArchiveSegment")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationFloorRetirementEvidence")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationFloorRetirementAttestation")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationFloorRetirement")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecyclePageRequest")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecyclePage")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecycleRetirementRef")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecycleAttestation")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecyclePageResponse")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecycleArchiveRef")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecyclePageV2")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecycleAttestationV2")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityObservationLedgerLifecyclePageResponseV2")).isTrue();
        assertThat(definitions.has("testSuiteStabilityObservationLedgerRange")).isTrue();
        assertThat(definitions.has("testSuiteStabilityCrossRetentionTrendEvidence")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityCrossRetentionObservationRef")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityCrossRetentionTrendAttestation")).isTrue();
        assertThat(definitions.has(
                "testSuiteStabilityCrossRetentionTrendAnalysisResponse")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponse")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV1")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV2")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV3")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV4")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV5")).isTrue();
        assertThat(definitions.has("testSuiteExecutionResponseV6")).isTrue();
        assertThat(definitions.has("testSuiteRunAttestation")).isTrue();
        assertThat(definitions.has("testSuiteRunAttestationV3")).isTrue();
        assertThat(definitions.has("testSuiteRunAttestationV4")).isTrue();
        assertThat(definitions.has("testSuiteRunAttestationV5")).isTrue();
        assertThat(definitions.has("testSuiteEvidenceBundle")).isTrue();
        assertThat(definitions.has("evidenceVerificationKeySet")).isTrue();
        assertThat(definitions.has("testSuiteCatalogMaterialization")).isTrue();
        assertThat(definitions.has("testSuiteRunEvidence")).isTrue();
        assertThat(definitions.has("testSuiteRunEvidenceV3")).isTrue();
        assertThat(definitions.has("testSuiteRunEvidenceV4")).isTrue();
        assertThat(definitions.has("testSuiteRunEvidenceV5")).isTrue();
        assertThat(definitions.has("testEvidenceIntegrity")).isTrue();
        assertThat(definitions.has("testBoundaryCasePlan")).isTrue();
        assertThat(definitions.has("testPropertyCasePlan")).isTrue();
        assertThat(definitions.has("testMutationCasePlan")).isTrue();
        assertThat(definitions.has("testSuiteV5")).isTrue();
        assertThat(definitions.has("testMutationSuiteMaterializationRequest")).isTrue();
        assertThat(definitions.has("testMutationSuiteMaterialization")).isTrue();
        assertThat(definitions.has("testBoundarySuiteMaterializationRequest")).isTrue();
        assertThat(definitions.has("testBoundarySuiteMaterialization")).isTrue();
        assertThat(definitions.has("durableTestOwnerClaimRequest")).isTrue();
        assertThat(definitions.has("durableTestOwnerClaimResponse")).isTrue();
        assertThat(definitions.has("durableTestWorkerAcquisitionRequest")).isTrue();
        assertThat(definitions.has("durableTestWorkerAcquisitionResponse")).isTrue();
        assertThat(definitions.has("durableTestExecutionView")).isTrue();
        assertThat(definitions.has("durableTestExecutionCreateRequest")).isTrue();
        assertThat(definitions.has("durableOperatorTestExecutionCreateRequest")).isTrue();
        assertThat(definitions.has("durableTestExecutionCreateResponse")).isTrue();
        assertThat(definitions.has("durableTestRecoveryHeartbeatRequest")).isTrue();
        assertThat(definitions.has("durableTestRecoveryHeartbeatResponse")).isTrue();
        assertThat(definitions.has("durableTestTerminalRecoveryRequest")).isTrue();
        assertThat(definitions.has("durableTestTerminalRecoveryResponse")).isTrue();
        assertThat(definitions.has("durableTestRecoveryStepRequest")).isTrue();
        assertThat(definitions.has("durableTestRecoveryStepResponse")).isTrue();
        assertThat(definitions.has("durableTestRecoverySequenceRequest")).isTrue();
        assertThat(definitions.has("durableTestRecoverySequenceResponse")).isTrue();
        assertThat(definitions.at("/durableTestOwnerClaimRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestOwnerClaimRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "expectedFence",
                        "expectedCheckpointFingerprint");
        assertThat(definitions.at("/durableTestOwnerClaimResponse/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/durableTestOwnerClaimResponse/properties/status/const").asText())
                .isEqualTo("RESUMING");
        assertThat(definitions.at(
                "/durableTestOwnerClaimResponse/properties/target/properties/fingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestWorkerAcquisitionRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestWorkerAcquisitionRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId");
        assertThat(definitions.at(
                "/durableTestWorkerAcquisitionResponse/properties/outcome/enum"))
                .extracting(JsonNode::asText).containsExactly("ACQUIRED", "NO_WORK");
        assertThat(definitions.at(
                "/durableTestWorkerAcquisitionResponse/properties").has("dispatch")).isFalse();
        assertThat(definitions.at(
                "/durableTestWorkerAssignment/properties/checkpointFingerprint/$ref").asText())
                .isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestExecutionView/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/durableTestExecutionView/required"))
                .extracting(JsonNode::asText)
                .contains("runId", "engineExecutionId", "status", "fence", "fixture",
                        "engineBoundary", "checkpointFingerprint", "recoverable",
                        "migrationRequired")
                .doesNotContain("target");
        assertThat(definitions.at(
                "/durableTestExecutionView/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("ACTIVE", "SUSPENDED", "RESUMING", "TERMINAL",
                        "CONTROL_PLAN_UNAVAILABLE");
        assertThat(definitions.at(
                "/durableTestExecutionView/properties/engineBoundary/properties/closureFingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestExecutionCreateRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestExecutionCreateRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "target",
                        "executionPurpose", "context", "fixtureBundleRef");
        assertThat(definitions.at(
                "/durableTestExecutionCreateRequest/properties/target/properties/kind/const")
                .asText()).isEqualTo("GRAPH");
        assertThat(definitions.at(
                "/durableTestExecutionCreateRequest/properties/target/properties/fingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestExecutionCreateRequest/properties/fixtureBundleRef/properties/fingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableOperatorTestExecutionCreateRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableOperatorTestExecutionCreateRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "target",
                        "executionPurpose", "input", "fixtureBundleRef");
        assertThat(definitions.at(
                "/durableOperatorTestExecutionCreateRequest/properties/target/properties/kind/const")
                .asText()).isEqualTo("OPERATOR");
        assertThat(definitions.at(
                "/durableOperatorTestExecutionCreateRequest/properties/executionPurpose/const")
                .asText()).isEqualTo("OPERATOR_UNIT_TEST");
        assertThat(definitions.at(
                "/durableOperatorTestExecutionCreateRequest/properties/target/properties/fingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestExecutionCreateResponse/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/durableTestExecutionCreateResponse/properties/execution/$ref").asText())
                .isEqualTo("#/$defs/durableTestExecutionView");
        assertThat(definitions.at(
                "/durableTestRecoveryHeartbeatRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestRecoveryHeartbeatRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "expectedFence",
                        "expectedCheckpointFingerprint");
        assertThat(definitions.at(
                "/durableTestRecoveryHeartbeatRequest/properties/expectedFence/$ref").asText())
                .isEqualTo("#/$defs/durableOwnerClaimFence");
        assertThat(definitions.at(
                "/durableTestRecoveryHeartbeatResponse/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/durableTestRecoveryHeartbeatResponse/properties/status/const").asText())
                .isEqualTo("RESUMING");
        assertThat(definitions.at(
                "/durableTestRecoveryHeartbeatResponse/properties/checkpointFingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestTerminalRecoveryRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "expectedFence",
                        "expectedCheckpointFingerprint", "signal");
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryRequest/properties/expectedFence/$ref").asText())
                .isEqualTo("#/$defs/durableOwnerClaimFence");
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryRequest/properties/signal/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryResponse/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryResponse/properties/status/const").asText())
                .isEqualTo("TERMINAL");
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryResponse/properties/terminalCheckpointFingerprint/$ref")
                .asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at(
                "/durableTestTerminalRecoveryResponse/properties/evidenceStatus/const").asText())
                .isEqualTo("EVIDENCE_INCOMPLETE");
        assertThat(definitions.at(
                "/durableTestRecoveryStepRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestRecoveryStepRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "expectedFence",
                        "expectedCheckpointFingerprint", "signal");
        assertThat(definitions.at(
                "/durableTestRecoveryStepRequest/properties/signal/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/properties/outcome/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("SUSPENDED", "COMPLETED", "FAILED", "FAILED_RECOVERY",
                        "CANCELLED", "TERMINATED");
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/properties/status/enum"))
                .extracting(JsonNode::asText).containsExactly("SUSPENDED", "TERMINAL");
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/properties/terminal/oneOf"))
                .hasSize(2);
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/oneOf")).hasSize(6);
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/oneOf/1/properties/terminal/properties/executionOutcome/const")
                .asText()).isEqualTo("COMPLETED");
        assertThat(definitions.at(
                "/durableTestRecoveryStepResponse/properties").has("signal")).isFalse();
        assertThat(definitions.at(
                "/durableTestRecoverySequenceRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/durableTestRecoverySequenceRequest/required"))
                .extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "clientRequestId", "expectedFence",
                        "expectedCheckpointFingerprint", "signals");
        assertThat(definitions.at(
                "/durableTestRecoverySequenceRequest/properties/signals/minItems").asInt())
                .isEqualTo(1);
        assertThat(definitions.at(
                "/durableTestRecoverySequenceRequest/properties/signals/maxItems").asInt())
                .isEqualTo(16);
        assertThat(definitions.at(
                "/durableTestRecoverySequenceRequest/properties/signals/items/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(definitions.at(
                "/durableTestRecoverySequenceResponse/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/durableTestRecoverySequenceResponse/properties/steps/items/$ref").asText())
                .isEqualTo("#/$defs/durableTestRecoveryStepResponse");
        assertThat(definitions.at(
                "/durableTestRecoverySequenceResponse/properties/stopReason/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("SIGNALS_EXHAUSTED", "TERMINAL");
        assertThat(definitions.at(
                "/durableTestRecoverySequenceResponse/oneOf")).hasSize(6);
        assertThat(definitions.at("/fixtureBundleRegistrationRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/target");
        assertThat(definitions.at("/testBoundaryCasePlan/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/testBoundaryCasePlan/properties/cases/maxItems").asInt())
                .isEqualTo(TestBoundaryCasePlanner.MAX_CASES);
        assertThat(definitions.at("/testBoundaryCasePlan/properties/policy/properties/maxDepth/const")
                .asInt()).isEqualTo(TestBoundaryCasePlanner.MAX_DEPTH);
        assertThat(definitions.at(
                "/testBoundaryCasePlan/properties/policy/properties/maxCollectionItems/const")
                .asInt()).isEqualTo(TestBoundaryCasePlanner.MAX_COLLECTION_ITEMS);
        assertThat(definitions.at("/testBoundaryCasePlan/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("GENERATED", "PARTIAL", "UNAVAILABLE");
        assertThat(definitions.at("/testBoundaryCasePlan/oneOf")).hasSize(3);
        assertThat(definitions.at("/testPropertyCasePlan/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/testPropertyCasePlan/properties/quantification/const")
                .asText()).isEqualTo(TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED.name());
        assertThat(definitions.at("/testPropertyCasePlan/properties/exhaustive/const")
                .asBoolean()).isFalse();
        assertThat(definitions.at("/testPropertyCasePlan/properties/trials/maxItems").asInt())
                .isEqualTo(TestPropertyCasePlanner.MAX_TRIALS);
        assertThat(definitions.at(
                "/testPropertyCasePlan/properties/trials/items/properties/shrinkPath/maxItems")
                .asInt()).isEqualTo(TestPropertyCasePlanner.MAX_SHRINK_STEPS);
        assertThat(definitions.at(
                "/testPropertyCasePlan/properties/policy/properties/maxCases/maximum")
                .asInt()).isEqualTo(TestPropertyCasePlanner.MAX_CASES);
        assertThat(definitions.at(
                "/testPropertyCasePlan/properties/policy/properties/generatorVersion/const")
                .asText()).isEqualTo(TestPropertyCasePlanner.GENERATOR_VERSION);
        assertThat(definitions.at("/testPropertyCasePlan/oneOf")).hasSize(3);
        assertThat(definitions.at("/testMutationCasePlan/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/testMutationCasePlan/properties/policy/properties/plannerVersion/const")
                .asText()).isEqualTo(TestDslMutationPlanner.PLANNER_VERSION);
        assertThat(definitions.at(
                "/testMutationCasePlan/properties/policy/properties/maxMutants/maximum")
                .asInt()).isEqualTo(TestDslMutationPlanner.MAX_MUTANTS);
        assertThat(definitions.at(
                "/testMutationCasePlan/properties/policy/properties/sourceFormat/const")
                .asText()).isEqualTo(TestDslMutationPlanner.SOURCE_FORMAT);
        assertThat(definitions.at(
                "/testMutationCasePlan/properties/policy/properties/verificationMode/const")
                .asText()).isEqualTo(TestDslMutationPlanner.VERIFICATION_MODE);
        assertThat(definitions.at(
                "/testMutationCasePlan/properties/policy/properties/externalOperatorMutation/const")
                .asBoolean()).isFalse();
        assertThat(definitions.at(
                "/testMutationCasePlan/properties/policy/properties/equivalentMutantDetection/const")
                .asBoolean()).isFalse();
        assertThat(definitions.at("/testMutationCasePlan/properties/mutants/maxItems").asInt())
                .isEqualTo(TestDslMutationPlanner.MAX_MUTANTS);
        assertThat(definitions.at("/testMutationCasePlan/oneOf")).hasSize(3);
        assertThat(definitions.at("/testSuiteV5/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testSuiteV5/properties/cases/maxItems").asInt())
                .isEqualTo(TestSuiteV5.MAX_CASES);
        assertThat(definitions.at("/testSuiteV5/properties/mutants/maxItems").asInt())
                .isEqualTo(TestSuiteV5.MAX_MUTANTS);
        assertThat(definitions.at(
                "/testSuiteMutationPolicy/properties/externalOperatorMutation/const")
                .asBoolean()).isFalse();
        assertThat(definitions.at(
                "/testSuiteMutationScorePolicy/properties/excludeEquivalentMutants/const")
                .asBoolean()).isFalse();
        assertThat(definitions.at("/testSuiteV5/oneOf")).hasSize(2);
        assertThat(definitions.at(
                "/testBoundarySuiteMaterializationRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/testBoundarySuiteMaterializationRequest/properties/selectedCaseIds/maxItems")
                .asInt()).isEqualTo(TestBoundaryCasePlanner.MAX_CASES);
        assertThat(definitions.at(
                "/testBoundarySuiteMaterialization/properties/sourcePlanStatus/enum"))
                .extracting(JsonNode::asText).containsExactly("GENERATED", "PARTIAL");
        assertThat(definitions.at("/testBoundarySuiteMaterialization/oneOf")).hasSize(2);
        assertThat(definitions.at(
                "/testPropertySuiteMaterializationRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/testPropertySuiteMaterializationRequest/properties/trials/maximum").asInt())
                .isEqualTo(TestPropertyCasePlanner.MAX_TRIALS);
        assertThat(definitions.at(
                "/testPropertySuiteMaterializationRequest/properties/maxShrinkSteps/maximum")
                .asInt()).isEqualTo(TestPropertyCasePlanner.MAX_SHRINK_STEPS);
        assertThat(definitions.at(
                "/testPropertySuiteMaterialization/properties/caseIds/maxItems").asInt())
                .isEqualTo(TestPropertyCasePlanner.MAX_CASES);
        assertThat(definitions.at("/testPropertySuiteMaterialization/oneOf")).hasSize(2);
        assertThat(definitions.at(
                "/testMutationSuiteMaterializationRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/testMutationSuiteMaterializationRequest/properties/maxMutants/maximum").asInt())
                .isEqualTo(TestSuiteV5.MAX_MUTANTS);
        assertThat(definitions.at(
                "/testMutationSuiteMaterialization/properties/mutantCaseExecutions/maximum").asInt())
                .isEqualTo(TestSuiteV5.MAX_MUTANT_CASE_EXECUTIONS);
        assertThat(definitions.at("/testMutationSuiteMaterialization/oneOf")).hasSize(2);
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
                .contains("TIME", "RANDOM", "UUID", "IDENTITY", "FEATURE_FLAG", "SECRET");
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
        assertThat(definitions.at("/testSuiteEvidenceBundleV1/properties/payloadPolicy/const").asText())
                .isEqualTo("OMITTED");
        assertThat(definitions.at("/testSuiteEvidenceBundle/oneOf")).hasSize(5);
        assertThat(definitions.at("/testSuiteExecutionResponse/oneOf")).hasSize(6);
        assertThat(definitions.at(
                "/testSuiteExecutionResponseV4/properties/evidence/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunEvidenceV3");
        assertThat(definitions.at(
                "/testSuiteExecutionResponseV4/properties/attestation/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunAttestationV3");
        assertThat(definitions.at(
                "/testSuiteRunAttestationV3/properties/childEvidenceRefs/maxItems").asInt())
                .isZero();
        assertThat(definitions.at(
                "/testSuiteRunEvidenceV3/properties/executionPurpose/const").asText())
                .isEqualTo(TestSuiteRunEvidenceV3.EXECUTION_PURPOSE);
        assertThat(definitions.at(
                "/testSuiteRunEvidenceV3/properties/verificationMode/const").asText())
                .isEqualTo(TestSuiteRunEvidenceV3.VERIFICATION_MODE);
        assertThat(definitions.at(
                "/testSuiteRunEvidenceV3/properties/coverage/allOf/1/properties/status/const")
                .asText()).isEqualTo("NOT_EVALUATED");
        assertThat(definitions.at(
                "/testSuiteRunEvidenceV3/properties/promotion/allOf/1/properties/status/const")
                .asText()).isEqualTo("BLOCKED");
        assertThat(definitions.at(
                "/testSuiteEvidenceBundleV3/properties/evidence/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunEvidenceV3");
        assertThat(definitions.at(
                "/testSuiteExecutionResponseV5/properties/evidence/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunEvidenceV4");
        assertThat(definitions.at(
                "/testSuiteExecutionResponseV5/properties/attestation/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunAttestationV4");
        assertThat(definitions.at(
                "/testSuiteRunEvidenceV4/properties/propertyCoverage/$ref").asText())
                .isEqualTo("#/$defs/testSuitePropertyCoverageVerdict");
        assertThat(definitions.at(
                "/testSuitePropertyCoverageVerdict/properties/globallyMinimal/const").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/testSuiteRunAttestationV4/properties/childEvidenceRefs/maxItems").asInt())
                .isEqualTo(96);
        assertThat(definitions.at(
                "/testSuiteEvidenceBundleV4/properties/evidence/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunEvidenceV4");
        assertThat(definitions.at(
                "/testSuiteExecutionResponseV6/properties/evidence/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunEvidenceV5");
        assertThat(definitions.at(
                "/testSuiteExecutionResponseV6/properties/attestation/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunAttestationV5");
        assertThat(definitions.at(
                "/testSuiteRunAttestationV5/properties/childEvidenceRefs/maxItems").asInt())
                .isEqualTo(272);
        assertThat(definitions.at(
                "/testSuiteMutationScoreVerdict/properties/equivalentMutantsExcluded/const")
                .asInt()).isZero();
        assertThat(definitions.at(
                "/testSuiteRunEvidenceV5/properties/executionPurpose/const").asText())
                .isEqualTo(TestSuiteRunEvidenceV5.EXECUTION_PURPOSE);
        assertThat(definitions.at(
                "/testSuiteEvidenceBundleV5/properties/evidence/$ref").asText())
                .isEqualTo("#/$defs/testSuiteRunEvidenceV5");
        assertThat(definitions.at("/testSuiteProtocol/oneOf")).hasSize(5);
        assertThat(definitions.at("/testSuiteV3/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testSuiteV3/properties/evaluationMode/const").asText())
                .isEqualTo("SCHEMA_ADMISSION");
        assertThat(definitions.at(
                "/testSuiteV3/properties/semanticCoveragePolicy/properties/requirements/maxItems")
                .asInt()).isZero();
        assertThat(definitions.at(
                "/testSuiteAdmissionExpectation/oneOf")).hasSize(2);
        assertThat(definitions.at("/testSuiteV4/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testSuiteV4/properties/evaluationMode/const").asText())
                .isEqualTo(TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION.name());
        assertThat(definitions.at("/testSuiteV4/properties/quantification/const").asText())
                .isEqualTo(TestSuiteV4.Quantification.BOUNDED_SAMPLED.name());
        assertThat(definitions.at("/testSuiteV4/properties/exhaustive/const").asBoolean())
                .isFalse();
        assertThat(definitions.at(
                "/testSuiteV4/properties/propertyTrials/items/$ref").asText())
                .isEqualTo("#/$defs/testSuitePropertyTrialRef");
        assertThat(definitions.at("/testSuiteV4/oneOf")).hasSize(2);
        assertThat(definitions.at("/testSuiteCase/properties/caseType/enum"))
                .extracting(JsonNode::asText).contains("PROPERTY");
        assertThat(definitions.at("/semanticRequirement/oneOf")).hasSize(4);
        assertThat(definitions.at("/semanticCoveragePolicy/properties/requirements/maxItems").asInt())
                .isEqualTo(1_000);
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
        assertThat(definitions.at(
                "/testMutationSuiteExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestMutationSuiteExecutionRequest.SCHEMA_VERSION);
        assertThat(definitions.at(
                "/testMutationSuiteExecutionRequest/properties/strategy/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("COLLECT_ALL", "STOP_AFTER_KILL");
        assertThat(definitions.at(
                "/testSuiteRunAttestationChild/properties/caseId/maxLength").asInt())
                .isEqualTo(512);
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
                .contains("replayDependencies", "executionServiceBindings");
        assertThat(definitions.at(
                "/effectivePlan/properties/replayDependencies/items/$ref").asText())
                .isEqualTo("#/$defs/replayDependency");
        assertThat(definitions.at("/replayDependency/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/replayDependency/properties/replayRef/pattern").asText())
                .contains("bloge-replay:");
        assertThat(definitions.at(
                "/effectivePlan/properties/executionServiceBindings/items/$ref").asText())
                .isEqualTo("#/$defs/executionServiceBinding");
        assertThat(definitions.at("/executionServiceBinding/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/executionServiceBinding/properties/service/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("TIME", "RANDOM", "UUID", "IDENTITY", "FEATURE_FLAG", "SECRET");
        assertThat(definitions.at("/executionServiceStateSnapshot/additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.at("/executionServiceStateSnapshot/required"))
                .extracting(JsonNode::asText)
                .contains("planFingerprint", "bindingSetFingerprint", "randomScopeCursors",
                        "uuidScopeCursors", "usages", "snapshotFingerprint");
        assertThat(definitions.at(
                "/executionServiceStateSnapshot/properties/randomScopeCursors/propertyNames/pattern")
                .asText()).contains("sha256:");
        assertThat(definitions.at(
                "/executionServiceStateSnapshot/properties/usages/items/$ref").asText())
                .isEqualTo("#/$defs/executionServiceStateUsage");
        assertThat(definitions.at("/testRunEvidenceV2/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testRunEvidenceV2/required")).extracting(JsonNode::asText)
                .contains("semanticResultFingerprint");
        assertThat(definitions.at(
                "/testRunEvidenceV2/properties/semanticResultFingerprint/$ref").asText())
                .isEqualTo("#/$defs/fingerprint");
        assertThat(definitions.at("/testRunEvidence/oneOf")).hasSize(2);
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
    void stabilitySchemaIsStrictBoundedAndPayloadFree() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs",
                "schemas", "resource-gateway-testing",
                "testing-control-plane-v1.schema.json"))).path("$defs");

        assertThat(definitions.at(
                "/testSuiteStabilityExecutionRequest/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at(
                "/testSuiteStabilityExecutionRequest/properties/attempts/minimum").asInt())
                .isEqualTo(TestSuiteStabilityEvidence.MIN_ATTEMPTS);
        assertThat(definitions.at(
                "/testSuiteStabilityExecutionRequest/properties/attempts/maximum").asInt())
                .isEqualTo(TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS);
        assertThat(definitions.at("/stabilityProvenanceMetadata/maxProperties").asInt())
                .isEqualTo(32);
        assertThat(definitions.at(
                "/stabilityProvenanceMetadata/additionalProperties/oneOf"))
                .extracting(node -> node.path("type").asText())
                .containsExactly("string", "number", "boolean");
        assertThat(definitions.at(
                "/testSuiteStabilityCaseObservation/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at(
                "/testSuiteStabilityCaseObservation/properties").has("input")).isFalse();
        assertThat(definitions.at(
                "/testSuiteStabilityCaseObservation/properties").has("output")).isFalse();
        assertThat(definitions.at(
                "/testSuiteStabilityEvidence/properties/attempts/minItems").asInt())
                .isEqualTo(1);
        assertThat(definitions.at(
                "/testSuiteStabilityEvidence/allOf/1/oneOf/0/properties/attempts/minItems")
                .asInt()).isEqualTo(TestSuiteStabilityEvidence.MIN_ATTEMPTS);
        assertThat(definitions.at(
                "/testSuiteStabilityEvidence/properties/attempts/maxItems").asInt())
                .isEqualTo(TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS);
        assertThat(definitions.at("/testSuiteStabilityEvidence/allOf/0/oneOf")).hasSize(4);
        assertThat(definitions.at("/testSuiteStabilityEvidence/allOf/1/oneOf")).hasSize(5);
        assertThat(definitions.at("/testSuiteStabilityAttestation/allOf/0/oneOf")).hasSize(3);
        assertThat(definitions.at("/testSuiteStabilityAttestation/allOf/1/oneOf")).hasSize(5);
        assertThat(definitions.at(
                "/testSuiteStabilityPromotionVerdict/properties"
                        + "/allSourceSuitesPromotionEligible/type").asText())
                .isEqualTo("boolean");
        assertThat(definitions.at(
                "/testSuiteStabilityPromotionVerdict/properties"
                        + "/statisticalConfidenceSatisfied/type").asText())
                .isEqualTo("boolean");
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalPolicy/properties/model/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("ZERO_INSTABILITY_EXACT_BINOMIAL",
                        "BASELINE_CONDITIONAL_EXACT_BINOMIAL",
                        "BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS");
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalAssessment/oneOf/0/properties/assumptions"
                        + "/prefixItems"))
                .extracting(node -> node.path("const").asText())
                .containsExactlyElementsOf(TestSuiteStabilityEvidence.STATISTICAL_MODEL_ASSUMPTIONS);
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalAssessment/oneOf/1/properties/assumptions"
                        + "/prefixItems"))
                .extracting(node -> node.path("const").asText())
                .containsExactlyElementsOf(
                        TestSuiteStabilityEvidence.BASELINE_CONDITIONAL_MODEL_ASSUMPTIONS);
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalAssessment/oneOf/2/properties/assumptions"
                        + "/prefixItems"))
                .extracting(node -> node.path("const").asText())
                .containsExactlyElementsOf(
                        TestSuiteStabilityEvidence.ANYTIME_VALID_MODEL_ASSUMPTIONS);
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalAssessment/properties/comparisonAttempts/maximum")
                .asInt()).isEqualTo(999);
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalAssessment/properties"
                        + "/upperInstabilityRateBps/maximum").asInt()).isEqualTo(10_000);
        assertThat(definitions.at(
                "/testSuiteStabilityStatisticalAssessment/properties"
                        + "/firstBoundaryCrossingAttempt/maximum").asInt()).isEqualTo(1_000);
        assertThat(definitions.at(
                "/testSuiteStabilityAttemptResult/properties/sourcePromotionReasons/items/pattern")
                .asText()).isEqualTo("^[A-Z][A-Z0-9_]{0,127}$");
        assertThat(definitions.at(
                "/testSuiteStabilityExecutionResponse/properties/attestation/allOf/1/properties"
                        + "/signatureStatus/const").asText()).isEqualTo("VERIFIED");
        assertThat(definitions.at(
                "/testSuiteStabilityProgress/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testSuiteStabilityProgress/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("RUNNING", "RECOVERABLE", "COMPLETED");
        assertThat(definitions.at("/testSuiteStabilityProgress/properties").has("ownerId"))
                .isFalse();
        assertThat(definitions.at("/testSuiteStabilityProgress/properties").has("leaseEpoch"))
                .isFalse();
        assertThat(definitions.at("/testSuiteStabilityProgress/properties").has("attempts"))
                .isFalse();
        assertThat(definitions.at(
                "/testSuiteStabilityProgress/properties/terminalReason/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("FIXED_HORIZON_REACHED", "E_VALUE_THRESHOLD_REACHED",
                        "MAXIMUM_HORIZON_REACHED", "CENSORING_OBSERVED");
    }

    @Test
    void asynchronousStabilityJobSchemaIsStrictStateBoundAndPayloadFree() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs",
                "schemas", "resource-gateway-testing",
                "testing-control-plane-v1.schema.json"))).path("$defs");

        JsonNode submit = definitions.path("testSuiteStabilityJobSubmitRequest");
        JsonNode cancel = definitions.path("testSuiteStabilityJobCancelRequest");
        JsonNode view = definitions.path("testSuiteStabilityJobView");
        assertThat(submit.path("additionalProperties").asBoolean()).isFalse();
        assertThat(cancel.path("additionalProperties").asBoolean()).isFalse();
        assertThat(view.path("additionalProperties").asBoolean()).isFalse();
        assertThat(submit.path("required")).extracting(JsonNode::asText)
                .containsExactly("schemaVersion", "execution", "priority", "deadlineAt");
        assertThat(submit.at("/properties/deadlineAt/pattern").asText()).endsWith("Z$");
        assertThat(cancel.at("/properties/clientRequestId/pattern").asText())
                .isEqualTo("^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}$");
        assertThat(view.at("/properties/status/enum")).extracting(JsonNode::asText)
                .containsExactly("QUEUED", "RUNNING", "CANCEL_REQUESTED", "COMMITTING",
                        "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "QUARANTINED");
        assertThat(view.path("allOf")).hasSize(2);
        assertThat(view.at("/allOf/0/oneOf")).hasSize(2);
        assertThat(view.at("/allOf/1/oneOf")).hasSize(2);
        assertThat(java.util.List.of("principal", "execution", "metadata", "leaseOwner",
                        "leaseEpoch", "cancellationFingerprint", "recordFingerprint"))
                .noneMatch(view.path("properties")::has);
        assertThat(definitions.at(
                "/testSuiteStabilityJobSubmitResponse/properties/job/$ref").asText())
                .isEqualTo("#/$defs/testSuiteStabilityJobView");
    }

    @Test
    void stabilityTrendSchemaIsStrictBoundedSignedAndExplicitlyNonCausal() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs",
                "schemas", "resource-gateway-testing",
                "testing-control-plane-v1.schema.json"))).path("$defs");

        JsonNode request = definitions.path("testSuiteStabilityTrendAnalysisRequest");
        JsonNode evidence = definitions.path("testSuiteStabilityTrendEvidence");
        JsonNode signal = definitions.path("testSuiteStabilityCorrelationSignal");
        JsonNode response = definitions.path("testSuiteStabilityTrendAnalysisResponse");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.at("/properties/maximumRuns/maximum").asInt()).isEqualTo(100);
        assertThat(evidence.path("additionalProperties").asBoolean()).isFalse();
        assertThat(evidence.at("/properties/sources/maxItems").asInt()).isEqualTo(100);
        assertThat(evidence.at("/properties/causalityStatus/const").asText())
                .isEqualTo("NOT_PROVEN");
        assertThat(signal.at("/properties/type/enum")).extracting(JsonNode::asText)
                .containsExactly("MULTI_CASE_FLAKINESS", "COINCIDENT_OUTCOME_SHIFT");
        assertThat(response.at(
                "/properties/attestation/allOf/1/properties/signatureStatus/const").asText())
                .isEqualTo("VERIFIED");
        assertThat(java.util.List.of("tenantId", "environmentId", "actorId", "payload",
                        "input", "output", "fixtureValue"))
                .noneMatch(evidence.path("properties")::has);
    }

    @Test
    void crossRetentionTrendSchemaFreezesHeadPinnedPayloadFreeTrustClosure() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs",
                "schemas", "resource-gateway-testing",
                "testing-control-plane-v1.schema.json"))).path("$defs");

        JsonNode request = definitions.path(
                "testSuiteStabilityCrossRetentionTrendAnalysisRequest");
        JsonNode observation = definitions.path("testSuiteStabilityObservation");
        JsonNode entry = definitions.path("testSuiteStabilityObservationLedgerEntry");
        JsonNode range = definitions.path("testSuiteStabilityObservationLedgerRange");
        JsonNode evidence = definitions.path("testSuiteStabilityCrossRetentionTrendEvidence");
        JsonNode attestation = definitions.path(
                "testSuiteStabilityCrossRetentionTrendAttestation");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("oneOf")).hasSize(2);
        assertThat(request.at("/properties/maximumRuns/maximum").asInt()).isEqualTo(100);
        assertThat(observation.path("additionalProperties").asBoolean()).isFalse();
        assertThat(entry.path("additionalProperties").asBoolean()).isFalse();
        assertThat(range.path("additionalProperties").asBoolean()).isFalse();
        assertThat(range.at("/properties/entries/maxItems").asInt()).isEqualTo(100);
        assertThat(evidence.path("additionalProperties").asBoolean()).isFalse();
        assertThat(evidence.at("/properties/request/$ref").asText()).isEqualTo(
                "#/$defs/testSuiteStabilityCrossRetentionTrendAnalysisRequest");
        assertThat(evidence.at("/properties/sourceOrder/const").asText())
                .isEqualTo("SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID");
        assertThat(evidence.at("/properties/causalityStatus/const").asText())
                .isEqualTo("NOT_PROVEN");
        assertThat(attestation.at("/properties/signatureStatus/const").asText())
                .isEqualTo("VERIFIED");
        assertThat(attestation.at("/properties/independentlyVerifiable/const").asBoolean())
                .isTrue();
        assertThat(java.util.List.of("tenantId", "environmentId", "actorId", "payload",
                        "input", "output", "fixtureValue"))
                .noneMatch(evidence.path("properties")::has);
    }

    @Test
    void lifecycleSchemaFreezesBoundedPinnedRetirementChainClosure() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs",
                "schemas", "resource-gateway-testing",
                "testing-control-plane-v1.schema.json"))).path("$defs");

        JsonNode request = definitions.path(
                "testSuiteStabilityObservationLedgerLifecyclePageRequest");
        JsonNode floor = definitions.path("testSuiteStabilityObservationLedgerFloor");
        JsonNode archive = definitions.path("testSuiteStabilityObservationArchiveSegment");
        JsonNode retirement = definitions.path(
                "testSuiteStabilityObservationFloorRetirement");
        JsonNode page = definitions.path(
                "testSuiteStabilityObservationLedgerLifecyclePage");
        JsonNode attestation = definitions.path(
                "testSuiteStabilityObservationLedgerLifecycleAttestation");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("oneOf")).hasSize(2);
        assertThat(request.at("/properties/maximumRetirements/maximum").asInt())
                .isEqualTo(10);
        assertThat(floor.path("additionalProperties").asBoolean()).isFalse();
        assertThat(floor.path("oneOf")).hasSize(2);
        assertThat(archive.at("/properties/retiredEntries/maxItems").asInt())
                .isEqualTo(100);
        assertThat(retirement.path("additionalProperties").asBoolean()).isFalse();
        assertThat(page.path("additionalProperties").asBoolean()).isFalse();
        assertThat(page.at("/properties/retirements/maxItems").asInt()).isEqualTo(10);
        assertThat(page.at("/properties/startingFloor/$ref").asText()).isEqualTo(
                "#/$defs/testSuiteStabilityObservationLedgerFloor");
        assertThat(page.at("/properties/currentFloor/$ref").asText()).isEqualTo(
                "#/$defs/testSuiteStabilityObservationLedgerFloor");
        assertThat(attestation.at("/properties/signatureStatus/const").asText())
                .isEqualTo("VERIFIED");
        assertThat(attestation.at("/properties/independentlyVerifiable/const").asBoolean())
                .isTrue();
        assertThat(java.util.List.of("tenantId", "environmentId", "actorId", "payload",
                        "input", "output", "fixtureValue"))
                .noneMatch(page.path("properties")::has);
    }

    @Test
    void lifecycleV2SchemaFreezesExactExternalArchiveProofClosure() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs",
                "schemas", "resource-gateway-testing",
                "testing-control-plane-v1.schema.json"))).path("$defs");

        JsonNode page = definitions.path(
                "testSuiteStabilityObservationLedgerLifecyclePageV2");
        JsonNode archiveRef = definitions.path(
                "testSuiteStabilityObservationLedgerLifecycleArchiveRef");
        JsonNode attestation = definitions.path(
                "testSuiteStabilityObservationLedgerLifecycleAttestationV2");
        JsonNode response = definitions.path(
                "testSuiteStabilityObservationLedgerLifecyclePageResponseV2");
        assertThat(page.path("additionalProperties").asBoolean()).isFalse();
        assertThat(page.at("/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchivePage.SCHEMA_VERSION);
        assertThat(page.at("/properties/retirements/maxItems").asInt()).isEqualTo(10);
        assertThat(page.at("/properties/externalArchiveReceiptSets/maxItems").asInt())
                .isEqualTo(10);
        assertThat(page.at("/properties/externalArchiveReceiptSets/items/$ref").asText())
                .isEqualTo("#/$defs/testSuiteStabilityObservationExternalArchiveReceiptSet");
        assertThat(archiveRef.path("additionalProperties").asBoolean()).isFalse();
        assertThat(archiveRef.at("/properties/receiptCount/maximum").asInt()).isEqualTo(16);
        assertThat(attestation.path("additionalProperties").asBoolean()).isFalse();
        assertThat(attestation.at("/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation
                        .SCHEMA_VERSION);
        assertThat(attestation.at("/properties/archiveRefs/maxItems").asInt()).isEqualTo(10);
        assertThat(response.at("/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse
                        .SCHEMA_VERSION);
        assertThat(response.at("/properties/page/$ref").asText())
                .isEqualTo("#/$defs/testSuiteStabilityObservationLedgerLifecyclePageV2");
        assertThat(java.util.List.of("tenantId", "environmentId", "actorId", "payload",
                        "input", "output", "fixtureValue"))
                .noneMatch(page.path("properties")::has);
    }

    @Test
    void externalArchiveSchemaFreezesStrictDeletionAdmissionReceipts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path schemaDirectory = Path.of("..", "docs", "schemas",
                "resource-gateway-testing");
        JsonNode standalone = mapper.readTree(Files.readString(schemaDirectory.resolve(
                "suite-stability-observation-external-archive-v1.schema.json")));
        JsonNode definitions = mapper.readTree(Files.readString(schemaDirectory.resolve(
                "testing-control-plane-v1.schema.json"))).path("$defs");

        JsonNode request = definitions.path(
                "testSuiteStabilityObservationExternalArchiveRequest");
        JsonNode receipt = definitions.path(
                "testSuiteStabilityObservationExternalArchiveReceipt");
        JsonNode conflict = definitions.path(
                "testSuiteStabilityObservationExternalArchiveConflictReceipt");
        JsonNode receiptSet = definitions.path(
                "testSuiteStabilityObservationExternalArchiveReceiptSet");
        JsonNode inventoryRequest = definitions.path(
                "testSuiteStabilityObservationExternalArchiveInventoryRequest");
        JsonNode inventoryItem = definitions.path(
                "testSuiteStabilityObservationExternalArchiveInventoryItem");
        JsonNode inventoryPage = definitions.path(
                "testSuiteStabilityObservationExternalArchiveInventoryPage");
        assertThat(standalone.path("oneOf")).hasSize(7);
        assertThat(standalone.at("/$defs/request/properties/retirement/$ref").asText())
                .isEqualTo("testing-control-plane-v1.schema.json#/$defs/"
                        + "testSuiteStabilityObservationFloorRetirement");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(receipt.path("additionalProperties").asBoolean()).isFalse();
        assertThat(conflict.path("additionalProperties").asBoolean()).isFalse();
        assertThat(receiptSet.path("additionalProperties").asBoolean()).isFalse();
        assertThat(inventoryRequest.path("additionalProperties").asBoolean()).isFalse();
        assertThat(inventoryItem.path("additionalProperties").asBoolean()).isFalse();
        assertThat(inventoryPage.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveRequest.SCHEMA_VERSION);
        assertThat(receipt.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReceipt.SCHEMA_VERSION);
        assertThat(conflict.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveConflictReceipt.SCHEMA_VERSION);
        assertThat(receiptSet.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReceiptSet.SCHEMA_VERSION);
        assertThat(inventoryRequest.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryRequest.SCHEMA_VERSION);
        assertThat(inventoryItem.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION);
        assertThat(inventoryPage.at("/properties/schemaVersion/const").asText()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION);
        assertThat(request.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "requestFingerprint", "trustDomain", "archiveSetId",
                        "retirement", "retainUntil", "challenge", "requestedAt", "expiresAt");
        assertThat(receipt.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "receiptFingerprint", "requestFingerprint",
                        "trustDomain", "archiveSetId", "authorityId", "failureDomain", "keyId",
                        "objectId", "retirementId", "retirementFingerprint", "segmentId",
                        "segmentFingerprint", "retentionPolicyFingerprint", "retainUntil",
                        "storedAt", "issuedAt", "expiresAt", "retentionMode",
                        "externallyDurable", "writeOnce", "deleteBeforeRetentionDenied",
                        "algorithm", "signature");
        assertThat(receiptSet.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "receiptSetId", "request", "requiredCopies",
                        "receipts", "confirmedAt", "receiptSetFingerprint");
        assertThat(conflict.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "conflictFingerprint", "requestFingerprint",
                        "trustDomain", "archiveSetId", "authorityId", "failureDomain",
                        "keyId", "objectId", "expectedObjectCommitment",
                        "observedObjectCommitment", "issuedAt", "expiresAt", "algorithm",
                        "signature");
        assertThat(inventoryRequest.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "requestFingerprint", "trustDomain", "archiveSetId",
                        "authorityId", "snapshotId", "afterObjectId", "pageSequence",
                        "maximumItems", "challenge", "requestedAt", "expiresAt");
        assertThat(inventoryItem.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "itemFingerprint", "objectId", "objectCommitment",
                        "retirementId", "retirementFingerprint", "segmentId",
                        "segmentFingerprint", "retentionPolicyFingerprint", "retainUntil",
                        "storedAt");
        assertThat(inventoryPage.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "schemaVersion", "pageFingerprint", "request", "authorityId",
                        "failureDomain", "keyId", "snapshotId", "snapshotAt",
                        "snapshotObjectCount", "snapshotRoot", "items", "nextAfterObjectId",
                        "complete", "issuedAt", "expiresAt", "algorithm", "signature");
        assertThat(receipt.at("/properties/retentionMode/const").asText())
                .isEqualTo("COMPLIANCE");
        assertThat(receipt.at("/properties/externallyDurable/const").asBoolean()).isTrue();
        assertThat(receipt.at("/properties/writeOnce/const").asBoolean()).isTrue();
        assertThat(receipt.at("/properties/deleteBeforeRetentionDenied/const").asBoolean())
                .isTrue();
        assertThat(receiptSet.at("/properties/receipts/maxItems").asInt()).isEqualTo(16);
        assertThat(inventoryRequest.at("/properties/maximumItems/maximum").asInt())
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveInventoryRequest
                        .MAXIMUM_ITEMS);
        assertThat(inventoryPage.at("/properties/items/maxItems").asInt())
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveInventoryRequest
                        .MAXIMUM_ITEMS);
        assertThat(inventoryPage.at("/properties/items/items/$ref").asText()).isEqualTo(
                "#/$defs/testSuiteStabilityObservationExternalArchiveInventoryItem");
        assertThat(inventoryPage.at("/properties/request/$ref").asText()).isEqualTo(
                "#/$defs/testSuiteStabilityObservationExternalArchiveInventoryRequest");
        assertThat(inventoryRequest.path("oneOf")).hasSize(2);
        assertThat(inventoryPage.path("oneOf")).hasSize(2);
        assertThat(java.util.List.of("tenantId", "environmentId", "actorId", "payload",
                        "input", "output", "fixtureValue", "endpoint", "credential"))
                .noneMatch(request.path("properties")::has)
                .noneMatch(receipt.path("properties")::has)
                .noneMatch(conflict.path("properties")::has)
                .noneMatch(receiptSet.path("properties")::has)
                .noneMatch(inventoryRequest.path("properties")::has)
                .noneMatch(inventoryItem.path("properties")::has)
                .noneMatch(inventoryPage.path("properties")::has);
    }

    @Test
    void evidenceSchemaFreezesOccurrenceAttemptAndEdgeCoordinates() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json"))).path("$defs");

        assertThat(definitions.at("/testRunEvidenceV2/properties/nodeTrace/items/$ref").asText())
                .isEqualTo("#/$defs/nodeTrace");
        assertThat(definitions.at("/testRunEvidenceV2/properties/edgeTrace/items/$ref").asText())
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
