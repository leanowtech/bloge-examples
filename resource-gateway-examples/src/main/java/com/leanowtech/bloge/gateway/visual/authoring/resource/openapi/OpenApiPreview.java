package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;

import java.util.List;

/** Immutable OpenAPI preview returned before any Resource is persisted. */
public record OpenApiPreview(String schemaVersion, String discoveryId, List<Operation> operations) {
    public static final String SCHEMA_VERSION = "bloge.openApiPreview.v1";

    public OpenApiPreview {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** One importable operation and its standard Resource save command. */
    public record Operation(String operationId, String method, String path,
                            ApiResourceCommand suggestedResource, List<Diagnostic> diagnostics) {
        public Operation {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /** Bounded, non-payload diagnostic for a projected operation. */
    public record Diagnostic(String code, String message) {
    }
}
