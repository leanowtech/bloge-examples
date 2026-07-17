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

/**
 * Profile-isolated adapter for human-confirmed boundary-suite materialization.
 *
 * <p>Both routes require suite-write authority because they commit immutable fixture and suite
 * revisions. Target-read or execution-only credentials cannot turn generated candidates into
 * governed assets.</p>
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public final class TestBoundarySuiteController {
    private final TestBoundarySuiteMaterializationService materialization;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param materialization immutable boundary asset service
     * @param authenticator workload identity verifier
     */
    public TestBoundarySuiteController(
            TestBoundarySuiteMaterializationService materialization,
            IntegrationRequestAuthenticator authenticator) {
        this.materialization = materialization;
        this.authenticator = authenticator;
    }

    /** Materializes selected cases from the current exact graph boundary plan. */
    @PostMapping("/targets/graphs/{graphName}/boundary-suites")
    public TestBoundarySuiteMaterializationResponse materializeGraph(
            @PathVariable String graphName,
            @RequestBody TestBoundarySuiteMaterializationRequest request,
            @RequestHeader HttpHeaders headers) {
        return materialization.materializeGraph(graphName, request,
                context(headers));
    }

    /** Materializes selected cases from the current exact operator boundary plan. */
    @PostMapping("/targets/operators/{operatorRef}/boundary-suites")
    public TestBoundarySuiteMaterializationResponse materializeOperator(
            @PathVariable String operatorRef,
            @RequestBody TestBoundarySuiteMaterializationRequest request,
            @RequestHeader HttpHeaders headers) {
        return materialization.materializeOperator(operatorRef, request,
                context(headers));
    }

    private IntegrationRequestContext context(HttpHeaders headers) {
        return authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE);
    }
}
