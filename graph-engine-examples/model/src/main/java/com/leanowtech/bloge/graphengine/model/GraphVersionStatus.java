package com.leanowtech.bloge.graphengine.model;

/**
 * Lifecycle states for an immutable graph version snapshot.
 */
public enum GraphVersionStatus {
    DRAFT,
    PUBLISHED,
    DEPRECATED,
    ARCHIVED
}
