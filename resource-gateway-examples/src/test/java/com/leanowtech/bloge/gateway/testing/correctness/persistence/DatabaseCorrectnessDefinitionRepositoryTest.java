package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCorrectnessDefinitionRepositoryTest {

    private static final Instant FIRST_SAVE = Instant.parse("2026-08-15T03:00:00Z");
    private static final Instant SECOND_SAVE = Instant.parse("2026-08-15T04:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseCorrectnessDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-definition-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = repositoryAt(FIRST_SAVE);
    }

    @Test
    void persistsServerOwnedRevisionAndRetainsVerifiedHistory() throws Exception {
        CorrectnessDefinition candidate = definition(scope("tenant-a"), 0, "Initial intent");

        StoredCorrectnessDefinition first = repository.saveIfRevision(0, candidate, actor())
                .orElseThrow();

        assertThat(first.definition().revision()).isEqualTo(1);
        assertThat(first.definitionFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.definition().metadata().createdAt()).isEqualTo(FIRST_SAVE);
        assertThat(first.definition().metadata().updatedAt()).isEqualTo(FIRST_SAVE);
        assertThat(first.definition().metadata().createdBy()).isEqualTo(actor());
        assertThat(repository.findHead(scope("tenant-a"), "loan-correctness"))
                .contains(first);
        String eventJson = jdbc.queryForObject(
                "SELECT event_json FROM rg_correctness_outbox WHERE aggregate_revision = 1",
                String.class);
        CorrectnessDefinitionChanged event = mapper.readValue(
                eventJson, CorrectnessDefinitionChanged.class);
        assertThat(event.definitionRef().fingerprint()).isEqualTo(first.definitionFingerprint());
        assertThat(event.scope()).isEqualTo(scope("tenant-a"));
        var eventNode = mapper.readTree(eventJson);
        assertThat(eventNode.has("title")).isFalse();
        assertThat(eventNode.has("businessIntent")).isFalse();
        assertThat(eventNode.has("successCriteria")).isFalse();
        assertThat(eventNode.has("payload")).isFalse();

        DatabaseCorrectnessDefinitionRepository secondRepository = repositoryAt(SECOND_SAVE);
        CorrectnessDefinition edited = definition(scope("tenant-a"), 1, "Revised intent");
        StoredCorrectnessDefinition second = secondRepository
                .saveIfRevision(1, edited, reviewer()).orElseThrow();

        assertThat(second.definition().revision()).isEqualTo(2);
        assertThat(second.definition().metadata().createdAt()).isEqualTo(FIRST_SAVE);
        assertThat(second.definition().metadata().updatedAt()).isEqualTo(SECOND_SAVE);
        assertThat(second.definition().metadata().createdBy()).isEqualTo(actor());
        assertThat(second.definition().metadata().updatedBy()).isEqualTo(reviewer());
        assertThat(repository.revisions(scope("tenant-a"), "loan-correctness"))
                .extracting(stored -> stored.definition().revision())
                .containsExactly(2L, 1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_outbox", Integer.class)).isEqualTo(2);
        assertThat(repository.findRevision(scope("tenant-a"), "loan-correctness", 1))
                .contains(first);
    }

    @Test
    void enforcesFullEnterpriseScopeForSameBusinessIdentifier() {
        StoredCorrectnessDefinition tenantA = repository.saveIfRevision(
                0, definition(scope("tenant-a"), 0, "Tenant A"), actor()).orElseThrow();
        StoredCorrectnessDefinition tenantB = repository.saveIfRevision(
                0, definition(scope("tenant-b"), 0, "Tenant B"), actor()).orElseThrow();

        assertThat(repository.findHead(scope("tenant-a"), "loan-correctness"))
                .contains(tenantA);
        assertThat(repository.findHead(scope("tenant-b"), "loan-correctness"))
                .contains(tenantB);
        assertThat(repository.findHeadCandidatesByTarget(
                scope("tenant-a"), TargetKind.GRAPH, "loan-graph", fingerprint('a')))
                .containsExactly(tenantA);
        assertThat(repository.findHeadCandidatesByTarget(
                scope("tenant-b"), TargetKind.GRAPH, "loan-graph", fingerprint('a')))
                .containsExactly(tenantB);
        assertThat(repository.findHead(
                new EnterpriseScope("tenant-a", "other-org", "credit", "test", "sg"),
                "loan-correctness")).isEmpty();
        assertThat(repository.findHeadCandidatesByTarget(
                new EnterpriseScope("tenant-a", "other-org", "credit", "test", "sg"),
                TargetKind.GRAPH, "loan-graph", fingerprint('a'))).isEmpty();
    }

    @Test
    void databaseCompareAndSwapAllowsOnlyOneConcurrentWriter() throws Exception {
        repository.saveIfRevision(
                0, definition(scope("tenant-a"), 0, "Initial"), actor()).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return repository.saveIfRevision(
                        1, definition(scope("tenant-a"), 1, "Concurrent A"), actor());
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.saveIfRevision(
                        1, definition(scope("tenant-a"), 1, "Concurrent B"), reviewer());
            });
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream()
                    .filter(Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(repository.findHead(scope("tenant-a"), "loan-correctness")
                .orElseThrow().definition().revision()).isEqualTo(2);
        assertThat(repository.revisions(scope("tenant-a"), "loan-correctness"))
                .hasSize(2);
    }

    @Test
    void rejectsStaleOrMismatchedClientRevision() {
        repository.saveIfRevision(
                0, definition(scope("tenant-a"), 0, "Initial"), actor()).orElseThrow();

        assertThat(repository.saveIfRevision(
                0, definition(scope("tenant-a"), 0, "Stale"), actor())).isEmpty();
        assertThatThrownBy(() -> repository.saveIfRevision(
                1, definition(scope("tenant-a"), 0, "Mismatched"), actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matching");
    }

    @Test
    void refusesColumnOrCanonicalDocumentTampering() {
        repository.saveIfRevision(
                0, definition(scope("tenant-a"), 0, "Initial"), actor()).orElseThrow();
        jdbc.update("""
                UPDATE rg_correctness_definition_heads SET owner_id = 'forged-owner'
                WHERE tenant_id = 'tenant-a' AND definition_id = 'loan-correctness'
                """);

        assertThatThrownBy(() -> repository.findHead(scope("tenant-a"), "loan-correctness"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void transactionRollsBackHeadAndHistoryWhenOutboxWriteFails() {
        jdbc.execute("DROP TABLE rg_correctness_outbox");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                repository.saveIfRevision(
                        0, definition(scope("tenant-a"), 0, "Initial"), actor())))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_definition_heads", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_definition_revisions", Integer.class))
                .isZero();
    }

    @Test
    void productionMigrationDeclaresCanonicalAndProjectionBoundaries() throws Exception {
        String migration = Files.readString(Path.of(
                "src", "main", "resources", "db", "postgresql",
                "V20260815_005__correctness_authoring_protocol.sql"));

        assertThat(migration).contains(
                "rg_correctness_definition_heads",
                "rg_correctness_definition_revisions",
                "rg_coverage_inventory_revisions",
                "rg_coverage_obligation_index",
                "rg_business_oracle_revisions",
                "rg_assertion_set_revisions",
                "rg_scenario_draft_set_v2_heads",
                "rg_scenario_case_v2_index",
                "rg_scenario_case_obligation_ref_index",
                "rg_fixture_asset_revisions",
                "rg_fixture_usage_index",
                "rg_correctness_publications",
                "rg_correctness_publication_attempt_history",
                "rg_correctness_command_receipts",
                "rg_correctness_outbox",
                "canonical_json JSONB NOT NULL");
        assertThat(migration).doesNotContain("fixture_payload", "request_payload", "response_payload");
        assertThat(migration.split("tenant_id VARCHAR", -1).length - 1).isGreaterThanOrEqualTo(13);
        assertThat(migration.split("organization_id VARCHAR", -1).length - 1)
                .isEqualTo(migration.split("tenant_id VARCHAR", -1).length - 1);
        assertThat(migration).contains(
                "LIKE rg_correctness_definition_heads",
                "LIKE rg_coverage_inventory_heads",
                "LIKE rg_business_oracle_heads",
                "LIKE rg_assertion_set_heads",
                "LIKE rg_scenario_draft_set_v2_heads",
                "LIKE rg_fixture_asset_heads");
        String eventSchema = Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-correctness-definition-changed-v1.schema.json"));
        assertThat(mapper.readTree(eventSchema).path("additionalProperties").asBoolean(true))
                .isFalse();
        String storedSchema = Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-stored-correctness-definition-v1.schema.json"));
        var storedDefinitionSchema = mapper.readTree(storedSchema);
        assertThat(storedDefinitionSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(storedDefinitionSchema.at("/properties/definition/$ref").asText())
                .isEqualTo("bloge-correctness-definition-v1.schema.json");
    }

    private DatabaseCorrectnessDefinitionRepository repositoryAt(Instant instant) {
        return new DatabaseCorrectnessDefinitionRepository(
                jdbc, mapper, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private CorrectnessDefinition definition(
            EnterpriseScope scope,
            long revision,
            String intent
    ) {
        Instant forgedClientTime = Instant.parse("2001-01-01T00:00:00Z");
        return new CorrectnessDefinition(
                "", "loan-correctness", revision, scope,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a')),
                "Loan decision correctness", intent, List.of("No ineligible approval"),
                RiskLevel.CRITICAL, actor(),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 4, fingerprint('b'))),
                null, new ExactAssetRef("INVENTORY", "loan-inventory", 2, fingerprint('c')),
                CorrectnessDefinition.DefinitionLifecycle.ACTIVE,
                new ReviewRecord(ReviewStatus.APPROVED, reviewer(), forgedClientTime, "Approved"),
                new AuditMetadata(forgedClientTime, forgedClientTime, reviewer(), reviewer()));
    }

    private EnterpriseScope scope(String tenant) {
        return new EnterpriseScope(tenant, "org-a", "credit", "test", "sg");
    }

    private PrincipalRef actor() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
