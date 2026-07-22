package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorFixtureScopeRepositoryTest {
    private static final Instant BOUND_AT = Instant.parse("2026-07-23T02:00:00Z");

    private JdbcTemplate jdbc;
    private DatabaseMirrorFixtureScopeRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:mirror-fixture-scope-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseMirrorFixtureScopeRepository(jdbc);
        repository.init();
    }

    @Test
    void persistsAcrossRestartAndPreservesTheFirstExactBindingOnRetry() {
        MirrorFixtureScopeBinding binding = binding(scope("org-a", "support"), 'a');

        assertThat(repository.create(binding)).isEqualTo(binding);
        MirrorFixtureScopeBinding retry = new MirrorFixtureScopeBinding(binding.scope(),
                binding.fixtureBundleRef(), BOUND_AT.plusSeconds(30), "retrying-actor");
        assertThat(repository.create(retry)).isEqualTo(binding);

        DatabaseMirrorFixtureScopeRepository restarted =
                new DatabaseMirrorFixtureScopeRepository(jdbc);
        restarted.init();
        assertThat(restarted.find(binding.scope(), "customer-fixture", 1))
                .contains(binding);
    }

    @Test
    void isolatesOrganizationProjectAndRegionEvenInsideOneTenantEnvironment() {
        MirrorFixtureScopeBinding first = binding(scope("org-a", "support"), 'a');
        MirrorFixtureScopeBinding second = binding(scope("org-b", "support"), 'a');
        MirrorFixtureScopeBinding third = binding(scope("org-a", "billing"), 'a');

        repository.create(first);
        repository.create(second);
        repository.create(third);

        assertThat(repository.find(first.scope(), "customer-fixture", 1)).contains(first);
        assertThat(repository.find(second.scope(), "customer-fixture", 1)).contains(second);
        assertThat(repository.find(third.scope(), "customer-fixture", 1)).contains(third);
        assertThat(repository.find(new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "support", "test", "eu"),
                "customer-fixture", 1)).isEmpty();
    }

    @Test
    void rejectsFingerprintMutationUnderOneImmutableScopeCoordinate() {
        MirrorFixtureScopeBinding first = binding(scope("org-a", "support"), 'a');
        repository.create(first);
        MirrorFixtureScopeBinding changed = binding(scope("org-a", "support"), 'b');

        assertThatThrownBy(() -> repository.create(changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void storesOnlyAuthorizationMetadataAndFailsOnIndexedFingerprintTampering() {
        MirrorFixtureScopeBinding binding = binding(scope("org-a", "support"), 'a');
        repository.create(binding);

        assertThat(jdbc.queryForList("""
                        SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = 'MIRROR_FIXTURE_SCOPE_BINDINGS'
                        ORDER BY ORDINAL_POSITION
                        """, String.class))
                .containsExactly("TENANT_ID", "ORGANIZATION_ID", "PROJECT_ID",
                        "ENVIRONMENT_ID", "REGION", "FIXTURE_BUNDLE_ID", "REVISION",
                        "FIXTURE_FINGERPRINT", "BOUND_AT", "BOUND_BY")
                .noneMatch(column -> column.contains("PAYLOAD")
                        || column.contains("VALUE") || column.contains("CONTEXT"));

        jdbc.update("""
                UPDATE mirror_fixture_scope_bindings SET fixture_fingerprint = 'tampered'
                WHERE tenant_id = 'tenant-a' AND organization_id = 'org-a'
                """);
        assertThatThrownBy(() -> repository.find(
                binding.scope(), "customer-fixture", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MirrorFixtureScopeBinding binding(
            CapabilitySnapshot.Scope scope, char fingerprint) {
        return new MirrorFixtureScopeBinding(scope,
                new MirrorArtifactRef("FIXTURE_BUNDLE", "customer-fixture", 1,
                        "sha256:" + String.valueOf(fingerprint).repeat(64)),
                BOUND_AT, "fixture-owner");
    }

    private static CapabilitySnapshot.Scope scope(String organization, String project) {
        return new CapabilitySnapshot.Scope(
                "tenant-a", organization, project, "test", "sg");
    }
}
