package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.Waiver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCoverageInventoryRepositoryTest {

    private static final Instant FIRST_SAVE = Instant.parse("2026-08-15T05:00:00Z");
    private static final Instant SECOND_SAVE = Instant.parse("2026-08-15T06:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseCoverageInventoryRepository repository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-coverage-inventory-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = repositoryAt(FIRST_SAVE);
    }

    @Test
    void persistsRetainedRevisionsObligationIndexAndPayloadFreeEvents() throws Exception {
        StoredCoverageInventory draft = repository.saveIfRevision(
                0, inventory(scope("tenant-a"), 0, InventoryLifecycle.DRAFT, false), author())
                .orElseThrow();

        assertThat(draft.inventory().revision()).isEqualTo(1);
        assertThat(draft.inventoryFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(draft.inventory().metadata().createdAt()).isEqualTo(FIRST_SAVE);
        assertThat(repository.findHead(scope("tenant-a"), "loan-inventory"))
                .contains(draft);
        assertThat(jdbc.queryForList("""
                SELECT obligation_id, dimension, lifecycle
                FROM rg_coverage_obligation_index ORDER BY obligation_id
                """))
                .extracting(row -> row.get("OBLIGATION_ID"))
                .containsExactly("boundary.amount", "policy.eligibility");
        String changedJson = jdbc.queryForObject(
                "SELECT event_json FROM rg_correctness_outbox WHERE aggregate_revision = 1",
                String.class);
        CoverageInventoryChanged changed = mapper.readValue(
                changedJson, CoverageInventoryChanged.class);
        assertThat(changed.obligationCount()).isEqualTo(2);
        assertPayloadFree(changedJson);

        StoredCoverageInventory frozen = repositoryAt(SECOND_SAVE).saveIfRevision(
                1, inventory(scope("tenant-a"), 1, InventoryLifecycle.FROZEN, true), reviewer())
                .orElseThrow();
        assertThat(frozen.inventory().revision()).isEqualTo(2);
        assertThat(frozen.inventory().metadata().createdAt()).isEqualTo(FIRST_SAVE);
        assertThat(frozen.inventory().metadata().updatedAt()).isEqualTo(SECOND_SAVE);
        assertThat(repository.revisions(scope("tenant-a"), "loan-inventory"))
                .extracting(value -> value.inventory().revision())
                .containsExactly(2L, 1L);
        assertThat(repository.findRevision(scope("tenant-a"), "loan-inventory", 1))
                .contains(draft);

        String frozenJson = jdbc.queryForObject(
                "SELECT event_json FROM rg_correctness_outbox WHERE aggregate_revision = 2",
                String.class);
        CoverageInventoryFrozen event = mapper.readValue(
                frozenJson, CoverageInventoryFrozen.class);
        assertThat(event.obligationCount()).isEqualTo(2);
        assertThat(event.waivedCount()).isEqualTo(1);
        assertThat(event.derivationSources()).hasSize(2);
        assertPayloadFree(frozenJson);
    }

    @Test
    void isolatesFullEnterpriseScopeAndAllowsOnlyOneCasWriter() throws Exception {
        repository.saveIfRevision(
                0, inventory(scope("tenant-a"), 0, InventoryLifecycle.DRAFT, false), author())
                .orElseThrow();
        repository.saveIfRevision(
                0, inventory(scope("tenant-b"), 0, InventoryLifecycle.DRAFT, false), author())
                .orElseThrow();

        assertThat(repository.findHead(scope("tenant-b"), "loan-inventory"))
                .isPresent();
        assertThat(repository.findHead(
                new EnterpriseScope("tenant-a", "other-org", "credit", "test", "sg"),
                "loan-inventory")).isEmpty();

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return repository.saveIfRevision(1,
                        inventory(scope("tenant-a"), 1, InventoryLifecycle.DRAFT, false), author());
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.saveIfRevision(1,
                        inventory(scope("tenant-a"), 1, InventoryLifecycle.DRAFT, false), reviewer());
            });
            start.countDown();
            assertThat(List.of(first.get(), second.get()).stream()
                    .filter(Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(repository.revisions(scope("tenant-a"), "loan-inventory"))
                .hasSize(2);
    }

    @Test
    void rejectsStaleRevisionAndDetectsCanonicalOrIndexTampering() {
        repository.saveIfRevision(
                0, inventory(scope("tenant-a"), 0, InventoryLifecycle.DRAFT, false), author())
                .orElseThrow();

        assertThat(repository.saveIfRevision(
                0, inventory(scope("tenant-a"), 0, InventoryLifecycle.DRAFT, false), author()))
                .isEmpty();
        assertThatThrownBy(() -> repository.saveIfRevision(
                1, inventory(scope("tenant-a"), 0, InventoryLifecycle.DRAFT, false), author()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matching");

        jdbc.update("""
                UPDATE rg_coverage_obligation_index SET risk = 'LOW'
                WHERE tenant_id = 'tenant-a' AND obligation_id = 'policy.eligibility'
                """);
        assertThatThrownBy(() -> repository.findHead(scope("tenant-a"), "loan-inventory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void rollsBackHeadHistoryAndIndexWhenOutboxWriteFails() {
        jdbc.execute("DROP TABLE rg_correctness_outbox");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                repository.saveIfRevision(
                        0, inventory(scope("tenant-a"), 0, InventoryLifecycle.DRAFT, false),
                        author())))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_coverage_inventory_heads", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_coverage_inventory_revisions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_coverage_obligation_index", Integer.class)).isZero();
    }

    private DatabaseCoverageInventoryRepository repositoryAt(Instant time) {
        return new DatabaseCoverageInventoryRepository(
                jdbc, mapper, Clock.fixed(time, ZoneOffset.UTC));
    }

    private CoverageInventory inventory(
            EnterpriseScope scope,
            long revision,
            InventoryLifecycle lifecycle,
            boolean resolved
    ) {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        List<CoverageObligation> obligations = List.of(
                obligation("policy.eligibility", ObligationDimension.POLICY,
                        resolved ? ObligationLifecycle.FROZEN : ObligationLifecycle.PROPOSED,
                        null),
                obligation("boundary.amount", ObligationDimension.BOUNDARY,
                        resolved ? ObligationLifecycle.WAIVED : ObligationLifecycle.PROPOSED,
                        resolved ? new Waiver(
                                "Legacy product has no zero amount path",
                                Instant.parse("2027-08-15T00:00:00Z"), reviewer(),
                                Instant.parse("2026-08-15T04:30:00Z")) : null));
        ReviewRecord review = lifecycle == InventoryLifecycle.FROZEN
                ? new ReviewRecord(ReviewStatus.APPROVED, reviewer(), forged, "Freeze denominator")
                : ReviewRecord.pending();
        return new CoverageInventory(
                "", "loan-inventory", revision, scope,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a')),
                lifecycle, obligations,
                List.of(
                        new ExactSourceSnapshotRef("CONTRACT", "loan-contract", 2, fingerprint('b')),
                        new ExactSourceSnapshotRef("DAG", "loan-graph", 3, fingerprint('a'))),
                review, new AuditMetadata(forged, forged, reviewer(), reviewer()));
    }

    private CoverageObligation obligation(
            String id,
            ObligationDimension dimension,
            ObligationLifecycle lifecycle,
            Waiver waiver
    ) {
        return new CoverageObligation(
                id, dimension, id, "The business behavior for " + id,
                RiskLevel.CRITICAL, author(), ObligationSource.AUTOMATED,
                lifecycle, waiver, List.of("loan"));
    }

    private EnterpriseScope scope(String tenant) {
        return new EnterpriseScope(tenant, "org-a", "credit", "test", "sg");
    }

    private PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static void assertPayloadFree(String json) {
        assertThat(json)
                .doesNotContain("statement", "title", "waiver", "reason", "payload", "given")
                .doesNotContain("business behavior");
    }
}
