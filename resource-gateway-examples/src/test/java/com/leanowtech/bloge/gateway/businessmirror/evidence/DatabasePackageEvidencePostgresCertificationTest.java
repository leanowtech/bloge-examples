package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Native PostgreSQL certification for evidence DDL, cross-replica leases, and atomic publication. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DatabasePackageEvidencePostgresCertificationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedPostgres postgres;

    @BeforeAll
    void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                .setServerConfig("lock_timeout", "5s")
                .start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void fencesClaimsAcrossReplicasAndCommitsIndexTasksAndCompletionTogether()
            throws Exception {
        DataSource firstSource = postgres.getPostgresDatabase();
        DataSource secondSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(
                new ClassPathResource(
                        "db/postgresql/V20260814_002__business_mirror_package_compilation.sql"),
                new ClassPathResource(
                        "db/postgresql/V20260815_001__business_mirror_package_evidence.sql"))
                .execute(firstSource);
        Replica first = replica(firstSource);
        Replica second = replica(secondSource);
        DomainFidelityInventory inventory = PackageEvidenceFixtures.inventory(mapper, 'd',
                PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt =
                PackageEvidenceFixtures.receiptWithInventory(mapper, inventory);
        first.transactions().executeWithoutResult(status -> {
            assertThat(first.evidence().enqueue(PackageEvidenceFixtures.SCOPE, receipt)).isTrue();
        });
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<PackageEvidenceRepository.ProjectionLease> left =
                CompletableFuture.supplyAsync(() -> claimAfter(start, first, "replica-a"));
        CompletableFuture<PackageEvidenceRepository.ProjectionLease> right =
                CompletableFuture.supplyAsync(() -> claimAfter(start, second, "replica-b"));
        start.countDown();

        List<PackageEvidenceRepository.ProjectionLease> claims = java.util.Arrays.asList(
                left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(claims).filteredOn(java.util.Objects::nonNull).singleElement();
        PackageEvidenceRepository.ProjectionLease lease = claims.stream()
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        Replica owner = lease.leaseOwner().equals("replica-a") ? first : second;
        owner.transactions().executeWithoutResult(status -> {
            PackageEvidenceRepository.ProjectionReservation reservation =
                    owner.evidence().reserveProjectionRevision(
                            PackageEvidenceFixtures.SCOPE, receipt.packageId(),
                            receipt.compilationRevision());
            PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt,
                    Optional.of(inventory), Optional.empty(), reservation.projectionRevision(),
                    reservation.reservedAt(), mapper);
            owner.evidence().append(index, "/business-mirror/?task=evidence");
            assertThat(owner.evidence().complete(lease)).isTrue();
        });

        JdbcTemplate jdbc = new JdbcTemplate(firstSource);
        assertThat(jdbc.queryForObject("""
                        SELECT status FROM business_mirror_package_evidence_outbox
                        WHERE package_id = ?
                        """, String.class, receipt.packageId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_evidence_indexes", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_evidence_owner_tasks", Long.class))
                .isEqualTo(2L);
        assertThat(first.evidence().findCurrent(
                PackageEvidenceFixtures.SCOPE, receipt.packageId())).isPresent();
        assertThat(jdbc.queryForObject("SHOW fsync", String.class)).isEqualTo("on");
        assertThat(jdbc.queryForObject("SHOW synchronous_commit", String.class)).isEqualTo("on");
    }

    private PackageEvidenceRepository.ProjectionLease claimAfter(
            CountDownLatch start, Replica replica, String owner) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return replica.transactions().execute(status -> replica.evidence()
                .claim(owner, Duration.ofMinutes(5)).orElse(null));
    }

    private Replica replica(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var evidence = new DatabasePackageEvidenceRepository(jdbc, mapper);
        evidence.init();
        return new Replica(evidence,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private record Replica(
            DatabasePackageEvidenceRepository evidence,
            TransactionTemplate transactions) {
    }
}
