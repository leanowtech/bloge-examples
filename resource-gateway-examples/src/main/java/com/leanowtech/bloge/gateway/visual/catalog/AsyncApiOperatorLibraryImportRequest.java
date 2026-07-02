package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request for projecting an AsyncAPI document into a visual operator-library draft.
 *
 * @param libraryId optional target library id; defaults from {@code info.title}
 * @param displayName optional display name; defaults from {@code info.title}
 * @param version optional semantic version; defaults from {@code info.version} when usable or {@code 1.0.0}
 * @param owner optional owner; defaults from {@code info.contact}
 * @param status optional library lifecycle status
 * @param operationId optional AsyncAPI operation id selector
 * @param channel optional AsyncAPI channel name/address selector
 * @param action optional AsyncAPI action selector, such as {@code subscribe}, {@code publish}, {@code receive}, or
 *               {@code send}
 * @param messageName optional AsyncAPI message name/title selector
 * @param selections optional stable AsyncAPI operation/message selectors for batch subset projection
 * @param asyncApi parsed AsyncAPI document
 * @param asyncApiText raw JSON or YAML AsyncAPI document
 */
public record AsyncApiOperatorLibraryImportRequest(
        String libraryId,
        String displayName,
        String version,
        String owner,
        String status,
        String operationId,
        String channel,
        String action,
        String messageName,
        List<AsyncApiOperationSelection> selections,
        Map<String, Object> asyncApi,
        String asyncApiText
) {
    /**
     * Creates a request.
     */
    public AsyncApiOperatorLibraryImportRequest {
        libraryId = libraryId == null ? "" : libraryId;
        displayName = displayName == null ? "" : displayName;
        version = version == null ? "" : version;
        owner = owner == null ? "" : owner;
        status = status == null ? "" : status;
        operationId = operationId == null ? "" : operationId;
        channel = channel == null ? "" : channel;
        action = action == null ? "" : action;
        messageName = messageName == null ? "" : messageName;
        selections = selections == null ? List.of() : List.copyOf(selections);
        asyncApi = asyncApi == null ? Map.of() : new LinkedHashMap<>(asyncApi);
        asyncApiText = asyncApiText == null ? "" : asyncApiText;
    }

    /**
     * Backward-compatible constructor for callers that project the full AsyncAPI document.
     */
    public AsyncApiOperatorLibraryImportRequest(String libraryId,
                                                String displayName,
                                                String version,
                                                String owner,
                                                String status,
                                                Map<String, Object> asyncApi,
                                                String asyncApiText) {
        this(libraryId, displayName, version, owner, status, "", "", "", "", List.of(), asyncApi, asyncApiText);
    }

    /**
     * Backward-compatible constructor for callers that project one selected AsyncAPI operation/message.
     */
    public AsyncApiOperatorLibraryImportRequest(String libraryId,
                                                String displayName,
                                                String version,
                                                String owner,
                                                String status,
                                                String operationId,
                                                String channel,
                                                String action,
                                                String messageName,
                                                Map<String, Object> asyncApi,
                                                String asyncApiText) {
        this(libraryId, displayName, version, owner, status, operationId, channel, action, messageName,
                List.of(), asyncApi, asyncApiText);
    }
}
