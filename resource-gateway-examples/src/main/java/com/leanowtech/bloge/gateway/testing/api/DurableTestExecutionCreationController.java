package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile-isolated authenticated HTTP boundary for initial durable graph/operator creation. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions")
public final class DurableTestExecutionCreationController {

    private final DurableTestExecutionCreationService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the durable creation transport.
     *
     * @param service authenticated creation application service
     * @param authenticator verified workload identity boundary
     */
    public DurableTestExecutionCreationController(
            DurableTestExecutionCreationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Creates or idempotently replays one durable graph test at its first signal suspension.
     *
     * @param request exact graph, fixture, idempotency, and business-input intent
     * @param headers workload credential and explicit test-execution purpose
     * @return payload-free initial suspended execution view
     */
    @PostMapping
    public DurableTestExecutionCreateResponse create(
            @RequestBody DurableTestExecutionCreateRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.create(request, authenticator.authenticate(
                headers, IntegrationOperation.TEST_DURABLE_EXECUTION_CREATE));
    }

    /**
     * Creates or idempotently replays one durable operator test at its first signal suspension.
     *
     * @param operatorRef path-bound registry reference that must equal the request target id
     * @param request exact operator, fixture, idempotency, and formal-input intent
     * @param headers workload credential and explicit test-execution purpose
     * @return payload-free initial suspended execution view
     */
    @PostMapping("/operators/{operatorRef}")
    public DurableTestExecutionCreateResponse createOperator(
            @PathVariable String operatorRef,
            @RequestBody DurableOperatorTestExecutionCreateRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.createOperator(operatorRef, request, authenticator.authenticate(
                headers, IntegrationOperation.TEST_DURABLE_EXECUTION_CREATE));
    }
}
