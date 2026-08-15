package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionDraft;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionOperator;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionScope;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.Given;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.ScenarioDraft;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.Then;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationController.MigrationRequest;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyScenarioV1MigrationControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T16:00:00Z");

    private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var adapter = new LegacyScenarioV1MigrationAdapter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        var authenticator = new IntegrationRequestAuthenticator(
                new IdentityResolver(), new RecordingAudit());
        mvc = MockMvcBuilders.standaloneSetup(
                        new LegacyScenarioV1MigrationController(adapter, authenticator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test
    void returnsNoStoreExploratoryPreviewWithoutManufacturedAuthority() throws Exception {
        mvc.perform(post("/api/visual/scenario-draft-sets-v2:migrate-v1-preview")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("X-Correlation-Id", "corr-migrate")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(request(legacy("tenant-a")))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.data.reviewRequired").value(true))
                .andExpect(jsonPath("$.data.proposedDraftSet.scenarios[0].lifecycle")
                        .value("EXPLORATORY"))
                .andExpect(jsonPath("$.data.proposedDraftSet.scenarios[0].obligationRefs")
                        .isEmpty())
                .andExpect(jsonPath("$.data.proposedDraftSet.scenarios[0].oracleRefs")
                        .isEmpty())
                .andExpect(jsonPath("$.data.proposedDraftSet.scenarios[0].assertionSetRefs")
                        .isEmpty())
                .andExpect(jsonPath("$.data.assertionProposals[0].assertions[0].expected")
                        .value("APPROVE"));
    }

    @Test
    void rejectsUnauthorizedAndCrossScopePreviewWithStableProblems() throws Exception {
        mvc.perform(post("/api/visual/scenario-draft-sets-v2:migrate-v1-preview")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(request(legacy("tenant-a")))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/visual/scenario-draft-sets-v2:migrate-v1-preview")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(request(legacy("other")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.MIGRATION_INPUT_INVALID"));
    }

    private MigrationRequest request(ScenarioDraftSet legacy) {
        return new MigrationRequest(
                legacy, exactTarget(),
                new ExactAssetRef("CONTRACT", "loan-contract", 2, fingerprint('c')),
                new ExactAssetRef(
                        "SCENARIO_DRAFT_SET_V1", "loan-scenarios", 7, fingerprint('7')),
                new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner"));
    }

    private ScenarioDraftSet legacy(String tenant) {
        ScenarioDraft scenario = new ScenarioDraft(
                "prime-approved", "Prime approved", "Prove eligible approval",
                ScenarioDraftSet.CaseType.GOLDEN, List.of("loan"),
                new Given(Map.of("applicantId", "A-100"),
                        ScenarioDraftSet.ValueProvenance.MIGRATED), List.of(),
                new Then(List.of(new AssertionDraft(
                        "decision", AssertionScope.OUTPUT_PATH, "", "", "", "/decision",
                        AssertionOperator.EQUALS, "APPROVE", null))));
        return new ScenarioDraftSet(
                "", "loan-scenarios", 7,
                new ScenarioDraftSet.EnterpriseScope(
                        tenant, "org-a", "credit", "test", "sg"),
                new ContractDraft.Target(
                        ContractDraft.TargetKind.GRAPH, "loan-graph", 3, fingerprint('a')),
                fingerprint('c'), List.of(scenario),
                new ScenarioDraftSet.Metadata(
                        "credit-owner", "CONFIDENTIAL", NOW, NOW, Map.of()));
    }

    private ExactTargetRef exactTarget() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static IntegrationWorkloadIdentity identity() {
        return new IntegrationWorkloadIdentity(
                "author", "tenant-a", "org-a", "credit", "test", "sg", "USER",
                "author-a", "", Set.of("CORRECTNESS_WRITE"), Instant.MAX, true);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static final class IdentityResolver implements IntegrationIdentityResolver {
        @Override
        public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
            return "author-token".equals(credential)
                    ? Optional.of(identity()) : Optional.empty();
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor("TEST", "TEST", true, true, false);
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
