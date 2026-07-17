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
 * Profile-isolated HTTP boundary for exact-checkpoint worker quarantine maintenance.
 *
 * <p>Every method authenticates a dedicated maintenance operation before service entry. Scope and
 * claim owner are absent from command JSON and come only from verified workload identity.</p>
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-state/worker-quarantines")
public final class DurableWorkerQuarantineController {

    private final DurableWorkerQuarantineService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the authenticated worker quarantine transport.
     *
     * @param service scoped maintenance application service
     * @param authenticator verified workload identity boundary
     */
    public DurableWorkerQuarantineController(
            DurableWorkerQuarantineService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Returns one bounded payload-free active quarantine page. */
    @GetMapping
    public DurableWorkerQuarantinesResponse quarantines(
            @RequestParam(defaultValue = "true") boolean actionableOnly,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.quarantines(actionableOnly, limit, identity(headers));
    }

    /** Returns one bounded immutable token-free manual action history page. */
    @GetMapping("/history")
    public DurableWorkerQuarantineHistoryResponse history(
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.history(limit, identity(headers));
    }

    /** Returns bounded token-free maker-checker evidence for approved discards. */
    @GetMapping("/approved-discards/history")
    public DurableWorkerQuarantineApprovedDiscardHistoryResponse discardHistory(
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.discardHistory(limit, identity(headers));
    }

    /** Claims one exact-checkpoint quarantine for the verified actor. */
    @PostMapping("/claims")
    public DurableWorkerQuarantineClaimResponse claim(
            @RequestBody DurableWorkerQuarantineClaimRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.claim(request, identity(headers));
    }

    /** Approves one exact live claim as an independent verified checker. */
    @PostMapping("/discard-approvals")
    public DurableWorkerQuarantineDiscardApprovalResponse approveDiscard(
            @RequestBody DurableWorkerQuarantineDiscardApprovalRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.approveDiscard(request, identity(headers));
    }

    /** Consumes an independent checker approval and discards one exact live claim. */
    @PostMapping("/approved-discards")
    public DurableWorkerQuarantineApprovedDiscardResponse discard(
            @RequestBody DurableWorkerQuarantineApprovedDiscardRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.discard(request, identity(headers));
    }

    /** Releases a live claim or replays a legacy discard receipt. */
    @PostMapping("/resolutions")
    public DurableWorkerQuarantineResolutionResponse resolve(
            @RequestBody DurableWorkerQuarantineResolutionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.resolve(request, identity(headers));
    }

    private com.leanowtech.bloge.gateway.integration.IntegrationRequestContext identity(
            HttpHeaders headers) {
        return authenticator.authenticate(headers,
                IntegrationOperation.TEST_DURABLE_WORKER_QUARANTINE_MAINTENANCE);
    }
}
