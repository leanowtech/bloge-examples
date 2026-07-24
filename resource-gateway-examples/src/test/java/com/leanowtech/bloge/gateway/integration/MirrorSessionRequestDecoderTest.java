package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCommandRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCreateRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionDescriptor;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStoreGeneration;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStoreGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.StateModel;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StatefulMirrorProtocolTest;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionRequestDecoderTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MirrorSessionRequestDecoder decoder =
            new MirrorSessionRequestDecoder(mapper);

    @Test
    void decodesCompleteCreateAndOpenBusinessCommandInput() throws Exception {
        Fixture fixture = fixture();
        MirrorSessionCreateRequest create = new MirrorSessionCreateRequest(
                MirrorSessionCreateRequest.SCHEMA_VERSION,
                "create-1", fixture.payload());
        MirrorSessionCommandRequest command =
                new MirrorSessionCommandRequest(
                        MirrorSessionCommandRequest.SCHEMA_VERSION,
                        WriteEffectSpecIntegrity.reference(fixture.effect()),
                        "",
                        Map.of("customerField", Map.of("nested", true)));

        assertThat(decoder.decodeCreate(
                mapper.writeValueAsBytes(create), identity()))
                .isEqualTo(create);
        assertThat(decoder.decodeCommand(
                mapper.writeValueAsBytes(command), identity()))
                .isEqualTo(command);
    }

    @Test
    void decodesStrictSignedCheckpointAndRejectsUnknownFields()
            throws Exception {
        Fixture fixture = fixture();
        MirrorSessionCheckpointBundle checkpoint =
                checkpoint(fixture);

        assertThat(decoder.decodeCheckpoint(
                mapper.writeValueAsBytes(checkpoint), identity()))
                .isEqualTo(checkpoint);

        ObjectNode unknown = mapper.valueToTree(checkpoint);
        unknown.put("payload", "sensitive-value");
        assertMalformed(() -> decoder.decodeCheckpoint(
                        mapper.writeValueAsBytes(unknown), identity()),
                "RG.MIRROR.SESSION.CHECKPOINT_REQUEST_MALFORMED");
    }

    @Test
    void rejectsNonCanonicalCheckpointAttestationAndDependencyOrder()
            throws Exception {
        MirrorSessionCheckpointBundle checkpoint =
                checkpoint(fixture());
        ObjectNode unverifiable = mapper.valueToTree(checkpoint);
        ((ObjectNode) unverifiable.path("attestation"))
                .put("independentlyVerifiable", false);

        assertMalformed(() -> decoder.decodeCheckpoint(
                        mapper.writeValueAsBytes(unverifiable), identity()),
                "RG.MIRROR.SESSION.CHECKPOINT_REQUEST_MALFORMED");

        ObjectNode unordered = mapper.valueToTree(checkpoint);
        var refs = ((ObjectNode) unordered.path("checkpoint"))
                .putArray("stateReadRefs");
        String fingerprint = checkpoint.checkpoint()
                .stateModelRef().fingerprint();
        refs.addObject()
                .put("kind", "STATE_READ_SPEC")
                .put("id", "read-z")
                .put("revision", 1)
                .put("fingerprint", fingerprint);
        refs.addObject()
                .put("kind", "STATE_READ_SPEC")
                .put("id", "read-a")
                .put("revision", 1)
                .put("fingerprint", fingerprint);

        assertMalformed(() -> decoder.decodeCheckpoint(
                        mapper.writeValueAsBytes(unordered), identity()),
                "RG.MIRROR.SESSION.CHECKPOINT_REQUEST_MALFORMED");
    }

    @Test
    void rejectsDuplicateAndUnknownFieldsWithoutLeakingPayload() throws Exception {
        Fixture fixture = fixture();
        String duplicate = """
                {
                  "schemaVersion":"%s",
                  "requestId":"create-1",
                  "requestId":"create-2",
                  "payload":%s
                }
                """.formatted(
                MirrorSessionCreateRequest.SCHEMA_VERSION,
                mapper.writeValueAsString(fixture.payload()));
        ObjectNode unknown = (ObjectNode) mapper.valueToTree(
                new MirrorSessionCreateRequest(
                        MirrorSessionCreateRequest.SCHEMA_VERSION,
                        "create-1", fixture.payload()));
        ((ObjectNode) unknown.path("payload").path("state"))
                .put("unexpected", "sensitive-value");

        assertMalformed(() -> decoder.decodeCreate(
                duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                identity()), "RG.MIRROR.SESSION.CREATE_REQUEST_MALFORMED");
        assertMalformed(() -> decoder.decodeCreate(
                mapper.writeValueAsBytes(unknown), identity()),
                "RG.MIRROR.SESSION.CREATE_REQUEST_MALFORMED");
    }

    @Test
    void rejectsCommandsBeyondTheStructuralDepthLimit() throws Exception {
        Fixture fixture = fixture();
        ObjectNode command = mapper.createObjectNode();
        command.put("schemaVersion",
                MirrorSessionCommandRequest.SCHEMA_VERSION);
        command.set("writeEffectRef", mapper.valueToTree(
                WriteEffectSpecIntegrity.reference(fixture.effect())));
        command.put("expectedStateFingerprint", "");
        ObjectNode cursor = command.putObject("input");
        for (int depth = 0;
             depth < MirrorSessionRequestDecoder.MAXIMUM_DEPTH;
             depth++) {
            cursor = cursor.putObject("next");
        }

        assertMalformed(() -> decoder.decodeCommand(
                mapper.writeValueAsBytes(command), identity()),
                "RG.MIRROR.SESSION.COMMAND_REQUEST_MALFORMED");
    }

    private Fixture fixture() {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace state = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        MirrorSessionPayload payload =
                MirrorSessionProtocolIntegrity.sealInitial(
                        mapper,
                        new MirrorSessionPayload(
                                "", model, List.of(effect), state, ""),
                        NOW);
        return new Fixture(effect, payload);
    }

    private MirrorSessionCheckpointBundle checkpoint(
            Fixture fixture) {
        SessionStateSpace state = fixture.payload().state();
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
        MirrorSessionCheckpointIntegrityService integrity =
                new MirrorSessionCheckpointIntegrityService(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                Clock.fixed(NOW, ZoneOffset.UTC)),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return integrity.seal(
                new MirrorSessionStateStore.CheckpointSnapshot(
                        generation,
                        new MirrorSessionStateStore.SessionSnapshot(
                                fixture.payload(), descriptor)));
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "tool-studio", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL",
                "corr-1", Set.of(), "CONFIDENTIAL", "");
    }

    private static void assertMalformed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(400);
                            assertThat(failure.problem().code())
                                    .isEqualTo(code);
                            assertThat(failure.getMessage())
                                    .doesNotContain("sensitive-value");
                        });
    }

    private record Fixture(
            WriteEffectSpec effect,
            MirrorSessionPayload payload) {
    }
}
