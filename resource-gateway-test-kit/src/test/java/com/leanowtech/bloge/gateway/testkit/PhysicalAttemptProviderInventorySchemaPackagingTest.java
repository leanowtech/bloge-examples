package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhysicalAttemptProviderInventorySchemaPackagingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testKitPackagesEveryPhysicalAttemptInventoryAndCapabilitySchema() throws Exception {
        for (String name : List.of(
                "physical-attempt-provider-inventory-v1.schema.json",
                "physical-attempt-provider-inventory-publication-v1.schema.json",
                "physical-attempt-provider-inventory-trust-root-publication-v1.schema.json",
                "physical-attempt-provider-inventory-publication-generation-v1.schema.json",
                "physical-attempt-provider-inventory-cohort-binding-v1.schema.json",
                "physical-attempt-provider-inventory-descriptor-v1.schema.json",
                "physical-attempt-provider-inventory-cohort-observation-v1.schema.json",
                "physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json",
                "physical-attempt-runtime-capability-v1.schema.json")) {
            String resource = "/schemas/resource-gateway-testing/" + name;
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                JsonNode schema = objectMapper.readTree(input);
                assertThat(schema.path("$schema").asText())
                        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
                assertThat(schema.path("$id").asText()).endsWith(name);
            }
        }
    }
}
