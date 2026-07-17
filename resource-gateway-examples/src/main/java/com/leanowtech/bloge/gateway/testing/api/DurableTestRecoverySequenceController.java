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

import java.util.Objects;

/** Authenticated, profile-isolated transport for bounded automatic recovery sequences. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions")
public class DurableTestRecoverySequenceController {

    private final DurableTestRecoverySequenceService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the bounded recovery-sequence HTTP boundary.
     *
     * @param service authenticated sequence orchestration service
     * @param authenticator verified workload identity boundary
     */
    public DurableTestRecoverySequenceController(
            DurableTestRecoverySequenceService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Consumes ordered signals until the graph terminates or the supplied program is exhausted.
     *
     * @param runId path-bound durable run identity
     * @param request exact initial fence and complete ordered signal program
     * @param headers authentication and explicit-purpose headers
     * @return ordered payload-free committed step projections
     */
    @PostMapping("/{runId}/recovery-sequences")
    public DurableTestRecoverySequenceResponse advance(
            @PathVariable String runId,
            @RequestBody DurableTestRecoverySequenceRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.advance(runId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_DURABLE_RECOVERY_SEQUENCE));
    }
}
