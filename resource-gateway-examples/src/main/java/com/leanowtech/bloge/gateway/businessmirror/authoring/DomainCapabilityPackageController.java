package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
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

/** Authenticated HTTP surface for durable Business Mirror Package authoring. */
@RestController
@RequestMapping("/api/business-mirror/packages")
public final class DomainCapabilityPackageController {
    private final DomainCapabilityPackageAuthoringService service;
    private final IntegrationRequestAuthenticator authenticator;

    public DomainCapabilityPackageController(
            DomainCapabilityPackageAuthoringService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ResponseEntity<DomainCapabilityPackageSaveReceipt> create(
            @RequestBody DomainCapabilityPackageDraft draft,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
            @RequestHeader HttpHeaders headers) {
        return response(service.create(draft, idempotencyKey,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_WRITE)), HttpStatus.CREATED);
    }

    @PutMapping("/{packageId}")
    public ResponseEntity<DomainCapabilityPackageSaveReceipt> save(
            @PathVariable String packageId,
            @RequestParam long expectedRevision,
            @RequestBody DomainCapabilityPackageDraft draft,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
            @RequestHeader HttpHeaders headers) {
        return response(service.save(packageId, expectedRevision, draft, idempotencyKey,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_WRITE)), HttpStatus.OK);
    }

    @GetMapping
    public DomainCapabilityPackagePage list(
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.list(after, limit,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    @GetMapping("/{packageId}")
    public StoredDomainCapabilityPackageDraft find(
            @PathVariable String packageId,
            @RequestHeader HttpHeaders headers) {
        return service.find(packageId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    @GetMapping("/{packageId}/revisions")
    public List<StoredDomainCapabilityPackageDraft> revisions(
            @PathVariable String packageId,
            @RequestHeader HttpHeaders headers) {
        return service.revisions(packageId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    @GetMapping("/{packageId}/revisions/{revision}")
    public StoredDomainCapabilityPackageDraft findRevision(
            @PathVariable String packageId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        return service.findRevision(packageId, revision,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    private IntegrationRequestContext context(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }

    private static ResponseEntity<DomainCapabilityPackageSaveReceipt> response(
            DomainCapabilityPackageSaveCoordinator.Outcome outcome, HttpStatus status) {
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .header("ETag", '"' + outcome.receipt().result().draftFingerprint() + '"')
                .body(outcome.receipt());
    }
}
