package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Lightweight AsyncAPI operation/message metadata used before projecting an operator library.
 *
 * @param operationId AsyncAPI operation id, blank when omitted
 * @param channelName AsyncAPI channel key or root operation channel reference
 * @param address resolved channel address used by the projected binding
 * @param action normalized operation action, such as {@code subscribe} or {@code publish}
 * @param messageName AsyncAPI message name/title, blank when omitted
 * @param title display title used by the generated operator
 * @param sourceKind inferred or explicitly declared visual source kind
 * @param hasPayload true when the message declares a payload schema
 * @param payloadType top-level payload schema type, or {@code opaque} when unknown
 * @param hasHeaders true when the message declares a headers schema
 * @param headersType top-level headers schema type, or {@code opaque} when unknown
 * @param tags operation and message tags
 * @param projectionLevel READY, WARNING, or BLOCKED for operator-library projection
 * @param projectionMessage short explanation of the projection level
 */
public record AsyncApiOperationSummary(
        String operationId,
        String channelName,
        String address,
        String action,
        String messageName,
        String title,
        String sourceKind,
        boolean hasPayload,
        String payloadType,
        boolean hasHeaders,
        String headersType,
        List<String> tags,
        String projectionLevel,
        String projectionMessage
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public AsyncApiOperationSummary {
        operationId = operationId == null ? "" : operationId;
        channelName = channelName == null ? "" : channelName;
        address = address == null ? "" : address;
        action = action == null ? "" : action.toLowerCase();
        messageName = messageName == null ? "" : messageName;
        title = title == null ? "" : title;
        sourceKind = sourceKind == null ? "" : sourceKind;
        payloadType = payloadType == null || payloadType.isBlank() ? "opaque" : payloadType;
        headersType = headersType == null || headersType.isBlank() ? "opaque" : headersType;
        tags = tags == null ? List.of() : List.copyOf(tags);
        projectionLevel = projectionLevel == null || projectionLevel.isBlank()
                ? "READY"
                : projectionLevel.toUpperCase();
        projectionMessage = projectionMessage == null ? "" : projectionMessage;
    }
}
