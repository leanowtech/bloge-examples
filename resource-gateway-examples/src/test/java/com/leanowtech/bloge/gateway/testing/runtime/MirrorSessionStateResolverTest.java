package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.leanowtech.bloge.gateway.integration.mirror.BoundedStateExpression;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpaceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateModel;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpecIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StatefulMirrorProtocolTest;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionStateResolverTest {
    private static final String PLAN =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";
    private static final InvocationSite SITE = new InvocationSite(
            InvocationSite.SCHEMA_VERSION, "sha256:" + "a".repeat(64),
            "/root", "query-order", "httpResource", "order-api", "",
            "sha256:" + "b".repeat(64), InvocationSite.InvocationKind.RESOURCE,
            null, "", null);

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private StateModel model;
    private WriteEffectSpec refund;
    private StateReadSpec queryOrder;
    private MirrorSessionStateResolver resolver;

    @BeforeEach
    void setUp() {
        model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        refund = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        queryOrder = StateReadSpecIntegrity.seal(mapper, new StateReadSpec(
                StateReadSpec.SCHEMA_VERSION,
                "query-order",
                1,
                "",
                StatefulMirrorProtocolTest.scope(),
                StatefulMirrorProtocolTest.capabilityRef("query-order"),
                StateModelIntegrity.reference(model),
                "order",
                "order-id",
                List.of(BoundedStateExpression.input("/orderId")),
                new BoundedStateExpression(
                        BoundedStateExpression.Operator.ENTITY_POINTER,
                        null, "", StateReadSpec.RESULT_ALIAS, List.of(), Map.of()),
                StatefulMirrorProtocolTest.ownerProvenance(),
                CapabilitySnapshot.Lifecycle.ACTIVE,
                Instant.parse("2026-07-24T02:00:00Z")));
        resolver = new MirrorSessionStateResolver(mapper);
    }

    @Test
    void resolvesLiveEntityFromOneFrozenSessionSnapshot() {
        List<StateObservation> observations = new ArrayList<>();
        MirrorResolver.Match match = resolver.resolve(observedRequest(
                        payload(initialState()),
                        Map.of("orderId", "O-100"),
                        observations))
                .orElseThrow();

        assertThat(match.rule().behavior().kind())
                .isEqualTo(FixtureRule.BehaviorKind.RETURN);
        assertThat(match.rule().behavior().value())
                .isEqualTo(Map.of(
                        "orderId", "O-100",
                        "paidAmount", 1000,
                        "refundedAmount", 0));
        assertThat(match.artifactRefs())
                .extracting(MirrorArtifactRef::kind)
                .containsExactly("SESSION_STATE", "STATE_MODEL", "STATE_READ_SPEC");
        assertThat(match.ruleRefs()).containsExactly(
                "state-business-key:order-id:"
                        + initialState().businessKeyIndex().getFirst().valueFingerprint(),
                "state-read-spec:query-order:1");
        assertThat(observations).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.outcome()).isEqualTo(
                            MirrorStateRunEvidence.AccessOutcome
                                    .LIVE_ENTITY);
                    assertThat(observation.businessKeyFingerprint())
                            .startsWith("sha256:");
                    assertThat(observation.stateRecordFingerprint())
                            .startsWith("sha256:");
                    assertThat(observation.projectedOutputFingerprint())
                            .startsWith("sha256:");
                });
    }

    @Test
    void absentEntityAbstainsSoLowerSourcesMayResolve() {
        List<StateObservation> observations = new ArrayList<>();
        MirrorResolver.Request request = observedRequest(
                payload(initialState()),
                Map.of("orderId", "O-404"), observations);

        assertThat(resolver.resolve(request)).isEmpty();
        assertThat(observations).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.outcome()).isEqualTo(
                            MirrorStateRunEvidence.AccessOutcome.ABSENT);
                    assertThat(observation.businessKeyFingerprint())
                            .startsWith("sha256:");
                    assertThat(observation.stateRecordFingerprint())
                            .isEmpty();
                    assertThat(observation.projectedOutputFingerprint())
                            .isEmpty();
                });
    }

    @Test
    void missingReadSpecFailsClosedInsteadOfMasqueradingAsAnAbsentEntity() {
        MirrorSessionPayload missing = MirrorSessionProtocolIntegrity.seal(
                mapper, new MirrorSessionPayload(
                        MirrorSessionPayload.SCHEMA_VERSION,
                        model,
                        List.of(),
                        List.of(refund),
                        initialState(),
                        ""));

        assertThatThrownBy(() -> resolver.resolve(request(missing)))
                .isInstanceOf(TestControlException.class)
                .extracting("code")
                .isEqualTo("MIRROR_SESSION_READ_SPEC_MISSING");
    }

    @Test
    void tombstoneIsATerminalSessionStateMatchAndNeverFallsBack() {
        WriteEffectSpec delete = deleteEffect();
        SessionStateSpace state = admit(initialState(), List.of(refund, delete));
        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, model, state, MirrorStateBaselineResolver.none(),
                Clock.fixed(Instant.parse("2026-07-24T02:10:00Z"), ZoneOffset.UTC),
                MirrorStateTransactionEngine.CommitGuard.noop());
        engine.execute(delete, Map.of("requestId", "DEL-1", "orderId", "O-100"));

        MirrorSessionPayload deleted = payload(
                engine.snapshot(), List.of(refund, delete));
        List<StateObservation> observations = new ArrayList<>();
        MirrorResolver.Match match = resolver.resolve(
                observedRequest(
                        deleted, Map.of("orderId", "O-100"),
                        observations)).orElseThrow();

        assertThat(match.rule().behavior().kind())
                .isEqualTo(FixtureRule.BehaviorKind.THROW);
        assertThat(match.rule().behavior().errorCode())
                .isEqualTo(MirrorSessionStateResolver.ENTITY_TOMBSTONED);
        assertThat(match.limitations()).containsExactly("TOMBSTONE_TERMINAL");
        assertThat(engine.snapshot().businessKeyIndex())
                .extracting(SessionStateSpace.BusinessKeyBinding::entityKey)
                .containsExactly(new SessionStateSpace.EntityKey("order", "O-100"));
        assertThat(observations).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.outcome()).isEqualTo(
                            MirrorStateRunEvidence.AccessOutcome
                                    .TOMBSTONED);
                    assertThat(observation.stateRecordFingerprint())
                            .startsWith("sha256:");
                    assertThat(observation.projectedOutputFingerprint())
                            .isEmpty();
                });
    }

    @Test
    void rejectsSnapshotFromAnotherPlanBeforeInspectingBusinessState() {
        MirrorResolver.SessionContext context = new MirrorResolver.SessionContext(
                payload(initialState()),
                "sha256:" + "3".repeat(64),
                Map.of(SITE.invocationSiteId(),
                        StatefulMirrorProtocolTest.capabilityRef("query-order")));

        assertThatThrownBy(() -> resolver.resolve(new MirrorResolver.Request(
                SITE, 1, 1, MirrorResolutionJournal.requestFingerprint(
                mapper, Map.of("orderId", "O-100")),
                Map.of("orderId", "O-100"), List.of(), null, context)))
                .isInstanceOf(TestControlException.class)
                .extracting("code")
                .isEqualTo("MIRROR_SESSION_PLAN_MISMATCH");
    }

    @Test
    void chainUsesSessionStateBeforeOwnerRule() {
        FixtureRule owner = new FixtureRule(
                FixtureRule.SCHEMA_VERSION, "owner",
                FixtureRule.Selector.any(),
                FixtureRule.Behavior.returning(Map.of("orderId", "owner")),
                new FixtureRule.Consumption(false, 0, 0,
                        FixtureRule.ExhaustedAction.FAIL,
                        FixtureRule.UnmatchedAction.FAIL),
                FixtureRule.SchemaCheck.strict());
        MirrorResolver.Request request = new MirrorResolver.Request(
                SITE, 1, 1,
                MirrorResolutionJournal.requestFingerprint(
                        mapper, Map.of("orderId", "O-100")),
                Map.of("orderId", "O-100"), List.of(owner), null,
                sessionContext(payload(initialState())));
        CompiledExecutionControl.ResolvedControl control =
                new CompiledExecutionControl.ResolvedControl(
                        SITE, List.of(owner), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .MIRROR_SOURCE_THEN_SELECTOR,
                        List.of(
                                MirrorPlan.MirrorSource.SESSION_STATE,
                                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision decision =
                MirrorResolverChain.standard(mapper).resolve(control, request);

        assertThat(decision.source()).isEqualTo(
                MirrorPlan.MirrorSource.SESSION_STATE);
        assertThat(decision.match().rule().behavior().value())
                .isEqualTo(Map.of(
                        "orderId", "O-100",
                        "paidAmount", 1000,
                        "refundedAmount", 0));
    }

    private MirrorResolver.Request request(MirrorSessionPayload payload) {
        return request(payload, Map.of("orderId", "O-100"));
    }

    private MirrorResolver.Request request(
            MirrorSessionPayload payload, Map<String, Object> input) {
        return new MirrorResolver.Request(
                SITE, 1, 1,
                MirrorResolutionJournal.requestFingerprint(mapper, input),
                input, List.of(), null, sessionContext(payload));
    }

    private MirrorResolver.Request observedRequest(
            MirrorSessionPayload payload,
            Map<String, Object> input,
            List<StateObservation> observations) {
        return new MirrorResolver.Request(
                SITE, 1, 1,
                MirrorResolutionJournal.requestFingerprint(
                        mapper, input),
                input, List.of(), null,
                sessionContext(payload),
                (request, spec, businessKeyFingerprint,
                 outcome, stateRecordFingerprint,
                 projectedOutputFingerprint) ->
                        observations.add(new StateObservation(
                                outcome, businessKeyFingerprint,
                                stateRecordFingerprint,
                                projectedOutputFingerprint)));
    }

    private MirrorResolver.SessionContext sessionContext(
            MirrorSessionPayload payload) {
        return new MirrorResolver.SessionContext(
                payload, PLAN, Map.of(
                SITE.invocationSiteId(),
                StatefulMirrorProtocolTest.capabilityRef("query-order")));
    }

    private SessionStateSpace initialState() {
        return StatefulMirrorProtocolTest.initialState(mapper, model, refund);
    }

    private MirrorSessionPayload payload(SessionStateSpace state) {
        return payload(state, List.of(refund));
    }

    private MirrorSessionPayload payload(
            SessionStateSpace state, List<WriteEffectSpec> effects) {
        return MirrorSessionProtocolIntegrity.seal(mapper,
                new MirrorSessionPayload(
                        MirrorSessionPayload.SCHEMA_VERSION,
                        model,
                        List.of(queryOrder),
                        effects,
                        state,
                        ""));
    }

    private SessionStateSpace admit(
            SessionStateSpace state, List<WriteEffectSpec> effects) {
        List<MirrorArtifactRef> refs = effects.stream()
                .map(WriteEffectSpecIntegrity::reference).toList();
        return SessionStateSpaceIntegrity.seal(mapper, new SessionStateSpace(
                state.schemaVersion(), state.sessionId(), state.scope(),
                state.planFingerprint(), state.stateModelRef(), refs,
                state.stateRevision(), state.logicalClock(), state.randomSeed(),
                state.entities(), state.tombstones(), state.businessKeyIndex(),
                state.committedEvents(), state.processedCommands(),
                state.expiresAt(), "", ""));
    }

    private WriteEffectSpec deleteEffect() {
        return WriteEffectSpecIntegrity.seal(mapper, new WriteEffectSpec(
                WriteEffectSpec.SCHEMA_VERSION,
                "delete-order",
                1,
                "",
                StatefulMirrorProtocolTest.scope(),
                StatefulMirrorProtocolTest.capabilityRef("delete-order"),
                StateModelIntegrity.reference(model),
                List.of(new WriteEffectSpec.Mutation(
                        "delete-order",
                        WriteEffectSpec.Operation.DELETE,
                        "order",
                        BoundedStateExpression.input("/orderId"),
                        StatefulMirrorProtocolTest.capabilityRef("query-order"),
                        List.of(),
                        List.of(),
                        List.of())),
                BoundedStateExpression.object(Map.of(
                        "orderId", BoundedStateExpression.input("/orderId"),
                        "status", BoundedStateExpression.literal("DELETED"))),
                new WriteEffectSpec.Idempotency("/requestId", true),
                StatefulMirrorProtocolTest.ownerProvenance(),
                CapabilitySnapshot.Lifecycle.ACTIVE,
                Instant.parse("2026-07-24T02:00:00Z")));
    }

    private record StateObservation(
            MirrorStateRunEvidence.AccessOutcome outcome,
            String businessKeyFingerprint,
            String stateRecordFingerprint,
            String projectedOutputFingerprint
    ) {
    }
}
