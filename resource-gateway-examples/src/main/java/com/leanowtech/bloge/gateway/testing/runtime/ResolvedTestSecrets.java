package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSecretResolutionContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Run-scoped secret values returned by a trusted test-secret authority.
 *
 * <p>This object is deliberately internal to execution. Its payload-free fingerprints may enter
 * plans and checkpoints; the object itself and {@link Secret#value()} must never be serialized to
 * fixtures, evidence, logs, errors, or durable state.</p>
 */
public final class ResolvedTestSecrets {
    /** Current trusted authority result version. */
    public static final String SCHEMA_VERSION = "bloge.resolvedTestSecrets.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAX_SECRET_CHARACTERS = 65_536;
    private static final ResolvedTestSecrets EMPTY = new ResolvedTestSecrets(
            SCHEMA_VERSION, "", "", "", Instant.EPOCH, Instant.EPOCH, Map.of(), true);

    private final String schemaVersion;
    private final String contextFingerprint;
    private final String authorityId;
    private final String authorityGeneration;
    private final Instant resolvedAt;
    private final Instant expiresAt;
    private final Map<String, Secret> secrets;

    /**
     * Creates an untrusted authority response. Call {@link #verified(ObjectMapper,
     * ResolvedTestSecrets, TestSecretResolutionContext, Instant)} before execution.
     */
    public ResolvedTestSecrets(String schemaVersion, String contextFingerprint,
                               String authorityId, String authorityGeneration,
                               Instant resolvedAt, Instant expiresAt,
                               Map<String, Secret> secrets) {
        this(schemaVersion, contextFingerprint, authorityId, authorityGeneration,
                resolvedAt, expiresAt, secrets, false);
    }

    private ResolvedTestSecrets(String schemaVersion, String contextFingerprint,
                                String authorityId, String authorityGeneration,
                                Instant resolvedAt, Instant expiresAt,
                                Map<String, Secret> secrets, boolean empty) {
        this.schemaVersion = defaultVersion(schemaVersion);
        if (!SCHEMA_VERSION.equals(this.schemaVersion)) {
            throw invalid("schemaVersion is unsupported");
        }
        this.contextFingerprint = empty ? "" : fingerprint(contextFingerprint);
        this.authorityId = empty ? "" : required(authorityId, 255, "authorityId");
        this.authorityGeneration = empty ? "" : required(
                authorityGeneration, 255, "authorityGeneration");
        this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!empty && !this.expiresAt.isAfter(this.resolvedAt)) {
            throw invalid("expiry must be after resolution time");
        }
        TreeMap<String, Secret> frozen = new TreeMap<>();
        if (secrets != null) {
            secrets.forEach((alias, secret) -> {
                Secret value = Objects.requireNonNull(secret, "secret");
                if (!normalize(alias).equals(value.alias())) {
                    throw invalid("secret closure is malformed");
                }
                frozen.put(value.alias(), value);
            });
        }
        if (!empty && (frozen.isEmpty() || frozen.size() > 100)) {
            throw invalid("secret closure must contain 1 to 100 entries");
        }
        this.secrets = Collections.unmodifiableMap(frozen);
    }

    /** @return a singleton representing an execution with no requested test secrets */
    public static ResolvedTestSecrets empty() {
        return EMPTY;
    }

    /**
     * Independently validates a returned closure against the exact request binding and time.
     *
     * @param objectMapper canonical protocol mapper
     * @param candidate untrusted authority result
     * @param expected exact request context
     * @param now trusted local validation time
     * @return the same immutable result after all checks pass
     */
    public static ResolvedTestSecrets verified(ObjectMapper objectMapper,
                                               ResolvedTestSecrets candidate,
                                               TestSecretResolutionContext expected,
                                               Instant now) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(now, "now");
        if (!candidate.contextFingerprint.equals(expected.fingerprint(objectMapper))) {
            throw invalid("does not match the authorized request context");
        }
        if (now.isBefore(candidate.resolvedAt) || !now.isBefore(candidate.expiresAt)) {
            throw invalid("is outside its authorized validity window");
        }
        if (!candidate.secrets.keySet().equals(expected.secretRefs().keySet())) {
            throw invalid("does not close the exact requested dependency set");
        }
        expected.secretRefs().forEach((alias, reference) -> {
            Secret secret = candidate.secrets.get(alias);
            if (secret == null || !alias.equals(secret.alias())
                    || !reference.equals(secret.reference())
                    || !secret.bindingFingerprint().equals(bindingFingerprint(
                    objectMapper, candidate.contextFingerprint, candidate.authorityId,
                    candidate.authorityGeneration, alias, reference, secret.version()))) {
                throw invalid("does not close the exact requested dependency set");
            }
        });
        return candidate;
    }

    /**
     * Computes the non-secret exact binding an authority must return for one resolved version.
     *
     * @param objectMapper canonical protocol mapper
     * @param contextFingerprint exact resolution context fingerprint
     * @param authorityId stable authority id
     * @param authorityGeneration exact authority policy/key generation
     * @param alias requested alias
     * @param reference requested opaque reference
     * @param version exact resolved secret version
     * @return canonical payload-free binding fingerprint
     */
    public static String bindingFingerprint(
            ObjectMapper objectMapper,
            String contextFingerprint,
            String authorityId,
            String authorityGeneration,
            String alias,
            String reference,
            String version) {
        return ProtocolFingerprint.ofBounded(objectMapper, Map.of(
                "schemaVersion", "bloge.testSecretBinding.v1",
                "contextFingerprint", fingerprint(contextFingerprint),
                "authorityId", required(authorityId, 255, "authorityId"),
                "authorityGeneration", required(
                        authorityGeneration, 255, "authorityGeneration"),
                "alias", required(alias, 128, "secret alias"),
                "reference", required(reference, 1_024, "secret reference"),
                "version", required(version, 255, "secret version")), 8_192);
    }

    /**
     * Resolves one exact alias without exposing it in a failure message.
     *
     * @param alias runtime secret alias
     * @return run-scoped plaintext value
     */
    public String resolve(String alias) {
        Secret secret = secrets.get(normalize(alias));
        if (secret == null) {
            throw new IllegalStateException("No governed test-secret value is authorized for this run");
        }
        return secret.value();
    }

    /**
     * Fingerprints only authority generation and hashed dependency bindings, never secret values.
     *
     * @param objectMapper canonical protocol mapper
     * @return stable payload-free configuration fingerprint
     */
    public String configurationFingerprint(ObjectMapper objectMapper) {
        if (this == EMPTY) {
            return ProtocolFingerprint.of(objectMapper, Map.of("configured", false));
        }
        return ProtocolFingerprint.ofBounded(objectMapper, Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "contextFingerprint", contextFingerprint,
                "authorityFingerprint", ProtocolFingerprint.of(objectMapper, Map.of(
                        "authorityId", authorityId,
                        "authorityGeneration", authorityGeneration)),
                "dependencies", planDependencies(objectMapper)), 131_072);
    }

    /**
     * Projects opaque per-dependency hashes suitable for plans and impact analysis.
     *
     * @param objectMapper canonical protocol mapper
     * @return stable dependency hashes in alias order
     */
    public List<String> planDependencies(ObjectMapper objectMapper) {
        List<String> projections = new ArrayList<>();
        secrets.values().forEach(secret -> projections.add(ProtocolFingerprint.of(objectMapper,
                Map.of("alias", secret.alias(), "reference", secret.reference(),
                        "version", secret.version(),
                        "bindingFingerprint", secret.bindingFingerprint()))));
        return List.copyOf(projections);
    }

    /** @return true when no test-secret closure is present */
    public boolean isEmpty() {
        return secrets.isEmpty();
    }

    /** @return trusted authority identifier; empty only for {@link #empty()} */
    public String authorityId() {
        return authorityId;
    }

    /** @return trusted authority generation; empty only for {@link #empty()} */
    public String authorityGeneration() {
        return authorityGeneration;
    }

    /**
     * One exact resolved dependency. The value is runtime-only and must not cross an evidence,
     * diagnostic, plan, or persistence boundary.
     */
    public record Secret(String alias, String reference, String version,
                         String bindingFingerprint, String value) {
        /** Validates bounded response material without echoing any supplied field. */
        public Secret {
            alias = required(alias, 128, "secret alias");
            reference = required(reference, 1_024, "secret reference");
            version = required(version, 255, "secret version");
            bindingFingerprint = fingerprint(bindingFingerprint);
            if (value == null || value.length() > MAX_SECRET_CHARACTERS) {
                throw invalid("contains an invalid secret value");
            }
        }
    }

    private static String defaultVersion(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? SCHEMA_VERSION : normalized;
    }

    private static String fingerprint(String value) {
        String normalized = normalize(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw invalid("contains an invalid fingerprint");
        }
        return normalized;
    }

    private static String required(String value, int maximum, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw invalid(field + " is invalid");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Resolved test-secret authority result " + reason + ".");
    }
}
