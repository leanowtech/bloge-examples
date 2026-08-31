package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import java.time.Instant;
import java.util.Objects;

/** Closed, payload-free application failure vocabulary for Resource save. */
public final class ApiResourceAuthoringFailure extends RuntimeException {
    /** Stable categories independent of HTTP status mapping. */
    public enum Code {
        CAPABILITY_UNAVAILABLE, VALIDATION, CONNECTION_NOT_FOUND, NOT_FOUND,
        BUSY, LEASE_LOST, CONFLICT, CAS_MISMATCH, CONNECTION_CHANGED,
        PROJECTION_INVALID, INTEGRITY, PERSISTENCE
    }

    private final Code code;
    private final Instant retryAt;

    /** Creates a safe failure without a retry deadline. */
    public ApiResourceAuthoringFailure(Code code) { this(code, null); }

    /** Creates a safe failure with an authoritative claim deadline. */
    public ApiResourceAuthoringFailure(Code code, Instant retryAt) {
        super(message(Objects.requireNonNull(code, "code")));
        this.code = code;
        this.retryAt = retryAt;
    }

    /** @return stable application category */
    public Code code() { return code; }
    /** @return retry deadline, or null when this failure is not retry-timed */
    public Instant retryAt() { return retryAt; }
    @Override public String toString() { return getClass().getSimpleName() + "[code=" + code + "]"; }

    private static String message(Code code) {
        return switch (code) {
            case CAPABILITY_UNAVAILABLE -> "Resource authoring capability is unavailable";
            case VALIDATION -> "Resource authoring request is invalid";
            case CONNECTION_NOT_FOUND -> "Resource Connection was not found";
            case NOT_FOUND -> "Resource revision was not found";
            case BUSY -> "Resource command is busy";
            case LEASE_LOST -> "Resource command lease was lost";
            case CONFLICT -> "Resource command conflicts with an existing request";
            case CAS_MISMATCH -> "Resource revision does not match";
            case CONNECTION_CHANGED -> "Resource Connection changed during save";
            case PROJECTION_INVALID -> "Resource projection is invalid";
            case INTEGRITY -> "Resource authoring integrity check failed";
            case PERSISTENCE -> "Resource authoring persistence failed";
        };
    }
}
