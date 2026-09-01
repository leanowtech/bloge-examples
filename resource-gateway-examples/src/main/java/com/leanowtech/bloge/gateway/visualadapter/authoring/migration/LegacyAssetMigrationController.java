package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Authenticated, read-only adapter for the payload-free legacy compatibility inventory. */
@RestController
@RequestMapping("/api/authoring/migrations/legacy-assets")
public final class LegacyAssetMigrationController {
    private final LegacyAssetMigrationModule module;
    private final IntegrationRequestAuthenticator authenticator;

    public LegacyAssetMigrationController(LegacyAssetMigrationModule module,
                                          IntegrationRequestAuthenticator authenticator) {
        this.module = Objects.requireNonNull(module, "module");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /** Returns migration eligibility and repair reasons without exposing any legacy content. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegacyAssetMigrationInventory> inventory(
            @RequestHeader HttpHeaders headers, HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_LEGACY_MIGRATION_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(module.inventory(trustedScope(context)));
    }

    private static AuthoringScope trustedScope(IntegrationRequestContext context) {
        try {
            return new AuthoringScope(context.tenantId(), context.projectId(), context.environmentId());
        } catch (IllegalArgumentException failure) {
            throw new IntegrationProblemException(new IntegrationProblem(
                    IntegrationProblem.SCHEMA_VERSION, "urn:bloge:problem:bad-authoring-request",
                    "The verified identity does not contain a complete migration scope.", 400,
                    "RG.AUTHORING.LEGACY_MIGRATION.AUTHORITY_INVALID", false,
                    context.correlationId(), Map.of()));
        }
    }
}
