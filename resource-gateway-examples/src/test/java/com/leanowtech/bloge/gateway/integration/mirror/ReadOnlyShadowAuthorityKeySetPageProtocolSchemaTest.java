package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityKeySetPageProtocolSchemaTest {
    private static final String SCHEMA =
            "read-only-shadow-authority-key-set-page-v1.schema.json";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemaExactlyMatchesAnEmptyGenesisCursorPage() throws Exception {
        var page = new ReadOnlyShadowAuthorityKeySetPage(
                "", Instant.parse("2026-07-26T10:30:00Z"),
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-a", "project-a", "staging", "sg"),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                "data-governance:shadow", "shadow-sampling-keys:staging",
                0, "", 0, 0, "", null, false, List.of());
        JsonNode value = mapper.valueToTree(page);
        JsonNode schema = mapper.readTree(Files.readString(schemaPath()));

        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(fieldNames(schema.path("properties")));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(textValues(schema.path("required")));
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.at("/properties/publications/maxItems").asInt()).isEqualTo(128);
        assertThat(schema.at("/properties/publications/items/$ref").asText())
                .isEqualTo("read-only-shadow-authority-key-set-publication-v1.schema.json");
    }

    private static Path schemaPath() {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", SCHEMA);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", SCHEMA);
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
