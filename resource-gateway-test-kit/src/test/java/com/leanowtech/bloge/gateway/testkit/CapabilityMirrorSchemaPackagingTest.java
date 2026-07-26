package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityMirrorSchemaPackagingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testKitPackagesEveryMirrorSchemaAndTheValidatedStageZeroBaseline() throws Exception {
        for (String name : List.of(
                "artifact-provenance-v1.schema.json",
                "effect-contract-v1.schema.json",
                "capability-contract-v1.schema.json",
                "capability-snapshot-v1.schema.json",
                "capability-closure-v1.schema.json",
                "capability-lifecycle-transition-v1.schema.json",
                "capability-mirror-compatibility-v1.schema.json",
                "mirror-execution-request-v1.schema.json",
                "mirror-execution-request-v2.schema.json",
                "mirror-run-summary-v1.schema.json",
                "mirror-resolution-v1.schema.json",
                "mirror-run-evidence-v1.schema.json",
                "mirror-run-evidence-v2.schema.json",
                "mirror-run-evidence-v3.schema.json",
                "mirror-run-evidence-v4.schema.json",
                "mirror-run-evidence-v5.schema.json",
                "mirror-state-run-evidence-v1.schema.json",
                "mirror-state-run-evidence-v2.schema.json",
                "mirror-state-run-evidence-v3.schema.json",
                "mirror-state-workbook-seed-v1.schema.json",
                "mirror-state-transition-workbook-seed-v1.schema.json",
                "mirror-state-write-outcome-workbook-seed-v1.schema.json",
                "mirror-evidence-attestation-v1.schema.json",
                "mirror-evidence-attestation-v2.schema.json",
                "mirror-evidence-attestation-v3.schema.json",
                "mirror-evidence-attestation-v4.schema.json",
                "mirror-evidence-attestation-v5.schema.json",
                "mirror-evidence-bundle-v1.schema.json",
                "mirror-evidence-bundle-v2.schema.json",
                "mirror-evidence-bundle-v3.schema.json",
                "mirror-evidence-bundle-v4.schema.json",
                "mirror-evidence-bundle-v5.schema.json",
                "mirror-deployment-isolation-attestation-v1.schema.json",
                "mirror-deployment-isolation-attestation-status-v1.schema.json",
                "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json",
                "mirror-deployment-isolation-attestation-bundle-v1.schema.json",
                "mirror-deployment-isolation-agent-snapshot-v1.schema.json",
                "mirror-deployment-isolation-run-trust-v1.schema.json",
                "mirror-deployment-isolation-authority-key-set-publication-v1.schema.json",
                "read-only-shadow-authority-key-set-publication-v1.schema.json",
                "read-only-shadow-authority-key-set-page-v1.schema.json",
                "read-only-shadow-guard-policy-publication-v1.schema.json",
                "read-only-shadow-sampling-grant-publication-v1.schema.json",
                "read-only-shadow-kill-switch-publication-v1.schema.json",
                "capability-observation-v1.schema.json",
                "capability-observation-admission-v1.schema.json",
                "capability-observation-receipt-v1.schema.json",
                "capability-observation-review-request-v1.schema.json",
                "capability-observation-review-v1.schema.json",
                "capability-corpus-candidate-request-v1.schema.json",
                "capability-corpus-revision-v1.schema.json",
                "capability-corpus-publish-request-v1.schema.json",
                "capability-corpus-publication-v1.schema.json",
                "capability-corpus-trajectory-publish-request-v1.schema.json",
                "capability-corpus-trajectory-publication-v1.schema.json",
                "capability-corpus-cluster-validation-v1.schema.json",
                "capability-corpus-cluster-publish-request-v1.schema.json",
                "capability-corpus-cluster-publication-v1.schema.json",
                "fixture-mirror-corpus-bindings-v1.schema.json",
                "fixture-mirror-trajectory-bindings-v1.schema.json",
                "fixture-mirror-cluster-bindings-v1.schema.json",
                "bounded-state-expression-v1.schema.json",
                "state-model-v1.schema.json",
                "state-read-spec-v1.schema.json",
                "write-effect-spec-v1.schema.json",
                "session-state-space-v1.schema.json",
                "mirror-session-payload-v1.schema.json",
                "mirror-session-create-request-v1.schema.json",
                "mirror-session-descriptor-v1.schema.json",
                "mirror-session-command-request-v1.schema.json",
                "mirror-session-command-result-v1.schema.json",
                "mirror-session-store-generation-v1.schema.json",
                "mirror-state-write-attempt-v1.schema.json",
                "mirror-session-checkpoint-v1.schema.json",
                "mirror-session-checkpoint-attestation-v1.schema.json",
                "mirror-session-checkpoint-bundle-v1.schema.json",
                "mirror-session-recovery-result-v1.schema.json",
                "scenario-pack-v1.schema.json",
                "scenario-case-v1.schema.json",
                "case-handling-assertion-v1.schema.json",
                "scenario-handling-assertion-result-v1.schema.json",
                "compiled-scenario-rehearsal-plan-v1.schema.json",
                "scenario-rehearsal-execution-request-v1.schema.json",
                "scenario-rehearsal-batch-request-v1.schema.json",
                "scenario-rehearsal-batch-manifest-v1.schema.json",
                "scenario-rehearsal-batch-cancellation-request-v1.schema.json",
                "scenario-rehearsal-batch-job-v1.schema.json",
                "scenario-rehearsal-batch-job-v2.schema.json",
                "scenario-rehearsal-batch-job-page-v1.schema.json",
                "scenario-rehearsal-remediation-preview-request-v1.schema.json",
                "scenario-rehearsal-remediation-plan-v1.schema.json",
                "scenario-rehearsal-remediation-approval-command-v1.schema.json",
                "scenario-rehearsal-remediation-approval-v1.schema.json",
                "scenario-rehearsal-remediation-submit-command-v1.schema.json",
                "scenario-rehearsal-remediation-receipt-v1.schema.json",
                "scenario-rehearsal-batch-item-page-v1.schema.json",
                "scenario-rehearsal-batch-evidence-index-v1.schema.json",
                "scenario-rehearsal-batch-evidence-attestation-v1.schema.json",
                "scenario-rehearsal-batch-evidence-bundle-v1.schema.json",
                "scenario-case-rehearsal-result-v1.schema.json",
                "scenario-rehearsal-result-v1.schema.json",
                "scenario-rehearsal-evidence-attestation-v1.schema.json",
                "scenario-rehearsal-evidence-bundle-v1.schema.json",
                "scenario-rehearsal-legal-hold-command-v1.schema.json",
                "scenario-rehearsal-purge-command-v1.schema.json",
                "scenario-rehearsal-retention-event-v1.schema.json",
                "scenario-rehearsal-retention-state-v1.schema.json",
                "scenario-rehearsal-workbook-seed-v1.schema.json",
                "scenario-rehearsal-batch-workbook-seed-v1.schema.json",
                "read-only-shadow-comparison-v1.schema.json",
                "online-read-only-shadow-baseline-command-v1.schema.json",
                "online-read-only-shadow-candidate-command-v1.schema.json",
                "online-read-only-shadow-baseline-observation-v1.schema.json",
                "online-read-only-shadow-baseline-capability-v1.schema.json",
                "online-read-only-shadow-candidate-capability-v1.schema.json",
                "read-only-shadow-source-resolution-attestation-v2.schema.json",
                "read-only-shadow-job-lifecycle-event-v1.schema.json",
                "read-only-shadow-job-lifecycle-page-v1.schema.json",
                "scenario-pack-stage7-v1.fixture.schema.json",
                "stateful-refund-stage3-v1.fixture.schema.json")) {
            String resource = CapabilityMirrorProtocol.SCHEMA_RESOURCE_ROOT + name;
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                JsonNode schema = objectMapper.readTree(input);
                assertThat(schema.path("$schema").asText())
                        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
                assertThat(schema.path("$id").asText()).endsWith(name);
            }
        }

        assertThat(CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V1)
                .isEqualTo("resourceGateway.mirrorExecutionRequest.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V2)
                .isEqualTo("resourceGateway.mirrorExecutionRequest.v2");
        assertThat(CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_V1)
                .isEqualTo("resourceGateway.mirrorRunSummary.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE)
                .endsWith("mirror-execution-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_EXECUTION_REQUEST_V2_SCHEMA_RESOURCE)
                .endsWith("mirror-execution-request-v2.schema.json");
        assertThat(CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE)
                .endsWith("mirror-run-summary-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_PAYLOAD_V1)
                .isEqualTo("resourceGateway.mirrorSessionPayload.v1");
        assertThat(CapabilityMirrorProtocol.STATE_READ_SPEC_V1)
                .isEqualTo("resourceGateway.stateReadSpec.v1");
        assertThat(CapabilityMirrorProtocol.STATE_READ_SPEC_SCHEMA_RESOURCE)
                .endsWith("state-read-spec-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_CREATE_REQUEST_V1)
                .isEqualTo("resourceGateway.mirrorSessionCreateRequest.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_DESCRIPTOR_V1)
                .isEqualTo("resourceGateway.mirrorSessionDescriptor.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_REQUEST_V1)
                .isEqualTo("resourceGateway.mirrorSessionCommandRequest.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_RESULT_V1)
                .isEqualTo("resourceGateway.mirrorSessionCommandResult.v1");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_CHECKPOINT_BUNDLE_V1)
                .isEqualTo(
                        "resourceGateway.mirrorSessionCheckpointBundle.v1");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_RECOVERY_RESULT_V1)
                .isEqualTo(
                        "resourceGateway.mirrorSessionRecoveryResult.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_PAYLOAD_SCHEMA_RESOURCE)
                .endsWith("mirror-session-payload-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_CREATE_REQUEST_SCHEMA_RESOURCE)
                .endsWith("mirror-session-create-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.MIRROR_SESSION_DESCRIPTOR_SCHEMA_RESOURCE)
                .endsWith("mirror-session-descriptor-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_COMMAND_REQUEST_SCHEMA_RESOURCE)
                .endsWith("mirror-session-command-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_COMMAND_RESULT_SCHEMA_RESOURCE)
                .endsWith("mirror-session-command-result-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_STORE_GENERATION_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-session-store-generation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_WRITE_ATTEMPT_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-write-attempt-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_CHECKPOINT_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-session-checkpoint-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_CHECKPOINT_ATTESTATION_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-session-checkpoint-attestation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_CHECKPOINT_BUNDLE_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-session-checkpoint-bundle-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_SESSION_RECOVERY_RESULT_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-session-recovery-result-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .COMPILED_SCENARIO_REHEARSAL_PLAN_SCHEMA_RESOURCE)
                .endsWith(
                        "compiled-scenario-rehearsal-plan-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_EXECUTION_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-execution-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_MANIFEST_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-manifest-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_CANCELLATION_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-cancellation-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_JOB_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-job-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_JOB_PAGE_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-job-page-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-finalization-status-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-finalization-remediation-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-finalization-remediation-receipt-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_FINALIZATION_HEALTH_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-finalization-health-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-remediation-preview-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_REMEDIATION_PLAN_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-remediation-plan-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_COMMAND_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-remediation-approval-command-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-remediation-approval-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-remediation-submit-command-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_REMEDIATION_RECEIPT_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-remediation-receipt-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_ITEM_PAGE_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-item-page-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-evidence-index-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-evidence-attestation-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-evidence-bundle-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_CASE_REHEARSAL_RESULT_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-case-rehearsal-result-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_RESULT_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-result-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-evidence-attestation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_EVIDENCE_BUNDLE_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-evidence-bundle-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_LEGAL_HOLD_COMMAND_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-legal-hold-command-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_PURGE_COMMAND_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-purge-command-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_RETENTION_EVENT_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-retention-event-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_RETENTION_STATE_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-retention-state-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_WORKBOOK_SEED_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-workbook-seed-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_SCHEMA_RESOURCE)
                .endsWith(
                        "scenario-rehearsal-batch-workbook-seed-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE)
                .endsWith("mirror-evidence-bundle-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_RUN_EVIDENCE_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-run-evidence-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_RUN_EVIDENCE_V2_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-run-evidence-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_RUN_EVIDENCE_V3_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-run-evidence-v3.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_WORKBOOK_SEED_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-workbook-seed-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_TRANSITION_WORKBOOK_SEED_V1)
                .isEqualTo(
                        "resourceGateway.mirrorStateTransitionWorkbookSeed.v1");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_TRANSITION_WORKBOOK_SEED_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-transition-workbook-seed-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED_V1)
                .isEqualTo(
                        "resourceGateway.mirrorStateWriteOutcomeWorkbookSeed.v1");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED_SCHEMA_RESOURCE)
                .endsWith(
                        "mirror-state-write-outcome-workbook-seed-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_EVIDENCE_BUNDLE_V3_SCHEMA_RESOURCE)
                .endsWith("mirror-evidence-bundle-v3.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_EVIDENCE_BUNDLE_V4_SCHEMA_RESOURCE)
                .endsWith("mirror-evidence-bundle-v4.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_EVIDENCE_BUNDLE_V5_SCHEMA_RESOURCE)
                .endsWith("mirror-evidence-bundle-v5.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_DEPLOYMENT_ISOLATION_RUN_TRUST_SCHEMA_RESOURCE)
                .endsWith("mirror-deployment-isolation-run-trust-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.CAPABILITY_OBSERVATION_SCHEMA_RESOURCE)
                .endsWith("capability-observation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_ADMISSION_SCHEMA_RESOURCE)
                .endsWith("capability-observation-admission-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_RECEIPT_SCHEMA_RESOURCE)
                .endsWith("capability-observation-receipt-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_REVIEW_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-observation-review-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_REVIEW_SCHEMA_RESOURCE)
                .endsWith("capability-observation-review-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_CANDIDATE_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-candidate-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE)
                .endsWith("capability-corpus-revision-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_PUBLISH_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-publish-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE)
                .endsWith("capability-corpus-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-trajectory-publish-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-trajectory-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_CLUSTER_VALIDATION_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-cluster-validation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_CLUSTER_PUBLISH_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-cluster-publish-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_CLUSTER_PUBLICATION_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-cluster-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_CLUSTER_FIXTURE_RESOURCE)
                .endsWith(
                        "capability-corpus-cluster-stage2-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE)
                .endsWith("fixture-mirror-corpus-bindings-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CORPUS_BINDINGS_FIXTURE_RESOURCE)
                .endsWith("fixture-mirror-corpus-bindings-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_TRAJECTORY_BINDINGS_SCHEMA_RESOURCE)
                .endsWith(
                        "fixture-mirror-trajectory-bindings-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_TRAJECTORY_BINDINGS_FIXTURE_RESOURCE)
                .endsWith(
                        "fixture-mirror-trajectory-bindings-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CLUSTER_BINDINGS_V1)
                .isEqualTo(
                        "resourceGateway.fixtureMirrorClusterBindings.v1");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CLUSTER_BINDINGS_SCHEMA_RESOURCE)
                .endsWith(
                        "fixture-mirror-cluster-bindings-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CLUSTER_BINDINGS_FIXTURE_RESOURCE)
                .endsWith(
                        "fixture-mirror-cluster-bindings-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowComparison.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-comparison-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V2)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowComparison.v2");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V2_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-comparison-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V3)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowComparison.v3");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V3_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-comparison-v3.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_REQUEST_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowJobRequest.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-job-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_REQUEST_V2)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowJobRequest.v2");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_REQUEST_V2_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-job-request-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_BINDING_REGISTRATION_REQUEST_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowSourceBindingRegistrationRequest.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_BINDING_REGISTRATION_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-source-binding-registration-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_BINDING_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowSourceBinding.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_BINDING_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-source-binding-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowSourceResolutionAttestation.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-source-resolution-attestation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V2)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowSourceResolutionAttestation.v2");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V2_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-source-resolution-attestation-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_COMMAND_V1)
                .isEqualTo(
                        "resourceGateway.onlineReadOnlyShadowBaselineCommand.v1");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_COMMAND_SCHEMA_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-baseline-command-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_CANDIDATE_COMMAND_V1)
                .isEqualTo(
                        "resourceGateway.onlineReadOnlyShadowCandidateCommand.v1");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_CANDIDATE_COMMAND_SCHEMA_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-candidate-command-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_V1)
                .isEqualTo(
                        "resourceGateway.onlineReadOnlyShadowBaselineObservation.v1");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_SCHEMA_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-baseline-observation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_CAPABILITY_V1)
                .isEqualTo(
                        "resourceGateway.onlineReadOnlyShadowBaselineCapability.v1");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_CAPABILITY_SCHEMA_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-baseline-capability-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_CANDIDATE_CAPABILITY_V1)
                .isEqualTo(
                        "resourceGateway.onlineReadOnlyShadowCandidateCapability.v1");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_CANDIDATE_CAPABILITY_SCHEMA_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-candidate-capability-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_COMPATIBILITY_V1)
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineCompatibilityFixture
                                .SCHEMA_VERSION);
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_BASELINE_FIXTURE_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-baseline-stage1-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_SOURCE_RESOLUTION_COMPATIBILITY_V1)
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionCompatibilityFixture
                                .SCHEMA_VERSION);
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_SOURCE_RESOLUTION_FIXTURE_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-source-resolution-stage1-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_WORKER_COMPATIBILITY_V1)
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .SCHEMA_VERSION);
        assertThat(CapabilityMirrorProtocol
                .ONLINE_READ_ONLY_SHADOW_WORKER_FIXTURE_RESOURCE)
                .endsWith(
                        "online-read-only-shadow-worker-stage1-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .onlineReadOnlyShadowWorkerCompatibilityFixture()
                .verify()
                .verified())
                .isTrue();
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowJob.v1");
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_OBSERVATION_ADMISSION_REQUEST_V1)
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeObservationAdmission.v1");
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_INBOX_ADMISSION_V1)
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeInboxAdmission.v1");
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_INBOX_ENTRY_V1)
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeInboxEntry.v1");
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_EVENT_V1)
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeInboxLifecycle.v1");
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_PAGE_V1)
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeInboxLifecyclePage.v1");
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_ADMISSION_REQUEST_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_INBOX_ADMISSION_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_INBOX_ENTRY_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_EVENT_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_PAGE_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-job-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowJobLifecycleEvent.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-job-lifecycle-event-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_V1)
                .isEqualTo(
                        "resourceGateway.readOnlyShadowJobLifecyclePage.v1");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_SCHEMA_RESOURCE)
                .endsWith(
                        "read-only-shadow-job-lifecycle-page-v1.schema.json");
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_V2_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_V3_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_REQUEST_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(CapabilityMirrorProtocol.fixtureMirrorClusterBindingsFixture()
                .path("schemaVersion").asText())
                .isEqualTo(
                        CapabilityMirrorProtocol
                                .FIXTURE_MIRROR_CLUSTER_BINDINGS_V1);

        JsonNode baseline = CapabilityMirrorProtocol.compatibilityBaseline();
        assertThat(baseline.path("schemaVersion").asText())
                .isEqualTo(CapabilityMirrorProtocol.COMPATIBILITY_V1);
        assertThat(baseline.path("requiredObjects").size()).isEqualTo(6);
        assertThat(baseline.path("requiredFeatures").size()).isEqualTo(6);
        assertThat(baseline.path("deferredFeatures").size()).isEqualTo(3);
    }

    @Test
    void callersCannotMutateTheProcessWideCompatibilityBaseline() {
        JsonNode first = CapabilityMirrorProtocol.compatibilityBaseline();
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("protocol", "changed");

        assertThat(CapabilityMirrorProtocol.compatibilityBaseline().path("protocol").asText())
                .isEqualTo(CapabilityMirrorProtocol.INTEGRATION_PROTOCOL);
    }

    @Test
    void packagedExecutionSchemasAcceptClosedPayloadFreeExamples() {
        com.fasterxml.jackson.databind.node.ObjectNode request = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V1)
                .put("requestId", "request-1")
                .put("planId", "plan-1")
                .put("expectedPlanFingerprint", fingerprint('1'));
        request.set("context", objectMapper.createObjectNode().put("customerId", "C-1"));
        com.fasterxml.jackson.databind.node.ObjectNode statefulRequest =
                request.deepCopy();
        statefulRequest.put(
                "schemaVersion",
                CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V2);
        statefulRequest.putObject("sessionBinding")
                .put("sessionId", "refund-session-1")
                .put("expectedStateFingerprint", fingerprint('4'));
        com.fasterxml.jackson.databind.node.ObjectNode summary = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_V1)
                .put("runId", "run-1")
                .put("requestId", "request-1")
                .put("planId", "plan-1")
                .put("planFingerprint", fingerprint('1'))
                .put("requestContextFingerprint", fingerprint('2'))
                .put("status", "PASSED")
                .put("evidenceClass", "EXPLORATORY")
                .put("startedAt", "2026-07-23T00:00:00Z")
                .put("completedAt", "2026-07-23T00:00:01Z")
                .put("durationMs", 1000)
                .put("nodeTraceCount", 2)
                .put("edgeTraceCount", 1)
                .put("resolutionCount", 1)
                .put("evidenceBundleFingerprint", fingerprint('3'));
        summary.set("scope", objectMapper.createObjectNode()
                .put("tenantId", "tenant-a")
                .put("organizationId", "org-a")
                .put("projectId", "support")
                .put("environmentId", "test")
                .put("region", "sg"));

        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(request,
                CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE,
                "invalid-request")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                statefulRequest,
                CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V2_SCHEMA_RESOURCE,
                "invalid-stateful-request")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(summary,
                CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE,
                "invalid-summary")).doesNotThrowAnyException();

        summary.put("output", "payload-must-not-fit");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(summary,
                CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE,
                "invalid-summary")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void packagesOneFixedIndependentlyVerifiableStageOneEvidenceFixture() {
        MirrorEvidenceCompatibilityFixture fixture =
                CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture();

        MirrorEvidenceVerifier.VerificationResult verified = new MirrorEvidenceVerifier()
                .verify(fixture.bundle(), fixture.verificationKey());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.runId()).isEqualTo("mirror-run-schema");
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.bundle())
                .put("bundleFingerprint", "changed");
        assertThat(new MirrorEvidenceVerifier().verify(
                CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture().bundle(),
                fixture.verificationKey()).verified()).isTrue();
    }

    @Test
    void packagesOneFixedThreeAuthoritySourceResolutionFixture() {
        ReadOnlyShadowSourceResolutionCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .readOnlyShadowSourceResolutionCompatibilityFixture();

        assertThat(fixture.verify().verified()).isTrue();
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_RESOLUTION_COMPATIBILITY_V1)
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionCompatibilityFixture
                                .SCHEMA_VERSION);
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_SOURCE_RESOLUTION_FIXTURE_RESOURCE)
                .endsWith(
                        "read-only-shadow-source-resolution-stage1-v1.fixture.json");
    }

    @Test
    void packagesOneFixedPublicOnlyAuthoritativeOutcomeFixture() {
        AuthoritativeOutcomeObservationCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeObservationCompatibilityFixture();

        assertThat(fixture.verify().verified()).isTrue();
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_OBSERVATION_COMPATIBILITY_V1)
                .isEqualTo(
                        AuthoritativeOutcomeObservationCompatibilityFixture
                                .SCHEMA_VERSION);
        assertThat(CapabilityMirrorProtocol
                .AUTHORITATIVE_OUTCOME_OBSERVATION_FIXTURE_RESOURCE)
                .endsWith(
                        "authoritative-outcome-observation-stage1-v1.fixture.json");
    }

    @Test
    void packagesOneFixedPublicOnlyOnlineBaselineFixture() {
        OnlineReadOnlyShadowBaselineCompatibilityFixture
                fixture =
                CapabilityMirrorProtocol
                        .onlineReadOnlyShadowBaselineCompatibilityFixture();

        var verified = fixture.verify();

        assertThat(verified.verified())
                .as(verified.reasonCode())
                .isTrue();
        assertThat(verified.zeroWrite()).isTrue();
        assertThat(verified.observationFingerprint())
                .isEqualTo(
                        fixture.expectedObservationRef()
                                .path("fingerprint")
                                .asText());
    }

    @Test
    void packagesOneFixedPublicOnlyOnlinePairedSourceFixture() {
        var fixture = CapabilityMirrorProtocol
                .onlineReadOnlyShadowSourceResolutionCompatibilityFixture();

        var verified = fixture.verify();

        assertThat(verified.verified())
                .as(verified.reasonCode())
                .isTrue();
        assertThat(verified.zeroWrite()).isTrue();
        assertThat(verified.attestationFingerprint())
                .isEqualTo(
                        fixture.expectedAttestationRef()
                                .path("fingerprint")
                                .asText());
    }

    @Test
    void packagesOneFixedThresholdSignedIsolationAuthorityFixture() {
        var fixture = CapabilityMirrorProtocol
                .mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture();

        var verified = new MirrorDeploymentIsolationAuthorityKeySetVerifier().verify(
                fixture.publication(), fixture.expectedBinding(), fixture.bootstrapRoots(),
                null, fixture.verificationTime());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.authorityKeys()).hasSize(1);
        assertThat(CapabilityMirrorProtocol
                .MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_SCHEMA_RESOURCE)
                .endsWith("authority-key-set-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_AUTHORITY_KEY_SET_SCHEMA_RESOURCE)
                .endsWith("read-only-shadow-authority-key-set-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PAGE_SCHEMA_RESOURCE)
                .endsWith("read-only-shadow-authority-key-set-page-v1.schema.json");
    }

    @Test
    void packagesOneFixedSignedPayloadFreeObservationFixture() {
        CapabilityObservationCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();

        CapabilityObservationVerifier.VerificationResult verified =
                new CapabilityObservationVerifier().verify(
                        fixture.observation(),
                        fixture.verificationKey(),
                        fixture.expectedScope(),
                        fixture.verificationTime());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.observationId())
                .isEqualTo("support-refund-observation-0001");
        assertThat(fixture.observation().toString())
                .doesNotContain("customer-123")
                .doesNotContain("requestBody")
                .doesNotContain("responseBody");
    }

    @Test
    void packagesOneFixedPayloadFreeCorpusGovernanceFixture() {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();

        CapabilityCorpusVerifier.VerificationResult verified =
                new CapabilityCorpusVerifier().verify(fixture);

        assertThat(verified.verified()).isTrue();
        assertThat(verified.corpusId()).isEqualTo("support-refund-corpus");
        assertThat(fixture.revision().toString())
                .doesNotContain("customer-123")
                .doesNotContain("requestBody")
                .doesNotContain("responseBody");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_FIXTURE_RESOURCE)
                .endsWith("capability-corpus-stage2-v1.fixture.json");
    }

    @Test
    void packagesOneFixedPayloadFreeRecordedClusterFixture() {
        CapabilityCorpusClusterCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .capabilityCorpusClusterCompatibilityFixture();

        CapabilityCorpusClusterVerifier.VerificationResult verified =
                new CapabilityCorpusClusterVerifier().verify(fixture);

        assertThat(verified.verified()).isTrue();
        assertThat(verified.clusterId())
                .isEqualTo("support-refund-customer-cluster");
        assertThat(fixture.publication().toString())
                .doesNotContain("customer-123")
                .doesNotContain("requestBody")
                .doesNotContain("responseBody");
    }

    @Test
    void packagedAttestationControlSchemasAcceptAtomicStatusBundleAndRevocation() {
        JsonNode attestation = CapabilityMirrorProtocol
                .mirrorDeploymentIsolationCompatibilityFixture().attestation();
        JsonNode deployment = attestation.at("/material/deployment");
        var scope = objectMapper.createObjectNode()
                .put("tenantId", "tenant-a")
                .put("organizationId", "org-a")
                .put("projectId", "tool-studio")
                .put("environmentId", "staging")
                .put("region", "ap-southeast-1");
        var authorityRef = objectMapper.createObjectNode()
                .put("kind", "DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET")
                .put("id", "mirror-isolation-authorities:staging")
                .put("revision", 3)
                .put("fingerprint", fingerprint('a'));
        var attestationRef = objectMapper.createObjectNode()
                .put("kind", "DEPLOYMENT_ISOLATION_ATTESTATION")
                .put("id", attestation.at("/material/attestationId").asText())
                .put("revision", attestation.at("/material/revision").asLong())
                .put("fingerprint", attestation.path("attestationFingerprint").asText());
        var material = objectMapper.createObjectNode()
                .put("statusRevision", 1)
                .put("previousStatusFingerprint", "")
                .put("state", "ACTIVE")
                .put("reason", "ACCEPTED")
                .put("effectiveAt", "2026-07-23T00:00:11Z");
        material.set("scope", scope.deepCopy());
        material.set("deployment", deployment.deepCopy());
        material.set("authorityKeySetRef", authorityRef.deepCopy());
        material.set("attestationRef", attestationRef);
        var status = objectMapper.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_V1)
                .put("statusFingerprint", fingerprint('b'));
        status.set("material", material);
        var bundle = objectMapper.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_V1)
                .put("bundleFingerprint", fingerprint('c'));
        bundle.set("scope", scope);
        bundle.set("authorityKeySetRef", authorityRef);
        bundle.set("attestation", attestation);
        bundle.set("status", status);
        JsonNode authority = CapabilityMirrorProtocol
                .mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture().publication();
        var exactAuthorityRef = objectMapper.createObjectNode()
                .put("kind", "DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET")
                .put("id", authority.at("/material/keySetId").asText())
                .put("revision", authority.at("/material/generation").asLong())
                .put("fingerprint", authority.path("publicationFingerprint").asText());
        bundle.set("authorityKeySetRef", exactAuthorityRef.deepCopy());
        status.withObject("/material").set("authorityKeySetRef", exactAuthorityRef);
        var snapshot = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_V1)
                .put("snapshotFingerprint", fingerprint('d'))
                .put("cacheGeneration", 1)
                .put("refreshedAt", "2026-07-23T00:00:11Z")
                .put("validUntil", "2026-07-23T00:05:11Z");
        snapshot.set("authorityPublication", authority);
        snapshot.set("attestationBundle", bundle);
        var revocation = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_V1)
                .put("attestationRevision", attestation.at("/material/revision").asLong())
                .put("attestationFingerprint",
                        attestation.path("attestationFingerprint").asText())
                .put("expectedStatusRevision", 1)
                .put("expectedStatusFingerprint", fingerprint('b'))
                .put("reason", "SECURITY_INCIDENT");

        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(status,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_SCHEMA_RESOURCE,
                "invalid-status")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(bundle,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_SCHEMA_RESOURCE,
                "invalid-bundle")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(revocation,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_SCHEMA_RESOURCE,
                "invalid-revocation")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(snapshot,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_SCHEMA_RESOURCE,
                "invalid-agent-snapshot")).doesNotThrowAnyException();

        revocation.put("reason", "ACCEPTED");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(revocation,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_SCHEMA_RESOURCE,
                "invalid-revocation")).isInstanceOf(IllegalArgumentException.class);
        snapshot.putNull("authorityPublication");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(snapshot,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_SCHEMA_RESOURCE,
                "invalid-agent-snapshot")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
