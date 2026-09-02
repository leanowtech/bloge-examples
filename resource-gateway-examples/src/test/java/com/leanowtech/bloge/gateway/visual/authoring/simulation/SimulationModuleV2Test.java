package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationModuleV2Test {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void exactPrivateReturnProducesInvocationEvidenceAndExactReplay() {
        Fixture fixture = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output("fixture"))),
                new FixtureSetCommand.Expect(output("fixture")), null);
        AtomicInteger runIds = new AtomicInteger();
        AtomicInteger invocationIds = new AtomicInteger();
        SimulationModuleV2 module = module(fixture, null, null,
                SimulationFixtureUsageRecorder.none(), new InMemorySimulationRunV2Store(),
                runIds, invocationIds);

        SimulationExecutionResultV2 first = module.execute(
                SCOPE, "return-key", command(fixture, input("one"), exact(fixture)));
        SimulationExecutionResultV2 replay = module.execute(
                SCOPE, "return-key", command(fixture, input("one"), exact(fixture)));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run()).isEqualTo(first.run());
        assertThat(runIds).hasValue(1);
        assertThat(invocationIds).hasValue(1);
        assertThat(first.run().output()).isEqualTo(output("fixture"));
        assertThat(first.run().invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.execution()).isEqualTo(SimulationRunV2.Execution.MOCKED);
            assertThat(invocation.matchedBy()).isEqualTo(SimulationRunV2.MatchedBy.EXACT_CASE);
            assertThat(invocation.behavior()).isEqualTo(SimulationRunV2.Behavior.RETURN);
            assertThat(invocation.provenance()).isEqualTo(SimulationRunV2.Provenance.PINNED_PRIVATE);
            assertThat(invocation.egress()).isEqualTo(new SimulationRun.Egress.Fixture(false));
        });
        assertThat(first.run().verdicts().assertions())
                .isEqualTo(SimulationRunV2.AssertionsVerdict.PASSED);
        assertThat(first.run().verdicts().contract()).isEqualTo(SimulationRunV2.ContractVerdict.VALID);
        assertThat(first.run().verdicts().aggregate())
                .isEqualTo(SimulationRunV2.AggregateVerdict.NOT_READY);
    }

    @Test
    void conditionAndAutoMatchUseBusinessInputWithoutChangingDriverInput() {
        Fixture fixture = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output("vip"))),
                null, new FixtureSetCommand.Condition("vip-condition", List.of(
                new FixtureSetCommand.Predicate.Eq("$.id", JSON.getNodeFactory().textNode("vip")))));
        SimulationModuleV2 module = module(fixture, null, null,
                SimulationFixtureUsageRecorder.none(), new InMemorySimulationRunV2Store(),
                new AtomicInteger(), new AtomicInteger());

        SimulationRunV2 conditioned = module.execute(SCOPE, "condition", command(
                fixture, input("vip"), new SimulationCommandV2.FixtureSelection.MatchCondition(
                        fixture.reference(), "vip-condition"))).run();
        SimulationRunV2 automatic = module.execute(SCOPE, "auto", command(
                fixture, input("vip"), new SimulationCommandV2.FixtureSelection.AutoMatch(
                        fixture.reference()))).run();

        assertThat(conditioned.invocations().getFirst().matchedBy())
                .isEqualTo(SimulationRunV2.MatchedBy.CONDITION);
        assertThat(automatic.invocations().getFirst().matchedBy())
                .isEqualTo(SimulationRunV2.MatchedBy.AUTO_MATCH);
    }

    @Test
    void errorTimeoutAndReplayHaveDistinctPayloadFreeEvidence() {
        Fixture error = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Behavior.Error("PROVIDER_SECRET", "private provider detail"),
                null, null);
        Fixture timeout = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Behavior.Timeout(750), null, null);
        Fixture replay = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Behavior.Replay("recording-1", "sha256:" + "9".repeat(64)),
                null, null);
        AtomicInteger replayReads = new AtomicInteger();
        SimulationReplayResolver resolver = (scope, id, fingerprint) -> {
            replayReads.incrementAndGet();
            return output("recorded");
        };

        SimulationRunV2 errorRun = module(error, null, resolver, null,
                new InMemorySimulationRunV2Store(), new AtomicInteger(), new AtomicInteger())
                .execute(SCOPE, "error", command(error, input("one"), exact(error))).run();
        SimulationRunV2 timeoutRun = module(timeout, null, resolver, null,
                new InMemorySimulationRunV2Store(), new AtomicInteger(), new AtomicInteger())
                .execute(SCOPE, "timeout", command(timeout, input("one"), exact(timeout))).run();
        SimulationModuleV2 replayModule = module(replay, null, resolver, null,
                new InMemorySimulationRunV2Store(), new AtomicInteger(), new AtomicInteger());
        SimulationRunV2 replayRun = replayModule.execute(
                SCOPE, "replay", command(replay, input("one"), exact(replay))).run();
        replayModule.execute(SCOPE, "replay", command(replay, input("one"), exact(replay)));

        assertThat(errorRun.status()).isEqualTo(SimulationRunV2.Status.FAILED);
        assertThat(errorRun.invocations().getFirst().behavior()).isEqualTo(SimulationRunV2.Behavior.ERROR);
        assertThat(errorRun.toString()).doesNotContain("PROVIDER_SECRET", "private provider detail");
        assertThat(errorRun.diagnostics().toString())
                .doesNotContain("PROVIDER_SECRET", "private provider detail");
        assertThat(timeoutRun.status()).isEqualTo(SimulationRunV2.Status.FAILED);
        assertThat(timeoutRun.invocations().getFirst().behavior())
                .isEqualTo(SimulationRunV2.Behavior.TIMEOUT);
        assertThat(replayRun.output()).isEqualTo(output("recorded"));
        assertThat(replayRun.invocations().getFirst().provenance())
                .isEqualTo(SimulationRunV2.Provenance.REPLAY);
        assertThat(replayReads).hasValue(1);
    }

    @Test
    void governedMaterialRequiresIdentityAndUsageIsIdempotentByCommittedInvocation() {
        var material = new FixtureSetCommand.Material.FixtureAsset(
                "asset-1", 5, "sha256:" + "8".repeat(64));
        Fixture fixture = fixture(FixtureSetView.Status.TEAM_AVAILABLE,
                FixtureSetCommand.Behavior.returned(material), null, null);
        Set<String> usage = new HashSet<>();
        SimulationFixtureUsageRecorder recorder = (scope, run, invocation, asset) ->
                usage.add(run + ":" + invocation + ":" + asset.fixtureAssetId() + ":" + asset.revision());
        FixtureAssetSimulationResolver resolver = (identity, asset) -> output("governed");
        SimulationModuleV2 module = module(fixture, resolver, null, recorder,
                new InMemorySimulationRunV2Store(), new AtomicInteger(), new AtomicInteger());
        SimulationCommandV2 command = command(fixture, input("one"), exact(fixture));

        SimulationRunV2 blocked = module.execute(SCOPE, "without-identity", command).run();
        SimulationRunV2 first = module.execute(SCOPE, "with-identity", command, identity()).run();
        SimulationRunV2 replay = module.execute(SCOPE, "with-identity", command, identity()).run();

        assertThat(blocked.status()).isEqualTo(SimulationRunV2.Status.BLOCKED);
        assertThat(first.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(replay).isEqualTo(first);
        assertThat(first.invocations().getFirst().fixtureAssetRef())
                .isEqualTo(new SimulationRunV2.FixtureAssetRef(
                        "asset-1", 5, "sha256:" + "8".repeat(64)));
        assertThat(usage).hasSize(1);
    }

    @Test
    void invalidInputAndOutputPersistHonestContractFailures() {
        Fixture invalidOutput = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                        JSON.createObjectNode().put("wrong", true))), null, null);
        SimulationModuleV2 module = module(invalidOutput, null, null, null,
                new InMemorySimulationRunV2Store(), new AtomicInteger(), new AtomicInteger());

        SimulationRunV2 inputFailure = module.execute(SCOPE, "bad-input", command(
                invalidOutput, JSON.createObjectNode().put("wrong", true), exact(invalidOutput))).run();
        SimulationRunV2 outputFailure = module.execute(SCOPE, "bad-output", command(
                invalidOutput, input("one"), exact(invalidOutput))).run();

        assertThat(inputFailure.status()).isEqualTo(SimulationRunV2.Status.FAILED);
        assertThat(inputFailure.invocations()).isEmpty();
        assertThat(inputFailure.verdicts().contract()).isEqualTo(SimulationRunV2.ContractVerdict.INVALID);
        assertThat(outputFailure.status()).isEqualTo(SimulationRunV2.Status.FAILED);
        assertThat(outputFailure.invocations()).singleElement()
                .extracting(SimulationRunV2.Invocation::status)
                .isEqualTo(SimulationRunV2.InvocationStatus.FAILED);
        assertThat(outputFailure.diagnostics()).isNotEmpty();
    }

    @Test
    void absentFixtureSelectionBlocksWithoutNetworkAndChangedCommandConflicts() {
        Fixture fixture = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output("fixture"))),
                null, null);
        SimulationModuleV2 module = module(fixture, null, null, null,
                new InMemorySimulationRunV2Store(), new AtomicInteger(), new AtomicInteger());
        SimulationCommandV2 none = new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION,
                fixture.subject(), new SimulationCommandV2.Input.Inline(input("one")),
                new SimulationCommandV2.FixturePlan.None(), SimulationCommandV2.ExecutionPolicy.denyAll());

        SimulationRunV2 blocked = module.execute(SCOPE, "none", none).run();
        module.execute(SCOPE, "conflict", command(fixture, input("one"), exact(fixture)));

        assertThat(blocked.status()).isEqualTo(SimulationRunV2.Status.BLOCKED);
        assertThat(blocked.invocations()).isEmpty();
        assertThatThrownBy(() -> module.execute(
                SCOPE, "conflict", command(fixture, input("two"), exact(fixture))))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.CONFLICT);
    }

    @Test
    void realControlIsRejectedBeforeTheIdempotencyCoordinateIsClaimed() {
        Fixture fixture = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Behavior.Real(), null, null);
        InMemorySimulationRunV2Store runs = new InMemorySimulationRunV2Store();
        SimulationModuleV2 module = module(fixture, null, null, null, runs,
                new AtomicInteger(), new AtomicInteger());

        assertThatThrownBy(() -> module.execute(
                SCOPE, "real", command(fixture, input("one"), exact(fixture))))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.UNSUPPORTED);
        assertThat(runs.claim(SCOPE, "real", "sha256:" + "a".repeat(64),
                () -> "still-free", NOW)).isEqualTo(
                new SimulationRunV2Store.Claim.Acquired("still-free"));
    }

    private static SimulationModuleV2 module(
            Fixture fixture, FixtureAssetSimulationResolver assets, SimulationReplayResolver replays,
            SimulationFixtureUsageRecorder usage, SimulationRunV2Store runs,
            AtomicInteger runIds, AtomicInteger invocationIds) {
        return new SimulationModuleV2(fixture.resources(), new FixturePlanCompiler(fixture.fixtures()),
                assets, replays, usage, runs, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "sim-v2-" + runIds.incrementAndGet(),
                () -> "inv-v2-" + invocationIds.incrementAndGet());
    }

    private static Fixture fixture(FixtureSetView.Status status, FixtureSetCommand.Behavior behavior,
                                   FixtureSetCommand.Expect expect,
                                   FixtureSetCommand.Condition condition) {
        ApiResourceSpec resource = resource();
        ExactFixtureSubjectRefV2.ApiResource subject = new ExactFixtureSubjectRefV2.ApiResource(
                resource.resourceId(), resource.revision(), resource.fingerprint());
        FixtureSubjectRef.ApiResource legacy = new FixtureSubjectRef.ApiResource(
                resource.resourceId(), resource.revision(), resource.fingerprint());
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "case-1", "Case one", input("driver"), condition,
                List.of(new FixtureSetCommand.Control(
                        FixtureSetCommand.Target.subject(), behavior,
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL)), expect);
        List<FixtureSetCommand.Case> cases = List.of(fixtureCase);
        String fingerprint = FixtureSetFingerprints.of("Profile fixtures", legacy, cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION,
                "profile-fixtures", 3, fingerprint, 1, "Profile fixtures", legacy, cases, status);
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                view.fixtureSetId(), view.revision(), view.fingerprint(), legacy,
                List.of(fixtureCase.caseId()), status, 1);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                view.fixtureSetId(), view.revision(), view.fingerprint(), view.displayName(), legacy,
                List.of(new FixtureSetSummary.CaseSummary(fixtureCase.caseId(), fixtureCase.name())), status, 1);
        StoredFixtureSet stored = new StoredFixtureSet(SCOPE, new GeneratedDefaultFixture(
                view, receipt, summary, List.of(new GeneratedDefaultFixture.CaseMapping("case-1", "case-1"))));
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        StoredApiResource storedResource = mock(StoredApiResource.class);
        when(storedResource.scope()).thenReturn(SCOPE);
        when(storedResource.resource()).thenReturn(resource);
        when(resources.findRevision(SCOPE, resource.resourceId(), resource.revision()))
                .thenReturn(Optional.of(storedResource));
        return new Fixture(resources, reader(stored), subject,
                new SimulationCommandV2.ExactFixtureSetRef(
                        view.fixtureSetId(), view.revision(), view.fingerprint()));
    }

    private static ApiResourceSpec resource() {
        SchemaEnvelope schema = SchemaEnvelope.object(
                Map.of("id", Map.of("type", "string")), List.of("id"));
        ApiResourceCommand command = new ApiResourceCommand("Customer profile", null,
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(schema, schema),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.readOnly(), List.of(new ApiResourceCommand.Example(
                "example", input("one"), output("one"))));
        return new ApiResourceDecisions(JSON).next(
                Optional.empty(), "customer-profile", "connection", command, ExpectedRevision.create());
    }

    private static SimulationCommandV2 command(
            Fixture fixture, JsonNode input, SimulationCommandV2.FixtureSelection selection) {
        return new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION, fixture.subject(),
                new SimulationCommandV2.Input.Inline(input),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                        List.of(new SimulationCommandV2.FixtureBinding(
                                new SimulationCommandV2.FixtureTarget.Subject(), selection))),
                SimulationCommandV2.ExecutionPolicy.denyAll());
    }

    private static SimulationCommandV2.FixtureSelection exact(Fixture fixture) {
        return new SimulationCommandV2.FixtureSelection.ExactCase(fixture.reference(), "case-1");
    }

    private static JsonNode input(String id) { return JSON.createObjectNode().put("id", id); }
    private static JsonNode output(String id) { return JSON.createObjectNode().put("id", id); }

    private static SimulationIdentity identity() {
        return new SimulationIdentity(SCOPE, "organization", "region", "HUMAN", "reviewer",
                "CONFIDENTIAL", "correlation");
    }

    private static FixtureSetAuthorityReader reader(StoredFixtureSet stored) {
        return new FixtureSetAuthorityReader() {
            @Override public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String id) {
                return Optional.empty();
            }
            @Override public Optional<StoredFixtureSet> findRevision(
                    AuthoringScope scope, String id, int revision) {
                FixtureSetView view = stored.generated().view();
                return SCOPE.equals(scope) && view.fixtureSetId().equals(id) && view.revision() == revision
                        ? Optional.of(stored) : Optional.empty();
            }
            @Override public List<FixtureSetSummary> listSummariesBySubject(
                    AuthoringScope scope, FixtureSubjectRef subject) {
                return List.of();
            }
        };
    }

    private record Fixture(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                           ExactFixtureSubjectRefV2.ApiResource subject,
                           SimulationCommandV2.ExactFixtureSetRef reference) { }
}
