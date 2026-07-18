package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile-isolated adapter for exact pure-DSL mutation-suite materialization. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public final class TestMutationSuiteController {
    private final TestMutationSuiteMaterializationService materialization;
    private final TestMutationSuiteExecutionService executions;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param materialization immutable V5 asset service
     * @param executions isolated durable V5 execution service
     * @param authenticator workload identity verifier
     */
    public TestMutationSuiteController(
            TestMutationSuiteMaterializationService materialization,
            TestMutationSuiteExecutionService executions,
            IntegrationRequestAuthenticator authenticator) {
        this.materialization = materialization;
        this.executions = executions;
        this.authenticator = authenticator;
    }

    /** Materializes the exact current graph mutation plan under suite-write authority. */
    @PostMapping("/targets/graphs/{graphName}/mutation-suites")
    public TestMutationSuiteMaterializationResponse materializeGraph(
            @PathVariable String graphName,
            @RequestBody TestMutationSuiteMaterializationRequest request,
            @RequestHeader HttpHeaders headers) {
        return materialization.materializeGraph(graphName, request,
                authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE));
    }

    /** Executes one exact immutable V5 suite under mutation-only runtime isolation. */
    @PostMapping("/suites/{suiteId}/mutation-executions")
    public TestSuiteExecutionResponse execute(
            @PathVariable String suiteId,
            @RequestBody TestMutationSuiteExecutionRequest request,
            @RequestHeader HttpHeaders headers) {
        return executions.execute(suiteId, request,
                authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_EXECUTION));
    }
}
