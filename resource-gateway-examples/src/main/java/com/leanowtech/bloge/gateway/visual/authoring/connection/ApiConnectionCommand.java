package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Write-only API Connection command. Credential payloads are never safe to log.
 * @param schemaVersion versioned command wire identifier
 * @param displayName human-readable connection name
 * @param baseUrl HTTPS absolute endpoint without credentials or query data
 * @param auth authentication configuration
 * @param defaults optional static transport defaults
 */
public record ApiConnectionCommand(
        String schemaVersion,
        String displayName,
        String baseUrl,
        Auth auth,
        @JsonInclude(JsonInclude.Include.NON_NULL) Defaults defaults) {

    public static final String SCHEMA_VERSION = "bloge.apiConnectionCommand.v1";

    /** Convenience constructor using the current wire schema version. */
    public ApiConnectionCommand(String displayName, String baseUrl, Auth auth, Defaults defaults) {
        this(SCHEMA_VERSION, displayName, baseUrl, auth, defaults);
    }

    /** Convenience constructor with omitted defaults. */
    public ApiConnectionCommand(String displayName, String baseUrl, Auth auth) {
        this(displayName, baseUrl, auth, null);
    }

    /** Defensive snapshot of mutable defaults. */
    public ApiConnectionCommand {
        defaults = defaults == null ? null : new Defaults(defaults.timeoutMs(), defaults.headers());
    }

    /** Returns a defensive defaults snapshot. */
    @Override
    public Defaults defaults() {
        return defaults == null ? null : new Defaults(defaults.timeoutMs(), defaults.headers());
    }

    /** Deliberately excludes all credential fields and values. */
    @Override
    public String toString() {
        return "ApiConnectionCommand[schemaVersion=" + schemaVersion + ", authKind="
                + (auth == null ? "null" : auth.kind()) + "]";
    }

    /** Wire-polymorphic credential write operation. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SecretWrite.Value.class, name = "VALUE"),
            @JsonSubTypes.Type(value = SecretWrite.SecretRef.class, name = "SECRET_REF"),
            @JsonSubTypes.Type(value = SecretWrite.KeepExisting.class, name = "KEEP_EXISTING")
    })
    public sealed interface SecretWrite permits SecretWrite.Value, SecretWrite.SecretRef, SecretWrite.KeepExisting {
        /** One-time plaintext credential accepted only at the staging boundary. */
        /**
         * One-time plaintext credential.
         * @param value plaintext accepted only at the staging boundary
         */
        record Value(String value) implements SecretWrite {
            public Value {
                if (value == null || value.isEmpty()) throw new IllegalArgumentException("secret value is required");
            }

            @Override public String toString() { return "SecretWrite.Value[REDACTED]"; }
        }

        /** Existing vault reference; scope authorization is supplied by the authority seam. */
        /**
         * Existing vault reference.
         * @param ref opaque vault handle
         */
        record SecretRef(String ref) implements SecretWrite {
            public SecretRef {
                SecretReference.requireValid(ref);
            }

            @Override public String toString() { return "SecretWrite.SecretRef[REDACTED]"; }
        }

        /** Retain the existing binding during a compatible update. */
        /** Keep the existing binding during a compatible update. */
        record KeepExisting() implements SecretWrite {
            @Override public String toString() { return "SecretWrite.KeepExisting"; }
        }

        /** @param value plaintext staged by the caller @return value write */
        static Value value(String value) { return new Value(value); }
        /** @param ref authorized vault handle @return reference write */
        static SecretRef secretRef(String ref) { return new SecretRef(ref); }
        /** @return keep-existing write */
        static KeepExisting keepExisting() { return new KeepExisting(); }
    }

    /** Wire-polymorphic authentication configuration. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Auth.None.class, name = "NONE"),
            @JsonSubTypes.Type(value = Auth.Bearer.class, name = "BEARER"),
            @JsonSubTypes.Type(value = Auth.Basic.class, name = "BASIC"),
            @JsonSubTypes.Type(value = Auth.ApiKey.class, name = "API_KEY")
    })
    public sealed interface Auth permits Auth.None, Auth.Bearer, Auth.Basic, Auth.ApiKey {
        /** @return stable wire discriminator */
        String kind();

        /** @return no-auth configuration */
        static None none() { return new None(); }
        /** @param token token write @return bearer configuration */
        static Bearer bearer(SecretWrite token) { return new Bearer(token); }
        /** @param username basic username @param password password write @return basic configuration */
        static Basic basic(String username, SecretWrite password) { return new Basic(username, password); }
        /** @param headerName safe custom header @param value API-key write @return API-key configuration */
        static ApiKey apiKey(String headerName, SecretWrite value) { return new ApiKey(headerName, value); }

        /** Authentication is not configured. */
        record None() implements Auth {
            @Override public String kind() { return "NONE"; }
            @Override public String toString() { return "Auth.None"; }
        }

        /** Bearer token authentication. */
        /**
         * Bearer token authentication.
         * @param token token write operation
         */
        record Bearer(SecretWrite token) implements Auth {
            @Override public String kind() { return "BEARER"; }
            @Override public String toString() { return "Auth.Bearer[REDACTED]"; }
        }

        /** Basic username/password authentication. */
        /**
         * Basic username/password authentication.
         * @param username basic username
         * @param password password write operation
         */
        record Basic(String username, SecretWrite password) implements Auth {
            @Override public String kind() { return "BASIC"; }
            @Override public String toString() { return "Auth.Basic[username=REDACTED,password=REDACTED]"; }
        }

        /** API-key authentication carried in one safe custom header. */
        /**
         * API-key authentication in a safe custom header.
         * @param headerName custom header name
         * @param value API-key write operation
         */
        record ApiKey(String headerName, SecretWrite value) implements Auth {
            @Override public String kind() { return "API_KEY"; }
            @Override public String toString() { return "Auth.ApiKey[headerName=REDACTED,value=REDACTED]"; }
        }
    }

    /**
     * Optional transport defaults. Header values must be non-secret static values.
     * @param timeoutMs request timeout in milliseconds
     * @param headers safe static request headers
     */
    public record Defaults(Integer timeoutMs, Map<String, String> headers) {
        public static final int DEFAULT_TIMEOUT_MS = 30_000;

        /** Creates an immutable defaults snapshot. */
        public Defaults {
            headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        }

        /** Returns a defensive header-map copy. */
        @Override public Map<String, String> headers() { return Map.copyOf(headers); }
        @Override public String toString() { return "Defaults[timeoutMs=" + timeoutMs + ", headers=REDACTED]"; }
    }
}
