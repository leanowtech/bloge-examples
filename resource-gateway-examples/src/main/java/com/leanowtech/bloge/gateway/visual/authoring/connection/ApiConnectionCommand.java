package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Write-only API Connection command. Credential payloads are never safe to log. */
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

    public ApiConnectionCommand {
        defaults = defaults == null ? null : new Defaults(defaults.timeoutMs(), defaults.headers());
    }

    @Override
    public Defaults defaults() {
        return defaults == null ? null : new Defaults(defaults.timeoutMs(), defaults.headers());
    }

    /** Deliberately excludes all credential fields and values. */
    @Override
    public String toString() {
        return "ApiConnectionCommand[schemaVersion=" + schemaVersion + ", displayName=" + displayName
                + ", baseUrl=" + baseUrl + ", auth=" + (auth == null ? "null" : auth.kind())
                + ", defaults=" + (defaults == null ? "null" : "Defaults[timeoutMs=" + defaults.timeoutMs()
                + ", headers=REDACTED]") + "]";
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
        record Value(String value) implements SecretWrite {
            public Value {
                if (value == null || value.isEmpty()) throw new IllegalArgumentException("secret value is required");
            }

            @Override public String toString() { return "SecretWrite.Value[REDACTED]"; }
        }

        /** Existing vault reference; scope authorization is supplied by the authority seam. */
        record SecretRef(String ref) implements SecretWrite {
            public SecretRef {
                if (ref == null || !ref.matches("^vault://[A-Za-z0-9][A-Za-z0-9._:/~-]*$")) {
                    throw new IllegalArgumentException("secret reference is invalid");
                }
            }

            @Override public String toString() { return "SecretWrite.SecretRef[REDACTED]"; }
        }

        /** Retain the existing binding during a compatible update. */
        record KeepExisting() implements SecretWrite {
            @Override public String toString() { return "SecretWrite.KeepExisting"; }
        }

        static Value value(String value) { return new Value(value); }
        static SecretRef secretRef(String ref) { return new SecretRef(ref); }
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
        String kind();

        static None none() { return new None(); }
        static Bearer bearer(SecretWrite token) { return new Bearer(token); }
        static Basic basic(String username, SecretWrite password) { return new Basic(username, password); }
        static ApiKey apiKey(String headerName, SecretWrite value) { return new ApiKey(headerName, value); }

        record None() implements Auth {
            @Override public String kind() { return "NONE"; }
            @Override public String toString() { return "Auth.None"; }
        }

        record Bearer(SecretWrite token) implements Auth {
            @Override public String kind() { return "BEARER"; }
            @Override public String toString() { return "Auth.Bearer[REDACTED]"; }
        }

        record Basic(String username, SecretWrite password) implements Auth {
            @Override public String kind() { return "BASIC"; }
            @Override public String toString() { return "Auth.Basic[username=REDACTED,password=REDACTED]"; }
        }

        record ApiKey(String headerName, SecretWrite value) implements Auth {
            @Override public String kind() { return "API_KEY"; }
            @Override public String toString() { return "Auth.ApiKey[headerName=REDACTED,value=REDACTED]"; }
        }
    }

    /** Optional transport defaults. Header values must be non-secret static values. */
    public record Defaults(Integer timeoutMs, Map<String, String> headers) {
        public static final int DEFAULT_TIMEOUT_MS = 30_000;

        public Defaults {
            headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        }

        @Override public Map<String, String> headers() { return Map.copyOf(headers); }
        @Override public String toString() { return "Defaults[timeoutMs=" + timeoutMs + ", headers=REDACTED]"; }
    }
}
