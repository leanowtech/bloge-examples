package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.flow.JdbcReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.JdbcReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishIntent;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveIntent;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
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

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JdbcStandaloneFixtureSetStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void createUpdateReplayHistoryAndSubjectListingSurviveRestart() {
        Fixture fixture = fixture("durable");
        ReusableFlowVersion version = fixture.version();
        GeneratedDefaultFixture first = materialize("cases", 1, version, "Cases");
        StandaloneFixtureSetSaveResult created = fixture.store().save(intent(
                ExpectedRevision.create(), "create", first, "sha256:" + "1".repeat(64)));
        StandaloneFixtureSetSaveResult replay = fixture.store().save(intent(
                ExpectedRevision.create(), "create", first, "sha256:" + "1".repeat(64)));
        GeneratedDefaultFixture second = materialize("cases", 2, version, "Updated cases");
        StandaloneFixtureSetSaveResult updated = fixture.store().save(intent(
                ExpectedRevision.match(1), "update", second, "sha256:" + "2".repeat(64)));
        JdbcStandaloneFixtureSetStore reopened = store(fixture.jdbc());

        assertThat(created.view().revision()).isEqualTo(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(updated.view().revision()).isEqualTo(2);
        assertThat(reopened.findHead(SCOPE, "cases")).get()
                .extracting(value -> value.generated().view()).isEqualTo(updated.view());
        assertThat(reopened.findRevision(SCOPE, "cases", 1)).isPresent();
        assertThat(reopened.findRevisionByStrongEtag(SCOPE, "cases", created.strongEtag()))
                .hasValueSatisfying(value -> assertThat(value.stored().generated().view())
                        .isEqualTo(created.view()));
        assertThat(reopened.listSummariesBySubject(SCOPE, version.subject()))
                .extracting(value -> value.fixtureSetId()).containsExactly("cases");
    }

    @Test
    void failedCasDoesNotOccupyKeyAndChangedReplayConflicts() {
        Fixture fixture = fixture("cas");
        GeneratedDefaultFixture first = materialize("cases", 1, fixture.version(), "Cases");
        fixture.store().save(intent(ExpectedRevision.create(), "create", first,
                "sha256:" + "1".repeat(64)));
        GeneratedDefaultFixture stale = materialize("cases", 8, fixture.version(), "Stale");

        assertCode(() -> fixture.store().save(intent(ExpectedRevision.match(7), "stale", stale,
                "sha256:" + "2".repeat(64))), StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        assertCode(() -> fixture.store().save(intent(ExpectedRevision.match(7), "stale", stale,
                "sha256:" + "2".repeat(64))), StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        assertCode(() -> fixture.store().save(intent(ExpectedRevision.create(), "create", first,
                "sha256:" + "9".repeat(64))), StandaloneFixtureSetStoreException.Code.CONFLICT);
    }

    @Test
    void tamperedAuthorityAndCrossScopeReadsFailClosed() {
        Fixture fixture = fixture("tamper");
        GeneratedDefaultFixture generated = materialize("cases", 1, fixture.version(), "Cases");
        fixture.store().save(intent(ExpectedRevision.create(), "create", generated,
                "sha256:" + "1".repeat(64)));
        assertThat(fixture.store().findHead(
                new AuthoringScope("other", "project", "dev"), "cases")).isEmpty();
        fixture.jdbc().execute("SET REFERENTIAL_INTEGRITY FALSE");
        fixture.jdbc().update("UPDATE rg_authoring_standalone_fixture_revisions SET subject_fingerprint=?",
                "sha256:" + "9".repeat(64));

        assertCode(() -> fixture.store().findHead(SCOPE, "cases"),
                StandaloneFixtureSetStoreException.Code.INTEGRITY);
    }

    private static Fixture fixture(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:standalone-fixture-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcReusableFlowDraftStore drafts = new JdbcReusableFlowDraftStore(jdbc, transactions, mapper);
        JdbcReusableFlowPublicationStore publications = new JdbcReusableFlowPublicationStore(
                jdbc, transactions, mapper);
        ReusableFlowDraft draft = drafts.save(new ReusableFlowSaveIntent(SCOPE, "author", "flow",
                ExpectedRevision.create(), "save-flow", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), flowCommand())).draft();
        ReusableFlowVersion version = publications.publish(new ReusableFlowPublishIntent(
                SCOPE, "author", "flow", "publish-flow", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), draft)).version();
        return new Fixture(jdbc, store(jdbc), version);
    }

    private static JdbcStandaloneFixtureSetStore store(JdbcTemplate jdbc) {
        return new JdbcStandaloneFixtureSetStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new ObjectMapper().findAndRegisterModules());
    }

    private static StandaloneFixtureSetSaveIntent intent(
            ExpectedRevision expected, String key, GeneratedDefaultFixture generated, String fingerprint) {
        return new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", generated.view().fixtureSetId(), expected, key, fingerprint, generated);
    }

    private static GeneratedDefaultFixture materialize(
            String id, int revision, ReusableFlowVersion version, String displayName) {
        FixtureSetCommand template = command(version.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);
        FixtureSetCommand exact = new FixtureSetCommand(
                FixtureSetCommand.SCHEMA_VERSION, displayName, version.subject(), template.cases());
        return new WholeFlowFixtureMaterializer().generate(id, revision, version, exact);
    }

    private static ReusableFlowCommand flowCommand() {
        SchemaEnvelope input = SchemaEnvelope.object(
                Map.of("customerId", Map.of("type", "string")), List.of("customerId"));
        SchemaEnvelope output = SchemaEnvelope.object(
                Map.of("eligible", Map.of("type", "boolean")), List.of("eligible"));
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION,
                new ReusableFlowCommand.Flow("Eligibility", ReusableFlowCommand.Kind.TOOL,
                        "Checks eligibility", new ReusableFlowCommand.Contract(input, output),
                        new ReusableFlowCommand.Graph(List.of(),
                                new ReusableFlowCommand.Output("result", "$")),
                        new ReusableFlowCommand.Layout(Map.of())));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   StandaloneFixtureSetStoreException.Code code) {
        assertThatThrownBy(call).isInstanceOf(StandaloneFixtureSetStoreException.class)
                .extracting("code").isEqualTo(code);
    }

    private record Fixture(JdbcTemplate jdbc, JdbcStandaloneFixtureSetStore store,
                           ReusableFlowVersion version) { }
}
