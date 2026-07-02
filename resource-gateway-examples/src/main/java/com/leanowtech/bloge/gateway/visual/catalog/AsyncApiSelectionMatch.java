package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Per-selector match evidence for AsyncAPI projection preview.
 *
 * @param target request path for this selector
 * @param status MATCHED, AMBIGUOUS, or NO_MATCH
 * @param selection selector submitted by the caller
 * @param matchedOperationCount number of discovered candidates matched by this selector
 * @param matchedOperations matched operation/message metadata
 */
public record AsyncApiSelectionMatch(
        String target,
        String status,
        AsyncApiOperationSelection selection,
        int matchedOperationCount,
        List<AsyncApiOperationSummary> matchedOperations
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public AsyncApiSelectionMatch {
        target = target == null || target.isBlank() ? "/" : target;
        status = status == null || status.isBlank() ? "NO_MATCH" : status.toUpperCase();
        selection = selection == null ? new AsyncApiOperationSelection("", "", "", "") : selection;
        matchedOperationCount = Math.max(0, matchedOperationCount);
        matchedOperations = matchedOperations == null ? List.of() : List.copyOf(matchedOperations);
    }
}
