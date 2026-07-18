package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile-isolated HTTP adapter for bounded signed suite-stability reruns. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public final class TestSuiteStabilityController {
    private final TestSuiteStabilityExecutionService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param service idempotent stability rerun service
     * @param authenticator workload identity verifier
     */
    public TestSuiteStabilityController(
            TestSuiteStabilityExecutionService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Executes one exact suite 3..20 times and returns signed payload-free stability evidence. */
    @PostMapping("/suites/{suiteId}/stability-executions")
    public TestSuiteStabilityExecutionResponse execute(
            @PathVariable String suiteId,
            @RequestBody TestSuiteStabilityExecutionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.execute(suiteId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_EXECUTION));
    }

    /** Resolves one retained signed stability analysis in the authenticated scope. */
    @GetMapping("/stability-executions/{stabilityRunId}")
    public TestSuiteStabilityExecutionResponse find(
            @PathVariable String stabilityRunId,
            @RequestHeader HttpHeaders headers) {
        return service.find(stabilityRunId,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_EXECUTION));
    }

    /** Resolves payload-free active, takeover-ready, or completed parent progress. */
    @GetMapping("/stability-executions/{stabilityRunId}/progress")
    public TestSuiteStabilityProgressResponse findProgress(
            @PathVariable String stabilityRunId,
            @RequestHeader HttpHeaders headers) {
        return service.findProgress(stabilityRunId,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_EXECUTION));
    }
}
