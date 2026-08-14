package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationReceiptRepository;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationService;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompiler;
import com.leanowtech.bloge.gateway.businessmirror.compilation.UnavailablePackageCompilationAuthority;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.implementation.DatabaseCapabilityImplementationBindingRepository;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceRepository;
import com.leanowtech.bloge.gateway.businessmirror.implementation.DatabaseCapabilityImplementationConformanceRepository;
import com.leanowtech.bloge.gateway.businessmirror.implementation.StoredCapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.businessmirror.simulation.DatabaseCapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
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
import java.time.Instant;
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

    @Test
    void appliesImplementationBindingDdlAndExactlyReplaysAcrossReplicas() throws Exception {
        DataSource firstDataSource = postgres.getPostgresDatabase();
        DataSource secondDataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260814_005__business_mirror_implementation_binding.sql"))
                .execute(firstDataSource);
        var first = new DatabaseCapabilityImplementationBindingRepository(
                new JdbcTemplate(firstDataSource), mapper);
        var second = new DatabaseCapabilityImplementationBindingRepository(
                new JdbcTemplate(secondDataSource), mapper);
        first.init();
        second.init();
        CapabilitySnapshot.Scope scope = new CapabilitySnapshot.Scope(
                "tenant", "customer-service", "refund", "test", "sg");
        Instant now = Instant.parse("2026-08-14T10:00:00Z");
        CapabilityImplementationBinding binding = new CapabilityImplementationBinding(
                "", "binding-postgres-1", 1, "", scope,
                implementationRef("CAPABILITY_PROPOSAL_DRAFT", "proposal-1", '1'),
                implementationRef("PROPOSAL_SIMULATION_EVIDENCE", "simulation-1", '2'),
                implementationRef("CAPABILITY", "refund-lookup", '3'),
                implementationFingerprint('4'), "runtime:refund:v1",
                implementationFingerprint('5'), "1.0.0", implementationFingerprint('6'),
                "refund-platform", List.of("sg"), true, true, now.minusSeconds(1),
                now.plusSeconds(3600), now).seal(mapper);
        StoredCapabilityImplementationBinding stored =
                new StoredCapabilityImplementationBinding("", implementationFingerprint('7'),
                        binding, new VisualRunEvidenceSeal("", binding.fingerprint(), "TEST",
                        "postgres-certification-key", now, "detached-signature"));
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Boolean> left = CompletableFuture.supplyAsync(() -> {
            await(start);
            return first.create(stored).created();
        });
        CompletableFuture<Boolean> right = CompletableFuture.supplyAsync(() -> {
            await(start);
            return second.create(stored).created();
        });
        start.countDown();

        assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(false, true);
        assertThat(first.find(scope, binding.bindingId())).contains(stored);
        assertThat(new JdbcTemplate(firstDataSource).queryForObject(
                "SELECT COUNT(*) FROM rg_bm_implementation_binding WHERE binding_id = ?",
                Long.class, binding.bindingId())).isEqualTo(1);
    }

    @Test
    void appliesImplementationConformanceDdlAndFencesTwoIndependentReplicas() throws Exception {
        DataSource firstDataSource = postgres.getPostgresDatabase();
        DataSource secondDataSource = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260814_006__business_mirror_implementation_conformance.sql"))
                .execute(firstDataSource);
        var first = new DatabaseCapabilityImplementationConformanceRepository(
                new JdbcTemplate(firstDataSource), mapper);
        var second = new DatabaseCapabilityImplementationConformanceRepository(
                new JdbcTemplate(secondDataSource), mapper);
        first.init();
        second.init();
        CapabilitySnapshot.Scope scope = new CapabilitySnapshot.Scope(
                "tenant", "customer-service", "refund", "test", "sg");
        CapabilityImplementationConformanceRepository.Registration registration =
                new CapabilityImplementationConformanceRepository.Registration(scope,
                        "conformance-postgres-1", "proposal-postgres-1", 1,
                        implementationRef("PROPOSAL_IMPLEMENTATION_BINDING",
                                "binding-postgres-1", '4'), implementationFingerprint('7'));
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<CapabilityImplementationConformanceRepository.Claim> left =
                CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return first.claim(registration, "replica-a", Duration.ofMinutes(10));
                });
        CompletableFuture<CapabilityImplementationConformanceRepository.Claim> right =
                CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return second.claim(registration, "replica-b", Duration.ofMinutes(10));
                });
        start.countDown();

        var claims = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        assertThat(claims).extracting(
                CapabilityImplementationConformanceRepository.Claim::outcome)
                .containsExactlyInAnyOrder(
                        CapabilityImplementationConformanceRepository.Outcome.ACQUIRED,
                        CapabilityImplementationConformanceRepository.Outcome.IN_PROGRESS);
        assertThat(new JdbcTemplate(firstDataSource).queryForObject(
                "SELECT COUNT(*) FROM rg_bm_implementation_conformance WHERE binding_id = ?",
                Long.class, registration.implementationBindingRef().id())).isEqualTo(1);
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

    private static void await(CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static MirrorArtifactRef implementationRef(
            String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, implementationFingerprint(fingerprint));
    }

    private static String implementationFingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
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
