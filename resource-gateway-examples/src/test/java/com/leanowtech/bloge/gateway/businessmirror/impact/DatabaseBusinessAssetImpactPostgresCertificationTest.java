package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabaseBusinessAssetImpactRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationFactRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Native PostgreSQL certification for the projection outbox, leases, and reverse index. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DatabaseBusinessAssetImpactPostgresCertificationTest {
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
    void appliesDdlAndFencesProjectionClaimsAcrossIndependentReplicas() throws Exception {
        DataSource firstSource = postgres.getPostgresDatabase();
        DataSource secondSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260814_002__business_mirror_package_compilation.sql"),
                new ClassPathResource("db/postgresql/V20260814_007__business_mirror_asset_impact.sql"))
                .execute(firstSource);
        Replica first = replica(firstSource);
        Replica second = replica(secondSource);
        PackageCompilationReceipt receipt = BusinessAssetImpactFixtures.receipt(mapper, 1, '1');
        first.transactions().executeWithoutResult(status -> {
            assertThat(first.facts().reserveRevision(
                    BusinessAssetImpactFixtures.SCOPE, receipt.packageId())).isEqualTo(1);
            first.facts().append(BusinessAssetImpactFixtures.SCOPE, receipt);
            assertThat(first.impacts().enqueue(BusinessAssetImpactFixtures.SCOPE, receipt)).isTrue();
        });
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<BusinessAssetImpactRepository.ProjectionLease> left =
                CompletableFuture.supplyAsync(() -> claimAfter(start, first, "replica-a"));
        CompletableFuture<BusinessAssetImpactRepository.ProjectionLease> right =
                CompletableFuture.supplyAsync(() -> claimAfter(start, second, "replica-b"));
        start.countDown();

        List<BusinessAssetImpactRepository.ProjectionLease> claims = java.util.Arrays.asList(
                left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(claims).filteredOn(java.util.Objects::nonNull).singleElement();
        BusinessAssetImpactRepository.ProjectionLease lease = claims.stream()
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        Replica owner = lease.leaseOwner().equals("replica-a") ? first : second;
        owner.transactions().executeWithoutResult(status -> {
            assertThat(owner.impacts().project(BusinessAssetImpactFixtures.SCOPE, receipt)
                    .replayed()).isFalse();
            assertThat(owner.impacts().complete(lease)).isTrue();
        });

        JdbcTemplate jdbc = new JdbcTemplate(firstSource);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM business_mirror_asset_impact_outbox WHERE package_id = ?",
                String.class, receipt.packageId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_asset_impact_projections WHERE package_id = ?",
                Long.class, receipt.packageId())).isEqualTo(5);
        assertThat(first.impacts().query(BusinessAssetImpactFixtures.SCOPE,
                new BusinessAssetSelector(com.leanowtech.bloge.gateway.businessmirror.domain
                        .BusinessAssetRef.Kind.RESOURCE, "trip-api", "customer-registry"),
                "", 25).items()).singleElement();
        assertThat(jdbc.queryForObject("SHOW fsync", String.class)).isEqualTo("on");
        assertThat(jdbc.queryForObject("SHOW synchronous_commit", String.class)).isEqualTo("on");
    }

    private BusinessAssetImpactRepository.ProjectionLease claimAfter(
            CountDownLatch start, Replica replica, String owner) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return replica.transactions().execute(status -> replica.impacts()
                .claim(owner, Duration.ofMinutes(5)).orElse(null));
    }

    private Replica replica(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var facts = new DatabasePackageCompilationFactRepository(jdbc, mapper);
        facts.init();
        var impacts = new DatabaseBusinessAssetImpactRepository(jdbc, mapper);
        impacts.init();
        return new Replica(facts, impacts,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private record Replica(
            DatabasePackageCompilationFactRepository facts,
            DatabaseBusinessAssetImpactRepository impacts,
            TransactionTemplate transactions) {
    }
}
