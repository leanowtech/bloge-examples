package com.leanowtech.bloge.gateway.visual.authoring.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleInferenceCandidateApplierTest {

    private ObjectMapper mapper;
    private SampleSchemaInferencer inferencer;
    private SampleInferenceCandidateApplier applier;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        inferencer = new SampleSchemaInferencer(mapper);
        applier = new SampleInferenceCandidateApplier();
    }

    @Test
    void appliesNestedDecisionsDeepestFirstAndRetainsAnAuditDecisionForEach() throws Exception {
        SampleInferenceResult result = infer(
                """
                {"profile":{"state":"active","createdAt":"2026-07-01"},
                 "accessToken":"secret-a"}
                """,
                """
                {"profile":{"state":"retired","createdAt":"2026-07-02"},
                 "accessToken":"secret-b"}
                """,
                """
                {"profile":{"state":"paused","createdAt":"2026-07-03"},
                 "accessToken":"secret-c"}
                """
        );
        Map<String, String> overrides = Map.of(
                "RG.AUTHORING.INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED|/profile", "CLOSED",
                "RG.AUTHORING.INFERENCE_PRESENCE_CONFIRMATION_REQUIRED|/profile", "OPTIONAL",
                "RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED|/state", "DECLARE_ENUM",
                "RG.AUTHORING.INFERENCE_FORMAT_CONFIRMATION_REQUIRED|/createdAt", "DATE",
                "RG.AUTHORING.INFERENCE_SENSITIVE_HANDLING_REQUIRED|/accessToken", "REMOVE_FIELD"
        );

        var applied = applier.apply(
                result,
                decisions(result, overrides),
                "alice"
        );

        assertThat(applied.removePort()).isFalse();
        assertThat(applied.portName()).isEqualTo("ticket");
        assertThat(applied.candidate().path("fields").has("accessToken")).isFalse();
        JsonNode profile = applied.candidate().path("fields").path("profile?");
        assertThat(profile.path("additionalProperties").asBoolean()).isFalse();
        assertThat(profile.path("fields").path("createdAt").asText()).isEqualTo("date");
        assertThat(profile.path("fields").path("state").path("enum"))
                .as("candidate: %s", applied.candidate())
                .isEqualTo(mapper.readTree("[\"active\",\"paused\",\"retired\"]"));
        assertThat(applied.confirmations()).hasSize(result.confirmationRequests().size());
        assertThat(applied.confirmations())
                .allSatisfy(confirmation -> {
                    assertThat(confirmation.evidenceFingerprint())
                            .isEqualTo(result.evidenceFingerprint());
                    assertThat(confirmation.decidedBy()).isEqualTo("alice");
                });
    }

    @Test
    void rejectsMissingUnknownAndStillUnresolvedDecisions() throws Exception {
        SampleInferenceResult normal = infer("{\"state\":\"active\"}", "{\"state\":\"retired\"}");
        List<SampleInferenceApplyRequest.Decision> missing =
                decisions(normal, Map.of()).subList(1, normal.confirmationRequests().size());
        assertRejected(
                () -> applier.apply(normal, missing, "alice"),
                "RG.AUTHORING.INFERENCE_CONFIRMATIONS_INCOMPLETE"
        );

        List<SampleInferenceApplyRequest.Decision> invalid =
                new ArrayList<>(decisions(normal, Map.of()));
        SampleInferenceResult.InferenceConfirmation first =
                normal.confirmationRequests().getFirst();
        invalid.set(0, new SampleInferenceApplyRequest.Decision(
                first.confirmationId(), "NOT_ALLOWED"));
        assertRejected(
                () -> applier.apply(normal, invalid, "alice"),
                "RG.AUTHORING.INFERENCE_DECISION_INVALID"
        );

        SampleInferenceResult conflict = infer("{\"value\":7}", "{\"value\":{\"x\":1}}");
        assertRejected(
                () -> applier.apply(conflict, decisions(conflict, Map.of()), "alice"),
                "RG.AUTHORING.INFERENCE_REVIEW_REQUIRED"
        );
        assertThat(applier.apply(
                conflict,
                decisions(conflict, Map.of(
                        "RG.AUTHORING.INFERENCE_TYPE_CONFLICT_CONFIRMATION_REQUIRED|/value",
                        "KEEP_UNKNOWN"
                )),
                "alice"
        ).candidate().path("fields").path("value").asText()).isEqualTo("unknown");
    }

    private SampleInferenceResult infer(String... values) throws Exception {
        List<JsonNode> samples = new ArrayList<>();
        for (String value : values) {
            samples.add(mapper.readTree(value));
        }
        return inferencer.infer(
                "support-draft",
                4,
                new SampleInferenceRequest(
                        SampleInferenceRequest.SCHEMA_VERSION,
                        new SampleInferenceRequest.Target(
                                "OPERATOR", "support:classify", "INPUT", "ticket"),
                        samples,
                        SampleInferenceRequest.Options.defaults(),
                        "candidate-applier-test"
                )
        );
    }

    private static List<SampleInferenceApplyRequest.Decision> decisions(
            SampleInferenceResult result,
            Map<String, String> overrides) {
        return result.confirmationRequests().stream()
                .map(confirmation -> new SampleInferenceApplyRequest.Decision(
                        confirmation.confirmationId(),
                        override(confirmation, overrides)
                ))
                .toList();
    }

    private static String override(
            SampleInferenceResult.InferenceConfirmation confirmation,
            Map<String, String> overrides) {
        return overrides.entrySet().stream()
                .filter(entry -> {
                    String[] selector = entry.getKey().split("\\|", 2);
                    return confirmation.code().equals(selector[0])
                            && confirmation.authoringPath().endsWith(selector[1]);
                })
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(confirmation.recommendedValue());
    }

    private static void assertRejected(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        SampleInferenceRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code)
                );
    }
}
