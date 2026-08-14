package com.leanowtech.bloge.gateway.businessmirror.migration;

import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageSaveCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageSaveReceipt;
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
import org.springframework.web.bind.annotation.RestController;

/** Authenticated migration previews and incremental imports for existing built-in Graphs. */
@RestController
@RequestMapping("/api/business-mirror/legacy-graphs")
public final class LegacyGraphPackageController {
    private final LegacyGraphPackageProjector projector;
    private final IntegrationRequestAuthenticator authenticator;

    public LegacyGraphPackageController(
            LegacyGraphPackageProjector projector,
            IntegrationRequestAuthenticator authenticator) {
        this.projector = projector;
        this.authenticator = authenticator;
    }

    @GetMapping
    public LegacyGraphPackageProjectionCatalog catalog(
            @RequestHeader HttpHeaders headers) {
        return projector.catalog(context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    @GetMapping("/{graphName}")
    public LegacyGraphPackageProjection preview(
            @PathVariable String graphName,
            @RequestHeader HttpHeaders headers) {
        return projector.preview(graphName,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ));
    }

    @PostMapping("/{graphName}/packages")
    public ResponseEntity<DomainCapabilityPackageSaveReceipt> importPackage(
            @PathVariable String graphName,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String idempotencyKey,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_WRITE);
        LegacyGraphPackageProjector.ImportOutcome imported =
                projector.importPackage(graphName, idempotencyKey, identity);
        LegacyGraphPackageProjection projection = imported.projection();
        DomainCapabilityPackageSaveCoordinator.Outcome outcome = imported.saveOutcome();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .header("ETag", '"' + outcome.receipt().result().draftFingerprint() + '"')
                .header("Legacy-Projection-Fingerprint", projection.projectionFingerprint())
                .header(HttpHeaders.LOCATION,
                        "/api/business-mirror/packages/" + projection.packageDraft().packageId())
                .body(outcome.receipt());
    }

    private IntegrationRequestContext context(
            HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
