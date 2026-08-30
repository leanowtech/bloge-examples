package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceTransportSafetyPolicy;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Pure Connection authority: validation, CAS, opaque secret binding and fingerprinting. */
public final class ApiConnectionDecisions {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    private static final int MAX_URL_LENGTH = 2048;

    private final ObjectMapper mapper;

    /** Creates a decision engine with a fresh canonical JSON mapper. */
    public ApiConnectionDecisions() { this(new ObjectMapper()); }

    /** @param mapper mapper used only for deterministic canonical fingerprints */
    public ApiConnectionDecisions(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper.copy();
    }

    /**
     * Applies validation, optimistic concurrency and scope-bound secret
     * resolution to produce the next immutable authority revision.
     *
     * @param scope authenticated tenant/project/environment scope
     * @param currentValue current head, or empty for create
     * @param connectionId stable connection identifier
     * @param command write-only command
     * @param expected create-only or exact-revision expectation
     * @param prepared opaque bindings staged/authorized by Secret Store
     * @return next authority revision
     */
    public ApiConnectionSpec next(AuthoringScope scope, Optional<ApiConnectionSpec> currentValue,
                                  String connectionId, ApiConnectionCommand command,
                                  ExpectedRevision expected, PreparedSecretBinding... prepared) {
        requireScope(scope);
        if (expected == null) invalid("expected revision is required");
        requireIdentifier(connectionId, "connectionId");
        ApiConnectionSpec current = currentValue == null ? null : currentValue.orElse(null);
        if (current != null && (!scope.equals(current.scope()) || !connectionId.equals(current.connectionId()))) {
            notFound();
        }
        if (expected instanceof ExpectedRevision.Create) {
            if (current != null) fail(ApiConnectionAuthoringException.Code.ALREADY_EXISTS);
        } else if (expected instanceof ExpectedRevision.Match match) {
            if (current == null) notFound();
            if (current.revision() != match.revision()) {
                fail(ApiConnectionAuthoringException.Code.CAS_MISMATCH);
            }
        } else {
            invalid("unsupported expected revision");
        }

        validateCommand(command);
        Map<String, PreparedSecretBinding> staged = staged(scope, prepared);
        ResolvedAuth resolved = resolveAuth(scope, current, command.auth(), staged);
        ApiConnectionCommand.Defaults defaults = effectiveDefaults(command.defaults());
        ApiConnectionSpec next = new ApiConnectionSpec(ApiConnectionSpec.SCHEMA_VERSION, scope, connectionId,
                current == null ? 1 : current.revision() + 1, "", command.displayName(), command.baseUrl(),
                resolved.kind(), resolved.username(), resolved.apiKeyHeader(), defaults, resolved.bindings());
        String metadataFingerprint = canonicalFingerprint(next);
        return new ApiConnectionSpec(ApiConnectionSpec.SCHEMA_VERSION, scope, connectionId, next.revision(),
                metadataFingerprint, next.displayName(), next.baseUrl(), next.authKind(), next.username(),
                next.apiKeyHeader(), next.defaults(), next.secretBindings());
    }

    /** Alias with current value first, matching other authoring decisions. */
    public ApiConnectionSpec next(Optional<ApiConnectionSpec> currentValue, String connectionId,
                                  ApiConnectionCommand command, ExpectedRevision expected,
                                  AuthoringScope scope, PreparedSecretBinding... prepared) {
        return next(scope, currentValue, connectionId, command, expected, prepared);
    }

    /** Convenience overload for commands without prepared bindings. */
    public ApiConnectionSpec next(AuthoringScope scope, Optional<ApiConnectionSpec> currentValue,
                                  String connectionId, ApiConnectionCommand command,
                                  ExpectedRevision expected) {
        return next(scope, currentValue, connectionId, command, expected, new PreparedSecretBinding[0]);
    }

    /** @param spec authority snapshot @return deterministic persisted metadata fingerprint */
    public String fingerprint(ApiConnectionSpec spec) {
        if (spec == null) invalid("connection spec is required");
        ObjectNode body = mapper.createObjectNode();
        body.put("connectionId", spec.connectionId());
        body.put("displayName", spec.displayName());
        body.put("baseUrl", spec.baseUrl());
        body.put("authKind", spec.authKind());
        if (spec.username() != null) body.put("username", spec.username());
        if (spec.apiKeyHeader() != null) body.put("apiKeyHeader", spec.apiKeyHeader());
        body.set("defaults", mapper.valueToTree(spec.defaults()));
        ObjectNode bindings = mapper.createObjectNode();
        spec.secretBindings().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> bindings.put(entry.getKey(), entry.getValue().ref()));
        body.set("secretBindings", bindings);
        return AuthoringFingerprints.of(body);
    }

    private void validateCommand(ApiConnectionCommand command) {
        if (command == null) invalid("command is required");
        if (!ApiConnectionCommand.SCHEMA_VERSION.equals(command.schemaVersion())) invalid("schemaVersion is unsupported");
        if (command.displayName() == null || command.displayName().isBlank() || command.displayName().length() > 200) {
            invalid("displayName is invalid");
        }
        validateBaseUrl(command.baseUrl());
        if (command.auth() == null) invalid("auth is required");
        ApiConnectionCommand.Defaults defaults = command.defaults();
        if (defaults != null) {
            if (defaults.timeoutMs() != null && (defaults.timeoutMs() < 100 || defaults.timeoutMs() > 120_000)) {
                invalid("timeoutMs is invalid");
            }
            for (Map.Entry<String, String> entry : defaults.headers().entrySet()) {
                if (entry.getValue() == null || entry.getValue().length() > 2048) invalid("default header value is invalid");
                try {
                    ApiResourceTransportSafetyPolicy.requireAllowedHeaderName(entry.getKey());
                } catch (IllegalArgumentException e) {
                    invalid("default header is reserved or invalid");
                }
            }
        }
        if (command.auth() instanceof ApiConnectionCommand.Auth.Bearer bearer) {
            requireSecretWrite(bearer.token());
        } else if (command.auth() instanceof ApiConnectionCommand.Auth.Basic basic) {
            if (basic.username() == null || basic.username().isBlank() || basic.username().length() > 256) {
                invalid("username is invalid");
            }
            requireSecretWrite(basic.password());
        } else if (command.auth() instanceof ApiConnectionCommand.Auth.ApiKey apiKey) {
            if (apiKey.headerName() == null || apiKey.headerName().isBlank()) invalid("api-key header is required");
            try {
                ApiResourceTransportSafetyPolicy.requireSafeApiKeyHeader(apiKey.headerName());
            } catch (IllegalArgumentException e) {
                invalid("api-key header is reserved or invalid");
            }
            requireSecretWrite(apiKey.value());
            if (defaults != null) {
                try {
                    ApiResourceTransportSafetyPolicy.requireSafeDefaults(defaults.headers(), apiKey.headerName());
                } catch (IllegalArgumentException e) {
                    invalid("connection default header conflicts with api-key header");
                }
            }
        } else if (!(command.auth() instanceof ApiConnectionCommand.Auth.None)) {
            invalid("auth kind is unsupported");
        }
    }

    private ResolvedAuth resolveAuth(AuthoringScope scope, ApiConnectionSpec current,
                                     ApiConnectionCommand.Auth auth, Map<String, PreparedSecretBinding> staged) {
        if (auth instanceof ApiConnectionCommand.Auth.None) return new ResolvedAuth("NONE", null, null, Map.of());
        String kind = auth.kind();
        String username = auth instanceof ApiConnectionCommand.Auth.Basic basic ? basic.username() : null;
        String apiKeyHeader = auth instanceof ApiConnectionCommand.Auth.ApiKey apiKey ? apiKey.headerName() : null;
        ApiConnectionCommand.SecretWrite write = secretWrite(auth);
        String slot = slot(auth);
        SecretReference reference;
        if (write instanceof ApiConnectionCommand.SecretWrite.KeepExisting) {
            if (current == null || !kind.equals(current.authKind())
                    || (apiKeyHeader != null && !apiKeyHeader.equalsIgnoreCase(current.apiKeyHeader()))) {
                invalid("KEEP_EXISTING requires a compatible existing auth");
            }
            reference = current.secretBindings().get(slot);
            if (reference == null) invalid("KEEP_EXISTING requires an existing secret");
        } else if (write instanceof ApiConnectionCommand.SecretWrite.Value) {
            PreparedSecretBinding binding = staged.get(slot);
            if (binding == null) invalid("VALUE requires a prepared secret binding");
            reference = binding.reference();
        } else if (write instanceof ApiConnectionCommand.SecretWrite.SecretRef secretRef) {
            PreparedSecretBinding authorized = staged.get(slot);
            if (authorized == null || !scope.equals(authorized.reference().scope())
                    || !secretRef.ref().equals(authorized.reference().ref())) notFound();
            reference = authorized.reference();
        } else {
            invalid("secret write is unsupported");
            return null;
        }
        return new ResolvedAuth(kind, username, apiKeyHeader, Map.of(slot, reference));
    }

    private Map<String, PreparedSecretBinding> staged(AuthoringScope scope, PreparedSecretBinding[] bindings) {
        Map<String, PreparedSecretBinding> result = new LinkedHashMap<>();
        if (bindings == null) return result;
        for (PreparedSecretBinding binding : bindings) {
            if (binding == null) invalid("prepared secret binding is required");
            if (!scope.equals(binding.reference().scope())) notFound();
            if (result.put(binding.slot(), binding) != null) invalid("duplicate prepared secret binding");
        }
        return result;
    }

    private String canonicalFingerprint(ApiConnectionSpec spec) {
        ObjectNode body = mapper.createObjectNode();
        body.put("connectionId", spec.connectionId());
        body.put("displayName", spec.displayName());
        body.put("baseUrl", spec.baseUrl());
        body.put("authKind", spec.authKind());
        if (spec.username() != null) body.put("username", spec.username());
        if (spec.apiKeyHeader() != null) body.put("apiKeyHeader", spec.apiKeyHeader());
        body.set("defaults", mapper.valueToTree(spec.defaults()));
        ObjectNode bindings = mapper.createObjectNode();
        spec.secretBindings().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> bindings.put(entry.getKey(), entry.getValue().ref()));
        body.set("secretBindings", bindings);
        return AuthoringFingerprints.of(body);
    }

    private static ApiConnectionCommand.Defaults effectiveDefaults(ApiConnectionCommand.Defaults defaults) {
        if (defaults == null) return new ApiConnectionCommand.Defaults(ApiConnectionCommand.Defaults.DEFAULT_TIMEOUT_MS, Map.of());
        return new ApiConnectionCommand.Defaults(
                defaults.timeoutMs() == null ? ApiConnectionCommand.Defaults.DEFAULT_TIMEOUT_MS : defaults.timeoutMs(),
                defaults.headers());
    }

    private static ApiConnectionCommand.SecretWrite secretWrite(ApiConnectionCommand.Auth auth) {
        return auth instanceof ApiConnectionCommand.Auth.Bearer bearer ? bearer.token()
                : auth instanceof ApiConnectionCommand.Auth.Basic basic ? basic.password()
                : ((ApiConnectionCommand.Auth.ApiKey) auth).value();
    }

    private static String slot(ApiConnectionCommand.Auth auth) {
        return auth instanceof ApiConnectionCommand.Auth.Bearer ? "token"
                : auth instanceof ApiConnectionCommand.Auth.Basic ? "password" : "value";
    }

    private static void requireSecretWrite(ApiConnectionCommand.SecretWrite write) {
        if (write == null) invalid("secret write is required");
    }

    private static void validateBaseUrl(String value) {
        if (value == null || value.length() > MAX_URL_LENGTH || value.chars().anyMatch(Character::isISOControl)) invalid("baseUrl is invalid");
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) invalid("baseUrl is invalid");
        } catch (IllegalArgumentException e) {
            invalid("baseUrl is invalid");
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.length() > 128 || !IDENTIFIER.matcher(value).matches()) invalid(name + " is invalid");
    }

    private static void requireScope(AuthoringScope scope) {
        if (scope == null) invalid("scope is required");
    }

    private static void notFound() { fail(ApiConnectionAuthoringException.Code.NOT_FOUND); }
    private static void invalid(String ignored) { fail(ApiConnectionAuthoringException.Code.VALIDATION); }
    private static void fail(ApiConnectionAuthoringException.Code code) {
        throw new ApiConnectionAuthoringException(code);
    }

    private record ResolvedAuth(String kind, String username, String apiKeyHeader,
                                Map<String, SecretReference> bindings) { }
}
