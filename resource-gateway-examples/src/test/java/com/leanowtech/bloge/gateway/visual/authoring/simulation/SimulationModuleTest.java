package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceSaveCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.InMemoryApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshot;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.InMemoryApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ProjectionDocument;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ReadyApiResourceProjections;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StagedApiResource;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationModuleTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void fixtureCaseProducesOneMockedRunAndExactReplay() {
        Fixture fixture = fixture();
        AtomicInteger ids = new AtomicInteger();
        SimulationModule module = new SimulationModule(fixture.resources(), fixture.fixtures(),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "sim-" + ids.incrementAndGet());
        SimulationRequest request = SimulationRequest.fixtureCase(
                fixture.generated().view().fixtureSetId(), 1, "happy");

        SimulationRun first = module.run(SCOPE, "run-key", request);
        SimulationRun replay = module.run(SCOPE, "run-key", request);

        assertThat(replay).isEqualTo(first);
        assertThat(ids).hasValue(1);
        assertThat(first.status()).isEqualTo(SimulationRun.Status.SUCCEEDED);
        assertThat(first.subject()).isEqualTo(fixture.generated().view().subject());
        assertThat(first.output()).isEqualTo(JSON.createObjectNode().put("id", "one"));
        assertThat(first.nodes()).containsExactly(new SimulationRun.Node("customer-profile",
                SimulationRun.NodeStatus.COMPLETED, SimulationRun.Execution.MOCKED,
                SimulationRun.FixtureSource.INLINE, SimulationRun.Fidelity.OUTPUT_LEVEL,
                SimulationRun.Egress.fixture()));
        assertThat(first.verdicts()).isEqualTo(new SimulationRun.Verdicts(
                SimulationRun.ExecutionVerdict.SIMULATED_ONLY, SimulationRun.Verdict.PASSED,
                SimulationRun.Verdict.NOT_CHECKED, SimulationRun.Verdict.NOT_CHECKED));
        assertThat(first.diagnostics()).isEmpty();
        assertThat(module.read(SCOPE, first.runId())).contains(first);
        assertThat(module.read(new AuthoringScope("other", "project", "dev"), first.runId())).isEmpty();
    }

    @Test
    void changedRequestCannotReuseAnIdempotencyKey() {
        Fixture fixture = fixture();
        SimulationModule module = new SimulationModule(fixture.resources(), fixture.fixtures(),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-1");
        module.run(SCOPE, "run-key", SimulationRequest.fixtureCase(
                fixture.generated().view().fixtureSetId(), 1, "happy"));

        assertThatThrownBy(() -> module.run(SCOPE, "run-key", SimulationRequest.fixtureCase(
                fixture.generated().view().fixtureSetId(), 1, "sad")))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.CONFLICT);
        assertThatThrownBy(() -> module.run(SCOPE, "run-key", SimulationRequest.fixtureCase(
                fixture.generated().view().fixtureSetId(), 2, "happy")))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.NOT_FOUND);
    }

    @Test
    void adHocAndExternalReadRemainExplicitlyUnsupported() {
        Fixture fixture = fixture();
        SimulationModule module = new SimulationModule(fixture.resources(), fixture.fixtures(),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-1");
        SimulationRequest adHoc = SimulationRequest.adHoc(fixture.generated().view().subject(),
                JSON.createObjectNode().put("id", "one"));
        SimulationRequest withRead = new SimulationRequest(SimulationRequest.SCHEMA_VERSION,
                SimulationRequest.fixtureCaseSource(fixture.generated().view().fixtureSetId(), 1, "happy"),
                new SimulationRequest.ExecutionPolicy(
                        new SimulationRequest.ExternalReads.AllowExact(List.of(fixture.resource().ref()), "debug"),
                        new SimulationRequest.ExternalWrites.Deny()));

        assertThatThrownBy(() -> module.run(SCOPE, "ad-hoc", adHoc))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.UNSUPPORTED);
        assertThatThrownBy(() -> module.run(SCOPE, "read", withRead))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.UNSUPPORTED);
    }

    @Test
    void pendingStaleAndRevokedFixturesCannotStartNewRuns() {
        Fixture fixture = fixture();

        for (FixtureSetView.Status status : List.of(
                FixtureSetView.Status.SHARING_PENDING,
                FixtureSetView.Status.STALE,
                FixtureSetView.Status.REVOKED)) {
            StoredFixtureSet stored = storedWithStatus(fixture.generated(), status);
            SimulationModule module = new SimulationModule(fixture.resources(), reader(stored),
                    new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-1");

            assertThatThrownBy(() -> module.run(SCOPE, "run-" + status.name().toLowerCase(),
                    SimulationRequest.fixtureCase(stored.generated().view().fixtureSetId(),
                            stored.generated().view().revision(), "happy")))
                    .isInstanceOf(SimulationFailure.class)
                    .extracting(value -> ((SimulationFailure) value).code())
                    .isEqualTo(SimulationFailure.Code.UNSUPPORTED);
        }
    }

    @Test
    void completedRunReplaysAfterTheJdbcRunAuthorityReopens() {
        Fixture fixture = fixture();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:simulation-module;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260831_013__authoring_simulation_runs.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SimulationRequest request = SimulationRequest.fixtureCase(
                fixture.generated().view().fixtureSetId(), 1, "happy");
        SimulationModule firstModule = new SimulationModule(fixture.resources(), fixture.fixtures(),
                jdbcStore(jdbc), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-durable");
        SimulationRun first = firstModule.run(SCOPE, "durable-key", request);
        SimulationModule reopened = new SimulationModule(fixture.resources(), fixture.fixtures(),
                jdbcStore(jdbc), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> { throw new AssertionError("replay must not allocate another run id"); });

        assertThat(reopened.run(SCOPE, "durable-key", request)).isEqualTo(first);
        assertThat(reopened.readRequired(SCOPE, "sim-durable")).isEqualTo(first);
    }

    private static Fixture fixture() {
        InMemoryApiResourceCommitStore resources = new InMemoryApiResourceCommitStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30),
                (scope, resource) -> projections(resource));
        InMemoryApiFixtureSetCommitStore fixtures = new InMemoryApiFixtureSetCommitStore();
        ApiResourceSpec resource = resource();
        GeneratedDefaultFixture generated = new DefaultFixtureSetMaterializer().generate(resource,
                (ApiResourceSaveCommand.DefaultFixture.FromExamples)
                        ApiResourceSaveCommand.DefaultFixture.fromExamples(
                                "Default cases", List.of("happy", "sad")));
        CommandLease requested = lease();
        CommandLease lease = ((ClaimResult.Acquired) resources.claim(requested.key(),
                requested.requestFingerprint(), requested.expectedRevision())).lease();
        StagedApiResource staged = resources.stage(lease, "customer", command());
        fixtures.stage(lease, generated);
        fixtures.commitChild(lease);
        var receipt = ApiResourceSaveReceiptClosure.create(staged, generated);
        resources.commit(lease, receipt);
        fixtures.publishChild(lease, receipt);
        return new Fixture(resources, fixtures, resource, generated);
    }

    private static ApiResourceSpec resource() {
        return new ApiResourceDecisions(JSON).next(Optional.empty(), "customer-profile", "customer",
                command(), ExpectedRevision.create());
    }

    private static ApiResourceCommand command() {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id"));
        var value = JSON.createObjectNode().put("id", "one");
        var other = JSON.createObjectNode().put("id", "two");
        return new ApiResourceCommand("Customer profile", null,
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(schema, schema),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.readOnly(),
                List.of(new ApiResourceCommand.Example("happy", value, value),
                        new ApiResourceCommand.Example("sad", other, other)));
    }

    private static ReadyApiResourceProjections projections(ApiResourceSpec resource) {
        var body = JSON.createObjectNode().put("ready", true);
        String fingerprint = AuthoringFingerprints.of(body);
        return new ReadyApiResourceProjections(
                new ProjectionDocument(ProjectionDocument.Kind.DESCRIPTOR, resource.ref(), body,
                        fingerprint, ProjectionDocument.State.READY),
                new ProjectionDocument(ProjectionDocument.Kind.DESIGN_CONTRACT, resource.ref(), body,
                        fingerprint, ProjectionDocument.State.READY),
                new ProjectionDocument(ProjectionDocument.Kind.OPERATOR, resource.ref(), body,
                        fingerprint, ProjectionDocument.State.READY),
                new ApiResourceConnectionSnapshot("customer", 1, "sha256:" + "b".repeat(64)));
    }

    private static CommandLease lease() {
        return new CommandLease("command", 1, "attempt",
                new CommandKey(SCOPE, "author", AuthoringEndpoint.API_RESOURCE_SAVE,
                        "customer-profile", "resource-key"), "sha256:" + "a".repeat(64),
                Instant.parse("2031-01-01T00:00:00Z"), ExpectedRevision.create());
    }

    private static JdbcSimulationRunStore jdbcStore(JdbcTemplate jdbc) {
        return new JdbcSimulationRunStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), JSON,
                Duration.ofSeconds(30));
    }

    private static StoredFixtureSet storedWithStatus(
            GeneratedDefaultFixture source, FixtureSetView.Status status) {
        FixtureSetView current = source.view();
        int statusRevision = current.statusRevision() + 1;
        FixtureSetView view = new FixtureSetView(current.schemaVersion(), current.fixtureSetId(),
                current.revision(), current.fingerprint(), statusRevision, current.displayName(),
                current.subject(), current.cases(), status);
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, view.fixtureSetId(), view.revision(),
                view.fingerprint(), view.subject(), source.receipt().caseIds(), status, statusRevision);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                view.fixtureSetId(), view.revision(), view.fingerprint(), view.displayName(),
                view.subject(), source.summary().cases(), status, statusRevision);
        return new StoredFixtureSet(SCOPE, new GeneratedDefaultFixture(
                view, receipt, summary, source.caseMappings()));
    }

    private static FixtureSetAuthorityReader reader(StoredFixtureSet stored) {
        return new FixtureSetAuthorityReader() {
            @Override public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String id) {
                return SCOPE.equals(scope) && stored.generated().view().fixtureSetId().equals(id)
                        ? Optional.of(stored) : Optional.empty();
            }
            @Override public Optional<StoredFixtureSet> findRevision(
                    AuthoringScope scope, String id, int revision) {
                return SCOPE.equals(scope) && stored.generated().view().fixtureSetId().equals(id)
                        && stored.generated().view().revision() == revision
                        ? Optional.of(stored) : Optional.empty();
            }
            @Override public List<FixtureSetSummary> listSummariesBySubject(
                    AuthoringScope scope,
                    com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef subject) {
                return List.of();
            }
        };
    }

    private record Fixture(InMemoryApiResourceCommitStore resources,
                           InMemoryApiFixtureSetCommitStore fixtures,
                           ApiResourceSpec resource, GeneratedDefaultFixture generated) { }
}
