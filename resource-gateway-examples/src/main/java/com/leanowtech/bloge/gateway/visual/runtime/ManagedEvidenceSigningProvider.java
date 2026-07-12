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
                  List<ManagedKey> keys) {
        public static final String SCHEMA_VERSION = "resourceGateway.managedEvidenceSigningKeys.v1";

        public KeySet {
            schemaVersion = schemaVersion == null ? "" : schemaVersion;
            activeKeyId = normalize(activeKeyId);
            keys = keys == null ? List.of() : List.copyOf(keys);
        }
    }

    record ManagedKey(String keyId,
                      String algorithm,
                      String encodedPublicKey,
                      Instant createdAt,
                      String state,
                      String providerKeyVersion) {
        public ManagedKey {
            keyId = normalize(keyId);
            algorithm = normalize(algorithm);
            encodedPublicKey = normalize(encodedPublicKey);
            state = normalize(state).toUpperCase(java.util.Locale.ROOT);
            providerKeyVersion = normalize(providerKeyVersion);
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
