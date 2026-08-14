package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Native PostgreSQL certification for Package DDL and cross-replica idempotency locks. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DatabaseDomainCapabilityPackagePostgresCertificationTest {
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
    void appliesDeploymentDdlAndSerializesTwoIndependentReplicas() throws Exception {
        DataSource firstDataSource = postgres.getPostgresDatabase();
        DataSource secondDataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260814_001__business_mirror_package_authoring.sql"))
                .execute(firstDataSource);
        Replica first = replica(firstDataSource);
        Replica second = replica(secondDataSource);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<DomainCapabilityPackageSaveCoordinator.Outcome> left =
                CompletableFuture.supplyAsync(() -> executeAfter(start, first));
        CompletableFuture<DomainCapabilityPackageSaveCoordinator.Outcome> right =
                CompletableFuture.supplyAsync(() -> executeAfter(start, second));
        start.countDown();

        var outcomes = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(outcomes).extracting(DomainCapabilityPackageSaveCoordinator.Outcome::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(outcomes.get(0).receipt()).isEqualTo(outcomes.get(1).receipt());
        JdbcTemplate jdbc = new JdbcTemplate(firstDataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_draft_revisions", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_save_receipts", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SHOW fsync", String.class)).isEqualTo("on");
        assertThat(jdbc.queryForObject("SHOW synchronous_commit", String.class)).isEqualTo("on");
    }

    private DomainCapabilityPackageSaveCoordinator.Outcome executeAfter(
            CountDownLatch start, Replica replica) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return replica.transactions().execute(status -> replica.service().create(
                BusinessMirrorAuthoringFixtures.draft("postgres-concurrent", 0, "v1"),
                "package:create:postgres", BusinessMirrorAuthoringFixtures.identity()));
    }

    private Replica replica(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var drafts = new DatabaseDomainCapabilityPackageDraftRepository(jdbc, mapper);
        drafts.init();
        var receipts = new DatabaseDomainCapabilityPackageSaveReceiptRepository(jdbc, mapper);
        receipts.init();
        var service = new DomainCapabilityPackageAuthoringService(drafts,
                new DomainCapabilityPackageSaveCoordinator(receipts, mapper), mapper);
        return new Replica(service,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private record Replica(
            DomainCapabilityPackageAuthoringService service,
            TransactionTemplate transactions) {
    }
}
