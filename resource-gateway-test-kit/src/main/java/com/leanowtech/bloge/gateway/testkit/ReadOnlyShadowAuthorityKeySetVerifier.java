package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dependency-light verifier for Shadow authority key sets and contiguous distribution pages.
 *
 * <p>The verifier treats every JSON value as hostile. It applies packaged strict schemas,
 * recomputes domain-separated content addresses, verifies all root signatures against local
 * bootstrap trust, enforces exact scope/kind/issuer policy, checks cursor continuity, and carries
 * an irreversible retained-key state across pages. Failed verification never exposes keys.</p>
 */
public final class ReadOnlyShadowAuthorityKeySetVerifier {
    /** Root-signature domain shared with the server protocol. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_AUTHORITY_KEY_SET_V1";
    /** Maximum canonical signed material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 1024 * 1024;
    /** Maximum canonical complete publication size. */
    public static final int MAXIMUM_PUBLICATION_BYTES = 2 * 1024 * 1024;
    /** Maximum publication freshness window. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofHours(24);
    /** Maximum issuance-to-activation delay. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY = Duration.ofMinutes(5);

    private static final String PUBLICATION_VERSION =
            "resourceGateway.readOnlyShadowAuthorityKeySetPublication.v1";
    private static final String PAGE_VERSION =
            "resourceGateway.readOnlyShadowAuthorityKeySetPage.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates one stateless independent Shadow authority trust verifier. */
    public ReadOnlyShadowAuthorityKeySetVerifier() {
    }

    /** Bounded independent verification outcomes. */
    public enum Outcome {
        /** A contiguous non-terminal page was verified and may advance the checkpoint. */
        VERIFIED_PAGE,
        /** The verified checkpoint equals a currently fresh high-water publication. */
        VERIFIED_CURRENT,
        /** Schema, canonical material, temporal policy, or signature is invalid. */
        INVALID,
        /** Bootstrap-root keys are unavailable. */
        ROOTS_UNAVAILABLE,
        /** Local root, threshold, policy-generation, or lifecycle policy rejected content. */
        POLICY_REJECTED,
        /** Scope, authority kind, issuer, key-set, or trust-domain binding drifted. */
        IDENTITY_MISMATCH,
        /** Current high-water publication is outside its online freshness window. */
        WINDOW_REJECTED,
        /** Generation, predecessor, cursor, or retained-key continuity failed. */
        CHAIN_REJECTED
    }

    /**
     * Immutable local policy for one authority key-set stream.
     *
     * @param scope complete enterprise scope
     * @param publicationType exact Shadow authority protocol
     * @param issuer exact delegated authority
     * @param keySetId stable key-set stream
     * @param rootTrustDomain independently configured bootstrap trust domain
     * @param rootThreshold required independent root signatures
     * @param acceptedPolicyFingerprints non-empty policy-generation allowlist
     */
    public record ExpectedBinding(
            ReadOnlyShadowAuthorityBinding.Scope scope,
            ReadOnlyShadowAuthorityBinding.Type publicationType,
            String issuer,
            String keySetId,
            String rootTrustDomain,
            int rootThreshold,
            Set<String> acceptedPolicyFingerprints
    ) {
        /** Validates detached local trust policy. */
        public ExpectedBinding {
            scope = Objects.requireNonNull(scope, "scope");
            publicationType = Objects.requireNonNull(publicationType, "publicationType");
            issuer = identifier(issuer, "issuer");
            keySetId = identifier(keySetId, "keySetId");
            rootTrustDomain = identifier(rootTrustDomain, "rootTrustDomain");
            if (rootThreshold < 1 || rootThreshold > 16) {
                throw new IllegalArgumentException("rootThreshold is outside protocol bounds");
            }
            acceptedPolicyFingerprints = acceptedPolicyFingerprints == null
                    ? Set.of() : Set.copyOf(acceptedPolicyFingerprints);
            if (acceptedPolicyFingerprints.isEmpty()
                    || acceptedPolicyFingerprints.stream().anyMatch(
                    value -> !isFingerprint(value))) {
                throw new IllegalArgumentException(
                        "acceptedPolicyFingerprints must be canonical and non-empty");
            }
        }

        private boolean matches(JsonNode material) {
            return material != null && material.isObject()
                    && scope.matches(material.path("scope"))
                    && publicationType.name().equals(
                    material.path("publicationKind").asText())
                    && issuer.equals(material.path("issuer").asText())
                    && keySetId.equals(material.path("keySetId").asText())
                    && rootTrustDomain.equals(material.path("rootTrustDomain").asText())
                    && rootThreshold == material.path("rootThreshold").asInt()
                    && acceptedPolicyFingerprints.contains(
                    material.path("policyFingerprint").asText());
        }
    }

    /**
     * Locally pinned bootstrap-root public key.
     *
     * @param authorityId independent root authority identity
     * @param keyId exact root key identity
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 public key
     * @param notBefore inclusive root-signing bound
     * @param notAfter exclusive root-signing bound
     * @param state current local root lifecycle
     */
    public record RootVerificationKey(
            String authorityId,
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant notAfter,
            State state
    ) {
        /** Validates one detached local root policy. */
        public RootVerificationKey {
            authorityId = identifier(authorityId, "authorityId");
            keyId = identifier(keyId, "keyId");
            algorithm = normalized(algorithm);
            encodedPublicKey = canonicalBase64(encodedPublicKey);
            notBefore = requiredTime(notBefore, "notBefore");
            notAfter = requiredTime(notAfter, "notAfter");
            state = state == null ? State.REVOKED : state;
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("bootstrap-root key is invalid");
            }
        }

        /** Bootstrap-root lifecycle. */
        public enum State {
            /** Root may verify current publications. */
            ACTIVE,
            /** Root may verify historical signatures but must not sign new publications. */
            RETIRED,
            /** Root must not verify any publication. */
            REVOKED
        }

        private boolean verificationAllowed() {
            return state == State.ACTIVE || state == State.RETIRED;
        }
    }

    /**
     * Durable consumer checkpoint including the retained key lifecycle needed for the next page.
     *
     * @param keySetId stable stream identity
     * @param generation positive accepted generation
     * @param publicationFingerprint exact accepted content address
     * @param keys verified retained public authority keys
     */
    public record TrustedState(
            String keySetId,
            long generation,
            String publicationFingerprint,
            List<ReadOnlyShadowAuthorityVerificationKey> keys
    ) {
        /** Validates one detached checkpoint. */
        public TrustedState {
            keySetId = identifier(keySetId, "keySetId");
            publicationFingerprint = fingerprint(
                    publicationFingerprint, "publicationFingerprint");
            keys = keys == null ? List.of() : List.copyOf(keys);
            if (generation < 1 || keys.isEmpty()) {
                throw new IllegalArgumentException("trusted key-set state is invalid");
            }
            keys.forEach(key -> Objects.requireNonNull(key, "key"));
        }
    }

    /**
     * Payload-free result with a new checkpoint only after complete verification.
     *
     * @param outcome bounded outcome
     * @param reasonCode stable machine-readable reason
     * @param trustedState new checkpoint, present only on verified non-empty results
     * @param highWaterGeneration bounded observed high-water generation
     * @param highWaterPublicationFingerprint bounded observed high-water fingerprint
     * @param hasMore whether another page is required
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            TrustedState trustedState,
            long highWaterGeneration,
            String highWaterPublicationFingerprint,
            boolean hasMore
    ) {
        /** Prevents failure outcomes from leaking untrusted key material. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = required(reasonCode, "reasonCode", 255);
            highWaterPublicationFingerprint =
                    optionalFingerprint(highWaterPublicationFingerprint);
            boolean successful = outcome == Outcome.VERIFIED_PAGE
                    || outcome == Outcome.VERIFIED_CURRENT;
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    || highWaterGeneration < 0
                    || !successful && trustedState != null) {
                throw new IllegalArgumentException("key-set verification result is invalid");
            }
        }

        /**
         * Reports whether all untrusted material needed for this result was verified.
         *
         * @return true for a valid checkpoint page or current high-water snapshot
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED_PAGE
                    || outcome == Outcome.VERIFIED_CURRENT;
        }

        /**
         * Reports whether the successor checkpoint equals a freshly verified current head.
         *
         * @return true only when the returned state equals a fresh current high-water
         */
        public boolean current() {
            return outcome == Outcome.VERIFIED_CURRENT;
        }
    }

    /**
     * Verifies one bounded distribution page and returns its durable successor checkpoint.
     *
     * @param page untrusted decoded page
     * @param binding exact local stream policy
     * @param roots locally pinned root keys
     * @param previous last durable checkpoint, or null only before genesis
     * @param verificationTime trusted current time
     * @return bounded result
     */
    public VerificationResult verifyPage(
            JsonNode page,
            ExpectedBinding binding,
            List<RootVerificationKey> roots,
            TrustedState previous,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.fromPage(page);
        try {
            CapabilityMirrorSchemaValidator.require(
                    page, CapabilityMirrorProtocol.READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PAGE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_AUTHORITY_KEY_SET_PAGE_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return failed(Outcome.INVALID, "PAGE_SCHEMA_INVALID", coordinates);
        }
        if (binding == null || !pageBindingMatches(page, binding)) {
            return failed(Outcome.IDENTITY_MISMATCH, "PAGE_BINDING_MISMATCH", coordinates);
        }
        if (!highWaterMatches(page, binding)) {
            return failed(Outcome.INVALID, "PAGE_HIGH_WATER_INVALID", coordinates);
        }
        long after = page.path("afterGeneration").asLong();
        String afterFingerprint = page.path("afterPublicationFingerprint").asText();
        if (previous != null && !trustedStateMatches(previous, binding)) {
            return failed(Outcome.IDENTITY_MISMATCH,
                    "PAGE_TRUSTED_STATE_BINDING_MISMATCH", coordinates);
        }
        if (previous == null ? after != 0 || !afterFingerprint.isBlank()
                : after != previous.generation()
                || !afterFingerprint.equals(previous.publicationFingerprint())
                || !previous.keySetId().equals(binding.keySetId())) {
            return failed(Outcome.CHAIN_REJECTED, "PAGE_CHECKPOINT_MISMATCH", coordinates);
        }
        TrustedState rolling = previous;
        for (JsonNode publication : page.path("publications")) {
            Instant historicalTime;
            try {
                historicalTime = Instant.parse(
                        publication.at("/material/notBefore").asText());
            } catch (RuntimeException invalid) {
                return failed(Outcome.INVALID, "PUBLICATION_TIME_INVALID", coordinates);
            }
            PublicationResult verified = verifyPublication(
                    publication, binding, roots, rolling, historicalTime);
            if (!verified.verified()) {
                return failed(verified.outcome(), verified.reasonCode(), coordinates);
            }
            rolling = verified.trustedState();
        }
        long through = page.path("throughGeneration").asLong();
        long highWater = page.path("highWaterGeneration").asLong();
        boolean hasMore = page.path("hasMore").asBoolean();
        long expectedThrough = rolling == null ? 0 : rolling.generation();
        if (through != expectedThrough || hasMore != (through < highWater)) {
            return failed(Outcome.CHAIN_REJECTED, "PAGE_CONTINUITY_INVALID", coordinates);
        }
        if (!hasMore && highWater > 0) {
            JsonNode head = page.path("highWaterPublication");
            PublicationResult current = verifyPublication(
                    head, binding, roots, rolling, verificationTime);
            if (!current.verified()) {
                return failed(current.outcome(), current.reasonCode(), coordinates);
            }
            if (current.trustedState().generation() != highWater
                    || !current.trustedState().publicationFingerprint().equals(
                    page.path("highWaterPublicationFingerprint").asText())) {
                return failed(Outcome.CHAIN_REJECTED,
                        "PAGE_HIGH_WATER_MISMATCH", coordinates);
            }
            return success(Outcome.VERIFIED_CURRENT, current.trustedState(), coordinates, false);
        }
        return success(Outcome.VERIFIED_PAGE, rolling, coordinates, hasMore);
    }

    private PublicationResult verifyPublication(
            JsonNode publication,
            ExpectedBinding binding,
            List<RootVerificationKey> roots,
            TrustedState previous,
            Instant verificationTime) {
        try {
            CapabilityMirrorSchemaValidator.require(
                    publication,
                    CapabilityMirrorProtocol.READ_ONLY_SHADOW_AUTHORITY_KEY_SET_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_AUTHORITY_KEY_SET_SCHEMA_INVALID");
            requireProtocolMaterial(publication);
        } catch (RuntimeException invalid) {
            return PublicationResult.failed(Outcome.INVALID, "PUBLICATION_SCHEMA_INVALID");
        }
        JsonNode material = publication.path("material");
        if (!binding.matches(material)
                || !PUBLICATION_VERSION.equals(publication.path("schemaVersion").asText())) {
            return PublicationResult.failed(
                    Outcome.IDENTITY_MISMATCH, "PUBLICATION_BINDING_MISMATCH");
        }
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    signatureMaterial(publication), MAXIMUM_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    publication.path("materialFingerprint").asText())) {
                return PublicationResult.failed(
                        Outcome.INVALID, "PUBLICATION_MATERIAL_FINGERPRINT_INVALID");
            }
            String publicationFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    publicationMaterial(publication), MAXIMUM_PUBLICATION_BYTES);
            if (!publicationFingerprint.equals(
                    publication.path("publicationFingerprint").asText())) {
                return PublicationResult.failed(
                        Outcome.INVALID, "PUBLICATION_FINGERPRINT_INVALID");
            }
        } catch (RuntimeException invalid) {
            return PublicationResult.failed(
                    Outcome.INVALID, "PUBLICATION_CANONICAL_MATERIAL_INVALID");
        }
        Instant notBefore = Instant.parse(material.path("notBefore").asText());
        Instant expiresAt = Instant.parse(material.path("expiresAt").asText());
        if (verificationTime == null || verificationTime.isBefore(notBefore)
                || !verificationTime.isBefore(expiresAt)) {
            return PublicationResult.failed(
                    Outcome.WINDOW_REJECTED, "PUBLICATION_OUTSIDE_VALIDITY_WINDOW");
        }
        String chainFailure = chainFailure(publication, previous);
        if (!chainFailure.isBlank()) {
            return PublicationResult.failed(Outcome.CHAIN_REJECTED, chainFailure);
        }
        if (roots == null || roots.isEmpty()) {
            return PublicationResult.failed(
                    Outcome.ROOTS_UNAVAILABLE, "BOOTSTRAP_ROOTS_UNAVAILABLE");
        }
        Map<RootCoordinate, RootVerificationKey> rootsByCoordinate = new HashMap<>();
        Set<String> rootMaterials = new HashSet<>();
        for (RootVerificationKey root : roots) {
            if (root == null || rootsByCoordinate.put(
                    new RootCoordinate(root.authorityId(), root.keyId()), root) != null
                    || !rootMaterials.add(root.algorithm() + '\0' + root.encodedPublicKey())) {
                return PublicationResult.failed(
                        Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOTS_AMBIGUOUS");
            }
        }
        for (JsonNode signature : publication.path("signatures")) {
            RootVerificationKey root = rootsByCoordinate.get(new RootCoordinate(
                    signature.path("authorityId").asText(), signature.path("keyId").asText()));
            if (root == null) {
                return PublicationResult.failed(
                        Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_UNKNOWN");
            }
            Instant signedAt = Instant.parse(signature.path("signedAt").asText());
            if (!root.verificationAllowed()
                    || !root.algorithm().equals(signature.path("algorithm").asText())
                    || signedAt.isBefore(root.notBefore())
                    || !signedAt.isBefore(root.notAfter())) {
                return PublicationResult.failed(
                        Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_POLICY_REJECTED");
            }
            try {
                if (!EvidenceVerificationSupport.verifyEd25519(
                        publication.path("materialFingerprint").asText(),
                        signature.path("signature").asText(),
                        root.encodedPublicKey())) {
                    return PublicationResult.failed(
                            Outcome.INVALID, "BOOTSTRAP_ROOT_SIGNATURE_INVALID");
                }
            } catch (GeneralSecurityException | RuntimeException invalid) {
                return PublicationResult.failed(
                        Outcome.INVALID, "BOOTSTRAP_ROOT_SIGNATURE_MATERIAL_INVALID");
            }
        }
        if (publication.path("signatures").size() < binding.rootThreshold()) {
            return PublicationResult.failed(
                    Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_THRESHOLD_NOT_MET");
        }
        List<ReadOnlyShadowAuthorityVerificationKey> keys;
        try {
            keys = authorityKeys(material, binding);
            requireLegalKeyEvolution(previous, keys);
        } catch (RuntimeException invalid) {
            return PublicationResult.failed(
                    Outcome.CHAIN_REJECTED, "AUTHORITY_KEY_LIFECYCLE_INVALID");
        }
        return PublicationResult.verified(new TrustedState(
                binding.keySetId(), material.path("generation").asLong(),
                publication.path("publicationFingerprint").asText(), keys));
    }

    private static void requireProtocolMaterial(JsonNode publication) {
        JsonNode material = publication.path("material");
        Instant issuedAt = Instant.parse(material.path("issuedAt").asText());
        Instant notBefore = Instant.parse(material.path("notBefore").asText());
        Instant expiresAt = Instant.parse(material.path("expiresAt").asText());
        if (notBefore.isBefore(issuedAt)
                || Duration.between(issuedAt, notBefore)
                .compareTo(MAXIMUM_ACTIVATION_DELAY) > 0
                || !expiresAt.isAfter(notBefore)
                || Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("publication window is invalid");
        }
        String previousKey = "";
        for (JsonNode key : material.path("keys")) {
            String keyId = identifier(key.path("keyId").asText(), "keyId");
            if (!previousKey.isBlank() && previousKey.compareTo(keyId) >= 0) {
                throw new IllegalArgumentException("authority keys are not canonical");
            }
            previousKey = keyId;
            canonicalBase64(key.path("encodedPublicKey").asText());
            Instant keyNotBefore = Instant.parse(key.path("notBefore").asText());
            Instant keyNotAfter = Instant.parse(key.path("notAfter").asText());
            if (!keyNotAfter.isAfter(keyNotBefore)) {
                throw new IllegalArgumentException("authority key window is invalid");
            }
        }
        String previousSignature = "";
        Set<String> authorities = new HashSet<>();
        for (JsonNode signature : publication.path("signatures")) {
            String coordinate = signature.path("authorityId").asText()
                    + '\0' + signature.path("keyId").asText();
            if (!previousSignature.isBlank() && previousSignature.compareTo(coordinate) >= 0
                    || !authorities.add(signature.path("authorityId").asText())) {
                throw new IllegalArgumentException("root signatures are not canonical");
            }
            previousSignature = coordinate;
            Instant signedAt = Instant.parse(signature.path("signedAt").asText());
            if (signedAt.isBefore(issuedAt) || signedAt.isAfter(notBefore)) {
                throw new IllegalArgumentException("root signature time is invalid");
            }
            canonicalBase64(signature.path("signature").asText());
        }
    }

    private static String chainFailure(JsonNode publication, TrustedState previous) {
        JsonNode material = publication.path("material");
        long generation = material.path("generation").asLong();
        String fingerprint = publication.path("publicationFingerprint").asText();
        if (previous == null) {
            return generation == 1 && material.path("previousPublicationFingerprint").asText().isBlank()
                    ? "" : "PUBLICATION_BOOTSTRAP_GENERATION_INVALID";
        }
        if (!previous.keySetId().equals(material.path("keySetId").asText())) {
            return "PUBLICATION_FLOOR_KEY_SET_MISMATCH";
        }
        if (generation == previous.generation()) {
            return fingerprint.equals(previous.publicationFingerprint())
                    ? "" : "PUBLICATION_GENERATION_FORK";
        }
        if (generation < previous.generation()) {
            return "PUBLICATION_GENERATION_ROLLBACK";
        }
        if (generation > previous.generation() + 1) {
            return "PUBLICATION_GENERATION_GAP";
        }
        return material.path("previousPublicationFingerprint").asText()
                .equals(previous.publicationFingerprint())
                ? "" : "PUBLICATION_PREDECESSOR_MISMATCH";
    }

    private static List<ReadOnlyShadowAuthorityVerificationKey> authorityKeys(
            JsonNode material, ExpectedBinding binding) {
        List<ReadOnlyShadowAuthorityVerificationKey> keys = new ArrayList<>();
        for (JsonNode key : material.path("keys")) {
            String stateName = key.path("state").asText();
            Instant retiredAt = key.path("retiredAt").isNull()
                    ? null : Instant.parse(key.path("retiredAt").asText());
            keys.add(new ReadOnlyShadowAuthorityVerificationKey(
                    key.path("keyId").asText(), key.path("algorithm").asText(),
                    key.path("encodedPublicKey").asText(), binding.issuer(), binding.scope(),
                    binding.publicationType(),
                    Instant.parse(key.path("notBefore").asText()),
                    Instant.parse(key.path("notAfter").asText()), retiredAt,
                    ReadOnlyShadowAuthorityVerificationKey.State.valueOf(stateName)));
        }
        return List.copyOf(keys);
    }

    private static void requireLegalKeyEvolution(
            TrustedState previous,
            List<ReadOnlyShadowAuthorityVerificationKey> next) {
        if (previous == null) {
            return;
        }
        Map<String, ReadOnlyShadowAuthorityVerificationKey> byId = new HashMap<>();
        next.forEach(key -> byId.put(key.keyId(), key));
        for (ReadOnlyShadowAuthorityVerificationKey before : previous.keys()) {
            ReadOnlyShadowAuthorityVerificationKey after = byId.get(before.keyId());
            if (after == null || !before.algorithm().equals(after.algorithm())
                    || !before.encodedPublicKey().equals(after.encodedPublicKey())
                    || !before.notBefore().equals(after.notBefore())
                    || !before.notAfter().equals(after.notAfter())
                    || !legalTransition(before, after)) {
                throw new IllegalArgumentException("authority key lifecycle is invalid");
            }
        }
    }

    private static boolean legalTransition(
            ReadOnlyShadowAuthorityVerificationKey before,
            ReadOnlyShadowAuthorityVerificationKey after) {
        return switch (before.state()) {
            case ACTIVE -> after.state()
                    == ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE
                    && after.retiredAt() == null
                    || after.state() == ReadOnlyShadowAuthorityVerificationKey.State.RETIRED
                    && after.retiredAt() != null
                    || after.state() == ReadOnlyShadowAuthorityVerificationKey.State.REVOKED
                    && after.retiredAt() == null;
            case RETIRED -> after.state()
                    == ReadOnlyShadowAuthorityVerificationKey.State.RETIRED
                    && before.retiredAt().equals(after.retiredAt())
                    || after.state() == ReadOnlyShadowAuthorityVerificationKey.State.REVOKED
                    && after.retiredAt() == null;
            case REVOKED -> after.state()
                    == ReadOnlyShadowAuthorityVerificationKey.State.REVOKED
                    && after.retiredAt() == null;
        };
    }

    private static boolean pageBindingMatches(JsonNode page, ExpectedBinding binding) {
        return PAGE_VERSION.equals(page.path("schemaVersion").asText())
                && binding.scope().matches(page.path("scope"))
                && binding.publicationType().name().equals(
                page.path("publicationKind").asText())
                && binding.issuer().equals(page.path("issuer").asText())
                && binding.keySetId().equals(page.path("keySetId").asText());
    }

    private static boolean highWaterMatches(
            JsonNode page,
            ExpectedBinding binding) {
        long generation = page.path("highWaterGeneration").asLong();
        if (generation == 0) {
            return page.path("highWaterPublication").isNull()
                    && page.path("highWaterPublicationFingerprint").asText().isBlank();
        }
        JsonNode publication = page.path("highWaterPublication");
        JsonNode material = publication.path("material");
        String fingerprint = page.path(
                "highWaterPublicationFingerprint").asText();
        try {
            requireProtocolMaterial(publication);
            return PUBLICATION_VERSION.equals(
                    publication.path("schemaVersion").asText())
                    && binding.matches(material)
                    && material.path("generation").asLong() == generation
                    && fingerprint.equals(
                    publication.path("publicationFingerprint").asText())
                    && publication.path("materialFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256Bounded(
                            signatureMaterial(publication),
                            MAXIMUM_MATERIAL_BYTES))
                    && fingerprint.equals(
                    EvidenceVerificationSupport.sha256Bounded(
                            publicationMaterial(publication),
                            MAXIMUM_PUBLICATION_BYTES));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean trustedStateMatches(
            TrustedState state,
            ExpectedBinding binding) {
        if (!binding.keySetId().equals(state.keySetId())) {
            return false;
        }
        String previousKeyId = "";
        for (ReadOnlyShadowAuthorityVerificationKey key : state.keys()) {
            if (!binding.issuer().equals(key.issuer())
                    || !binding.scope().equals(key.scope())
                    || binding.publicationType() != key.publicationType()
                    || !previousKeyId.isBlank()
                    && previousKeyId.compareTo(key.keyId()) >= 0) {
                return false;
            }
            previousKeyId = key.keyId();
        }
        return true;
    }

    private static ObjectNode signatureMaterial(JsonNode publication) {
        ObjectNode value = JSON.createObjectNode();
        value.put("domain", SIGNATURE_DOMAIN);
        value.put("schemaVersion", publication.path("schemaVersion").asText());
        value.set("material", publication.path("material"));
        return value;
    }

    private static ObjectNode publicationMaterial(JsonNode publication) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", publication.path("schemaVersion").asText());
        value.put("publicationFingerprint", "");
        value.put("materialFingerprint",
                publication.path("materialFingerprint").asText());
        value.set("material", publication.path("material"));
        value.set("signatures", publication.path("signatures"));
        return value;
    }

    private static VerificationResult failed(
            Outcome outcome, String reason, Coordinates coordinates) {
        return new VerificationResult(outcome, reason, null,
                coordinates.highWaterGeneration(), coordinates.highWaterFingerprint(), false);
    }

    private static VerificationResult success(
            Outcome outcome,
            TrustedState state,
            Coordinates coordinates,
            boolean hasMore) {
        return new VerificationResult(outcome, "VERIFIED", state,
                coordinates.highWaterGeneration(), coordinates.highWaterFingerprint(), hasMore);
    }

    private static String canonicalBase64(String value) {
        String exact = normalized(value);
        try {
            byte[] decoded = Base64.getDecoder().decode(exact);
            if (decoded.length == 0
                    || !exact.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException("base64 is not canonical");
            }
            return exact;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("base64 is invalid", invalid);
        }
    }

    private static Instant requiredTime(Instant value, String field) {
        if (value == null || Instant.EPOCH.equals(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String identifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = normalized(value);
        if (!isFingerprint(exact)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value) {
        String exact = normalized(value);
        return exact.isBlank() || isFingerprint(exact) ? exact : "";
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("sha256:[a-f0-9]{64}");
    }

    private static String required(String value, String field, int maximum) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record RootCoordinate(String authorityId, String keyId) {
    }

    private record PublicationResult(
            boolean verified,
            Outcome outcome,
            String reasonCode,
            TrustedState trustedState) {
        private static PublicationResult verified(TrustedState state) {
            return new PublicationResult(true, Outcome.VERIFIED_PAGE, "VERIFIED", state);
        }

        private static PublicationResult failed(Outcome outcome, String reason) {
            return new PublicationResult(false, outcome, reason, null);
        }
    }

    private record Coordinates(long highWaterGeneration, String highWaterFingerprint) {
        private static Coordinates fromPage(JsonNode page) {
            if (page == null || !page.isObject()) {
                return new Coordinates(0, "");
            }
            return new Coordinates(
                    Math.max(0, page.path("highWaterGeneration").asLong()),
                    optionalFingerprint(
                            page.path("highWaterPublicationFingerprint").asText()));
        }
    }
}
