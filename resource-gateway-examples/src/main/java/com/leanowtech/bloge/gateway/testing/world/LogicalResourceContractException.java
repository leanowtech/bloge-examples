package com.leanowtech.bloge.gateway.testing.world;

/** Stable payload-free rejection raised by the logical-resource contract boundary. */
public final class LogicalResourceContractException extends RuntimeException {

    private final String code;

    public LogicalResourceContractException(String code, String message) {
        super(message == null || message.isBlank() ? "Logical resource contract operation was rejected." : message);
        this.code = code == null || code.isBlank() ? "RG.WORLD.LOGICAL_CONTRACT_INVALID" : code.trim();
    }

    /** @return stable machine-readable rejection code */
    public String code() {
        return code;
    }

    static LogicalResourceContractException invalid() {
        return new LogicalResourceContractException(
                "RG.LOGICAL_CONTRACT.INVALID", "Logical resource contract is invalid.");
    }

    static LogicalResourceContractException projectionInvalid() {
        return new LogicalResourceContractException(
                "RG.LOGICAL_CONTRACT.PROJECTION_INVALID", "Logical resource contract projection was rejected.");
    }

    static LogicalResourceContractException implementationIncompatible() {
        return new LogicalResourceContractException(
                "RG.LOGICAL_CONTRACT.IMPLEMENTATION_INCOMPATIBLE",
                "Resource implementation does not satisfy the logical contract.");
    }

    static LogicalResourceContractException implementationUnknown() {
        return new LogicalResourceContractException(
                "RG.LOGICAL_CONTRACT.IMPLEMENTATION_COMPATIBILITY_UNKNOWN",
                "Resource implementation requires compatibility review.");
    }

    static LogicalResourceContractException confirmationRequired() {
        return new LogicalResourceContractException(
                "RG.LOGICAL_CONTRACT.CONFIRMATION_REQUIRED",
                "Logical resource contract semantics require confirmation before binding.");
    }
}
