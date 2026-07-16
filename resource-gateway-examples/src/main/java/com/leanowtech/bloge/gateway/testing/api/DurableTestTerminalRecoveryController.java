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

/** Authenticated, profile-isolated transport for one server-owned terminal recovery execution. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions")
public class DurableTestTerminalRecoveryController {

    private final DurableTestTerminalRecoveryService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the terminal-recovery HTTP boundary.
     *
     * @param service authenticated terminal-recovery application service
     * @param authenticator verified workload identity boundary
     */
    public DurableTestTerminalRecoveryController(
            DurableTestTerminalRecoveryService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Executes one signal and commits only when the isolated engine reaches terminal state.
     *
     * @param runId path-bound durable run identity
     * @param request exact source fence, idempotency key, and bounded signal
     * @param headers authentication and explicit-purpose headers
     * @return payload-free terminal receipt projection
     */
    @PostMapping("/{runId}/terminal-recoveries")
    public DurableTestTerminalRecoveryResponse recover(
            @PathVariable String runId,
            @RequestBody DurableTestTerminalRecoveryRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.recover(runId, request,
                authenticator.authenticate(headers,
                        IntegrationOperation.TEST_DURABLE_RECOVERY_CONTROL));
    }
}
