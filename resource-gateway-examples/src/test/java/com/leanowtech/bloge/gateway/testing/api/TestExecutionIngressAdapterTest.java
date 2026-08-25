package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestExecutionIngressAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestExecutionIngressAdapter adapter = new TestExecutionIngressAdapter(mapper);
    private final IntegrationRequestContext identity = new IntegrationRequestContext(
            "tenant-a", "org-a", "project-a", "test", "region-a", "WORKLOAD", "runner-a",
            "", "TEST_EXECUTION", "corr-a", Set.of("quality"), "INTERNAL", "");

    @Test
    void noControlHeadersPreserveRequestAndBusinessContext() {
        TestExecutionApiRequest request = request(null, null);

        TestExecutionIngress admitted = adapter.admit(request, identity, new HttpHeaders());

        assertThat(admitted.request()).isSameAs(request);
        assertThat(admitted.request().context()).containsEntry("customerId", "customer-a");
        assertThat(admitted.fidelityToken()).isNull();
        assertThat(admitted.scopeToken()).isNull();
    }

    @Test
    void knownControlParsingDoesNotTreatFutureHeaderAsAParsedProtocolHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-bloge-test-Future", "future");

        assertThat(TestExecutionIngressAdapter.hasControlHeaders(headers)).isFalse();
        assertThat(TestExecutionIngressAdapter.hasAnyTestControlHeaders(headers)).isTrue();
    }

    @Test
    void inlineFixtureIsDecodedIntoTheExistingFixturePathAndContextIsUnchanged() {
        Map<String, Object> originalContext = Map.of("customerId", "customer-a", "amount", 42);
        TestExecutionApiRequest request = request(originalContext, null);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-BLOGE-Test-Inline", encoded(inlineFixtureNode()));
        headers.set("X-BLOGE-Test-Fidelity", "mock");
        headers.set("X-BLOGE-Test-Scope", "graph");

        TestExecutionIngress admitted = adapter.admit(request, identity, headers);

        assertThat(admitted.request()).isNotSameAs(request);
        assertThat(admitted.request().fixtureBundle()).isEqualTo(fixture());
        assertThat(admitted.request().fixtureBundleRef()).isNull();
        assertThat(admitted.request().context()).containsExactlyInAnyOrderEntriesOf(originalContext);
        assertThat(admitted.fidelityToken()).isEqualTo("mock");
        assertThat(admitted.scopeToken()).isEqualTo("graph");
        assertThat(admitted.toString()).doesNotContain("fixture-a", "customer-a");
    }

    @Test
    void inlineAndBodyFixtureSourcesAreRejected() {
        TestExecutionApiRequest request = request(null, fixture());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-BLOGE-Test-Inline", encoded(inlineFixtureNode()));

        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(request, identity, headers), IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.FIXTURE_SOURCE_AMBIGUOUS");
        assertThat(exception.toString()).doesNotContain("fixture-a");
    }

    @Test
    void envelopePurposeCannotBeForgedAndUnsupportedAssetIsRejected() {
        HttpHeaders forged = new HttpHeaders();
        forged.set("X-BLOGE-Test-Envelope", encoded(envelope("NOT_GRAPH_CONTRACT_TEST", false)));

        IntegrationProblemException purpose = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, forged), IntegrationProblemException.class);

        assertThat(purpose.problem().code()).isEqualTo("RG.TEST.CONTROL_PURPOSE_MISMATCH");

        HttpHeaders unsupported = new HttpHeaders();
        unsupported.set("X-BLOGE-Test-Envelope", encoded(envelope("GRAPH_CONTRACT_TEST", false)));
        IntegrationProblemException asset = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, unsupported), IntegrationProblemException.class);

        assertThat(asset.problem().code()).isEqualTo("RG.TEST.CONTROL_ASSET_RESOLUTION_UNSUPPORTED");
    }

    @Test
    void correlationMismatchIsRejectedWithoutHeaderValueDisclosure() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-BLOGE-Test-Envelope", encoded(envelope("GRAPH_CONTRACT_TEST", true)));

        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, headers), IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.CONTROL_CORRELATION_MISMATCH");
        assertThat(exception.toString()).doesNotContain("corr-other", "fixture-a");
    }

    @Test
    void malformedAndDuplicateControlsFailClosedWithSanitizedReason() {
        HttpHeaders malformed = new HttpHeaders();
        malformed.set("X-BLOGE-Test-Inline", "not-a-base64-value");
        IntegrationProblemException invalid = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, malformed), IntegrationProblemException.class);
        assertThat(invalid.problem().code()).isEqualTo("RG.TEST.CONTROL_HEADERS_INVALID");
        assertThat(invalid.toString()).doesNotContain("not-a-base64-value");

        HttpHeaders duplicate = new HttpHeaders();
        duplicate.put("x-bloge-test-scope", List.of("mock", "mock"));
        IntegrationProblemException repeated = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, duplicate), IntegrationProblemException.class);
        assertThat(repeated.problem().code()).isEqualTo("RG.TEST.CONTROL_HEADERS_INVALID");
        assertThat(repeated.toString()).doesNotContain("mock");

        HttpHeaders oversized = new HttpHeaders();
        String oversizedValue = "A".repeat(8_193);
        oversized.set("X-BLOGE-Test-Inline", oversizedValue);
        IntegrationProblemException tooLarge = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, oversized), IntegrationProblemException.class);
        assertThat(tooLarge.problem().code()).isEqualTo("RG.TEST.CONTROL_HEADERS_INVALID");
        assertThat(tooLarge.problem().correlationId()).isEqualTo(identity.correlationId());
        assertThat(tooLarge.problem().details()).containsEntry("reason", "ENCODED_VALUE_TOO_LARGE");
        assertThat(tooLarge.toString()).doesNotContain(oversizedValue);
    }

    @Test
    void inlineShapeRejectsUnknownControlSemantics() {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("fixtureBundle");
        root.putObject("unexpected");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-BLOGE-Test-Inline", encoded(root));

        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity, headers), IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.INLINE_FIXTURE_INVALID");
        assertThat(exception.toString()).doesNotContain("unexpected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "TEST", " test ", "staging", " STAGING "})
    void environmentIdsUseTheSameCanonicalTestAndStagingValues(String environment) {
        TestExecutionIngress admitted = adapter.admit(
                request(null, null), identity(environment), new HttpHeaders());

        assertThat(admitted.request()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "PROD", " prod ", "production", "PRODUCTION", " production "})
    void productionEnvironmentIdsShareOneRejectedPolicy(String environment) {
        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(request(null, null), identity(environment), new HttpHeaders()),
                IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.ENVIRONMENT_FORBIDDEN");
    }

    @Test
    void authenticationRunsBeforeControlHeaderParsing() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        IntegrationWorkloadIdentity workload = new IntegrationWorkloadIdentity(
                "identity-a", "tenant-a", "org-a", "project-a", "test", "region-a",
                "WORKLOAD", "runner-a", "", Set.of("TEST_EXECUTION"),
                java.time.Instant.MAX, true, Set.of(), "INTERNAL", "", java.time.Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", workload, false),
                mock(IntegrationAccessAuditRepository.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestExecutionController(
                        service, mock(TestSuiteRegistryService.class), mock(TestSuiteExecutionService.class),
                        mock(TestSuiteCatalogMaterializationService.class), mock(TestReplayPayloadService.class),
                        authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler())
                .build();

        mvc.perform(post("/api/testing/executions")
                        .header("X-BLOGE-Test-Inline", "definitely-not-base64url")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(service);
    }

    @Test
    void identityIsRejectedBeforeMalformedControlHeaderParsing() {
        HttpHeaders malformed = new HttpHeaders();
        malformed.set("X-BLOGE-Test-Inline", "not-a-base64-value");
        IntegrationRequestContext production = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "production", "region-a", "WORKLOAD", "runner-a",
                "", "TEST_EXECUTION", "corr-production", Set.of(), "INTERNAL", "");

        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(request(null, null), production, malformed), IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.ENVIRONMENT_FORBIDDEN");
        assertThat(exception.problem().correlationId()).isEqualTo("corr-production");
        assertThat(exception.toString()).doesNotContain("not-a-base64-value");
    }

    @Test
    void enterpriseScopeIsRejectedBeforeMalformedControlHeaderParsing() {
        HttpHeaders malformed = new HttpHeaders();
        malformed.set("X-BLOGE-Test-Inline", "not-a-base64-value");
        IntegrationRequestContext incomplete = new IntegrationRequestContext(
                "tenant-a", "org-a", "", "test", "region-a", "WORKLOAD", "runner-a",
                "", "TEST_EXECUTION", "corr-incomplete", Set.of(), "INTERNAL", "");

        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(request(null, null), incomplete, malformed), IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.ENTERPRISE_SCOPE_REQUIRED");
        assertThat(exception.problem().correlationId()).isEqualTo("corr-incomplete");
    }

    @Test
    void forgedBodyPurposeIsRejectedAtTheAdmissionBoundary() {
        IntegrationProblemException exception = catchThrowableOfType(
                () -> adapter.admit(requestWithPurpose("FORGED_PURPOSE"), identity, new HttpHeaders()),
                IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.EXECUTION_PURPOSE_INVALID");
        assertThat(exception.problem().correlationId()).isEqualTo(identity.correlationId());
        assertThat(exception.toString()).doesNotContain("FORGED_PURPOSE");
    }

    @Test
    void controllerDoesNotForwardForgedBodyPurposeIntoTheServiceBoundary() {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        TestExecutionController controller = new TestExecutionController(
                service, mock(TestSuiteRegistryService.class), mock(TestSuiteExecutionService.class),
                mock(TestSuiteCatalogMaterializationService.class), mock(TestReplayPayloadService.class),
                authenticator());
        HttpHeaders headers = authenticatedHeaders();
        headers.set("X-BLOGE-Test-Fidelity", "mock");

        IntegrationProblemException exception = catchThrowableOfType(
                () -> controller.execute(requestWithPurpose("FORGED_PURPOSE"), headers),
                IntegrationProblemException.class);

        assertThat(exception.problem().code()).isEqualTo("RG.TEST.EXECUTION_PURPOSE_INVALID");
        verifyNoInteractions(service);
    }

    @Test
    void controllerPassesAdmittedIngressToServiceWithFixtureAndOriginalContext() {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        TestExecutionApiResponse response = new TestExecutionApiResponse(
                "", "run-inline", request(null, null).target(), null, null, null);
        when(service.executeAdmittedIngress(any(TestExecutionIngress.class), any()))
                .thenReturn(response);
        TestExecutionController controller = new TestExecutionController(
                service, mock(TestSuiteRegistryService.class), mock(TestSuiteExecutionService.class),
                mock(TestSuiteCatalogMaterializationService.class), mock(TestReplayPayloadService.class),
                authenticator());
        TestExecutionApiRequest request = request(Map.of("customerId", "customer-a", "amount", 42), null);
        HttpHeaders headers = authenticatedHeaders();
        headers.set("X-BLOGE-Test-Inline", encoded(inlineFixtureNode()));

        assertThat(controller.execute(request, headers)).isSameAs(response);

        org.mockito.ArgumentCaptor<TestExecutionIngress> ingress =
                org.mockito.ArgumentCaptor.forClass(TestExecutionIngress.class);
        verify(service).executeAdmittedIngress(ingress.capture(), any());
        assertThat(ingress.getValue().request().fixtureBundle()).isEqualTo(fixture());
        assertThat(ingress.getValue().request().context()).containsExactlyInAnyOrderEntriesOf(request.context());
    }

    private TestExecutionApiRequest request(Map<String, Object> context, FixtureBundle bodyFixture) {
        return requestWithPurpose(TestExecutionApiService.AUTHORIZED_PURPOSE, context, bodyFixture);
    }

    private IntegrationRequestContext identity(String environment) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "region-a", "WORKLOAD", "runner-a",
                "", "TEST_EXECUTION", "corr-a", Set.of("quality"), "INTERNAL", "");
    }

    private TestExecutionApiRequest requestWithPurpose(String purpose) {
        return requestWithPurpose(purpose, null, null);
    }

    private TestExecutionApiRequest requestWithPurpose(String purpose,
                                                       Map<String, Object> context,
                                                       FixtureBundle bodyFixture) {
        return new TestExecutionApiRequest(
                TestExecutionApiRequest.SCHEMA_VERSION,
                new TestExecutionApiRequest.Target("GRAPH", "graph-a", ""),
                purpose,
                context == null ? Map.of("customerId", "customer-a") : context,
                bodyFixture, null, TestExecutionApiRequest.Verbosity.STANDARD, Map.of());
    }

    private IntegrationRequestAuthenticator authenticator() {
        IntegrationWorkloadIdentity workload = new IntegrationWorkloadIdentity(
                "identity-a", "tenant-a", "org-a", "project-a", "test", "region-a",
                "WORKLOAD", "runner-a", "", Set.of("TEST_EXECUTION"),
                Instant.MAX, true, Set.of(), "INTERNAL", "", Instant.MAX);
        return new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", workload, false),
                mock(IntegrationAccessAuditRepository.class));
    }

    private HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-controller");
        return headers;
    }

    private FixtureBundle fixture() {
        return new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION, "fixture-a", 1, "sha256:" + "a".repeat(64),
                "INTERNAL", null, null, List.of(), List.of(), Map.of());
    }

    private JsonNode inlineFixtureNode() {
        ObjectNode root = mapper.createObjectNode();
        root.set("fixtureBundle", mapper.valueToTree(fixture()));
        return root;
    }

    private ObjectNode envelope(String purpose, boolean otherCorrelation) {
        ObjectNode root = mapper.createObjectNode();
        root.put("purpose", purpose);
        root.put("correlationId", otherCorrelation ? "corr-other" : "corr-a");
        ObjectNode scenario = root.putObject("scenario");
        scenario.put("id", "scenario-a");
        scenario.put("revision", 1);
        scenario.put("fingerprint", "sha256:" + "b".repeat(64));
        return root;
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
