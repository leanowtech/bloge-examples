package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationProtocolSchemaTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schemas",
            "resource-gateway-testing",
            "external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-publication-v1.schema.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaIsStrictAndMatchesEveryPublicationRecordComponent() throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(SCHEMA));

        assertThat(root.path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertStrictProperties(root.path("$defs").path("publication"), Set.of(
                "schemaVersion", "inventory", "material", "materialFingerprint",
                "signatures", "witness"));
        assertStrictProperties(root.path("$defs").path("material"), Set.of(
                "schemaVersion", "trustDomain", "publicationId", "deploymentScopeId",
                "fleetId", "sequence", "inventoryMaterialFingerprint", "state",
                "policyFingerprint", "previousPublicationFingerprint", "issuedAt",
                "notBefore", "expiresAt", "reasonCode"));
        assertStrictProperties(root.path("$defs").path("witness"), Set.of(
                "schemaVersion", "material", "materialFingerprint", "signatures"));
        assertStrictProperties(root.path("$defs").path("witnessMaterial"), Set.of(
                "schemaVersion", "witnessDomain", "checkpointId", "deploymentScopeId",
                "fleetId", "sequence", "publicationMaterialFingerprint",
                "previousWitnessFingerprint", "issuedAt", "notBefore", "expiresAt"));
    }

    @Test
    void schemaPinsExternalInventoryRefStateAndCanonicalScalarFormats() throws Exception {
        JsonNode defs = objectMapper.readTree(Files.readString(SCHEMA)).path("$defs");

        assertThat(defs.path("publication").path("properties").path("inventory")
                .path("$ref").asText()).isEqualTo(
                "external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json");
        assertThat(defs.path("material").path("properties").path("state")
                .path("enum")).extracting(JsonNode::asText)
                .containsExactly("ACTIVE", "REVOKED");
        assertThat(defs.path("material").path("properties").path("issuedAt")
                .path("format").asText()).isEqualTo("date-time");
        assertThat(defs.path("authoritySignature").path("properties")
                .path("algorithm").path("const").asText()).isEqualTo("Ed25519");
        Pattern fingerprint = Pattern.compile(defs.path("fingerprint")
                .path("pattern").asText());
        assertThat(fingerprint.matcher("sha256:" + "a".repeat(64)).matches()).isTrue();
        assertThat(fingerprint.matcher("sha256:" + "A".repeat(64)).matches()).isFalse();
    }

    @Test
    void schemaCannotCarryPrivateRuntimeOrCredentialMaterial() throws Exception {
        String schema = Files.readString(SCHEMA);

        assertThat(schema).doesNotContain(
                "privateKey", "credential", "secretValue", "accessToken",
                "serviceObject", "laneResolver", "providerPayload", "runtimeObject");
    }

    private static void assertStrictProperties(JsonNode definition, Set<String> expected) {
        assertThat(definition.path("type").asText()).isEqualTo("object");
        assertThat(definition.path("additionalProperties").asBoolean()).isFalse();
        assertThat(definition.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(definition.path("properties").propertyStream()
                .map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
    }
}
