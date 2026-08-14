package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDomainCapabilityPackageAuthoringTest {
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private TransactionTemplate transactions;
    private DomainCapabilityPackageAuthoringService service;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        mapper = new ObjectMapper().findAndRegisterModules();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = service(dataSource);
    }

    @Test
    void replaysExactReceiptAcrossRepositoryRestartAfterResponseLoss() {
        var draft = BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 0, "first");
        var first = transactions.execute(status -> service.create(
                draft, "package:create:response-loss", BusinessMirrorAuthoringFixtures.identity()));

        DomainCapabilityPackageAuthoringService restarted = service(dataSource);
        var replay = transactions.execute(status -> restarted.create(
                draft, "package:create:response-loss", BusinessMirrorAuthoringFixtures.identity()));

        assertThat(first).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(replay.receipt().result().revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_draft_revisions", Long.class)).isEqualTo(1);
    }

    @Test
    void rejectsSameKeyWithDifferentCanonicalCommand() {
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 0, "first"),
                "package:create:drift", BusinessMirrorAuthoringFixtures.identity()));

        assertThatThrownBy(() -> transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 0, "different"),
                "package:create:drift", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void retainsHistoryAndProvidesStableBoundedScopeIndex() {
        var first = transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 0, "v1"),
                "package:create:history", BusinessMirrorAuthoringFixtures.identity()));
        transactions.execute(status -> service.save("cancellation-fee", 1,
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 1, "v2"),
                "package:save:history", BusinessMirrorAuthoringFixtures.identity()));
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("driver-support", 0, "v1"),
                "package:create:driver", BusinessMirrorAuthoringFixtures.identity()));

        assertThat(first).isNotNull();
        assertThat(service.find("cancellation-fee", BusinessMirrorAuthoringFixtures.identity()).revision())
                .isEqualTo(2);
        assertThat(service.revisions("cancellation-fee", BusinessMirrorAuthoringFixtures.identity()))
                .extracting(StoredDomainCapabilityPackageDraft::revision).containsExactly(2L, 1L);
        DomainCapabilityPackagePage firstPage = service.list("", 1, BusinessMirrorAuthoringFixtures.identity());
        DomainCapabilityPackagePage secondPage = service.list(
                firstPage.nextCursor(), 1, BusinessMirrorAuthoringFixtures.identity());
        assertThat(firstPage.items()).extracting(StoredDomainCapabilityPackageDraft::packageId)
                .containsExactly("cancellation-fee");
        assertThat(secondPage.items()).extracting(StoredDomainCapabilityPackageDraft::packageId)
                .containsExactly("driver-support");
    }

    @Test
    void isolatesIdenticalPackageIdsByCompleteEnterpriseScope() {
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 0, "primary"),
                "package:create:scope-primary", BusinessMirrorAuthoringFixtures.identity()));
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                "ride-hailing", "safety", "cancellation", "test", "sg");
        var otherIdentity = BusinessMirrorAuthoringFixtures.identity(other, "bob", "correlation-2");
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft(other, "cancellation-fee", 0, "other"),
                "package:create:scope-other", otherIdentity));

        assertThat(service.find("cancellation-fee", BusinessMirrorAuthoringFixtures.identity())
                .draft().assumptions()).containsExactly("primary");
        assertThat(service.find("cancellation-fee", otherIdentity).draft().assumptions())
                .containsExactly("other");
    }

    @Test
    void rejectsBodyScopeThatDoesNotMatchVerifiedIdentity() {
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                "ride-hailing", "safety", "cancellation", "test", "sg");

        assertThatThrownBy(() -> transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft(other, "scope-drift", 0, "v1"),
                "package:create:scope-drift", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.BUSINESS_MIRROR.PACKAGE_SCOPE_MISMATCH"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_drafts", Long.class)).isZero();
    }

    @Test
    void rejectsMissingIdempotencyKeyBeforeAnyMutation() {
        assertThatThrownBy(() -> transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("missing-key", 0, "v1"),
                "", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_drafts", Long.class)).isZero();
    }

    @Test
    void failsClosedWhenReceiptColumnsAndJsonDisagree() {
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("receipt-integrity", 0, "v1"),
                "package:create:receipt-integrity", BusinessMirrorAuthoringFixtures.identity()));
        jdbc.update("""
                UPDATE business_mirror_package_save_receipts
                SET request_fingerprint = ? WHERE idempotency_key = ?
                """, "sha256:" + "f".repeat(64), "package:create:receipt-integrity");
        var receipts = new DatabaseDomainCapabilityPackageSaveReceiptRepository(jdbc, mapper);

        assertThatThrownBy(() -> receipts.find(
                BusinessMirrorAuthoringFixtures.SCOPE, "package:create:receipt-integrity"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored Package receipt integrity check failed");
    }

    @Test
    void reportsOptimisticConflictWithoutOverwritingCurrentRevision() {
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 0, "v1"),
                "package:create:conflict", BusinessMirrorAuthoringFixtures.identity()));
        transactions.execute(status -> service.save("cancellation-fee", 1,
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 1, "v2"),
                "package:save:conflict-first", BusinessMirrorAuthoringFixtures.identity()));

        assertThatThrownBy(() -> transactions.execute(status -> service.save("cancellation-fee", 1,
                BusinessMirrorAuthoringFixtures.draft("cancellation-fee", 1, "stale"),
                "package:save:conflict-stale", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT");
                    assertThat(failure.problem().retryable()).isTrue();
                    assertThat(failure.problem().details()).containsEntry("currentRevision", 2L);
                });
        assertThat(service.find("cancellation-fee", BusinessMirrorAuthoringFixtures.identity())
                .draft().assumptions()).containsExactly("v2");
    }

    @Test
    void rollsBackDraftHistoryAndReceiptAsOneTransaction() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.create(BusinessMirrorAuthoringFixtures.draft("rollback", 0, "v1"),
                    "package:create:rollback", BusinessMirrorAuthoringFixtures.identity());
            throw new IllegalStateException("response failed before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_drafts", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_draft_revisions", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_save_receipts", Long.class)).isZero();
    }

    @Test
    void serializesConcurrentReplicasIntoOneMutationAndOneExactReplay() throws Exception {
        DomainCapabilityPackageAuthoringService first = service(dataSource);
        DomainCapabilityPackageAuthoringService second = service(dataSource);
        TransactionTemplate firstTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate secondTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<DomainCapabilityPackageSaveCoordinator.Outcome> left =
                CompletableFuture.supplyAsync(() -> after(start, firstTransaction, first));
        CompletableFuture<DomainCapabilityPackageSaveCoordinator.Outcome> right =
                CompletableFuture.supplyAsync(() -> after(start, secondTransaction, second));
        start.countDown();

        var outcomes = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        assertThat(outcomes).extracting(DomainCapabilityPackageSaveCoordinator.Outcome::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(outcomes.get(0).receipt()).isEqualTo(outcomes.get(1).receipt());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_package_draft_revisions", Long.class)).isEqualTo(1);
    }

    private DomainCapabilityPackageSaveCoordinator.Outcome after(
            CountDownLatch start,
            TransactionTemplate transaction,
            DomainCapabilityPackageAuthoringService target) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return transaction.execute(status -> target.create(
                BusinessMirrorAuthoringFixtures.draft("concurrent", 0, "v1"),
                "package:create:concurrent", BusinessMirrorAuthoringFixtures.identity()));
    }

    private DomainCapabilityPackageAuthoringService service(DataSource source) {
        JdbcTemplate local = new JdbcTemplate(source);
        var repository = new DatabaseDomainCapabilityPackageDraftRepository(local, mapper);
        repository.init();
        var receipts = new DatabaseDomainCapabilityPackageSaveReceiptRepository(local, mapper);
        receipts.init();
        return new DomainCapabilityPackageAuthoringService(repository,
                new DomainCapabilityPackageSaveCoordinator(receipts, mapper), mapper);
    }
}
