package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated payload-free collection of governed Fixture metadata. */
@RestController
@ConditionalOnBean(FixtureAssetCollectionService.class)
@RequestMapping("/api/visual/fixture-assets")
public final class FixtureAssetCollectionController {
    private final FixtureAssetCollectionService service;
    private final IntegrationRequestAuthenticator authenticator;

    /** Creates the authenticated collection endpoint. */
    public FixtureAssetCollectionController(
            FixtureAssetCollectionService service, IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Lists only metadata in the caller's verified scope. */
    @GetMapping
    public ResponseEntity<CorrectnessApiEnvelope<List<FixtureAssetCollectionService.FixtureAssetSummary>>> list(
            @RequestHeader HttpHeaders headers,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "" + FixtureAssetCollectionService.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_WORKSPACE_READ);
        if (limit < 1 || limit > FixtureAssetCollectionService.MAX_LIMIT
                || offset < 0 || offset > FixtureAssetCollectionService.MAX_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Fixture collection bounds are invalid");
        }
        EnterpriseScope scope = new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
        List<FixtureAssetCollectionService.FixtureAssetSummary> summaries = service.list(
                scope, activeOnly, limit, offset);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(CorrectnessApiEnvelope.of(identity.correlationId(), scope,
                        List.of("FIXTURE_ASSET_COLLECTION_READ_V1"), summaries));
    }
}
