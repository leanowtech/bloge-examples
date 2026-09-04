package com.leanowtech.bloge.gateway.agenttdd;

import java.util.List;

/**
 * Payload-free, registry-owned diagnostic that a coding Agent can use without reading raw compiler prose.
 *
 * @param level ERROR, WARNING or INFO
 * @param phase fixed authoring pipeline phase
 * @param code stable Resource Gateway diagnostic code
 * @param target structural target without source text or runtime payload
 * @param span one-based source position when the lower layer supplied structured coordinates
 * @param safeSummary server-owned explanation containing no interpolated source values
 * @param expectedKinds fixed kinds that can satisfy the failed construct
 * @param referenceRefs anchors in the server reference response
 * @param fixHints deterministic fixes or authorized contract candidates
 * @param resolutionClass whether an Agent can revise or a platform/human must intervene
 * @param retryable whether an unchanged request may become valid after refreshing external context
 * @param diagnosticFingerprint content address used for bounded repair-loop detection
 */
public record DslAuthoringDiagnostic(
        String level,
        String phase,
        String code,
        String target,
        Span span,
        String safeSummary,
        List<String> expectedKinds,
        List<String> referenceRefs,
        List<FixHint> fixHints,
        String resolutionClass,
        boolean retryable,
        String diagnosticFingerprint
) {
    /** One-based source span; all coordinates are zero when {@code known=false}. */
    public record Span(boolean known, int startLine, int startColumn, int endLine, int endColumn) { }

    /** Fixed repair kind and an optional authorized or language-owned candidate. */
    public record FixHint(String kind, String candidate, String reasonCode) { }

    /** Freezes all collections so diagnostics cannot change after their fingerprint is issued. */
    public DslAuthoringDiagnostic {
        expectedKinds = expectedKinds == null ? List.of() : List.copyOf(expectedKinds);
        referenceRefs = referenceRefs == null ? List.of() : List.copyOf(referenceRefs);
        fixHints = fixHints == null ? List.of() : List.copyOf(fixHints);
    }
}
