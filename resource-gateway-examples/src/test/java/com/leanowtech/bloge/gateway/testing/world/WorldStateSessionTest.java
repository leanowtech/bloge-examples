package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldStateSessionTest {
    private static final WorldStateSession.Binding BINDING = new WorldStateSession.Binding(
            "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64), "run-1");

    @Test
    void startsFromDefaultsAndOverridesWithImmutableReadView() {
        WorldStateSpec spec = spec();
        StateAccessPlan plan = plan();
        WorldStateSession session = new WorldStateSession(spec, Map.of("/balance", 25), BINDING, plan);

        WorldStateView view = session.read(access(plan, "charge"));
        assertThat(view.value("/balance")).isEqualTo(25);
        assertThatThrownBy(() -> view.values().put("/balance", 99))
                .isInstanceOf(UnsupportedOperationException.class);
        StateAccessPlan.Access forged = new StateAccessPlan.Access("rule", "/root/charge#PRIMARY",
                "charge", List.of("/writeOnly"), List.of("/balance"));
        assertThatThrownBy(() -> session.read(forged))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_ACCESS_PLAN_MISMATCH));
    }

    @Test
    void commitsOneAtomicTransitionAndExposesOnlyPayloadFreeObservation() {
        StateAccessPlan plan = plan();
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);

        StateAccessPlan.Access access = access(plan, "charge");
        Integer result = session.transition(coordinate(access, 1), access,
                view -> new WorldStateSession.StateTransition<>(
                        80, Map.of("/balance", ((Integer) view.value("/balance")) - 20)));

        assertThat(result).isEqualTo(80);
        assertThat(session.read(access).value("/balance")).isEqualTo(80);
        assertThat(session.revision()).isEqualTo(1);
        assertThat(session.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.coordinate().canonicalKey()).isEqualTo(coordinate(access, 1).canonicalKey());
            assertThat(observation.readKeys()).containsExactly("/balance");
            assertThat(observation.writeKeys()).containsExactly("/balance", "/writeOnly");
            assertThat(observation.toString()).doesNotContain("80");
        });
    }

    @Test
    void failedTransitionDoesNotCommit() {
        StateAccessPlan plan = plan();
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);

        StateAccessPlan.Access access = access(plan, "fail");
        assertThatThrownBy(() -> session.transition(coordinate(access, 1), access, view -> {
                    throw new IllegalStateException("secret-payload");
                })).isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSITION_FAILED);
                    assertThat(error.getMessage()).doesNotContain("secret-payload");
                });

        assertThat(session.revision()).isZero();
        assertThat(session.read(access).value("/balance")).isEqualTo(100);
        assertThat(session.observations()).isEmpty();
    }

    @Test
    void resultFreezeOrFingerprintFailureDoesNotCommit() {
        StateAccessPlan plan = plan();
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        Map<String, Object> cyclic = new java.util.LinkedHashMap<>();
        cyclic.put("self", cyclic);

        StateAccessPlan.Access access = access(plan, "hostile");
        assertThatThrownBy(() -> session.transition(coordinate(access, 1), access,
                view -> new WorldStateSession.StateTransition<>(
                        cyclic, Map.of("/balance", 50))))
                .isInstanceOf(WorldModelException.class);

        assertThat(session.revision()).isZero();
        assertThat(session.read(access).value("/balance")).isEqualTo(100);
        assertThat(session.observations()).isEmpty();
    }

    @Test
    void actualWriteSetMayBeEmptyOrAConsistentSubsetOfDeclaredWrites() {
        StateAccessPlan plan = plan();
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);

        StateAccessPlan.Access noop = access(plan, "noop");
        session.transition(coordinate(noop, 1), noop, view -> new WorldStateSession.StateTransition<>(
                        "unchanged", Map.of()));
        StateAccessPlan.Access partial = access(plan, "partial");
        session.transition(coordinate(partial, 1), partial, view -> new WorldStateSession.StateTransition<>(
                        "partial", Map.of("/balance", 90)));

        assertThat(session.revision()).isEqualTo(2);
        assertThat(session.read(partial).value("/balance")).isEqualTo(90);
    }

    @Test
    void occurrenceCoordinatesAreUniqueAndSnapshotRestoresEveryObservation() {
        StateAccessPlan plan = plan();
        StateAccessPlan.Access access = access(plan, "charge");
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        WorldInvocationCoordinate first = coordinate(access, 1);
        WorldInvocationCoordinate second = coordinate(access, 2);
        AtomicInteger evaluations = new AtomicInteger();

        session.transition(first, access, view -> {
            evaluations.incrementAndGet();
            return new WorldStateSession.StateTransition<>("first", Map.of("/balance", 101));
        });
        session.transition(second, access, view -> {
            evaluations.incrementAndGet();
            return new WorldStateSession.StateTransition<>("second", Map.of("/balance", 102));
        });
        WorldStateSnapshot snapshot = session.snapshot();
        WorldStateSession restored = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        restored.restore(snapshot);

        assertThat(evaluations).hasValue(2);
        assertThat(restored.observations()).extracting(value -> value.coordinate().canonicalKey())
                .containsExactly(first.canonicalKey(), second.canonicalKey());
        assertThat(restored.snapshot().fingerprint()).isEqualTo(snapshot.fingerprint());
        assertThat(restored.snapshot().state()).isEqualTo(snapshot.state());
    }

    @Test
    void duplicateCoordinateAndMismatchedStructuralIdentityAreRejectedBeforeEvaluator() {
        StateAccessPlan plan = plan();
        StateAccessPlan.Access access = access(plan, "charge");
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        WorldInvocationCoordinate coordinate = coordinate(access, 1);
        session.transition(coordinate, access, view ->
                new WorldStateSession.StateTransition<>("first", Map.of("/balance", 101)));
        AtomicInteger evaluations = new AtomicInteger();

        assertThatThrownBy(() -> session.transition(coordinate, access, view -> {
            evaluations.incrementAndGet();
            return new WorldStateSession.StateTransition<>("duplicate", Map.of());
        })).isInstanceOfSatisfying(WorldModelException.class, error ->
                assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID));
        assertThat(evaluations).hasValue(0);

        WorldInvocationCoordinate foreignCoordinate = new WorldInvocationCoordinate(
                "/foreign", access.nodeId(), 1, 2, 1, access.coordinate());
        assertThatThrownBy(() -> session.transition(foreignCoordinate, access, view -> {
            evaluations.incrementAndGet();
            return new WorldStateSession.StateTransition<>("foreign", Map.of());
        })).isInstanceOfSatisfying(WorldModelException.class, error ->
                assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID));
        assertThat(evaluations).hasValue(0);
        WorldStateSession restored = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        restored.restore(session.snapshot());
    }

    @Test
    void constructorRejectsPlanThatDoesNotMatchStateSchema() {
        StateAccessPlan plan = plan();
        assertThatThrownBy(() -> new WorldStateSession(
                StateSpecV2.of(List.of(new StateKeySpec("/balance", StateKeySpec.Access.READ_WRITE,
                Map.of("type", "integer"), 0))), Map.of(), BINDING, plan))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_ACCESS_PLAN_MISMATCH));
    }

    @Test
    void restoreRejectsForeignSiteAndReducedWriteSetEvenWithRecomputedFingerprint() {
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan());
        WorldStateTransactionObservation foreign = new WorldStateTransactionObservation(
                observationCoordinate("foreign"), List.of("/balance"), List.of("/balance", "/writeOnly"),
                fp("read"), fp("write"), fp("result"));
        WorldStateTransactionObservation reduced = new WorldStateTransactionObservation(
                observationCoordinate("charge"), List.of("/balance"), List.of(),
                fp("read"), fp("write"), fp("result"));

        assertThatThrownBy(() -> session.restore(snapshot(1,
                Map.of("/balance", 100, "/writeOnly", "sealed"), List.of(foreign))))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_INVALID));
        assertThatThrownBy(() -> session.restore(snapshot(1,
                Map.of("/balance", 100, "/writeOnly", "sealed"), List.of(reduced))))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_INVALID));
    }

    @Test
    void disjointCommitOrderProducesOneCanonicalSnapshotFingerprint() {
        WorldStateSpec state = StateSpecV2.of(List.of(
                new StateKeySpec("/left", StateKeySpec.Access.READ_WRITE,
                        Map.of("type", "integer"), 0),
                new StateKeySpec("/right", StateKeySpec.Access.READ_WRITE,
                        Map.of("type", "integer"), 0)));
        StateAccessPlan plan = disjointPlan(state);
        StateAccessPlan.Access left = access(plan, "left");
        StateAccessPlan.Access right = access(plan, "right");
        WorldStateSession first = new WorldStateSession(state, Map.of(), BINDING, plan);
        WorldStateSession reversed = new WorldStateSession(state, Map.of(), BINDING, plan);
        first.transition(coordinate(left, 1), left,
                view -> new WorldStateSession.StateTransition<>("left", Map.of("/left", 1)));
        first.transition(coordinate(right, 1), right,
                view -> new WorldStateSession.StateTransition<>("right", Map.of("/right", 1)));
        reversed.transition(coordinate(right, 1), right,
                view -> new WorldStateSession.StateTransition<>("right", Map.of("/right", 1)));
        reversed.transition(coordinate(left, 1), left,
                view -> new WorldStateSession.StateTransition<>("left", Map.of("/left", 1)));

        assertThat(first.snapshot().state()).isEqualTo(reversed.snapshot().state());
        assertThat(first.snapshot().fingerprint()).isEqualTo(reversed.snapshot().fingerprint());
        assertThat(first.observations()).isEqualTo(reversed.observations());
    }

    @Test
    void ordinaryJacksonRoundTripRestoresBoundSnapshotAndRejectsTampering() throws Exception {
        StateAccessPlan plan = plan();
        StateAccessPlan.Access access = access(plan, "charge");
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        session.transition(coordinate(access, 1), access,
                view -> new WorldStateSession.StateTransition<>("round-trip",
                        Map.of("/balance", 101)));
        WorldStateSnapshot original = session.snapshot();
        ObjectMapper mapper = new ObjectMapper();

        WorldStateSnapshot decoded = mapper.readValue(
                mapper.writeValueAsString(original), WorldStateSnapshot.class);
        WorldStateSession restored = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        restored.restore(decoded);

        assertThat(decoded.fingerprint()).isEqualTo(original.fingerprint());
        assertThat(decoded.state()).isEqualTo(original.state());
        assertThat(decoded.observations()).isEqualTo(original.observations());
        assertThat(restored.snapshot().fingerprint()).isEqualTo(original.fingerprint());

        com.fasterxml.jackson.databind.node.ObjectNode tampered = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(mapper.writeValueAsString(original));
        tampered.with("state").put("/balance", 999);
        assertThatThrownBy(() -> mapper.readValue(
                mapper.writeValueAsString(tampered), WorldStateSnapshot.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void nestedTransitionIsRejectedAndOuterFailureLeavesSessionUnchanged() {
        StateAccessPlan plan = plan();
        StateAccessPlan.Access access = access(plan, "charge");
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        AtomicInteger nestedEvaluations = new AtomicInteger();

        assertThatThrownBy(() -> session.transition(coordinate(access, 1), access, view -> {
            assertThatThrownBy(() -> session.transition(coordinate(access, 2), access,
                    nestedView -> {
                        nestedEvaluations.incrementAndGet();
                        return new WorldStateSession.StateTransition<>("nested",
                                Map.of("/balance", 1));
                    })).isInstanceOfSatisfying(WorldModelException.class, error ->
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID));
            throw new IllegalStateException("outer-failure");
        })).isInstanceOfSatisfying(WorldModelException.class, error ->
                assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSITION_FAILED));

        assertThat(nestedEvaluations).hasValue(0);
        assertThat(session.revision()).isZero();
        assertThat(session.read(access).value("/balance")).isEqualTo(100);
        assertThat(session.observations()).isEmpty();
    }

    @Test
    void closeAndRestoreCannotMutateSessionDuringEvaluator() {
        StateAccessPlan plan = plan();
        StateAccessPlan.Access access = access(plan, "charge");
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan);
        WorldStateSnapshot initial = session.snapshot();

        assertThatThrownBy(() -> session.transition(coordinate(access, 1), access, view -> {
            assertThatThrownBy(session::close).isInstanceOfSatisfying(WorldModelException.class,
                    error -> assertThat(error.code()).isEqualTo(
                            WorldModelException.Code.STATE_TRANSACTION_INVALID));
            assertThatThrownBy(() -> session.restore(initial))
                    .isInstanceOfSatisfying(WorldModelException.class, error ->
                            assertThat(error.code()).isEqualTo(
                                    WorldModelException.Code.STATE_TRANSACTION_INVALID));
            throw new IllegalStateException("outer-failure");
        })).isInstanceOfSatisfying(WorldModelException.class, error ->
                assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSITION_FAILED));

        assertThat(session.revision()).isZero();
        assertThat(session.snapshot().state()).isEqualTo(initial.state());
    }

    @Test
    void snapshotIsStrictlyBoundAndTamperEvident() {
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan());
        WorldStateSnapshot snapshot = session.snapshot();
        assertThat(snapshot.fingerprint()).startsWith("sha256:");

        assertThatThrownBy(() -> new WorldStateSnapshot(snapshot.binding(),
                snapshot.stateSpecFingerprint(), snapshot.revision(),
                Map.of("/balance", 1, "/writeOnly", "sealed"), snapshot.observations(),
                snapshot.fingerprint()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_TAMPERED));

        WorldStateSession.Binding other = new WorldStateSession.Binding(
                BINDING.scenarioFingerprint(), BINDING.worldFingerprint(),
                BINDING.graphArtifactFingerprint(), "run-2");
        assertThatThrownBy(() -> new WorldStateSession(spec(), Map.of(), other, plan()).restore(snapshot))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_WRONG_BINDING));
    }

    @Test
    void rejectsNonCanonicalObservationKeysWithSanitizedCode() {
        assertThatThrownBy(() -> new WorldInvocationCoordinate("/root", "x", 0, 1, 1,
                "/root/x#PRIMARY"))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID);
                });
        assertThatThrownBy(() -> new WorldStateTransactionObservation(
                observationCoordinate("x"), List.of("/balance"), List.of(),
                "bad", fp("write"), fp("result")))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID));
    }

    @Test
    void sanitizesInvalidBindingViewAndSnapshotInputs() {
        assertThatThrownBy(() -> new WorldStateSession.Binding("scenario", BINDING.worldFingerprint(),
                BINDING.graphArtifactFingerprint(), BINDING.runId()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_BINDING_INVALID));
        assertThatThrownBy(() -> new WorldStateView(Map.of("balance", 1), 0, "fp"))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_INPUT_INVALID));
        assertThatThrownBy(() -> new WorldStateSnapshot(BINDING, "sha256:" + "4".repeat(64),
                1, Map.of(), new java.util.ArrayList<>(java.util.Collections.singletonList(null)), "fp"))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_INVALID));
    }

    @Test
    void runStateDescriptorRejectsInvalidIdentityAndStateMaterial() {
        assertThatThrownBy(() -> new WorldRunStateDescriptor(spec(), Map.of(),
                "scenario", BINDING.worldFingerprint(), BINDING.graphArtifactFingerprint()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_INPUT_INVALID));
        assertThatThrownBy(() -> new WorldRunStateDescriptor(spec(), Map.of("/balance", "bad"),
                BINDING.scenarioFingerprint(), BINDING.worldFingerprint(),
                BINDING.graphArtifactFingerprint()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_NOT_SUPPORTED));
        assertThat(WorldRunStateDescriptor.legacy().stateful()).isFalse();
    }

    @Test
    void restoreRejectsRecomputedButInconsistentSnapshotLogsAndStateKeys() {
        WorldStateTransactionObservation observation = new WorldStateTransactionObservation(
                observationCoordinate("charge"), List.of("/balance"), List.of("/balance"),
                fp("read"), fp("write"), fp("result"));
        WorldStateSession session = new WorldStateSession(spec(), Map.of(), BINDING, plan());

        WorldStateSnapshot wrongRevision = snapshot(2, Map.of("/balance", 100, "/writeOnly", "sealed"),
                List.of(observation));
        assertThatThrownBy(() -> session.restore(wrongRevision))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_INVALID));

        WorldStateSnapshot duplicateLog = snapshot(2,
                Map.of("/balance", 100, "/writeOnly", "sealed"), List.of(observation, observation));
        assertThatThrownBy(() -> session.restore(duplicateLog))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_INVALID));

        java.util.ArrayList<WorldStateTransactionObservation> oversized = new java.util.ArrayList<>();
        for (int index = 0; index < WorldStateSession.MAX_TRANSACTIONS + 1; index++) {
            oversized.add(new WorldStateTransactionObservation(observationCoordinate("step-" + index),
                    List.of("/balance"), List.of(), fp("read"), fp("write"), fp("result")));
        }
        WorldStateSnapshot tooLarge = snapshot(oversized.size(),
                Map.of("/balance", 100, "/writeOnly", "sealed"), oversized);
        assertThatThrownBy(() -> session.restore(tooLarge))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SNAPSHOT_INVALID));

        WorldStateSnapshot missingStateKey = snapshot(0, Map.of("/balance", 100), List.of());
        assertThatThrownBy(() -> session.restore(missingStateKey))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_INPUT_INVALID));
    }

    private static WorldStateSnapshot snapshot(long revision, Map<String, Object> state,
                                                List<WorldStateTransactionObservation> observations) {
        String stateSpecFingerprint = spec().fingerprint();
        String fingerprint = WorldStateSnapshot.fingerprint(BINDING, stateSpecFingerprint,
                revision, state, observations);
        return new WorldStateSnapshot(BINDING, stateSpecFingerprint, revision, state,
                observations, fingerprint);
    }

    private static WorldStateSpec spec() {
        return StateSpecV2.of(List.of(
                new StateKeySpec("/balance", StateKeySpec.Access.READ_WRITE,
                        Map.of("type", "integer"), 100),
                new StateKeySpec("/writeOnly", StateKeySpec.Access.WRITE,
                        Map.of("type", "string"), "sealed")));
    }

    private static StateAccessPlan plan() {
        Operator<Object, Object> identity = (input, context) -> input;
        GraphBuilder builder = new GraphBuilder("session-plan");
        var current = builder.node("charge", identity);
        current = current.node("fail", identity);
        current.dependsOn("charge");
        current = current.node("hostile", identity);
        current.dependsOn("fail");
        current = current.node("noop", identity);
        current.dependsOn("hostile");
        current = current.node("partial", identity);
        current.dependsOn("noop");
        var graph = current.build();
        InvocationInventory inventory = new InvocationInventoryBuilder(new DefaultOperatorRegistry())
                .build(graph, BINDING.graphArtifactFingerprint());
        WorldDelegateBinding binding = new WorldDelegateBinding("rule", "contract", fp("fragment"),
                BlogeFragmentRef.frozen("session.bloge", "graph state { transform result { value = true } }"),
                spec());
        return StateAccessPlan.compile(graph, Map.of("contract", inventory.entries()),
                List.of(binding), inventory.entries());
    }

    private static StateAccessPlan.Access access(StateAccessPlan plan, String nodeId) {
        return plan.accesses().stream().filter(value -> value.nodeId().equals(nodeId)).findFirst()
                .orElseThrow();
    }

    private static WorldInvocationCoordinate coordinate(StateAccessPlan.Access access, int occurrence) {
        return new WorldInvocationCoordinate("/root", access.nodeId(), 1, occurrence, 1,
                access.coordinate());
    }

    private static String fp(String seed) {
        return "sha256:" + com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint
                .ofText(seed).substring("sha256:".length());
    }

    private static StateAccessPlan disjointPlan(WorldStateSpec aggregate) {
        Operator<Object, Object> identity = (input, context) -> input;
        var builder = new GraphBuilder("disjoint-session-plan");
        var current = builder.node("left", identity);
        current = current.node("right", identity);
        var graph = current.build();
        InvocationInventory inventory = new InvocationInventoryBuilder(new DefaultOperatorRegistry())
                .build(graph, BINDING.graphArtifactFingerprint());
        StateSpecV2 leftState = StateSpecV2.of(List.of(new StateKeySpec("/left",
                StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 0)));
        StateSpecV2 rightState = StateSpecV2.of(List.of(new StateKeySpec("/right",
                StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 0)));
        WorldDelegateBinding leftBinding = new WorldDelegateBinding("left-rule", "left-contract",
                fp("left-fragment"), BlogeFragmentRef.frozen("left.bloge", "graph state { transform result { value = true } }"), leftState);
        WorldDelegateBinding rightBinding = new WorldDelegateBinding("right-rule", "right-contract",
                fp("right-fragment"), BlogeFragmentRef.frozen("right.bloge", "graph state { transform result { value = true } }"), rightState);
        return StateAccessPlan.compile(graph, Map.of(
                "left-contract", List.of(entryFor(inventory, "left")),
                "right-contract", List.of(entryFor(inventory, "right"))),
                List.of(leftBinding, rightBinding), inventory.entries());
    }

    private static InvocationInventory.Entry entryFor(InvocationInventory inventory, String nodeId) {
        return inventory.entries().stream().filter(entry -> entry.node().id().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private static WorldInvocationCoordinate observationCoordinate(String nodeId) {
        return new WorldInvocationCoordinate("/root", nodeId, 1, 1, 1,
                "/root/" + nodeId + "#PRIMARY");
    }
}
