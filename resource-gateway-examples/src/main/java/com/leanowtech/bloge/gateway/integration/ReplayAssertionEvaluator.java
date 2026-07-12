package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayAssertionResult;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates declarative replay assertions without invoking graph operators. */
final class ReplayAssertionEvaluator {
    private final ObjectMapper objectMapper;

    ReplayAssertionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    List<VisualReplayAssertionResult> evaluate(ReplayExecutionRequest request,
                                               VisualGraphRunRecord parent,
                                               RunEvidenceBundle parentEvidence) {
        return request.assertions().stream()
                .map(assertion -> evaluate(assertion, parent, parentEvidence))
                .toList();
    }

    private VisualReplayAssertionResult evaluate(ReplayExecutionRequest.Assertion assertion,
                                                  VisualGraphRunRecord parent,
                                                  RunEvidenceBundle evidence) {
        Object actual = actual(assertion, parent, evidence);
        boolean passed;
        String message;
        try {
            passed = switch (assertion.mode()) {
                case "EQUALS" -> jsonEquals(assertion.expectedValue(), actual);
                case "PATH_EQUALS" -> jsonEquals(assertion.expectedValue(), at(actual, assertion.path()));
                case "PATH_EXISTS" -> !at(actual, assertion.path()).isMissingNode();
                case "PATH_ABSENT" -> at(actual, assertion.path()).isMissingNode();
                case "MATCHES_SCHEMA" -> matchesSchema(assertion.expectedValue(), actual);
                case "ERROR_CONTAINS" -> parent.errors().stream()
                        .anyMatch(error -> error.contains(String.valueOf(assertion.expectedValue())));
                case "GOVERNANCE_EXPECTATION" -> governanceExpectation(assertion.expectedValue(), parent, evidence);
                default -> false;
            };
            message = passed ? "Assertion passed." : "Assertion failed for mode " + assertion.mode() + ".";
        } catch (RuntimeException failure) {
            passed = false;
            message = "Assertion could not be evaluated: " + failure.getClass().getSimpleName() + ".";
        }
        return new VisualReplayAssertionResult(
                assertion.assertionId(), assertion.scope(), assertion.nodeId(), assertion.mode(), assertion.path(),
                passed, fingerprint(assertion.expectedValue()), fingerprint(actual), message);
    }

    private Object actual(ReplayExecutionRequest.Assertion assertion,
                          VisualGraphRunRecord parent,
                          RunEvidenceBundle evidence) {
        return switch (assertion.scope()) {
            case "OUTPUT" -> parent.outputPayload();
            case "NODE" -> nodeOutput(parent, assertion.nodeId());
            case "RUN" -> Map.of(
                    "errors", parent.errors(),
                    "success", parent.success(),
                    "evidenceStatus", evidence.manifest().evidenceStatus(),
                    "signatureStatus", evidence.manifest().signatureStatus(),
                    "mockUsed", evidence.execution().mockUsed());
            default -> null;
        };
    }

    private static Object nodeOutput(VisualGraphRunRecord parent, String nodeId) {
        if (parent.resultsPayload().containsKey(nodeId)) {
            return parent.resultsPayload().get(nodeId);
        }
        List<VisualNodeExecutionAttempt> attempts = parent.nodeAttempts().getOrDefault(nodeId, List.of());
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).output();
    }

    private JsonNode at(Object actual, String path) {
        JsonNode root = objectMapper.valueToTree(actual);
        return path == null || path.isBlank() ? root : root.at(path);
    }

    private boolean matchesSchema(Object expected, Object actual) {
        if (!(expected instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(String.valueOf(key), value));
        SchemaEnvelope schema = values.get("schema") instanceof Map<?, ?>
                ? objectMapper.convertValue(values, SchemaEnvelope.class)
                : new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", values);
        return VisualSchemaValidator.validateEnvelope(schema, "/expectedValue").isEmpty()
                && VisualSchemaValidator.validateValue(schema, actual, "/actual").isEmpty();
    }

    private static boolean governanceExpectation(Object expected,
                                                 VisualGraphRunRecord parent,
                                                 RunEvidenceBundle evidence) {
        return switch (String.valueOf(expected).trim().toUpperCase()) {
            case "EVIDENCE_READY" -> "READY".equals(evidence.manifest().evidenceStatus());
            case "SIGNATURE_VERIFIED" -> "VERIFIED".equals(evidence.manifest().signatureStatus());
            case "NO_MOCKS" -> !evidence.execution().mockUsed();
            case "NO_ERRORS" -> parent.errors().isEmpty();
            default -> false;
        };
    }

    private boolean jsonEquals(Object expected, Object actual) {
        return objectMapper.valueToTree(expected).equals(objectMapper.valueToTree(actual));
    }

    private static String fingerprint(Object value) {
        return VisualBundleFingerprint.fromMaterial(Map.of("value", value == null ? "" : value));
    }
}
