package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Base64;

/**
 * Opaque signing port for one independently controlled bootstrap-root authority.
 *
 * <p>The port exposes public verification material and detached Ed25519 signing only. Resource
 * Gateway never receives a private key, credential, provider endpoint, or provider error text.
 * An HSM/KMS adapter may use the deterministic request id as its idempotency key, but it must sign
 * exactly the UTF-8 bytes of {@link SignatureRequest#materialFingerprint()}.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootSigningAuthority {

    /**
     * Returns the stable public identity of the non-exportable signing key.
     *
     * @return public authority and key descriptor
     */
    Descriptor descriptor();

    /**
     * Signs one exact ceremony material fingerprint.
     *
     * <p>Implementations used by a durable ceremony service must treat {@code requestId} as an
     * idempotency key: the same complete request returns the same response, while reuse with any
     * changed field fails closed. This permits safe recovery after a process loses its database
     * lease between a remote signature side effect and outcome commit.</p>
     *
     * @param request bounded, role-aware, deterministic signing command
     * @return detached signature response echoing the complete request identity
     */
    SignatureResponse sign(SignatureRequest request);

    /** Distinguishes old-root authorization from incoming-root proof of possession. */
    enum Role {
        /** Current root authorizes the complete successor root material. */
        AUTHORIZING_ROOT,

        /** Successor root proves possession of its advertised private key. */
        INCOMING_ROOT
    }

    /**
     * Public identity of one authority-owned Ed25519 key.
     *
     * @param schemaVersion descriptor protocol generation
     * @param authorityId stable independent authority identity
     * @param keyId rotation-aware key identity
     * @param algorithm fixed {@code Ed25519} algorithm
     * @param publicKeyBase64 X.509-encoded public Ed25519 key
     */
    record Descriptor(
            String schemaVersion,
            String authorityId,
            String keyId,
            String algorithm,
            String publicKeyBase64) {

        /** Current public signer-descriptor protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootSignerDescriptor.v1";

        /** Rejects private, malformed, non-Ed25519, or non-canonical signer identity. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            publicKeyBase64 = normalized(publicKeyBase64);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !validIdentifier(authorityId)
                    || !validIdentifier(keyId)
                    || !"Ed25519".equals(algorithm)
                    || !validPublicKey(publicKeyBase64)) {
                throw new IllegalArgumentException(
                        "External bootstrap-root signer descriptor is invalid");
            }
        }
    }

    /**
     * Deterministic command sent to one opaque signing authority.
     *
     * <p>{@code ceremonyId}, role, sequence, and key identity are control-plane context. The
     * detached signature covers the material fingerprint, whose canonical material already binds
     * sequence, predecessor, lifecycle, policy, and the complete successor key set.</p>
     *
     * @param schemaVersion signing-request protocol generation
     * @param requestId deterministic content-addressed idempotency identity
     * @param ceremonyId operator-supplied ceremony correlation identity
     * @param role authority participation role
     * @param rootSetId exact root chain identity
     * @param sequence exact successor sequence
     * @param authorityId expected signer authority
     * @param keyId expected signer key
     * @param materialFingerprint exact canonical material identity to sign
     * @param issuedAt canonical material issuance time echoed as signature time
     */
    record SignatureRequest(
            String schemaVersion,
            String requestId,
            String ceremonyId,
            Role role,
            String rootSetId,
            long sequence,
            String authorityId,
            String keyId,
            String materialFingerprint,
            Instant issuedAt) {

        /** Current opaque-authority signing request protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootSignRequest.v1";

        /** Enforces exact bounded request identity before a remote signer can be invoked. */
        public SignatureRequest {
            schemaVersion = normalized(schemaVersion);
            requestId = normalized(requestId);
            ceremonyId = normalized(ceremonyId);
            rootSetId = normalized(rootSetId);
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            materialFingerprint = normalized(materialFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !validFingerprint(requestId)
                    || !validIdentifier(ceremonyId)
                    || role == null
                    || !validIdentifier(rootSetId)
                    || sequence < 1
                    || !validIdentifier(authorityId)
                    || !validIdentifier(keyId)
                    || !validFingerprint(materialFingerprint)
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(issuedAt)) {
                throw new IllegalArgumentException(
                        "External bootstrap-root signing request is invalid");
            }
        }
    }

    /**
     * Detached response from one opaque authority.
     *
     * @param schemaVersion signing-response protocol generation
     * @param requestId exact request identity
     * @param authorityId exact authority identity
     * @param keyId exact key identity
     * @param algorithm fixed {@code Ed25519} algorithm
     * @param materialFingerprint exact signed material identity
     * @param signedAt exact canonical material issuance time
     * @param signature base64-encoded 64-byte Ed25519 signature
     */
    record SignatureResponse(
            String schemaVersion,
            String requestId,
            String authorityId,
            String keyId,
            String algorithm,
            String materialFingerprint,
            Instant signedAt,
            String signature) {

        /** Current opaque-authority signing response protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootSignResponse.v1";

        /** Rejects incomplete, non-canonical, or malformed detached responses. */
        public SignatureResponse {
            schemaVersion = normalized(schemaVersion);
            requestId = normalized(requestId);
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            materialFingerprint = normalized(materialFingerprint);
            signature = normalized(signature);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !validFingerprint(requestId)
                    || !validIdentifier(authorityId)
                    || !validIdentifier(keyId)
                    || !"Ed25519".equals(algorithm)
                    || !validFingerprint(materialFingerprint)
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(signedAt)
                    || !validSignature(signature)) {
                throw new IllegalArgumentException(
                        "External bootstrap-root signing response is invalid");
            }
        }
    }

    private static boolean validPublicKey(String encoded) {
        try {
            return encoded.length() <= 512 && Base64.getDecoder().decode(encoded).length == 44;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validSignature(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded).length == 64;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validIdentifier(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static boolean validFingerprint(String value) {
        return value.matches("sha256:[a-f0-9]{64}");
    }
}
