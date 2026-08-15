package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectnessWorkspaceControllerTest {

    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void authenticatesAndReturnsVersionedScopeBoundEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CorrectnessDefinition definition = definition();
        StoredCorrectnessDefinition stored = StoredCorrectnessDefinition.verified(mapper, definition);
        CorrectnessDefinitionRepository repository = new SingleDefinitionRepository(stored);
        CorrectnessWorkspaceQuery query = new CorrectnessWorkspaceQuery(
                repository, new DefinitionOnlyCorrectnessWorkspaceComponentSource(), mapper);
        RecordingAudit audit = new RecordingAudit();
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "studio", "tenant-a", "org-a", "credit", "test", "sg",
                "WORKLOAD", "author-a", "", Set.of("CORRECTNESS_READ"),
                Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false), audit);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new CorrectnessWorkspaceController(query, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(get("/api/visual/correctness-workspaces/GRAPH/loan-graph")
                        .queryParam("targetFingerprint", TARGET_FINGERPRINT)
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .header("X-Correlation-Id", "corr-workspace"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.protocolVersion")
                        .value("bloge.correctnessApi.v1"))
                .andExpect(jsonPath("$.correlationId").value("corr-workspace"))
                .andExpect(jsonPath("$.scope.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.data.definition.definitionRef.id")
                        .value("definition-a"))
                .andExpect(jsonPath("$.data.cases.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.verdict.gate").value("BLOCKED"))
                .andExpect(jsonPath("$.data.success").doesNotExist())
                .andExpect(jsonPath("$.data.fixtures.rows").isEmpty());

        mvc.perform(get("/api/visual/correctness-workspaces/GRAPH/loan-graph")
                        .queryParam("targetFingerprint", TARGET_FINGERPRINT)
                        .header("X-Purpose", "CORRECTNESS_READ"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
    }

    private static CorrectnessDefinition definition() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        PrincipalRef owner = new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
        return new CorrectnessDefinition(
                "", "definition-a", 1,
                new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg"),
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, TARGET_FINGERPRINT),
                "Loan correctness", "No ineligible approval",
                List.of("Reject ineligible applicants"), RiskLevel.CRITICAL, owner,
                List.of(), null, null, CorrectnessDefinition.DefinitionLifecycle.DRAFT,
                null, new AuditMetadata(now, now, owner, owner));
    }

    private record SingleDefinitionRepository(StoredCorrectnessDefinition stored)
            implements CorrectnessDefinitionRepository {

        @Override
        public Optional<StoredCorrectnessDefinition> findHead(
                EnterpriseScope scope,
                String definitionId
        ) {
            return matches(scope) && stored.definition().definitionId().equals(definitionId)
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public List<StoredCorrectnessDefinition> findHeadCandidatesByTarget(
                EnterpriseScope scope,
                TargetKind targetKind,
                String targetId,
                String targetFingerprint
        ) {
            return matches(scope) && stored.definition().target().kind() == targetKind
                    && stored.definition().target().id().equals(targetId)
                    && stored.definition().target().fingerprint().equals(targetFingerprint)
                    ? List.of(stored) : List.of();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> findRevision(
                EnterpriseScope scope,
                String definitionId,
                long revision
        ) {
            return findHead(scope, definitionId)
                    .filter(value -> value.definition().revision() == revision);
        }

        @Override
        public List<StoredCorrectnessDefinition> revisions(
                EnterpriseScope scope,
                String definitionId
        ) {
            return findHead(scope, definitionId).stream().toList();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> saveIfRevision(
                long expectedRevision,
                CorrectnessDefinition candidate,
                PrincipalRef actor
        ) {
            throw new UnsupportedOperationException();
        }

        private boolean matches(EnterpriseScope scope) {
            return stored.definition().scope().equals(scope);
        }
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
