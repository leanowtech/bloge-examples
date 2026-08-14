package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated HTTP surface for durable Capability Proposal authoring. */
@RestController
@RequestMapping("/api/business-mirror/proposals")
public final class CapabilityProposalController {
    private final CapabilityProposalAuthoringService service;
    private final IntegrationRequestAuthenticator authenticator;

    public CapabilityProposalController(
            CapabilityProposalAuthoringService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ResponseEntity<CapabilityProposalSaveReceipt> create(
            @RequestBody CapabilityProposalDraft draft,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
            @RequestHeader HttpHeaders headers) {
        return response(service.create(draft, idempotencyKey,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_WRITE)),
                HttpStatus.CREATED);
    }

    @PutMapping("/{proposalId}")
    public ResponseEntity<CapabilityProposalSaveReceipt> save(
            @PathVariable String proposalId,
            @RequestParam long expectedRevision,
            @RequestBody CapabilityProposalDraft draft,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
            @RequestHeader HttpHeaders headers) {
        return response(service.save(proposalId, expectedRevision, draft, idempotencyKey,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_WRITE)),
                HttpStatus.OK);
    }

    @GetMapping
    public CapabilityProposalPage list(
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.list(after, limit,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_READ));
    }

    @GetMapping("/{proposalId}")
    public StoredCapabilityProposalDraft find(
            @PathVariable String proposalId, @RequestHeader HttpHeaders headers) {
        return service.find(proposalId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_READ));
    }

    @GetMapping("/{proposalId}/revisions")
    public List<StoredCapabilityProposalDraft> revisions(
            @PathVariable String proposalId, @RequestHeader HttpHeaders headers) {
        return service.revisions(proposalId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_READ));
    }

    @GetMapping("/{proposalId}/revisions/{revision}")
    public StoredCapabilityProposalDraft findRevision(
            @PathVariable String proposalId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        return service.findRevision(proposalId, revision,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_READ));
    }

    private IntegrationRequestContext context(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }

    private static ResponseEntity<CapabilityProposalSaveReceipt> response(
            CapabilityProposalSaveCoordinator.Outcome outcome, HttpStatus status) {
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .header("ETag", '"' + outcome.receipt().result().draftFingerprint() + '"')
                .body(outcome.receipt());
    }
}
