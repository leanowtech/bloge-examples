package com.leanowtech.bloge.gateway.agenttdd;

import java.util.List;

/**
 * Selects one bounded slice of the server-authoritative BLOGE graph reference.
 *
 * @param libraryRefs exact visible operator libraries; an empty list means platform built-ins only
 * @param topics syntax topic identifiers; empty selects the compact required set
 * @param operatorRefs exact operator contracts to project; empty selects the complete scoped context
 * @param includeExamples whether compatible certified examples should be included
 */
public record DslReferenceRequest(
        List<String> libraryRefs,
        List<String> topics,
        List<String> operatorRefs,
        boolean includeExamples
) {
    /** Defensively freezes all selectors while preserving explicit empty-list semantics. */
    public DslReferenceRequest {
        libraryRefs = libraryRefs == null ? null : List.copyOf(libraryRefs);
        topics = topics == null ? List.of() : List.copyOf(topics);
        operatorRefs = operatorRefs == null ? List.of() : List.copyOf(operatorRefs);
    }
}
