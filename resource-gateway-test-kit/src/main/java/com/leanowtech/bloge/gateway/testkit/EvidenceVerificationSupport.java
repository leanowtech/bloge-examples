package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Shared canonical fingerprint, signature, and signing-time key policy primitives. */
final class EvidenceVerificationSupport {
    static final Duration KEY_CREATION_SKEW = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private EvidenceVerificationSupport() {
    }

    static String sha256(JsonNode value) {
        return sha256Bounded(value, Integer.MAX_VALUE);
    }

    static String sha256Bounded(JsonNode value, int maximumBytes) {
        if (value == null || maximumBytes < 1) {
            throw new IllegalArgumentException("Canonical evidence and a positive byte limit are required");
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(value));
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException("Canonical evidence exceeds its byte limit");
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw new IllegalArgumentException("Canonical evidence cannot be fingerprinted", failure);
        }
    }

    /**
     * Hashes an explicitly reconstructed historical wire projection without reordering fields.
     *
     * <p>This is reserved for protocols whose producer content address predates recursive JSON
     * key sorting. Callers must rebuild the complete version-specific projection themselves so
     * untrusted input field order cannot affect the digest.</p>
     */
    static String sha256OrderedBounded(
            JsonNode value,
            int maximumBytes) {
        if (value == null || maximumBytes < 1) {
            throw new IllegalArgumentException(
                    "Ordered evidence and a positive byte limit are required");
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(
                    value);
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException(
                        "Ordered evidence exceeds its byte limit");
            }
            return "sha256:"
                    + HexFormat.of().formatHex(
                    MessageDigest.getInstance(
                                    "SHA-256")
                            .digest(bytes));
        } catch (JsonProcessingException
                 | GeneralSecurityException failure) {
            throw new IllegalArgumentException(
                    "Ordered evidence cannot be fingerprinted",
                    failure);
        }
    }

    static boolean verifyEd25519(
            String materialFingerprint,
            String encodedSignature,
            String encodedPublicKey) throws GeneralSecurityException {
        byte[] publicKey = Base64.getDecoder().decode(encodedPublicKey);
        byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signatureBytes);
    }

    static String signingTimePolicyReason(
            EvidenceVerificationKeySet keySet,
            String keyId,
            Instant signedAt) {
        EvidenceVerificationKeySet.KeyPolicy key = keySet.keys().stream()
                .filter(candidate -> candidate.keyId().equals(keyId))
                .findFirst().orElse(null);
        if (key == null) {
            return "EVIDENCE_KEY_NOT_IN_PINNED_SET";
        }
        if (signedAt.isBefore(key.notBefore().minus(KEY_CREATION_SKEW))
                || key.notAfter() != null && !signedAt.isBefore(key.notAfter())) {
            return "EVIDENCE_KEY_NOT_VALID_AT_SIGNING_TIME";
        }
        List<EvidenceVerificationKeySet.LifecycleEvent> relevant = keySet.events().stream()
                .filter(event -> event.keyId().equals(key.keyId()))
                .sorted(Comparator.comparing(EvidenceVerificationKeySet.LifecycleEvent::effectiveAt)
                        .thenComparingLong(EvidenceVerificationKeySet.LifecycleEvent::sequence))
                .toList();
        EvidenceVerificationKeySet.EventType stateAtSigning = null;
        for (EvidenceVerificationKeySet.LifecycleEvent event : relevant) {
            boolean revocation = event.type() == EvidenceVerificationKeySet.EventType.REVOKED
                    || event.type() == EvidenceVerificationKeySet.EventType.COMPROMISE_DECLARED;
            if (revocation
                    && event.revocationMode()
                    == EvidenceVerificationKeySet.RevocationMode.RETROACTIVE
                    && !signedAt.isBefore(event.invalidFrom())) {
                return "EVIDENCE_KEY_REVOKED_AT_SIGNING_TIME";
            }
            if (event.type() != EvidenceVerificationKeySet.EventType.CREATED
                    && !signedAt.isBefore(event.effectiveAt())) {
                stateAtSigning = event.type();
            }
        }
        if (stateAtSigning == null) {
            return "EVIDENCE_KEY_NOT_ACTIVE_AT_SIGNING_TIME";
        }
        return switch (stateAtSigning) {
            case ACTIVATED -> "";
            case RETIRED -> "EVIDENCE_KEY_RETIRED_AT_SIGNING_TIME";
            case DISABLED -> "EVIDENCE_KEY_DISABLED_AT_SIGNING_TIME";
            case REVOKED, COMPROMISE_DECLARED -> "EVIDENCE_KEY_REVOKED_AT_SIGNING_TIME";
            case CREATED -> "EVIDENCE_KEY_NOT_ACTIVE_AT_SIGNING_TIME";
        };
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }
}
