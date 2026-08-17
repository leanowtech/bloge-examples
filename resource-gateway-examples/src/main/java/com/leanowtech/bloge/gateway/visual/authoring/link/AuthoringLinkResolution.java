package com.leanowtech.bloge.gateway.visual.authoring.link;

/** Fail-closed service outcome projected to stable HTTP problem codes by the controller. */
public record AuthoringLinkResolution(
        String schemaVersion,
        Status status,
        AuthoringLinkDescriptor descriptor,
        String errorCode
) {
    public static final String SCHEMA_VERSION = "bloge.authoringLinkResolution.v1";

    public AuthoringLinkResolution {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        status = status == null ? Status.NOT_FOUND : status;
        descriptor = status == Status.RESOLVED ? descriptor : null;
        errorCode = errorCode == null ? "" : errorCode.trim();
        if (status == Status.RESOLVED && descriptor == null) {
            throw new IllegalArgumentException("resolved Authoring link requires a descriptor");
        }
        if (status != Status.RESOLVED && errorCode.isBlank()) {
            throw new IllegalArgumentException("failed Authoring link resolution requires an error code");
        }
    }

    public enum Status {
        RESOLVED,
        INVALID_REQUEST,
        DRIFTED,
        FORBIDDEN,
        NOT_FOUND
    }
}
