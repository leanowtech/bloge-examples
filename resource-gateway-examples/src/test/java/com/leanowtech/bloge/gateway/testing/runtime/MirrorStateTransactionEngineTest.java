package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.mirror.BoundedStateExpression;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpaceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateModel;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StatefulMirrorProtocolTest;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateTransactionEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final StateModel model = StateModelIntegrity.seal(
            mapper, StatefulMirrorProtocolTest.stateModel());
    private final WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
            mapper, StatefulMirrorProtocolTest.refundEffect(model));

    @Test
    void executesAtomicRefundAndReturnsTheOriginalReceiptForAnExactRetry() {
        MirrorStateTransactionEngine engine = engine(initialState());
        AtomicInteger newCommandAdmissions = new AtomicInteger();
        Map<String, Object> input = Map.of(
                "requestId", "REQ-1", "orderId", "O-100",
                "amount", new BigDecimal("450"));

        SessionStateSpace.TransactionReceipt first = engine.execute(
                effect, input, current -> newCommandAdmissions.incrementAndGet());
        SessionStateSpace.TransactionReceipt retry = engine.execute(
                effect, input, current -> {
                    throw new AssertionError(
                            "exact replay must bypass new-command admission");
                });
        SessionStateSpace state = engine.snapshot();

        assertThat(retry).isEqualTo(first);
        assertThat(newCommandAdmissions).hasValue(1);
        assertThat(state.stateRevision()).isEqualTo(1);
        assertThat(state.entities()).hasSize(2);
        assertThat(entity(state, "refund", "R-1").value())
                .containsEntry("orderId", "O-100")
                .containsEntry("amount", new BigDecimal("450"));
        assertThat(entity(state, "order", "O-100").value())
                .containsEntry("refundedAmount", new BigDecimal("450"));
        assertThat(first.response()).isEqualTo(Map.of(
                "refundId", "R-1", "orderId", "O-100", "status", "CREATED"));
        assertThat(state.processedCommands()).hasSize(1);
    }

    @Test
    void rollsBackEveryEntityAndDeterministicSequenceWhenALaterMutationFails() {
        AtomicInteger attempts = new AtomicInteger();
        MirrorStateTransactionEngine engine = engine(initialState(), (expected, candidate) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated state-store failure");
            }
        });
        Map<String, Object> input = Map.of(
                "requestId", "REQ-1", "orderId", "O-100",
                "amount", new BigDecimal("450"));

        assertThatThrownBy(() -> engine.execute(effect, input))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.COMMIT_FAILED");
        assertThat(engine.snapshot().stateRevision()).isZero();
        assertThat(engine.snapshot().entities()).hasSize(1);

        SessionStateSpace.TransactionReceipt receipt = engine.execute(effect, input);
        assertThat(receipt.response()).isEqualTo(Map.of(
                "refundId", "R-1", "orderId", "O-100", "status", "CREATED"));
    }

    @Test
    void rejectsIdempotencyConflictsMissingBaselinesAndOverRefundWithoutPartialWrites() {
        MirrorStateTransactionEngine engine = engine(initialState());
        engine.execute(effect, Map.of(
                "requestId", "REQ-1", "orderId", "O-100",
                "amount", new BigDecimal("100")));

        assertThatThrownBy(() -> engine.execute(effect, Map.of(
                "requestId", "REQ-1", "orderId", "O-100",
                "amount", new BigDecimal("200"))))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.IDEMPOTENCY_CONFLICT");

        assertThatThrownBy(() -> engine.execute(effect, Map.of(
                "requestId", "REQ-2", "orderId", "O-404",
                "amount", new BigDecimal("100"))))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.BASELINE_ABSENT");

        assertThatThrownBy(() -> engine.execute(effect, Map.of(
                "requestId", "REQ-3", "orderId", "O-100",
                "amount", new BigDecimal("950"))))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("REFUND_EXCEEDS_PAID_AMOUNT");

        assertThat(engine.snapshot().stateRevision()).isEqualTo(1);
        assertThat(engine.snapshot().entities()).hasSize(2);
    }

    @Test
    void refusesRevokedWriteEffectsBeforeMutation() {
        WriteEffectSpec revoked = new WriteEffectSpec(
                effect.schemaVersion(),
                effect.specId(),
                effect.revision(),
                "",
                effect.scope(),
                effect.targetCapabilityRef(),
                effect.stateModelRef(),
                effect.mutations(),
                effect.responseProjection(),
                effect.idempotency(),
                effect.provenance().withRevocation("revocation:refund-effect:1"),
                com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot
                        .Lifecycle.REVOKED,
                effect.createdAt());
        WriteEffectSpec sealedRevoked = WriteEffectSpecIntegrity.seal(mapper, revoked);
        MirrorStateTransactionEngine engine = engine(initialState());

        assertThatThrownBy(() -> engine.execute(sealedRevoked, Map.of(
                "requestId", "REQ-1", "orderId", "O-100", "amount", BigDecimal.ONE)))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.WRITE_EFFECT_NOT_ACTIVE");
        assertThat(engine.snapshot().stateRevision()).isZero();
    }

    @Test
    void serializesConcurrentMutationsWithoutLostUpdatesOrDuplicateIds() throws Exception {
        MirrorStateTransactionEngine engine = engine(initialState());
        int commandCount = 24;
        List<Future<SessionStateSpace.TransactionReceipt>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < commandCount; index++) {
                String requestId = "REQ-" + index;
                futures.add(executor.submit(() -> engine.execute(effect, Map.of(
                        "requestId", requestId,
                        "orderId", "O-100",
                        "amount", BigDecimal.ONE))));
            }
            for (Future<SessionStateSpace.TransactionReceipt> future : futures) {
                future.get();
            }
        }

        SessionStateSpace state = engine.snapshot();
        assertThat(state.stateRevision()).isEqualTo(commandCount);
        assertThat(state.processedCommands()).hasSize(commandCount);
        assertThat(state.entities().stream()
                .filter(entity -> "refund".equals(entity.key().entityType())))
                .extracting(entity -> entity.key().entityId())
                .doesNotHaveDuplicates()
                .hasSize(commandCount);
        assertThat(entity(state, "order", "O-100").value())
                .containsEntry("refundedAmount", new BigDecimal("24"));
    }

    @Test
    void copiesOnlyAnExactAllowedBaselineAndCommitsItWithTheWrite() {
        SessionStateSpace empty = SessionStateSpaceIntegrity.seal(
                mapper, initialState().withWorld(
                        0, NOW, List.of(), List.of(), List.of(), List.of(), List.of()));
        SessionStateSpace.EntitySnapshot order = SessionStateSpaceIntegrity.sealEntity(
                mapper, new SessionStateSpace.EntitySnapshot(
                        new SessionStateSpace.EntityKey("order", "O-100"),
                        1,
                        Map.of("orderId", "O-100", "paidAmount", 1000, "refundedAmount", 0),
                        ""));
        SessionStateSpace.BusinessKeyBinding orderKey =
                SessionStateSpaceIntegrity.businessKey(
                        mapper, "order-id", List.of("O-100"), order.key());
        MirrorStateBaselineResolver resolver = request -> Optional.of(
                new MirrorStateBaselineResolver.Baseline(
                        MirrorStateBaselineResolver.Source.RECORDED_EXACT,
                        new MirrorArtifactRef(
                                "CORPUS_SAMPLE",
                                "query-order-O-100",
                                1,
                                "sha256:" + "3".repeat(64)),
                        order,
                        List.of(orderKey)));
        MirrorStateTransactionEngine engine = engine(
                empty, resolver, MirrorStateTransactionEngine.CommitGuard.noop());

        engine.execute(effect, Map.of(
                "requestId", "REQ-1", "orderId", "O-100", "amount", BigDecimal.ONE));

        assertThat(engine.snapshot().committedEvents())
                .extracting(SessionStateSpace.StateTransitionEvent::operation)
                .containsExactly(
                        SessionStateSpace.TransitionOperation.CREATE,
                        SessionStateSpace.TransitionOperation.COPY_IN,
                        SessionStateSpace.TransitionOperation.UPDATE);
        assertThat(entity(engine.snapshot(), "order", "O-100").value())
                .containsEntry("refundedAmount", BigDecimal.ONE);
    }

    @Test
    void rejectsBaselineSourceArtifactMismatch() {
        SessionStateSpace.EntitySnapshot order = entity(initialState(), "order", "O-100");
        SessionStateSpace.BusinessKeyBinding orderKey = initialState().businessKeyIndex().getFirst();

        assertThatThrownBy(() -> new MirrorStateBaselineResolver.Baseline(
                MirrorStateBaselineResolver.Source.RECORDED_EXACT,
                new MirrorArtifactRef(
                        "FIXTURE", "order-fixture", 1, "sha256:" + "3".repeat(64)),
                order,
                List.of(orderKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void deleteProducesAnAuditableTombstoneThatCannotBeRecreated() {
        WriteEffectSpec delete = deleteEffect();
        SessionStateSpace admitted = admit(initialState(), delete);
        MirrorStateTransactionEngine deletedEngine = engine(admitted);
        deletedEngine.execute(delete, Map.of("requestId", "DEL-1", "orderId", "O-100"));

        assertThat(deletedEngine.snapshot().entities()).isEmpty();
        assertThat(deletedEngine.snapshot().tombstones())
                .extracting(value -> value.key().entityId())
                .containsExactly("O-100");
        assertThat(deletedEngine.snapshot().processedCommands()).hasSize(1);

        assertThatThrownBy(() -> deletedEngine.execute(effect, Map.of(
                "requestId", "REQ-1", "orderId", "O-100",
                "amount", BigDecimal.ONE)))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.ENTITY_TOMBSTONED");
    }

    @Test
    void aSuccessfulCommitWinsOverLateThreadInterruption() {
        MirrorStateTransactionEngine engine = engine(
                initialState(),
                MirrorStateBaselineResolver.none(),
                (expected, candidate) -> Thread.currentThread().interrupt());
        try {
            SessionStateSpace.TransactionReceipt receipt = engine.execute(effect, Map.of(
                    "requestId", "REQ-1", "orderId", "O-100",
                    "amount", BigDecimal.ONE));

            assertThat(receipt.revisionAfter()).isEqualTo(1);
            assertThat(engine.snapshot().stateRevision()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionBeforeCommitRollsBackTheWholeCommand() {
        MirrorStateTransactionEngine engine = engine(initialState());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> engine.execute(effect, Map.of(
                    "requestId", "REQ-1", "orderId", "O-100",
                    "amount", BigDecimal.ONE)))
                    .isInstanceOf(MirrorStateException.class)
                    .hasMessage("RG.MIRROR.STATE.CANCELLED_BEFORE_COMMIT");
            assertThat(engine.snapshot().stateRevision()).isZero();
            assertThat(engine.snapshot().entities()).hasSize(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidCandidateEntitySchemaRollsBackWithoutAnEventOrReceipt() {
        WriteEffectSpec invalid = invalidCreateEffect();
        SessionStateSpace admitted = admit(initialState(), invalid);
        MirrorStateTransactionEngine engine = engine(admitted);

        assertThatThrownBy(() -> engine.execute(invalid, Map.of(
                "requestId", "REQ-1", "orderId", "O-100", "amount", BigDecimal.ONE)))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.ENTITY_SCHEMA_INVALID");
        assertThat(engine.snapshot().stateRevision()).isZero();
        assertThat(engine.snapshot().entities()).hasSize(1);
        assertThat(engine.snapshot().committedEvents()).isEmpty();
        assertThat(engine.snapshot().processedCommands()).isEmpty();
    }

    @Test
    void rejectsAnEventJournalWithoutAnExactReceiptClosure() {
        MirrorStateTransactionEngine engine = engine(initialState());
        engine.execute(effect, Map.of(
                "requestId", "REQ-1", "orderId", "O-100", "amount", BigDecimal.ONE));
        SessionStateSpace committed = engine.snapshot();
        SessionStateSpace withoutReceipts = committed.withWorld(
                committed.stateRevision(),
                committed.logicalClock(),
                committed.entities(),
                committed.tombstones(),
                committed.businessKeyIndex(),
                committed.committedEvents(),
                List.of());

        assertThatThrownBy(() -> SessionStateSpaceIntegrity.seal(mapper, withoutReceipts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipt");
    }

    @Test
    void expiredSessionsFailClosed() {

        MirrorStateTransactionEngine expired = new MirrorStateTransactionEngine(
                mapper,
                model,
                SessionStateSpaceIntegrity.seal(
                        mapper, initialState().withExpiry(NOW.minusSeconds(1))),
                MirrorStateBaselineResolver.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                MirrorStateTransactionEngine.CommitGuard.noop());
        assertThatThrownBy(() -> expired.execute(effect, Map.of(
                "requestId", "REQ-1", "orderId", "O-100",
                "amount", BigDecimal.ONE)))
                .isInstanceOf(MirrorStateException.class)
                .hasMessage("RG.MIRROR.STATE.SESSION_EXPIRED");
    }

    private MirrorStateTransactionEngine engine(SessionStateSpace initial) {
        return engine(initial, MirrorStateTransactionEngine.CommitGuard.noop());
    }

    private MirrorStateTransactionEngine engine(
            SessionStateSpace initial, MirrorStateTransactionEngine.CommitGuard guard) {
        return engine(initial, MirrorStateBaselineResolver.none(), guard);
    }

    private MirrorStateTransactionEngine engine(
            SessionStateSpace initial,
            MirrorStateBaselineResolver resolver,
            MirrorStateTransactionEngine.CommitGuard guard) {
        return new MirrorStateTransactionEngine(
                mapper,
                model,
                initial,
                resolver,
                Clock.fixed(NOW, ZoneOffset.UTC),
                guard);
    }

    private SessionStateSpace initialState() {
        return StatefulMirrorProtocolTest.initialState(mapper, model, effect);
    }

    private WriteEffectSpec deleteEffect() {
        WriteEffectSpec value = new WriteEffectSpec(
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
                com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot
                        .Lifecycle.ACTIVE,
                NOW);
        return WriteEffectSpecIntegrity.seal(mapper, value);
    }

    private WriteEffectSpec invalidCreateEffect() {
        BoundedStateExpression refundId =
                BoundedStateExpression.literal("R-invalid");
        String mutationId = "create-invalid-refund";
        WriteEffectSpec value = new WriteEffectSpec(
                WriteEffectSpec.SCHEMA_VERSION,
                mutationId,
                1,
                "",
                StatefulMirrorProtocolTest.scope(),
                StatefulMirrorProtocolTest.capabilityRef(mutationId),
                StateModelIntegrity.reference(model),
                List.of(new WriteEffectSpec.Mutation(
                        mutationId,
                        WriteEffectSpec.Operation.CREATE,
                        "refund",
                        refundId,
                        null,
                        List.of(),
                        List.of(
                                new WriteEffectSpec.FieldEffect(
                                        "/refundId", refundId),
                                new WriteEffectSpec.FieldEffect(
                                        "/orderId",
                                        BoundedStateExpression.input("/orderId")),
                                new WriteEffectSpec.FieldEffect(
                                        "/amount",
                                        BoundedStateExpression.input("/amount")),
                                new WriteEffectSpec.FieldEffect(
                                        "/status",
                                        BoundedStateExpression.literal("CREATED")),
                                new WriteEffectSpec.FieldEffect(
                                        "/createdAt",
                                        BoundedStateExpression.literal(42))),
                        List.of(
                                new WriteEffectSpec.BusinessKeyRule(
                                        "refund-id",
                                        List.of(BoundedStateExpression.entity(
                                                mutationId, "/refundId"))),
                                new WriteEffectSpec.BusinessKeyRule(
                                        "refund-request",
                                        List.of(
                                                BoundedStateExpression.entity(
                                                        mutationId, "/orderId"),
                                                BoundedStateExpression.entity(
                                                        mutationId, "/refundId")))))),
                BoundedStateExpression.object(Map.of(
                        "refundId", BoundedStateExpression.entity(
                                mutationId, "/refundId"))),
                new WriteEffectSpec.Idempotency("/requestId", true),
                StatefulMirrorProtocolTest.ownerProvenance(),
                com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot
                        .Lifecycle.ACTIVE,
                NOW);
        return WriteEffectSpecIntegrity.seal(mapper, value);
    }

    private SessionStateSpace admit(SessionStateSpace state, WriteEffectSpec additional) {
        return SessionStateSpaceIntegrity.seal(mapper, new SessionStateSpace(
                state.schemaVersion(),
                state.sessionId(),
                state.scope(),
                state.planFingerprint(),
                state.stateModelRef(),
                List.of(
                        WriteEffectSpecIntegrity.reference(effect),
                        WriteEffectSpecIntegrity.reference(additional)),
                state.stateRevision(),
                state.logicalClock(),
                state.randomSeed(),
                state.entities(),
                state.tombstones(),
                state.businessKeyIndex(),
                state.committedEvents(),
                state.processedCommands(),
                state.expiresAt(),
                "",
                ""));
    }

    private static SessionStateSpace.EntitySnapshot entity(
            SessionStateSpace state, String type, String id) {
        return state.entities().stream()
                .filter(candidate -> type.equals(candidate.key().entityType())
                        && id.equals(candidate.key().entityId()))
                .findFirst()
                .orElseThrow();
    }
}
