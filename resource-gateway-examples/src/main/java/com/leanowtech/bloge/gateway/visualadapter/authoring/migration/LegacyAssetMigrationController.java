package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyMigrationAssessment;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyApiResourceReauthorPreview;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyFixtureReauthorPreview;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyReusableFlowReauthorPreview;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authenticated, read-only adapter for the payload-free legacy compatibility inventory. */
@RestController
@RequestMapping("/api/authoring/migrations/legacy-assets")
public final class LegacyAssetMigrationController {
    private static final Pattern ASSESSMENT_ETAG = Pattern.compile("^\"sha256:[0-9a-f]{64}\"$");
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

    /**
     * Returns replayable classification evidence for the current inventory without changing legacy state.
     * An optional strong {@code If-Match} fences a replay to the exact assessed source snapshot.
     */
    @GetMapping(path = "/assessment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegacyMigrationAssessment> assessment(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_LEGACY_MIGRATION_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        LegacyMigrationAssessment assessment = module.assessment(trustedScope(context));
        String etag = "\"" + assessment.inventoryFingerprint() + "\"";
        if (ifMatch != null && !ASSESSMENT_ETAG.matcher(ifMatch).matches()) {
            throw problem(context, 400, "RG.AUTHORING.LEGACY_MIGRATION.ETAG_INVALID",
                    "The migration assessment replay fence must be one strong ETag.");
        }
        if (ifMatch != null && !etag.equals(ifMatch)) {
            throw problem(context, 412, "RG.AUTHORING.LEGACY_MIGRATION.ASSESSMENT_CHANGED",
                    "The legacy inventory changed after the supplied assessment.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .eTag(etag)
                .body(assessment);
    }

    /** Returns one connection-independent command that the author must visibly review and save. */
    @GetMapping(path = "/resources/{resourceId}:preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegacyApiResourceReauthorPreview> previewResource(
            @PathVariable String resourceId, @RequestHeader HttpHeaders headers, HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_LEGACY_MIGRATION_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        trustedScope(context);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(module.previewResource(resourceId));
    }

    /** Returns one fixture-free Flow command projected from an exact legacy Draft or Publication. */
    @GetMapping(path = "/flows/{sourceKind}/{sourceId}:preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegacyReusableFlowReauthorPreview> previewFlow(
            @PathVariable LegacyAssetMigrationInventory.Kind sourceKind,
            @PathVariable String sourceId,
            @RequestParam long revision,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_LEGACY_MIGRATION_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(module.previewFlow(trustedScope(context), sourceKind, sourceId, revision));
    }

    /** Returns reference classifications and the exact new Flow subject for explicit Fixture authoring. */
    @GetMapping(path = "/fixtures/{draftId}:preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegacyFixtureReauthorPreview> previewFixture(
            @PathVariable String draftId,
            @RequestParam long revision,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_LEGACY_MIGRATION_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(module.previewFixture(trustedScope(context), draftId, revision));
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

    private static IntegrationProblemException problem(
            IntegrationRequestContext context, int status, String code, String detail) {
        return new IntegrationProblemException(new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION, "urn:bloge:problem:legacy-migration-assessment",
                detail, status, code, false, context.correlationId(), Map.of()));
    }
}
