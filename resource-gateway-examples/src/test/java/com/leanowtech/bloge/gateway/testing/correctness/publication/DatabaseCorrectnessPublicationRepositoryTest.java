package com.leanowtech.bloge.gateway.testing.correctness.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilerTest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.Failure;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCorrectnessPublicationRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-08-15T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private DatabaseCorrectnessPublicationRepository repository;
    private FrozenCompilationInput source;
    private CorrectnessCompilationReport report;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:correctness-publication-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        repository = new DatabaseCorrectnessPublicationRepository(jdbc, mapper);
        source = new CorrectnessCompilerTest().input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        CorrectnessCompiler compiler = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        report = compiler.compileReport(source);
    }

    @Test
    void persistsCasHistoryAndAtomicCommittedManifest() {
        StoredCorrectnessPublicationAttempt preparing = state(1, AttemptStage.PREPARING, null,
                List.of(), Failure.none());
        assertThat(repository.saveAttemptIfVersion(source.scope(), 0, preparing)).isPresent();
        StoredCorrectnessPublicationAttempt compiled = state(
                2, AttemptStage.COMPILED, report, List.of(), Failure.none());
        assertThat(repository.saveAttemptIfVersion(source.scope(), 1, compiled)).isPresent();
        StoredCorrectnessPublicationAttempt registering = state(
                3, AttemptStage.REGISTERING, report, List.of(), Failure.none());
        assertThat(repository.saveAttemptIfVersion(source.scope(), 2, registering)).isPresent();
        ExactAssetRef fixtureRef = compiledRef("FIXTURE_BUNDLE");
        StoredCorrectnessPublicationAttempt fixtureVerified = state(
                4, AttemptStage.REGISTERING, report, List.of(fixtureRef), Failure.none());
        assertThat(repository.saveAttemptIfVersion(source.scope(), 3, fixtureVerified)).isPresent();

        StoredCorrectnessPublication storedPublication = publication();
        ExactAssetRef suiteRef = compiledRef("TEST_SUITE");
        StoredCorrectnessPublicationAttempt committed = state(
                5, AttemptStage.COMMITTED, report,
                List.of(fixtureRef, suiteRef), Failure.none());
        CorrectnessPublicationCompleted event = event(storedPublication);
        var result = repository.commitIfVersion(
                source.scope(), 4, committed, storedPublication, event).orElseThrow();

        assertThat(result.attempt()).isEqualTo(committed);
        assertThat(result.publication()).isEqualTo(storedPublication);
        assertThat(repository.findPublication(source.scope(), "publication-1"))
                .contains(storedPublication);
        assertThat(repository.attemptHistory(source.scope(), "attempt-1"))
                .extracting(value -> value.attempt().stage())
                .containsExactly(
                        AttemptStage.PREPARING, AttemptStage.COMPILED,
                        AttemptStage.REGISTERING, AttemptStage.REGISTERING,
                        AttemptStage.COMMITTED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsConcurrentCasAndDuplicateIdempotencyKey() {
        StoredCorrectnessPublicationAttempt preparing = state(
                1, AttemptStage.PREPARING, null, List.of(), Failure.none());
        repository.saveAttemptIfVersion(source.scope(), 0, preparing).orElseThrow();

        assertThat(repository.saveAttemptIfVersion(source.scope(), 0, preparing)).isEmpty();
        PublicationAttempt conflicting = new PublicationAttempt(
                "", "attempt-2", 1, preparing.attempt().idempotencyKeyFingerprint(),
                preparing.attempt().coordinate(), AttemptStage.PREPARING,
                List.of(), Failure.none(), metadata(CREATED));
        assertThat(repository.saveAttemptIfVersion(
                source.scope(), 0,
                new StoredCorrectnessPublicationAttempt(
                        "", source.scope(), conflicting, null))).isEmpty();
        assertThat(repository.findAttemptByIdempotencyFingerprint(
                source.scope(), preparing.attempt().idempotencyKeyFingerprint()))
                .contains(preparing);
    }

    @Test
    void rejectsIllegalStageJumpAndVerifiedAssetRegression() {
        StoredCorrectnessPublicationAttempt preparing = state(
                1, AttemptStage.PREPARING, null, List.of(), Failure.none());
        repository.saveAttemptIfVersion(source.scope(), 0, preparing).orElseThrow();
        assertThatThrownBy(() -> repository.saveAttemptIfVersion(
                source.scope(), 1,
                state(2, AttemptStage.COMMITTED, report,
                        report.compiledAssets().stream().map(value -> value.assetRef()).toList(),
                        Failure.none())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transition");

        repository.saveAttemptIfVersion(
                source.scope(), 1,
                state(2, AttemptStage.COMPILED, report, List.of(), Failure.none())).orElseThrow();
        ExactAssetRef fixtureRef = compiledRef("FIXTURE_BUNDLE");
        repository.saveAttemptIfVersion(
                source.scope(), 2,
                state(3, AttemptStage.REGISTERING, report,
                        List.of(fixtureRef), Failure.none())).orElseThrow();
        assertThatThrownBy(() -> repository.saveAttemptIfVersion(
                source.scope(), 3,
                state(4, AttemptStage.REGISTERING, report,
                        List.of(), Failure.none())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transition");
    }

    @Test
    void refusesCanonicalOrIndexedTampering() {
        StoredCorrectnessPublicationAttempt preparing = state(
                1, AttemptStage.PREPARING, null, List.of(), Failure.none());
        repository.saveAttemptIfVersion(source.scope(), 0, preparing).orElseThrow();
        jdbc.update("""
                UPDATE rg_correctness_publication_attempts SET stage = 'COMMITTED'
                WHERE attempt_id = 'attempt-1'
                """);
        assertThatThrownBy(() -> repository.findAttempt(source.scope(), "attempt-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void commitRollsBackAttemptAndManifestWhenOutboxFails() {
        repository.saveAttemptIfVersion(
                source.scope(), 0,
                state(1, AttemptStage.PREPARING, null, List.of(), Failure.none())).orElseThrow();
        repository.saveAttemptIfVersion(
                source.scope(), 1,
                state(2, AttemptStage.COMPILED, report, List.of(), Failure.none())).orElseThrow();
        repository.saveAttemptIfVersion(
                source.scope(), 2,
                state(3, AttemptStage.REGISTERING, report,
                        List.of(compiledRef("FIXTURE_BUNDLE")), Failure.none())).orElseThrow();
        jdbc.execute("DROP TABLE rg_correctness_outbox");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        StoredCorrectnessPublication publication = publication();
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                repository.commitIfVersion(
                        source.scope(), 3,
                        state(4, AttemptStage.COMMITTED, report,
                                report.compiledAssets().stream()
                                        .map(value -> value.assetRef()).toList(),
                                Failure.none()),
                        publication, event(publication))))
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.findAttempt(source.scope(), "attempt-1").orElseThrow()
                .attempt().stage()).isEqualTo(AttemptStage.REGISTERING);
        assertThat(repository.findPublication(source.scope(), "publication-1")).isEmpty();
        assertThat(repository.attemptHistory(source.scope(), "attempt-1")).hasSize(3);
    }

    @Test
    void scopeIsPartOfEveryLookupKey() {
        StoredCorrectnessPublicationAttempt preparing = state(
                1, AttemptStage.PREPARING, null, List.of(), Failure.none());
        repository.saveAttemptIfVersion(source.scope(), 0, preparing).orElseThrow();
        EnterpriseScope other = new EnterpriseScope(
                "tenant-b", "org-a", "loan", "test", "sg");

        assertThat(repository.findAttempt(other, "attempt-1")).isEmpty();
        assertThat(repository.attemptHistory(other, "attempt-1")).isEmpty();
    }

    private StoredCorrectnessPublicationAttempt state(
            long version,
            AttemptStage stage,
            CorrectnessCompilationReport compilationReport,
            List<ExactAssetRef> verifiedAssets,
            Failure failure
    ) {
        PublicationAttempt attempt = new PublicationAttempt(
                "", "attempt-1", version, fp('a'), source.coordinate(), stage,
                verifiedAssets, failure, metadata(CREATED.plusSeconds(version)));
        return new StoredCorrectnessPublicationAttempt(
                "", source.scope(), attempt, compilationReport);
    }

    private StoredCorrectnessPublication publication() {
        ExactAssetRef fixtureRef = compiledRef("FIXTURE_BUNDLE");
        ExactAssetRef suiteRef = compiledRef("TEST_SUITE");
        CorrectnessPublication value = new CorrectnessPublication(
                "", "publication-1", source.scope(), source.coordinate().target(),
                source.coordinate().definitionRef(), source.coordinate().inventoryRef(),
                source.coordinate().scenarioDraftSetRef(), source.coordinate().oracleRefs(),
                source.coordinate().assertionSetRefs(), source.coordinate().fixtureAssetRefs(),
                List.of(fixtureRef), suiteRef, CorrectnessCompiler.COMPILER_VERSION,
                report.compilationFingerprint(), metadata(CREATED.plusSeconds(10)));
        return StoredCorrectnessPublication.verified(mapper, value);
    }

    private CorrectnessPublicationCompleted event(StoredCorrectnessPublication publication) {
        CorrectnessPublication value = publication.publication();
        return new CorrectnessPublicationCompleted(
                "", "event-1", source.scope(),
                new ExactAssetRef(
                        "CORRECTNESS_PUBLICATION", value.publicationId(), 1,
                        publication.publicationFingerprint()),
                value.target(), value.definitionRef(), value.inventoryRef(),
                value.scenarioDraftSetRef(), value.compiledFixtureBundleRefs(),
                value.compiledTestSuiteRef(), value.compilationFingerprint(),
                "publisher", value.metadata().updatedAt());
    }

    private ExactAssetRef compiledRef(String kind) {
        return report.compiledAssets().stream()
                .map(value -> value.assetRef())
                .filter(ref -> kind.equals(ref.kind()))
                .findFirst().orElseThrow();
    }

    private AuditMetadata metadata(Instant updatedAt) {
        PrincipalRef actor = new PrincipalRef("publisher", PrincipalKind.USER, "Publisher");
        return new AuditMetadata(CREATED, updatedAt, actor, actor);
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE rg_correctness_publications (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL,
                    publication_id VARCHAR(512) NOT NULL,
                    publication_fingerprint VARCHAR(80) NOT NULL,
                    definition_id VARCHAR(512) NOT NULL,
                    definition_revision BIGINT NOT NULL,
                    definition_fingerprint VARCHAR(80) NOT NULL,
                    inventory_id VARCHAR(512) NOT NULL,
                    inventory_revision BIGINT NOT NULL,
                    inventory_fingerprint VARCHAR(80) NOT NULL,
                    scenario_draft_set_id VARCHAR(512) NOT NULL,
                    scenario_draft_set_revision BIGINT NOT NULL,
                    scenario_draft_set_fingerprint VARCHAR(80) NOT NULL,
                    canonical_json CLOB NOT NULL,
                    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    committed_by VARCHAR(512) NOT NULL,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        publication_id),
                    UNIQUE (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        publication_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE rg_correctness_publication_attempts (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL,
                    attempt_id VARCHAR(512) NOT NULL,
                    state_version BIGINT NOT NULL,
                    idempotency_key_fingerprint VARCHAR(80) NOT NULL,
                    stage VARCHAR(32) NOT NULL,
                    canonical_json CLOB NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        attempt_id),
                    UNIQUE (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        idempotency_key_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE rg_correctness_publication_attempt_history (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL,
                    attempt_id VARCHAR(512) NOT NULL,
                    state_version BIGINT NOT NULL,
                    stage VARCHAR(32) NOT NULL,
                    canonical_json CLOB NOT NULL,
                    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        attempt_id, state_version)
                )
                """);
        jdbc.execute("""
                CREATE TABLE rg_correctness_outbox (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL,
                    event_id VARCHAR(512) NOT NULL,
                    aggregate_kind VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(512) NOT NULL,
                    aggregate_revision BIGINT NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    event_json CLOB NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    published_at TIMESTAMP WITH TIME ZONE,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        event_id)
                )
                """);
    }
}
