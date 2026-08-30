package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal immutable Connection authority. It retains only non-secret auth
 * metadata and opaque, scope-bound references returned by Secret Store.
 */
public final class ApiConnectionSpec {
    public static final String SCHEMA_VERSION = "bloge.apiConnectionSpec.v1";

    private final String schemaVersion;
    private final AuthoringScope scope;
    private final String connectionId;
    private final int revision;
    private final String fingerprint;
    private final String displayName;
    private final String baseUrl;
    private final String authKind;
    private final String username;
    private final String apiKeyHeader;
    private final ApiConnectionCommand.Defaults defaults;
    private final Map<String, SecretReference> secretBindings;

    ApiConnectionSpec(String schemaVersion, AuthoringScope scope, String connectionId, int revision,
                      String fingerprint, String displayName, String baseUrl, String authKind,
                      String username, String apiKeyHeader, ApiConnectionCommand.Defaults defaults,
                      Map<String, SecretReference> secretBindings) {
        this.schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.connectionId = connectionId;
        this.revision = revision;
        this.fingerprint = fingerprint;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.authKind = authKind;
        this.username = username;
        this.apiKeyHeader = apiKeyHeader;
        this.defaults = defaults == null ? null
                : new ApiConnectionCommand.Defaults(defaults.timeoutMs(), defaults.headers());
        this.secretBindings = Map.copyOf(new LinkedHashMap<>(secretBindings == null ? Map.of() : secretBindings));
    }

    /** @return authority schema version */
    public String schemaVersion() { return schemaVersion; }
    /** @return authenticated scope */
    public AuthoringScope scope() { return scope; }
    /** @return stable connection identifier */
    public String connectionId() { return connectionId; }
    /** @return one-based revision */
    public int revision() { return revision; }
    /** @return deterministic canonical fingerprint */
    public String fingerprint() { return fingerprint; }
    /** @return non-secret display name */
    public String displayName() { return displayName; }
    /** @return validated HTTPS base URL */
    public String baseUrl() { return baseUrl; }
    /** @return auth discriminator */
    public String authKind() { return authKind; }
    /** @return Basic auth username, when applicable */
    public String username() { return username; }
    /** @return API-key header name, when applicable */
    public String apiKeyHeader() { return apiKeyHeader; }
    /** @return defensive non-secret defaults snapshot */
    public ApiConnectionCommand.Defaults defaults() {
        return defaults == null ? null : new ApiConnectionCommand.Defaults(defaults.timeoutMs(), defaults.headers());
    }
    /** @return defensive map of opaque scope-bound secret handles */
    public Map<String, SecretReference> secretBindings() { return Map.copyOf(secretBindings); }

    /** @return exact payload-free client projection */
    public ApiConnectionView view() {
        return new ApiConnectionView(ApiConnectionView.SCHEMA_VERSION, connectionId, revision, displayName,
                baseUrl, new ApiConnectionView.Auth(authKind, !secretBindings.isEmpty()), defaults());
    }

    @Override
    public String toString() {
        return "ApiConnectionSpec[connectionId=" + connectionId + ", revision=" + revision
                + ", fingerprint=" + fingerprint + ", authKind=" + authKind + "]";
    }
}
