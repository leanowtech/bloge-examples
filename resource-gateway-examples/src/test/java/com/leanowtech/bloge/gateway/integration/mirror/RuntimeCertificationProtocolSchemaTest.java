package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCertificationProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemasExactlyMatchEveryServerRecordBoundary() throws Exception {
        JsonNode manifest = mapper.valueToTree(RuntimeCertificationProtocolFixtures.manifest());
        JsonNode authorization = mapper.valueToTree(
                RuntimeCertificationProtocolFixtures.authorization());
        JsonNode report = mapper.valueToTree(RuntimeCertificationProtocolFixtures.report());
        JsonNode manifestSchema = schema("runtime-certification-manifest-v1.schema.json");
        JsonNode authorizationSchema = schema(
                "runtime-certification-execution-authorization-v1.schema.json");
        JsonNode reportSchema = schema("runtime-certification-report-v1.schema.json");

        assertExact(manifest, manifestSchema);
        assertExact(manifest.path("scope"), manifestSchema.at("/$defs/scope"));
        assertExact(manifest.path("deployment"), manifestSchema.at("/$defs/deployment"));
        assertExact(manifest.path("components").get(0),
                manifestSchema.at("/$defs/component"));
        assertExact(manifest.path("scenarios").get(0),
                manifestSchema.at("/$defs/scenarioRequirement"));
        assertExact(authorization, authorizationSchema);
        assertExact(authorization.path("authorizationSeal"),
                authorizationSchema.at("/$defs/seal"));
        assertExact(report, reportSchema);
        assertExact(report.path("adapter"), reportSchema.at("/$defs/adapter"));
        assertExact(report.path("observedComponents").get(0),
                reportSchema.at("/$defs/component"));
        assertExact(report.path("scenarioResults").get(0),
                reportSchema.at("/$defs/scenarioResult"));
        assertExact(report.path("scenarioResults").get(0)
                        .path("invariantObservations").get(0),
                reportSchema.at("/$defs/invariantObservation"));
        assertExact(report.path("reportSeal"), reportSchema.at("/$defs/seal"));
    }

    @Test
    void fixedFixturesAreProducedByCurrentServerTypes() throws Exception {
        assertThat(firstDifference(mapper.valueToTree(
                RuntimeCertificationProtocolFixtures.manifest()), fixture(
                "runtime-certification-manifest-v1.fixture.json"), "$"))
                .isEmpty();
        assertThat(firstDifference(mapper.valueToTree(
                RuntimeCertificationProtocolFixtures.authorization()), fixture(
                "runtime-certification-execution-authorization-v1.fixture.json"), "$"))
                .isEmpty();
        assertThat(firstDifference(mapper.valueToTree(
                RuntimeCertificationProtocolFixtures.report()), fixture(
                "runtime-certification-report-v1.fixture.json"), "$"))
                .isEmpty();
    }

    @Test
    void schemasKeepPayloadCredentialsAndAggregateScoresOutOfCertification() throws Exception {
        String source = Files.readString(path("runtime-certification-manifest-v1.schema.json"))
                + Files.readString(path(
                "runtime-certification-execution-authorization-v1.schema.json"))
                + Files.readString(path("runtime-certification-report-v1.schema.json"));
        for (String forbidden : Set.of(
                "requestPayload", "responsePayload", "businessPayload", "customerId",
                "credential", "secretValue", "password", "endpointUri", "privateKey",
                "stackTrace", "maturityScore", "readinessScore", "aggregateScore")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private JsonNode schema(String name) throws Exception {
        return mapper.readTree(Files.readString(path(name)));
    }

    private JsonNode fixture(String name) throws Exception {
        return mapper.readTree(Files.readString(path(name)));
    }

    private static Path path(String name) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", name);
        return Files.exists(moduleRelative.getParent()) ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", name);
    }

    private static void assertExact(JsonNode value, JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(fieldNames(schema.path("properties")));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(textValues(schema.path("required")));
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

    private static String firstDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0
                    ? "" : path + " value";
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return path + " type";
        }
        if (expected.isObject()) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!expected.has(name) || !actual.has(name)) {
                    return path + "." + name + " missing";
                }
                String difference = firstDifference(
                        expected.get(name), actual.get(name), path + "." + name);
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return path + " size";
            }
            for (int index = 0; index < expected.size(); index++) {
                String difference = firstDifference(
                        expected.get(index), actual.get(index), path + "[" + index + "]");
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        return expected.equals(actual) ? "" : path + " value";
    }
}
