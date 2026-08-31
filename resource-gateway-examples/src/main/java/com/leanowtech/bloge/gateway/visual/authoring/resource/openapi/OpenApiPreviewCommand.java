package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/** Side-effect-free command for previewing OpenAPI operations as API Resource drafts. */
public record OpenApiPreviewCommand(String schemaVersion, Source source, List<String> operationIds) {
    public static final String SCHEMA_VERSION = "bloge.openApiPreviewCommand.v1";

    public OpenApiPreviewCommand {
        operationIds = operationIds == null ? List.of() : List.copyOf(operationIds);
    }

    /** Inline and future authenticated remote OpenAPI sources. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Inline.class, name = "INLINE"),
            @JsonSubTypes.Type(value = Remote.class, name = "REMOTE")
    })
    public sealed interface Source permits Inline, Remote {
    }

    /** Inline OpenAPI JSON or YAML. The document is deliberately omitted from {@link #toString()}. */
    public record Inline(String documentText) implements Source {
        @Override
        public String toString() {
            return "Inline[documentText=<redacted>, length="
                    + (documentText == null ? 0 : documentText.length()) + "]";
        }
    }

    /** Future remote source. Fetching remains disabled until an authenticated egress seam exists. */
    public record Remote(String url, @JsonInclude(JsonInclude.Include.NON_NULL) String connectionId)
            implements Source {
    }
}
