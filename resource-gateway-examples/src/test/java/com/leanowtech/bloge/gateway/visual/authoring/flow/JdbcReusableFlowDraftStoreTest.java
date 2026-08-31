package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReusableFlowDraftStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String REQUEST = "sha256:" + "a".repeat(64);
    private static final String CONTENT = "sha256:" + "b".repeat(64);

    @Test
    void createUpdateHistoryAndExactReplaySurviveRestart() {
        Fixture fixture = fixture("durable");
        JdbcReusableFlowDraftStore store = fixture.store();
        ReusableFlowSaveResult created = store.save(intent(ExpectedRevision.create(), "create", "First"));
        ReusableFlowSaveResult updated = store.save(intent(ExpectedRevision.match(1), "update", "Second"));

        ReusableFlowSaveResult replay = store.save(intent(ExpectedRevision.match(1), "update", "Second"));
        JdbcReusableFlowDraftStore reopened = store(fixture.jdbc(), new AtomicInteger(100));

        assertThat(created.draft().revision()).isEqualTo(1);
        assertThat(updated.draft().revision()).isEqualTo(2);
        assertThat(updated.draft().draftId()).isEqualTo(created.draft().draftId());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.draft()).isEqualTo(updated.draft());
        assertThat(reopened.findHead(SCOPE, "tool")).contains(updated.draft());
        assertThat(reopened.findRevision(SCOPE, "tool", 1)).contains(created.draft());
        assertThat(reopened.findRevisionByStrongEtag(SCOPE, "tool", created.strongEtag()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.draft()).isEqualTo(created.draft());
                    assertThat(stored.receipt()).isEqualTo(created.receipt());
                    assertThat(stored.strongEtag()).isEqualTo(created.strongEtag());
                });
        assertThat(reopened.findRevisionByStrongEtag(SCOPE, "tool", "W/\"weak\"")).isEmpty();
        assertThat(reopened.findRevisionByStrongEtag(SCOPE, "tool", "\"unknown\"")).isEmpty();
    }

    @Test
    void changedReplayConflictsAndFailedCasDoesNotOccupyKey() {
        Fixture fixture = fixture("cas");
        JdbcReusableFlowDraftStore store = fixture.store();
        store.save(intent(ExpectedRevision.create(), "create", "First"));

        assertCode(store, intent(ExpectedRevision.match(7), "stale", "Stale"),
                ReusableFlowFailure.Code.CAS_MISMATCH);
        assertCode(store, intent(ExpectedRevision.match(7), "stale", "Stale"),
                ReusableFlowFailure.Code.CAS_MISMATCH);
        store.save(intent(ExpectedRevision.match(1), "update", "Second"));
        assertCode(store, intent(ExpectedRevision.match(1), "update", "Changed"),
                ReusableFlowFailure.Code.CONFLICT);
    }

    @Test
    void readsAreScopeBoundAndTamperedAuthorityFailsClosed() {
        Fixture fixture = fixture("tamper");
        ReusableFlowSaveResult saved = fixture.store().save(
                intent(ExpectedRevision.create(), "create", "First"));
        AuthoringScope other = new AuthoringScope("other", "project", "dev");
        assertThat(fixture.store().findHead(other, "tool")).isEmpty();

        String draftJson = fixture.jdbc().queryForObject("""
                SELECT draft_json FROM rg_authoring_flow_revisions
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=? AND revision=?
                """, String.class, SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(),
                "tool", saved.draft().revision());
        fixture.jdbc().update("""
                UPDATE rg_authoring_flow_revisions SET draft_json=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=? AND revision=?
                """, draftJson.replace(CONTENT, "sha256:" + "c".repeat(64)),
                SCOPE.tenantId(), SCOPE.projectId(),
                SCOPE.environmentId(), "tool", saved.draft().revision());

        assertThatThrownBy(() -> fixture.store().findRevision(SCOPE, "tool", 1))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.INTEGRITY);
    }

    @Test
    void simultaneousExactCreateHasOneCommitAndOneReplay() throws Exception {
        Fixture fixture = fixture("concurrent-replay");
        CyclicBarrier firstIdentifier = new CyclicBarrier(2);
        AtomicInteger sequence = new AtomicInteger();
        java.util.function.Supplier<String> racingIds = () -> {
            int value = sequence.incrementAndGet();
            if (value <= 2) {
                try {
                    firstIdentifier.await(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return "race-" + value;
        };
        JdbcReusableFlowDraftStore first = store(fixture.jdbc(), racingIds);
        JdbcReusableFlowDraftStore second = store(fixture.jdbc(), racingIds);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReusableFlowSaveResult> left = executor.submit(
                    () -> first.save(intent(ExpectedRevision.create(), "same", "First")));
            Future<ReusableFlowSaveResult> right = executor.submit(
                    () -> second.save(intent(ExpectedRevision.create(), "same", "First")));
            List<ReusableFlowSaveResult> results = List.of(
                    left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));

            assertThat(results).extracting(ReusableFlowSaveResult::replayed)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results.get(0).draft()).isEqualTo(results.get(1).draft());
            assertThat(results.get(0).strongEtag()).isEqualTo(results.get(1).strongEtag());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void existingHeadWithBrokenRevisionClosureFailsAsIntegrityRatherThanNotFound() {
        Fixture fixture = fixture("head-closure");
        fixture.store().save(intent(ExpectedRevision.create(), "create", "First"));
        fixture.jdbc().execute("SET REFERENTIAL_INTEGRITY FALSE");
        fixture.jdbc().update("""
                UPDATE rg_authoring_flow_heads SET strong_etag='"tampered"'
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=?
                """, SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), "tool");

        assertThatThrownBy(() -> fixture.store().findHead(SCOPE, "tool"))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.INTEGRITY);
    }

    private static void assertCode(JdbcReusableFlowDraftStore store, ReusableFlowSaveIntent intent,
                                   ReusableFlowFailure.Code code) {
        assertThatThrownBy(() -> store.save(intent))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code()).isEqualTo(code);
    }

    private static ReusableFlowSaveIntent intent(ExpectedRevision expected, String key, String name) {
        String request = "Changed".equals(name) ? "sha256:" + "e".repeat(64) : REQUEST;
        return new ReusableFlowSaveIntent(SCOPE, "alice", "tool", expected, key, request, CONTENT,
                command(name));
    }

    private static ReusableFlowCommand command(String name) {
        SchemaEnvelope input = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id"));
        ReusableFlowCommand.Node node = new ReusableFlowCommand.Node("node", "Node",
                new ReusableFlowCommand.ComposableRef.ApiResource(
                        "customer", 1, "sha256:" + "d".repeat(64)),
                List.of(new ReusableFlowCommand.Input("$.id",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.id"))));
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION,
                new ReusableFlowCommand.Flow(name, ReusableFlowCommand.Kind.TOOL, "description",
                        new ReusableFlowCommand.Contract(input, input),
                        new ReusableFlowCommand.Graph(List.of(node),
                                new ReusableFlowCommand.Output("node", "$")),
                        new ReusableFlowCommand.Layout(Map.of("node",
                                new ReusableFlowCommand.Position(10, 20)))));
    }

    private static Fixture fixture(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:flow-draft-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260901_014__reusable_flow_drafts.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        return new Fixture(jdbc, store(jdbc, new AtomicInteger()));
    }

    private static JdbcReusableFlowDraftStore store(JdbcTemplate jdbc, AtomicInteger sequence) {
        return store(jdbc, () -> "id-" + sequence.incrementAndGet());
    }

    private static JdbcReusableFlowDraftStore store(
            JdbcTemplate jdbc, java.util.function.Supplier<String> identifiers) {
        return new JdbcReusableFlowDraftStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new ObjectMapper().findAndRegisterModules(), identifiers);
    }

    private record Fixture(JdbcTemplate jdbc, JdbcReusableFlowDraftStore store) { }
}
