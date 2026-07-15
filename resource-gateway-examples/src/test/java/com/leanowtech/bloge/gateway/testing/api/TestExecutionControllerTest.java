package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestExecutionControllerTest {

    @Test
    void httpSurfaceRequiresVerifiedTestExecutionIdentity() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/testing/executions/run-1").header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"resource-gateway-testing\""))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
    }

    @Test
    void verifiedRequestUsesServerIdentityAndReturnsStoredRun() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        TestExecutionApiResponse response = new TestExecutionApiResponse("", "run-1",
                new TestExecutionApiRequest.Target("GRAPH", "graph-a", "sha256:target"),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED", "fixture-a", 1,
                        "sha256:fixture"), null, null);
        when(service.find(eq("run-1"), eq(TestExecutionApiRequest.Verbosity.SUMMARY), any()))
                .thenReturn(response);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/testing/executions/run-1")
                        .queryParam("verbosity", "SUMMARY")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.target.id").value("graph-a"));

        verify(service).find(eq("run-1"), eq(TestExecutionApiRequest.Verbosity.SUMMARY),
                org.mockito.ArgumentMatchers.argThat(identity -> identity.tenantId().equals("tenant-a")
                        && identity.environmentId().equals("test")
                        && identity.actorId().equals("runner")));
    }

    @Test
    void malformedJsonUsesStableTestingProblemContract() throws Exception {
        MockMvc mvc = mvc(mock(TestExecutionApiService.class));

        mvc.perform(post("/api/testing/executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
    }

    @Test
    void operatorDiscoveryAndExecutionUseDedicatedPathsAndPurposes() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        TestExecutionApiRequest.Target target = new TestExecutionApiRequest.Target(
                "OPERATOR", "customer.normalize", "sha256:target");
        when(service.describeOperatorTarget(eq("customer.normalize"), any())).thenReturn(
                new TestOperatorTargetDescriptor("", target, "sha256:implementation", "sha256:state",
                        "sha256:schema", "sha256:composability",
                        Map.of("dependencyMode", "NONE"),
                        Map.of(), Map.of(), "SYNCHRONOUS", "READ_ONLY", "IDEMPOTENT", Map.of(),
                        "EXECUTABLE_UNIT", Map.of(), "NONE_DECLARED", true, true, List.of(), List.of()));
        TestExecutionApiResponse response = new TestExecutionApiResponse("", "run-operator-1", target,
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "INLINE", "fixture-a", 1, "sha256:fixture"), null, null);
        when(service.executeOperator(eq("customer.normalize"), any(), any())).thenReturn(response);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/testing/targets/operators/customer.normalize")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.kind").value("OPERATOR"))
                .andExpect(jsonPath("$.testabilityClass").value("EXECUTABLE_UNIT"));
        mvc.perform(post("/api/testing/targets/operators/customer.normalize/executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"bloge.testOperatorExecutionRequest.v1",
                                 "target":{"kind":"OPERATOR","id":"customer.normalize","fingerprint":""},
                                 "executionPurpose":"OPERATOR_UNIT_TEST","input":{"value":"Ada"},
                                 "fixtureBundle":null,
                                 "fixtureBundleRef":{"fixtureBundleId":"fixture-a","revision":1,"fingerprint":""},
                                 "verbosity":"STANDARD","metadata":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-operator-1"));

        verify(service).describeOperatorTarget(eq("customer.normalize"), any());
        verify(service).executeOperator(eq("customer.normalize"), any(), any());
    }

    @Test
    void executionPurposeCannotWriteGovernedFixtureRevisions() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(put("/api/testing/fixture-bundles/fixture-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void suiteRegistryUsesDedicatedWritePurposeAndExactRevisionPath() throws Exception {
        TestExecutionApiService execution = mock(TestExecutionApiService.class);
        TestSuiteRegistryService suites = mock(TestSuiteRegistryService.class);
        TestSuite suite = new TestSuite("", "suite-a", 1,
                new TestSuite.Target("GRAPH", "graph-a", "sha256:" + "a".repeat(64)),
                "INTERNAL", List.of(), TestSuite.CoveragePolicy.defaults(),
                TestSuite.PromotionPolicy.defaults(), Map.of());
        when(suites.register(eq("suite-a"), any(), any())).thenReturn(new StoredTestSuite(
                "", "tenant-a", "test", "suite-a", 1, "sha256:" + "b".repeat(64),
                suite, Instant.now(), "runner"));
        MockMvc mvc = mvc(execution, suites, Set.of("TEST_SUITE_WRITE"));

        mvc.perform(put("/api/testing/suites/suite-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_SUITE_WRITE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"bloge.testSuiteRegistrationRequest.v1",
                                 "testSuite":{"schemaVersion":"bloge.testSuite.v1","suiteId":"suite-a",
                                 "revision":1,"target":{"kind":"GRAPH","id":"graph-a",
                                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                                 "classification":"INTERNAL","cases":[],
                                 "coveragePolicy":{"minimumCases":1,"requiredCaseTypes":[],
                                 "requiredInvocationSiteIds":[],"requiredEdgeTransfers":[],"minimumAssertionsPerCase":0,
                                 "requireAllFixtureRulesConsumed":true},
                                 "promotionPolicy":{"requireAllCasesPassed":true,
                                 "minimumCertifiableCases":1,"requireTargetCertificationEligible":true},
                                 "metadata":{}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suiteId").value("suite-a"))
                .andExpect(jsonPath("$.revision").value(1));

        verify(suites).register(eq("suite-a"), any(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_SUITE_WRITE")));
        verifyNoInteractions(execution);
    }

    @Test
    void executionPurposeCannotWriteGovernedSuiteRevisions() throws Exception {
        TestExecutionApiService execution = mock(TestExecutionApiService.class);
        TestSuiteRegistryService suites = mock(TestSuiteRegistryService.class);
        MockMvc mvc = mvc(execution, suites, Set.of("TEST_EXECUTION"));

        mvc.perform(put("/api/testing/suites/suite-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(execution, suites);
    }

    @Test
    void suiteExecutionUsesTestExecutionPurposeAndExactIdempotentRequest() throws Exception {
        TestExecutionApiService execution = mock(TestExecutionApiService.class);
        TestSuiteRegistryService suites = mock(TestSuiteRegistryService.class);
        TestSuiteExecutionService suiteRuns = mock(TestSuiteExecutionService.class);
        when(suiteRuns.execute(eq("suite-a"), any(), any())).thenReturn(
                new TestSuiteExecutionResponse("", "suite-run-1", "sha256:" + "f".repeat(64), null));
        MockMvc mvc = mvc(execution, suites, suiteRuns, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/suite-a/executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"bloge.testSuiteExecutionRequest.v1",
                                 "suiteRef":{"suiteId":"suite-a","revision":3,
                                 "fingerprint":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
                                 "clientRequestId":"ci-build-42","strategy":"COLLECT_ALL","metadata":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suiteRunId").value("suite-run-1"));

        verify(suiteRuns).execute(eq("suite-a"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.clientRequestId().equals("ci-build-42")
                                && request.suiteRef().revision() == 3),
                org.mockito.ArgumentMatchers.argThat(context ->
                        context.purpose().equals("TEST_EXECUTION")));
        verifyNoInteractions(execution, suites);
    }

    @Test
    void catalogMaterializationUsesSuiteWritePurposeAndVerifiedScope() throws Exception {
        TestExecutionApiService execution = mock(TestExecutionApiService.class);
        TestSuiteRegistryService suites = mock(TestSuiteRegistryService.class);
        TestSuiteExecutionService suiteRuns = mock(TestSuiteExecutionService.class);
        TestSuiteCatalogMaterializationService catalogs =
                mock(TestSuiteCatalogMaterializationService.class);
        TestSuiteCatalogMaterializationResponse response =
                new TestSuiteCatalogMaterializationResponse("", "catalog-a",
                        "sha256:" + "a".repeat(64), "tenant-a", "test", 1, 1, List.of(
                        new TestSuiteCatalogMaterializationResponse.SuiteAsset(
                                "source-a", "graph-a", 1,
                                new TestSuiteExecutionRequest.SuiteRef(
                                        "suite-a", 3, "sha256:" + "b".repeat(64)),
                                List.of(new TestSuite.FixtureBundleRef(
                                        "fixture-a", 2, "sha256:" + "c".repeat(64))))));
        when(catalogs.materializeBuiltIn(any())).thenReturn(response);
        MockMvc mvc = mvc(execution, suites, suiteRuns, catalogs, Set.of("TEST_SUITE_WRITE"));

        mvc.perform(put("/api/testing/catalogs/gateway-graph-contract-v1")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_SUITE_WRITE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.suites[0].suiteRef.suiteId").value("suite-a"));

        verify(catalogs).materializeBuiltIn(org.mockito.ArgumentMatchers.argThat(context ->
                context.purpose().equals("TEST_SUITE_WRITE")
                        && context.tenantId().equals("tenant-a")));
        verifyNoInteractions(execution, suites, suiteRuns);
    }

    private static MockMvc mvc(TestExecutionApiService service) {
        return mvc(service, mock(TestSuiteRegistryService.class), Set.of("TEST_EXECUTION"));
    }

    private static MockMvc mvc(TestExecutionApiService service,
                               TestSuiteRegistryService suites,
                               Set<String> purposes) {
        return mvc(service, suites, mock(TestSuiteExecutionService.class), purposes);
    }

    private static MockMvc mvc(TestExecutionApiService service,
                               TestSuiteRegistryService suites,
                               TestSuiteExecutionService suiteRuns,
                               Set<String> purposes) {
        return mvc(service, suites, suiteRuns,
                mock(TestSuiteCatalogMaterializationService.class), purposes);
    }

    private static MockMvc mvc(TestExecutionApiService service,
                               TestSuiteRegistryService suites,
                               TestSuiteExecutionService suiteRuns,
                               TestSuiteCatalogMaterializationService catalogs,
                               Set<String> purposes) {
        RecordingAudit audit = new RecordingAudit();
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false), audit);
        return MockMvcBuilders.standaloneSetup(new TestExecutionController(
                        service, suites, suiteRuns, catalogs, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler()).build();
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();
        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }
        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }
}
