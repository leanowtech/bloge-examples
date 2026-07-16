package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile-isolated HTTP boundary for the global durable projection finding owner queue.
 *
 * <p>All methods authenticate the dedicated maintenance operation before service entry. Claim
 * owner is absent from the wire request and comes only from verified workload identity.</p>
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-state/projection-findings")
public final class DurableStateProjectionFindingController {

    private final DurableStateProjectionFindingService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the authenticated projection finding transport.
     *
     * @param service globally authorized finding application service
     * @param authenticator verified workload identity boundary
     */
    public DurableStateProjectionFindingController(
            DurableStateProjectionFindingService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Returns one bounded payload-free finding page.
     *
     * @param actionableOnly whether to exclude resolved findings and live claims
     * @param limit page bound from 1 through 1000
     * @param headers credential and dedicated maintenance purpose
     * @return payload-free owner queue page
     */
    @GetMapping
    public DurableStateProjectionFindingsResponse findings(
            @RequestParam(defaultValue = "true") boolean actionableOnly,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.findings(actionableOnly, limit, authenticator.authenticate(headers,
                IntegrationOperation.TEST_DURABLE_PROJECTION_MAINTENANCE));
    }

    /**
     * Claims one actionable finding for the verified workload actor.
     *
     * @param request finding, idempotency key, and bounded lease intent
     * @param headers credential and dedicated maintenance purpose
     * @return exact server-issued claim fence
     */
    @PostMapping("/claims")
    public DurableStateProjectionFindingClaimResponse claim(
            @RequestBody DurableStateProjectionFindingClaimRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.claim(request, authenticator.authenticate(headers,
                IntegrationOperation.TEST_DURABLE_PROJECTION_MAINTENANCE));
    }

    /**
     * Resolves one finding under its exact live claim fence.
     *
     * @param request exact claim and manual resolution intent
     * @param headers credential and dedicated maintenance purpose
     * @return immutable token-free resolution receipt
     */
    @PostMapping("/resolutions")
    public DurableStateProjectionFindingResolutionResponse resolve(
            @RequestBody DurableStateProjectionFindingResolutionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.resolve(request, authenticator.authenticate(headers,
                IntegrationOperation.TEST_DURABLE_PROJECTION_MAINTENANCE));
    }
}
