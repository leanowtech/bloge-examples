package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.CaseType;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GivenV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.CheckStatus;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.ClosureCheck;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.ClosurePhase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScenarioDraftSetV2ControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T15:00:00Z");

    private ObjectMapper mapper;
    private ScenarioDraftSetV2Service service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = mock(ScenarioDraftSetV2Service.class);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new TwoActorIdentityResolver(), new RecordingAudit());
        mvc = MockMvcBuilders.standaloneSetup(
                        new ScenarioDraftSetV2Controller(service, authenticator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test
    void savesSubmitsAndIdempotentlyApprovesThroughAuthenticatedBoundary() throws Exception {
        ScenarioDraftSetV2 draft = scenarioSet(0, ScenarioLifecycle.EXPLORATORY);
        StoredScenarioDraftSetV2 storedDraft = stored(
                scenarioSet(1, ScenarioLifecycle.EXPLORATORY));
        var ready = transition(2, ScenarioLifecycle.REVIEW_READY, false);
        var canonical = transition(3, ScenarioLifecycle.CANONICAL, false);
        when(service.saveDraft(anyLong(), any(), any())).thenReturn(storedDraft);
        when(service.markReviewReady(any(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(ready);
        when(service.approveCanonicalIdempotently(
                any(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(canonical);

        mvc.perform(put("/api/visual/scenario-draft-sets-v2/loan-scenarios")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("X-Correlation-Id", "corr-scenario")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.scenarioDraftSet.scenarios[0].lifecycle")
                        .value("EXPLORATORY"));

        mvc.perform(post("/api/visual/scenario-draft-sets-v2/loan-scenarios"
                        + "/cases/prime-approved:review-ready")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.closure.complete").value(true));

        mvc.perform(post("/api/visual/scenario-draft-sets-v2/loan-scenarios"
                        + "/cases/prime-approved:approve")
                        .header("Authorization", "Bearer reviewer-token")
                        .header("X-Purpose", "CORRECTNESS_REVIEW")
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "canonical-prime-v1")
                        .contentType("application/json")
                        .content("{\"comment\":\"Policy owner approved\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.data.stored.scenarioDraftSet.scenarios[0].lifecycle")
                        .value("CANONICAL"));

        verify(service).approveCanonicalIdempotently(
                scope(), "loan-scenarios", "prime-approved", 2,
                "Policy owner approved",
                new PrincipalRef("reviewer-a", PrincipalKind.USER, ""),
                "canonical-prime-v1");
    }

    @Test
    void returnsStructuredClosureAndStablePreconditionAndScopeProblems() throws Exception {
        ScenarioClosureReport closure = new ScenarioClosureReport(
                "", "prime-approved", ClosurePhase.REVIEW_READY, false,
                List.of(new ClosureCheck(
                        "oracle:required", "ORACLE", null, "", CheckStatus.MISSING,
                        "RG.CORRECTNESS.ORACLE_NOT_APPROVED")));
        when(service.markReviewReady(any(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new ScenarioV2CommandException(
                        "RG.CORRECTNESS.SCENARIO_CLOSURE_INCOMPLETE",
                        "Scenario closure is incomplete.", closure));

        mvc.perform(post("/api/visual/scenario-draft-sets-v2/loan-scenarios"
                        + "/cases/prime-approved:review-ready")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.SCENARIO_CLOSURE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.closure.checks[0].reasonCode")
                        .value("RG.CORRECTNESS.ORACLE_NOT_APPROVED"));

        mvc.perform(put("/api/visual/scenario-draft-sets-v2/loan-scenarios")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(
                                scenarioSet(0, ScenarioLifecycle.EXPLORATORY))))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.PRECONDITION_REQUIRED"));

        ScenarioDraftSetV2 wrongScope = new ScenarioDraftSetV2(
                "", "loan-scenarios", 0,
                new EnterpriseScope("other", "org-a", "credit", "test", "sg"),
                target(), contractRef(), scenarioSet(0, ScenarioLifecycle.EXPLORATORY).scenarios(),
                metadata(author()));
        mvc.perform(put("/api/visual/scenario-draft-sets-v2/loan-scenarios")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(wrongScope)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.SCENARIO_NOT_FOUND"));
    }

    private ScenarioDraftSetV2Service.TransitionResult transition(
            long revision,
            ScenarioLifecycle lifecycle,
            boolean replayed
    ) {
        ScenarioDraftSetV2 set = scenarioSet(revision, lifecycle);
        return new ScenarioDraftSetV2Service.TransitionResult(
                stored(set), new ScenarioClosureReport(
                        "", "prime-approved",
                        lifecycle == ScenarioLifecycle.CANONICAL
                                ? ClosurePhase.CANONICAL : ClosurePhase.REVIEW_READY,
                        true, List.of(new ClosureCheck(
                                "contract", "CONTRACT", contractRef(), "",
                                CheckStatus.VERIFIED, ""))), replayed);
    }

    private ScenarioDraftSetV2 scenarioSet(long revision, ScenarioLifecycle lifecycle) {
        boolean governed = lifecycle != ScenarioLifecycle.EXPLORATORY;
        ReviewRecord review = lifecycle == ScenarioLifecycle.CANONICAL
                ? new ReviewRecord(ReviewStatus.APPROVED, reviewer(), NOW, "Approved")
                : ReviewRecord.pending();
        ScenarioDraftV2 scenario = new ScenarioDraftV2(
                "prime-approved", "Prime approved", "Prove eligible approval", "",
                CaseType.GOLDEN, RiskLevel.HIGH, owner(), lifecycle,
                lifecycle == ScenarioLifecycle.CANONICAL
                        ? List.of(new ExactObligationRef(
                                inventoryRef(), "policy.eligibility", fingerprint('f')))
                        : List.of(),
                lifecycle == ScenarioLifecycle.CANONICAL ? List.of(oracleRef()) : List.of(),
                governed ? List.of(assertionRef()) : List.of(), List.of(),
                new GivenV2(new InlineValue(Map.of("applicantId", "A-100"))),
                List.of(), review, List.of("loan"));
        return new ScenarioDraftSetV2(
                "", "loan-scenarios", revision, scope(), target(), contractRef(),
                List.of(scenario), metadata(author()));
    }

    private StoredScenarioDraftSetV2 stored(ScenarioDraftSetV2 set) {
        return StoredScenarioDraftSetV2.verified(mapper, set);
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static ExactAssetRef contractRef() {
        return new ExactAssetRef("CONTRACT", "loan-contract", 2, fingerprint('c'));
    }

    private static ExactAssetRef inventoryRef() {
        return new ExactAssetRef("INVENTORY", "loan-inventory", 2, fingerprint('b'));
    }

    private static ExactAssetRef oracleRef() {
        return new ExactAssetRef("ORACLE", "loan-oracle", 2, fingerprint('d'));
    }

    private static ExactAssetRef assertionRef() {
        return new ExactAssetRef("ASSERTION_SET", "loan-checks", 2, fingerprint('e'));
    }

    private static PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private static PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private static AuditMetadata metadata(PrincipalRef actor) {
        return new AuditMetadata(NOW, NOW, actor, actor);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static IntegrationWorkloadIdentity identity(String id, String actor) {
        return new IntegrationWorkloadIdentity(
                id, "tenant-a", "org-a", "credit", "test", "sg", "USER", actor, "",
                Set.of("CORRECTNESS_WRITE", "CORRECTNESS_REVIEW"), Instant.MAX, true);
    }

    private static final class TwoActorIdentityResolver implements IntegrationIdentityResolver {
        @Override
        public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
            return switch (credential) {
                case "author-token" -> Optional.of(identity("author", "author-a"));
                case "reviewer-token" -> Optional.of(identity("reviewer", "reviewer-a"));
                default -> Optional.empty();
            };
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
