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

/** Profile-isolated HTTP adapter for signed retained-window stability trends. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public final class TestSuiteStabilityTrendController {
    private final TestSuiteStabilityTrendAnalysisService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param service authorized trend-analysis service
     * @param authenticator workload identity verifier
     */
    public TestSuiteStabilityTrendController(
            TestSuiteStabilityTrendAnalysisService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Produces one signed exact-suite retained-window trend projection. */
    @PostMapping("/suites/{suiteId}/stability-trend-analyses")
    public TestSuiteStabilityTrendAnalysisResponse analyze(
            @PathVariable String suiteId,
            @RequestBody TestSuiteStabilityTrendAnalysisRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.analyze(suiteId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_STABILITY_TREND_READ));
    }
}
