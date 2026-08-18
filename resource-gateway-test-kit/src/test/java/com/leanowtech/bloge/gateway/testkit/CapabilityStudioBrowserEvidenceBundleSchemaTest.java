package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserEvidenceBundleSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void packagesStrictBrowserEvidenceBundleManifestSchema() throws Exception {
        try (var input = getClass().getResourceAsStream(
                CapabilityStudioSchemaSupport.BROWSER_EVIDENCE_BUNDLE_MANIFEST_RESOURCE)) {
            assertThat(input).isNotNull();
            var schema = JSON.readTree(input);
            assertThat(schema.path("$schema").asText())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.path("$id").asText())
                    .endsWith("browser-evidence-bundle-manifest-v1.schema.json");
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("properties").path("expectedEntryCount").path("const").asInt())
                    .isEqualTo(438);
            assertThat(schema.path("properties").path("persistedEntryCount").path("const").asInt())
                    .isEqualTo(438);
            assertThat(schema.path("properties").path("entries").path("minItems").asInt())
                    .isEqualTo(438);
            assertThat(schema.path("properties").path("entries").path("maxItems").asInt())
                    .isEqualTo(438);
            assertThat(schema.path("$defs").path("entry").path("additionalProperties").asBoolean())
                    .isFalse();
        }
    }
}
