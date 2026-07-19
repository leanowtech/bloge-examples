package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile- and feature-isolated HTTP adapter for signed compact-observation trends. */
@RestController
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        name = "gateway.testing.stability-cross-retention-preview-enabled",
        havingValue = "true")
@RequestMapping("/api/testing")
public final class TestSuiteStabilityCrossRetentionTrendController {
    private final TestSuiteStabilityCrossRetentionTrendAnalysisService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param service authorized range trend service
     * @param authenticator workload identity verifier
     */
    public TestSuiteStabilityCrossRetentionTrendController(
            TestSuiteStabilityCrossRetentionTrendAnalysisService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Produces one signed exact-suite compact-observation range trend. */
    @PostMapping("/suites/{suiteId}/stability-cross-retention-trend-analyses")
    public TestSuiteStabilityCrossRetentionTrendAnalysisResponse analyze(
            @PathVariable String suiteId,
            @RequestBody TestSuiteStabilityCrossRetentionTrendAnalysisRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.analyze(suiteId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_STABILITY_TREND_READ));
    }
}
