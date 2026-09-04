package com.leanowtech.bloge.gateway.agenttdd;

import java.util.List;

/**
 * Candidate source and the exact server reference context against which it must be compiled.
 *
 * @param sourceId logical, non-secret source identifier used only for source-map coordinates
 * @param source candidate BLOGE graph source
 * @param libraryRefs exact library selector used to obtain the reference
 * @param authoringContextFingerprint fingerprint returned by {@code rg.dsl.reference.get}
 */
public record DslPreviewRequest(
        String sourceId,
        String source,
        List<String> libraryRefs,
        String authoringContextFingerprint
) {
    /** Normalizes null scalars and freezes the library selector. */
    public DslPreviewRequest {
        sourceId = sourceId == null ? "" : sourceId.trim();
        source = source == null ? "" : source;
        libraryRefs = libraryRefs == null ? null : List.copyOf(libraryRefs);
        authoringContextFingerprint = authoringContextFingerprint == null
                ? "" : authoringContextFingerprint.trim();
    }
}
