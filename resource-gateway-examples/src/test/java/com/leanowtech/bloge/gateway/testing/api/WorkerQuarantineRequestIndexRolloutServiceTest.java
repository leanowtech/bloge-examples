package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexInventory;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerQuarantineRequestIndexRolloutServiceTest {

    private static final String CHALLENGE = "deployment_gate_challenge_000001";
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String STARTUP = "11111111-1111-1111-1111-111111111111";

    private ObjectMapper objectMapper;
    private DatabaseDurableWorkerQuarantineControlPlane controlPlane;
    private InMemoryVisualEvidenceSigner signer;
    private List<TestSecurityEvent> events;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        controlPlane = mock(DatabaseDurableWorkerQuarantineControlPlane.class);
        signer = new InMemoryVisualEvidenceSigner();
        events = new ArrayList<>();
    }

    @Test
    void signsChallengeArtifactProcessModeAndDatabaseInventoryAsOneMaterial() {
        Instant observedAt = Instant.now();
        when(controlPlane.requestIndexMode())
                .thenReturn(WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE);
        when(controlPlane.requestIndexInventory()).thenReturn(inventory(observedAt, 0, 0));
        WorkerQuarantineRequestIndexRolloutService service = service(signer, repository(events));

        WorkerQuarantineRequestIndexReplicaProof proof = service.prove(
                request(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE), identity());

        assertThat(proof.material()).satisfies(material -> {
            assertThat(material.challenge()).isEqualTo(CHALLENGE);
            assertThat(material.instanceId()).isEqualTo("replica-a");
            assertThat(material.startupId()).isEqualTo(STARTUP);
            assertThat(material.artifactFingerprint()).isEqualTo(ARTIFACT);
            assertThat(material.currentMode())
                    .isEqualTo(WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE);
            assertThat(material.targetMode())
                    .isEqualTo(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE);
            assertThat(material.transitionAllowed()).isTrue();
            assertThat(material.blockers()).isEmpty();
            assertThat(material.expiresAt()).isEqualTo(observedAt.plusSeconds(60));
        });
        assertThat(proof.materialFingerprint())
                .isEqualTo(ProtocolFingerprint.of(objectMapper, proof.material()));
        assertThat(signer.verify(proof.seal(), proof.materialFingerprint()).valid()).isTrue();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType())
                    .isEqualTo("DURABLE_WORKER_REQUEST_INDEX_REPLICA_PROOF");
            assertThat(event.reasonCode())
                    .isEqualTo("RG.TEST.REQUEST_INDEX_REPLICA_PROOF_ISSUED");
            assertThat(event.facts()).doesNotContainKeys("challenge", "tenantId", "projectId");
        });
    }

    @Test
    void returnsSignedBlockersUntilEveryLegacyRowExpiresBeforeKeyedOnly() {
        Instant observedAt = Instant.now();
        when(controlPlane.requestIndexMode())
                .thenReturn(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE);
        when(controlPlane.requestIndexInventory()).thenReturn(inventory(observedAt, 3, 2));
        WorkerQuarantineRequestIndexRolloutService service = service(signer, repository(events));

        WorkerQuarantineRequestIndexReplicaProof proof = service.prove(
                request(WorkerQuarantineRequestIndexMode.KEYED_ONLY), identity());

        assertThat(proof.material().transitionAllowed()).isFalse();
        assertThat(proof.material().blockers()).containsExactly("LIVE_LEGACY_ROWS_PRESENT");
        assertThat(proof.material().inventory().liveLegacyRows()).isEqualTo(3);
        assertThat(signer.verify(proof.seal(), proof.materialFingerprint()).valid()).isTrue();
    }

    @Test
    void reportsWrongPredecessorAndConflictingRowsWithoutPretendingReadiness() {
        Instant observedAt = Instant.now();
        when(controlPlane.requestIndexMode())
                .thenReturn(WorkerQuarantineRequestIndexMode.KEYED_ONLY);
        when(controlPlane.requestIndexInventory()).thenReturn(inventory(observedAt, 0, 2));
        WorkerQuarantineRequestIndexRolloutService service = service(signer, repository(events));

        WorkerQuarantineRequestIndexReplicaProof proof = service.prove(
                request(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE), identity());

        assertThat(proof.material().blockers()).containsExactly(
                "CURRENT_MODE_NOT_PREDECESSOR", "LIVE_KEYED_ROWS_PRESENT");
        assertThat(proof.material().transitionAllowed()).isFalse();
    }

    @Test
    void rejectsInvalidChallengeAndUnauthorizedIdentityBeforeInventoryRead() {
        WorkerQuarantineRequestIndexRolloutService service = service(signer, repository(events));
        WorkerQuarantineRequestIndexReplicaProofRequest invalid =
                new WorkerQuarantineRequestIndexReplicaProofRequest(
                        WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION,
                        "too-short", WorkerQuarantineRequestIndexMode.KEYED_ONLY);
        IntegrationRequestContext unprivileged = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "region-a", "SERVICE",
                "deployer-a", "", "TEST_RUNTIME_MAINTENANCE", "correlation-a",
                Set.of("wrong-group"), "RESTRICTED", "");

        assertThatThrownBy(() -> service.prove(invalid, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.TEST.REQUEST_INDEX_REPLICA_PROOF_REQUEST_INVALID");
        assertThatThrownBy(() -> service.prove(
                request(WorkerQuarantineRequestIndexMode.KEYED_ONLY), unprivileged))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.TEST.REQUEST_INDEX_REPLICA_PROOF_FORBIDDEN");
        org.mockito.Mockito.verifyNoInteractions(controlPlane);
    }

    @Test
    void failsClosedWhenSigningOrAuditCannotComplete() {
        Instant observedAt = Instant.now();
        when(controlPlane.requestIndexMode())
                .thenReturn(WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE);
        when(controlPlane.requestIndexInventory()).thenReturn(inventory(observedAt, 0, 0));
        VisualEvidenceSigner failedSigner = mock(VisualEvidenceSigner.class);
        when(failedSigner.available()).thenReturn(true);
        when(failedSigner.seal(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("private provider detail"));
        WorkerQuarantineRequestIndexRolloutService signingFailure =
                service(failedSigner, repository(events));
        TestSecurityEventRepository failedAudit = new TestSecurityEventRepository() {
            @Override
            public TestSecurityEvent append(TestSecurityEvent event) {
                throw new IllegalStateException("database credential detail");
            }

            @Override
            public List<TestSecurityEvent> recent(int limit) {
                return List.of();
            }
        };
        WorkerQuarantineRequestIndexRolloutService auditFailure = service(signer, failedAudit);

        assertUnavailable(signingFailure);
        assertUnavailable(auditFailure);
    }

    @Test
    void rejectsNonCanonicalGenerationExpiryAndDuplicateBlockersBeforeSigning() {
        Instant observedAt = Instant.now();
        var generation = new WorkerQuarantineRequestIndexInventory.KeyGeneration(
                "request-key-v1", 1, observedAt);

        assertThatThrownBy(() -> new WorkerQuarantineRequestIndexInventory(
                observedAt, 0, 1, Instant.EPOCH, observedAt, List.of(generation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry");

        WorkerQuarantineRequestIndexInventory empty = inventory(observedAt, 0, 0);
        assertThatThrownBy(() -> new WorkerQuarantineRequestIndexReplicaProof.Material(
                WorkerQuarantineRequestIndexReplicaProof.MATERIAL_SCHEMA_VERSION,
                CHALLENGE, "sha256:" + "b".repeat(64), "replica-a", STARTUP,
                ARTIFACT, ToolStudioResourceGatewayProtocol.VERSION,
                WorkerQuarantineRequestIndexMode.KEYED_ONLY,
                WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE,
                empty, false,
                List.of("CURRENT_MODE_NOT_PREDECESSOR", "CURRENT_MODE_NOT_PREDECESSOR"),
                observedAt.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");
    }

    private void assertUnavailable(WorkerQuarantineRequestIndexRolloutService service) {
        assertThatThrownBy(() -> service.prove(
                request(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE), identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    IntegrationProblemException problem = (IntegrationProblemException) failure;
                    assertThat(problem.problem().code())
                            .isEqualTo("RG.TEST.REQUEST_INDEX_REPLICA_PROOF_UNAVAILABLE");
                    assertThat(problem.problem().title())
                            .doesNotContain("private provider detail")
                            .doesNotContain("database credential detail");
                });
    }

    private WorkerQuarantineRequestIndexRolloutService service(
            VisualEvidenceSigner proofSigner, TestSecurityEventRepository repository) {
        return new WorkerQuarantineRequestIndexRolloutService(
                controlPlane, repository, proofSigner, objectMapper,
                new WorkerQuarantineRequestIndexRolloutService.Settings(
                        "replica-a", STARTUP, ARTIFACT, Duration.ofSeconds(60),
                        "rollout-operators", "RESTRICTED"));
    }

    private static TestSecurityEventRepository repository(List<TestSecurityEvent> events) {
        return new TestSecurityEventRepository() {
            @Override
            public TestSecurityEvent append(TestSecurityEvent event) {
                events.add(event);
                return event.withSequence(events.size());
            }

            @Override
            public List<TestSecurityEvent> recent(int limit) {
                return List.copyOf(events);
            }
        };
    }

    private static WorkerQuarantineRequestIndexInventory inventory(
            Instant observedAt, long legacy, long keyed) {
        Instant legacyExpiry = legacy == 0 ? Instant.EPOCH : observedAt.plusSeconds(600);
        Instant keyedExpiry = keyed == 0 ? Instant.EPOCH : observedAt.plusSeconds(600);
        List<WorkerQuarantineRequestIndexInventory.KeyGeneration> generations = keyed == 0
                ? List.of() : List.of(new WorkerQuarantineRequestIndexInventory.KeyGeneration(
                "request-key-v1", keyed, keyedExpiry));
        return new WorkerQuarantineRequestIndexInventory(observedAt, legacy, keyed,
                legacyExpiry, keyedExpiry, generations);
    }

    private static WorkerQuarantineRequestIndexReplicaProofRequest request(
            WorkerQuarantineRequestIndexMode target) {
        return new WorkerQuarantineRequestIndexReplicaProofRequest(
                WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION,
                CHALLENGE, target);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "region-a", "SERVICE",
                "deployer-a", "", "TEST_RUNTIME_MAINTENANCE", "correlation-a",
                Set.of("rollout-operators"), "RESTRICTED", "");
    }
}
