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

class RegionalDataPlaneProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemasExactlyMatchServerProducedRecordBoundaries() throws Exception {
        JsonNode contract = mapper.valueToTree(RegionalDataPlaneProtocolFixtures.contract());
        JsonNode certification = mapper.valueToTree(
                RegionalDataPlaneProtocolFixtures.certification());
        JsonNode contractSchema = schema(
                "regional-data-plane-deployment-contract-v1.schema.json");
        JsonNode certificationSchema = schema(
                "regional-data-plane-certification-v1.schema.json");

        assertExact(contract, contractSchema);
        assertExact(contract.path("scope"), contractSchema.at("/$defs/scope"));
        assertExact(contract.path("deployment"), contractSchema.at("/$defs/deployment"));
        assertExact(contract.path("requiredComponents").get(0),
                contractSchema.at("/$defs/componentRequirement"));
        assertExact(contract.path("rotationPolicy"),
                contractSchema.at("/$defs/rotationPolicy"));
        assertExact(certification, certificationSchema);
        assertExact(certification.path("componentObservations").get(0),
                certificationSchema.at("/$defs/componentObservation"));
        assertExact(certification.path("rotationObservations").get(0),
                certificationSchema.at("/$defs/rotationObservation"));
        assertExact(certification.path("certificationSeal"),
                certificationSchema.at("/$defs/seal"));
    }

    @Test
    void fixedFixturesAreProducedByCurrentServerTypes() throws Exception {
        JsonNode contract = mapper.valueToTree(RegionalDataPlaneProtocolFixtures.contract());
        JsonNode certification = mapper.valueToTree(
                RegionalDataPlaneProtocolFixtures.certification());
        assertThat(firstDifference(contract, fixture(
                "regional-data-plane-deployment-contract-v1.fixture.json"), "$"))
                .isEmpty();
        assertThat(firstDifference(certification, fixture(
                "regional-data-plane-certification-v1.fixture.json"), "$"))
                .isEmpty();
    }

    @Test
    void v2IsolationBundleRequiresExactRegionalCertificationRef() throws Exception {
        JsonNode value = mapper.valueToTree(
                RegionalDataPlaneProtocolFixtures.isolationBundle());
        JsonNode schema = schema(
                "mirror-deployment-isolation-attestation-bundle-v2.schema.json");

        assertExact(value, schema);
        assertThat(firstDifference(value, fixture(
                "mirror-deployment-isolation-attestation-bundle-v2.fixture.json"), "$"))
                .isEmpty();
        assertThat(value.path("regionalDataPlaneCertificationRef").path("kind").asText())
                .isEqualTo(RegionalDataPlaneCertification.ARTIFACT_KIND);
        var attestationIntegrity = new MirrorDeploymentIsolationAttestationIntegrity(mapper);
        assertThat(new MirrorDeploymentIsolationAttestationBundleIntegrity(
                mapper, attestationIntegrity).canonicalBundleVerified(
                RegionalDataPlaneProtocolFixtures.isolationBundle())).isTrue();
    }

    @Test
    void protocolsCannotCarryPayloadEndpointOrCredentialMaterial() throws Exception {
        String source = Files.readString(path(
                "regional-data-plane-deployment-contract-v1.schema.json"))
                + Files.readString(path(
                "regional-data-plane-certification-v1.schema.json"))
                + Files.readString(path(
                "mirror-deployment-isolation-attestation-bundle-v2.schema.json"));
        for (String forbidden : Set.of(
                "requestPayload", "responsePayload", "businessPayload", "customerId",
                "credential", "secretValue", "token", "password", "endpointUri",
                "privateKey", "rawCertificate", "stackTrace")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
        assertThat(source).doesNotContain("maturityScore", "readinessScore");
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

    private static String firstDifference(
            JsonNode expected, JsonNode actual, String path) {
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
                String difference = firstDifference(expected.get(index), actual.get(index),
                        path + "[" + index + "]");
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        return expected.equals(actual) ? "" : path + " value";
    }
}
