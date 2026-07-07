package com.leanowtech.bloge.gateway.visual.importer;

/**
 * Source span for an imported visual element.
 *
 * @param sourceId logical source file id
 * @param startLine one-based start line
 * @param startColumn one-based start column
 * @param endLine one-based end line when known
 * @param endColumn one-based end column when known
 * @param dslKind source AST kind
 */
public record DslSourceSpan(
        String sourceId,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String dslKind
) {
    /**
     * Creates a normalized source span.
     */
    public DslSourceSpan {
        sourceId = sourceId == null ? "" : sourceId;
        startLine = Math.max(-1, startLine);
        startColumn = Math.max(-1, startColumn);
        endLine = Math.max(-1, endLine);
        endColumn = Math.max(-1, endColumn);
        dslKind = dslKind == null ? "" : dslKind;
    }

    public static DslSourceSpan point(String sourceId, int line, int column, String dslKind) {
        return new DslSourceSpan(sourceId, line, column, -1, -1, dslKind);
    }
}
