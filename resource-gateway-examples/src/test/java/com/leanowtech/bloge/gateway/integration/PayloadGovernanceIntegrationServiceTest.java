package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.ConfiguredVisualPayloadGovernancePolicy;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadGovernanceIntegrationServiceTest {

    private ToolStudioIntegrationService service;
    private InMemoryVisualGraphRunRepository runs;

    @BeforeEach
    void setUp() {
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        var policy = new ConfiguredVisualPayloadGovernancePolicy("customer-data-policy", "3",
                "CONFIDENTIAL", Set.of("case-reviewers"),
                Map.of("CONFIDENTIAL", Duration.ofDays(7)));
        var payloads = new InMemoryVisualRunPayloadRepository(policy, signer);
        runs = new InMemoryVisualGraphRunRepository(signer, payloads);
        runs.create(record("run-1"));
        service = new ToolStudioIntegrationService(null, null, null, runs);
    }

    @Test
    void replayRequiresBothClassificationClearanceAndPolicyGroups() {
        assertProblem(() -> service.replay("run-1", context("PAYLOAD_REPLAY", "INTERNAL", Set.of("case-reviewers"))),
                403, "RG.INTEGRATION.PAYLOAD_CLEARANCE_REQUIRED");
        assertProblem(() -> service.replay("run-1", context("PAYLOAD_REPLAY", "CONFIDENTIAL", Set.of())),
                403, "RG.INTEGRATION.PAYLOAD_GROUP_REQUIRED");

        IntegrationEnvelope<PayloadReplayBundle> replay = service.replay("run-1",
                context("PAYLOAD_REPLAY", "CONFIDENTIAL", Set.of("case-reviewers")));

        assertThat(replay.payload().schemaVersion()).isEqualTo(PayloadReplayBundle.SCHEMA_VERSION);
        assertThat(replay.payload().context()).containsEntry("customerId", "customer-42");
        assertThat(replay.payload().retention().descriptor().classification()).isEqualTo("CONFIDENTIAL");
        assertThat(replay.payload().retention().state()).isEqualTo(VisualRunPayloadStatus.AVAILABLE);
    }

    @Test
    void legalHoldBlocksPurgeAndReleaseRestoresPolicyLifecycle() {
        IntegrationRequestContext legal = context("LEGAL_HOLD", "RESTRICTED", Set.of("case-reviewers"));
        IntegrationRequestContext admin = context("PAYLOAD_RETENTION_ADMIN", "RESTRICTED",
                Set.of("case-reviewers"));

        PayloadRetentionView held = service.placePayloadHold("run-1",
                new PayloadLifecycleCommand("", "request-1", "case-9", "litigation"), legal).payload();
        assertThat(held.status().state()).isEqualTo(VisualRunPayloadStatus.LEGAL_HOLD);
        PayloadRetentionView replayed = service.placePayloadHold("run-1",
                new PayloadLifecycleCommand("", "request-1", "case-9", "litigation"), legal).payload();
        assertThat(replayed.status().revision()).isEqualTo(held.status().revision());
        assertThat(replayed.events()).hasSize(2);
        assertProblem(() -> service.placePayloadHold("run-1",
                        new PayloadLifecycleCommand("", "request-1", "case-9", "different reason"), legal),
                409, "RG.INTEGRATION.PAYLOAD_LIFECYCLE_CONFLICT");
        assertProblem(() -> service.purgePayload("run-1",
                        new PayloadLifecycleCommand("", "request-2", "", "retention request"), admin),
                409, "RG.INTEGRATION.PAYLOAD_LIFECYCLE_CONFLICT");

        PayloadRetentionView released = service.releasePayloadHold("run-1", "case-9",
                new PayloadLifecycleCommand("", "request-3", "", "case closed"), legal).payload();
        assertThat(released.status().state()).isEqualTo(VisualRunPayloadStatus.AVAILABLE);
        assertThat(released.events()).extracting(event -> event.type()).containsExactly(
                "CAPTURED", "HOLD_PLACED", "HOLD_RELEASED");

        PayloadRetentionView purged = service.purgePayload("run-1",
                new PayloadLifecycleCommand("", "request-4", "", "retention ticket 42"), admin).payload();
        assertThat(purged.status().state()).isEqualTo(VisualRunPayloadStatus.PURGED);
        assertProblem(() -> service.replay("run-1",
                        context("PAYLOAD_REPLAY", "RESTRICTED", Set.of("case-reviewers"))),
                410, "RG.INTEGRATION.PAYLOAD_NOT_AVAILABLE");

        RunEvidenceBundle evidence = service.runEvidence("run-1",
                context("GOVERNANCE_EVIDENCE_INGESTION", "PUBLIC", Set.of())).payload();
        assertThat(evidence.retention().state()).isEqualTo(VisualRunPayloadStatus.PURGED);
        assertThat(evidence.retention().payloadFingerprint()).startsWith("sha256:");
        assertThat(evidence.manifest().signatureStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void retentionMetadataRemainsScopedAndDoesNotExposeMissingGroupNames() {
        PayloadRetentionView view = service.payloadRetention("run-1",
                context("GOVERNANCE_EVIDENCE_INGESTION", "PUBLIC", Set.of())).payload();
        assertThat(view.status().tenantId()).isEqualTo("tenant-a");
        assertThat(view.events()).singleElement().satisfies(event -> {
            assertThat(event.evidenceSeal().signed()).isTrue();
            assertThat(event.payloadFingerprint()).startsWith("sha256:");
        });

        IntegrationRequestContext otherTenant = new IntegrationRequestContext("tenant-b", "org-a", "project-a",
                "prod", "local", "WORKLOAD", "aneke", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-2",
                Set.of(), "RESTRICTED", "");
        assertProblem(() -> service.payloadRetention("run-1", otherTenant),
                404, "RG.INTEGRATION.RUN_NOT_FOUND");
    }

    @Test
    void lifecycleCommandRequiresAnIdempotencyKey() {
        assertProblem(() -> service.placePayloadHold("run-1",
                        new PayloadLifecycleCommand("", "", "case-9", "litigation"),
                        context("LEGAL_HOLD", "RESTRICTED", Set.of("case-reviewers"))),
                400, "RG.INTEGRATION.PAYLOAD_LIFECYCLE_COMMAND_INVALID");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    IntegrationProblem problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.status()).isEqualTo(status);
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.details().toString()).doesNotContain("case-reviewers");
                });
    }

    private static IntegrationRequestContext context(String purpose, String clearance, Set<String> groups) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "prod", "local", "WORKLOAD",
                "aneke", "", purpose, "corr-1", groups, clearance, "");
    }

    private static VisualGraphRunRecord record(String runId) {
        GraphDraft draft = new GraphDraft("", "draft-1", 1, "visualPolicy", "tenant-a", "local", "prod",
                "", SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("response", ""));
        VisualGraphRunResponse response = new VisualGraphRunResponse(true, true, true, "visualPolicy", "response",
                Map.of("decision", "approved"), Map.of("response", Map.of("decision", "approved")),
                Map.of("response", "COMPLETED"), 10, Map.of("response", 5L), List.of(), List.of(), null, null,
                "graph visualPolicy {}");
        return VisualGraphRunRecord.storedDraft(draft,
                Map.of("customerId", "customer-42", "apiToken", "secret-token"), response)
                .withIdentity(runId, null);
    }
}
