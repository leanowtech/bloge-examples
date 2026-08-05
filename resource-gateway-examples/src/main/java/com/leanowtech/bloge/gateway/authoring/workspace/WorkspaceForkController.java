package com.leanowtech.bloge.gateway.authoring.workspace;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated authoring endpoint for atomic, idempotent Workspace forks. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/authoring/workspace-forks")
public final class WorkspaceForkController {

    private final WorkspaceForkService service;
    private final IntegrationRequestAuthenticator authenticator;

    public WorkspaceForkController(
            WorkspaceForkService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Materializes one complete seed. Retries must retain the same key and request body. */
    @PostMapping
    public WorkspaceForkReceipt fork(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody WorkspaceForkCommand command,
            @RequestHeader HttpHeaders headers) {
        return service.fork(
                idempotencyKey,
                command,
                authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE));
    }
}
