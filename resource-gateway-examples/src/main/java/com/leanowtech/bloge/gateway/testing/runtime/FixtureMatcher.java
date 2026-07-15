package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.planning.BoundedRegexPolicy;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Evaluates the bounded, declarative v1 fixture match language. */
final class FixtureMatcher {

    private final ObjectMapper objectMapper;

    FixtureMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    boolean matches(FixtureRule rule, Object input, String runtimeCorrelationKey) {
        JsonNode actual = objectMapper.valueToTree(input);
        String resourceRef = rule.selector().resourceRef();
        if (!resourceRef.isBlank()
                && !resourceRef.equals(actual.path("resourceId").asText(""))) {
            return false;
        }
        if (!rule.selector().correlationKey().isBlank()) {
            String expected = rule.selector().correlationKey();
            boolean runtimeMatch = expected.equals(runtimeCorrelationKey);
            boolean inputMatch = expected.equals(actual.path("correlationKey").asText(""));
            if (!runtimeMatch && !inputMatch) {
                return false;
            }
        }
        FixtureRule.Match match = rule.selector().match();
        if (match.canonicalInput() != null
                && !Objects.equals(objectMapper.valueToTree(match.canonicalInput()), actual)) {
            return false;
        }
        for (Map.Entry<String, Object> entry : match.pathEquals().entrySet()) {
            if (!Objects.equals(objectMapper.valueToTree(entry.getValue()), actual.at(entry.getKey()))) {
                return false;
            }
        }
        if (match.pathsExist().stream().anyMatch(path -> actual.at(path).isMissingNode())) {
            return false;
        }
        if (match.pathsAbsent().stream().anyMatch(path -> !actual.at(path).isMissingNode())) {
            return false;
        }
        if (!match.correlationKey().isBlank()
                && !match.correlationKey().equals(actual.path("correlationKey").asText(""))) {
            return false;
        }
        if (!match.schema().isEmpty()
                && !VisualSchemaValidator.validateValue(new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA, "2020-12", match.schema()), input, "/input").isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : match.boundedRegex().entrySet()) {
            String pattern = entry.getValue();
            String value = actual.at(entry.getKey()).asText("");
            if (!BoundedRegexPolicy.rejectionReason(pattern).isEmpty()
                    || value.length() > BoundedRegexPolicy.MAX_INPUT_LENGTH
                    || !Pattern.compile(pattern).matcher(value).matches()) {
                return false;
            }
        }
        return true;
    }
}
