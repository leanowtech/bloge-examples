package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload-free read projection of a Connection.
 * @param schemaVersion versioned view wire identifier
 * @param connectionId stable connection identity
 * @param revision one-based authoritative revision
 * @param displayName human-readable connection name
 * @param baseUrl validated HTTPS endpoint
 * @param auth credential-free authentication status
 * @param defaults non-sensitive transport defaults
 */
public record ApiConnectionView(
        String schemaVersion,
        String connectionId,
        int revision,
        String displayName,
        String baseUrl,
        Auth auth,
        @JsonInclude(JsonInclude.Include.NON_NULL) ApiConnectionCommand.Defaults defaults) {
    public static final String SCHEMA_VERSION = "bloge.apiConnectionView.v1";

    /** Creates an immutable view snapshot. */
    public ApiConnectionView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        defaults = defaults == null ? null
                : new ApiConnectionCommand.Defaults(defaults.timeoutMs(), defaults.headers());
    }

    /** Credential-free auth summary. */
    public record Auth(String kind, boolean configured) {
        @Override public String toString() { return "Auth[kind=" + kind + ", configured=" + configured + "]"; }
    }

    /** Returns a defensive defaults snapshot. */
    @Override
    public ApiConnectionCommand.Defaults defaults() {
        return defaults == null ? null
                : new ApiConnectionCommand.Defaults(defaults.timeoutMs(), defaults.headers());
    }
}
