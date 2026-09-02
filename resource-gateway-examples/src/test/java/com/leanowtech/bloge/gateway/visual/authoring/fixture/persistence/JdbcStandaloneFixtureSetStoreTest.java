package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ComponentFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
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
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentSimulationAuthorityV2;
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
import java.util.concurrent.atomic.AtomicInteger;

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
                .satisfies(value -> {
                    assertThat(value.generated().view()).isEqualTo(updated.view());
                    assertThat(value.strongEtag()).isEqualTo(updated.strongEtag());
                });
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

    @Test
    void componentSubjectsRoundTripListAndRejectCoordinateTampering() {
        Fixture fixture = fixture("components");
        FixtureSubjectRef.OperatorVersion operator = new FixtureSubjectRef.OperatorVersion(
                "risk-library", 3, "risk.score", "sha256:" + "3".repeat(64));
        FixtureSubjectRef.BuiltinFunctionVersion function =
                new FixtureSubjectRef.BuiltinFunctionVersion(
                        "bloge", 1, "lookup", "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64));
        GeneratedDefaultFixture operatorFixture = componentMaterialize(
                "operator-cases", operator, "Operator cases");
        GeneratedDefaultFixture functionFixture = componentMaterialize(
                "function-cases", function, "Function cases");

        fixture.store().save(intent(ExpectedRevision.create(), "operator", operatorFixture,
                "sha256:" + "6".repeat(64)));
        fixture.store().save(intent(ExpectedRevision.create(), "function", functionFixture,
                "sha256:" + "7".repeat(64)));
        JdbcStandaloneFixtureSetStore reopened = store(fixture.jdbc());

        assertThat(reopened.findHead(SCOPE, "operator-cases")).get()
                .extracting(value -> value.generated().view().subject()).isEqualTo(operator);
        assertThat(reopened.findHead(SCOPE, "function-cases")).get()
                .extracting(value -> value.generated().view().subject()).isEqualTo(function);
        assertThat(reopened.listSummariesBySubject(SCOPE, operator))
                .extracting(FixtureSetSummary::fixtureSetId).containsExactly("operator-cases");
        assertThat(reopened.listSummariesBySubject(SCOPE, function))
                .extracting(FixtureSetSummary::fixtureSetId).containsExactly("function-cases");

        fixture.jdbc().update("""
                UPDATE rg_authoring_standalone_fixture_revisions
                   SET subject_member_id='other'
                 WHERE fixture_set_id='function-cases'
                """);
        assertCode(() -> reopened.findHead(SCOPE, "function-cases"),
                StandaloneFixtureSetStoreException.Code.INTEGRITY);
    }

    @Test
    void shareDerivationIsAtomicDurableAndReplaysBeforeProtectedMaterialWrite() {
        Fixture fixture = fixture("share");
        GeneratedDefaultFixture source = materialize("cases", 1, fixture.version(), "Cases");
        StandaloneFixtureSetSaveResult saved = fixture.store().save(intent(
                ExpectedRevision.create(), "create", source, "sha256:" + "1".repeat(64)));
        FixtureShareCommand command = new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("cases", 1, source.view().fingerprint(), 1),
                new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                        new FixtureShareCommand.Redaction("default-v1", List.of("/email"))));
        StandaloneFixtureSetShareIntent shareIntent = new StandaloneFixtureSetShareIntent(
                SCOPE, "author", "cases", saved.strongEtag(), "share",
                "sha256:" + "2".repeat(64), command);
        AtomicInteger writes = new AtomicInteger();

        StandaloneFixtureSetShareResult shared = fixture.store().share(shareIntent,
                (stored, revision, statusRevision, reviewRequestId) -> {
                    writes.incrementAndGet();
                    return pending(stored.generated(), revision, statusRevision, reviewRequestId);
                });
        StandaloneFixtureSetShareResult replay = store(fixture.jdbc()).share(shareIntent,
                (stored, revision, statusRevision, reviewRequestId) -> {
                    writes.incrementAndGet();
                    throw new AssertionError("replay must not repeat protected material writes");
                });

        assertThat(shared.view().status()).isEqualTo(FixtureSetView.Status.SHARING_PENDING);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt()).isEqualTo(shared.receipt());
        assertThat(writes).hasValue(1);
        assertThat(store(fixture.jdbc()).findRevision(SCOPE, "cases", 1)).get()
                .extracting(value -> value.generated().view().status())
                .isEqualTo(FixtureSetView.Status.PRIVATE_DRAFT);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT status, source_revision, derived_revision, policy_json
                  FROM rg_authoring_fixture_review_requests
                 WHERE review_request_id=?
                """, shared.receipt().reviewRequestId()))
                .containsEntry("STATUS", "PENDING")
                .containsEntry("SOURCE_REVISION", 1L)
                .containsEntry("DERIVED_REVISION", 2L)
                .satisfies(row -> assertThat((String) row.get("POLICY_JSON"))
                        .contains("CONFIDENTIAL").doesNotContain("eligible", "customerId"));
    }

    @Test
    void failedDeriverRollsBackReviewAndPendingRevision() {
        Fixture fixture = fixture("share-rollback");
        GeneratedDefaultFixture source = materialize("cases", 1, fixture.version(), "Cases");
        StandaloneFixtureSetSaveResult saved = fixture.store().save(intent(
                ExpectedRevision.create(), "create", source, "sha256:" + "1".repeat(64)));
        FixtureShareCommand command = new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("cases", 1, source.view().fingerprint(), 1),
                new FixtureShareCommand.Policy("INTERNAL", 30,
                        new FixtureShareCommand.Redaction("default-v1", List.of())));
        fixture.jdbc().execute("CREATE TABLE share_callback_probe (id VARCHAR(128) PRIMARY KEY)");

        assertCode(() -> fixture.store().share(new StandaloneFixtureSetShareIntent(
                        SCOPE, "author", "cases", saved.strongEtag(), "share",
                        "sha256:" + "2".repeat(64), command),
                (stored, revision, statusRevision, reviewRequestId) -> {
                    fixture.jdbc().update(
                            "INSERT INTO share_callback_probe (id) VALUES (?)", reviewRequestId);
                    throw new IllegalStateException("injected failure");
                }), StandaloneFixtureSetStoreException.Code.INTEGRITY);

        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM share_callback_probe", Integer.class)).isZero();
        assertThat(fixture.store().findHead(SCOPE, "cases")).get()
                .extracting(value -> value.generated().view().revision()).isEqualTo(1);
    }

    @Test
    void independentReviewCompletesPendingRequestAndReplaysBeforeActivation() {
        Fixture fixture = fixture("review");
        GeneratedDefaultFixture source = materialize("cases", 1, fixture.version(), "Cases");
        StandaloneFixtureSetSaveResult saved = fixture.store().save(intent(
                ExpectedRevision.create(), "create", source, "sha256:" + "1".repeat(64)));
        FixtureShareCommand shareCommand = new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("cases", 1, source.view().fingerprint(), 1),
                new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                        new FixtureShareCommand.Redaction("default-v1", List.of("/email"))));
        StandaloneFixtureSetShareResult shared = fixture.store().share(
                new StandaloneFixtureSetShareIntent(SCOPE, "author", "cases", saved.strongEtag(),
                        "share", "sha256:" + "2".repeat(64), shareCommand),
                (stored, revision, statusRevision, reviewRequestId) ->
                        pending(stored.generated(), revision, statusRevision, reviewRequestId));
        FixtureReviewCommand reviewCommand = new FixtureReviewCommand(FixtureReviewCommand.SCHEMA_VERSION,
                new FixtureReviewCommand.Source(shared.receipt().reviewRequestId(), "cases",
                        shared.view().revision(), shared.view().fingerprint(),
                        shared.view().statusRevision()),
                new FixtureReviewCommand.Attestations(
                        true, true, true, "Independent reviewer approved protected material"));
        StandaloneFixtureSetReviewIntent reviewIntent = new StandaloneFixtureSetReviewIntent(
                SCOPE, "reviewer", "cases", shared.strongEtag(), "review",
                "sha256:" + "3".repeat(64), reviewCommand);
        AtomicInteger activations = new AtomicInteger();

        StandaloneFixtureSetReviewResult reviewed = fixture.store().review(reviewIntent,
                (stored, revision, statusRevision) -> {
                    activations.incrementAndGet();
                    return active(stored.generated(), reviewCommand.source().reviewRequestId(),
                            revision, statusRevision);
                });
        StandaloneFixtureSetReviewResult replay = store(fixture.jdbc()).review(reviewIntent,
                (stored, revision, statusRevision) -> {
                    activations.incrementAndGet();
                    throw new AssertionError("replay must not repeat material activation");
                });

        assertThat(reviewed.view().status()).isEqualTo(FixtureSetView.Status.TEAM_AVAILABLE);
        assertThat(reviewed.view().revision()).isEqualTo(3);
        assertThat(reviewed.view().cases().getFirst().controls().getFirst().behavior())
                .isInstanceOfSatisfying(FixtureSetCommand.Behavior.Return.class, returned ->
                        assertThat(returned.material())
                                .isEqualTo(new FixtureSetCommand.Material.FixtureAsset(
                                        "asset-approved", 5, "sha256:" + "a".repeat(64))));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt()).isEqualTo(reviewed.receipt());
        assertThat(activations).hasValue(1);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT status, completed_revision, completed_by
                  FROM rg_authoring_fixture_review_requests
                 WHERE review_request_id=?
                """, shared.receipt().reviewRequestId()))
                .containsEntry("STATUS", "COMPLETED")
                .containsEntry("COMPLETED_REVISION", 3L)
                .containsEntry("COMPLETED_BY", "reviewer");
    }

    private static Fixture fixture(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:standalone-fixture-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"),
                new ClassPathResource("db/postgresql/V20260901_017__fixture_share_requests.sql"),
                new ClassPathResource("db/postgresql/V20260901_018__fixture_review_completion.sql"),
                new ClassPathResource(
                        "db/postgresql/V20260902_019__standalone_component_fixture_subjects.sql"))
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

    private static GeneratedDefaultFixture componentMaterialize(
            String id, FixtureSubjectRef subject, String displayName) {
        FixtureSetCommand command = new FixtureSetCommand(
                FixtureSetCommand.SCHEMA_VERSION, displayName, subject, List.of(
                new FixtureSetCommand.Case("case", "case", output(), List.of(
                        new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                                FixtureSetCommand.Behavior.returned(
                                        FixtureSetCommand.Material.inline(output())), null)), null)));
        return new ComponentFixtureSetMaterializer().generate(
                id, 1, subject, new ComponentSimulationAuthorityV2.ComponentContract(
                        SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of()), command);
    }

    private static FixtureShareMaterialization pending(
            GeneratedDefaultFixture source, int revision, int statusRevision, String reviewRequestId) {
        List<FixtureSetCommand.Case> cases = source.view().cases().stream().map(fixtureCase -> {
            FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
            return new FixtureSetCommand.Case(fixtureCase.caseId(), fixtureCase.name(),
                    fixtureCase.input(), List.of(new FixtureSetCommand.Control(control.target(),
                    FixtureSetCommand.Behavior.returned(new FixtureSetCommand.Material.FixtureAsset(
                            "asset-approved", 2, "sha256:" + "a".repeat(64))), control.fidelity())),
                    fixtureCase.expect());
        }).toList();
        String fingerprint = FixtureSetFingerprints.of(
                source.view().displayName(), source.view().subject(), cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, "cases", revision,
                fingerprint, statusRevision, source.view().displayName(), source.view().subject(),
                cases, FixtureSetView.Status.SHARING_PENDING);
        FixtureSetSaveReceipt saveReceipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, "cases", revision, fingerprint,
                view.subject(), cases.stream().map(FixtureSetCommand.Case::caseId).toList(),
                view.status(), statusRevision);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                "cases", revision, fingerprint, view.displayName(), view.subject(),
                cases.stream().map(value -> new FixtureSetSummary.CaseSummary(
                        value.caseId(), value.name())).toList(),
                view.status(), statusRevision);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view, saveReceipt, summary,
                cases.stream().map(value -> new GeneratedDefaultFixture.CaseMapping(
                        value.caseId(), value.caseId())).toList());
        FixtureShareReceipt receipt = new FixtureShareReceipt(FixtureShareReceipt.SCHEMA_VERSION,
                "cases", source.view().revision(), revision, fingerprint,
                FixtureSetView.Status.SHARING_PENDING, statusRevision, reviewRequestId);
        return new FixtureShareMaterialization(generated, receipt);
    }

    private static FixtureReviewMaterialization active(
            GeneratedDefaultFixture source, String reviewRequestId, int revision, int statusRevision) {
        List<FixtureSetCommand.Case> cases = source.view().cases().stream().map(fixtureCase -> {
            FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
            return new FixtureSetCommand.Case(fixtureCase.caseId(), fixtureCase.name(),
                    fixtureCase.input(), List.of(new FixtureSetCommand.Control(control.target(),
                    FixtureSetCommand.Behavior.returned(new FixtureSetCommand.Material.FixtureAsset(
                            "asset-approved", 5, "sha256:" + "a".repeat(64))), control.fidelity())),
                    fixtureCase.expect());
        }).toList();
        String fingerprint = FixtureSetFingerprints.of(
                source.view().displayName(), source.view().subject(), cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, "cases", revision,
                fingerprint, statusRevision, source.view().displayName(), source.view().subject(),
                cases, FixtureSetView.Status.TEAM_AVAILABLE);
        FixtureSetSaveReceipt saveReceipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, "cases", revision, fingerprint,
                view.subject(), cases.stream().map(FixtureSetCommand.Case::caseId).toList(),
                view.status(), statusRevision);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                "cases", revision, fingerprint, view.displayName(), view.subject(),
                cases.stream().map(value -> new FixtureSetSummary.CaseSummary(
                        value.caseId(), value.name())).toList(), view.status(), statusRevision);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view, saveReceipt, summary,
                cases.stream().map(value -> new GeneratedDefaultFixture.CaseMapping(
                        value.caseId(), value.caseId())).toList());
        FixtureReviewReceipt receipt = new FixtureReviewReceipt(FixtureReviewReceipt.SCHEMA_VERSION,
                reviewRequestId, "cases", source.view().revision(), revision, fingerprint,
                view.status(), statusRevision, 1);
        return new FixtureReviewMaterialization(generated, receipt);
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
