package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent verifier for receipt-aware observation-ledger lifecycle v2 pages.
 *
 * <p>The verifier first reuses the common lifecycle core to prove observations, entries, archives,
 * retirements, floors, head, page identity, continuation, and all Gateway signatures. It then
 * independently recomputes challenge-bound request, receipt, receipt-set, and immutable-object
 * identities and verifies every external receipt against a caller-owned authority policy. No
 * trusted checkpoint is returned unless both layers pass.</p>
 */
public final class TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier lifecycleVerifier;

    /** Creates a verifier using current UTC time for pinned lifecycle key-set freshness. */
    public TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier() {
        this(Clock.systemUTC());
    }

    TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier(Clock clock) {
        lifecycleVerifier = new TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier(
                clock == null ? Clock.systemUTC() : clock);
    }

    /** Closed verification outcomes suitable for CI and governance decisions. */
    public enum Outcome {
        /** Lifecycle and every external archive proof layer verified. */
        VERIFIED,
        /** Canonical material, transition, or a detached signature is invalid. */
        INVALID,
        /** A required lifecycle or external authority public key is unavailable. */
        KEY_UNAVAILABLE,
        /** Caller-pinned lifecycle or archive policy rejects otherwise shaped material. */
        POLICY_REJECTED
    }

    /**
     * Bounded verification result that never exposes receipt signatures or business payload.
     *
     * @param outcome closed trust outcome
     * @param reasonCode stable machine-readable reason
     * @param lifecyclePageId exact v2 page identity
     * @param lifecycleKeyId outer Gateway lifecycle key id when available
     * @param verifiedRetirements retirements verified before termination
     * @param verifiedObservations compact observation signatures verified before termination
     * @param verifiedReceiptSets external receipt sets verified before termination
     * @param verifiedReceipts external authority signatures verified before termination
     * @param checkpoint next lifecycle state; null unless every layer passed
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String lifecyclePageId,
            String lifecycleKeyId,
            int verifiedRetirements,
            int verifiedObservations,
            int verifiedReceiptSets,
            int verifiedReceipts,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint
                    checkpoint
    ) {
        /** Normalizes bounded result fields and forbids a checkpoint on partial trust. */
        public VerificationResult {
            outcome = outcome == null ? Outcome.INVALID : outcome;
            reasonCode = normalized(reasonCode);
            lifecyclePageId = normalized(lifecyclePageId);
            lifecycleKeyId = normalized(lifecycleKeyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")
                    || verifiedRetirements < 0 || verifiedRetirements > 10
                    || verifiedObservations < 0 || verifiedObservations > 1_010
                    || verifiedReceiptSets < 0 || verifiedReceiptSets > 10
                    || verifiedReceipts < 0 || verifiedReceipts > 160
                    || (outcome == Outcome.VERIFIED) != (checkpoint != null)) {
                throw new IllegalArgumentException(
                        "Receipt-aware lifecycle verification result is invalid");
            }
        }

        /**
         * Indicates whether lifecycle and every external proof layer passed.
         *
         * @return true only when a trusted checkpoint is present
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies a first v2 page with explicitly supplied lifecycle keys and archive trust policy.
     *
     * @param page strict generation-zero receipt-aware page
     * @param lifecycleKeys Gateway observation, retirement, and page keys by key id
     * @param archivePolicy caller-owned external authority and retention policy
     * @return bounded independent verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            Map<String, EvidenceVerificationKey> lifecycleKeys,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy) {
        return verify(page, null, lifecycleKeys, archivePolicy);
    }

    /**
     * Verifies a first or continuation v2 page with explicit lifecycle keys.
     *
     * @param page strict receipt-aware page
     * @param previous null for generation zero; prior verified checkpoint otherwise
     * @param lifecycleKeys Gateway observation, retirement, and page keys by key id
     * @param archivePolicy caller-owned external authority and retention policy
     * @return bounded independent verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint
                    previous,
            Map<String, EvidenceVerificationKey> lifecycleKeys,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy) {
        var lifecycle = lifecycleVerifier.verifyView(page, previous, lifecycleKeys);
        return complete(page, lifecycle, archivePolicy);
    }

    /**
     * Performs release-grade first-page lifecycle verification with a pinned Gateway key set.
     *
     * @param page strict generation-zero receipt-aware page
     * @param lifecycleKeySet complete signed Gateway key-lifecycle snapshot
     * @param trustedSnapshotFingerprint fingerprint pinned outside Gateway output
     * @param archivePolicy caller-owned external authority and retention policy
     * @return bounded independent verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            EvidenceVerificationKeySet lifecycleKeySet,
            String trustedSnapshotFingerprint,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy) {
        return verify(page, null, lifecycleKeySet, trustedSnapshotFingerprint, archivePolicy);
    }

    /**
     * Performs release-grade continuation verification with a pinned Gateway key set.
     *
     * @param page strict receipt-aware page
     * @param previous null for generation zero; prior verified checkpoint otherwise
     * @param lifecycleKeySet complete signed Gateway key-lifecycle snapshot
     * @param trustedSnapshotFingerprint fingerprint pinned outside Gateway output
     * @param archivePolicy caller-owned external authority and retention policy
     * @return bounded independent verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint
                    previous,
            EvidenceVerificationKeySet lifecycleKeySet,
            String trustedSnapshotFingerprint,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy) {
        var lifecycle = lifecycleVerifier.verifyView(
                page, previous, lifecycleKeySet, trustedSnapshotFingerprint);
        return complete(page, lifecycle, archivePolicy);
    }

    /**
     * Returns Gateway signing keys required by nested lifecycle and outer v2 material.
     *
     * @param page strict receipt-aware page; null yields an empty set
     * @return immutable distinct Gateway key ids
     */
    public static Set<String> requiredLifecycleKeyIds(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page) {
        return TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier
                .requiredKeyIdsView(page);
    }

    /**
     * Returns every external archive signing key id referenced by one v2 page.
     *
     * @param page strict receipt-aware page; null yields an empty set
     * @return immutable distinct external key ids
     */
    public static Set<String> requiredArchiveKeyIds(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (page != null) {
            page.externalArchiveReceiptSets().forEach(set ->
                    set.receipts().forEach(receipt -> ids.add(receipt.keyId())));
        }
        return Set.copyOf(ids);
    }

    private static VerificationResult complete(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.VerificationResult
                    lifecycle,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy) {
        if (!lifecycle.verified()) {
            return result(Outcome.valueOf(lifecycle.outcome().name()), lifecycle.reasonCode(),
                    page, lifecycle.verifiedRetirements(), lifecycle.verifiedObservations(),
                    0, 0, null);
        }
        ArchiveProgress archive = verifyArchiveProofs(page, archivePolicy);
        if (archive.failure() != null) {
            return result(archive.failure().outcome(), archive.failure().reasonCode(), page,
                    lifecycle.verifiedRetirements(), lifecycle.verifiedObservations(),
                    archive.verifiedReceiptSets(), archive.verifiedReceipts(), null);
        }
        return result(Outcome.VERIFIED, "VERIFIED", page,
                lifecycle.verifiedRetirements(), lifecycle.verifiedObservations(),
                archive.verifiedReceiptSets(), archive.verifiedReceipts(),
                lifecycle.checkpoint());
    }

    private static ArchiveProgress verifyArchiveProofs(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy policy) {
        if (policy == null) {
            return failure(Outcome.POLICY_REJECTED, "EXTERNAL_ARCHIVE_POLICY_MISSING", 0, 0);
        }
        int verifiedSets = 0;
        int verifiedReceipts = 0;
        JsonNode rawPage = page.rawResponse().path("page");
        for (int index = 0; index < page.externalArchiveReceiptSets().size(); index++) {
            var set = page.externalArchiveReceiptSets().get(index);
            JsonNode rawSet = rawPage.path("externalArchiveReceiptSets").path(index);
            JsonNode rawRequest = rawSet.path("request");
            JsonNode rawRetirement = rawPage.path("retirements").path(index);
            ArchiveFailure canonical = canonicalSetFailure(
                    set, rawSet, rawRequest, rawRetirement);
            if (canonical != null) {
                return new ArchiveProgress(canonical, verifiedSets, verifiedReceipts);
            }
            ArchiveFailure setPolicy = setPolicyFailure(set, policy);
            if (setPolicy != null) {
                return new ArchiveProgress(setPolicy, verifiedSets, verifiedReceipts);
            }
            for (int receiptIndex = 0; receiptIndex < set.receipts().size(); receiptIndex++) {
                var receipt = set.receipts().get(receiptIndex);
                JsonNode rawReceipt = rawSet.path("receipts").path(receiptIndex);
                ArchiveFailure receiptFailure = verifyReceipt(receipt, rawReceipt, set, policy);
                if (receiptFailure != null) {
                    return new ArchiveProgress(
                            receiptFailure, verifiedSets, verifiedReceipts);
                }
                verifiedReceipts++;
            }
            verifiedSets++;
        }
        return new ArchiveProgress(null, verifiedSets, verifiedReceipts);
    }

    private static ArchiveFailure canonicalSetFailure(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage.ExternalArchiveReceiptSet set,
            JsonNode rawSet,
            JsonNode rawRequest,
            JsonNode rawRetirement) {
        try {
            if (!set.requestFingerprint().equals(rawRequest.path("requestFingerprint").asText())
                    || !set.requestFingerprint().equals(EvidenceVerificationSupport.sha256(
                    without(rawRequest, "requestFingerprint")))) {
                return invalid("EXTERNAL_ARCHIVE_REQUEST_FINGERPRINT_INVALID");
            }
            if (!canonicallyEqual(rawRetirement, rawRequest.path("retirement"))) {
                return invalid("EXTERNAL_ARCHIVE_RETIREMENT_BINDING_INVALID");
            }
            if (!set.receiptSetFingerprint().equals(
                    EvidenceVerificationSupport.sha256(
                            without(rawSet, "receiptSetFingerprint")))) {
                return invalid("EXTERNAL_ARCHIVE_RECEIPT_SET_FINGERPRINT_INVALID");
            }
            ObjectNode identity = JSON.createObjectNode();
            identity.put("schemaVersion", rawSet.path("schemaVersion").asText());
            identity.put("requestFingerprint", set.requestFingerprint());
            identity.put("requiredCopies", set.requiredCopies());
            ArrayNode refs = identity.putArray("receipts");
            rawSet.path("receipts").forEach(receipt -> {
                ObjectNode ref = refs.addObject();
                ref.put("authorityId", receipt.path("authorityId").asText());
                ref.put("failureDomain", receipt.path("failureDomain").asText());
                ref.put("receiptFingerprint", receipt.path("receiptFingerprint").asText());
            });
            String expectedSetId = "stability-observation-external-archive-receipts-"
                    + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
            if (!expectedSetId.equals(set.receiptSetId())) {
                return invalid("EXTERNAL_ARCHIVE_RECEIPT_SET_IDENTITY_INVALID");
            }
            ObjectNode objectIdentity = JSON.createObjectNode();
            objectIdentity.put("retirementId", set.retirementId());
            objectIdentity.put("retirementFingerprint", set.retirementFingerprint());
            objectIdentity.put("segmentId", set.segmentId());
            objectIdentity.put("segmentFingerprint", set.segmentFingerprint());
            objectIdentity.put("retentionPolicyFingerprint",
                    set.retentionPolicyFingerprint());
            String expectedObjectId = "stability-observation-worm-"
                    + EvidenceVerificationSupport.sha256(objectIdentity)
                    .substring("sha256:".length());
            if (set.receipts().stream().anyMatch(receipt ->
                    !expectedObjectId.equals(receipt.objectId()))) {
                return invalid("EXTERNAL_ARCHIVE_OBJECT_IDENTITY_INVALID");
            }
            byte[] challenge = java.util.Base64.getUrlDecoder().decode(set.challenge());
            if (challenge.length != 32) {
                return invalid("EXTERNAL_ARCHIVE_CHALLENGE_INVALID");
            }
            return null;
        } catch (RuntimeException invalid) {
            return invalid("EXTERNAL_ARCHIVE_RECEIPT_SET_MATERIAL_INVALID");
        }
    }

    private static ArchiveFailure setPolicyFailure(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage.ExternalArchiveReceiptSet set,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy policy) {
        if (!policy.trustDomain().equals(set.trustDomain())) {
            return rejected("EXTERNAL_ARCHIVE_TRUST_DOMAIN_REJECTED");
        }
        if (!policy.archiveSetId().equals(set.archiveSetId())) {
            return rejected("EXTERNAL_ARCHIVE_SET_REJECTED");
        }
        if (!policy.acceptedRetentionPolicyFingerprints().contains(
                set.retentionPolicyFingerprint())) {
            return rejected("EXTERNAL_ARCHIVE_RETENTION_POLICY_REJECTED");
        }
        if (set.requiredCopies() < policy.minimumCopies()
                || set.receipts().size() < policy.minimumCopies()) {
            return rejected("EXTERNAL_ARCHIVE_COPY_THRESHOLD_REJECTED");
        }
        if (set.retainUntil().isBefore(policy.requiredRetainUntil())) {
            return rejected("EXTERNAL_ARCHIVE_RETENTION_HORIZON_REJECTED");
        }
        return null;
    }

    private static ArchiveFailure verifyReceipt(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage.ExternalArchiveReceipt receipt,
            JsonNode rawReceipt,
            TestSuiteStabilityObservationLedgerLifecycleArchivePage.ExternalArchiveReceiptSet set,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy policy) {
        if (!receipt.receiptFingerprint().equals(EvidenceVerificationSupport.sha256(
                without(rawReceipt, "receiptFingerprint", "signature")))) {
            return invalid("EXTERNAL_ARCHIVE_RECEIPT_FINGERPRINT_INVALID");
        }
        if (receipt.retainUntil().isBefore(policy.requiredRetainUntil())
                || receipt.retainUntil().isBefore(set.retainUntil())) {
            return rejected("EXTERNAL_ARCHIVE_RETENTION_HORIZON_REJECTED");
        }
        var authority = policy.authority(receipt.authorityId()).orElse(null);
        if (authority == null) {
            return rejected("EXTERNAL_ARCHIVE_AUTHORITY_REJECTED");
        }
        if (!authority.failureDomain().equals(receipt.failureDomain())) {
            return rejected("EXTERNAL_ARCHIVE_FAILURE_DOMAIN_REJECTED");
        }
        EvidenceVerificationKey key = authority.key(receipt.keyId()).orElse(null);
        if (key == null) {
            return new ArchiveFailure(Outcome.KEY_UNAVAILABLE,
                    "EXTERNAL_ARCHIVE_VERIFICATION_KEY_UNAVAILABLE");
        }
        if (!key.keyId().equals(receipt.keyId())
                || !"Ed25519".equals(receipt.algorithm())
                || !receipt.algorithm().equals(key.algorithm())
                || !key.verificationAllowed()
                || receipt.issuedAt().isBefore(key.createdAt().minus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return rejected("EXTERNAL_ARCHIVE_VERIFICATION_KEY_POLICY_REJECTED");
        }
        try {
            if (!EvidenceVerificationSupport.verifyEd25519(
                    receipt.receiptFingerprint(), receipt.signature(),
                    key.encodedPublicKey())) {
                return invalid("EXTERNAL_ARCHIVE_RECEIPT_SIGNATURE_INVALID");
            }
            return null;
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return invalid("EXTERNAL_ARCHIVE_RECEIPT_SIGNATURE_INVALID");
        }
    }

    private static ObjectNode without(JsonNode value, String... fields) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        for (String field : fields) {
            copy.remove(field);
        }
        return copy;
    }

    private static boolean canonicallyEqual(JsonNode left, JsonNode right) {
        return EvidenceVerificationSupport.sha256(left)
                .equals(EvidenceVerificationSupport.sha256(right));
    }

    private static ArchiveProgress failure(
            Outcome outcome,
            String reason,
            int verifiedSets,
            int verifiedReceipts) {
        return new ArchiveProgress(
                new ArchiveFailure(outcome, reason), verifiedSets, verifiedReceipts);
    }

    private static ArchiveFailure invalid(String reason) {
        return new ArchiveFailure(Outcome.INVALID, reason);
    }

    private static ArchiveFailure rejected(String reason) {
        return new ArchiveFailure(Outcome.POLICY_REJECTED, reason);
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            int verifiedRetirements,
            int verifiedObservations,
            int verifiedReceiptSets,
            int verifiedReceipts,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint
                    checkpoint) {
        return new VerificationResult(outcome, reason,
                page == null ? "" : page.lifecyclePageId(),
                page == null ? "" : page.outerKeyId(),
                verifiedRetirements, verifiedObservations,
                verifiedReceiptSets, verifiedReceipts, checkpoint);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record ArchiveFailure(Outcome outcome, String reasonCode) {
    }

    private record ArchiveProgress(
            ArchiveFailure failure,
            int verifiedReceiptSets,
            int verifiedReceipts) {
    }
}
