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

/**
 * Profile-isolated HTTP boundary for durable test execution ownership commands.
 *
 * <p>Every command authenticates before service entry. This endpoint claims an expired lease only;
 * it does not execute or resume BLOGE and therefore cannot return node inputs, outputs, fixtures, or
 * replay payloads.</p>
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions")
public class DurableTestOwnerClaimController {

    private final DurableTestOwnerClaimService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the authenticated durable owner-claim transport.
     *
     * @param service owner-claim application service
     * @param authenticator verified workload identity boundary
     */
    public DurableTestOwnerClaimController(
            DurableTestOwnerClaimService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Claims one exact expired durable execution for the server-configured recovery owner.
     *
     * @param runId path-bound durable run identity
     * @param request exact prior fence and caller idempotency key
     * @param headers authentication and explicit-purpose headers
     * @return payload-free immutable owner-claim result
     */
    @PostMapping("/{runId}/owner-claims")
    public DurableTestOwnerClaimResponse claim(
            @PathVariable String runId,
            @RequestBody DurableTestOwnerClaimRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.claim(runId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_DURABLE_OWNER_CLAIM));
    }
}
