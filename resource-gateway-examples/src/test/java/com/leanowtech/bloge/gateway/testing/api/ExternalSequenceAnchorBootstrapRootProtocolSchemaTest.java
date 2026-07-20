package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesGenesisTransitionAndBundleRecords() throws Exception {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        var genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                "stability-fleet", "notary-bootstrap-roots", "bootstrap.example",
                1, 0, List.of(key), "sha256:" + "a".repeat(64));
        String genesisFingerprint = genesis.materialFingerprint(objectMapper);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1, genesisFingerprint,
                "stability-fleet", "bootstrap.example", 1, 0, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(signature), List.of(signature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesisFingerprint, List.of(transition), materialFingerprint);
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertProperties(objectMapper.valueToTree(bundle),
                schema.at("/$defs/bundle/properties"));
        assertProperties(objectMapper.valueToTree(transition),
                schema.at("/$defs/transition/properties"));
        assertProperties(objectMapper.valueToTree(material),
                schema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(genesis),
                schema.at("/$defs/genesis/properties"));
        assertProperties(objectMapper.valueToTree(key),
                schema.at("/$defs/rootKeyMaterial/properties"));
        assertThat(schema.at("/$defs/bundle/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/bundle/properties/transitions/maxItems").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootBundle.MAXIMUM_TRANSITIONS);
        assertThat(schema.at("/$defs/genesis/properties/signatureThreshold/maximum").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootGenesis
                        .MAXIMUM_SIGNATURE_THRESHOLD);
        assertThat(schema.at(
                "/$defs/transition/properties/authorizingRootSignatures/maxItems").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootTransition
                        .MAXIMUM_SIGNATURES_PER_ROLE);
        assertThat(List.of("bundle", "transition", "material", "genesis",
                "rootKeyMaterial")).allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
    }

    @Test
    void schemaContainsOnlyPublicCeremonyMaterial() throws Exception {
        String schema = Files.readString(schemaPath());

        for (String forbidden : List.of("privateKey", "credential", "payload", "fixture",
                "nodeOutput", "endpoint", "etag", "requestFingerprint")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void deploymentGenesisHasAStandaloneSchemaEntryPoint() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(genesisSchemaPath()));

        assertThat(schema.path("$ref").asText()).isEqualTo(
                "external-sequence-anchor-bootstrap-root-bundle-v1.schema.json#/$defs/genesis");
        assertThat(schema.path("$id").asText())
                .endsWith("external-sequence-anchor-bootstrap-root-genesis-v1.schema.json");
    }

    @Test
    void strictCeremonySchemaMatchesProducerAndOpaqueSignerRecords() throws Exception {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        var genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                "stability-fleet", "notary-bootstrap-roots", "bootstrap.example",
                1, 0, List.of(key), "sha256:" + "a".repeat(64));
        String genesisFingerprint = genesis.materialFingerprint(objectMapper);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1, genesisFingerprint,
                "stability-fleet", "bootstrap.example", 1, 0, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        String requestId = "sha256:" + "c".repeat(64);
        String signatureValue = Base64.getEncoder().encodeToString(new byte[64]);
        var descriptor = new ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor.SCHEMA_VERSION,
                "root-a", "root-key-a", "Ed25519", publicKey);
        var signRequest = new ExternalSequenceAnchorBootstrapRootSigningAuthority
                .SignatureRequest(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest
                        .SCHEMA_VERSION,
                requestId, "ceremony-a",
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.AUTHORIZING_ROOT,
                "notary-bootstrap-roots", 1, "root-a", "root-key-a",
                materialFingerprint, now);
        var signResponse = new ExternalSequenceAnchorBootstrapRootSigningAuthority
                .SignatureResponse(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse
                        .SCHEMA_VERSION,
                requestId, "root-a", "root-key-a", "Ed25519",
                materialFingerprint, now, signatureValue);
        var rotationRequest = new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                "ceremony-a", genesisFingerprint, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        var authoritySignature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", now, signatureValue);
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(authoritySignature),
                List.of(authoritySignature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesisFingerprint, List.of(transition), materialFingerprint);
        var attempts = List.of(
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                .AUTHORIZING_ROOT,
                        "root-a", "root-key-a",
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.SIGNED),
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.INCOMING_ROOT,
                        "root-a", "root-key-a",
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.SIGNED));
        var outcome = new ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome
                        .SCHEMA_VERSION,
                "ceremony-a", bundle, attempts);
        JsonNode schema = objectMapper.readTree(Files.readString(ceremonySchemaPath()));

        assertProperties(objectMapper.valueToTree(descriptor),
                schema.at("/$defs/signerDescriptor/properties"));
        assertProperties(objectMapper.valueToTree(signRequest),
                schema.at("/$defs/signatureRequest/properties"));
        assertProperties(objectMapper.valueToTree(signResponse),
                schema.at("/$defs/signatureResponse/properties"));
        assertProperties(objectMapper.valueToTree(rotationRequest),
                schema.at("/$defs/rotationRequest/properties"));
        assertProperties(objectMapper.valueToTree(attempts.getFirst()),
                schema.at("/$defs/signingAttempt/properties"));
        assertProperties(objectMapper.valueToTree(outcome),
                schema.at("/$defs/ceremonyOutcome/properties"));
        assertThat(List.of("signerDescriptor", "signatureRequest", "signatureResponse",
                "rotationRequest", "signingAttempt", "ceremonyOutcome"))
                .allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
        assertThat(schema.at("/$defs/ceremonyOutcome/properties/signingAttempts/maxItems")
                .asInt()).isEqualTo(2 * ExternalSequenceAnchorBootstrapRootTransition
                .MAXIMUM_SIGNATURES_PER_ROLE);
        assertThat(schema.at("/$defs/ceremonyOutcome/properties/signingAttempts/uniqueItems")
                .asBoolean()).isTrue();
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .CeremonyOutcome(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome
                        .SCHEMA_VERSION,
                "ceremony-a", bundle,
                List.of(attempts.getFirst(), attempts.getFirst(), attempts.getLast())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore
                .ExpectedBinding(
                "stability-fleet", "notary-bootstrap-roots", "bootstrap.example",
                ExternalSequenceAnchorBootstrapRootGenesis.MAXIMUM_SIGNATURE_THRESHOLD + 1,
                10, java.time.Duration.ofDays(30), java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30), 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ceremonySchemaContainsNoPrivateCustodyOrProviderDiagnostics() throws Exception {
        String schema = Files.readString(ceremonySchemaPath());

        for (String forbidden : List.of("privateKey", "credential", "secret", "endpoint",
                "providerName", "errorMessage", "exception")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-bundle-v1.schema.json");
    }

    private static Path genesisSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-genesis-v1.schema.json");
    }

    private static Path ceremonySchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-ceremony-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value)).containsExactlyInAnyOrderElementsOf(
                fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
