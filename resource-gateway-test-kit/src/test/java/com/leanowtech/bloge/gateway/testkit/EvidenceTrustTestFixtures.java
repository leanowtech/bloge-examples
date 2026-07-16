package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class EvidenceTrustTestFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-07-16T00:05:00Z");
    static final String DOMAIN = "corp.example/evidence";
    static final String LOG = "resource-gateway/prod";
    static final String PIN_A = "sha256:" + "a".repeat(64);
    static final String PIN_B = "sha256:" + "b".repeat(64);
    static final String PIN_C = "sha256:" + "c".repeat(64);

    private EvidenceTrustTestFixtures() {
    }

    static Fixture fixture() {
        KeyPair evidence = keyPair();
        Authority security = new Authority("security-a", keyPair());
        Authority release = new Authority("release-b", keyPair());
        ObjectNode keySet = keySet(evidence);
        return new Fixture(evidence, security, release, keySet,
                keySet.path("snapshotFingerprint").asText());
    }

    static ObjectNode keySet(KeyPair evidence) {
        try {
            Instant createdAt = NOW.minusSeconds(600);
            ObjectNode material = JSON.createObjectNode();
            material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
            material.put("provider", "test-evidence-authority");
            material.put("generatedAt", NOW.minusSeconds(300).toString());
            material.put("expiresAt", NOW.plusSeconds(3600).toString());
            material.put("activeKeyId", "evidence-key-a");
            material.put("policyCompleteness", "COMPLETE");
            ObjectNode key = material.putArray("keys").addObject();
            key.put("keyId", "evidence-key-a");
            key.put("algorithm", "Ed25519");
            key.put("encodedPublicKey", Base64.getEncoder().encodeToString(evidence.getPublic().getEncoded()));
            key.put("createdAt", createdAt.toString());
            key.put("notBefore", createdAt.toString());
            key.putNull("notAfter");
            key.put("state", "ACTIVE");
            key.put("providerKeyVersion", "version-a");
            ArrayNode events = material.putArray("events");
            event(events.addObject(), 1, "created:evidence-key-a", "CREATED", createdAt);
            event(events.addObject(), 2, "activated:evidence-key-a", "ACTIVATED", createdAt);
            String fingerprint = fingerprint(material);
            ObjectNode snapshot = material.deepCopy();
            snapshot.put("snapshotFingerprint", fingerprint);
            ObjectNode seal = snapshot.putObject("attestation");
            seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
            seal.put("materialFingerprint", fingerprint);
            seal.put("algorithm", "Ed25519");
            seal.put("keyId", "evidence-key-a");
            seal.put("signedAt", NOW.minusSeconds(299).toString());
            seal.put("signature", sign(evidence, fingerprint));
            return snapshot;
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    static ObjectNode publication(
            long sequence, String previous, long recoveryEpoch, Instant publishedAt,
            List<Pin> pins, List<Authority> authorities) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_KEY_SET_TRUST_PUBLICATION_V1);
        material.put("trustDomain", DOMAIN);
        material.put("logId", LOG);
        material.put("sequence", sequence);
        material.put("previousPublicationFingerprint", previous);
        material.put("recoveryEpoch", recoveryEpoch);
        material.put("publishedAt", publishedAt.toString());
        material.put("expiresAt", publishedAt.plusSeconds(1800).toString());
        ArrayNode pinValues = material.putArray("pins");
        pins.stream().sorted(Comparator.comparing(Pin::fingerprint))
                .forEach(pin -> pin.write(pinValues.addObject()));
        String fingerprint = fingerprint(material);
        ObjectNode publication = material.deepCopy();
        publication.put("publicationFingerprint", fingerprint);
        ArrayNode signatures = publication.putArray("signatures");
        authorities.stream().sorted(Comparator.comparing(Authority::id)).forEach(authority -> {
            ObjectNode signature = signatures.addObject();
            signature.put("authorityId", authority.id());
            signature.put("algorithm", "Ed25519");
            signature.put("signature", sign(authority.keyPair(), fingerprint));
        });
        return publication;
    }

    static EvidenceKeySetTrustBundle bundle(
            long after, long highWater, boolean hasMore, List<ObjectNode> page,
            ObjectNode head, ObjectNode keySet) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1);
        value.put("generatedAt", NOW.toString());
        value.put("trustDomain", DOMAIN);
        value.put("logId", LOG);
        value.put("afterSequence", after);
        value.put("throughSequence", page.isEmpty()
                ? after : page.getLast().path("sequence").asLong());
        value.put("highWaterSequence", highWater);
        value.put("headPublicationFingerprint", head.path("publicationFingerprint").asText());
        value.set("headPublication", head.deepCopy());
        value.put("hasMore", hasMore);
        ArrayNode publications = value.putArray("publications");
        page.forEach(publication -> publications.add(publication.deepCopy()));
        value.set("keySet", keySet.deepCopy());
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("payloadKind", "EVIDENCE_KEY_SET_TRUST_BUNDLE");
        envelope.put("payloadSchemaVersion", TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1);
        envelope.set("payload", value);
        return EvidenceKeySetTrustBundle.fromEnvelope(envelope);
    }

    static EvidenceTrustPolicy policy(Fixture fixture, int threshold) {
        return new EvidenceTrustPolicy(DOMAIN, LOG, threshold,
                List.of(authorityKey(fixture.security()), authorityKey(fixture.release())));
    }

    static Pin active(String fingerprint) {
        return new Pin(fingerprint, "ACTIVE", NOW.minusSeconds(600), null, null, "");
    }

    static Pin overlap(String fingerprint) {
        return new Pin(fingerprint, "OVERLAP", NOW.minusSeconds(600), NOW.plusSeconds(3600), null, "");
    }

    static Pin revoked(String fingerprint, Instant revokedAt) {
        return new Pin(fingerprint, "REVOKED", NOW.minusSeconds(600), revokedAt,
                revokedAt, "KEY_COMPROMISED");
    }

    static String fingerprint(JsonNode value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(canonical(value))));
        } catch (GeneralSecurityException | JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static EvidenceTrustPolicy.AuthorityKey authorityKey(Authority authority) {
        return new EvidenceTrustPolicy.AuthorityKey(authority.id(), "Ed25519",
                Base64.getEncoder().encodeToString(authority.keyPair().getPublic().getEncoded()),
                Instant.MIN, Instant.MAX, true, false);
    }

    private static void event(ObjectNode event, long sequence, String eventId,
                              String type, Instant occurredAt) {
        event.put("sequence", sequence);
        event.put("eventId", eventId);
        event.put("keyId", "evidence-key-a");
        event.put("type", type);
        event.put("occurredAt", occurredAt.toString());
        event.put("effectiveAt", occurredAt.toString());
        event.putNull("revocationMode");
        event.putNull("invalidFrom");
        event.put("reasonCode", "KEY_" + type);
    }

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String sign(KeyPair keyPair, String fingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
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

    record Authority(String id, KeyPair keyPair) {
    }

    record Fixture(KeyPair evidence, Authority security, Authority release,
                   ObjectNode keySet, String keySetFingerprint) {
    }

    record Pin(String fingerprint, String state, Instant validFrom, Instant validUntil,
               Instant revokedAt, String reasonCode) {
        void write(ObjectNode value) {
            value.put("snapshotFingerprint", fingerprint);
            value.put("state", state);
            value.put("validFrom", validFrom.toString());
            if (validUntil == null) value.putNull("validUntil"); else value.put("validUntil", validUntil.toString());
            if (revokedAt == null) value.putNull("revokedAt"); else value.put("revokedAt", revokedAt.toString());
            value.put("reasonCode", reasonCode);
        }
    }
}
