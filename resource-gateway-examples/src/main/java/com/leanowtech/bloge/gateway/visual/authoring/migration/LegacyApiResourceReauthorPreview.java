package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;

import java.util.LinkedHashSet;
import java.util.List;

/** Safe, connection-independent command preview for visibly re-authoring one legacy API Resource. */
public record LegacyApiResourceReauthorPreview(
        String schemaVersion,
        Source source,
        ApiResourceCommand suggestedResource,
        List<Diagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.legacyApiResourceReauthorPreview.v1";

    public LegacyApiResourceReauthorPreview {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || source == null || suggestedResource == null) {
            throw new IllegalArgumentException("A legacy Resource preview requires source and command authority");
        }
        suggestedResource = suggestedResource.copy();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (diagnostics.isEmpty() || diagnostics.size() > 8
                || new LinkedHashSet<>(diagnostics).size() != diagnostics.size()) {
            throw new IllegalArgumentException("A legacy Resource preview requires bounded unique diagnostics");
        }
    }

    @Override
    public ApiResourceCommand suggestedResource() {
        return suggestedResource.copy();
    }

    /** Exact legacy coordinate; descriptor and design-contract stores currently have no revision authority. */
    public record Source(String kind, String resourceId, long sourceRevision) {
        public Source {
            if (!"API_RESOURCE".equals(kind) || resourceId == null || resourceId.length() > 128
                    || !resourceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*") || sourceRevision != 0) {
                throw new IllegalArgumentException("A legacy Resource preview requires an exact source coordinate");
            }
        }
    }

    /** Bounded author-facing review requirement; never contains legacy transport or payload material. */
    public record Diagnostic(String code, String message) {
        public Diagnostic {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")
                    || message == null || message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("A legacy Resource diagnostic must be bounded and code-derived");
            }
        }
    }
}
