package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated ANEKE Package registry export and governance projection transport. */
@RestController
@RequestMapping("/api/integration/domain-capability-packages")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class PackageGovernanceIntegrationController {
    private final PackageGovernanceIntegrationService service;
    private final IntegrationRequestAuthenticator authenticator;

    public PackageGovernanceIntegrationController(
            PackageGovernanceIntegrationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @GetMapping("/{packageId}/revisions/{revision}/registry-ingest-bundle")
    public PackageRegistryIngestBundle exportBundle(
            @PathVariable String packageId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        return service.exportBundle(packageId, revision,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_REGISTRY_EXPORT));
    }

    @PostMapping("/{packageId}/governance-projections")
    public PackageGovernanceProjectionReceipt ingest(
            @PathVariable String packageId,
            @RequestBody DomainCapabilityPackageGovernanceProjection projection,
            @RequestHeader HttpHeaders headers) {
        return service.ingest(packageId, projection,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_GOVERNANCE_FEEDBACK));
    }

    @GetMapping("/{packageId}/governance-projection")
    public DomainCapabilityPackageGovernanceView view(
            @PathVariable String packageId,
            @RequestHeader HttpHeaders headers) {
        return service.view(packageId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_GOVERNANCE_READ));
    }

    private IntegrationRequestContext context(
            HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
