package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.AssetKind;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureReceipt;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoringFixtureRepositoryTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final TestingArtifactScope SCOPE = new TestingArtifactScope(
            "tenant-a", "knowledge", "support", "test", "ap-southeast-1");

    private JdbcTemplate jdbc;
    private DatabaseAuthoringFixtureRepository repository;
    private DatabaseTestSecurityEventRepository securityEvents;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:authoring-fixture-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseAuthoringFixtureRepository(
                jdbc, new DataSourceTransactionManager(dataSource), MAPPER);
        securityEvents = new DatabaseTestSecurityEventRepository(jdbc, MAPPER);
        repository.init();
        securityEvents.init();
    }

    @Test
    void storesCiphertextAndAuditAtomicallyWithinCompleteScope() {
        StoredAuthoringFixture candidate = fixture(
                SCOPE, "echo-golden", 1, Instant.now().plusSeconds(300));
        TestSecurityEvent event = audit("echo-golden");

        StoredAuthoringFixture stored = repository.create(
                SCOPE, candidate, 0, securityEvents.boundAppend(event));

        assertThat(repository.find(SCOPE, "echo-golden", 1)).contains(stored);
        assertThat(repository.find(
                new TestingArtifactScope(
                        "tenant-a", "knowledge", "another-project",
                        "test", "ap-southeast-1"),
                "echo-golden",
                1)).isEmpty();
        assertThat(jdbc.queryForObject("""
                        SELECT protected_payload
                        FROM rg_visual_authoring_fixtures
                        WHERE fixture_id = ?
                        """, String.class, "echo-golden"))
                .startsWith("v1.test-v1.")
                .doesNotContain("customer@example.test", "fixture-secret");
        assertThat(securityEvents.recent(10))
                .extracting(TestSecurityEvent::eventType)
                .containsExactly("AUTHORING_FIXTURE_SAVED");

        StoredAuthoringFixture rolledBack = fixture(
                SCOPE, "rollback", 1, Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> repository.create(
                SCOPE,
                rolledBack,
                0,
                ignored -> {
                    throw new IllegalStateException("audit unavailable");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(repository.find(SCOPE, "rollback", 1)).isEmpty();
    }

    @Test
    void enforcesMonotonicRevisionAndRejectsProjectionOrCommitmentTampering() {
        repository.create(
                SCOPE,
                fixture(SCOPE, "revisioned", 1, Instant.now().plusSeconds(300)),
                0,
                ignored -> {
                });

        assertThatThrownBy(() -> repository.create(
                SCOPE,
                fixture(SCOPE, "revisioned", 1, Instant.now().plusSeconds(300)),
                0,
                ignored -> {
                }))
                .isInstanceOf(AuthoringFixtureRevisionConflictException.class)
                .extracting("currentRevision")
                .isEqualTo(1L);
        assertThat(repository.latestRevision(SCOPE, "revisioned")).isEqualTo(1);

        jdbc.update("""
                UPDATE rg_visual_authoring_fixtures
                SET classification = ?
                WHERE fixture_id = ?
                """, "PUBLIC", "revisioned");
        assertThatThrownBy(() -> repository.find(SCOPE, "revisioned", 1))
                .isInstanceOf(AuthoringFixtureIntegrityException.class);

        repository.create(
                SCOPE,
                fixture(SCOPE, "commitment", 1, Instant.now().plusSeconds(300)),
                0,
                ignored -> {
                });
        jdbc.update("""
                UPDATE rg_visual_authoring_fixtures
                SET record_fingerprint = ?
                WHERE fixture_id = ?
                """, "sha256:" + "0".repeat(64), "commitment");
        assertThatThrownBy(() -> repository.find(SCOPE, "commitment", 1))
                .isInstanceOf(AuthoringFixtureIntegrityException.class);
    }

    @Test
    void rejectsRevisionThatRebindsAnExistingFixtureLineage() {
        repository.create(
                SCOPE,
                fixture(SCOPE, "stable-lineage", 1, Instant.now().plusSeconds(300)),
                0,
                ignored -> {
                });

        assertThatThrownBy(() -> repository.create(
                SCOPE,
                fixture(
                        SCOPE,
                        "stable-lineage",
                        2,
                        Instant.now().plusSeconds(300),
                        "PUBLIC"),
                1,
                ignored -> {
                }))
                .isInstanceOf(AuthoringFixtureLineageConflictException.class);
        assertThat(repository.latestRevision(SCOPE, "stable-lineage")).isEqualTo(1);
    }

    @Test
    void expiryClearsProtectedPayloadAndPreservesVerifiableTombstone() {
        Instant expiredAt = Instant.now().minusSeconds(10);
        StoredAuthoringFixture stored = fixture(
                SCOPE, "expired-golden", 1, expiredAt);
        repository.create(SCOPE, stored, 0, ignored -> {
        });

        assertThat(repository.expireDue(Instant.now(), 10)).isEqualTo(1);

        StoredAuthoringFixture tombstone = repository.find(
                SCOPE, "expired-golden", 1).orElseThrow();
        assertThat(tombstone.state()).isEqualTo(StoredAuthoringFixture.EXPIRED);
        assertThat(tombstone.payloadAvailable()).isFalse();
        assertThat(tombstone.protectedPayload()).isEmpty();
        assertThat(tombstone.descriptor()).isEqualTo(stored.descriptor());
        assertThat(jdbc.queryForObject("""
                        SELECT protected_payload
                        FROM rg_visual_authoring_fixtures
                        WHERE fixture_id = ?
                        """, String.class, "expired-golden"))
                .isNull();
        assertThat(repository.expireDue(Instant.now(), 10)).isZero();
    }

    private static StoredAuthoringFixture fixture(
            TestingArtifactScope scope,
            String fixtureId,
            long revision,
            Instant expiresAt) {
        return fixture(scope, fixtureId, revision, expiresAt, "CONFIDENTIAL");
    }

    private static StoredAuthoringFixture fixture(
            TestingArtifactScope scope,
            String fixtureId,
            long revision,
            Instant expiresAt,
            String classification) {
        Instant createdAt = expiresAt.minusSeconds(60);
        FixtureReceipt receipt = new FixtureReceipt(
                FixtureReceipt.SCHEMA_VERSION,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                fixtureId,
                revision,
                SourceKind.OPERATOR_TEST_CASE,
                AssetKind.OPERATOR,
                "demo:echo",
                "draft-a",
                7,
                fingerprint('a'),
                fingerprint('b'),
                fingerprint('c'),
                fingerprint('d'),
                classification,
                "retention.v1",
                expiresAt,
                "redaction.v1",
                List.of("/inputs/customer/email"),
                createdAt,
                "author-a",
                true,
                false);
        String envelope = "v1.test-v1."
                + "AAAAAAAAAAAAAAAA"
                + "."
                + "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        return AuthoringFixtureIntegrity.attach(
                MAPPER,
                new StoredAuthoringFixture(
                        StoredAuthoringFixture.SCHEMA_VERSION,
                        scope,
                        receipt,
                        StoredAuthoringFixture.AVAILABLE,
                        true,
                        envelope,
                        ""));
    }

    private static TestSecurityEvent audit(String fixtureId) {
        return new TestSecurityEvent(
                0,
                Instant.now(),
                "correlation-a",
                SCOPE.tenantId(),
                SCOPE.environmentId(),
                "author-a",
                "AUTHORING_FIXTURE_SAVED",
                "SUCCESS",
                "RG.AUTHORING.FIXTURE_SAVED",
                Map.of("fixtureId", fixtureId, "fixtureRevision", 1));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
