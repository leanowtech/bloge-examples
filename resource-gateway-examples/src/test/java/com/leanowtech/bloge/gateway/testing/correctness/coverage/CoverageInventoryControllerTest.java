package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageDerivationSource.DerivationSnapshot;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseCoverageInventoryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CoverageInventoryControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    private ObjectMapper mapper;
    private MockMvc mvc;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-coverage-inventory-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        var repository = new DatabaseCoverageInventoryRepository(
                jdbc, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        var receipts = new DatabaseCoverageFreezeReceiptRepository(jdbc, mapper);
        CoverageDerivationSource source = (scope, target) -> new DerivationSnapshot(
                scope, target, sources(), List.of(proposedObligation()));
        CoverageInventoryService service = new CoverageInventoryService(
                repository, (scope, inventory, actor) -> true, source, receipts, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "studio", "tenant-a", "org-a", "credit", "test", "sg",
                "USER", "author-a", "", Set.of(
                        "CORRECTNESS_WRITE", "CORRECTNESS_REVIEW", "CORRECTNESS_READ"),
                Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        mvc = MockMvcBuilders.standaloneSetup(
                        new CoverageInventoryController(service, authenticator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test
    void createsFreezesAndAnalyzesThroughAuthenticatedVersionedBoundary() throws Exception {
        mvc.perform(put("/api/visual/coverage-inventories/loan-inventory")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("X-Correlation-Id", "corr-save")
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.protocolVersion").value("bloge.correctnessApi.v1"))
                .andExpect(jsonPath("$.correlationId").value("corr-save"))
                .andExpect(jsonPath("$.data.inventory.revision").value(1))
                .andExpect(jsonPath("$.data.inventory.metadata.createdBy.id").value("author-a"));

        mvc.perform(post("/api/visual/coverage-inventories/loan-inventory:freeze")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_REVIEW")
                        .header("X-Correlation-Id", "corr-freeze")
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "freeze-loan-v1")
                        .contentType("application/json")
                        .content("{\"comment\":\"Reviewed denominator\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.data.stored.inventory.lifecycle").value("FROZEN"))
                .andExpect(jsonPath("$.data.stored.inventory.freezeReview.status")
                        .value("APPROVED"))
                .andExpect(jsonPath("$.data.stored.inventory.freezeReview.reviewer.id")
                        .value("author-a"))
                .andExpect(jsonPath("$.data.stored.inventory.freezeReview.reviewedAt")
                        .value("2026-08-15T08:00:00Z"));

        mvc.perform(post("/api/visual/coverage-inventories/loan-inventory:freeze")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_REVIEW")
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "freeze-loan-v1")
                        .contentType("application/json")
                        .content("{\"comment\":\"Reviewed denominator\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.stored.inventory.revision").value(2));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_outbox", Integer.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_command_receipts", Integer.class)).isEqualTo(1);

        mvc.perform(post("/api/visual/coverage-inventories/loan-inventory:impact")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(
                                new CoverageInventoryController.ImpactRequest(target()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposalFingerprint")
                        .value(org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{64}")))
                .andExpect(jsonPath("$.data.changes[0].kind").value("UNCHANGED"));
    }

    @Test
    void requiresAuthenticationPurposePreconditionAndExactScope() throws Exception {
        mvc.perform(put("/api/visual/coverage-inventories/loan-inventory")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft())))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/visual/coverage-inventories/loan-inventory")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft())))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.PRECONDITION_REQUIRED"));

        CoverageInventory wrongScope = new CoverageInventory(
                "", "loan-inventory", 0,
                new EnterpriseScope("other", "org-a", "credit", "test", "sg"),
                target(), InventoryLifecycle.DRAFT, List.of(resolvedObligation()),
                sources(), ReviewRecord.pending(), metadata());
        mvc.perform(put("/api/visual/coverage-inventories/loan-inventory")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(wrongScope)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private CoverageInventory draft() {
        return new CoverageInventory(
                "", "loan-inventory", 0, scope(), target(), InventoryLifecycle.DRAFT,
                List.of(resolvedObligation()), sources(), ReviewRecord.pending(), metadata());
    }

    private static CoverageObligation resolvedObligation() {
        return new CoverageObligation(
                "policy.eligibility", ObligationDimension.POLICY, "Eligibility",
                "Eligible applicants are approved", RiskLevel.LOW, author(),
                ObligationSource.BUSINESS, ObligationLifecycle.FROZEN, null, List.of("loan"));
    }

    private static CoverageObligation proposedObligation() {
        CoverageObligation value = resolvedObligation();
        return new CoverageObligation(
                value.obligationId(), value.dimension(), value.title(), value.statement(),
                value.risk(), value.owner(), value.source(), ObligationLifecycle.PROPOSED,
                null, value.tags());
    }

    private static List<ExactSourceSnapshotRef> sources() {
        return List.of(new ExactSourceSnapshotRef(
                "DAG", "loan-graph", 3, fingerprint('a')));
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static AuditMetadata metadata() {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new AuditMetadata(forged, forged, author(), author());
    }

    private static PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
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
