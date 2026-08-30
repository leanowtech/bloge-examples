package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Payload-free read projection of a Connection. */
public record ApiConnectionView(
        String schemaVersion,
        String connectionId,
        int revision,
        String displayName,
        String baseUrl,
        Auth auth,
        @JsonInclude(JsonInclude.Include.NON_NULL) ApiConnectionCommand.Defaults defaults) {
    public static final String SCHEMA_VERSION = "bloge.apiConnectionView.v1";

    public ApiConnectionView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        defaults = defaults == null ? null
                : new ApiConnectionCommand.Defaults(defaults.timeoutMs(), defaults.headers());
    }

    /** The view auth object intentionally has no credential metadata. */
    public record Auth(String kind, boolean configured) {
        @Override public String toString() { return "Auth[kind=" + kind + ", configured=" + configured + "]"; }
    }

    @Override
    public ApiConnectionCommand.Defaults defaults() {
        return defaults == null ? null
                : new ApiConnectionCommand.Defaults(defaults.timeoutMs(), defaults.headers());
    }
}
