package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowModule;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowStoredDraft;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Thin authenticated HTTP adapter for reusable Tool/Solution drafts. */
@RestController
@RequestMapping("/api/authoring/flows")
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public final class ReusableFlowAuthoringController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final ReusableFlowModule module;
    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper strictMapper;

    public ReusableFlowAuthoringController(ReusableFlowModule module,
                                           IntegrationRequestAuthenticator authenticator,
                                           ObjectMapper mapper) {
        this.module = Objects.requireNonNull(module, "module");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Returns the current or one exact committed Flow draft and strong ETag. */
    @GetMapping(path = "/{flowId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReusableFlowDraft> read(@PathVariable String flowId,
                                                   @RequestParam(required = false) String revision,
                                                   @RequestHeader HttpHeaders headers,
                                                   HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_REUSABLE_FLOW_READ, request);
        AuthoringScope scope = trustedScope(context);
        ReusableFlowStoredDraft stored = (revision == null
                ? module.findHeadStored(scope, flowId)
                : module.findRevisionStored(scope, flowId, positiveRevision(revision, context.correlationId())))
                .orElseThrow(() -> new com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure(
                        com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.ETAG, stored.strongEtag()).body(stored.draft());
    }

    /** Compiles and atomically creates or updates one Flow under a strong precondition. */
    @PutMapping(path = "/{flowId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReusableFlowSaveReceipt> save(
            @PathVariable String flowId, @RequestHeader HttpHeaders headers,
            @RequestBody JsonNode commandWire, HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_REUSABLE_FLOW_WRITE, request);
        ReusableFlowSaveResult result = module.save(trustedScope(context), context.actorId(), flowId,
                precondition(headers, context.correlationId()),
                idempotencyKey(headers, context.correlationId()),
                command(commandWire, context.correlationId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.ETAG, result.strongEtag())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.receipt());
    }

    /** Authenticates before rejecting a missing or unsupported content type. */
    @PutMapping(path = "/{flowId}", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReusableFlowSaveReceipt> unsupportedMedia(
            @RequestHeader HttpHeaders headers, HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_REUSABLE_FLOW_WRITE, request);
        throw invalid("RG.AUTHORING.REUSABLE_FLOW.CONTENT_TYPE_REQUIRED",
                "Reusable Flow commands must use application/json.", context.correlationId(), 415,
                "urn:bloge:problem:unsupported-authoring-media");
    }

    /** Publishes one exact readable Draft as an immutable catalog version. */
    @PostMapping(path = "/{flowId}:publish", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReusableFlowPublishReceipt> publish(
            @PathVariable String flowId, @RequestHeader HttpHeaders headers,
            @RequestBody JsonNode commandWire, HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_REUSABLE_FLOW_WRITE, request);
        ReusableFlowPublishResult result = module.publish(trustedScope(context), context.actorId(), flowId,
                idempotencyKey(headers, context.correlationId()),
                publishCommand(commandWire, context.correlationId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.receipt());
    }

    /** Returns the server-authoritative latest immutable version for one Flow. */
    @GetMapping(path = "/{flowId}/versions/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReusableFlowVersion> latestVersion(
            @PathVariable String flowId, @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_REUSABLE_FLOW_READ, request);
        ReusableFlowVersion version = module.findLatestVersion(trustedScope(context), flowId)
                .orElseThrow(() -> notFound(context.correlationId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(version);
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation,
                                                     HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(headers, operation);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return context;
    }

    private ReusableFlowCommand command(JsonNode wire, String correlationId) {
        try {
            ReusableFlowCommand command = strictMapper.treeToValue(wire, ReusableFlowCommand.class);
            if (command == null) throw invalidRequest(correlationId);
            return command;
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private ReusableFlowPublishCommand publishCommand(JsonNode wire, String correlationId) {
        try {
            ReusableFlowPublishCommand command = strictMapper.treeToValue(
                    wire, ReusableFlowPublishCommand.class);
            if (command == null) throw invalidRequest(correlationId);
            return command;
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private static ReusableFlowPrecondition precondition(HttpHeaders headers, String correlationId) {
        List<String> matches = headers.get(HttpHeaders.IF_MATCH);
        List<String> creates = headers.get(HttpHeaders.IF_NONE_MATCH);
        boolean hasMatch = matches != null && !matches.isEmpty();
        boolean hasCreate = creates != null && !creates.isEmpty();
        if (!hasMatch && !hasCreate) {
            throw invalid("RG.AUTHORING.REUSABLE_FLOW.PRECONDITION_REQUIRED",
                    "If-None-Match: * is required for create; one strong If-Match is required for update.",
                    correlationId, 428, "urn:bloge:problem:authoring-precondition-required");
        }
        if (hasMatch == hasCreate) throw invalidRequest(correlationId);
        if (hasCreate) {
            if (creates.size() != 1 || !"*".equals(creates.getFirst())) throw invalidRequest(correlationId);
            return ReusableFlowPrecondition.create();
        }
        try {
            if (matches.size() != 1) throw new IllegalArgumentException("multiple ETags");
            return ReusableFlowPrecondition.matchStrongEtag(matches.getFirst());
        } catch (RuntimeException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private static String idempotencyKey(HttpHeaders headers, String correlationId) {
        List<String> values = headers.get("Idempotency-Key");
        if (values == null || values.size() != 1 || values.getFirst() == null
                || values.getFirst().length() > 160
                || !IDEMPOTENCY_KEY.matcher(values.getFirst()).matches()) {
            throw invalidRequest(correlationId);
        }
        return values.getFirst();
    }

    private static int positiveRevision(String value, String correlationId) {
        try {
            int revision = Integer.parseInt(value);
            if (revision < 1) throw new NumberFormatException("revision must be positive");
            return revision;
        } catch (RuntimeException failure) {
            throw invalidRequest(correlationId);
        }
    }

    private static AuthoringScope trustedScope(IntegrationRequestContext context) {
        try {
            return new AuthoringScope(context.tenantId(), context.projectId(), context.environmentId());
        } catch (IllegalArgumentException failure) {
            throw invalidRequest(context.correlationId());
        }
    }

    private static IntegrationProblemException invalidRequest(String correlationId) {
        return invalid("RG.AUTHORING.REUSABLE_FLOW.REQUEST_INVALID",
                "The reusable Flow request headers or body are malformed.", correlationId, 400,
                "urn:bloge:problem:bad-authoring-request");
    }

    private static IntegrationProblemException notFound(String correlationId) {
        return invalid("RG.AUTHORING.REUSABLE_FLOW.VERSION_NOT_FOUND",
                "No published Flow Version exists for this Flow.", correlationId, 404,
                "urn:bloge:problem:authoring-resource-not-found");
    }

    private static IntegrationProblemException invalid(String code, String title, String correlationId,
                                                        int status, String type) {
        return new IntegrationProblemException(new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION, type, title, status, code, false,
                correlationId, java.util.Map.of()));
    }
}
