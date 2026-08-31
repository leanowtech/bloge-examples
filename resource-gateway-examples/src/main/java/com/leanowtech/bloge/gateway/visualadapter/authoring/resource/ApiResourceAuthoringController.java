package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringResult;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceSaveCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Thin authenticated HTTP adapter for the first API Resource save tracer. */
@RestController
@RequestMapping("/api/authoring/resources")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiResourceAuthoringController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final ApiResourceAuthoringFacade facade;
    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper strictMapper;

    /** Creates the adapter over the application facade and trusted identity boundary. */
    public ApiResourceAuthoringController(ApiResourceAuthoringFacade facade,
                                          IntegrationRequestAuthenticator authenticator,
                                          ObjectMapper mapper) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Returns the current or one exact committed Resource for an object-page deep link. */
    @GetMapping(path = "/{resourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec> read(
            @PathVariable String resourceId,
            @RequestParam(required = false) String revision,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest servletRequest) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_API_RESOURCE_READ);
        servletRequest.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        StoredApiResource stored = facade.read(trustedScope(context), resourceId,
                revision == null ? null : positiveRevision(revision, context.correlationId()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.ETAG, stored.receipt().strongEtag())
                .body(stored.resource());
    }

    /** Creates or updates one Resource under an explicit strong HTTP precondition. */
    @PutMapping(path = "/{resourceId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> save(@PathVariable String resourceId,
                                         @RequestHeader HttpHeaders headers,
                                         @RequestBody JsonNode commandWire,
                                         HttpServletRequest servletRequest) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_API_RESOURCE_WRITE);
        servletRequest.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        AuthoringScope scope = trustedScope(context);
        String idempotencyKey = idempotencyKey(headers, context.correlationId());
        ApiResourceAuthoringPrecondition precondition = precondition(headers, context.correlationId());
        ApiResourceSaveCommand command = command(commandWire, context.correlationId());
        ApiResourceAuthoringResult result = facade.save(new ApiResourceAuthoringRequest(
                scope, context.actorId(), resourceId, idempotencyKey, precondition, command));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.ETAG, result.stored().receipt().strongEtag())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.stored().receipt().body());
    }

    private ApiResourceSaveCommand command(JsonNode wire, String correlationId) {
        try {
            ApiResourceSaveCommand command = strictMapper.treeToValue(wire, ApiResourceSaveCommand.class);
            if (command == null) throw invalidRequest(correlationId);
            return command;
        } catch (IntegrationProblemException ex) {
            throw ex;
        } catch (RuntimeException | java.io.IOException ex) {
            throw invalidRequest(correlationId);
        }
    }

    /** Authenticates the same route before rejecting a missing or unsupported content type. */
    @PutMapping(path = "/{resourceId}", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> unsupportedMedia(@RequestHeader HttpHeaders headers,
                                                     HttpServletRequest servletRequest) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_API_RESOURCE_WRITE);
        servletRequest.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        throw invalid("RG.AUTHORING.API_RESOURCE.CONTENT_TYPE_REQUIRED",
                "API Resource commands must use application/json.", context.correlationId(), 415,
                "urn:bloge:problem:unsupported-authoring-media");
    }

    private static AuthoringScope trustedScope(IntegrationRequestContext context) {
        try {
            return new AuthoringScope(context.tenantId(), context.projectId(), context.environmentId());
        } catch (IllegalArgumentException ex) {
            throw invalid("RG.AUTHORING.API_RESOURCE.AUTHORITY_INVALID",
                    "The verified authoring identity does not contain a complete Resource scope.",
                    context.correlationId());
        }
    }

    private static String idempotencyKey(HttpHeaders headers, String correlationId) {
        List<String> values = headers.get("Idempotency-Key");
        if (values == null || values.size() != 1) {
            throw invalidRequest(correlationId);
        }
        String value = values.getFirst();
        if (value == null || value.length() > 160 || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalidRequest(correlationId);
        }
        return value;
    }

    private static ApiResourceAuthoringPrecondition precondition(HttpHeaders headers, String correlationId) {
        List<String> matches = headers.get(HttpHeaders.IF_MATCH);
        List<String> creates = headers.get(HttpHeaders.IF_NONE_MATCH);
        boolean hasMatch = matches != null && !matches.isEmpty();
        boolean hasCreate = creates != null && !creates.isEmpty();
        if (!hasMatch && !hasCreate) {
            throw invalid("RG.AUTHORING.API_RESOURCE.PRECONDITION_REQUIRED",
                    "If-None-Match: * is required for create; one strong If-Match is required for update.",
                    correlationId, 428, "urn:bloge:problem:authoring-precondition-required");
        }
        if (hasMatch == hasCreate) throw invalidRequest(correlationId);
        if (hasCreate) {
            if (creates.size() != 1 || !"*".equals(creates.getFirst())) throw invalidRequest(correlationId);
            return ApiResourceAuthoringPrecondition.create();
        }
        if (matches.size() != 1 || !StrongEtag.isValid(matches.getFirst())) throw invalidRequest(correlationId);
        return ApiResourceAuthoringPrecondition.matchStrongEtag(matches.getFirst());
    }

    private static IntegrationProblemException invalidRequest(String correlationId) {
        return invalid("RG.AUTHORING.API_RESOURCE.REQUEST_INVALID",
                "The API Resource request headers are malformed or incomplete.", correlationId);
    }

    private static long positiveRevision(String value, String correlationId) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) throw new NumberFormatException("revision must be positive");
            return revision;
        } catch (RuntimeException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private static IntegrationProblemException invalid(String code, String title, String correlationId) {
        return invalid(code, title, correlationId, 400, "urn:bloge:problem:bad-authoring-request");
    }

    private static IntegrationProblemException invalid(String code, String title, String correlationId,
                                                        int status, String type) {
        return new IntegrationProblemException(new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION, type, title, status, code, false,
                correlationId, java.util.Map.of()));
    }
}
