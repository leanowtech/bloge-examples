package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical preimages used to bind external governance approval to one exact local mutation.
 *
 * <p>The signed envelope carries only the resulting fingerprints. Publishing the preimage models
 * prevents Resource Gateway and an external approval service from independently reimplementing the
 * field set or canonicalization rules.</p>
 */
public final class WorkerQuarantineChangeAuthorizationBinding {

    private WorkerQuarantineChangeAuthorizationBinding() {
    }

    /**
     * Identity-derived scope preimage.
     *
     * @param schemaVersion scope binding protocol version
     * @param tenantId verified tenant
     * @param organizationId verified organization
     * @param projectId verified project
     * @param environmentId verified test or staging environment
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ScopeMaterial(
            String schemaVersion,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId) {

        /** Current scope-binding preimage version. */
        public static final String SCHEMA_VERSION =
                "bloge.workerQuarantineChangeAuthorizationScope.v1";

        /** Validates a complete identity-derived scope. */
        public ScopeMaterial {
            schemaVersion = normalized(schemaVersion).isBlank()
                    ? SCHEMA_VERSION : normalized(schemaVersion);
            tenantId = required(tenantId, "tenantId", 255);
            organizationId = required(organizationId, "organizationId", 255);
            projectId = required(projectId, "projectId", 255);
            environmentId = required(environmentId, "environmentId", 32)
                    .toLowerCase(Locale.ROOT);
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "External change-authorization scope version is invalid");
            }
        }

        /** @return canonical SHA-256 identity for this exact scope */
        public String fingerprint(ObjectMapper objectMapper) {
            return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                    this);
        }
    }

    /**
     * Exact destructive-mutation preimage.
     *
     * @param schemaVersion subject binding protocol version
     * @param key exact quarantine identity
     * @param claimOwner observed maker identity
     * @param claimVersion exact maintenance generation
     * @param claimUntil exact database-clock claim deadline
     * @param reasonCode shared non-payload rationale
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SubjectMaterial(
            String schemaVersion,
            DurableWorkerQuarantineKey key,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            String reasonCode) {

        /** Current mutation-subject preimage version. */
        public static final String SCHEMA_VERSION =
                "bloge.workerQuarantineChangeAuthorizationSubject.v1";

        private static final Pattern REASON =
                Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Validates an exact token-free claim closure and rationale. */
        public SubjectMaterial {
            schemaVersion = normalized(schemaVersion).isBlank()
                    ? SCHEMA_VERSION : normalized(schemaVersion);
            key = Objects.requireNonNull(key, "key");
            claimOwner = required(claimOwner, "claimOwner", 255);
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            reasonCode = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
            if (!SCHEMA_VERSION.equals(schemaVersion) || claimVersion <= 0
                    || claimUntil.getNano() % 1_000 != 0 || !REASON.matcher(reasonCode).matches()) {
                throw new IllegalArgumentException(
                        "External change-authorization subject is invalid");
            }
        }

        /** @return canonical SHA-256 identity for this exact mutation subject */
        public String fingerprint(ObjectMapper objectMapper) {
            return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                    this);
        }
    }

    private static String required(String value, String name, int maximum) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1.." + maximum
                    + " characters");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
