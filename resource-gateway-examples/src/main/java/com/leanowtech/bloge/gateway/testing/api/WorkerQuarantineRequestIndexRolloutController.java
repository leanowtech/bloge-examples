package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated HTTP boundary for short-lived request-index rollout proofs. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-state/worker-quarantines/request-index")
public final class WorkerQuarantineRequestIndexRolloutController {

    private final WorkerQuarantineRequestIndexRolloutService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the profile-isolated rollout-proof transport.
     *
     * @param service signed per-replica proof boundary
     * @param authenticator verified workload identity adapter
     */
    public WorkerQuarantineRequestIndexRolloutController(
            WorkerQuarantineRequestIndexRolloutService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Returns one signed challenge-bound proof for this exact process start. */
    @PostMapping("/replica-proofs")
    public WorkerQuarantineRequestIndexReplicaProof prove(
            @RequestBody WorkerQuarantineRequestIndexReplicaProofRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.prove(request, identity(headers));
    }

    private IntegrationRequestContext identity(HttpHeaders headers) {
        return authenticator.authenticate(headers,
                IntegrationOperation.TEST_DURABLE_REQUEST_INDEX_ROLLOUT);
    }
}
