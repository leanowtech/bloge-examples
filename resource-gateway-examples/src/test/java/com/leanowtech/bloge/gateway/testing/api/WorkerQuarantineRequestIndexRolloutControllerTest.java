package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexInventory;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerQuarantineRequestIndexRolloutControllerTest {

    @Test
    void authenticatesDedicatedOperationAndReturnsTheSignedEnvelope() throws Exception {
        WorkerQuarantineRequestIndexRolloutService service =
                mock(WorkerQuarantineRequestIndexRolloutService.class);
        WorkerQuarantineRequestIndexReplicaProof proof = proof();
        when(service.prove(any(), any())).thenReturn(proof);
        MockMvc mvc = mvc(service, Set.of("TEST_RUNTIME_MAINTENANCE"));

        mvc.perform(post("/api/testing/durable-state/worker-quarantines/request-index/replica-proofs")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(WorkerQuarantineRequestIndexReplicaProof.SCHEMA_VERSION))
                .andExpect(jsonPath("$.material.instanceId").value("replica-a"))
                .andExpect(jsonPath("$.material.targetMode").value("DUAL_READ_KEYED_WRITE"))
                .andExpect(jsonPath("$.seal.signature").isNotEmpty());
        verify(service).prove(any(), org.mockito.ArgumentMatchers.argThat(identity ->
                identity.actorId().equals("deployer-a")
                        && identity.projectId().equals("project-a")
                        && identity.purpose().equals("TEST_RUNTIME_MAINTENANCE")));
    }

    @Test
    void rejectsWrongPurposeAndUnknownRequestFieldsBeforeServiceEntry() throws Exception {
        WorkerQuarantineRequestIndexRolloutService service =
                mock(WorkerQuarantineRequestIndexRolloutService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_RUNTIME_MAINTENANCE"));

        mvc.perform(post("/api/testing/durable-state/worker-quarantines/request-index/replica-proofs")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/request-index/replica-proofs")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson().replace("\n}", ",\n\"instanceId\":\"forged\"\n}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
        verifyNoInteractions(service);
    }

    private static WorkerQuarantineRequestIndexReplicaProof proof() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.now();
        var inventory = new WorkerQuarantineRequestIndexInventory(
                now, 0, 0, Instant.EPOCH, Instant.EPOCH, List.of());
        var material = new WorkerQuarantineRequestIndexReplicaProof.Material(
                WorkerQuarantineRequestIndexReplicaProof.MATERIAL_SCHEMA_VERSION,
                "deployment_gate_challenge_000001", "sha256:" + "b".repeat(64),
                "replica-a", "11111111-1111-1111-1111-111111111111",
                "sha256:" + "a".repeat(64), "1.0",
                WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE,
                WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE,
                inventory, true, List.of(), now.plusSeconds(60));
        String fingerprint = ProtocolFingerprint.of(mapper, material);
        var signer = new InMemoryVisualEvidenceSigner();
        return new WorkerQuarantineRequestIndexReplicaProof(
                WorkerQuarantineRequestIndexReplicaProof.SCHEMA_VERSION,
                material, fingerprint, signer.seal(fingerprint));
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.workerQuarantineRequestIndexReplicaProofRequest.v1",
                  "challenge": "deployment_gate_challenge_000001",
                  "targetMode": "DUAL_READ_KEYED_WRITE"
                }
                """;
    }

    private static MockMvc mvc(
            WorkerQuarantineRequestIndexRolloutService service,
            Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "request-index-rollout", "tenant-a", "org-a", "project-a", "test",
                "region-a", "SERVICE", "deployer-a", "", purposes, Instant.MAX, true,
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED", "",
                Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new WorkerQuarantineRequestIndexRolloutController(service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler())
                .build();
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(records);
        }
    }
}
