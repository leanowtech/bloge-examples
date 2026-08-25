package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real servlet-container proof that graph test execution authentication precedes DTO parsing. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.testing.mirror.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.demo-token=test-token",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=region-a",
                "gateway.integration.identity.groups=resource-gateway-test-runtime-operators",
                "gateway.integration.identity.clearance=RESTRICTED",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_FIXTURE_READ,TEST_FIXTURE_WRITE,TEST_REPLAY,TEST_SUITE_READ,TEST_SUITE_WRITE,TEST_RUNTIME_MAINTENANCE,MIRROR_REHEARSAL,MIRROR_SHADOW,GOVERNANCE_EVIDENCE_INGESTION",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:test-execution-auth-main;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:test-execution-auth-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class TestExecutionAuthenticationInterceptorIntegrationTest {
    private static final String BODY_SECRET = "body-secret-must-not-leak";
    private static final String HEADER_SECRET = "header-secret-must-not-leak";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private TestExecutionApiService executions;

    @MockitoBean
    private TestSuiteRegistryService suiteRegistry;

    @MockitoBean
    private TestSuiteExecutionService suiteExecutions;

    @MockitoBean
    private TestReplayPayloadService replayPayloads;

    @ParameterizedTest
    @MethodSource("bodyBearingRoutes")
    void unauthenticatedMalformedOversizedJsonFailsAuthenticationBeforeDtoParsing(
            String method, String path, String acceptedPurpose, String ignoredWrongPurpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Purpose", acceptedPurpose);
        headers.set("X-Leak-Probe", HEADER_SECRET);
        String malformedOversizedBody = "{\"schemaVersion\":\"" + BODY_SECRET + " " + "x".repeat(16_384);

        ResponseEntity<String> response = rest.exchange(path,
                HttpMethod.valueOf(method), new HttpEntity<>(malformedOversizedBody, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("RG.INTEGRATION.AUTHENTICATION_REQUIRED");
        assertThat(response.getBody()).doesNotContain("RG.TEST.REQUEST_MALFORMED", BODY_SECRET, HEADER_SECRET);
        verifyNoInteractions(executions, suiteRegistry, suiteExecutions, replayPayloads);
    }

    @ParameterizedTest
    @MethodSource("bodyBearingRoutes")
    void operationMismatchFailsBeforeDtoParsingAndCannotReuseAnotherRouteContext(
            String method, String path, String ignoredAcceptedPurpose, String wrongPurpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Purpose", wrongPurpose);
        headers.set("X-Correlation-Id", "corr-mismatch");
        headers.set("X-Leak-Probe", HEADER_SECRET);
        String malformedOversizedBody = "{\"schemaVersion\":\"" + BODY_SECRET + " " + "x".repeat(16_384);

        ResponseEntity<String> response = rest.exchange(path,
                HttpMethod.valueOf(method), new HttpEntity<>(malformedOversizedBody, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("RG.INTEGRATION.PURPOSE_FORBIDDEN");
        assertThat(response.getBody()).doesNotContain("RG.TEST.REQUEST_MALFORMED", BODY_SECRET, HEADER_SECRET);
        verifyNoInteractions(executions, suiteRegistry, suiteExecutions, replayPayloads);
    }

    @Test
    void authenticatedBoundedInlineControlReachesAdmittedServicePath() {
        TestExecutionApiResponse response = new TestExecutionApiResponse(
                "", "run-inline", new TestExecutionApiRequest.Target("GRAPH", "graph-a", ""),
                null, null, null);
        when(executions.executeAdmittedIngress(any(TestExecutionIngress.class), any()))
                .thenReturn(response);
        HttpHeaders headers = authenticatedHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-BLOGE-Test-Inline", encoded(inlineFixtureNode()));
        String body = """
                {"schemaVersion":"bloge.testExecutionRequest.v1",
                 "target":{"kind":"GRAPH","id":"graph-a","fingerprint":""},
                 "executionPurpose":"GRAPH_CONTRACT_TEST",
                 "context":{"customerId":"customer-a","city":"Singapore"},
                 "verbosity":"SUMMARY","metadata":{"caseId":"case-a"}}
                """;

        ResponseEntity<String> actual = rest.exchange("/api/testing/executions",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).contains("run-inline");
        ArgumentCaptor<TestExecutionIngress> ingress = ArgumentCaptor.forClass(TestExecutionIngress.class);
        ArgumentCaptor<IntegrationRequestContext> identity =
                ArgumentCaptor.forClass(IntegrationRequestContext.class);
        verify(executions).executeAdmittedIngress(ingress.capture(), identity.capture());
        assertThat(ingress.getValue().request().fixtureBundle()).isEqualTo(fixture());
        assertThat(ingress.getValue().request().fixtureBundleRef()).isNull();
        assertThat(ingress.getValue().request().context())
                .containsEntry("customerId", "customer-a")
                .containsEntry("city", "Singapore");
        assertThat(identity.getValue().purpose()).isEqualTo("TEST_EXECUTION");
        assertThat(identity.getValue().correlationId()).isEqualTo("corr-inline");
    }

    @ParameterizedTest
    @MethodSource("controlRejectedRoutes")
    void authenticatedUnsupportedControlHeadersAreRejectedBeforeBodyParsing(
            String method, String path, String purpose, String controlHeaderName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Purpose", purpose);
        headers.set("X-Correlation-Id", "corr-control-rejected");
        headers.set(controlHeaderName, "graph");
        String body = "{\"secretBusinessPayload\":\"" + BODY_SECRET + "\"";

        ResponseEntity<String> response = rest.exchange(path,
                HttpMethod.valueOf(method), new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("RG.TEST.CONTROL_HEADERS_UNSUPPORTED");
        assertThat(response.getBody()).doesNotContain(BODY_SECRET, HEADER_SECRET);
        verifyNoInteractions(executions, suiteRegistry, suiteExecutions, replayPayloads);
    }

    private static Stream<Arguments> bodyBearingRoutes() {
        return Stream.of(
                arguments("POST", "/api/testing/executions", "TEST_EXECUTION", "TEST_SUITE_WRITE"),
                arguments("POST", "/api/testing/targets/operators/customer.normalize/executions",
                        "TEST_EXECUTION", "TEST_SUITE_WRITE"),
                arguments("POST", "/api/testing/executions/batch", "TEST_EXECUTION", "TEST_SUITE_WRITE"),
                arguments("PUT", "/api/testing/fixture-bundles/fixture-a",
                        "TEST_FIXTURE_WRITE", "TEST_EXECUTION"),
                arguments("PUT", "/api/testing/replay-payloads/replay-a", "TEST_REPLAY", "TEST_EXECUTION"),
                arguments("PUT", "/api/testing/suites/suite-a", "TEST_SUITE_WRITE", "TEST_EXECUTION"),
                arguments("POST", "/api/testing/suites/suite-a/executions",
                        "TEST_EXECUTION", "TEST_SUITE_WRITE"));
    }

    private static Stream<Arguments> controlRejectedRoutes() {
        return Stream.of(
                arguments("POST", "/api/testing/targets/operators/customer.normalize/executions",
                        "TEST_EXECUTION", "X-BLOGE-Test-Scope"),
                arguments("POST", "/api/testing/executions/batch", "TEST_EXECUTION",
                        "X-BLOGE-Test-Scope"),
                arguments("PUT", "/api/testing/fixture-bundles/fixture-a", "TEST_FIXTURE_WRITE",
                        "X-BLOGE-Test-Scope"),
                arguments("PUT", "/api/testing/replay-payloads/replay-a", "TEST_REPLAY",
                        "X-BLOGE-Test-Scope"),
                arguments("PUT", "/api/testing/suites/suite-a", "TEST_SUITE_WRITE",
                        "X-BLOGE-Test-Scope"),
                arguments("POST", "/api/testing/suites/suite-a/executions", "TEST_EXECUTION",
                        "X-BLOGE-Test-Scope"),
                arguments("POST", "/api/testing/executions/batch", "TEST_EXECUTION",
                        "X-BLOGE-Test-Future"));
    }

    private HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-inline");
        return headers;
    }

    private JsonNode inlineFixtureNode() {
        ObjectNode root = mapper.createObjectNode();
        root.set("fixtureBundle", mapper.valueToTree(fixture()));
        return root;
    }

    private FixtureBundle fixture() {
        return new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION, "fixture-a", 1, "sha256:" + "a".repeat(64),
                "INTERNAL", Instant.parse("2026-01-01T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of());
    }

    private String encoded(JsonNode node) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
