package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.util.LinkedHashSet;
import java.util.List;

/** Payload-free review plan for replacing legacy node Fixtures with newly authored Fixture Cases. */
public record LegacyFixtureReauthorPreview(
        String schemaVersion,
        Source source,
        String targetFlowId,
        String suggestedFixtureSetId,
        FixtureSubjectRef.FlowDraft target,
        List<Reference> references,
        List<Diagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.legacyFixtureReauthorPreview.v1";

    public LegacyFixtureReauthorPreview {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        references = references == null ? List.of() : List.copyOf(references);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (!SCHEMA_VERSION.equals(schemaVersion) || source == null || target == null
                || !identifier(targetFlowId) || !identifier(suggestedFixtureSetId)
                || references.isEmpty() || references.size() > 10_000
                || diagnostics.isEmpty() || diagnostics.size() > 8
                || new LinkedHashSet<>(references.stream().map(Reference::nodeId).toList()).size()
                != references.size()
                || new LinkedHashSet<>(diagnostics).size() != diagnostics.size()) {
            throw new IllegalArgumentException("A legacy Fixture preview requires exact payload-free authority");
        }
    }

    /** Exact legacy Graph Draft coordinate from which reference metadata was observed. */
    public record Source(String draftId, long revision) {
        public Source {
            if (!identifier(draftId) || revision < 1) {
                throw new IllegalArgumentException("A legacy Fixture source coordinate is invalid");
            }
        }
    }

    /** Safe classification of one old reference; no material coordinate or value is exposed. */
    public record Reference(String nodeId, MaterialKind materialKind, String fidelity,
                            boolean expectedInputPresent) {
        public Reference {
            if (!identifier(nodeId) || materialKind == null || fidelity == null
                    || !fidelity.matches("OUTPUT_LEVEL|PROTOCOL_DERIVED|TRANSPORT_LEVEL")) {
                throw new IllegalArgumentException("A legacy Fixture reference classification is invalid");
            }
        }
    }

    /** Whether the old graph embedded inline material or referred to governed server-owned material. */
    public enum MaterialKind { INLINE, GOVERNED }

    /** Bounded author-facing instruction that contains no legacy payload or governed coordinate. */
    public record Diagnostic(String code, String message) {
        public Diagnostic {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")
                    || message == null || message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("A legacy Fixture diagnostic must be bounded and code-derived");
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.length() <= 128
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
