package com.leanowtech.bloge.gateway.visual.reference;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectnessDefinitionCandidateControllerTest {

    @Test
    void exposesDemoLikeTargetAndItsBoundDefinitionWithoutManualCoordinates() throws Exception {
        StoredCorrectnessDefinition stored = storedDefinition();
        CorrectnessDefinitionRepository repository = mock(CorrectnessDefinitionRepository.class);
        when(repository.supportsHeadListing()).thenReturn(true);
        when(repository.listHeads(scope(), 100)).thenReturn(List.of(stored));
        when(repository.findHeadCandidatesByTarget(
                scope(), TargetKind.GRAPH, "loan-decision", fingerprint('a')))
                .thenReturn(List.of(stored));
        @SuppressWarnings("unchecked")
        ObjectProvider<CorrectnessDefinitionRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        MockMvc mvc = mvc(provider);

        mvc.perform(get("/api/visual/correctness-targets")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .param("targetKind", "GRAPH")
                        .param("query", "loan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("loan-decision"))
                .andExpect(jsonPath("$.items[0].fingerprint").value(fingerprint('a')))
                .andExpect(jsonPath("$.items[0].displayName").value("Loan decision correctness"));

        mvc.perform(get("/api/visual/correctness-targets/GRAPH/loan-decision/definitions")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .param("targetFingerprint", fingerprint('a')))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("CORRECTNESS_DEFINITION"))
                .andExpect(jsonPath("$.items[0].id").value("loan-correctness"))
                .andExpect(jsonPath("$.items[0].owner.stableId").value("risk-team"));
    }

    private static MockMvc mvc(ObjectProvider<CorrectnessDefinitionRepository> definitions) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "candidate-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("CORRECTNESS_READ"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("candidate-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new CorrectnessDefinitionCandidateController(definitions, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static StoredCorrectnessDefinition storedDefinition() {
        PrincipalRef owner = new PrincipalRef("risk-team", PrincipalKind.TEAM, "Risk Team");
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        CorrectnessDefinition definition = new CorrectnessDefinition(
                CorrectnessDefinition.SCHEMA_VERSION,
                "loan-correctness",
                1,
                scope(),
                new ExactTargetRef(TargetKind.GRAPH, "loan-decision", 7, fingerprint('a')),
                "Loan decision correctness",
                "Proves policy-compliant lending decisions.",
                List.of("Eligible requests receive the reviewed result"),
                RiskLevel.CRITICAL,
                owner,
                List.of(),
                null,
                null,
                CorrectnessDefinition.DefinitionLifecycle.DRAFT,
                null,
                new AuditMetadata(now, now, owner, owner));
        return StoredCorrectnessDefinition.verified(
                new ObjectMapper().findAndRegisterModules(), definition);
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "local");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
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
