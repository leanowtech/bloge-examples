package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationReceiptRepository;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationService;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompiler;
import com.leanowtech.bloge.gateway.businessmirror.compilation.UnavailablePackageCompilationAuthority;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.businessmirror.simulation.DatabaseCapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
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
import java.time.Clock;
import java.time.Duration;
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

    @Test
    void appliesCompilationDdlAndSerializesPackageRevisionAllocationAcrossReplicas() throws Exception {
        DataSource firstDataSource = postgres.getPostgresDatabase();
        DataSource secondDataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260814_001__business_mirror_package_authoring.sql"),
                new ClassPathResource("db/postgresql/V20260814_002__business_mirror_package_compilation.sql"))
                .execute(firstDataSource);
        Replica authoring = replica(firstDataSource);
        authoring.transactions().execute(status -> authoring.service().create(
                BusinessMirrorAuthoringFixtures.draft("postgres-compile", 0, "v1"),
                "package:create:postgres-compile", BusinessMirrorAuthoringFixtures.identity()));
        CompilationReplica first = compilationReplica(firstDataSource);
        CompilationReplica second = compilationReplica(secondDataSource);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<PackageCompilationCoordinator.Outcome> left = CompletableFuture.supplyAsync(
                () -> compileAfter(start, first, "package:compile:postgres:left"));
        CompletableFuture<PackageCompilationCoordinator.Outcome> right = CompletableFuture.supplyAsync(
                () -> compileAfter(start, second, "package:compile:postgres:right"));
        start.countDown();

        var outcomes = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(outcomes).extracting(value -> value.receipt().compilationRevision())
                .containsExactlyInAnyOrder(1L, 2L);
        JdbcTemplate jdbc = new JdbcTemplate(firstDataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_compilations WHERE package_id = ?",
                Long.class, "postgres-compile")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT next_revision FROM business_mirror_package_compilation_heads WHERE package_id = ?",
                Long.class, "postgres-compile")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_compile_receipts WHERE package_id = ?",
                Long.class, "postgres-compile")).isEqualTo(2);
    }

    @Test
    void appliesProposalDdlAndSerializesTwoIndependentProposalReplicas() throws Exception {
        DataSource firstDataSource = postgres.getPostgresDatabase();
        DataSource secondDataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260814_003__business_mirror_proposal_authoring.sql"))
                .execute(firstDataSource);
        ProposalReplica first = proposalReplica(firstDataSource);
        ProposalReplica second = proposalReplica(secondDataSource);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<CapabilityProposalSaveCoordinator.Outcome> left =
                CompletableFuture.supplyAsync(() -> proposalAfter(start, first));
        CompletableFuture<CapabilityProposalSaveCoordinator.Outcome> right =
                CompletableFuture.supplyAsync(() -> proposalAfter(start, second));
        start.countDown();

        var outcomes = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(outcomes).extracting(CapabilityProposalSaveCoordinator.Outcome::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(outcomes.get(0).receipt()).isEqualTo(outcomes.get(1).receipt());
        JdbcTemplate jdbc = new JdbcTemplate(firstDataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_proposal_draft_revisions", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_proposal_save_receipts", Long.class))
                .isEqualTo(1);
    }

    @Test
    void appliesProposalSimulationDdlAndFencesTwoIndependentReplicas() throws Exception {
        DataSource firstDataSource = postgres.getPostgresDatabase();
        DataSource secondDataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260814_004__business_mirror_proposal_simulation.sql"))
                .execute(firstDataSource);
        SimulationReplica first = simulationReplica(firstDataSource);
        SimulationReplica second = simulationReplica(secondDataSource);
        CapabilitySnapshot.Scope scope = new CapabilitySnapshot.Scope(
                "tenant", "customer-service", "refund", "test", "sg");
        CapabilityProposalSimulationRepository.Registration registration =
                new CapabilityProposalSimulationRepository.Registration(scope,
                        "simulation-postgres-1", "proposal-postgres-1", 1,
                        "sha256:" + "a".repeat(64));
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<CapabilityProposalSimulationRepository.Claim> left =
                CompletableFuture.supplyAsync(() -> claimAfter(
                        start, first, registration, "replica-a"));
        CompletableFuture<CapabilityProposalSimulationRepository.Claim> right =
                CompletableFuture.supplyAsync(() -> claimAfter(
                        start, second, registration, "replica-b"));
        start.countDown();

        var claims = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(claims).extracting(CapabilityProposalSimulationRepository.Claim::outcome)
                .containsExactlyInAnyOrder(
                        CapabilityProposalSimulationRepository.Outcome.ACQUIRED,
                        CapabilityProposalSimulationRepository.Outcome.IN_PROGRESS);
        CapabilityProposalSimulationRepository.Claim acquired = claims.stream()
                .filter(value -> value.outcome()
                        == CapabilityProposalSimulationRepository.Outcome.ACQUIRED)
                .findFirst().orElseThrow();
        assertThat(first.transactions().execute(status ->
                first.repository().renew(acquired.lease(), Duration.ofMinutes(10)))
                || second.transactions().execute(status ->
                second.repository().renew(acquired.lease(), Duration.ofMinutes(10)))).isTrue();
        assertThat(new JdbcTemplate(firstDataSource).queryForObject(
                "SELECT COUNT(*) FROM rg_bm_proposal_simulation WHERE proposal_id = ?",
                Long.class, registration.proposalId())).isEqualTo(1);
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

    private PackageCompilationCoordinator.Outcome compileAfter(
            CountDownLatch start, CompilationReplica replica, String key) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return replica.transactions().execute(status -> replica.service().compile(
                "postgres-compile", 1, key, BusinessMirrorAuthoringFixtures.identity()));
    }

    private CompilationReplica compilationReplica(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var drafts = new DatabaseDomainCapabilityPackageDraftRepository(jdbc, mapper);
        drafts.init();
        var facts = new DatabasePackageCompilationFactRepository(jdbc, mapper);
        facts.init();
        var receipts = new DatabasePackageCompilationReceiptRepository(jdbc, mapper);
        receipts.init();
        var compiler = new PackageCompiler(mapper, new UnavailablePackageCompilationAuthority());
        var coordinator = new PackageCompilationCoordinator(
                receipts, facts, compiler, mapper, Clock.systemUTC());
        return new CompilationReplica(new PackageCompilationService(drafts, facts, coordinator),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private CapabilityProposalSaveCoordinator.Outcome proposalAfter(
            CountDownLatch start, ProposalReplica replica) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return replica.transactions().execute(status -> replica.service().create(
                BusinessMirrorAuthoringFixtures.proposal("postgres-proposal", 0, "v1"),
                "proposal:create:postgres", BusinessMirrorAuthoringFixtures.identity()));
    }

    private ProposalReplica proposalReplica(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var drafts = new DatabaseCapabilityProposalDraftRepository(jdbc, mapper);
        drafts.init();
        var receipts = new DatabaseCapabilityProposalSaveReceiptRepository(jdbc, mapper);
        receipts.init();
        var service = new CapabilityProposalAuthoringService(drafts,
                new CapabilityProposalSaveCoordinator(receipts, mapper), mapper);
        return new ProposalReplica(service,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private CapabilityProposalSimulationRepository.Claim claimAfter(
            CountDownLatch start,
            SimulationReplica replica,
            CapabilityProposalSimulationRepository.Registration registration,
            String owner) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return replica.transactions().execute(status -> replica.repository().claim(
                registration, owner, Duration.ofMinutes(5)));
    }

    private SimulationReplica simulationReplica(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = new DatabaseCapabilityProposalSimulationRepository(jdbc, mapper);
        repository.init();
        return new SimulationReplica(repository,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private record Replica(
            DomainCapabilityPackageAuthoringService service,
            TransactionTemplate transactions) {
    }

    private record CompilationReplica(
            PackageCompilationService service,
            TransactionTemplate transactions) {
    }

    private record ProposalReplica(
            CapabilityProposalAuthoringService service,
            TransactionTemplate transactions) {
    }

    private record SimulationReplica(
            DatabaseCapabilityProposalSimulationRepository repository,
            TransactionTemplate transactions) {
    }
}
