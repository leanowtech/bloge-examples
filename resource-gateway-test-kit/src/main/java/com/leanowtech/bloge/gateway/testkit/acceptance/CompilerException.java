package com.leanowtech.bloge.gateway.testkit.acceptance;

import java.util.Objects;

/**
 * Exception thrown when compilation fails.
 * All 12 reason codes are INVALID per the wire schema.
 * reasonField is an RFC6901 JSON Pointer path and is never null.
 */
public final class CompilerException extends RuntimeException {

    /** The reason code classifying the failure, never null. */
    private final ReasonCode reasonCode;

    /** RFC6901 JSON Pointer path within the failing document, never null. */
    private final String reasonField;

    /**
     * Constructs a CompilerException with no cause.
     *
     * @param reasonCode  classifies the failure, never null
     * @param reasonField RFC6901 JSON Pointer path within the failing document, never null
     * @param message     human-readable detail message
     */
    public CompilerException(ReasonCode reasonCode, String reasonField, String message) {
        super(message);
        this.reasonCode  = Objects.requireNonNull(reasonCode,  "reasonCode");
        this.reasonField = Objects.requireNonNull(reasonField, "reasonField");
    }

    /**
     * Constructs a CompilerException with a cause.
     *
     * @param reasonCode  classifies the failure, never null
     * @param reasonField RFC6901 JSON Pointer path within the failing document, never null
     * @param message     human-readable detail message
     * @param cause       underlying exception, or null
     */
    public CompilerException(ReasonCode reasonCode, String reasonField, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode  = Objects.requireNonNull(reasonCode,  "reasonCode");
        this.reasonField = Objects.requireNonNull(reasonField, "reasonField");
    }

    /**
     * Returns the reason code classifying the failure.
     *
     * @return the reason code, never null
     */
    public ReasonCode reasonCode()  { return reasonCode; }

    /**
     * Returns the RFC6901 JSON Pointer path within the failing document.
     *
     * @return the field path, never null
     */
    public String     reasonField() { return reasonField; }

    @Override public String toString() {
        return "CompilerException{reasonCode=" + reasonCode +
               ", reasonField='" + reasonField + '\'' +
               ", message=" + getMessage() + '}';
    }

    /** Exactly the 12 INVALID reason codes from the wire schema. */
    public enum ReasonCode {

        /** Schema validation failed against the Draft2020-12 JSON Schema for the document. */
        INVALID_SCHEMA,

        /** The JSON structure of the plan document violates the Capability Studio Acceptance Plan schema. */
        INVALID_PLAN_STRUCTURE,

        /** The primitive dependency graph contains a directed cycle. */
        INVALID_TOPOLOGY_CYCLE,

        /** A primitive id in dependsOn does not exist in the plan's primitive list. */
        INVALID_TOPOLOGY_UNKNOWN_NODE,

        /** A primitive's typeId is not registered in the Capability Studio Primitive Registry. */
        INVALID_REGISTRY_TYPE_NOT_FOUND,

        /** A primitive's declared revision does not match the registry's current revision for its typeId. */
        INVALID_REGISTRY_REVISION_MISMATCH,

        /** A dependency crosses a phase barrier in a forbidden direction. */
        INVALID_BARRIER_BYPASS,

        /** A bounded collection or input exceeds its allowed size. */
        INVALID_COLLECTION_SIZE,

        /** The catalogId or catalog semantic fingerprint does not match the Capability Studio profile contract. */
        INVALID_CATALOG_SEMANTICS,

        /** A referenced or computed fingerprint does not match its expected value. */
        INVALID_FINGERPRINT_MISMATCH,

        /** A stage-exit contract count differs from the exact required count. */
        INVALID_STAGE_EXIT_CONTRACT_COUNT,

        /** The supplied plan or compiled material fails a cryptographic integrity check. */
        INVALID_TAMPERED_PLAN,
    }
}
