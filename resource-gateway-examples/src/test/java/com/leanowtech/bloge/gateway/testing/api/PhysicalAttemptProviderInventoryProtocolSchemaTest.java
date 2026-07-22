package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PhysicalAttemptProviderInventoryProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void inventorySchemaMatchesEnvelopeMaterialBindingAndSignatureWireFields() throws Exception {
        JsonNode schema = schema("physical-attempt-provider-inventory-v1.schema.json");
        JsonNode inventory = objectMapper.valueToTree(inventory());

        assertFields(inventory, schema.at("/$defs/inventory/properties"));
        assertFields(inventory.path("material"), schema.at("/$defs/material/properties"));
        assertFields(inventory.at("/material/bindings/0"),
                schema.at("/$defs/binding/properties"));
        assertFields(inventory.at("/signatures/0"),
                schema.at("/$defs/authoritySignature/properties"));
        assertThat(schema.at("/$defs/inventory/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/material/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/binding/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/inventory/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION);
    }

    @Test
    void aggregateSchemasMatchJavaFieldsAndExcludePrivateResolverIdentity() throws Exception {
        JsonNode descriptorSchema = schema(
                "physical-attempt-provider-inventory-descriptor-v1.schema.json");
        JsonNode cohortSchema = schema(
                "physical-attempt-provider-inventory-cohort-observation-v1.schema.json");
        JsonNode capabilitySchema = schema(
                "physical-attempt-runtime-capability-v1.schema.json");

        assertFields(objectMapper.valueToTree(descriptor()), descriptorSchema.path("properties"));
        assertFields(objectMapper.valueToTree(cohort()), cohortSchema.path("properties"));
        assertFields(objectMapper.valueToTree(ready()), capabilitySchema.path("properties"));
        assertFields(objectMapper.valueToTree(
                        TestSuiteStabilityPhysicalAttemptRuntimeCapability.disabled()),
                capabilitySchema.path("properties"));

        String aggregateSource = descriptorSchema + cohortSchema.toString()
                + capabilitySchema;
        for (String forbidden : List.of("providerId", "deploymentId", "cohortId",
                "keyId", "materialFingerprint", "policyFingerprint", "endpoint",
                "credential", "privateKey", "payload", "exception")) {
            assertThat(aggregateSource).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void publicationSchemaMatchesLifecycleWitnessAndSignatureWireFields() throws Exception {
        JsonNode schema = schema(
                "physical-attempt-provider-inventory-publication-v1.schema.json");
        JsonNode publication = objectMapper.valueToTree(publication());

        assertFields(publication, schema.at("/$defs/publication/properties"));
        assertFields(publication.path("material"), schema.at("/$defs/material/properties"));
        assertFields(publication.path("witness"), schema.at("/$defs/witness/properties"));
        assertFields(publication.at("/witness/material"),
                schema.at("/$defs/witnessMaterial/properties"));
        assertFields(publication.at("/signatures/0"),
                schema.at("/$defs/authoritySignature/properties"));
        assertThat(schema.at("/$defs/publication/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(
                        TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material
                                .SCHEMA_VERSION);
    }

    @Test
    void privateGenerationAndSignedCohortBindingSchemasMatchJavaFields() throws Exception {
        JsonNode generationSchema = schema(
                "physical-attempt-provider-inventory-publication-generation-v1.schema.json");
        JsonNode bindingSchema = schema(
                "physical-attempt-provider-inventory-cohort-binding-v1.schema.json");
        var generation = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation
                        .SCHEMA_VERSION,
                "provider-fleet", 1, "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64), "", "");
        var binding = new
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.CohortBinding(
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.CohortBinding
                        .SCHEMA_VERSION,
                "provider-fleet", "cohort-a", List.of("replica-a", "replica-b"), true,
                1, "sha256:" + "3".repeat(64),
                Instant.parse("2026-07-23T00:00:00Z"));

        assertFields(objectMapper.valueToTree(generation), generationSchema.path("properties"));
        assertFields(objectMapper.valueToTree(binding), bindingSchema.path("properties"));
        assertThat(generationSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(bindingSchema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void capabilityStatusVocabularyExactlyMatchesJavaProtocol() throws Exception {
        JsonNode schema = schema("physical-attempt-runtime-capability-v1.schema.json");

        assertThat(schema.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                        TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                                .values()).map(Enum::name).toArray(String[]::new));
        assertThat(schema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.SCHEMA_VERSION);
        assertThat(schema.at("/allOf/1/then/properties/status/const").asText())
                .isEqualTo("READY");
    }

    @Test
    void physicalInventoryExternalAnchorEntryPointReferencesTheStrictSharedContract()
            throws Exception {
        JsonNode schema = schema(
                "physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json");

        assertThat(schema.path("$ref").asText()).isEqualTo(
                "external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-configuration-v2.schema.json");
        assertThat(schema.path("description").asText())
                .contains("challenge-bound notary quorum")
                .contains("managed receipt trust")
                .contains("complete bootstrap-root chain");
    }

    @Test
    void schemaFilesAreStrictBoundedAndPackagedByTheTestKitResourceRoot() throws Exception {
        for (String name : List.of(
                "physical-attempt-provider-inventory-v1.schema.json",
                "physical-attempt-provider-inventory-publication-v1.schema.json",
                "physical-attempt-provider-inventory-publication-generation-v1.schema.json",
                "physical-attempt-provider-inventory-cohort-binding-v1.schema.json",
                "physical-attempt-provider-inventory-descriptor-v1.schema.json",
                "physical-attempt-provider-inventory-cohort-observation-v1.schema.json",
                "physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json",
                "physical-attempt-runtime-capability-v1.schema.json")) {
            JsonNode schema = schema(name);
            assertThat(schema.path("$schema").asText())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.path("$id").asText()).endsWith(name);
            assertThat(Files.size(schemaPath(name))).isBetween(500L, 25_000L);
        }
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventory inventory() {
        var material = new TestSuiteStabilityPhysicalAttemptProviderInventory.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventory.Material.SCHEMA_VERSION,
                "inventory.example", "inventory-17", 17, "provider-fleet", "cohort-a",
                "bloge.physical-attempt-provider.v1", "sha256:" + "2".repeat(64),
                List.of(binding()), Instant.parse("2026-07-21T23:59:00Z"),
                Instant.parse("2026-07-21T23:59:00Z"),
                Instant.parse("2026-07-23T00:00:00Z"));
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "authority-a", "key-a", "Ed25519",
                Instant.parse("2026-07-22T00:00:00Z"),
                java.util.Base64.getEncoder().encodeToString(new byte[64]));
        return new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                material, "sha256:" + "1".repeat(64), List.of(signature));
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication() {
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory();
        var material = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material
                        .SCHEMA_VERSION,
                "inventory.example", "publication-1", 1, "provider-fleet", "cohort-a",
                inventory.materialFingerprint(), List.of("replica-a", "replica-b"),
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "sha256:" + "2".repeat(64), "",
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-23T00:00:00Z"), "");
        String materialFingerprint = "sha256:" + "4".repeat(64);
        var witnessMaterial = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial
                        .SCHEMA_VERSION,
                "inventory-witness.example", "checkpoint-1", 1,
                materialFingerprint, "", Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-23T00:00:00Z"));
        var witness = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint
                        .SCHEMA_VERSION,
                witnessMaterial, "sha256:" + "5".repeat(64),
                List.of(signature("witness-a", "witness-key-a")));
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.SCHEMA_VERSION,
                inventory, material, materialFingerprint,
                List.of(signature("authority-a", "key-a")), witness);
    }

    private static TestSuiteStabilityServingInventory.AuthoritySignature signature(
            String authorityId, String keyId) {
        return new TestSuiteStabilityServingInventory.AuthoritySignature(
                authorityId, keyId, "Ed25519",
                Instant.parse("2026-07-22T00:00:00Z"),
                java.util.Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventory.Binding binding() {
        return new TestSuiteStabilityPhysicalAttemptProviderInventory.Binding(
                TestSuiteStabilityPhysicalAttemptProviderInventory.Binding.SCHEMA_VERSION,
                "provider-a", "deployment-a", "sha256:" + "3".repeat(64), "key-a",
                List.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                1_000, 60_000);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor
            descriptor() {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor
                        .SCHEMA_VERSION,
                true, true, true, "VERIFIED", 17, 1,
                Map.of("sourceType", "DYNAMIC_SIGNED",
                        "privateMaterialPresent", false,
                        "dynamicInventory", true,
                        "automaticRefresh", true,
                        "signedRevocation", true,
                        "witnessedPublications", true,
                        "durablePublicationFloor", true,
                        "externalNonEquivocation", true,
                        "byzantineQuorumNonEquivocation", true));
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation
            cohort() {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation
                        .SCHEMA_VERSION,
                true, "CONVERGED", 17, "sha256:" + "1".repeat(64), 2, 2, 1,
                Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static TestSuiteStabilityPhysicalAttemptRuntimeCapability ready() {
        return new TestSuiteStabilityPhysicalAttemptRuntimeCapability(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.SCHEMA_VERSION,
                true, true,
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus.READY,
                descriptor(), true, true, true, true, true, true, true, true, 2, 2);
    }

    private JsonNode schema(String name) throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath(name)));
    }

    private static Path schemaPath(String name) {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing", name);
    }

    private static void assertFields(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(Map.Entry::getKey).toList());
    }
}
