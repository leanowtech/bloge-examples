package com.leanowtech.bloge.graphengine.store;

/**
 * Structured error codes for graph-engine metadata store failures.
 *
 * <p>Mirrors the approach of {@link com.leanowtech.bloge.durable.DurableErrorCode}
 * while staying scoped to the product-layer metadata stores.</p>
 */
public enum GraphEngineErrorCode {

    /** The requested entity does not exist. */
    NOT_FOUND,

    /** An entity with the same natural key already exists. */
    DUPLICATE,

    /** An optimistic-lock revision conflict was detected. */
    VERSION_CONFLICT,

    /** The mutation is illegal for the entity's current lifecycle state. */
    INVALID_STATE_TRANSITION,

    /** A tenant-isolation constraint was violated. */
    TENANT_MISMATCH
}
