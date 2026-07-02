package com.leanowtech.bloge.gateway.visual.catalog;

/**
 * Stable AsyncAPI operation/message selector used before projecting an operator-library subset.
 *
 * @param operationId optional AsyncAPI operation id selector
 * @param channel optional AsyncAPI channel name/address selector
 * @param action optional AsyncAPI action selector, such as {@code subscribe}, {@code publish}, {@code receive}, or
 *               {@code send}
 * @param messageName optional AsyncAPI message name/title selector
 */
public record AsyncApiOperationSelection(
        String operationId,
        String channel,
        String action,
        String messageName
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public AsyncApiOperationSelection {
        operationId = operationId == null ? "" : operationId;
        channel = channel == null ? "" : channel;
        action = action == null ? "" : action;
        messageName = messageName == null ? "" : messageName;
    }
}
