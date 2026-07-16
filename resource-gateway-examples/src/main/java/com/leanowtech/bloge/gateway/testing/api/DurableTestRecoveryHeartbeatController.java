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

/** Authenticated, profile-isolated transport for renewing an issued recovery fence. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions")
public class DurableTestRecoveryHeartbeatController {

    private final DurableTestRecoveryHeartbeatService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the recovery-heartbeat HTTP boundary.
     *
     * @param service authenticated recovery-heartbeat application service
     * @param authenticator verified workload identity boundary
     */
    public DurableTestRecoveryHeartbeatController(
            DurableTestRecoveryHeartbeatService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Renews one exact issued and still-live recovery fence.
     *
     * @param runId path-bound durable run identity
     * @param request exact source fence and caller idempotency key
     * @param headers authentication and explicit-purpose headers
     * @return payload-free successor fence
     */
    @PostMapping("/{runId}/heartbeats")
    public DurableTestRecoveryHeartbeatResponse heartbeat(
            @PathVariable String runId,
            @RequestBody DurableTestRecoveryHeartbeatRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.heartbeat(runId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_DURABLE_RECOVERY_CONTROL));
    }
}
