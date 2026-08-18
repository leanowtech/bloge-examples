package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioGovernedRunEvidenceSchemaPackagingTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void packagesTheGovernedRunEvidenceSchemaFromTheCapabilityStudioDocsResource() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                CapabilityStudioSchemaSupport.GOVERNED_RUN_EVIDENCE_RESOURCE)) {
            assertThat(input).isNotNull();
            JsonNode schema = objectMapper.readTree(input);
            assertThat(schema.path("$schema").asText())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.path("$id").asText())
                    .endsWith("capability-studio-governed-run-evidence-v1.schema.json");
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("properties").path("dataLens").isMissingNode()).isFalse();
            assertThat(schema.path("$defs").path("traceIdentifier").path("maxLength").asInt())
                    .isEqualTo(256);
            assertThat(schema.path("$defs").path("edge").path("properties").path("edgeId")
                    .path("$ref").asText()).isEqualTo("#/$defs/traceIdentifier");
        }
    }
}
