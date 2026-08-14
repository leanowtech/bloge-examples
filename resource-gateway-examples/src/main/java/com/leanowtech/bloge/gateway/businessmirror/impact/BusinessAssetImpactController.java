package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated impact-analysis and reverse-index maintenance endpoints. */
@RestController
@RequestMapping
public final class BusinessAssetImpactController {
    private final BusinessAssetImpactService service;
    private final IntegrationRequestAuthenticator authenticator;

    public BusinessAssetImpactController(
            BusinessAssetImpactService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @GetMapping({
            "/api/business-mirror/business-assets/{kind}/{id}/impact",
            "/api/integration/business-assets/{kind}/{id}/impact"
    })
    public BusinessAssetImpactReport impact(
            @PathVariable String kind,
            @PathVariable String id,
            @RequestParam(defaultValue = "") String authority,
            @RequestParam(defaultValue = "") String afterPackageId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.query(kind, id, authority, afterPackageId, limit,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_IMPACT_READ));
    }

    @PostMapping("/api/business-mirror/impact-index/rebuild")
    public BusinessAssetImpactRebuildReport rebuild(
            @RequestParam(defaultValue = "") String afterPackageId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.rebuild(afterPackageId, limit,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_IMPACT_REBUILD));
    }

    private IntegrationRequestContext context(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
