package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, dependency-light JWT verifier for enterprise workload identities. */
public final class SignedJwtIntegrationIdentityResolver implements IntegrationIdentityResolver {
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final int MAX_SEGMENT_LENGTH = 4096;
    private static final int MAX_CLAIM_LENGTH = 255;
    private static final int MAX_PURPOSES = 32;
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    private final ObjectMapper objectMapper;
    private final String issuer;
    private final String audience;
    private final IntegrationJwtTrustStore trustStore;
    private final Duration clockSkew;
    private final Duration maximumLifetime;
    private final Clock clock;

    public SignedJwtIntegrationIdentityResolver(ObjectMapper objectMapper,
                                                String issuer,
                                                String audience,
                                                IntegrationJwtTrustStore trustStore,
                                                Duration clockSkew,
                                                Duration maximumLifetime) {
        this(objectMapper, issuer, audience, trustStore, clockSkew, maximumLifetime, Clock.systemUTC());
    }

    SignedJwtIntegrationIdentityResolver(ObjectMapper objectMapper,
                                         String issuer,
                                         String audience,
                                         IntegrationJwtTrustStore trustStore,
                                         Duration clockSkew,
                                         Duration maximumLifetime,
                                         Clock clock) {
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper).copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.issuer = required(issuer, "A trusted integration JWT issuer is required");
        this.audience = required(audience, "A trusted integration JWT audience is required");
        if (trustStore == null || trustStore.snapshot().trustedKeyCount() == 0) {
            throw new IllegalArgumentException("A non-empty integration JWT trust store is required");
        }
        this.trustStore = trustStore;
        this.clockSkew = boundedDuration(clockSkew, Duration.ofSeconds(30), Duration.ofMinutes(5), "clock skew");
        this.maximumLifetime = boundedDuration(maximumLifetime, Duration.ofMinutes(15), Duration.ofHours(24),
                "maximum token lifetime");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
        return resolveVerified(credential).map(Resolution::identity);
    }

    @Override
    public Optional<Resolution> resolveVerified(String credential) {
        ResolutionAttempt attempt = resolveAttempt(credential);
        return attempt.outcome() == ResolutionOutcome.VERIFIED
                ? Optional.of(attempt.resolution()) : Optional.empty();
    }

    @Override
    public ResolutionAttempt resolveAttempt(String credential) {
        try {
            return ResolutionAttempt.verified(verify(credential));
        } catch (IntegrationIdentityProviderUnavailableException failure) {
            return ResolutionAttempt.providerUnavailable();
        } catch (RuntimeException | java.security.GeneralSecurityException failure) {
            return ResolutionAttempt.invalid();
        }
    }

    @Override
    public Descriptor descriptor() {
        IntegrationJwtTrustStore.Snapshot snapshot = trustStore.snapshot();
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("acceptedAlgorithms", List.of("RS256", "EdDSA"));
        properties.put("trustedKeyCount", snapshot.trustedKeyCount());
        properties.put("activeKeyCount", snapshot.activeKeyCount());
        properties.put("revokedKeyCount", snapshot.revokedKeyCount());
        properties.put("revokedTokenCount", snapshot.revokedTokenCount());
        properties.put("keyRotationSupported", snapshot.trustedKeyCount() > 1
                || snapshot.sourceType().equals("DYNAMIC_JWKS"));
        properties.put("keyRevocationSupported", snapshot.keyRevocationSupported());
        properties.put("tokenRevocationSupported", snapshot.tokenRevocationSupported());
        properties.put("organizationGroupClaimsSupported", true);
        properties.put("clearanceClaimsSupported", true);
        properties.put("issuerAttestedDelegationGrantSupported", true);
        properties.put("maximumTokenLifetimeSeconds", maximumLifetime.toSeconds());
        properties.put("clockSkewSeconds", clockSkew.toSeconds());
        properties.put("trustSourceType", snapshot.sourceType());
        properties.put("dynamicRefreshSupported", snapshot.sourceType().equals("DYNAMIC_JWKS"));
        if (snapshot.sourceType().equals("DYNAMIC_JWKS")) {
            properties.put("refreshState", snapshot.refreshState());
            properties.put("refreshSuccessCount", snapshot.refreshSuccessCount());
            properties.put("refreshFailureCount", snapshot.refreshFailureCount());
            properties.put("lastFailureCode", snapshot.lastFailureCode());
            properties.put("refreshIntervalSeconds", snapshot.refreshIntervalSeconds());
            properties.put("revocationPropagationSloSeconds", snapshot.propagationSloSeconds());
            properties.put("outageFailClosed", snapshot.failClosed());
            properties.put("staleSnapshotAccepted", snapshot.staleSnapshotAccepted());
            if (snapshot.lastSuccessfulRefreshAt() != null) {
                properties.put("lastSuccessfulRefreshAt", snapshot.lastSuccessfulRefreshAt().toString());
            }
            if (snapshot.nextRefreshAt() != null) {
                properties.put("nextRefreshAt", snapshot.nextRefreshAt().toString());
            }
        }
        String claimsSource = snapshot.sourceType().equals("DYNAMIC_JWKS") ? "DYNAMIC_JWKS" : "VERIFIED_TOKEN";
        return new Descriptor("SIGNED_JWT", claimsSource, snapshot.available(), false, true, properties);
    }

    private Resolution verify(String credential) throws java.security.GeneralSecurityException {
        if (credential == null || credential.isBlank() || credential.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid integration JWT shape");
        }
        String[] segments = credential.trim().split("\\.", -1);
        if (segments.length != 3) {
            throw new IllegalArgumentException("Invalid integration JWT shape");
        }
        JsonNode header = decodeObject(segments[0]);
        JsonNode claims = decodeObject(segments[1]);
        String algorithm = text(header, "alg", 16, true);
        String keyId = text(header, "kid", 128, true);
        String type = text(header, "typ", 16, false);
        if ((!type.isBlank() && !type.equals("JWT"))
                || header.has("crit") && (!header.path("crit").isArray() || !header.path("crit").isEmpty())
                || header.has("b64") && (!header.path("b64").isBoolean() || !header.path("b64").booleanValue())) {
            throw new IllegalArgumentException("Unsupported integration JWT header");
        }
        IntegrationJwtTrustStore.VerificationKey key = trustStore.find(keyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown integration JWT key"));
        Instant now = clock.instant();
        if (!key.algorithm().equals(algorithm) || !key.activeAt(now)) {
            throw new IllegalArgumentException("Inactive or mismatched integration JWT key");
        }
        verifySignature(key, segments[0] + "." + segments[1], segments[2]);

        String tokenIssuer = text(claims, "iss", MAX_CLAIM_LENGTH, true);
        if (!issuer.equals(tokenIssuer) || !hasAudience(claims.path("aud"), audience)) {
            throw new IllegalArgumentException("Integration JWT issuer or audience mismatch");
        }
        Instant issuedAt = numericDate(claims, "iat");
        Instant notBefore = claims.has("nbf") ? numericDate(claims, "nbf") : issuedAt;
        Instant expiresAt = numericDate(claims, "exp");
        if (issuedAt.isAfter(now.plus(clockSkew)) || notBefore.isAfter(now.plus(clockSkew))
                || !expiresAt.isAfter(now.minus(clockSkew)) || !expiresAt.isAfter(issuedAt)
                || !expiresAt.isAfter(notBefore)
                || Duration.between(issuedAt, expiresAt).compareTo(maximumLifetime) > 0) {
            throw new IllegalArgumentException("Integration JWT is outside its accepted time window");
        }
        String tokenId = text(claims, "jti", 160, true);
        if (trustStore.isTokenRevoked(tokenId)) {
            throw new IllegalArgumentException("Integration JWT has been revoked");
        }

        String identityId = text(claims, "sub", MAX_CLAIM_LENGTH, true);
        String actorId = text(claims, "actor_id", MAX_CLAIM_LENGTH, true);
        String delegatedBy = text(claims, "delegated_by", MAX_CLAIM_LENGTH, false);
        if (!delegatedBy.isBlank() && (delegatedBy.equals(actorId) || delegatedBy.equals(identityId))) {
            throw new IllegalArgumentException("Integration JWT delegation chain is invalid");
        }
        Set<String> tokenPurposes = purposes(claims.path("purposes"));
        Set<String> groups = groups(claims.path("groups"));
        String clearance = text(claims, "clearance", 32, false);
        String delegationGrantId = "";
        Instant delegationExpiresAt = Instant.MAX;
        if (!delegatedBy.isBlank()) {
            delegationGrantId = text(claims, "delegation_grant_id", 160, true);
            delegationExpiresAt = numericDate(claims, "delegation_exp");
            Set<String> delegatedPurposes = purposes(claims.path("delegation_purposes"));
            if (!delegationExpiresAt.isAfter(now.minus(clockSkew))
                    || delegationExpiresAt.isAfter(expiresAt)
                    || !delegatedPurposes.containsAll(tokenPurposes)) {
                throw new IllegalArgumentException("Integration JWT delegation grant is invalid");
            }
        } else if (claims.has("delegation_grant_id") || claims.has("delegation_exp")
                || claims.has("delegation_purposes")) {
            throw new IllegalArgumentException("Integration JWT delegation grant has no delegating identity");
        }
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(identityId,
                text(claims, "tenant_id", MAX_CLAIM_LENGTH, true),
                text(claims, "organization_id", MAX_CLAIM_LENGTH, true),
                text(claims, "project_id", MAX_CLAIM_LENGTH, true),
                text(claims, "environment_id", MAX_CLAIM_LENGTH, true),
                text(claims, "region", MAX_CLAIM_LENGTH, true),
                text(claims, "actor_type", 64, true).toUpperCase(Locale.ROOT), actorId, delegatedBy,
                tokenPurposes, expiresAt, true, groups, clearance, delegationGrantId, delegationExpiresAt);
        return new Resolution(identity, keyId, tokenId);
    }

    private void verifySignature(IntegrationJwtTrustStore.VerificationKey key,
                                 String signedContent,
                                 String encodedSignature) throws java.security.GeneralSecurityException {
        byte[] signatureBytes = decode(encodedSignature);
        Signature verifier = Signature.getInstance(key.algorithm().equals("RS256") ? "SHA256withRSA" : "Ed25519");
        verifier.initVerify(key.publicKey());
        verifier.update(signedContent.getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(signatureBytes)) {
            throw new IllegalArgumentException("Invalid integration JWT signature");
        }
    }

    private JsonNode decodeObject(String segment) {
        try {
            byte[] decoded = decode(segment);
            if (decoded.length == 0 || decoded.length > MAX_SEGMENT_LENGTH) {
                throw new IllegalArgumentException("Invalid integration JWT segment size");
            }
            JsonNode value = objectMapper.readTree(decoded);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("Integration JWT segment must be a JSON object");
            }
            return value;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Invalid integration JWT JSON", failure);
        }
    }

    private static byte[] decode(String segment) {
        if (segment == null || segment.isBlank() || segment.length() > MAX_SEGMENT_LENGTH
                || !BASE64_URL.matcher(segment).matches()) {
            throw new IllegalArgumentException("Invalid base64url integration JWT segment");
        }
        return Base64.getUrlDecoder().decode(segment);
    }

    private static String text(JsonNode object, String field, int maximumLength, boolean required) {
        JsonNode value = object.path(field);
        if (value.isMissingNode()) {
            if (!required) {
                return "";
            }
            throw new IllegalArgumentException("Missing integration JWT claim: " + field);
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Integration JWT claim must be a string: " + field);
        }
        String result = value.textValue().trim();
        if ((required && result.isBlank()) || result.length() > maximumLength
                || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid integration JWT claim: " + field);
        }
        return result;
    }

    private static Instant numericDate(JsonNode claims, String field) {
        JsonNode value = claims.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Missing numeric integration JWT claim: " + field);
        }
        return Instant.ofEpochSecond(value.longValue());
    }

    private static boolean hasAudience(JsonNode value, String expected) {
        if (value.isTextual()) {
            return expected.equals(value.textValue());
        }
        if (!value.isArray() || value.size() == 0 || value.size() > 16) {
            return false;
        }
        boolean found = false;
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                return false;
            }
            if (expected.equals(item.textValue())) {
                found = true;
            }
        }
        return found;
    }

    private static Set<String> purposes(JsonNode value) {
        if (!value.isArray() || value.isEmpty() || value.size() > MAX_PURPOSES) {
            throw new IllegalArgumentException("Integration JWT purposes must be a bounded non-empty array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Integration JWT purpose must be a string");
            }
            String purpose = item.textValue().trim().toUpperCase(Locale.ROOT);
            if (purpose.isBlank() || purpose.length() > 128
                    || !purpose.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("Invalid integration JWT purpose");
            }
            if (!result.add(purpose)) {
                throw new IllegalArgumentException("Duplicate integration JWT purpose");
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> groups(JsonNode value) {
        if (value.isMissingNode()) {
            return Set.of();
        }
        if (!value.isArray() || value.size() > 64) {
            throw new IllegalArgumentException("Integration JWT groups must be a bounded array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Integration JWT group must be a string");
            }
            String group = item.textValue().trim();
            if (group.isBlank() || group.length() > 128
                    || group.chars().anyMatch(Character::isISOControl) || !result.add(group)) {
                throw new IllegalArgumentException("Invalid or duplicate integration JWT group");
            }
        }
        return Set.copyOf(result);
    }

    private static Duration boundedDuration(Duration value,
                                            Duration fallback,
                                            Duration maximum,
                                            String label) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isNegative() || resolved.isZero() || resolved.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid integration JWT " + label);
        }
        return resolved;
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > MAX_CLAIM_LENGTH) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
