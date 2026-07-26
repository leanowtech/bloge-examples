package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityKeySetProtocolSchemaTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:30:00Z");
    private static final String SCHEMA =
            "read-only-shadow-authority-key-set-publication-v1.schema.json";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemaExactlyMatchesTheSerializedProductionRecord() throws Exception {
        InMemoryVisualEvidenceSigner root =
                InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
        InMemoryVisualEvidenceSigner authority =
                InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
        VisualEvidenceSigner.VerificationKey key = authority.key(
                authority.descriptor().activeKeyId()).orElseThrow();
        var material = new ReadOnlyShadowAuthorityKeySetPublication.Material(
                "shadow-sampling-keys:staging", 1, "",
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-a", "project-a", "staging", "sg"),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                "data-governance:shadow", "security:shadow-bootstrap", 1,
                fingerprint('a'), NOW.minusSeconds(1), NOW, NOW.plusSeconds(3_600),
                List.of(new ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey(
                        key.keyId(), key.algorithm(), key.encodedPublicKey(),
                        NOW.minusSeconds(60), NOW.plusSeconds(7_200), null,
                        ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE)));
        var publication = new ReadOnlyShadowAuthorityKeySetIntegrity(mapper).seal(
                material, List.of(new ReadOnlyShadowAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:a", root)));
        JsonNode value = mapper.valueToTree(publication);
        JsonNode schema = mapper.readTree(Files.readString(schemaPath()));

        assertProperties(value, schema.path("properties"));
        assertProperties(value.path("material"), schema.at("/$defs/material/properties"));
        assertProperties(value.at("/material/scope"), schema.at("/$defs/scope/properties"));
        assertProperties(value.at("/material/keys/0"),
                schema.at("/$defs/authorityKey/properties"));
        assertProperties(value.at("/signatures/0"),
                schema.at("/$defs/rootSignature/properties"));
        for (String pointer : List.of(
                "", "/$defs/material", "/$defs/scope",
                "/$defs/authorityKey", "/$defs/rootSignature")) {
            assertStrictRequiredObject(schema.at(pointer));
        }
        assertThat(schema.at("/$defs/material/properties/keys/maxItems").asInt())
                .isEqualTo(128);
        assertThat(schema.at(
                "/$defs/authorityKey/allOf/0/then/properties/retiredAt/$ref").asText())
                .isEqualTo("#/$defs/canonicalInstant");
    }

    @Test
    void keySetProtocolCannotCarryBusinessPayloadsCredentialsOrPrivateKeys() throws Exception {
        String source = Files.readString(schemaPath());
        for (String forbidden : Set.of(
                "requestPayload", "responsePayload", "nodeInput", "nodeOutput",
                "credential", "secret", "token", "password", "privateKey",
                "stackTrace", "endpointUri")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static Path schemaPath() {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", SCHEMA);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", SCHEMA);
    }

    private static void assertStrictRequiredObject(JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(textValues(schema.path("required")));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
