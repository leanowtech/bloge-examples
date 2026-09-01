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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReusableFlowPublicationStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant-a", "project-a", "test");
    private static final String DEPENDENCY = "sha256:" + "d".repeat(64);

    @Test
    void publishesReplaysAndReadsImmutableVersionsAcrossRestart() {
        Fixture fixture = fixture("publish");
        ReusableFlowSaveResult draft = fixture.drafts().save(saveIntent());
        ReusableFlowPublishIntent intent = intent(draft.draft(), "publish-1", "sha256:" + "a".repeat(64));

        ReusableFlowPublishResult first = fixture.publications().publish(intent);
        ReusableFlowPublishResult replay = fixture.publications().publish(intent);
        JdbcReusableFlowPublicationStore reopened = publicationStore(fixture.jdbc(),
                () -> "unused", Clock.systemUTC());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(reopened.findVersion(SCOPE, first.version().publicationId(), 1))
                .contains(first.version());
        assertThat(reopened.findVersion(new AuthoringScope("tenant-b", "project-a", "test"),
                first.version().publicationId(), 1)).isEmpty();
    }

    @Test
    void stableIdentityAllocatesMonotonicVersionsAndRejectsChangedReplay() {
        Fixture fixture = fixture("versions");
        ReusableFlowSaveResult firstDraft = fixture.drafts().save(saveIntent());
        ReusableFlowPublishResult first = fixture.publications().publish(
                intent(firstDraft.draft(), "publish-1", "sha256:" + "a".repeat(64)));
        ReusableFlowSaveResult secondDraft = fixture.drafts().save(new ReusableFlowSaveIntent(
                SCOPE, "alice", "tool", ExpectedRevision.match(1), "save-2",
                "sha256:" + "e".repeat(64), "sha256:" + "f".repeat(64), command("Tool v2")));
        ReusableFlowPublishResult second = fixture.publications().publish(
                intent(secondDraft.draft(), "publish-2", "sha256:" + "b".repeat(64)));

        assertThat(second.version().publicationId()).isEqualTo(first.version().publicationId());
        assertThat(second.version().revision()).isEqualTo(2);
        assertThat(fixture.publications().findLatestVersion(SCOPE, "tool")).contains(second.version());
        assertThat(fixture.publications().findLatestVersion(
                new AuthoringScope("tenant-b", "project-a", "test"), "tool")).isEmpty();
        assertThatThrownBy(() -> fixture.publications().publish(
                intent(firstDraft.draft(), "publish-1", "sha256:" + "c".repeat(64))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CONFLICT);
    }

    @Test
    void damagedVersionFailsClosed() {
        Fixture fixture = fixture("tamper");
        ReusableFlowSaveResult draft = fixture.drafts().save(saveIntent());
        ReusableFlowPublishResult published = fixture.publications().publish(
                intent(draft.draft(), "publish-1", "sha256:" + "a".repeat(64)));
        fixture.jdbc().update("UPDATE rg_authoring_flow_versions SET version_fingerprint=?",
                "sha256:" + "9".repeat(64));

        assertThatThrownBy(() -> fixture.publications().findVersion(
                SCOPE, published.version().publicationId(), 1))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.INTEGRITY);
    }

    @Test
    void concurrentPublishesShareIdentityAndAllocateDistinctMonotonicVersions() throws Exception {
        Fixture fixture = fixture("concurrent");
        ReusableFlowSaveResult draft = fixture.drafts().save(saveIntent());
        JdbcReusableFlowPublicationStore other = publicationStore(fixture.jdbc(),
                () -> "publication-tool", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> fixture.publications().publish(
                    intent(draft.draft(), "publish-left", "sha256:" + "1".repeat(64))));
            var right = executor.submit(() -> other.publish(
                    intent(draft.draft(), "publish-right", "sha256:" + "2".repeat(64))));
            List<ReusableFlowPublishResult> results = List.of(left.get(), right.get());
            assertThat(results).extracting(result -> result.version().publicationId())
                    .containsOnly("publication-tool");
            assertThat(results).extracting(result -> result.version().revision())
                    .containsExactlyInAnyOrder(1, 2);
        }
    }

    private static ReusableFlowPublishIntent intent(
            ReusableFlowDraft draft, String key, String requestFingerprint) {
        return new ReusableFlowPublishIntent(SCOPE, "alice", "tool", key,
                requestFingerprint, "sha256:" + "8".repeat(64), draft);
    }

    private static ReusableFlowSaveIntent saveIntent() {
        return new ReusableFlowSaveIntent(SCOPE, "alice", "tool", ExpectedRevision.create(), "save-1",
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), command("Tool"));
    }

    private static ReusableFlowCommand command(String name) {
        SchemaEnvelope schema = SchemaEnvelope.object(
                Map.of("id", Map.of("type", "string")), List.of("id"));
        ReusableFlowCommand.Node node = new ReusableFlowCommand.Node("node", "Node",
                new ReusableFlowCommand.ComposableRef.ApiResource("customer", 1, DEPENDENCY),
                List.of(new ReusableFlowCommand.Input("$.id",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.id"))));
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION,
                new ReusableFlowCommand.Flow(name, ReusableFlowCommand.Kind.TOOL, "description",
                        new ReusableFlowCommand.Contract(schema, schema),
                        new ReusableFlowCommand.Graph(List.of(node),
                                new ReusableFlowCommand.Output("node", "$")),
                        new ReusableFlowCommand.Layout(Map.of("node",
                                new ReusableFlowCommand.Position(0, 0)))));
    }

    private static Fixture fixture(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:flow-publication-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        AtomicInteger draftIds = new AtomicInteger();
        JdbcReusableFlowDraftStore drafts = new JdbcReusableFlowDraftStore(jdbc, transactions,
                new ObjectMapper().findAndRegisterModules(), () -> "id-" + draftIds.incrementAndGet());
        return new Fixture(jdbc, drafts, publicationStore(jdbc,
                () -> "publication-tool", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
    }

    private static JdbcReusableFlowPublicationStore publicationStore(
            JdbcTemplate jdbc, java.util.function.Supplier<String> ids, Clock clock) {
        return new JdbcReusableFlowPublicationStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new ObjectMapper().findAndRegisterModules(), ids, clock);
    }

    private record Fixture(JdbcTemplate jdbc, JdbcReusableFlowDraftStore drafts,
                           JdbcReusableFlowPublicationStore publications) { }
}
