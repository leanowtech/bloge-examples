package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabasePackageEvidenceRepositoryTest {
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private PackageEvidenceRepository repository;
    private PackageCompilationReceipt receipt;
    private DomainFidelityInventory inventory;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:package-evidence-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DatabasePackageEvidenceRepository database =
                new DatabasePackageEvidenceRepository(jdbc, mapper);
        database.init();
        repository = database;
        inventory = PackageEvidenceFixtures.inventory(mapper, 'd',
                PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        receipt = PackageEvidenceFixtures.receiptWithInventory(mapper, inventory);
    }

    @Test
    void appendsIndependentProjectionRevisionsAndExposesCurrentDomainHead() {
        PackageEvidenceIndex first = project(1, Optional.empty(), PackageEvidenceFixtures.NOW);
        PackageEvidenceRepository.ProjectionResult inserted = transactions.execute(status -> {
            assertThat(repository.reserveProjectionRevision(
                    PackageEvidenceFixtures.SCOPE, receipt.packageId(), 7).projectionRevision())
                    .isEqualTo(1);
            return repository.append(first, "/business-mirror/?task=evidence");
        });

        assertThat(inserted).isNotNull();
        assertThat(inserted.replayed()).isFalse();
        assertThat(repository.findCurrent(PackageEvidenceFixtures.SCOPE, receipt.packageId()))
                .contains(first);
        PackageEvidenceRepository.CurrentPage page = repository.findCurrentByDomain(
                PackageEvidenceFixtures.SCOPE, "ride-cancellation", "", 10);
        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isEmpty();

        DomainFidelityProfile profile = PackageEvidenceFixtures.profile(mapper, inventory,
                DomainFidelityProfile.MeasurementOutcome.PASS, PackageEvidenceFixtures.NOW);
        PackageEvidenceIndex second = project(2, Optional.of(profile),
                PackageEvidenceFixtures.NOW.plusSeconds(60));
        transactions.executeWithoutResult(status -> {
            assertThat(repository.reserveProjectionRevision(
                    PackageEvidenceFixtures.SCOPE, receipt.packageId(), 7).projectionRevision())
                    .isEqualTo(2);
            repository.append(second, "/business-mirror/?task=evidence");
        });

        assertThat(repository.findCurrent(PackageEvidenceFixtures.SCOPE, receipt.packageId()))
                .contains(second);
        assertThat(repository.find(PackageEvidenceFixtures.SCOPE, receipt.packageId(), 1))
                .contains(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_evidence_indexes", Long.class))
                .isEqualTo(2L);
        assertThat(repository.findTasks(PackageEvidenceFixtures.SCOPE, "", receipt.packageId(),
                EvidenceOwnerTask.Status.SUPERSEDED, 20)).hasSize(2);
    }

    @Test
    void journalsOptimisticTaskLifecycleAndRequiresResolutionEvidence() {
        PackageEvidenceIndex index = project(1, Optional.empty(), PackageEvidenceFixtures.NOW);
        transactions.executeWithoutResult(status -> {
            repository.reserveProjectionRevision(PackageEvidenceFixtures.SCOPE,
                    receipt.packageId(), 7);
            repository.append(index, "/business-mirror/?task=evidence");
        });
        EvidenceOwnerTask open = repository.findTasks(PackageEvidenceFixtures.SCOPE,
                "ride-cancellation", receipt.packageId(), EvidenceOwnerTask.Status.OPEN, 20)
                .getFirst();

        EvidenceOwnerTask acknowledged = transactions.execute(status -> repository.transitionTask(
                PackageEvidenceFixtures.SCOPE, open.taskId(), 1,
                EvidenceOwnerTask.Status.ACKNOWLEDGED, "domain-owner", null,
                PackageEvidenceFixtures.NOW.plusSeconds(30)));
        assertThat(acknowledged).isNotNull();
        assertThat(acknowledged.version()).isEqualTo(2);
        assertThatThrownBy(() -> transactions.execute(status -> repository.transitionTask(
                PackageEvidenceFixtures.SCOPE, open.taskId(), 1,
                EvidenceOwnerTask.Status.RESOLVED, "domain-owner",
                new MirrorArtifactRef("CORRECTNESS_EVIDENCE", "resolution", 1,
                        "sha256:" + "a".repeat(64)),
                PackageEvidenceFixtures.NOW.plusSeconds(60))))
                .isInstanceOf(PackageEvidenceRepository.TaskVersionConflictException.class);

        EvidenceOwnerTask resolved = transactions.execute(status -> repository.transitionTask(
                PackageEvidenceFixtures.SCOPE, open.taskId(), 2,
                EvidenceOwnerTask.Status.RESOLVED, "domain-owner",
                new MirrorArtifactRef("CORRECTNESS_EVIDENCE", "resolution", 1,
                        "sha256:" + "a".repeat(64)),
                PackageEvidenceFixtures.NOW.plusSeconds(60)));
        assertThat(resolved).isNotNull();
        assertThat(resolved.status()).isEqualTo(EvidenceOwnerTask.Status.RESOLVED);
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM business_mirror_evidence_owner_task_events
                        WHERE task_id = ?
                        """, Long.class, open.taskId())).isEqualTo(3L);
    }

    @Test
    void fencesLeaseTakeoverAndQuarantinesPoisonJobs() {
        transactions.executeWithoutResult(status -> repository.enqueue(
                PackageEvidenceFixtures.SCOPE, receipt));
        PackageEvidenceRepository.ProjectionLease first = transactions.execute(
                status -> repository.claim("worker-a", Duration.ofMinutes(5)).orElseThrow());
        assertThat(first).isNotNull();
        jdbc.update("UPDATE business_mirror_package_evidence_outbox "
                + "SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND");
        PackageEvidenceRepository.ProjectionLease second = transactions.execute(
                status -> repository.claim("worker-b", Duration.ofMinutes(5)).orElseThrow());
        assertThat(second).isNotNull();
        assertThat(second.leaseEpoch()).isGreaterThan(first.leaseEpoch());
        Boolean staleCompletion = transactions.execute(status -> repository.complete(first));
        assertThat(staleCompletion).isFalse();

        PackageEvidenceRepository.ProjectionRelease release = transactions.execute(
                status -> repository.release(second, "RG.EVIDENCE.FAILED", 3));
        assertThat(release).isNotNull();
        jdbc.update("UPDATE business_mirror_package_evidence_outbox SET available_at = CURRENT_TIMESTAMP");
        PackageEvidenceRepository.ProjectionLease finalAttempt = transactions.execute(
                status -> repository.claim("worker-c", Duration.ofMinutes(5)).orElseThrow());
        PackageEvidenceRepository.ProjectionRelease quarantined = transactions.execute(
                status -> repository.release(finalAttempt, "RG.EVIDENCE.FAILED", 3));
        assertThat(quarantined.status())
                .isEqualTo(PackageEvidenceRepository.ProjectionJobStatus.QUARANTINED);
        Optional<PackageEvidenceRepository.ProjectionLease> noWork = transactions.execute(
                status -> repository.claim("worker-d", Duration.ofMinutes(5)));
        assertThat(noWork).isEmpty();
    }

    @Test
    void rejectsSameProjectionCoordinateWithDifferentCanonicalContent() {
        PackageEvidenceIndex exact = project(1, Optional.empty(), PackageEvidenceFixtures.NOW);
        transactions.executeWithoutResult(status -> {
            repository.reserveProjectionRevision(PackageEvidenceFixtures.SCOPE,
                    receipt.packageId(), 7);
            repository.append(exact, "/business-mirror/?task=evidence");
        });
        PackageEvidenceIndex drift = new PackageEvidenceIndex(exact.schemaVersion(), "",
                exact.scope(), exact.packageId(), exact.compilationRevision(),
                exact.projectionRevision(), exact.packageSnapshotSource(), exact.readinessSource(),
                exact.businessAssetClosureSource(), exact.domainId(), "TRIP.OTHER",
                exact.layers(), exact.fidelity(), exact.driftSignals(), exact.projectedAt(),
                exact.validUntil()).seal(mapper);

        assertThatThrownBy(() -> transactions.execute(
                status -> repository.append(drift, "/business-mirror/?task=evidence")))
                .isInstanceOf(PackageEvidenceRepository.ProjectionDriftException.class);
    }

    @Test
    void failsClosedWhenDuplicatedScopeDomainOrTaskIndexesAreTampered() {
        PackageEvidenceIndex index = project(1, Optional.empty(), PackageEvidenceFixtures.NOW);
        transactions.executeWithoutResult(status -> {
            repository.reserveProjectionRevision(PackageEvidenceFixtures.SCOPE,
                    receipt.packageId(), 7);
            repository.append(index, "/business-mirror/?task=evidence");
        });
        jdbc.update("UPDATE business_mirror_package_evidence_heads SET domain_id = 'other-domain'");

        assertThatThrownBy(() -> repository.findCurrent(
                PackageEvidenceFixtures.SCOPE, receipt.packageId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");

        jdbc.update("UPDATE business_mirror_package_evidence_heads SET domain_id = ?",
                index.domainId());
        jdbc.update("UPDATE business_mirror_evidence_owner_tasks SET status = 'RESOLVED'");
        assertThatThrownBy(() -> repository.findTasks(PackageEvidenceFixtures.SCOPE,
                "", receipt.packageId(), null, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    private PackageEvidenceIndex project(
            long projectionRevision,
            Optional<DomainFidelityProfile> profile,
            java.time.Instant projectedAt) {
        return PackageEvidenceProjector.project(receipt, Optional.of(inventory), profile,
                projectionRevision, projectedAt, mapper);
    }
}
