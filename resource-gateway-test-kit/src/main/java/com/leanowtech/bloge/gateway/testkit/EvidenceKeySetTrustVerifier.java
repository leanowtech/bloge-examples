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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent verifier for externally authorized evidence key-set transparency pages.
 *
 * <p>The verifier consumes only caller-owned trust anchors, a durable caller checkpoint, and the
 * schema-validated wire bundle. It recomputes every publication fingerprint and authority quorum,
 * rejects rollback/fork/gap/time regression, permanently remembers revoked pins, and delegates the
 * nested key-set cryptography to {@link TestSuiteEvidenceVerifier#verifyKeySet} only after the trust
 * log has selected the exact snapshot fingerprint.</p>
 */
public final class EvidenceKeySetTrustVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private final Clock clock;
    private final TestSuiteEvidenceVerifier keySetVerifier;

    /** Creates a verifier using the system UTC clock. */
    public EvidenceKeySetTrustVerifier() {
        this(Clock.systemUTC());
    }

    EvidenceKeySetTrustVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.keySetVerifier = new TestSuiteEvidenceVerifier(this.clock);
    }

    /** Bounded trust-chain verification outcomes. */
    public enum Outcome {
        /** Complete chain, fresh head, authority quorum, pin, and nested key set all passed. */
        VERIFIED,
        /** Page is valid so far, but another bounded page is required before trusting a pin. */
        CATCH_UP_REQUIRED,
        /** Canonical material, signature, chain, or page shape is invalid. */
        INVALID,
        /** External policy, time, revocation, or quorum rules reject the response. */
        POLICY_REJECTED
    }

    /**
     * Payload-free trust decision and caller state advancement.
     *
     * @param outcome bounded trust outcome
     * @param reasonCode stable machine-readable reason
     * @param checkpoint new durable checkpoint after all verified page entries
     * @param trustedSnapshotFingerprint exact key-set pin selected by a terminal head
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            EvidenceTrustCheckpoint checkpoint,
            String trustedSnapshotFingerprint
    ) {
        /** Normalizes log-safe output. */
        public VerificationResult {
            if (outcome == null) {
                throw new IllegalArgumentException("Evidence trust verification outcome is required");
            }
            reasonCode = normalized(reasonCode);
            trustedSnapshotFingerprint = normalized(trustedSnapshotFingerprint);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Evidence trust verification reason is invalid");
            }
        }

        /**
         * Tests whether the complete current trust and nested key-set decision passed.
         *
         * @return true only for a terminal verified decision
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one bounded response page and advances caller-owned anti-rollback state.
     *
     * @param bundle untrusted schema-validated trust page
     * @param policy independently provisioned authority policy
     * @param checkpoint last caller checkpoint, or null only for genesis bootstrap
     * @return payload-free trust decision and next checkpoint
     */
    public VerificationResult verify(EvidenceKeySetTrustBundle bundle,
                                     EvidenceTrustPolicy policy,
                                     EvidenceTrustCheckpoint checkpoint) {
        if (bundle == null) {
            return result(Outcome.INVALID, "TRUST_BUNDLE_MISSING", checkpoint, "");
        }
        if (policy == null) {
            return result(Outcome.POLICY_REJECTED, "TRUST_POLICY_MISSING", checkpoint, "");
        }
        if (!policy.trustDomain().equals(bundle.trustDomain())
                || !policy.logId().equals(bundle.logId())) {
            return result(Outcome.POLICY_REJECTED, "TRUST_LOG_IDENTITY_MISMATCH", checkpoint, "");
        }
        if (bundle.generatedAt().isAfter(clock.instant().plus(CLOCK_SKEW))) {
            return result(Outcome.POLICY_REJECTED, "TRUST_BUNDLE_NOT_YET_VALID", checkpoint, "");
        }
        VerificationResult checkpointFailure = validateCheckpoint(bundle, policy, checkpoint);
        if (checkpointFailure != null) {
            return checkpointFailure;
        }
        List<EvidenceKeySetTrustBundle.Publication> page = bundle.publications();
        if (page.size() > 256) {
            return result(Outcome.INVALID, "TRUST_PAGE_LIMIT_EXCEEDED", checkpoint, "");
        }
        long expectedSequence = checkpoint == null ? 1 : checkpoint.sequence() + 1;
        String previousFingerprint = checkpoint == null ? "" : checkpoint.publicationFingerprint();
        long recoveryEpoch = checkpoint == null ? 0 : checkpoint.recoveryEpoch();
        Instant previousPublishedAt = checkpoint == null ? null : checkpoint.publishedAt();
        Set<String> permanentlyRevoked = new HashSet<>(checkpoint == null
                ? Set.of() : checkpoint.permanentlyRevokedPins());
        EvidenceTrustCheckpoint advanced = checkpoint;
        for (EvidenceKeySetTrustBundle.Publication publication : page) {
            VerificationResult publicationFailure = verifyPublication(publication, policy,
                    expectedSequence, previousFingerprint, recoveryEpoch, previousPublishedAt,
                    permanentlyRevoked, false);
            if (publicationFailure != null) {
                return publicationFailure;
            }
            Set<String> newlyRevoked = revokedFingerprints(publication);
            newlyRevoked.removeAll(permanentlyRevoked);
            permanentlyRevoked.addAll(newlyRevoked);
            recoveryEpoch = publication.recoveryEpoch();
            previousFingerprint = publication.publicationFingerprint();
            previousPublishedAt = publication.publishedAt();
            expectedSequence++;
            advanced = checkpoint(publication, permanentlyRevoked);
        }
        long expectedThrough = page.isEmpty()
                ? bundle.afterSequence() : page.getLast().sequence();
        if (bundle.throughSequence() != expectedThrough
                || bundle.hasMore() != (bundle.throughSequence() < bundle.highWaterSequence())) {
            return result(Outcome.INVALID, "TRUST_PAGE_METADATA_INVALID", checkpoint, "");
        }
        EvidenceKeySetTrustBundle.Publication head = bundle.headPublication();
        VerificationResult headFailure = verifyHeadEnvelope(bundle, policy, head, permanentlyRevoked);
        if (headFailure != null) {
            return headFailure;
        }
        if (bundle.hasMore()) {
            if (advanced == null) {
                return result(Outcome.INVALID, "TRUST_PAGE_EMPTY", checkpoint, "");
            }
            return result(Outcome.CATCH_UP_REQUIRED, "TRUST_LOG_CATCH_UP_REQUIRED", advanced, "");
        }
        boolean headMatchesVerifiedState = advanced != null
                && advanced.sequence() == head.sequence()
                && advanced.publicationFingerprint().equals(head.publicationFingerprint());
        if (advanced == null || advanced.sequence() != bundle.highWaterSequence()
                || !advanced.publicationFingerprint().equals(bundle.headPublicationFingerprint())
                || !headMatchesVerifiedState
                || (!page.isEmpty() && !samePublication(page.getLast(), head))) {
            return result(Outcome.INVALID, "TRUST_HEAD_CHAIN_MISMATCH", checkpoint, "");
        }
        if (!head.expiresAt().isAfter(clock.instant())) {
            return result(Outcome.POLICY_REJECTED, "TRUST_PUBLICATION_STALE", checkpoint, "");
        }
        EvidenceKeySetTrustBundle.SnapshotPin active = head.pins().stream()
                .filter(pin -> pin.state() == EvidenceKeySetTrustBundle.PinState.ACTIVE)
                .findFirst().orElse(null);
        if (active == null || !active.acceptedAt(clock.instant())
                || !active.snapshotFingerprint().equals(bundle.keySet().snapshotFingerprint())) {
            return result(Outcome.POLICY_REJECTED, "TRUST_ACTIVE_PIN_MISMATCH", checkpoint, "");
        }
        TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                keySetVerifier.verifyKeySet(bundle.keySet(), active.snapshotFingerprint());
        if (!keySetResult.verified()) {
            Outcome outcome = keySetResult.outcome() == TestSuiteEvidenceVerifier.Outcome.INVALID
                    ? Outcome.INVALID : Outcome.POLICY_REJECTED;
            return result(outcome, keySetResult.reasonCode(), checkpoint, "");
        }
        return result(Outcome.VERIFIED, "VERIFIED", advanced, active.snapshotFingerprint());
    }

    private VerificationResult validateCheckpoint(
            EvidenceKeySetTrustBundle bundle, EvidenceTrustPolicy policy,
            EvidenceTrustCheckpoint checkpoint) {
        if (checkpoint == null) {
            return bundle.afterSequence() == 0 ? null
                    : result(Outcome.POLICY_REJECTED, "TRUST_CHECKPOINT_REQUIRED", null, "");
        }
        if (!policy.trustDomain().equals(checkpoint.trustDomain())
                || !policy.logId().equals(checkpoint.logId())) {
            return result(Outcome.POLICY_REJECTED, "TRUST_CHECKPOINT_IDENTITY_MISMATCH",
                    checkpoint, "");
        }
        if (bundle.afterSequence() != checkpoint.sequence()) {
            return result(Outcome.POLICY_REJECTED, "TRUST_CURSOR_CHECKPOINT_MISMATCH",
                    checkpoint, "");
        }
        if (bundle.highWaterSequence() < checkpoint.sequence()) {
            return result(Outcome.INVALID, "TRUST_LOG_ROLLBACK_DETECTED", checkpoint, "");
        }
        if (bundle.highWaterSequence() == checkpoint.sequence()
                && !bundle.headPublicationFingerprint().equals(checkpoint.publicationFingerprint())) {
            return result(Outcome.INVALID, "TRUST_LOG_SPLIT_VIEW_DETECTED", checkpoint, "");
        }
        return null;
    }

    private VerificationResult verifyHeadEnvelope(
            EvidenceKeySetTrustBundle bundle, EvidenceTrustPolicy policy,
            EvidenceKeySetTrustBundle.Publication head, Set<String> permanentlyRevoked) {
        if (head.sequence() != bundle.highWaterSequence()
                || !head.publicationFingerprint().equals(bundle.headPublicationFingerprint())
                || !head.trustDomain().equals(bundle.trustDomain())
                || !head.logId().equals(bundle.logId())) {
            return result(Outcome.INVALID, "TRUST_HEAD_METADATA_INVALID", null, "");
        }
        return verifyPublication(head, policy, head.sequence(), head.previousPublicationFingerprint(),
                head.recoveryEpoch(), null, permanentlyRevoked, true);
    }

    private VerificationResult verifyPublication(
            EvidenceKeySetTrustBundle.Publication publication, EvidenceTrustPolicy policy,
            long expectedSequence, String previousFingerprint, long previousRecoveryEpoch,
            Instant previousPublishedAt, Set<String> permanentlyRevoked, boolean headOnly) {
        if (!policy.trustDomain().equals(publication.trustDomain())
                || !policy.logId().equals(publication.logId())) {
            return result(Outcome.POLICY_REJECTED, "TRUST_LOG_IDENTITY_MISMATCH", null, "");
        }
        if (!headOnly && (publication.sequence() != expectedSequence
                || !publication.previousPublicationFingerprint().equals(previousFingerprint))) {
            return result(Outcome.INVALID,
                    publication.sequence() != expectedSequence
                            ? "TRUST_LOG_SEQUENCE_GAP" : "TRUST_LOG_FORK_DETECTED", null, "");
        }
        if (publication.publishedAt().isAfter(clock.instant().plus(CLOCK_SKEW))
                || !publication.expiresAt().isAfter(publication.publishedAt())
                || (previousPublishedAt != null
                && publication.publishedAt().isBefore(previousPublishedAt))) {
            return result(Outcome.POLICY_REJECTED, "TRUST_PUBLICATION_TIME_INVALID", null, "");
        }
        if (!sha256(publicationMaterial(publication))
                .equals(publication.publicationFingerprint())) {
            return result(Outcome.INVALID, "TRUST_PUBLICATION_MATERIAL_INVALID", null, "");
        }
        String pinReason = pinPolicyReason(publication, permanentlyRevoked);
        if (!pinReason.isBlank()) {
            return result(Outcome.POLICY_REJECTED, pinReason, null, "");
        }
        if (!headOnly) {
            Set<String> newlyRevoked = revokedFingerprints(publication);
            newlyRevoked.removeAll(permanentlyRevoked);
            long expectedRecovery = newlyRevoked.isEmpty()
                    ? previousRecoveryEpoch : previousRecoveryEpoch + 1;
            if (publication.recoveryEpoch() != expectedRecovery) {
                return result(Outcome.POLICY_REJECTED, "TRUST_RECOVERY_EPOCH_INVALID", null, "");
            }
        }
        Map<String, EvidenceTrustPolicy.AuthorityKey> trusted = new HashMap<>();
        policy.authorities().forEach(authority -> trusted.put(authority.authorityId(), authority));
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (EvidenceKeySetTrustBundle.AuthoritySignature authoritySignature
                : publication.signatures()) {
            if (!seen.add(authoritySignature.authorityId())) {
                return result(Outcome.INVALID, "TRUST_AUTHORITY_SIGNATURE_DUPLICATE", null, "");
            }
            EvidenceTrustPolicy.AuthorityKey authority = trusted.get(authoritySignature.authorityId());
            if (authority == null || !authority.activeAt(publication.publishedAt())) {
                continue;
            }
            if (!"Ed25519".equals(authoritySignature.algorithm())) {
                return result(Outcome.POLICY_REJECTED, "TRUST_AUTHORITY_ALGORITHM_REJECTED", null, "");
            }
            try {
                if (!verifySignature(publication.publicationFingerprint(), authoritySignature.signature(),
                        authority.encodedPublicKey())) {
                    return result(Outcome.INVALID, "TRUST_AUTHORITY_SIGNATURE_INVALID", null, "");
                }
                valid++;
            } catch (GeneralSecurityException | IllegalArgumentException failure) {
                return result(Outcome.INVALID, "TRUST_AUTHORITY_SIGNATURE_INVALID", null, "");
            }
        }
        if (valid < policy.signatureThreshold()) {
            return result(Outcome.POLICY_REJECTED, "TRUST_AUTHORITY_QUORUM_NOT_MET", null, "");
        }
        return null;
    }

    private static String pinPolicyReason(
            EvidenceKeySetTrustBundle.Publication publication,
            Set<String> permanentlyRevoked) {
        Set<String> ids = new HashSet<>();
        long active = 0;
        for (EvidenceKeySetTrustBundle.SnapshotPin pin : publication.pins()) {
            if (!ids.add(pin.snapshotFingerprint()) || pin.validFrom().isAfter(publication.publishedAt())
                    || (pin.validUntil() != null && !pin.validUntil().isAfter(pin.validFrom()))) {
                return "TRUST_PIN_POLICY_INVALID";
            }
            if (pin.state() == EvidenceKeySetTrustBundle.PinState.REVOKED) {
                if (pin.revokedAt() == null || pin.revokedAt().isAfter(publication.publishedAt())
                        || pin.reasonCode().isBlank()) {
                    return "TRUST_PIN_POLICY_INVALID";
                }
            } else {
                if (pin.revokedAt() != null || !pin.reasonCode().isBlank()
                        || permanentlyRevoked.contains(pin.snapshotFingerprint())) {
                    return permanentlyRevoked.contains(pin.snapshotFingerprint())
                            ? "TRUST_REVOKED_PIN_REACTIVATED" : "TRUST_PIN_POLICY_INVALID";
                }
            }
            if (pin.state() == EvidenceKeySetTrustBundle.PinState.ACTIVE) {
                active++;
                if (!pin.acceptedAt(publication.publishedAt())) {
                    return "TRUST_PIN_POLICY_INVALID";
                }
            }
        }
        return active == 1 ? "" : "TRUST_ACTIVE_PIN_CARDINALITY_INVALID";
    }

    private static Set<String> revokedFingerprints(
            EvidenceKeySetTrustBundle.Publication publication) {
        Set<String> revoked = new HashSet<>();
        publication.pins().stream()
                .filter(pin -> pin.state() == EvidenceKeySetTrustBundle.PinState.REVOKED)
                .map(EvidenceKeySetTrustBundle.SnapshotPin::snapshotFingerprint)
                .forEach(revoked::add);
        return revoked;
    }

    private static EvidenceTrustCheckpoint checkpoint(
            EvidenceKeySetTrustBundle.Publication publication, Set<String> permanentlyRevoked) {
        return new EvidenceTrustCheckpoint(publication.trustDomain(), publication.logId(),
                publication.sequence(), publication.publicationFingerprint(),
                publication.recoveryEpoch(), publication.publishedAt(), permanentlyRevoked);
    }

    private static boolean samePublication(EvidenceKeySetTrustBundle.Publication left,
                                           EvidenceKeySetTrustBundle.Publication right) {
        return left.sequence() == right.sequence()
                && left.publicationFingerprint().equals(right.publicationFingerprint());
    }

    private static ObjectNode publicationMaterial(EvidenceKeySetTrustBundle.Publication publication) {
        JsonNode raw = publication.rawPublication();
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of("schemaVersion", "trustDomain", "logId", "sequence",
                "previousPublicationFingerprint", "recoveryEpoch", "publishedAt", "expiresAt", "pins")) {
            material.set(field, raw.path(field).deepCopy());
        }
        return material;
    }

    private static boolean verifySignature(
            String fingerprint, String encodedSignature, String encodedPublicKey)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey))));
        verifier.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encodedSignature));
    }

    private static String sha256(JsonNode value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(value));
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw new IllegalArgumentException("Canonical evidence trust material is invalid", failure);
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

    private static VerificationResult result(
            Outcome outcome, String reason, EvidenceTrustCheckpoint checkpoint, String pin) {
        return new VerificationResult(outcome, reason, checkpoint, pin);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
