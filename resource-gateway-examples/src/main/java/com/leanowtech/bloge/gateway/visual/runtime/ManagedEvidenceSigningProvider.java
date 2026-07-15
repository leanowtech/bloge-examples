package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Narrow provider port for non-exportable evidence signing keys.
 * Cloud KMS, Key Vault and HSM adapters only need to implement key discovery and digest signing.
 */
public interface ManagedEvidenceSigningProvider {

    KeySet fetchKeys();

    SignatureResult sign(SignatureRequest request);

    String providerName();

    record KeySet(String schemaVersion,
                  Instant generatedAt,
                  Instant expiresAt,
                  String activeKeyId,
                  List<ManagedKey> keys,
                  String policyCompleteness,
                  List<KeyLifecycleEvent> lifecycleEvents) {
        /** Current provider key-discovery protocol with time-aware lifecycle policy. */
        public static final String SCHEMA_VERSION = "resourceGateway.managedEvidenceSigningKeys.v2";
        /** Historical provider protocol carrying only current key state. */
        public static final String SCHEMA_VERSION_V1 = "resourceGateway.managedEvidenceSigningKeys.v1";

        public KeySet {
            schemaVersion = schemaVersion == null ? "" : schemaVersion;
            activeKeyId = normalize(activeKeyId);
            keys = keys == null ? List.of() : List.copyOf(keys);
            policyCompleteness = normalize(policyCompleteness).toUpperCase(java.util.Locale.ROOT);
            lifecycleEvents = lifecycleEvents == null ? List.of() : List.copyOf(lifecycleEvents);
        }

        /** Retains source compatibility for current-state-only provider adapters. */
        public KeySet(String schemaVersion, Instant generatedAt, Instant expiresAt,
                      String activeKeyId, List<ManagedKey> keys) {
            this(schemaVersion, generatedAt, expiresAt, activeKeyId, keys,
                    "CURRENT_STATE_ONLY", List.of());
        }
    }

    record ManagedKey(String keyId,
                      String algorithm,
                      String encodedPublicKey,
                      Instant createdAt,
                      String state,
                      String providerKeyVersion,
                      Instant notBefore,
                      Instant notAfter) {
        public ManagedKey {
            keyId = normalize(keyId);
            algorithm = normalize(algorithm);
            encodedPublicKey = normalize(encodedPublicKey);
            state = normalize(state).toUpperCase(java.util.Locale.ROOT);
            providerKeyVersion = normalize(providerKeyVersion);
            notBefore = notBefore == null ? createdAt : notBefore;
        }

        /** Retains source compatibility for v1 key descriptors. */
        public ManagedKey(String keyId, String algorithm, String encodedPublicKey,
                          Instant createdAt, String state, String providerKeyVersion) {
            this(keyId, algorithm, encodedPublicKey, createdAt, state, providerKeyVersion,
                    createdAt, null);
        }
    }

    /**
     * Provider-owned lifecycle event used to decide historical signature validity.
     *
     * @param sequence strictly increasing authority-local sequence
     * @param eventId stable event identifier
     * @param keyId affected key
     * @param type CREATED, ACTIVATED, RETIRED, DISABLED, REVOKED, or COMPROMISE_DECLARED
     * @param occurredAt authority observation time
     * @param effectiveAt policy effective time
     * @param revocationMode PROSPECTIVE or RETROACTIVE for revocation events
     * @param invalidFrom earliest invalid signing time for retroactive revocation
     * @param reasonCode machine-readable reason
     */
    record KeyLifecycleEvent(long sequence,
                             String eventId,
                             String keyId,
                             String type,
                             Instant occurredAt,
                             Instant effectiveAt,
                             String revocationMode,
                             Instant invalidFrom,
                             String reasonCode) {
        public KeyLifecycleEvent {
            eventId = normalize(eventId);
            keyId = normalize(keyId);
            type = normalize(type).toUpperCase(java.util.Locale.ROOT);
            revocationMode = normalize(revocationMode).toUpperCase(java.util.Locale.ROOT);
            reasonCode = normalize(reasonCode).toUpperCase(java.util.Locale.ROOT);
        }
    }

    record SignatureRequest(String schemaVersion,
                            String requestId,
                            String keyId,
                            String algorithm,
                            String materialFingerprint) {
        public static final String SCHEMA_VERSION = "resourceGateway.managedEvidenceSignRequest.v1";

        public SignatureRequest {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
            requestId = normalize(requestId);
            keyId = normalize(keyId);
            algorithm = normalize(algorithm);
            materialFingerprint = normalize(materialFingerprint);
        }
    }

    record SignatureResult(String schemaVersion,
                           String requestId,
                           String keyId,
                           String algorithm,
                           String materialFingerprint,
                           Instant signedAt,
                           String signature) {
        public static final String SCHEMA_VERSION = "resourceGateway.managedEvidenceSignResponse.v1";

        public SignatureResult {
            schemaVersion = schemaVersion == null ? "" : schemaVersion;
            requestId = normalize(requestId);
            keyId = normalize(keyId);
            algorithm = normalize(algorithm);
            materialFingerprint = normalize(materialFingerprint);
            signature = normalize(signature);
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
