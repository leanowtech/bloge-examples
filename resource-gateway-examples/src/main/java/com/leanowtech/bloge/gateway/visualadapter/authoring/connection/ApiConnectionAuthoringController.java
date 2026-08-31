package com.leanowtech.bloge.gateway.visualadapter.authoring.connection;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRead;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringResult;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Thin authenticated adapter for reusable, payload-free API Connections. */
@RestController
@RequestMapping("/api/authoring/connections")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiConnectionAuthoringController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final ApiConnectionAuthoringFacade facade;
    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper strictMapper;

    /** Creates the adapter over one lifecycle-complete Connection facade. */
    public ApiConnectionAuthoringController(ApiConnectionAuthoringFacade facade,
                                             IntegrationRequestAuthenticator authenticator,
                                             ObjectMapper mapper) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Returns the current payload-free Connection view and opaque strong ETag. */
    @GetMapping(path = "/{connectionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiConnectionView> read(@PathVariable String connectionId,
                                                   @RequestHeader HttpHeaders headers,
                                                   HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_API_CONNECTION_READ, request);
        ApiConnectionAuthoringRead result = facade.read(trustedScope(context), connectionId);
        return response(result.view(), result.strongEtag(), null);
    }

    /** Creates or updates one Connection under an explicit HTTP precondition. */
    @PutMapping(path = "/{connectionId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiConnectionView> save(@PathVariable String connectionId,
                                                   @RequestHeader HttpHeaders headers,
                                                   @RequestBody JsonNode commandWire,
                                                   HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_API_CONNECTION_WRITE, request);
        ApiConnectionAuthoringResult result = facade.save(new ApiConnectionAuthoringRequest(
                trustedScope(context), context.actorId(), connectionId,
                idempotencyKey(headers, context.correlationId()),
                precondition(headers, context.correlationId()),
                command(commandWire, context.correlationId())));
        return response(result.view(), result.strongEtag(), result.replayed());
    }

    /** Authenticates before rejecting a missing or unsupported content type. */
    @PutMapping(path = "/{connectionId}", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiConnectionView> unsupportedMedia(@RequestHeader HttpHeaders headers,
                                                               HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_API_CONNECTION_WRITE, request);
        throw invalid("RG.AUTHORING.API_CONNECTION.CONTENT_TYPE_REQUIRED",
                "API Connection commands must use application/json.", context.correlationId(), 415,
                "urn:bloge:problem:unsupported-authoring-media");
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation,
                                                     HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(headers, operation);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return context;
    }

    private ApiConnectionCommand command(JsonNode wire, String correlationId) {
        try {
            ApiConnectionCommand command = strictMapper.treeToValue(wire, ApiConnectionCommand.class);
            if (command == null) throw invalidRequest(correlationId);
            return command;
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private static ResponseEntity<ApiConnectionView> response(ApiConnectionView view, String etag,
                                                               Boolean replayed) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.ETAG, etag);
        if (replayed != null) response.header("Idempotency-Replayed", replayed.toString());
        return response.body(view);
    }

    private static AuthoringScope trustedScope(IntegrationRequestContext context) {
        try {
            return new AuthoringScope(context.tenantId(), context.projectId(), context.environmentId());
        } catch (IllegalArgumentException failure) {
            throw invalid("RG.AUTHORING.API_CONNECTION.AUTHORITY_INVALID",
                    "The verified authoring identity does not contain a complete Connection scope.",
                    context.correlationId(), 400, "urn:bloge:problem:bad-authoring-request");
        }
    }

    private static String idempotencyKey(HttpHeaders headers, String correlationId) {
        List<String> values = headers.get("Idempotency-Key");
        if (values == null || values.size() != 1) throw invalidRequest(correlationId);
        String value = values.getFirst();
        if (value == null || value.length() > 160 || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalidRequest(correlationId);
        }
        return value;
    }

    private static ApiConnectionAuthoringPrecondition precondition(HttpHeaders headers, String correlationId) {
        List<String> matches = headers.get(HttpHeaders.IF_MATCH);
        List<String> creates = headers.get(HttpHeaders.IF_NONE_MATCH);
        boolean hasMatch = matches != null && !matches.isEmpty();
        boolean hasCreate = creates != null && !creates.isEmpty();
        if (!hasMatch && !hasCreate) {
            throw invalid("RG.AUTHORING.API_CONNECTION.PRECONDITION_REQUIRED",
                    "If-None-Match: * is required for create; one strong If-Match is required for update.",
                    correlationId, 428, "urn:bloge:problem:authoring-precondition-required");
        }
        if (hasMatch == hasCreate) throw invalidRequest(correlationId);
        if (hasCreate) {
            if (creates.size() != 1 || !"*".equals(creates.getFirst())) throw invalidRequest(correlationId);
            return ApiConnectionAuthoringPrecondition.create();
        }
        if (matches.size() != 1 || !StrongEtag.isValid(matches.getFirst())) throw invalidRequest(correlationId);
        return ApiConnectionAuthoringPrecondition.matchStrongEtag(matches.getFirst());
    }

    private static IntegrationProblemException invalidRequest(String correlationId) {
        return invalid("RG.AUTHORING.API_CONNECTION.REQUEST_INVALID",
                "The API Connection request headers or body are malformed.", correlationId, 400,
                "urn:bloge:problem:bad-authoring-request");
    }

    private static IntegrationProblemException invalid(String code, String title, String correlationId,
                                                        int status, String type) {
        return new IntegrationProblemException(new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION, type, title, status, code, false,
                correlationId, java.util.Map.of()));
    }
}
