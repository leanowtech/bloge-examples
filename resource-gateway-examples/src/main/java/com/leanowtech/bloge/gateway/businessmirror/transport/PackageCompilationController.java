package com.leanowtech.bloge.gateway.businessmirror.transport;

import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationService;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated HTTP surface for deterministic, durable Package compilation. */
@RestController
@RequestMapping("/api/business-mirror/packages/{packageId}")
public final class PackageCompilationController {
    private final PackageCompilationService service;
    private final IntegrationRequestAuthenticator authenticator;

    public PackageCompilationController(
            PackageCompilationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/compile")
    public ResponseEntity<PackageCompilationReceipt> compile(
            @PathVariable String packageId,
            @RequestParam long sourceRevision,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
            @RequestHeader HttpHeaders headers) {
        PackageCompilationCoordinator.Outcome outcome = service.compile(packageId, sourceRevision,
                idempotencyKey, context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_COMPILE));
        PackageCompilationReceipt receipt = outcome.receipt();
        String fingerprint = receipt.snapshot() == null
                ? receipt.readiness().fingerprint() : receipt.snapshot().fingerprint();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .header("Compilation-Status", receipt.readiness().status().name())
                .header("ETag", '"' + fingerprint + '"')
                .body(receipt);
    }

    @GetMapping("/compilations/{compilationRevision}")
    public PackageCompilationReceipt find(
            @PathVariable String packageId,
            @PathVariable long compilationRevision,
            @RequestHeader HttpHeaders headers) {
        return service.find(packageId, compilationRevision,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    private IntegrationRequestContext context(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
