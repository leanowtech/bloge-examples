package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionCheckpointIntegrityServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final MirrorSessionCheckpointIntegrityService integrity =
            new MirrorSessionCheckpointIntegrityService(
                    mapper, signer,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exactDependencyClosureRejectsAValidButDifferentReadModel() {
        Material current = material(false);
        Material differentDependencies = material(true);
        MirrorSessionCheckpointBundle checkpoint =
                integrity.seal(differentDependencies.snapshot());

        assertThatThrownBy(() -> integrity.verifyCurrent(
                checkpoint, current.snapshot()))
                .isInstanceOfSatisfying(
                        MirrorSessionCheckpointException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(
                                        MirrorSessionCheckpointException.Code
                                                .DEPENDENCY_CONFLICT));
    }

    @Test
    void anotherSignerCannotVerifyAnOtherwiseWellFormedCheckpoint() {
        MirrorSessionCheckpointBundle checkpoint =
                integrity.seal(material(false).snapshot());
        MirrorSessionCheckpointIntegrityService other =
                new MirrorSessionCheckpointIntegrityService(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                Clock.fixed(NOW, ZoneOffset.UTC)),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(other.verify(checkpoint))
                .isEqualTo(
                        MirrorSessionCheckpointIntegrityService.Verification
                                .INVALID);
    }

    @Test
    void unavailableSignerCannotCreateOrClaimCheckpointTrust() {
        MirrorSessionCheckpointIntegrityService unavailable =
                new MirrorSessionCheckpointIntegrityService(
                        mapper, VisualEvidenceSigner.unavailable(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        Material material = material(false);

        assertThatThrownBy(() -> unavailable.seal(
                material.snapshot()))
                .isInstanceOfSatisfying(
                        MirrorSessionCheckpointException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(
                                        MirrorSessionCheckpointException.Code
                                                .SIGNER_UNAVAILABLE));
        MirrorSessionCheckpointBundle checkpoint =
                integrity.seal(material.snapshot());
        assertThat(unavailable.verify(checkpoint))
                .isEqualTo(
                        MirrorSessionCheckpointIntegrityService.Verification
                                .UNAVAILABLE);
    }

    private Material material(boolean includeReadSpec) {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        StateReadSpec read = StateReadSpecIntegrity.seal(
                mapper,
                StatefulMirrorProtocolTest.queryOrderReadSpec(model));
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace state =
                StatefulMirrorProtocolTest.initialState(
                        mapper, model, effect);
        MirrorSessionPayload payload =
                MirrorSessionProtocolIntegrity.sealInitial(
                        mapper,
                        new MirrorSessionPayload(
                                "", model,
                                includeReadSpec
                                        ? List.of(read) : List.of(),
                                List.of(effect), state, ""),
                        NOW);
        MirrorSessionDescriptor descriptor =
                MirrorSessionProtocolIntegrity.sealDescriptor(
                        mapper, new MirrorSessionDescriptor(
                                "", state.sessionId(), state.scope(),
                                state.planFingerprint(),
                                state.stateModelRef(),
                                state.writeEffectRefs(),
                                state.stateRevision(),
                                MirrorSessionDescriptor.Status.ACTIVE,
                                state.worldFingerprint(),
                                state.fingerprint(),
                                NOW, NOW, state.expiresAt(),
                                null, ""));
        MirrorSessionStoreGeneration generation =
                MirrorSessionStoreGenerationIntegrity.seal(
                        mapper, new MirrorSessionStoreGeneration(
                                "", "store-generation-1", 1,
                                NOW.minusSeconds(60), ""));
        return new Material(
                new MirrorSessionStateStore.CheckpointSnapshot(
                        generation,
                        new MirrorSessionStateStore.SessionSnapshot(
                                payload, descriptor)));
    }

    private record Material(
            MirrorSessionStateStore.CheckpointSnapshot snapshot
    ) {
    }
}
