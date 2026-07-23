package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateTransactionEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorSessionProtocolSchemaTest {
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemasCloseEverySerializedSessionProtocolField() throws Exception {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace initial = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        MirrorSessionPayload payload = MirrorSessionProtocolIntegrity.sealInitial(
                mapper, new MirrorSessionPayload(
                        "", model, List.of(effect), initial, ""), NOW);
        MirrorSessionCreateRequest create = new MirrorSessionCreateRequest(
                "", "create-1", payload);
        MirrorSessionCommandRequest command = new MirrorSessionCommandRequest(
                "", WriteEffectSpecIntegrity.reference(effect),
                initial.fingerprint(),
                Map.of("requestId", "REQ-1", "orderId", "O-100", "amount", 450));

        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, model, initial, MirrorStateBaselineResolver.none(),
                Clock.fixed(NOW, ZoneOffset.UTC), (expected, candidate) -> {
                });
        SessionStateSpace.TransactionReceipt receipt =
                engine.execute(effect, command.input());
        SessionStateSpace updated = engine.snapshot();
        MirrorSessionDescriptor descriptor =
                MirrorSessionProtocolIntegrity.sealDescriptor(mapper,
                        new MirrorSessionDescriptor(
                                "", updated.sessionId(), updated.scope(),
                                updated.planFingerprint(), updated.stateModelRef(),
                                updated.writeEffectRefs(), updated.stateRevision(),
                                MirrorSessionDescriptor.Status.ACTIVE,
                                updated.worldFingerprint(), updated.fingerprint(),
                                NOW, NOW, updated.expiresAt(), null, ""));
        MirrorSessionCommandResult result = new MirrorSessionCommandResult(
                "", descriptor, receipt, false);

        assertProperties(payload, "mirror-session-payload-v1.schema.json");
        assertProperties(create, "mirror-session-create-request-v1.schema.json");
        assertProperties(descriptor, "mirror-session-descriptor-v1.schema.json");
        assertProperties(command, "mirror-session-command-request-v1.schema.json");
        assertProperties(result, "mirror-session-command-result-v1.schema.json");
        assertThat(schema("mirror-session-command-request-v1.schema.json")
                .at("/properties/input/additionalProperties").asBoolean()).isTrue();
    }

    @Test
    void descriptorAndCommandSchemasCannotCarryLeaseOrEncryptionMaterial()
            throws Exception {
        JsonNode descriptor = schema(
                "mirror-session-descriptor-v1.schema.json").path("properties");
        JsonNode command = schema(
                "mirror-session-command-request-v1.schema.json").path("properties");

        assertThat(descriptor.has("leaseOwner")).isFalse();
        assertThat(descriptor.has("leaseFence")).isFalse();
        assertThat(descriptor.has("ciphertext")).isFalse();
        assertThat(descriptor.has("encryptionKeyId")).isFalse();
        assertThat(command.has("tenantId")).isFalse();
        assertThat(command.has("environmentId")).isFalse();
    }

    private void assertProperties(Object value, String schemaFile) throws Exception {
        JsonNode serialized = mapper.valueToTree(value);
        JsonNode contract = schema(schemaFile);
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        serialized.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        contract.path("properties").fieldNames().forEachRemaining(expected::add);

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(contract.path("additionalProperties").asBoolean()).isFalse();
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", file)));
    }
}
