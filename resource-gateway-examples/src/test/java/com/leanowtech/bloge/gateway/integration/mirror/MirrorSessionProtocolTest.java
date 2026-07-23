package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateTransactionEngine;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionProtocolTest {
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private static final String SHA_ZERO = "sha256:" + "0".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sealsInitialPayloadAndPayloadFreeDescriptor() {
        Fixture fixture = fixture();

        MirrorSessionPayload payload = MirrorSessionProtocolIntegrity.sealInitial(
                mapper, fixture.payload(), NOW);
        MirrorSessionDescriptor descriptor =
                MirrorSessionProtocolIntegrity.sealDescriptor(mapper,
                        descriptor(payload, MirrorSessionDescriptor.Status.ACTIVE));

        assertThat(payload.fingerprint()).startsWith("sha256:");
        assertThat(descriptor.fingerprint()).startsWith("sha256:");
        assertThat(descriptor.stateRevision()).isZero();
        assertThat(descriptor.writeEffectRefs())
                .containsExactly(WriteEffectSpecIntegrity.reference(fixture.effect()));
        MirrorSessionProtocolIntegrity.verifyInitial(mapper, payload, NOW);
        MirrorSessionProtocolIntegrity.verifyDescriptor(mapper, descriptor);
    }

    @Test
    void rejectsPayloadTamperingAndIncompleteEffectClosure() {
        Fixture fixture = fixture();
        MirrorSessionPayload payload = MirrorSessionProtocolIntegrity.sealInitial(
                mapper, fixture.payload(), NOW);

        assertThatThrownBy(() -> MirrorSessionProtocolIntegrity.verify(
                mapper, payload.withFingerprint(SHA_ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session payload fingerprint mismatch");

        MirrorSessionPayload missingEffect = new MirrorSessionPayload(
                MirrorSessionPayload.SCHEMA_VERSION,
                fixture.model(),
                List.of(WriteEffectSpecIntegrity.seal(
                        mapper, StatefulMirrorProtocolTest.refundEffect(fixture.model())
                                .withScope(new CapabilitySnapshot.Scope(
                                        "tenant-a", "org-b", "tool-studio", "test", "sg")))),
                fixture.state(),
                "");
        assertThatThrownBy(() -> MirrorSessionProtocolIntegrity.seal(
                mapper, missingEffect))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void rejectsNonInitialJournalsAndUnboundedLifetime() {
        Fixture fixture = fixture();
        SessionStateSpace tooLong = SessionStateSpaceIntegrity.seal(
                mapper, copyState(fixture.state(), NOW.plusSeconds(86_401)));
        MirrorSessionPayload payload = new MirrorSessionPayload(
                MirrorSessionPayload.SCHEMA_VERSION,
                fixture.model(),
                List.of(fixture.effect()),
                tooLong,
                "");

        assertThatThrownBy(() -> MirrorSessionProtocolIntegrity.sealInitial(
                mapper, payload, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next 24 hours");

        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, fixture.model(), fixture.state(),
                MirrorStateBaselineResolver.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (expected, candidate) -> {
                });
        engine.execute(fixture.effect(), Map.of(
                "requestId", "REQ-0",
                "orderId", "O-100",
                "amount", 100));
        SessionStateSpace nonInitial = engine.snapshot();
        MirrorSessionPayload journaled = new MirrorSessionPayload(
                MirrorSessionPayload.SCHEMA_VERSION,
                fixture.model(),
                List.of(fixture.effect()),
                nonInitial,
                "");

        assertThatThrownBy(() -> MirrorSessionProtocolIntegrity.sealInitial(
                mapper, journaled, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision zero");
    }

    @Test
    void createAndCommandContractsCarryExactIdempotencyAndStateFences() {
        Fixture fixture = fixture();
        MirrorSessionPayload payload = MirrorSessionProtocolIntegrity.sealInitial(
                mapper, fixture.payload(), NOW);
        MirrorSessionCreateRequest create = new MirrorSessionCreateRequest(
                MirrorSessionCreateRequest.SCHEMA_VERSION, "create-1", payload);
        MirrorSessionCommandRequest command = new MirrorSessionCommandRequest(
                MirrorSessionCommandRequest.SCHEMA_VERSION,
                WriteEffectSpecIntegrity.reference(fixture.effect()),
                fixture.state().fingerprint(),
                Map.of("requestId", "REQ-1", "orderId", "O-100", "amount", 450));

        assertThat(MirrorSessionProtocolIntegrity.createFingerprint(mapper, create))
                .startsWith("sha256:");
        assertThat(command.input()).containsEntry("requestId", "REQ-1");
        assertThatThrownBy(() -> new MirrorSessionCommandRequest(
                "", StatefulMirrorProtocolTest.capabilityRef("wrong"), "",
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WRITE_EFFECT");
    }

    @Test
    void sessionAggregateRejectsIdentifiersThatCannotRoundTripThroughHttpPaths() {
        Fixture fixture = fixture();
        SessionStateSpace state = fixture.state();
        SessionStateSpace pathUnsafe = new SessionStateSpace(
                state.schemaVersion(),
                "unsafe/session",
                state.scope(),
                state.planFingerprint(),
                state.stateModelRef(),
                state.writeEffectRefs(),
                state.stateRevision(),
                state.logicalClock(),
                state.randomSeed(),
                state.entities(),
                state.tombstones(),
                state.businessKeyIndex(),
                state.committedEvents(),
                state.processedCommands(),
                state.expiresAt(),
                state.worldFingerprint(),
                state.fingerprint());

        assertThatThrownBy(() -> new MirrorSessionPayload(
                MirrorSessionPayload.SCHEMA_VERSION,
                fixture.model(),
                List.of(fixture.effect()),
                pathUnsafe,
                ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path-safe");
    }

    @Test
    void terminalDescriptorsRequireADeletionTime() {
        Fixture fixture = fixture();
        MirrorSessionPayload payload = MirrorSessionProtocolIntegrity.sealInitial(
                mapper, fixture.payload(), NOW);

        assertThatThrownBy(() -> descriptor(
                payload, MirrorSessionDescriptor.Status.DESTROYED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destroyedAt");
    }

    @Test
    void replayedResultMayReturnAnOlderOriginalReceiptWithTheCurrentDescriptor() {
        Fixture fixture = fixture();
        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, fixture.model(), fixture.state(),
                MirrorStateBaselineResolver.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (expected, candidate) -> {
                });
        SessionStateSpace.TransactionReceipt receipt = engine.execute(
                fixture.effect(), Map.of(
                        "requestId", "REQ-1",
                        "orderId", "O-100",
                        "amount", 100));
        engine.execute(fixture.effect(), Map.of(
                "requestId", "REQ-2",
                "orderId", "O-100",
                "amount", 50));
        SessionStateSpace current = engine.snapshot();
        MirrorSessionDescriptor later = MirrorSessionProtocolIntegrity.sealDescriptor(
                mapper, new MirrorSessionDescriptor(
                        "", current.sessionId(), current.scope(),
                        current.planFingerprint(), current.stateModelRef(),
                        current.writeEffectRefs(), current.stateRevision(),
                        MirrorSessionDescriptor.Status.ACTIVE,
                        current.worldFingerprint(), current.fingerprint(),
                        NOW, NOW, current.expiresAt(), null, ""));

        assertThat(new MirrorSessionCommandResult(
                "", later, receipt, true).replayed()).isTrue();
        assertThatThrownBy(() -> new MirrorSessionCommandResult(
                "", later, receipt, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
    }

    private Fixture fixture() {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace state = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        return new Fixture(model, effect, state,
                new MirrorSessionPayload(
                        MirrorSessionPayload.SCHEMA_VERSION,
                        model, List.of(effect), state, ""));
    }

    private static MirrorSessionDescriptor descriptor(
            MirrorSessionPayload payload, MirrorSessionDescriptor.Status status) {
        SessionStateSpace state = payload.state();
        return new MirrorSessionDescriptor(
                MirrorSessionDescriptor.SCHEMA_VERSION,
                state.sessionId(),
                state.scope(),
                state.planFingerprint(),
                state.stateModelRef(),
                state.writeEffectRefs(),
                state.stateRevision(),
                status,
                state.worldFingerprint(),
                state.fingerprint(),
                NOW,
                NOW,
                state.expiresAt(),
                null,
                "");
    }

    private static SessionStateSpace copyState(
            SessionStateSpace state, Instant expiresAt) {
        return new SessionStateSpace(
                state.schemaVersion(), state.sessionId(), state.scope(),
                state.planFingerprint(), state.stateModelRef(), state.writeEffectRefs(),
                state.stateRevision(), state.logicalClock(), state.randomSeed(),
                state.entities(), state.tombstones(), state.businessKeyIndex(),
                state.committedEvents(), state.processedCommands(), expiresAt, "", "");
    }

    private record Fixture(
            StateModel model,
            WriteEffectSpec effect,
            SessionStateSpace state,
            MirrorSessionPayload payload) {
    }
}
