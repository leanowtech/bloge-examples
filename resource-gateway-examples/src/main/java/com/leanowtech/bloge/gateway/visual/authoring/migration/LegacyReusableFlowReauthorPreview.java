package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;

import java.util.LinkedHashSet;
import java.util.List;

/** Safe command preview for visibly rebuilding one legacy Graph Draft or Publication as a reusable Flow. */
public record LegacyReusableFlowReauthorPreview(
        String schemaVersion,
        Source source,
        String suggestedFlowId,
        ReusableFlowCommand suggestedFlow,
        int fixtureReferences,
        List<Diagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.legacyReusableFlowReauthorPreview.v1";

    public LegacyReusableFlowReauthorPreview {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || source == null || suggestedFlow == null
                || !identifier(suggestedFlowId) || fixtureReferences < 0) {
            throw new IllegalArgumentException("A legacy Flow preview requires exact payload-free authority");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (diagnostics.isEmpty() || diagnostics.size() > 8
                || new LinkedHashSet<>(diagnostics).size() != diagnostics.size()) {
            throw new IllegalArgumentException("A legacy Flow preview requires bounded unique diagnostics");
        }
    }

    /** Exact source coordinate; the preview never substitutes another draft or publication. */
    public record Source(LegacyAssetMigrationInventory.Kind kind, String sourceId, long sourceRevision) {
        public Source {
            if ((kind != LegacyAssetMigrationInventory.Kind.REUSABLE_FLOW_DRAFT
                    && kind != LegacyAssetMigrationInventory.Kind.REUSABLE_FLOW_VERSION)
                    || !identifier(sourceId) || sourceRevision < 1) {
                throw new IllegalArgumentException("A legacy Flow preview requires an exact source coordinate");
            }
        }
    }

    /** Bounded author-facing review requirement; never contains legacy graph or Fixture material. */
    public record Diagnostic(String code, String message) {
        public Diagnostic {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")
                    || message == null || message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("A legacy Flow diagnostic must be bounded and code-derived");
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.length() <= 128
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
