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

class DatabaseCapabilityProposalAuthoringTest {
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private TransactionTemplate transactions;
    private CapabilityProposalAuthoringService service;

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
    void replaysExactReceiptAcrossRepositoryRestartAndRejectsMaterialDrift() {
        var draft = BusinessMirrorAuthoringFixtures.proposal("trip-attribution", 0, "first");
        var first = transactions.execute(status -> service.create(
                draft, "proposal:create:response-loss", BusinessMirrorAuthoringFixtures.identity()));
        CapabilityProposalAuthoringService restarted = service(dataSource);
        var replay = transactions.execute(status -> restarted.create(
                draft, "proposal:create:response-loss", BusinessMirrorAuthoringFixtures.identity()));

        assertThat(first).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(replay.receipt().result().revision()).isEqualTo(1);
        assertThatThrownBy(() -> transactions.execute(status -> restarted.create(
                BusinessMirrorAuthoringFixtures.proposal("trip-attribution", 0, "different"),
                "proposal:create:response-loss", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_proposal_draft_revisions", Long.class))
                .isEqualTo(1);
    }

    @Test
    void retainsHistoryProvidesStablePagesAndReportsOptimisticConflict() {
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.proposal("cancellation-attribution", 0, "v1"),
                "proposal:create:history", BusinessMirrorAuthoringFixtures.identity()));
        transactions.execute(status -> service.save("cancellation-attribution", 1,
                BusinessMirrorAuthoringFixtures.proposal("cancellation-attribution", 1, "v2"),
                "proposal:save:history", BusinessMirrorAuthoringFixtures.identity()));
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.proposal("refund-eligibility", 0, "v1"),
                "proposal:create:refund", BusinessMirrorAuthoringFixtures.identity()));

        assertThat(service.revisions("cancellation-attribution",
                BusinessMirrorAuthoringFixtures.identity()))
                .extracting(StoredCapabilityProposalDraft::revision).containsExactly(2L, 1L);
        CapabilityProposalPage first = service.list("", 1, BusinessMirrorAuthoringFixtures.identity());
        CapabilityProposalPage second = service.list(
                first.nextCursor(), 1, BusinessMirrorAuthoringFixtures.identity());
        assertThat(first.items()).extracting(StoredCapabilityProposalDraft::proposalId)
                .containsExactly("cancellation-attribution");
        assertThat(second.items()).extracting(StoredCapabilityProposalDraft::proposalId)
                .containsExactly("refund-eligibility");

        assertThatThrownBy(() -> transactions.execute(status -> service.save(
                "cancellation-attribution", 1,
                BusinessMirrorAuthoringFixtures.proposal("cancellation-attribution", 1, "stale"),
                "proposal:save:stale", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.BUSINESS_MIRROR.PROPOSAL_REVISION_CONFLICT");
                    assertThat(failure.problem().retryable()).isTrue();
                    assertThat(failure.problem().details()).containsEntry("currentRevision", 2L);
                });
    }

    @Test
    void isolatesIdenticalIdsByCompleteScopeAndRejectsBodyScopeDrift() {
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.proposal("trip-query", 0, "primary"),
                "proposal:create:scope-primary", BusinessMirrorAuthoringFixtures.identity()));
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                "ride-hailing", "safety", "cancellation", "test", "sg");
        var otherIdentity = BusinessMirrorAuthoringFixtures.identity(other, "bob", "correlation-2");
        transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.proposal(other, "trip-query", 0, "other"),
                "proposal:create:scope-other", otherIdentity));

        assertThat(service.find("trip-query", BusinessMirrorAuthoringFixtures.identity())
                .draft().businessIntent().expectedValue()).isEqualTo("primary");
        assertThat(service.find("trip-query", otherIdentity)
                .draft().businessIntent().expectedValue()).isEqualTo("other");
        assertThatThrownBy(() -> transactions.execute(status -> service.create(
                BusinessMirrorAuthoringFixtures.proposal(other, "scope-drift", 0, "invalid"),
                "proposal:create:scope-drift", BusinessMirrorAuthoringFixtures.identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.BUSINESS_MIRROR.PROPOSAL_SCOPE_MISMATCH"));
    }

    @Test
    void rollsBackDraftHistoryAndReceiptAsOneTransaction() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.create(BusinessMirrorAuthoringFixtures.proposal("rollback", 0, "v1"),
                    "proposal:create:rollback", BusinessMirrorAuthoringFixtures.identity());
            throw new IllegalStateException("response failed before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_proposal_drafts", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_proposal_draft_revisions", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_mirror_proposal_save_receipts", Long.class)).isZero();
    }

    @Test
    void serializesConcurrentReplicasIntoOneMutationAndOneExactReplay() throws Exception {
        CapabilityProposalAuthoringService first = service(dataSource);
        CapabilityProposalAuthoringService second = service(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<CapabilityProposalSaveCoordinator.Outcome> left =
                CompletableFuture.supplyAsync(() -> after(start, first));
        CompletableFuture<CapabilityProposalSaveCoordinator.Outcome> right =
                CompletableFuture.supplyAsync(() -> after(start, second));
        start.countDown();

        var outcomes = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        assertThat(outcomes).extracting(CapabilityProposalSaveCoordinator.Outcome::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(outcomes.get(0).receipt()).isEqualTo(outcomes.get(1).receipt());
    }

    private CapabilityProposalSaveCoordinator.Outcome after(
            CountDownLatch start, CapabilityProposalAuthoringService target) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return transaction.execute(status -> target.create(
                BusinessMirrorAuthoringFixtures.proposal("concurrent", 0, "v1"),
                "proposal:create:concurrent", BusinessMirrorAuthoringFixtures.identity()));
    }

    private CapabilityProposalAuthoringService service(DataSource source) {
        JdbcTemplate local = new JdbcTemplate(source);
        var repository = new DatabaseCapabilityProposalDraftRepository(local, mapper);
        repository.init();
        var receipts = new DatabaseCapabilityProposalSaveReceiptRepository(local, mapper);
        receipts.init();
        return new CapabilityProposalAuthoringService(repository,
                new CapabilityProposalSaveCoordinator(receipts, mapper), mapper);
    }
}
