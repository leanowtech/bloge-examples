package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fingerprintable, signed snapshot of evidence verification keys and their lifecycle policy.
 *
 * <p>The snapshot signature proves custody of one key present in the snapshot. It is deliberately
 * not treated as its own trust root: offline consumers must pin {@link #snapshotFingerprint()} (or
 * obtain that pin from an independently trusted governance registry) before accepting the embedded
 * public keys. This prevents a forged key set from becoming trusted merely by self-signing.</p>
 *
 * @param schemaVersion public key-set protocol version
 * @param snapshotFingerprint canonical fingerprint of {@link #material()}
 * @param provider signing authority name
 * @param generatedAt authoritative snapshot generation time
 * @param expiresAt policy freshness deadline
 * @param activeKeyId only key permitted to create new evidence signatures
 * @param policyCompleteness completeness of the exported lifecycle history
 * @param keys bounded public verification-key policies
 * @param events ordered lifecycle facts used for time-aware verification
 * @param attestation detached signature over {@code snapshotFingerprint}
 */
public record EvidenceVerificationKeySet(
        String schemaVersion,
        String snapshotFingerprint,
        String provider,
        Instant generatedAt,
        Instant expiresAt,
        String activeKeyId,
        PolicyCompleteness policyCompleteness,
        List<KeyPolicy> keys,
        List<LifecycleEvent> events,
        VisualRunEvidenceSeal attestation
) {
    /** Current signed key-set snapshot protocol version. */
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.evidenceVerificationKeySet.v1";
    /** Maximum number of public keys accepted from one authority snapshot. */
    public static final int MAX_KEYS = 64;
    /** Maximum number of lifecycle facts carried by one snapshot. */
    public static final int MAX_EVENTS = 512;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Whether the authority supplied a complete lifecycle history or only its current state. */
    public enum PolicyCompleteness {
        /** Lifecycle events are sufficient for time-aware rotation and revocation decisions. */
        COMPLETE,
        /** Only current key states are known; ambiguous historical decisions must fail closed. */
        CURRENT_STATE_ONLY
    }

    /** Public key lifecycle states. */
    public enum KeyState {
        /** Key may create and verify evidence. */
        ACTIVE,
        /** Key may verify historical evidence but may not create new evidence. */
        VERIFY_ONLY,
        /** Key was administratively disabled; historical validity depends on its event time. */
        DISABLED,
        /** Key was revoked; historical validity depends on its revocation policy. */
        REVOKED
    }

    /** Lifecycle event kinds retained in the signed snapshot. */
    public enum EventType {
        CREATED,
        ACTIVATED,
        RETIRED,
        DISABLED,
        REVOKED,
        COMPROMISE_DECLARED
    }

    /** Revocation effect applied to evidence signing times. */
    public enum RevocationMode {
        /** Evidence signed before the event effective time remains valid. */
        PROSPECTIVE,
        /** Evidence at or after {@code invalidFrom} is invalid, including pre-declaration evidence. */
        RETROACTIVE
    }

    /**
     * Public verification material and bounded validity interval for one key.
     *
     * @param keyId stable provider key identifier
     * @param algorithm signature algorithm
     * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo bytes
     * @param createdAt key creation time
     * @param notBefore earliest permitted evidence signing time
     * @param notAfter exclusive latest permitted evidence signing time; {@code null} means unbounded
     * @param state current lifecycle state
     * @param providerKeyVersion opaque provider version without secret material
     */
    public record KeyPolicy(
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant createdAt,
            Instant notBefore,
            Instant notAfter,
            KeyState state,
            String providerKeyVersion
    ) {
        /** Normalizes and validates public key policy material. */
        public KeyPolicy {
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            encodedPublicKey = normalized(encodedPublicKey);
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            notBefore = notBefore == null ? createdAt : notBefore;
            providerKeyVersion = normalized(providerKeyVersion);
            if (keyId.isBlank() || keyId.length() > 255 || containsControl(keyId)
                    || !"Ed25519".equals(algorithm)
                    || encodedPublicKey.isBlank() || encodedPublicKey.length() > 2048
                    || Instant.EPOCH.equals(createdAt) || state == null
                    || notBefore.isBefore(createdAt)
                    || (notAfter != null && !notAfter.isAfter(notBefore))
                    || providerKeyVersion.length() > 255
                    || !validPublicKey(encodedPublicKey)) {
                throw new IllegalArgumentException("Evidence verification key policy is invalid");
            }
        }
    }

    /**
     * One immutable lifecycle fact in authority order.
     *
     * @param sequence strictly increasing authority-local sequence
     * @param eventId stable event identifier
     * @param keyId affected verification key
     * @param type lifecycle transition or compromise declaration
     * @param occurredAt time the authority recorded the event
     * @param effectiveAt time at which the state transition takes effect
     * @param revocationMode revocation policy; required for revocation/compromise events
     * @param invalidFrom earliest invalid signing time for retroactive revocation
     * @param reasonCode bounded machine-readable reason
     */
    public record LifecycleEvent(
            long sequence,
            String eventId,
            String keyId,
            EventType type,
            Instant occurredAt,
            Instant effectiveAt,
            RevocationMode revocationMode,
            Instant invalidFrom,
            String reasonCode
    ) {
        /** Normalizes and validates one lifecycle event without accepting free-form diagnostics. */
        public LifecycleEvent {
            eventId = normalized(eventId);
            keyId = normalized(keyId);
            occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
            effectiveAt = effectiveAt == null ? occurredAt : effectiveAt;
            reasonCode = normalized(reasonCode).toUpperCase(Locale.ROOT);
            boolean revocation = type == EventType.REVOKED || type == EventType.COMPROMISE_DECLARED;
            if (sequence < 1 || eventId.isBlank() || eventId.length() > 255 || containsControl(eventId)
                    || keyId.isBlank() || keyId.length() > 255 || type == null
                    || Instant.EPOCH.equals(occurredAt) || effectiveAt.isAfter(occurredAt)
                    || (revocation && revocationMode == null)
                    || (!revocation && (revocationMode != null || invalidFrom != null))
                    || (revocationMode == RevocationMode.RETROACTIVE && invalidFrom == null)
                    || (invalidFrom != null && invalidFrom.isAfter(effectiveAt))
                    || reasonCode.isBlank() || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Evidence key lifecycle event is invalid");
            }
        }
    }

    /**
     * Unsigned source snapshot supplied by a signer implementation.
     *
     * @param provider authority name
     * @param generatedAt authority snapshot time
     * @param expiresAt freshness deadline
     * @param activeKeyId active signing key
     * @param policyCompleteness event-history completeness
     * @param keys key policies
     * @param events lifecycle events
     */
    public record Source(
            String provider,
            Instant generatedAt,
            Instant expiresAt,
            String activeKeyId,
            PolicyCompleteness policyCompleteness,
            List<KeyPolicy> keys,
            List<LifecycleEvent> events
    ) {
        /** Normalizes, orders, and validates signer-owned source material. */
        public Source {
            provider = normalized(provider);
            generatedAt = generatedAt == null ? Instant.EPOCH : generatedAt;
            expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
            activeKeyId = normalized(activeKeyId);
            policyCompleteness = policyCompleteness == null
                    ? PolicyCompleteness.CURRENT_STATE_ONLY : policyCompleteness;
            keys = keys == null ? List.of() : keys.stream()
                    .sorted(Comparator.comparing(KeyPolicy::keyId)).toList();
            events = events == null ? List.of() : events.stream()
                    .sorted(Comparator.comparingLong(LifecycleEvent::sequence)).toList();
            requireSource(provider, generatedAt, expiresAt, activeKeyId, policyCompleteness,
                    keys, events);
        }
    }

    /** Canonical material whose fingerprint is externally pinned and cryptographically signed. */
    public record Material(
            String schemaVersion,
            String provider,
            Instant generatedAt,
            Instant expiresAt,
            String activeKeyId,
            PolicyCompleteness policyCompleteness,
            List<KeyPolicy> keys,
            List<LifecycleEvent> events
    ) {
        /** Applies the protocol version and immutable collection semantics. */
        public Material {
            schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
            keys = keys == null ? List.of() : List.copyOf(keys);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /** Validates the published snapshot and its exact attestation binding. */
    public EvidenceVerificationKeySet {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        snapshotFingerprint = normalized(snapshotFingerprint);
        provider = normalized(provider);
        activeKeyId = normalized(activeKeyId);
        keys = keys == null ? List.of() : List.copyOf(keys);
        events = events == null ? List.of() : List.copyOf(events);
        requireSource(provider, generatedAt, expiresAt, activeKeyId, policyCompleteness,
                keys, events);
        if (!SCHEMA_VERSION.equals(schemaVersion) || policyCompleteness == null
                || !FINGERPRINT.matcher(snapshotFingerprint).matches()
                || attestation == null || !attestation.signed()
                || !snapshotFingerprint.equals(attestation.materialFingerprint())
                || keys.stream().noneMatch(key -> key.keyId().equals(attestation.keyId()))) {
            throw new IllegalArgumentException("Signed evidence verification key set is invalid");
        }
    }

    /**
     * Produces and immediately verifies a signed public snapshot.
     *
     * @param mapper canonical JSON mapper
     * @param signer authority that owns the active private key
     * @param source immutable source key policy
     * @return signed key-set snapshot ready for fingerprint pinning
     */
    public static EvidenceVerificationKeySet publish(ObjectMapper mapper,
                                                     VisualEvidenceSigner signer,
                                                     Source source) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(source, "source");
        Material material = material(source);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, material, 256 * 1024);
        VisualRunEvidenceSeal seal = signer.seal(fingerprint);
        if (!source.activeKeyId().equals(seal.keyId())
                || !signer.verify(seal, fingerprint).valid()) {
            throw new IllegalStateException("Evidence key-set attestation failed local verification");
        }
        return new EvidenceVerificationKeySet("", fingerprint, source.provider(), source.generatedAt(),
                source.expiresAt(), source.activeKeyId(), source.policyCompleteness(), source.keys(),
                source.events(), seal);
    }

    /** @return exact canonical material represented by this published snapshot */
    public Material material() {
        return new Material(schemaVersion, provider, generatedAt, expiresAt, activeKeyId,
                policyCompleteness, keys, events);
    }

    private static Material material(Source source) {
        return new Material("", source.provider(), source.generatedAt(), source.expiresAt(),
                source.activeKeyId(), source.policyCompleteness(), source.keys(), source.events());
    }

    private static void requireSource(String provider, Instant generatedAt, Instant expiresAt,
                                      String activeKeyId, PolicyCompleteness policyCompleteness,
                                      List<KeyPolicy> keys,
                                      List<LifecycleEvent> events) {
        if (provider.isBlank() || provider.length() > 128 || containsControl(provider)
                || generatedAt == null || Instant.EPOCH.equals(generatedAt)
                || expiresAt == null || !expiresAt.isAfter(generatedAt)
                || activeKeyId.isBlank() || policyCompleteness == null
                || keys.isEmpty() || keys.size() > MAX_KEYS
                || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("Evidence verification key-set source is invalid");
        }
        Set<String> keyIds = new HashSet<>();
        Map<String, KeyPolicy> policiesById = new HashMap<>();
        long previousSequence = 0;
        Set<String> eventIds = new HashSet<>();
        for (KeyPolicy key : keys) {
            if (!keyIds.add(key.keyId())) {
                throw new IllegalArgumentException("Evidence verification key ids must be unique");
            }
            if (generatedAt.isBefore(key.createdAt())) {
                throw new IllegalArgumentException("Evidence verification key time is invalid");
            }
            policiesById.put(key.keyId(), key);
        }
        KeyPolicy active = keys.stream().filter(key -> key.keyId().equals(activeKeyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active evidence key is missing"));
        if (active.state() != KeyState.ACTIVE
                || keys.stream().filter(key -> key.state() == KeyState.ACTIVE).count() != 1) {
            throw new IllegalArgumentException("Evidence key set must identify exactly one active key");
        }
        for (LifecycleEvent event : events) {
            KeyPolicy key = policiesById.get(event.keyId());
            if (event.sequence() <= previousSequence || !eventIds.add(event.eventId())
                    || key == null || event.occurredAt().isAfter(generatedAt)
                    || event.effectiveAt().isBefore(key.createdAt())
                    || (event.invalidFrom() != null && event.invalidFrom().isBefore(key.createdAt()))) {
                throw new IllegalArgumentException("Evidence key lifecycle ordering is invalid");
            }
            previousSequence = event.sequence();
        }
        if (policyCompleteness == PolicyCompleteness.COMPLETE) {
            requireCompletePolicy(keys, events);
        }
    }

    private static void requireCompletePolicy(List<KeyPolicy> keys, List<LifecycleEvent> events) {
        Map<String, KeyPolicy> policiesById = new HashMap<>();
        keys.forEach(key -> policiesById.put(key.keyId(), key));
        Set<String> created = new HashSet<>();
        Set<String> activated = new HashSet<>();
        Map<String, EventType> latestState = new HashMap<>();
        for (LifecycleEvent event : events) {
            KeyPolicy key = policiesById.get(event.keyId());
            if (event.type() == EventType.CREATED) {
                if (!created.add(event.keyId()) || !event.effectiveAt().equals(key.createdAt())) {
                    throw new IllegalArgumentException("Evidence key creation history is inconsistent");
                }
                continue;
            }
            if (!created.contains(event.keyId())) {
                throw new IllegalArgumentException("Evidence key lifecycle begins before creation");
            }
            if (event.type() == EventType.ACTIVATED) {
                activated.add(event.keyId());
            }
            latestState.put(event.keyId(), event.type());
        }
        for (KeyPolicy key : keys) {
            EventType expected = switch (key.state()) {
                case ACTIVE -> EventType.ACTIVATED;
                case VERIFY_ONLY -> EventType.RETIRED;
                case DISABLED -> EventType.DISABLED;
                case REVOKED -> null;
            };
            EventType latest = latestState.get(key.keyId());
            boolean stateMatches = key.state() == KeyState.REVOKED
                    ? latest == EventType.REVOKED || latest == EventType.COMPROMISE_DECLARED
                    : latest == expected;
            if (!created.contains(key.keyId()) || !stateMatches
                    || ((key.state() == KeyState.ACTIVE || key.state() == KeyState.VERIFY_ONLY)
                    && !activated.contains(key.keyId()))) {
                throw new IllegalArgumentException("Evidence key lifecycle state is inconsistent");
            }
        }
    }

    private static boolean validPublicKey(String encodedPublicKey) {
        try {
            byte[] encoded = Base64.getDecoder().decode(encodedPublicKey);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
