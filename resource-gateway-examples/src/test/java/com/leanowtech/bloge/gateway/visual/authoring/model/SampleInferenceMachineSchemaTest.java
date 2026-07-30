package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleSchemaInferencer;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInferenceMachineSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void requestSchemaRequiresEphemeralBoundedSamples() throws Exception {
        Map<String, Object> valid = mapper.readValue("""
                {
                  "schemaVersion": "bloge.visualSampleInferenceRequest.v1",
                  "target": {
                    "assetKind": "OPERATOR",
                    "assetRef": "support:classify",
                    "portDirection": "INPUT",
                    "portName": "ticket"
                  },
                  "samples": [{"id":"one"}],
                  "options": {
                    "suggestEnums": true,
                    "suggestFormats": true,
                    "persistPayload": false
                  },
                  "idempotencyKey": "request-1"
                }
                """, Map.class);
        Map<String, Object> unsafePersistence = new java.util.LinkedHashMap<>(valid);
        unsafePersistence.put("options", Map.of(
                "suggestEnums", true,
                "suggestFormats", true,
                "persistPayload", true
        ));

        assertThat(validate(requestSchema(), valid)).isEmpty();
        assertThat(validate(requestSchema(), unsafePersistence))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("persistPayload"));
    }

    @Test
    void resultSchemaAcceptsTheRealPayloadFreeInferenceProjection() throws Exception {
        JsonNode first = mapper.readTree("""
                {"id":"one","state":"active","privateToken":"value-A"}
                """);
        JsonNode second = mapper.readTree("""
                {"id":"two","state":"paused","privateToken":"value-B"}
                """);
        SampleInferenceRequest request = new SampleInferenceRequest(
                SampleInferenceRequest.SCHEMA_VERSION,
                new SampleInferenceRequest.Target(
                        "OPERATOR", "support:classify", "INPUT", "ticket"),
                List.of(first, second),
                SampleInferenceRequest.Options.defaults(),
                "request-1"
        );
        SampleInferenceResult result = new SampleSchemaInferencer(mapper)
                .infer("support-draft", 3, request);

        assertThat(validate(
                resultSchema(),
                mapper.convertValue(result, Object.class)
        )).isEmpty();
        assertThat(mapper.writeValueAsString(result))
                .doesNotContain("value-A")
                .doesNotContain("value-B");
    }

    @SuppressWarnings("unchecked")
    private List<VisualDiagnostic> validate(Path schemaPath, Object value) throws Exception {
        Map<String, Object> schema = mapper.readValue(Files.readString(schemaPath), Map.class);
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                value,
                "/inference"
        );
    }

    private static Path requestSchema() {
        return Path.of("..", "docs", "schemas",
                "bloge-visual-sample-inference-request-v1.schema.json");
    }

    private static Path resultSchema() {
        return Path.of("..", "docs", "schemas",
                "bloge-visual-sample-inference-result-v1.schema.json");
    }
}
