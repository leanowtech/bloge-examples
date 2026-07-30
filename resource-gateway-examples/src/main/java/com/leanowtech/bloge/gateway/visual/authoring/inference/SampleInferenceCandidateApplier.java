package com.leanowtech.bloge.gateway.visual.authoring.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringConfirmation;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies only explicit, allowed inference decisions to a conservative candidate.
 */
public final class SampleInferenceCandidateApplier {

    private static final String PRESENCE =
            "RG.AUTHORING.INFERENCE_PRESENCE_CONFIRMATION_REQUIRED";
    private static final String NULLABILITY =
            "RG.AUTHORING.INFERENCE_NULLABILITY_CONFIRMATION_REQUIRED";
    private static final String FORMAT =
            "RG.AUTHORING.INFERENCE_FORMAT_CONFIRMATION_REQUIRED";
    private static final String ENUM =
            "RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED";
    private static final String OBJECT_CLOSURE =
            "RG.AUTHORING.INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED";
    private static final String TYPE_CONFLICT =
            "RG.AUTHORING.INFERENCE_TYPE_CONFLICT_CONFIRMATION_REQUIRED";
    private static final String SENSITIVE =
            "RG.AUTHORING.INFERENCE_SENSITIVE_HANDLING_REQUIRED";

    public ApplyResult apply(SampleInferenceResult result,
                             List<SampleInferenceApplyRequest.Decision> decisions,
                             String actor) {
        if (result == null || result.candidate() == null) {
            reject(
                    "RG.AUTHORING.INFERENCE_RESULT_REQUIRED",
                    "A complete inference result is required before applying decisions.",
                    400,
                    "/"
            );
        }
        Map<String, SampleInferenceApplyRequest.Decision> supplied = supplied(decisions);
        Map<String, SampleInferenceResult.InferenceConfirmation> expected = new LinkedHashMap<>();
        result.confirmationRequests().forEach(confirmation ->
                expected.put(confirmation.confirmationId(), confirmation));
        if (!supplied.keySet().equals(expected.keySet())) {
            reject(
                    "RG.AUTHORING.INFERENCE_CONFIRMATIONS_INCOMPLETE",
                    "Every current inference confirmation must have exactly one decision.",
                    422,
                    "/decisions"
            );
        }

        Map<String, SampleInferenceResult.FieldObservation> observations = new LinkedHashMap<>();
        result.observations().forEach(observation ->
                observations.put(observation.factId(), observation));
        List<ResolvedDecision> resolved = new ArrayList<>();
        List<AuthoringConfirmation> retained = new ArrayList<>();
        for (SampleInferenceResult.InferenceConfirmation confirmation
                : result.confirmationRequests()) {
            SampleInferenceApplyRequest.Decision suppliedDecision =
                    supplied.get(confirmation.confirmationId());
            String value = suppliedDecision.value();
            if (!confirmation.allowedValues().contains(value)) {
                reject(
                        "RG.AUTHORING.INFERENCE_DECISION_INVALID",
                        "Decision is not allowed for the referenced confirmation.",
                        422,
                        "/decisions"
                );
            }
            if ("REVIEW_SAMPLES".equals(value)) {
                reject(
                        "RG.AUTHORING.INFERENCE_REVIEW_REQUIRED",
                        "Resolve conflicting samples before applying the candidate.",
                        422,
                        confirmation.authoringPath()
                );
            }
            resolved.add(new ResolvedDecision(
                    confirmation,
                    value,
                    observations.get(confirmation.factId())
            ));
            retained.add(new AuthoringConfirmation(
                    confirmation.confirmationId(),
                    result.evidenceFingerprint(),
                    confirmation.factId(),
                    confirmation.code(),
                    confirmation.authoringPath(),
                    value,
                    confirmation.blocking(),
                    normalized(actor, "visual-library-workbench")
            ));
        }

        JsonNode candidate = result.candidate().deepCopy();
        String targetPath = result.target().authoringPath();
        List<ResolvedDecision> deepestFirst = resolved.stream()
                .sorted(Comparator.comparingInt(
                        (ResolvedDecision decision) -> pathDepth(
                                targetPath,
                                decision.confirmation().authoringPath())
                ).reversed())
                .toList();
        for (ResolvedDecision decision : deepestFirst) {
            if (PRESENCE.equals(decision.confirmation().code())
                    || SENSITIVE.equals(decision.confirmation().code())) {
                continue;
            }
            List<String> relativePath = relativePath(
                    targetPath, decision.confirmation().authoringPath());
            candidate = applyStructuralDecision(candidate, relativePath, decision);
        }

        String portName = result.target().portName();
        boolean removePort = false;
        for (ResolvedDecision decision : deepestFirst) {
            List<String> relativePath = relativePath(
                    targetPath, decision.confirmation().authoringPath());
            if (PRESENCE.equals(decision.confirmation().code())) {
                if (relativePath.isEmpty()) {
                    portName = requiredKey(portName, "REQUIRED".equals(decision.value()));
                } else {
                    candidate = applyPresence(candidate, relativePath, decision.value());
                }
            } else if (SENSITIVE.equals(decision.confirmation().code())
                    && "REMOVE_FIELD".equals(decision.value())) {
                if (relativePath.isEmpty()) {
                    removePort = true;
                } else {
                    candidate = remove(candidate, relativePath);
                }
            }
        }
        return new ApplyResult(
                candidate,
                portName,
                removePort,
                List.copyOf(retained)
        );
    }

    private static JsonNode applyStructuralDecision(JsonNode candidate,
                                                    List<String> relativePath,
                                                    ResolvedDecision decision) {
        return switch (decision.confirmation().code()) {
            case NULLABILITY -> replace(
                    candidate,
                    relativePath,
                    nullable(
                            nodeAt(candidate, relativePath),
                            "NULLABLE".equals(decision.value())
                                    || "KEEP_JSON_NULLABLE".equals(decision.value())
                    )
            );
            case FORMAT -> replace(
                    candidate,
                    relativePath,
                    formatted(nodeAt(candidate, relativePath), decision.value())
            );
            case ENUM -> "DECLARE_ENUM".equals(decision.value())
                    ? replace(candidate, relativePath, enumNode(decision))
                    : candidate;
            case OBJECT_CLOSURE -> {
                JsonNode selected = nodeAt(candidate, relativePath);
                if (!(selected instanceof ObjectNode)) {
                    reject(
                            "RG.AUTHORING.INFERENCE_DECISION_TARGET_INVALID",
                            "Object closure decision does not target an object candidate.",
                            422,
                            decision.confirmation().authoringPath()
                    );
                }
                ObjectNode object = (ObjectNode) selected;
                object.put("additionalProperties", "OPEN".equals(decision.value()));
                yield candidate;
            }
            case TYPE_CONFLICT -> candidate;
            default -> candidate;
        };
    }

    private static JsonNode nullable(JsonNode selected, boolean nullable) {
        if (selected == null) {
            reject(
                    "RG.AUTHORING.INFERENCE_DECISION_TARGET_INVALID",
                    "Nullability decision target is absent from the candidate.",
                    422,
                    "/decisions"
            );
        }
        if (selected.isTextual()) {
            String value = selected.asText();
            if (nullable) {
                if ("unknown".equals(value)) {
                    value = "json";
                }
                return JsonNodeFactory.instance.textNode(
                        value.endsWith("?") ? value : value + "?");
            }
            return JsonNodeFactory.instance.textNode(
                    value.endsWith("?") ? value.substring(0, value.length() - 1) : value);
        }
        if (selected.isObject()) {
            return nullable
                    ? JsonNodeFactory.instance.textNode("json?")
                    : selected;
        }
        return selected;
    }

    private static JsonNode formatted(JsonNode selected, String decision) {
        if ("STRING".equals(decision)) {
            return JsonNodeFactory.instance.textNode("string");
        }
        if ("DATE".equals(decision)) {
            return JsonNodeFactory.instance.textNode("date");
        }
        if ("DATETIME".equals(decision)) {
            return JsonNodeFactory.instance.textNode("datetime");
        }
        return selected;
    }

    private static JsonNode enumNode(ResolvedDecision decision) {
        if (decision.observation() == null
                || decision.observation().enumCandidates().isEmpty()) {
            reject(
                    "RG.AUTHORING.INFERENCE_ENUM_EVIDENCE_MISSING",
                    "Enum decision has no retained candidate values.",
                    422,
                    decision.confirmation().authoringPath()
            );
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArrayNode values = node.putArray("enum");
        decision.observation().enumCandidates().forEach(values::add);
        return node;
    }

    private static JsonNode applyPresence(JsonNode candidate,
                                          List<String> relativePath,
                                          String value) {
        FieldCoordinate coordinate = fieldCoordinate(candidate, relativePath);
        String nextKey = requiredKey(
                coordinate.key(),
                "REQUIRED".equals(value)
        );
        if (nextKey.equals(coordinate.key())) {
            return candidate;
        }
        JsonNode field = coordinate.fields().remove(coordinate.key());
        if (field == null) {
            reject(
                    "RG.AUTHORING.INFERENCE_DECISION_TARGET_INVALID",
                    "Presence decision target is absent from the candidate.",
                    422,
                    "/decisions"
            );
        }
        coordinate.fields().set(nextKey, field);
        return candidate;
    }

    private static JsonNode remove(JsonNode candidate, List<String> relativePath) {
        FieldCoordinate coordinate = fieldCoordinate(candidate, relativePath);
        coordinate.fields().remove(coordinate.key());
        return candidate;
    }

    private static JsonNode replace(JsonNode candidate,
                                    List<String> relativePath,
                                    JsonNode replacement) {
        if (relativePath.isEmpty()) {
            return replacement;
        }
        FieldCoordinate coordinate = fieldCoordinate(candidate, relativePath);
        coordinate.fields().set(coordinate.key(), replacement);
        return candidate;
    }

    private static JsonNode nodeAt(JsonNode candidate, List<String> relativePath) {
        if (relativePath.isEmpty()) {
            return candidate;
        }
        return fieldCoordinate(candidate, relativePath).value();
    }

    private static FieldCoordinate fieldCoordinate(JsonNode candidate,
                                                   List<String> relativePath) {
        if (relativePath.size() < 2
                || relativePath.size() % 2 != 0) {
            reject(
                    "RG.AUTHORING.INFERENCE_DECISION_TARGET_UNSUPPORTED",
                    "Decision target cannot be represented by the compact candidate.",
                    422,
                    "/decisions"
            );
        }
        JsonNode current = candidate;
        ObjectNode fields = null;
        String key = "";
        for (int index = 0; index < relativePath.size(); index += 2) {
            if (!"fields".equals(relativePath.get(index))
                    || !(current instanceof ObjectNode object)
                    || !(object.get("fields") instanceof ObjectNode)) {
                reject(
                        "RG.AUTHORING.INFERENCE_DECISION_TARGET_UNSUPPORTED",
                        "Decision target cannot be represented by the compact candidate.",
                        422,
                        "/decisions"
                );
            }
            ObjectNode nextFields = (ObjectNode) current.get("fields");
            fields = nextFields;
            key = relativePath.get(index + 1);
            current = fields.get(key);
            if (current == null) {
                reject(
                        "RG.AUTHORING.INFERENCE_DECISION_TARGET_INVALID",
                        "Decision target is absent from the candidate.",
                        422,
                        "/decisions"
                );
            }
        }
        return new FieldCoordinate(fields, key, current);
    }

    private static Map<String, SampleInferenceApplyRequest.Decision> supplied(
            List<SampleInferenceApplyRequest.Decision> decisions) {
        Map<String, SampleInferenceApplyRequest.Decision> supplied = new LinkedHashMap<>();
        if (decisions == null) {
            return supplied;
        }
        for (SampleInferenceApplyRequest.Decision decision : decisions) {
            if (decision == null
                    || decision.confirmationId().isBlank()
                    || decision.value().isBlank()
                    || supplied.putIfAbsent(decision.confirmationId(), decision) != null) {
                reject(
                        "RG.AUTHORING.INFERENCE_DECISIONS_INVALID",
                        "Decisions require unique confirmationId and non-empty value.",
                        400,
                        "/decisions"
                );
            }
        }
        return supplied;
    }

    private static List<String> relativePath(String targetPath, String authoringPath) {
        if (authoringPath.equals(targetPath)) {
            return List.of();
        }
        if (!authoringPath.startsWith(targetPath + "/")) {
            reject(
                    "RG.AUTHORING.INFERENCE_DECISION_TARGET_INVALID",
                    "Confirmation does not belong to the inference target.",
                    422,
                    authoringPath
            );
        }
        return java.util.Arrays.stream(
                        authoringPath.substring(targetPath.length() + 1).split("/"))
                .map(SampleInferenceCandidateApplier::decodePointer)
                .toList();
    }

    private static int pathDepth(String targetPath, String authoringPath) {
        return relativePath(targetPath, authoringPath).size();
    }

    private static String requiredKey(String key, boolean required) {
        String base = key.endsWith("?") ? key.substring(0, key.length() - 1) : key;
        return required ? base : base + "?";
    }

    private static String decodePointer(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void reject(String code, String message, int status, String path) {
        throw new SampleInferenceRejectedException(code, message, status, path);
    }

    public record ApplyResult(
            JsonNode candidate,
            String portName,
            boolean removePort,
            List<AuthoringConfirmation> confirmations
    ) {
    }

    private record ResolvedDecision(
            SampleInferenceResult.InferenceConfirmation confirmation,
            String value,
            SampleInferenceResult.FieldObservation observation
    ) {
    }

    private record FieldCoordinate(
            ObjectNode fields,
            String key,
            JsonNode value
    ) {
    }
}
