package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request for projecting an AsyncAPI document into a visual operator-library draft.
 *
 * @param libraryId optional target library id; defaults from {@code info.title}
 * @param displayName optional display name; defaults from {@code info.title}
 * @param version optional semantic version; defaults from {@code info.version} when usable or {@code 1.0.0}
 * @param owner optional owner; defaults from {@code info.contact}
 * @param status optional library lifecycle status
 * @param asyncApi parsed AsyncAPI document
 * @param asyncApiText raw JSON or YAML AsyncAPI document
 */
public record AsyncApiOperatorLibraryImportRequest(
        String libraryId,
        String displayName,
        String version,
        String owner,
        String status,
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
        asyncApi = asyncApi == null ? Map.of() : new LinkedHashMap<>(asyncApi);
        asyncApiText = asyncApiText == null ? "" : asyncApiText;
    }
}
