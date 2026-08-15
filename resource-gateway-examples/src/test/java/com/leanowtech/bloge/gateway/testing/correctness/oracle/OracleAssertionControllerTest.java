package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.AssertionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseAssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseBusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OracleAssertionControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T11:00:00Z");

    private ObjectMapper mapper;
    private MockMvc mvc;
    private DatabaseBusinessOracleRepository oracles;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-oracle-assertion-schema.sql")).execute(database);
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        oracles = new DatabaseBusinessOracleRepository(jdbc, mapper, clock);
        var assertionRepository = new DatabaseAssertionSetRepository(jdbc, mapper, clock);
        var oracleService = new BusinessOracleService(
                oracles,
                (scope, oracle, actor) -> OracleReviewAuthorizer.ApprovalDecision.ownerReview(),
                (scope, target, refs) -> true,
                new DatabaseOracleApprovalReceiptRepository(jdbc, mapper), mapper, clock);
        var assertionService = new AssertionSetService(
                assertionRepository, oracles, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new TwoActorIdentityResolver(), new RecordingAudit());
        mvc = MockMvcBuilders.standaloneSetup(
                        new OracleAssertionController(
                                oracleService, assertionService, authenticator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test
    void authorsApprovesCompilesAndValidatesThroughAuthenticatedBoundary() throws Exception {
        mvc.perform(put("/api/visual/oracles/loan-approved")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("X-Correlation-Id", "corr-oracle")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(oracle())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.oracle.lifecycle").value("PROPOSED"))
                .andExpect(jsonPath("$.data.oracle.metadata.createdBy.id").value("author-a"));

        mvc.perform(post("/api/visual/oracles/loan-approved:approve")
                        .header("Authorization", "Bearer reviewer-token")
                        .header("X-Purpose", "CORRECTNESS_REVIEW")
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "oracle-approval-v1")
                        .contentType("application/json")
                        .content("{\"comment\":\"Policy owner approved\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.data.stored.oracle.lifecycle").value("APPROVED"))
                .andExpect(jsonPath("$.data.stored.oracle.approval.reviewer.id")
                        .value("reviewer-a"))
                .andExpect(jsonPath("$.data.stored.oracle.approval.reviewedAt")
                        .value("2026-08-15T11:00:00Z"));

        mvc.perform(post("/api/visual/oracles/loan-approved:approve")
                        .header("Authorization", "Bearer reviewer-token")
                        .header("X-Purpose", "CORRECTNESS_REVIEW")
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "oracle-approval-v1")
                        .contentType("application/json")
                        .content("{\"comment\":\"Policy owner approved\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        AssertionSet draft = assertionSet(exactOracle(), false);
        mvc.perform(post("/api/visual/assertion-sets:compile-preview")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.compatibility.supported").value(true))
                .andExpect(jsonPath("$.data.dispositions[0].status")
                        .value("COMPILED_RUNTIME"));

        mvc.perform(put("/api/visual/assertion-sets/loan-checks")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.assertionSet.compatibility.supported")
                        .value(false));

        mvc.perform(post("/api/visual/assertion-sets/loan-checks:validate")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.stored.assertionSet.lifecycle").value("VALID"))
                .andExpect(jsonPath("$.data.compilation.runtimeAssertions").isNotEmpty());
    }

    @Test
    void returnsStableProblemsForPreconditionsScopeAndUnsupportedValidation() throws Exception {
        mvc.perform(put("/api/visual/oracles/loan-approved")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(oracle())))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.PRECONDITION_REQUIRED"));

        BusinessOracle wrongScope = new BusinessOracle(
                "", "loan-approved", 0,
                new EnterpriseScope("other", "org-a", "credit", "test", "sg"),
                target(), "Expected outcome", List.of(), List.of(basis()), owner(),
                OracleLifecycle.PROPOSED, ReviewRecord.pending(), List.of(), metadata());
        mvc.perform(put("/api/visual/oracles/loan-approved")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(wrongScope)))
                .andExpect(status().isNotFound());

        seedApprovedOracle();
        AssertionSet unsupported = assertionSet(exactOracle(), true);
        mvc.perform(put("/api/visual/assertion-sets/unsupported-checks")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(unsupported)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/visual/assertion-sets/unsupported-checks:validate")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.ASSERTION_UNSUPPORTED"));
    }

    private void seedApprovedOracle() throws Exception {
        mvc.perform(put("/api/visual/oracles/loan-approved")
                .header("Authorization", "Bearer author-token")
                .header("X-Purpose", "CORRECTNESS_WRITE")
                .header("If-Match", "0")
                .contentType("application/json")
                .content(mapper.writeValueAsBytes(oracle()))).andExpect(status().isCreated());
        mvc.perform(post("/api/visual/oracles/loan-approved:approve")
                .header("Authorization", "Bearer reviewer-token")
                .header("X-Purpose", "CORRECTNESS_REVIEW")
                .header("If-Match", "1")
                .header("Idempotency-Key", "seed-approval")
                .contentType("application/json")
                .content("{\"comment\":\"Approved\"}")).andExpect(status().isOk());
    }

    private BusinessOracle oracle() {
        return new BusinessOracle(
                "", "loan-approved", 0, scope(), target(),
                "Prime applicants are approved without manual review",
                List.of("manual review", "rejection"), List.of(basis()), owner(),
                OracleLifecycle.PROPOSED, ReviewRecord.pending(), List.of(), metadata());
    }

    private AssertionSet assertionSet(ExactAssetRef oracleRef, boolean unsupported) {
        List<AssertionSet.ExecutableAssertionSpec> specs = unsupported
                ? List.of(new InvocationAssertion(
                        "manual-not-used", EvaluationKind.EVIDENCE, "manual-review",
                        InvocationOperator.NOT_USED, true))
                : List.of(new OutputAssertion(
                        "decision", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, "approve"));
        return new AssertionSet(
                "", unsupported ? "unsupported-checks" : "loan-checks", 0,
                target(), oracleRef, AssertionLifecycle.DRAFT, specs,
                CompilationCompatibility.unsupported("client-draft"), metadata());
    }

    private ExactAssetRef exactOracle() {
        StoredBusinessOracle approved = oracles.findHead(scope(), "loan-approved").orElseThrow();
        return new ExactAssetRef(
                "ORACLE", approved.oracle().oracleId(), approved.oracle().revision(),
                approved.oracleFingerprint());
    }

    private static ExactBasisRef basis() {
        return new ExactBasisRef("POLICY", "loan-policy", 7, fingerprint('b'));
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private static AuditMetadata metadata() {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        PrincipalRef author = new PrincipalRef("forged", PrincipalKind.USER, "Forged");
        return new AuditMetadata(forged, forged, author, author);
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
