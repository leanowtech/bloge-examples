package com.leanowtech.bloge.gateway.visual.catalog;

/**
 * AsyncAPI operation/message candidate omitted from a projection preview.
 *
 * @param operation discovered operation/message metadata
 * @param reason machine-readable omission reason
 */
public record AsyncApiOmittedOperation(
        AsyncApiOperationSummary operation,
        String reason
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public AsyncApiOmittedOperation {
        reason = reason == null || reason.isBlank() ? "unknown" : reason;
    }
}
