package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageGovernanceProjectionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** Native PostgreSQL proof for monotonic ANEKE projection ingestion across replicas. */
@Timeout(120)
class DatabasePackageGovernanceProjectionPostgresCertificationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DomainCapabilityPackageGovernanceProjectionIntegrity integrity =
            new DomainCapabilityPackageGovernanceProjectionIntegrity(mapper);

    @Test
    void appliesDeploymentDdlAndFencesConflictingSuccessorsAcrossReplicas() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                .setServerConfig("lock_timeout", "5s")
                .start()) {
            DataSource firstDataSource = postgres.getPostgresDatabase();
            DataSource secondDataSource = postgres.getPostgresDatabase();
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/postgresql/V20260815_004__package_governance_projection.sql"))
                    .execute(firstDataSource);
            PackageGovernanceProjectionRepository first = repository(firstDataSource);
            PackageGovernanceProjectionRepository second = repository(secondDataSource);
            CapabilitySnapshot.Scope scope = PackageGovernanceProtocolFixtures.bundle().scope();
            first.append(projection(1, 'a', scope));

            try (var workers = Executors.newFixedThreadPool(2)) {
                var outcomes = workers.invokeAll(List.of(
                                attempt(first, projection(2, 'b', scope)),
                                attempt(second, projection(2, 'c', scope))))
                        .stream().map(future -> {
                            try {
                                return future.get();
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                        }).toList();
                assertThat(outcomes).containsExactlyInAnyOrder("COMMITTED", "GENERATION_FORK");
            }

            JdbcTemplate jdbc = new JdbcTemplate(firstDataSource);
            assertThat(jdbc.queryForObject("""
                    SELECT external_generation
                    FROM business_mirror_package_governance_heads
                    WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                      AND environment_id = ? AND region_id = ? AND package_id = ?
                    """, Long.class, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), "cancellation-package"))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM business_mirror_package_governance_projections
                    WHERE package_id = ?
                    """, Long.class, "cancellation-package")).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SHOW fsync", String.class)).isEqualTo("on");
            assertThat(jdbc.queryForObject("SHOW synchronous_commit", String.class))
                    .isEqualTo("on");
        }
    }

    private PackageGovernanceProjectionRepository repository(DataSource dataSource) {
        var repository = new DatabasePackageGovernanceProjectionRepository(
                new JdbcTemplate(dataSource), mapper, integrity,
                new DataSourceTransactionManager(dataSource));
        repository.init();
        return repository;
    }

    private Callable<String> attempt(
            PackageGovernanceProjectionRepository repository,
            DomainCapabilityPackageGovernanceProjection projection) {
        return () -> {
            try {
                repository.append(projection);
                return "COMMITTED";
            } catch (PackageGovernanceProjectionRepository.Violation violation) {
                return violation.reason().name();
            }
        };
    }

    private DomainCapabilityPackageGovernanceProjection projection(
            long generation, char material, CapabilitySnapshot.Scope scope) {
        PackageRegistryIngestBundle bundle = PackageGovernanceProtocolFixtures.bundle();
        var base = PackageGovernanceProtocolFixtures.projection(
                PackageGovernanceProtocolFixtures.signer());
        MirrorArtifactRef gate = new MirrorArtifactRef("ANEKE_PACKAGE_GATE_DECISION",
                "gate:cancellation-package:" + generation, generation,
                PackageGovernanceProtocolFixtures.fingerprint(material));
        var projectionMaterial =
                new DomainCapabilityPackageGovernanceProjectionIntegrity.Material(
                        "aneke-governance:cancellation-package:1", generation, generation, scope,
                        bundle.packageSnapshot().artifactRef(), bundle.artifactRef(),
                        bundle.evidenceIndex().artifactRef(), base.registryRecordRef(),
                        DomainCapabilityPackageGovernanceProjection.Status.ACCEPTED, gate,
                        PackageGovernanceProtocolFixtures.fingerprint(material),
                        PackageGovernanceProtocolFixtures.GOVERNED_AT,
                        PackageGovernanceProtocolFixtures.GOVERNED_AT,
                        PackageGovernanceProtocolFixtures.GOVERNED_AT
                                .plus(Duration.ofHours(24)),
                        "aneke:tool-studio");
        return integrity.seal(projectionMaterial, PackageGovernanceProtocolFixtures.signer());
    }
}
