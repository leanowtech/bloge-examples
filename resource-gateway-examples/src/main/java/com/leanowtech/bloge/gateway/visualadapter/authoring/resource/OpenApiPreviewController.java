package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreview;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewModule;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Thin authenticated adapter for side-effect-free OpenAPI Resource preview. */
@RestController
@RequestMapping("/api/authoring")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class OpenApiPreviewController {
    private final OpenApiPreviewModule module;
    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper strictMapper;

    /** Creates the adapter over one preview module and the trusted identity boundary. */
    public OpenApiPreviewController(OpenApiPreviewModule module,
                                    IntegrationRequestAuthenticator authenticator,
                                    ObjectMapper mapper) {
        this.module = Objects.requireNonNull(module, "module");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Previews inline OpenAPI operations without persistence or network egress. */
    @PostMapping(path = "/resources:preview-openapi", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OpenApiPreview> preview(@RequestHeader HttpHeaders headers,
                                                  @RequestBody JsonNode commandWire,
                                                  HttpServletRequest servletRequest) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_API_RESOURCE_PREVIEW);
        servletRequest.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        OpenApiPreviewCommand command;
        try {
            command = strictMapper.treeToValue(commandWire, OpenApiPreviewCommand.class);
        } catch (RuntimeException | java.io.IOException ex) {
            throw invalid(context.correlationId());
        }
        if (command == null) throw invalid(context.correlationId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(module.preview(command));
    }

    private static IntegrationProblemException invalid(String correlationId) {
        return new IntegrationProblemException(new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION, "urn:bloge:problem:bad-authoring-request",
                "OpenAPI preview request is invalid", 400,
                "RG.AUTHORING.OPENAPI.REQUEST_INVALID", false, correlationId, Map.of()));
    }
}
