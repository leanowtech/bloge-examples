package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseGraphDraftSaveReceiptRepositoryTest {

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseGraphDraftRepository graphs;
    private DatabaseGraphDraftSaveReceiptRepository receipts;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        mapper = new ObjectMapper().findAndRegisterModules();
        jdbc = new JdbcTemplate(dataSource);
        graphs = new DatabaseGraphDraftRepository(jdbc, mapper);
        graphs.init();
        receipts = new DatabaseGraphDraftSaveReceiptRepository(jdbc, graphs);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void replaysTheExactReceiptAcrossRepositoryAndCoordinatorRestart() {
        GraphDraft request = simpleDraft("", 0, "restart-safe");
        GraphDraftSaveCommand command = GraphDraftSaveCommand.create(
                request, "alice", "canvas", "Create", "test");
        GraphDraftSaveCoordinator first = new GraphDraftSaveCoordinator(receipts, mapper);

        GraphDraftSaveCoordinator.GraphDraftSaveOutcome stored = transactions.execute(status ->
                first.execute("graph-save:restart-1", command, () -> graphs.save(request)));

        DatabaseGraphDraftSaveReceiptRepository reloadedReceipts =
                new DatabaseGraphDraftSaveReceiptRepository(jdbc, graphs);
        GraphDraftSaveCoordinator reloaded = new GraphDraftSaveCoordinator(reloadedReceipts, mapper);
        AtomicInteger duplicateMutations = new AtomicInteger();
        GraphDraftSaveCoordinator.GraphDraftSaveOutcome replay = transactions.execute(status ->
                reloaded.execute("graph-save:restart-1", command, () -> {
                    duplicateMutations.incrementAndGet();
                    return graphs.save(request);
                }));

        assertThat(stored).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.draft()).isEqualTo(stored.draft());
        assertThat(duplicateMutations).hasValue(0);
        assertThat(graphs.all()).hasSize(1);
    }

    @Test
    void rollsBackTheDraftAndReceiptAsOneTransaction() {
        GraphDraft request = simpleDraft("", 0, "rollback-safe");
        GraphDraftSaveCommand command = GraphDraftSaveCommand.create(
                request, "alice", "canvas", "Create", "test");
        GraphDraftSaveCoordinator coordinator = new GraphDraftSaveCoordinator(receipts, mapper);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            coordinator.execute("graph-save:rollback-1", command, () -> graphs.save(request));
            throw new IllegalStateException("simulate failure before transaction commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_drafts", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM visual_graph_draft_save_receipts", Long.class)).isZero();
        assertThat(receipts.find(command.scope(), "graph-save:rollback-1")).isEmpty();
    }

    @Test
    void serializesConcurrentReplicasAtTheDatabaseCommandLock() throws Exception {
        GraphDraft request = simpleDraft("", 0, "replica-safe");
        GraphDraftSaveCommand command = GraphDraftSaveCommand.create(
                request, "alice", "canvas", "Create", "test");
        GraphDraftSaveCoordinator first = new GraphDraftSaveCoordinator(receipts, mapper);
        GraphDraftSaveCoordinator second = new GraphDraftSaveCoordinator(
                new DatabaseGraphDraftSaveReceiptRepository(jdbc, graphs), mapper);
        AtomicInteger mutations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<GraphDraftSaveCoordinator.GraphDraftSaveOutcome> left = CompletableFuture.supplyAsync(
                () -> runAfter(start, first, command, mutations));
        CompletableFuture<GraphDraftSaveCoordinator.GraphDraftSaveOutcome> right = CompletableFuture.supplyAsync(
                () -> runAfter(start, second, command, mutations));
        start.countDown();

        GraphDraftSaveCoordinator.GraphDraftSaveOutcome leftResult = left.get(10, TimeUnit.SECONDS);
        GraphDraftSaveCoordinator.GraphDraftSaveOutcome rightResult = right.get(10, TimeUnit.SECONDS);
        assertThat(mutations).hasValue(1);
        assertThat(leftResult.draft()).isEqualTo(rightResult.draft());
        assertThat(List.of(leftResult.replayed(), rightResult.replayed()))
                .containsExactlyInAnyOrder(false, true);
        assertThat(graphs.all()).hasSize(1);
    }

    private GraphDraftSaveCoordinator.GraphDraftSaveOutcome runAfter(
            CountDownLatch start,
            GraphDraftSaveCoordinator coordinator,
            GraphDraftSaveCommand command,
            AtomicInteger mutations) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return transactions.execute(status -> coordinator.execute(
                "graph-save:replica-1",
                command,
                () -> {
                    mutations.incrementAndGet();
                    return graphs.save(command.draft());
                }));
    }

    private static GraphDraft simpleDraft(String draftId, long revision, String graphName) {
        return new GraphDraft(
                "",
                draftId,
                revision,
                graphName,
                "tenant-a",
                "local",
                "test",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "bloge:transform",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );
    }
}
