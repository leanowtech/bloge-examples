package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageGovernanceProjectionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabasePackageGovernanceProjectionRepositoryTest {
    private ObjectMapper mapper;
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private DomainCapabilityPackageGovernanceProjectionIntegrity integrity;
    private PackageGovernanceProjectionRepository repository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:package-governance-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(dataSource);
        integrity = new DomainCapabilityPackageGovernanceProjectionIntegrity(mapper);
        repository = repository();
    }

    @Test
    void appendsContiguousGenerationsRecoversExactReplayAndSurvivesRestart() {
        DomainCapabilityPackageGovernanceProjection first = projection(1, 'a',
                PackageGovernanceProtocolFixtures.bundle().scope());
        DomainCapabilityPackageGovernanceProjection second = projection(2, 'b', first.scope());

        assertThat(repository.append(first).replayed()).isFalse();
        assertThat(repository.append(first).replayed()).isTrue();
        assertThat(repository.append(second).replayed()).isFalse();

        PackageGovernanceProjectionRepository restarted = repository();
        assertThat(restarted.findCurrent(first.scope(), first.packageSnapshotRef().id()))
                .contains(second);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_governance_projections",
                Long.class)).isEqualTo(2L);
    }

    @Test
    void rejectsRollbackForkGapAndImmutableStreamTakeover() {
        DomainCapabilityPackageGovernanceProjection first = projection(1, 'a',
                PackageGovernanceProtocolFixtures.bundle().scope());
        DomainCapabilityPackageGovernanceProjection second = projection(2, 'b', first.scope());
        repository.append(first);
        repository.append(second);

        assertViolation(() -> repository.append(first),
                PackageGovernanceProjectionRepository.Reason.GENERATION_ROLLBACK);
        assertViolation(() -> repository.append(projection(2, 'c', first.scope())),
                PackageGovernanceProjectionRepository.Reason.GENERATION_FORK);
        assertViolation(() -> repository.append(projection(4, 'd', first.scope())),
                PackageGovernanceProjectionRepository.Reason.GENERATION_GAP);

        DomainCapabilityPackageGovernanceProjection takeover = projection(
                3, 'e', first.scope(), "other-projection", "aneke:other-studio");
        assertViolation(() -> repository.append(takeover),
                PackageGovernanceProjectionRepository.Reason.STREAM_IDENTITY_MISMATCH);
    }

    @Test
    void rejectedBootstrapGapCannotSquatTheImmutableStreamIdentity() {
        CapabilitySnapshot.Scope scope = PackageGovernanceProtocolFixtures.bundle().scope();
        DomainCapabilityPackageGovernanceProjection gap = projection(
                2, 'a', scope, "aneke-governance:poison", "aneke:other-studio");

        assertViolation(() -> repository.append(gap),
                PackageGovernanceProjectionRepository.Reason.BOOTSTRAP_GENERATION_INVALID);

        DomainCapabilityPackageGovernanceProjection legitimate = projection(1, 'b', scope);
        assertThat(repository.append(legitimate).replayed()).isFalse();
        assertThat(repository.findCurrent(scope, legitimate.packageSnapshotRef().id()))
                .contains(legitimate);
    }

    @Test
    void isolatesCompleteEnterpriseScopeAndFailsClosedOnIndexedRowTamper() {
        DomainCapabilityPackageGovernanceProjection first = projection(1, 'a',
                PackageGovernanceProtocolFixtures.bundle().scope());
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                "tenant-b", "mobility", "customer-service", "staging", "sg");
        DomainCapabilityPackageGovernanceProjection isolated = projection(1, 'b', other);
        repository.append(first);
        repository.append(isolated);

        assertThat(repository.findCurrent(first.scope(), first.packageSnapshotRef().id()))
                .contains(first);
        assertThat(repository.findCurrent(other, isolated.packageSnapshotRef().id()))
                .contains(isolated);

        jdbc.update("""
                UPDATE business_mirror_package_governance_projections
                SET evidence_index_fingerprint = ? WHERE tenant_id = ?
                """, PackageGovernanceProtocolFixtures.fingerprint('f'),
                first.scope().tenantId());
        assertViolation(() -> repository.findCurrent(
                        first.scope(), first.packageSnapshotRef().id()),
                PackageGovernanceProjectionRepository.Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void twoRepositoryInstancesCannotCommitDifferentSuccessors() throws Exception {
        DomainCapabilityPackageGovernanceProjection first = projection(1, 'a',
                PackageGovernanceProtocolFixtures.bundle().scope());
        repository.append(first);
        PackageGovernanceProjectionRepository replicaA = repository();
        PackageGovernanceProjectionRepository replicaB = repository();
        DomainCapabilityPackageGovernanceProjection candidateA = projection(2, 'b', first.scope());
        DomainCapabilityPackageGovernanceProjection candidateB = projection(2, 'c', first.scope());

        try (var workers = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<String>> results = workers.invokeAll(List.of(
                    attempt(replicaA, candidateA), attempt(replicaB, candidateB)));
            List<String> outcomes = results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertThat(outcomes).containsExactlyInAnyOrder("COMMITTED", "GENERATION_FORK");
        }
        assertThat(repository.findCurrent(first.scope(), first.packageSnapshotRef().id()))
                .get().extracting(DomainCapabilityPackageGovernanceProjection::externalGeneration)
                .isEqualTo(2L);
    }

    private PackageGovernanceProjectionRepository repository() {
        DatabasePackageGovernanceProjectionRepository value =
                new DatabasePackageGovernanceProjectionRepository(jdbc, mapper, integrity,
                        new DataSourceTransactionManager(dataSource));
        value.init();
        return value;
    }

    private Callable<String> attempt(
            PackageGovernanceProjectionRepository target,
            DomainCapabilityPackageGovernanceProjection projection) {
        return () -> {
            try {
                target.append(projection);
                return "COMMITTED";
            } catch (PackageGovernanceProjectionRepository.Violation violation) {
                return violation.reason().name();
            }
        };
    }

    private DomainCapabilityPackageGovernanceProjection projection(
            long generation, char material, CapabilitySnapshot.Scope scope) {
        return projection(generation, material, scope,
                "aneke-governance:cancellation-package:1", "aneke:tool-studio");
    }

    private DomainCapabilityPackageGovernanceProjection projection(
            long generation,
            char material,
            CapabilitySnapshot.Scope scope,
            String projectionId,
            String issuer) {
        PackageRegistryIngestBundle bundle = PackageGovernanceProtocolFixtures.bundle();
        var base = PackageGovernanceProtocolFixtures.projection(
                PackageGovernanceProtocolFixtures.signer());
        var gate = new MirrorArtifactRef("ANEKE_PACKAGE_GATE_DECISION",
                "gate:cancellation-package:" + generation, generation,
                PackageGovernanceProtocolFixtures.fingerprint(material));
        var source = new DomainCapabilityPackageGovernanceProjectionIntegrity.Material(
                projectionId, generation, generation, scope,
                bundle.packageSnapshot().artifactRef(), bundle.artifactRef(),
                bundle.evidenceIndex().artifactRef(), base.registryRecordRef(),
                DomainCapabilityPackageGovernanceProjection.Status.ACCEPTED, gate,
                PackageGovernanceProtocolFixtures.fingerprint(material),
                PackageGovernanceProtocolFixtures.GOVERNED_AT,
                PackageGovernanceProtocolFixtures.GOVERNED_AT,
                PackageGovernanceProtocolFixtures.GOVERNED_AT.plus(Duration.ofHours(24)),
                issuer);
        return integrity.seal(source, PackageGovernanceProtocolFixtures.signer());
    }

    private static void assertViolation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            PackageGovernanceProjectionRepository.Reason reason) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        PackageGovernanceProjectionRepository.Violation.class,
                        violation -> assertThat(violation.reason()).isEqualTo(reason));
    }
}
