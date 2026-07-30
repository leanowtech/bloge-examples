package com.leanowtech.bloge.gateway.visual.authoring.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleSchemaInferencerTest {

    private ObjectMapper mapper;
    private SampleSchemaInferencer inferencer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        inferencer = new SampleSchemaInferencer(mapper);
    }

    @Test
    void rejectsMissingSchemaVersionInsteadOfApplyingAnImplicitDefault() throws Exception {
        SampleInferenceRequest request = request(List.of(
                mapper.readTree("{\"id\":\"t-1\"}")
        ), SampleInferenceRequest.Options.defaults());
        request = new SampleInferenceRequest(
                null,
                request.target(),
                request.samples(),
                request.options(),
                "missing-version"
        );

        SampleInferenceRequest missingVersion = request;
        assertThatThrownBy(() -> inferencer.infer("support-library", 3, missingVersion))
                .isInstanceOfSatisfying(SampleInferenceRejectedException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo("RG.AUTHORING.INFERENCE_SCHEMA_UNSUPPORTED");
                    assertThat(exception.authoringPath()).isEqualTo("/schemaVersion");
                });
    }

    @Test
    void rejectsMissingOptionFieldsInsteadOfApplyingPrimitiveDefaults() throws Exception {
        SampleInferenceRequest request = new SampleInferenceRequest(
                SampleInferenceRequest.SCHEMA_VERSION,
                new SampleInferenceRequest.Target(
                        "OPERATOR", "support:classify", "INPUT", "ticket"),
                List.of(mapper.readTree("{\"id\":\"t-1\"}")),
                new SampleInferenceRequest.Options(true, null, false),
                "missing-option"
        );

        assertThatThrownBy(() -> inferencer.infer("support-library", 3, request))
                .isInstanceOfSatisfying(SampleInferenceRejectedException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo("RG.AUTHORING.INFERENCE_OPTIONS_INVALID");
                    assertThat(exception.authoringPath()).isEqualTo("/options");
                });
    }

    @Test
    void infersExplainableOpenCandidateWithoutEchoingSensitivePayloads() throws Exception {
        SampleInferenceResult result = infer("""
                {"id":"a","score":1,"nickname":null,"status":"open",
                 "created":"2026-07-01","password":"top-value-A","tags":[]}
                """, """
                {"id":"b","score":1.5,"status":"closed",
                 "created":"2026-07-02","password":"top-value-B","tags":["x"]}
                """, """
                {"id":"c","score":2,"status":"open",
                 "created":"2026-07-03","password":"top-value-C","tags":["y"]}
                """);

        assertThat(result.payloadPersisted()).isFalse();
        assertThat(result.candidate()).isEqualTo(mapper.readTree("""
                {
                  "fields": {
                    "created": "string",
                    "id": "string",
                    "nickname?": "unknown",
                    "password": "string",
                    "score": "number",
                    "status": "string",
                    "tags": "string[]"
                  },
                  "additionalProperties": true
                }
                """));
        assertThat(observation(result, "/fields/score"))
                .satisfies(observation -> {
                    assertThat(observation.suggestedType()).isEqualTo("number");
                    assertThat(observation.widenReasons())
                            .containsExactly("integer widened to number");
                });
        assertThat(observation(result, "/fields/status").enumCandidates())
                .containsExactly("closed", "open");
        assertThat(observation(result, "/fields/password"))
                .satisfies(observation -> {
                    assertThat(observation.sensitive()).isTrue();
                    assertThat(observation.enumCandidates()).isEmpty();
                });
        assertThat(observation(result, "/fields/created").formatCandidate())
                .isEqualTo("date");
        assertThat(result.confirmationRequests())
                .extracting(SampleInferenceResult.InferenceConfirmation::code)
                .contains(
                        "RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED",
                        "RG.AUTHORING.INFERENCE_FORMAT_CONFIRMATION_REQUIRED",
                        "RG.AUTHORING.INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED",
                        "RG.AUTHORING.INFERENCE_SENSITIVE_HANDLING_REQUIRED"
                );
        String encoded = mapper.writeValueAsString(result);
        assertThat(encoded)
                .doesNotContain("top-value-A")
                .doesNotContain("top-value-B")
                .doesNotContain("top-value-C")
                .doesNotContain("\"samples\"");
    }

    @Test
    void isIndependentOfSampleAndObjectFieldOrder() throws Exception {
        SampleInferenceResult first = infer(
                "{\"b\":2,\"a\":\"x\"}",
                "{\"a\":\"y\",\"b\":3}"
        );
        SampleInferenceResult reversed = infer(
                "{\"b\":3,\"a\":\"y\"}",
                "{\"a\":\"x\",\"b\":2}"
        );

        assertThat(reversed.evidenceFingerprint()).isEqualTo(first.evidenceFingerprint());
        assertThat(reversed.candidate()).isEqualTo(first.candidate());
        assertThat(reversed.observations()).isEqualTo(first.observations());
        assertThat(reversed.confirmationRequests()).isEqualTo(first.confirmationRequests());
    }

    @Test
    void countsSemanticallyEqualObjectsOnceRegardlessOfFieldOrder() throws Exception {
        SampleInferenceResult result = infer(
                "{\"profile\":{\"first\":\"Ada\",\"last\":\"Lovelace\"}}",
                "{\"profile\":{\"last\":\"Lovelace\",\"first\":\"Ada\"}}"
        );

        assertThat(observation(result, "/fields/profile").distinctCount()).isEqualTo(1);
    }

    @Test
    void keepsEnumAndClosedObjectAsSuggestionsUntilExplicitConfirmation() throws Exception {
        SampleInferenceResult result = infer(
                "{\"state\":\"draft\"}",
                "{\"state\":\"active\"}",
                "{\"state\":\"retired\"}"
        );

        assertThat(result.candidate().path("additionalProperties").asBoolean()).isTrue();
        assertThat(result.candidate().path("fields").path("state").asText()).isEqualTo("string");
        assertThat(observation(result, "/fields/state").enumCandidates())
                .containsExactly("active", "draft", "retired");
        assertThat(result.confirmationRequests())
                .anySatisfy(confirmation -> {
                    assertThat(confirmation.code())
                            .isEqualTo("RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED");
                    assertThat(confirmation.recommendedValue()).isEqualTo("KEEP_STRING");
                })
                .anySatisfy(confirmation -> {
                    assertThat(confirmation.code())
                            .isEqualTo("RG.AUTHORING.INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED");
                    assertThat(confirmation.recommendedValue()).isEqualTo("OPEN");
                });
    }

    @Test
    void makesTypeConflictsUnknownAndBlocking() throws Exception {
        SampleInferenceResult result = infer(
                "{\"value\":7}",
                "{\"value\":{\"nested\":true}}"
        );

        assertThat(result.candidate().path("fields").path("value").asText())
                .isEqualTo("unknown");
        assertThat(observation(result, "/fields/value").conflictTypes())
                .containsExactly("integer", "object");
        assertThat(result.confirmationRequests())
                .anySatisfy(confirmation -> {
                    assertThat(confirmation.code())
                            .isEqualTo("RG.AUTHORING.INFERENCE_TYPE_CONFLICT_CONFIRMATION_REQUIRED");
                    assertThat(confirmation.blocking()).isTrue();
                });
        assertThat(result.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .contains("RG.AUTHORING.INFERENCE_TYPE_CONFLICT");
    }

    @Test
    void preservesOptionalAndNullableAsSeparateObservedFacts() throws Exception {
        SampleInferenceResult result = infer(
                "{\"value\":\"x\"}",
                "{\"value\":null}",
                "{}"
        );

        assertThat(result.candidate().path("fields").path("value?").asText())
                .isEqualTo("string?");
        assertThat(observation(result, "/fields/value?"))
                .satisfies(observation -> {
                    assertThat(observation.requiredCandidate()).isFalse();
                    assertThat(observation.nullableCandidate()).isTrue();
                    assertThat(observation.presenceCount()).isEqualTo(2);
                    assertThat(observation.nullCount()).isEqualTo(1);
                    assertThat(observation.sampleCount()).isEqualTo(3);
                });
        assertThat(result.confirmationRequests())
                .extracting(SampleInferenceResult.InferenceConfirmation::code)
                .contains(
                        "RG.AUTHORING.INFERENCE_PRESENCE_CONFIRMATION_REQUIRED",
                        "RG.AUTHORING.INFERENCE_NULLABILITY_CONFIRMATION_REQUIRED"
                );
    }

    @Test
    void keepsEmptyArrayItemsUnknown() throws Exception {
        SampleInferenceResult result = infer("{\"items\":[]}", "{\"items\":[]}");

        assertThat(result.candidate().path("fields").path("items").asText())
                .isEqualTo("unknown[]");
        assertThat(result.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .contains("RG.AUTHORING.INFERENCE_EMPTY_ARRAY");
    }

    @Test
    void rejectsUnsafeOptionsAndResourceLimitBreaches() throws Exception {
        SampleInferenceRequest persist = request(
                List.of(mapper.readTree("{\"id\":\"a\"}")),
                new SampleInferenceRequest.Options(true, true, true)
        );
        assertThatThrownBy(() -> inferencer.infer("draft", 1, persist))
                .isInstanceOfSatisfying(SampleInferenceRejectedException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("RG.AUTHORING.INFERENCE_PAYLOAD_PERSISTENCE_UNSUPPORTED"));

        List<JsonNode> tooMany = new ArrayList<>();
        for (int index = 0; index <= SampleSchemaInferencer.MAXIMUM_SAMPLES; index++) {
            tooMany.add(mapper.getNodeFactory().numberNode(index));
        }
        assertThatThrownBy(() -> inferencer.infer(
                "draft",
                1,
                request(tooMany, SampleInferenceRequest.Options.defaults())
        )).isInstanceOfSatisfying(SampleInferenceRejectedException.class, exception -> {
            assertThat(exception.code())
                    .isEqualTo("RG.AUTHORING.INFERENCE_SAMPLE_LIMIT_EXCEEDED");
            assertThat(exception.status()).isEqualTo(413);
        });
    }

    private SampleInferenceResult infer(String... samples) throws Exception {
        List<JsonNode> values = new ArrayList<>();
        for (String sample : samples) {
            values.add(mapper.readTree(sample));
        }
        return inferencer.infer(
                "support-draft",
                3,
                request(values, SampleInferenceRequest.Options.defaults())
        );
    }

    private SampleInferenceRequest request(List<JsonNode> samples,
                                           SampleInferenceRequest.Options options) {
        return new SampleInferenceRequest(
                SampleInferenceRequest.SCHEMA_VERSION,
                new SampleInferenceRequest.Target(
                        "OPERATOR",
                        "support:classify",
                        "INPUT",
                        "ticket"
                ),
                samples,
                options,
                "infer-support-ticket"
        );
    }

    private static SampleInferenceResult.FieldObservation observation(
            SampleInferenceResult result,
            String pathSuffix) {
        return result.observations().stream()
                .filter(observation -> observation.authoringPath().endsWith(pathSuffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing observation ending with " + pathSuffix));
    }
}
