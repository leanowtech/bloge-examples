package com.leanowtech.bloge.graphengine.model;

import java.util.List;

/**
 * Generic offset/limit page envelope returned by control-plane query endpoints.
 *
 * @param <T> page item type
 * @param items items on the requested page
 * @param page zero-based page index
 * @param size requested page size
 * @param total total number of matching items before pagination
 */
public record PagedResult<T>(List<T> items, int page, int size, long total) {

    /**
     * Normalizes the page envelope so callers never receive mutable items or
     * negative paging metadata.
     */
    public PagedResult {
        items = items == null ? List.of() : List.copyOf(items);
        page = Math.max(0, page);
        size = Math.max(0, size);
        total = Math.max(0L, total);
    }
}
