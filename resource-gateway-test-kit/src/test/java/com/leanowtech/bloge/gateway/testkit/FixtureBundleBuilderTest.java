package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureBundleBuilderTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void buildsSchemaCompleteFailClosedFixtureAndBothExecutionSources() throws Exception {
        FixtureBundleBuilder fixture = FixtureBundleBuilder.graph("loanDecision", FINGERPRINT)
                .id("loan-approved")
                .revision(3)
                .classification(FixtureBundleBuilder.Classification.CONFIDENTIAL)
                .logicalClock(Instant.parse("2026-07-15T10:15:30Z"))
                .randomSeed(42)
                .metadata("suiteRef", "loan-policy-regression")
                .rule("credit-score")
                    .node("creditScore")
                    .matchPath("/applicant/id", "app-42")
                    .returnValue(Map.of("score", 780))
                    .requiredUses(1, 1)
                    .add()
                .rule("bureau-protocol")
                    .resource("creditBureau")
                    .protocolResponse("{\"code\":0,\"data\":{\"score\":780}}", 200,
                            Map.of("Content-Type", "application/json"))
                    .optionalUses(0, 1)
                    .add()
                .assertOutput("/approved", "EQUALS", true)
                .assertNumericOutput("/rate", "EQUALS", 0.0525, 0.0001);

        JsonNode bundle = fixture.buildBundle();
        assertThat(bundle.path("schemaVersion").asText()).isEqualTo(TestingProtocol.FIXTURE_BUNDLE_V1);
        assertThat(bundle.path("fixtureBundleId").asText()).isEqualTo("loan-approved");
        assertThat(bundle.path("revision").asLong()).isEqualTo(3);
        assertThat(bundle.path("classification").asText()).isEqualTo("CONFIDENTIAL");
        assertThat(bundle.path("logicalClock").asText()).isEqualTo("2026-07-15T10:15:30Z");
        assertThat(bundle.path("randomSeed").asLong()).isEqualTo(42);
        assertThat(bundle.path("rules")).hasSize(2);

        JsonNode first = bundle.path("rules").get(0);
        assertThat(first.path("selector").path("graphPath").asText()).isEqualTo("/root");
        assertThat(first.path("selector").path("match").path("pathEquals").path("/applicant/id").asText())
                .isEqualTo("app-42");
        assertThat(first.path("consumption").path("required").asBoolean()).isTrue();
        assertThat(first.path("consumption").path("onExhausted").asText()).isEqualTo("FAIL");
        assertThat(first.path("consumption").path("onUnmatched").asText()).isEqualTo("FAIL");
        assertThat(first.path("schemaCheck").path("mode").asText()).isEqualTo("STRICT");

        JsonNode protocol = bundle.path("rules").get(1).path("behavior");
        assertThat(protocol.path("boundary").asText()).isEqualTo("TRANSPORT");
        assertThat(protocol.path("statusCode").asInt()).isEqualTo(200);
        assertThat(protocol.path("value").isNull()).isTrue();

        JsonNode registration = fixture.registrationRequest();
        assertThat(registration.path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.FIXTURE_REGISTRATION_REQUEST_V1);
        assertThat(registration.path("target").path("id").asText()).isEqualTo("loanDecision");

        JsonNode inline = fixture.inlineExecution(Map.of("applicantId", "app-42"),
                ResourceGatewayTestClient.Verbosity.FULL, Map.of("caseRef", "approved"));
        assertThat(inline.path("fixtureBundle").isObject()).isTrue();
        assertThat(inline.path("fixtureBundleRef").isNull()).isTrue();
        assertThat(inline.path("executionPurpose").asText()).isEqualTo("GRAPH_CONTRACT_TEST");

        JsonNode stored = fixture.storedExecution("sha256:" + "b".repeat(64), Map.of(),
                ResourceGatewayTestClient.Verbosity.STANDARD, Map.of());
        assertThat(stored.path("fixtureBundle").isNull()).isTrue();
        assertThat(stored.path("fixtureBundleRef").path("revision").asLong()).isEqualTo(3);

        assertAllRequiredPropertiesPresent(bundle, schema().at("/$defs/fixtureBundle"));
        assertAllRequiredPropertiesPresent(first, schema().at("/$defs/fixtureRule"));
        assertAllRequiredPropertiesPresent(first.path("selector"), schema().at("/$defs/selector"));
        assertAllRequiredPropertiesPresent(first.path("selector").path("match"), schema().at("/$defs/match"));
        assertAllRequiredPropertiesPresent(first.path("behavior"), schema().at("/$defs/behavior"));
    }

    @Test
    void rejectsWireContractViolationsBeforeSending() {
        assertThatThrownBy(() -> FixtureBundleBuilder.graph("", FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graphId");
        assertThatThrownBy(() -> FixtureBundleBuilder.graph("graph", "not-a-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");

        FixtureBundleBuilder builder = FixtureBundleBuilder.graph("graph", FINGERPRINT)
                .id("fixture")
                .rule("same").node("one").returnValue(1).add();
        assertThatThrownBy(() -> builder.rule("same").node("two").returnValue(2).add())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        Map<String, String> tooManyEntries = new LinkedHashMap<>();
        for (int index = 0; index <= 100; index++) {
            tooManyEntries.put("key-" + index, "value");
        }
        assertThatThrownBy(() -> builder.inlineExecution(Map.of(),
                ResourceGatewayTestClient.Verbosity.STANDARD, tooManyEntries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Execution metadata");
        assertThatThrownBy(() -> builder.rule("oversized-body").resource("provider")
                .protocolResponse("x".repeat(1_048_577), 200, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawBody");
        assertThatThrownBy(() -> builder.rule("too-many-headers").resource("provider")
                .protocolResponse("{}", 200, tooManyEntries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("headers");
        assertThatThrownBy(() -> builder.rule("oversized-error").node("provider")
                .throwing("UPSTREAM_FAILED", "UpstreamException", "x".repeat(4_097)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorMessage");

        assertThat(builder.buildBundle().path("rules")).hasSize(1);
    }

    @Test
    void buildsBoundedDelayAndTimeoutControls() {
        JsonNode bundle = FixtureBundleBuilder.graph("graph", FINGERPRINT)
                .id("time-controls")
                .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
                .rule("delayed-score")
                    .node("score")
                    .delay(Duration.ofSeconds(5), Map.of("score", 780))
                    .add()
                .rule("provider-timeout")
                    .node("provider")
                    .timeout(Duration.ofSeconds(3), "PROVIDER_TIMEOUT", "provider unavailable")
                    .requiredUses(2, 2)
                    .add()
                .buildBundle();

        assertThat(bundle.path("rules").get(0).path("behavior").path("kind").asText())
                .isEqualTo("DELAY");
        assertThat(bundle.path("rules").get(0).path("behavior").path("after").asText())
                .isEqualTo("PT5S");
        assertThat(bundle.path("rules").get(1).path("behavior").path("kind").asText())
                .isEqualTo("TIMEOUT");
        assertThat(bundle.path("rules").get(1).path("behavior").path("errorCode").asText())
                .isEqualTo("PROVIDER_TIMEOUT");
        assertThat(bundle.path("rules").get(1).path("consumption").path("maxUses").asInt())
                .isEqualTo(2);

        assertThatThrownBy(() -> FixtureBundleBuilder.graph("graph", FINGERPRINT).id("bad")
                .rule("too-long").node("provider")
                .timeout(Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365 days");
    }

    @Test
    void buildsOperatorFixtureRegistrationAndTypedMicroGraphRequests() {
        FixtureBundleBuilder fixture = FixtureBundleBuilder.operator("customer.normalize", FINGERPRINT)
                .id("normalize-contract")
                .rule("execute-real-binding")
                    .operator("customer.normalize")
                    .spy()
                    .requiredUses(1, 1)
                    .add()
                .assertOutput("/normalized", "EQUALS", "ADA");

        JsonNode registration = fixture.registrationRequest();
        JsonNode inline = fixture.inlineOperatorExecution(Map.of("name", "Ada"),
                ResourceGatewayTestClient.Verbosity.FULL, Map.of("caseRef", "uppercase"));
        JsonNode stored = fixture.storedOperatorExecution("sha256:" + "b".repeat(64),
                Map.of("name", "Ada"), ResourceGatewayTestClient.Verbosity.STANDARD, Map.of());

        assertThat(registration.path("target").path("kind").asText()).isEqualTo("OPERATOR");
        assertThat(registration.path("target").path("id").asText()).isEqualTo("customer.normalize");
        assertThat(inline.path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.OPERATOR_EXECUTION_REQUEST_V1);
        assertThat(inline.path("executionPurpose").asText()).isEqualTo("OPERATOR_UNIT_TEST");
        assertThat(inline.path("input").path("name").asText()).isEqualTo("Ada");
        assertThat(inline.path("fixtureBundle").isObject()).isTrue();
        assertThat(stored.path("fixtureBundleRef").path("fingerprint").asText())
                .isEqualTo("sha256:" + "b".repeat(64));
        assertThatThrownBy(() -> fixture.inlineExecution(Map.of(),
                ResourceGatewayTestClient.Verbosity.STANDARD, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GRAPH execution");
    }

    private static JsonNode schema() throws Exception {
        try (InputStream input = FixtureBundleBuilderTest.class.getResourceAsStream(
                "/schemas/resource-gateway-testing/testing-control-plane-v1.schema.json")) {
            assertThat(input).isNotNull();
            return new ObjectMapper().readTree(input);
        }
    }

    private static void assertAllRequiredPropertiesPresent(JsonNode value, JsonNode schema) {
        for (JsonNode required : schema.path("required")) {
            assertThat(value.has(required.asText()))
                    .as("required property %s", required.asText())
                    .isTrue();
        }
    }
}
