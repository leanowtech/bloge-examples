package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

/** Closed, payload-free failure raised by OpenAPI preview. */
public final class OpenApiPreviewFailure extends RuntimeException {
    public enum Code { VALIDATION, CAPABILITY_UNAVAILABLE }

    private final Code code;

    public OpenApiPreviewFailure(Code code) {
        super(code == Code.CAPABILITY_UNAVAILABLE
                ? "Remote OpenAPI preview is unavailable."
                : "OpenAPI preview request is invalid or cannot be projected.");
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
