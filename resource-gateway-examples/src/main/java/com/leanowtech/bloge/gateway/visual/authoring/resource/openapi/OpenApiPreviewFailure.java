package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

/** Closed, payload-free failure raised by OpenAPI preview. */
public final class OpenApiPreviewFailure extends RuntimeException {
    public enum Code { VALIDATION, CAPABILITY_UNAVAILABLE, REMOTE_FETCH_FAILED }

    private final Code code;

    public OpenApiPreviewFailure(Code code) {
        super(safeMessage(code));
        this.code = code;
    }

    public Code code() {
        return code;
    }

    private static String safeMessage(Code code) {
        return switch (code) {
            case CAPABILITY_UNAVAILABLE -> "Remote OpenAPI preview is unavailable.";
            case REMOTE_FETCH_FAILED -> "Remote OpenAPI document could not be read safely.";
            case VALIDATION -> "OpenAPI preview request is invalid or cannot be projected.";
        };
    }
}
