package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile-isolated adapter for exact seeded-property-suite materialization. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public final class TestPropertySuiteController {
    private final TestPropertySuiteMaterializationService materialization;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param materialization immutable V4 asset service
     * @param authenticator workload identity verifier
     */
    public TestPropertySuiteController(
            TestPropertySuiteMaterializationService materialization,
            IntegrationRequestAuthenticator authenticator) {
        this.materialization = materialization;
        this.authenticator = authenticator;
    }

    /** Materializes the exact current graph property plan under suite-write authority. */
    @PostMapping("/targets/graphs/{graphName}/property-suites")
    public TestPropertySuiteMaterializationResponse materializeGraph(
            @PathVariable String graphName,
            @RequestBody TestPropertySuiteMaterializationRequest request,
            @RequestHeader HttpHeaders headers) {
        return materialization.materializeGraph(graphName, request, context(headers));
    }

    /** Materializes the exact current operator property plan under suite-write authority. */
    @PostMapping("/targets/operators/{operatorRef}/property-suites")
    public TestPropertySuiteMaterializationResponse materializeOperator(
            @PathVariable String operatorRef,
            @RequestBody TestPropertySuiteMaterializationRequest request,
            @RequestHeader HttpHeaders headers) {
        return materialization.materializeOperator(operatorRef, request, context(headers));
    }

    private IntegrationRequestContext context(HttpHeaders headers) {
        return authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE);
    }
}
