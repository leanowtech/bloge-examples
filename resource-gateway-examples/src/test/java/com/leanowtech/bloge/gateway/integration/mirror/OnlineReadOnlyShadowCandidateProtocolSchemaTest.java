package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineReadOnlyShadowCandidateProtocolSchemaTest {
    private static final String COMMAND_SCHEMA =
            "online-read-only-shadow-candidate-command-v1.schema.json";
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .mapper();

    @Test
    void strictSchemaExactlyMatchesTheCandidateCommandWireModel()
            throws Exception {
        OnlineReadOnlyShadowCandidateCommand command =
                command();
        JsonNode schema = schema();

        assertThat(schema.path("additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(fieldNames(
                mapper.valueToTree(command)))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(
                                schema.path("properties")));
        assertThat(fieldNames(
                schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(
                                schema.path("required")));
        assertThat(command.commandFingerprint(mapper))
                .matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void schemaCannotCarryPayloadCredentialEndpointOrFreeText()
            throws Exception {
        Set<String> forbidden = Set.of(
                "payload",
                "requestPayload",
                "responsePayload",
                "requestBody",
                "responseBody",
                "credential",
                "credentialRef",
                "credentialValue",
                "secret",
                "token",
                "password",
                "endpoint",
                "endpointUri",
                "stackTrace",
                "message",
                "description");
        Set<String> propertyNames =
                new LinkedHashSet<>();

        collectPropertyNames(
                schema(), propertyNames);

        assertThat(propertyNames)
                .doesNotContainAnyElementsOf(
                        forbidden);
    }

    private OnlineReadOnlyShadowCandidateCommand command() {
        OnlineReadOnlyShadowBaselineCommand baseline =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(mapper);
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                baseline.executionId(),
                baseline.requestId(),
                baseline.scope(),
                baseline.inventoryRef(),
                baseline.unitId(),
                baseline.scenarioCaseRef(),
                baseline.targetCapabilityRef(),
                OnlineReadOnlyShadowBaselineTestFixtures
                        .ref(
                                "MIRROR_PLAN",
                                "candidate-plan",
                                'a'),
                baseline.comparisonPolicyRef(),
                OnlineReadOnlyShadowBaselineTestFixtures
                        .ref(
                                OnlineReadOnlyShadowBaselineObservation
                                        .ARTIFACT_KIND,
                                "online-baseline",
                                'b'),
                OnlineReadOnlyShadowBaselineTestFixtures
                        .ref(
                                "PAYLOAD_VAULT_RECEIPT",
                                "vault-receipt",
                                'c'),
                OnlineReadOnlyShadowBaselineTestFixtures
                        .fingerprint('d'),
                baseline.accessGrant(),
                baseline.admissionFingerprint(),
                baseline.admittedAt(),
                baseline.deadlineAt());
    }

    private JsonNode schema() throws Exception {
        return mapper.readTree(
                Files.readString(
                        schemaPath()));
    }

    private static Path schemaPath() {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas",
                "resource-gateway-mirror",
                COMMAND_SCHEMA);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                        "docs", "schemas",
                        "resource-gateway-mirror",
                        COMMAND_SCHEMA);
    }

    private static void collectPropertyNames(
            JsonNode value,
            Set<String> names) {
        if (value.isObject()) {
            JsonNode properties = value.get(
                    "properties");
            if (properties != null
                    && properties.isObject()) {
                properties.fieldNames()
                        .forEachRemaining(names::add);
            }
            value.elements().forEachRemaining(
                    child -> collectPropertyNames(
                            child, names));
        } else if (value.isArray()) {
            value.forEach(
                    child -> collectPropertyNames(
                            child, names));
        }
    }

    private static LinkedHashSet<String> fieldNames(
            JsonNode value) {
        LinkedHashSet<String> names =
                new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(
                names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(
            JsonNode value) {
        LinkedHashSet<String> values =
                new LinkedHashSet<>();
        value.forEach(
                item -> values.add(
                        item.asText()));
        return values;
    }
}
