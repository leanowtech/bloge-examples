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

/** Profile- and feature-isolated HTTP adapter for receipt-aware lifecycle v2 pages. */
@RestController
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        name = "gateway.testing.stability-cross-retention-preview-enabled",
        havingValue = "true")
@RequestMapping("/api/testing")
public final class TestSuiteStabilityObservationLedgerLifecycleArchiveController {
    private final TestSuiteStabilityObservationLedgerLifecycleArchivePageService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param service authorized receipt-aware lifecycle assembler
     * @param authenticator workload identity verifier
     */
    public TestSuiteStabilityObservationLedgerLifecycleArchiveController(
            TestSuiteStabilityObservationLedgerLifecycleArchivePageService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Produces one signed exact-suite lifecycle v2 page with external archive proof. */
    @PostMapping("/suites/{suiteId}/stability-observation-ledger-lifecycle-archive-pages")
    public TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse read(
            @PathVariable String suiteId,
            @RequestBody TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.read(suiteId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_STABILITY_TREND_READ));
    }
}
