package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.config.GatewayConfiguration;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Caller-driven test execution surface, assembled only in {@code test} and {@code staging} profiles.
 * Every operation authenticates a workload before request payloads enter the service layer.
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public class TestExecutionController {

    private final TestExecutionApiService service;
    private final TestSuiteRegistryService suiteRegistry;
    private final TestSuiteExecutionService suiteExecutions;
    private final TestSuiteCatalogMaterializationService catalogMaterialization;
    private final TestReplayPayloadService replayPayloads;
    private final IntegrationRequestAuthenticator authenticator;
    private final TestExecutionIngressAdapter ingressAdapter;

    public TestExecutionController(TestExecutionApiService service,
                                   TestSuiteRegistryService suiteRegistry,
                                   TestSuiteExecutionService suiteExecutions,
                                   TestSuiteCatalogMaterializationService catalogMaterialization,
                                   TestReplayPayloadService replayPayloads,
                                   IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.suiteRegistry = suiteRegistry;
        this.suiteExecutions = suiteExecutions;
        this.catalogMaterialization = catalogMaterialization;
        this.replayPayloads = replayPayloads;
        this.authenticator = authenticator;
        this.ingressAdapter = new TestExecutionIngressAdapter(
                new GatewayConfiguration().objectMapper());
    }

    @PostMapping("/executions")
    public TestExecutionApiResponse execute(@RequestBody TestExecutionApiRequest request,
                                             @RequestHeader HttpHeaders headers,
                                             HttpServletRequest servletRequest) {
        IntegrationRequestContext authenticated = testExecutionContext(headers, servletRequest);
        if (!TestExecutionIngressAdapter.hasControlHeaders(headers)) {
            return service.execute(request, authenticated);
        }
        return service.executeAdmittedIngress(ingressAdapter.admit(request, authenticated, headers), authenticated);
    }

    /** Backward-compatible direct-call entry used by standalone controller tests. */
    TestExecutionApiResponse execute(TestExecutionApiRequest request,
                                     HttpHeaders headers) {
        return execute(request, headers, null);
    }

    @GetMapping("/targets/graphs/{graphName}")
    public TestGraphTargetDescriptor describeGraphTarget(@PathVariable String graphName,
                                                         @RequestHeader HttpHeaders headers) {
        return service.describeGraphTarget(graphName,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Generates a validator-proven boundary input plan for one graph contract. */
    @GetMapping("/targets/graphs/{graphName}/boundary-cases")
    public TestBoundaryCasePlan planGraphBoundaryCases(
            @PathVariable String graphName,
            @RequestHeader HttpHeaders headers) {
        return service.planGraphBoundaryCases(graphName,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Generates a reproducible bounded property plan for one graph contract. */
    @GetMapping("/targets/graphs/{graphName}/property-cases")
    public TestPropertyCasePlan planGraphPropertyCases(
            @PathVariable String graphName,
            @RequestParam long seed,
            @RequestParam(defaultValue = "8") int trials,
            @RequestParam(defaultValue = "3") int maxShrinkSteps,
            @RequestHeader HttpHeaders headers) {
        return service.planGraphPropertyCases(graphName, seed, trials, maxShrinkSteps,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Generates a bounded independently compiled pure-DSL mutation plan for one graph. */
    @GetMapping("/targets/graphs/{graphName}/mutation-cases")
    public TestMutationCasePlan planGraphMutationCases(
            @PathVariable String graphName,
            @RequestParam(defaultValue = "64") int maxMutants,
            @RequestHeader HttpHeaders headers) {
        return service.planGraphMutationCases(graphName, maxMutants,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Discovers one frozen operator binding and its executable testability contract. */
    @GetMapping("/targets/operators/{operatorRef}")
    public TestOperatorTargetDescriptor describeOperatorTarget(@PathVariable String operatorRef,
                                                               @RequestHeader HttpHeaders headers) {
        return service.describeOperatorTarget(operatorRef,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Generates a validator-proven boundary input plan for one operator binding. */
    @GetMapping("/targets/operators/{operatorRef}/boundary-cases")
    public TestBoundaryCasePlan planOperatorBoundaryCases(
            @PathVariable String operatorRef,
            @RequestHeader HttpHeaders headers) {
        return service.planOperatorBoundaryCases(operatorRef,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Generates a reproducible bounded property plan for one operator binding. */
    @GetMapping("/targets/operators/{operatorRef}/property-cases")
    public TestPropertyCasePlan planOperatorPropertyCases(
            @PathVariable String operatorRef,
            @RequestParam long seed,
            @RequestParam(defaultValue = "8") int trials,
            @RequestParam(defaultValue = "3") int maxShrinkSteps,
            @RequestHeader HttpHeaders headers) {
        return service.planOperatorPropertyCases(operatorRef, seed, trials, maxShrinkSteps,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Executes one operator through the common one-node BLOGE test kernel. */
    @PostMapping("/targets/operators/{operatorRef}/executions")
    public TestExecutionApiResponse executeOperator(@PathVariable String operatorRef,
                                                    @RequestBody TestOperatorExecutionApiRequest request,
                                                    @RequestHeader HttpHeaders headers,
                                                    HttpServletRequest servletRequest) {
        return service.executeOperator(operatorRef, request,
                context(headers, IntegrationOperation.TEST_EXECUTION, servletRequest));
    }

    @PostMapping("/executions/batch")
    public TestExecutionBatchResponse executeBatch(@RequestBody TestExecutionBatchRequest request,
                                                   @RequestHeader HttpHeaders headers,
                                                   HttpServletRequest servletRequest) {
        return service.executeBatch(request,
                context(headers, IntegrationOperation.TEST_EXECUTION, servletRequest));
    }

    @GetMapping("/executions/{runId}")
    public TestExecutionApiResponse find(@PathVariable String runId,
                                         @RequestParam(defaultValue = "STANDARD")
                                         TestExecutionApiRequest.Verbosity verbosity,
                                         @RequestHeader HttpHeaders headers) {
        return service.find(runId, verbosity, context(headers, IntegrationOperation.TEST_EXECUTION));
    }

    @PutMapping("/fixture-bundles/{fixtureBundleId}")
    public StoredFixtureBundle registerFixture(@PathVariable String fixtureBundleId,
                                               @RequestBody FixtureBundleRegistrationRequest request,
                                               @RequestHeader HttpHeaders headers,
                                               HttpServletRequest servletRequest) {
        return service.registerFixture(fixtureBundleId, request,
                context(headers, IntegrationOperation.TEST_FIXTURE_WRITE, servletRequest));
    }

    @GetMapping("/fixture-bundles/{fixtureBundleId}")
    public StoredFixtureBundle findFixture(@PathVariable String fixtureBundleId,
                                           @RequestParam long revision,
                                           @RequestHeader HttpHeaders headers) {
        return service.findFixture(fixtureBundleId, revision,
                context(headers, IntegrationOperation.TEST_FIXTURE_READ));
    }

    /** Captures one exact successful attempt from the governed run payload vault. */
    @PutMapping("/replay-payloads/{replayPayloadId}")
    public StoredReplayPayload captureReplayPayload(
            @PathVariable String replayPayloadId,
            @RequestBody ReplayPayloadCaptureRequest request,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest servletRequest) {
        return replayPayloads.capture(replayPayloadId, request,
                context(headers, IntegrationOperation.TEST_REPLAY_WRITE, servletRequest));
    }

    /** Resolves one exact governed replay payload while its value remains available. */
    @GetMapping("/replay-payloads/{replayPayloadId}")
    public StoredReplayPayload findReplayPayload(
            @PathVariable String replayPayloadId,
            @RequestParam long revision,
            @RequestHeader HttpHeaders headers) {
        return replayPayloads.find(replayPayloadId, revision,
                context(headers, IntegrationOperation.TEST_REPLAY_READ));
    }

    /** Registers one dependency-closed immutable test-suite revision. */
    @PutMapping("/suites/{suiteId}")
    public StoredTestSuite registerSuite(@PathVariable String suiteId,
                                         @RequestBody TestSuiteRegistrationRequest request,
                                         @RequestHeader HttpHeaders headers,
                                         HttpServletRequest servletRequest) {
        return suiteRegistry.register(suiteId, request,
                context(headers, IntegrationOperation.TEST_SUITE_WRITE, servletRequest));
    }

    /** Resolves one exact governed test-suite revision. */
    @GetMapping("/suites/{suiteId}")
    public StoredTestSuite findSuite(@PathVariable String suiteId,
                                     @RequestParam long revision,
                                     @RequestHeader HttpHeaders headers) {
        return suiteRegistry.find(suiteId, revision,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /**
     * Materializes the trusted legacy graph catalog into exact caller-scoped fixture and suite assets.
     */
    @PutMapping("/catalogs/gateway-graph-contract-v1")
    public TestSuiteCatalogMaterializationResponse materializeGatewayGraphContractCatalog(
            @RequestHeader HttpHeaders headers) {
        return catalogMaterialization.materializeBuiltIn(
                context(headers, IntegrationOperation.TEST_SUITE_WRITE));
    }

    /** Executes one exact immutable suite revision with an idempotent client request key. */
    @PostMapping("/suites/{suiteId}/executions")
    public TestSuiteExecutionResponse executeSuite(
            @PathVariable String suiteId,
            @RequestBody TestSuiteExecutionRequest request,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest servletRequest) {
        return suiteExecutions.execute(suiteId, request,
                context(headers, IntegrationOperation.TEST_SUITE_EXECUTION, servletRequest));
    }

    /** Resolves the latest durable checkpoint or terminal evidence for one suite run. */
    @GetMapping("/suite-executions/{suiteRunId}")
    public TestSuiteExecutionResponse findSuiteExecution(
            @PathVariable String suiteRunId,
            @RequestHeader HttpHeaders headers) {
        return suiteExecutions.find(suiteRunId,
                context(headers, IntegrationOperation.TEST_SUITE_EXECUTION));
    }

    /** Exports one verified terminal aggregate without child request or response payloads. */
    @GetMapping("/suite-executions/{suiteRunId}/evidence-bundle")
    public TestSuiteEvidenceBundle exportSuiteEvidence(
            @PathVariable String suiteRunId,
            @RequestHeader HttpHeaders headers) {
        return suiteExecutions.evidenceBundle(suiteRunId,
                context(headers, IntegrationOperation.TEST_SUITE_EXECUTION));
    }

    private IntegrationRequestContext context(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }

    private IntegrationRequestContext context(HttpHeaders headers,
                                              IntegrationOperation operation,
                                              HttpServletRequest servletRequest) {
        Object value = servletRequest == null ? null
                : servletRequest.getAttribute(TestExecutionAuthenticationInterceptor.REQUEST_ATTRIBUTE);
        if (value instanceof TestExecutionAuthenticationInterceptor.AuthenticatedRequest authenticated) {
            if (authenticated.operation() != operation) {
                throw new IntegrationProblemException(IntegrationProblem.forbidden(
                        "RG.TEST.PRE_AUTH_OPERATION_MISMATCH",
                        "The pre-authenticated request context does not match the endpoint operation.",
                        authenticated.context() == null ? "" : authenticated.context().correlationId(),
                        Map.of("operation", operation.name())));
            }
            return authenticated.context();
        }
        return context(headers, operation);
    }

    private IntegrationRequestContext testExecutionContext(HttpHeaders headers,
                                                           HttpServletRequest servletRequest) {
        return context(headers, IntegrationOperation.TEST_EXECUTION, servletRequest);
    }
}
