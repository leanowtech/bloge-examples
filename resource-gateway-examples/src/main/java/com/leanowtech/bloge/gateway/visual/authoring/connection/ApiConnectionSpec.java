package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Internal immutable Connection authority. It retains only non-secret auth
 * metadata and the stable set of configured secret slots.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
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
    private final SortedSet<String> secretSlots;

    ApiConnectionSpec(String schemaVersion, AuthoringScope scope, String connectionId, int revision,
                      String fingerprint, String displayName, String baseUrl, String authKind,
                      String username, String apiKeyHeader, ApiConnectionCommand.Defaults defaults,
                      Set<String> secretSlots) {
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
        this.secretSlots = validSecretSlots(authKind, secretSlots);
    }

    /**
     * Restores a persisted authority snapshot while preserving its stored
     * fingerprint for a separate canonical-integrity check by the repository.
     * The fingerprint is format-checked here but is not trusted as proof of
     * the reconstructed fields.
     *
     * @param fingerprint deterministic fingerprint persisted with the revision
     * @return restored immutable authority snapshot
     */
    public static ApiConnectionSpec restore(String schemaVersion, AuthoringScope scope, String connectionId,
                                            int revision, String fingerprint, String displayName, String baseUrl,
                                            String authKind, String username, String apiKeyHeader,
                                            ApiConnectionCommand.Defaults defaults,
                                            Set<String> secretSlots) {
        if (fingerprint == null || !fingerprint.matches("sha256:[0-9a-f]{64}")
                || revision < 1 || displayName == null || baseUrl == null || authKind == null) {
            throw new IllegalArgumentException("connection authority fields are invalid");
        }
        return new ApiConnectionSpec(schemaVersion, scope, connectionId, revision, fingerprint, displayName,
                baseUrl, authKind, username, apiKeyHeader, defaults, secretSlots);
    }

    /**
     * Transitional restore overload for adapters migrating from reference-backed
     * authority. References are deliberately discarded; only their slot names
     * remain authoritative.
     */
    @Deprecated(forRemoval = true)
    public static ApiConnectionSpec restore(String schemaVersion, AuthoringScope scope, String connectionId,
                                            int revision, String fingerprint, String displayName, String baseUrl,
                                            String authKind, String username, String apiKeyHeader,
                                            ApiConnectionCommand.Defaults defaults,
                                            Map<String, SecretReference> secretBindings) {
        return restore(schemaVersion, scope, connectionId, revision, fingerprint, displayName, baseUrl,
                authKind, username, apiKeyHeader, defaults,
                secretBindings == null ? Set.of() : secretBindings.keySet());
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
    /** @return stable sorted configured secret slots */
    public SortedSet<String> secretSlots() { return Collections.unmodifiableSortedSet(new TreeSet<>(secretSlots)); }

    /**
     * Transitional read-only bridge for adapters that still compile against the
     * old API. Values are slot markers, never source references or locators.
     *
     * @return slot-marker map for the legacy adapter seam
     * @deprecated migrate adapters to {@link #secretSlots()}
     */
    @Deprecated(forRemoval = true)
    @JsonIgnore
    public Map<String, SecretReference> secretBindings() {
        return secretSlots.stream().collect(Collectors.toUnmodifiableMap(
                slot -> slot, slot -> new SecretReference(scope, "vault://slot-marker/" + slot)));
    }

    /** @return exact payload-free client projection */
    public ApiConnectionView view() {
        return new ApiConnectionView(ApiConnectionView.SCHEMA_VERSION, connectionId, revision, displayName,
                baseUrl, new ApiConnectionView.Auth(authKind, !secretSlots.isEmpty()), defaults());
    }

    @Override
    public String toString() {
        return "ApiConnectionSpec[connectionId=" + connectionId + ", revision=" + revision
                + ", fingerprint=" + fingerprint + ", authKind=" + authKind + "]";
    }

    private static SortedSet<String> validSecretSlots(String authKind, Set<String> slots) {
        SortedSet<String> result = new TreeSet<>();
        if (slots != null) {
            for (String slot : slots) {
                if (slot == null || !slot.matches("token|password|value")) {
                    throw new IllegalArgumentException("secret slots are invalid");
                }
                result.add(slot);
            }
        }
        if (authKind == null) throw new IllegalArgumentException("auth kind is invalid");
        String required = switch (authKind) {
            case "NONE" -> null;
            case "BEARER" -> "token";
            case "BASIC" -> "password";
            case "API_KEY" -> "value";
            default -> throw new IllegalArgumentException("auth kind is invalid");
        };
        if (required == null ? !result.isEmpty() : !result.equals(Set.of(required))) {
            throw new IllegalArgumentException("secret slots do not match auth kind");
        }
        return Collections.unmodifiableSortedSet(result);
    }
}
