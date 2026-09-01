package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalogItem;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Trusted-scope HTTP read model for selecting exact reusable Flow dependencies. */
@RestController
@RequestMapping("/api/authoring/catalog")
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public final class ComposableCatalogController {
    private final ComposableCatalog catalog;
    private final IntegrationRequestAuthenticator authenticator;

    public ComposableCatalogController(ComposableCatalog catalog,
                                       IntegrationRequestAuthenticator authenticator) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /** Returns at most 100 payload-free choices, pinned to committed immutable coordinates. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ComposableCatalogItem>> list(
            @RequestParam(name = "kind", required = false) List<ComposableCatalog.Kind> kinds,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers, HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_REUSABLE_FLOW_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "catalog limit must be between 1 and 100");
        }
        Set<ComposableCatalog.Kind> selected = kinds == null || kinds.isEmpty()
                ? Set.of(ComposableCatalog.Kind.API_RESOURCE, ComposableCatalog.Kind.FLOW_VERSION)
                : new LinkedHashSet<>(kinds);
        AuthoringScope scope = new AuthoringScope(
                context.tenantId(), context.projectId(), context.environmentId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(catalog.list(scope, selected, limit));
    }
}
