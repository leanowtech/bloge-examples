package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityProtocolSchemaTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T09:30:00Z");
    private static final Instant ISSUED_AT =
            NOW.minusSeconds(30);
    private static final Instant EXPIRES_AT =
            NOW.plusSeconds(600);

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ReadOnlyShadowAuthorityIntegrity integrity =
            new ReadOnlyShadowAuthorityIntegrity(mapper);
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void strictSchemasExactlyMatchEverySerializedProductionRecord() throws Exception {
        var policy = policy();
        var grant = grant(policy.artifactRef());
        var killSwitch = killSwitch();

        assertEnvelope(policy, schema("read-only-shadow-guard-policy-publication-v1"
                + ".schema.json"), List.of("guardScope", "limits"));
        assertEnvelope(grant, schema("read-only-shadow-sampling-grant-publication-v1"
                + ".schema.json"), List.of("scope", "guardScope", "guardPolicyRef"));
        assertEnvelope(killSwitch, schema("read-only-shadow-kill-switch-publication-v1"
                + ".schema.json"), List.of("scope"));
    }

    @Test
    void protocolBoundsAndCurrentHeadChainSyntaxAreFrozen() throws Exception {
        JsonNode policy = schema(
                "read-only-shadow-guard-policy-publication-v1.schema.json");
        JsonNode grant = schema(
                "read-only-shadow-sampling-grant-publication-v1.schema.json");

        assertThat(policy.at(
                "/$defs/limits/properties/maximumConcurrent/maximum").asInt())
                .isEqualTo(10_000);
        assertThat(policy.at(
                "/$defs/limits/properties/maximumStartsPerWindow/maximum").asInt())
                .isEqualTo(10_000_000);
        assertThat(grant.at(
                "/$defs/material/properties/maximumSamples/maximum").asLong())
                .isEqualTo(1_000_000_000L);
        for (JsonNode schema : schemas()) {
            assertThat(schema.at(
                    "/$defs/material/allOf/0/then/properties/"
                            + "previousPublicationFingerprint/const").asText())
                    .isEmpty();
            assertThat(schema.at("/$defs/canonicalInstant/pattern").asText())
                    .endsWith("Z$");
            assertThat(schema.at(
                    "/$defs/seal/properties/algorithm/const").asText())
                    .isEqualTo("Ed25519");
        }
    }

    @Test
    void authorityProtocolsCannotCarryBusinessOrCredentialPayloads() throws Exception {
        for (String filename : List.of(
                "read-only-shadow-guard-policy-publication-v1.schema.json",
                "read-only-shadow-sampling-grant-publication-v1.schema.json",
                "read-only-shadow-kill-switch-publication-v1.schema.json")) {
            String source = Files.readString(schemaPath(filename));
            for (String forbidden : Set.of(
                    "requestPayload",
                    "responsePayload",
                    "nodeInput",
                    "nodeOutput",
                    "credential",
                    "secret",
                    "token",
                    "password",
                    "stackTrace",
                    "endpointUri")) {
                assertThat(source).doesNotContain("\"" + forbidden + "\"");
            }
        }
    }

    private void assertEnvelope(
            Object publication,
            JsonNode schema,
            List<String> nestedMaterialFields) {
        JsonNode value = mapper.valueToTree(publication);
        assertProperties(value, schema.path("properties"));
        assertProperties(
                value.path("material"),
                schema.at("/$defs/material/properties"));
        assertProperties(value.path("seal"), schema.at("/$defs/seal/properties"));
        for (String field : nestedMaterialFields) {
            String definition = switch (field) {
                case "scope", "guardScope" -> "scope";
                case "limits" -> "limits";
                case "guardPolicyRef" -> "artifactRef";
                default -> throw new IllegalArgumentException("unknown field");
            };
            assertProperties(
                    value.at("/material/" + field),
                    schema.at("/$defs/" + definition + "/properties"));
        }
        for (String pointer : List.of(
                "",
                "/$defs/material",
                "/$defs/seal")) {
            assertStrictRequiredObject(schema.at(pointer));
        }
    }

    private static void assertStrictRequiredObject(JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(schema.path("required")));
    }

    private ReadOnlyShadowGuardPolicyPublication policy() {
        return integrity.sealGuardPolicy(
                new ReadOnlyShadowGuardPolicyPublication.Material(
                        "provider:credit-primary",
                        1,
                        "",
                        guardScope(),
                        new ReadOnlyShadowExecutionGuard.Limits(
                                4,
                                20,
                                Duration.ofMinutes(1),
                                3,
                                Duration.ofMinutes(2)),
                        ISSUED_AT,
                        ISSUED_AT,
                        EXPIRES_AT,
                        "data-governance:shadow"),
                signer);
    }

    private ReadOnlyShadowSamplingGrantPublication grant(
            MirrorArtifactRef policyRef) {
        return integrity.sealSamplingGrant(
                new ReadOnlyShadowSamplingGrantPublication.Material(
                        "grant:loan-risk",
                        1,
                        "",
                        executionScope(),
                        true,
                        100,
                        guardScope(),
                        policyRef,
                        ISSUED_AT,
                        ISSUED_AT,
                        EXPIRES_AT,
                        "data-governance:shadow"),
                signer);
    }

    private ReadOnlyShadowKillSwitchPublication killSwitch() {
        return integrity.sealKillSwitch(
                new ReadOnlyShadowKillSwitchPublication.Material(
                        "switch:loan-risk",
                        1,
                        "",
                        executionScope(),
                        true,
                        ISSUED_AT,
                        ISSUED_AT,
                        EXPIRES_AT,
                        "data-governance:shadow"),
                signer);
    }

    private static CapabilitySnapshot.Scope executionScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "risk", "loan", "staging", "sg");
    }

    private static CapabilitySnapshot.Scope guardScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "shared-provider", "", "staging", "sg");
    }

    private List<JsonNode> schemas() throws Exception {
        return List.of(
                schema("read-only-shadow-guard-policy-publication-v1.schema.json"),
                schema("read-only-shadow-sampling-grant-publication-v1.schema.json"),
                schema("read-only-shadow-kill-switch-publication-v1.schema.json"));
    }

    private JsonNode schema(String filename) throws Exception {
        return mapper.readTree(Files.readString(schemaPath(filename)));
    }

    private static Path schemaPath(String filename) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", filename);
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
}
