package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
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

/**
 * Caller-driven test execution surface, assembled only in {@code test} and {@code staging} profiles.
 * Every operation authenticates a workload before request payloads enter the service layer.
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public class TestExecutionController {

    private final TestExecutionApiService service;
    private final IntegrationRequestAuthenticator authenticator;

    public TestExecutionController(TestExecutionApiService service,
                                   IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/executions")
    public TestExecutionApiResponse execute(@RequestBody TestExecutionApiRequest request,
                                            @RequestHeader HttpHeaders headers) {
        return service.execute(request, context(headers, IntegrationOperation.TEST_EXECUTION));
    }

    @GetMapping("/targets/graphs/{graphName}")
    public TestGraphTargetDescriptor describeGraphTarget(@PathVariable String graphName,
                                                         @RequestHeader HttpHeaders headers) {
        return service.describeGraphTarget(graphName,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Discovers one frozen operator binding and its executable testability contract. */
    @GetMapping("/targets/operators/{operatorRef}")
    public TestOperatorTargetDescriptor describeOperatorTarget(@PathVariable String operatorRef,
                                                               @RequestHeader HttpHeaders headers) {
        return service.describeOperatorTarget(operatorRef,
                context(headers, IntegrationOperation.TEST_TARGET_READ));
    }

    /** Executes one operator through the common one-node BLOGE test kernel. */
    @PostMapping("/targets/operators/{operatorRef}/executions")
    public TestExecutionApiResponse executeOperator(@PathVariable String operatorRef,
                                                    @RequestBody TestOperatorExecutionApiRequest request,
                                                    @RequestHeader HttpHeaders headers) {
        return service.executeOperator(operatorRef, request,
                context(headers, IntegrationOperation.TEST_EXECUTION));
    }

    @PostMapping("/executions/batch")
    public TestExecutionBatchResponse executeBatch(@RequestBody TestExecutionBatchRequest request,
                                                   @RequestHeader HttpHeaders headers) {
        return service.executeBatch(request, context(headers, IntegrationOperation.TEST_EXECUTION));
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
                                               @RequestHeader HttpHeaders headers) {
        return service.registerFixture(fixtureBundleId, request,
                context(headers, IntegrationOperation.TEST_FIXTURE_WRITE));
    }

    @GetMapping("/fixture-bundles/{fixtureBundleId}")
    public StoredFixtureBundle findFixture(@PathVariable String fixtureBundleId,
                                           @RequestParam long revision,
                                           @RequestHeader HttpHeaders headers) {
        return service.findFixture(fixtureBundleId, revision,
                context(headers, IntegrationOperation.TEST_FIXTURE_READ));
    }

    private IntegrationRequestContext context(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
