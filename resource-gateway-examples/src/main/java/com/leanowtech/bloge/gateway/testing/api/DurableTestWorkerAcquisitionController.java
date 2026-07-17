package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, profile-isolated transport for non-blocking durable worker acquisition. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions/worker-acquisitions")
public class DurableTestWorkerAcquisitionController {

    private final DurableTestWorkerAcquisitionService service;
    private final IntegrationRequestAuthenticator authenticator;

    /** Creates the worker pull transport over verified integration identity. */
    public DurableTestWorkerAcquisitionController(
            DurableTestWorkerAcquisitionService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Acquires at most one authorized expired execution without disclosing queue contents.
     *
     * @param request versioned caller-stable poll identity
     * @param headers workload credential and explicit test purpose
     * @return immutable acquired assignment or bounded no-work observation
     */
    @PostMapping
    public DurableTestWorkerAcquisitionResponse acquire(
            @RequestBody DurableTestWorkerAcquisitionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.acquire(request, authenticator.authenticate(
                headers, IntegrationOperation.TEST_DURABLE_WORKER_ACQUISITION));
    }
}
