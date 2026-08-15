package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;

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

class DatabaseFixtureAssetRepositoryTest {

    private static final Instant SAVED = Instant.parse("2026-08-15T09:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseFixtureAssetRepository repository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseFixtureAssetRepository(
                jdbc, mapper, Clock.fixed(SAVED, ZoneOffset.UTC));
    }

    @Test
    void persistsOnlyVerifiedDescriptorMetadataAndPayloadFreeEvent() throws Exception {
        StoredFixtureAsset stored = repository.saveIfRevision(
                0, descriptor(scope("tenant-a"), 0, FixtureLifecycle.DRAFT), author())
                .orElseThrow();

        assertThat(stored.descriptor().revision()).isEqualTo(1);
        assertThat(stored.descriptor().metadata().createdAt()).isEqualTo(SAVED);
        assertThat(repository.findHead(scope("tenant-a"), "prime-applicant"))
                .contains(stored);
        assertThat(repository.revisions(scope("tenant-a"), "prime-applicant"))
                .containsExactly(stored);
        String eventJson = jdbc.queryForObject(
                "SELECT event_json FROM rg_correctness_outbox", String.class);
        FixtureAssetChanged event = mapper.readValue(eventJson, FixtureAssetChanged.class);
        assertThat(event.fixtureAssetRef()).isEqualTo(stored.exactRef());
        assertThat(event.materialRef()).isEqualTo(stored.descriptor().materialRef());
        assertThat(mapper.readTree(eventJson).has("payload")).isFalse();
        String canonicalJson = jdbc.queryForObject(
                "SELECT canonical_json FROM rg_fixture_asset_heads", String.class);
        assertThat(canonicalJson).doesNotContain("customerPhone", "secret-value");
    }

    @Test
    void enforcesFullScopeCasAndImmutableHistory() throws Exception {
        StoredFixtureAsset tenantA = repository.saveIfRevision(
                0, descriptor(scope("tenant-a"), 0, FixtureLifecycle.DRAFT), author())
                .orElseThrow();
        StoredFixtureAsset tenantB = repository.saveIfRevision(
                0, descriptor(scope("tenant-b"), 0, FixtureLifecycle.DRAFT), author())
                .orElseThrow();

        assertThat(repository.findHead(scope("tenant-a"), "prime-applicant")).contains(tenantA);
        assertThat(repository.findHead(scope("tenant-b"), "prime-applicant")).contains(tenantB);
        assertThat(repository.findHead(
                new EnterpriseScope("tenant-a", "other", "credit", "test", "sg"),
                "prime-applicant")).isEmpty();

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return repository.saveIfRevision(
                        1, descriptor(scope("tenant-a"), 1, FixtureLifecycle.PROPOSED), author());
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.saveIfRevision(
                        1, descriptor(scope("tenant-a"), 1, FixtureLifecycle.PROPOSED), reviewer());
            });
            start.countDown();
            assertThat(List.of(first.get(), second.get()).stream()
                    .filter(Optional::isPresent)).hasSize(1);
        }
        assertThat(repository.revisions(scope("tenant-a"), "prime-applicant"))
                .extracting(value -> value.descriptor().revision())
                .containsExactly(2L, 1L);
    }

    @Test
    void recordsExactUsageAndRejectsUnknownOrDriftedFixtureRefs() {
        StoredFixtureAsset stored = repository.saveIfRevision(
                0, descriptor(scope("tenant-a"), 0, FixtureLifecycle.ACTIVE), author())
                .orElseThrow();
        ExactAssetRef consumer = asset("SCENARIO_DRAFT_SET", "loan-cases", 4, 'd');

        repository.replaceUsageForConsumer(
                scope("tenant-a"), consumer, List.of(stored.exactRef(), stored.exactRef()));

        assertThat(repository.usages(scope("tenant-a"), stored.exactRef(), 100))
                .containsExactly(new FixtureAssetRepository.FixtureUsage(stored.exactRef(), consumer));
        assertThat(repository.resolveExact(scope("tenant-a"), List.of(stored.exactRef())))
                .containsExactly(stored);
        assertThatThrownBy(() -> repository.resolveExact(
                scope("tenant-a"), List.of(asset("FIXTURE_ASSET", "prime-applicant", 1, 'f'))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drifted");
        assertThatThrownBy(() -> repository.replaceUsageForConsumer(
                scope("tenant-b"), consumer, List.of(stored.exactRef())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void refusesColumnTamperingAndRollsBackWhenOutboxFails() {
        repository.saveIfRevision(
                0, descriptor(scope("tenant-a"), 0, FixtureLifecycle.DRAFT), author())
                .orElseThrow();
        jdbc.update("UPDATE rg_fixture_asset_heads SET material_fingerprint = ?",
                fingerprint('f'));
        assertThatThrownBy(() -> repository.findHead(scope("tenant-a"), "prime-applicant"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");

        setUp();
        jdbc.execute("DROP TABLE rg_correctness_outbox");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                repository.saveIfRevision(
                        0, descriptor(scope("tenant-a"), 0, FixtureLifecycle.DRAFT), author())))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_asset_heads", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_asset_revisions", Integer.class)).isZero();
    }

    private FixtureAssetDescriptor descriptor(
            EnterpriseScope scope, long revision, FixtureLifecycle lifecycle) {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new FixtureAssetDescriptor(
                "", "prime-applicant", revision, scope, "Prime applicant",
                new FixtureSource(SourceKind.SAMPLE, null),
                asset("FIXTURE_MATERIAL", "prime-applicant", 3, 'a'),
                new ExactSchemaRef("loan-request", 2, fingerprint('b')),
                "prime", lifecycle, "INTERNAL", author(),
                new RedactionDescriptor("redaction-v2", List.of("/phone"), true),
                new RetentionDescriptor("retention-v2", 30,
                        Instant.parse("2026-09-15T00:00:00Z")),
                new QualityProfile(true, true, 0, 0), List.of("loan", "golden"),
                new AuditMetadata(forged, forged, reviewer(), reviewer()));
    }

    private static EnterpriseScope scope(String tenant) {
        return new EnterpriseScope(tenant, "org-a", "credit", "test", "sg");
    }

    private static PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private static PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static ExactAssetRef asset(String kind, String id, long revision, char seed) {
        return new ExactAssetRef(kind, id, revision, fingerprint(seed));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
