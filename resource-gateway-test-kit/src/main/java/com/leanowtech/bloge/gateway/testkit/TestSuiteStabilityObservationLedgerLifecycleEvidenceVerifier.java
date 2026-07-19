package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-light independent verifier for signed observation-ledger lifecycle pages.
 *
 * <p>The verifier recomputes observation, entry, floor, head, archive, retirement, page, and
 * deterministic-id material; verifies compact-observation, retirement, and page Ed25519
 * signatures; derives every successor floor; and binds continuation pages to a verified
 * checkpoint. Producer scope and database ordering remain signed producer-authoritative facts.
 * Same-database archives are not promoted to external WORM or non-equivocation evidence.</p>
 */
public final class TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;

    /** Creates a verifier using current UTC time for key-set freshness policy. */
    public TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier() {
        this(Clock.systemUTC());
    }

    TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Closed verification outcomes suitable for CI and governance consumers. */
    public enum Outcome {
        /** Every canonical layer, signature, transition, and checkpoint relation passed. */
        VERIFIED,
        /** Evidence, signature, transition, or checkpoint relation is invalid. */
        INVALID,
        /** A required public verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** An externally pinned key lifecycle policy rejects the evidence. */
        POLICY_REJECTED
    }

    /**
     * Verified cross-page continuation state.
     *
     * @param suiteId exact immutable suite id
     * @param revision exact immutable suite revision
     * @param suiteFingerprint exact suite fingerprint
     * @param scopeFingerprint payload-free producer scope identity
     * @param currentFloorFingerprint snapshot current floor pin
     * @param headFingerprint snapshot head pin
     * @param terminalRetirementGeneration next exclusive generation cursor
     * @param terminalFloorFingerprint exact floor expected at the next page start
     * @param complete whether the chain reached the pinned current floor
     */
    public record LifecycleCheckpoint(
            String suiteId,
            long revision,
            String suiteFingerprint,
            String scopeFingerprint,
            String currentFloorFingerprint,
            String headFingerprint,
            long terminalRetirementGeneration,
            String terminalFloorFingerprint,
            boolean complete
    ) {
        /** Validates one payload-free exact continuation checkpoint. */
        public LifecycleCheckpoint {
            suiteId = normalized(suiteId);
            suiteFingerprint = normalized(suiteFingerprint);
            scopeFingerprint = normalized(scopeFingerprint);
            currentFloorFingerprint = normalized(currentFloorFingerprint);
            headFingerprint = normalized(headFingerprint);
            terminalFloorFingerprint = normalized(terminalFloorFingerprint);
            if (suiteId.isBlank() || revision < 1
                    || !fingerprint(suiteFingerprint) || !fingerprint(scopeFingerprint)
                    || !fingerprint(currentFloorFingerprint) || !fingerprint(headFingerprint)
                    || terminalRetirementGeneration < 0
                    || !fingerprint(terminalFloorFingerprint)) {
                throw new IllegalArgumentException("Lifecycle checkpoint is incomplete");
            }
        }
    }

    /**
     * Bounded payload-free verification result.
     *
     * @param outcome closed trust outcome
     * @param reasonCode stable machine-readable reason
     * @param lifecyclePageId exact page identity
     * @param keyId outer signature key id when available
     * @param verifiedRetirements retirements verified before termination
     * @param verifiedObservations compact signatures verified before termination
     * @param checkpoint next verified state; null unless the page passed
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String lifecyclePageId,
            String keyId,
            int verifiedRetirements,
            int verifiedObservations,
            LifecycleCheckpoint checkpoint
    ) {
        /** Normalizes bounded result fields. */
        public VerificationResult {
            outcome = outcome == null ? Outcome.INVALID : outcome;
            reasonCode = normalized(reasonCode);
            lifecyclePageId = normalized(lifecyclePageId);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")
                    || verifiedRetirements < 0 || verifiedRetirements > 10
                    || verifiedObservations < 0 || verifiedObservations > 1_010
                    || (outcome == Outcome.VERIFIED) != (checkpoint != null)) {
                throw new IllegalArgumentException(
                        "Lifecycle verification result is invalid");
            }
        }

        /**
         * Reports whether every required verification layer passed.
         *
         * @return true only for a verified result with a continuation checkpoint
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies a first page against explicitly supplied public keys.
     *
     * @param page strict generation-zero lifecycle page
     * @param keys exact public verification keys by key id
     * @return bounded independent verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            Map<String, EvidenceVerificationKey> keys) {
        return verify(page, null, keys);
    }

    /**
     * Verifies a first or continuation page against explicitly supplied public keys.
     *
     * <p>This overload is useful for local diagnosis. Release gates should use the pinned key-set
     * overload because a map alone does not prove lifecycle completeness or split-view resistance.</p>
     *
     * @param page strict first or continuation lifecycle page
     * @param previous null for generation zero; verified prior-page checkpoint otherwise
     * @param keys exact public verification keys by key id
     * @return bounded independent verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            LifecycleCheckpoint previous,
            Map<String, EvidenceVerificationKey> keys) {
        if (page == null) {
            return result(Outcome.INVALID, "LIFECYCLE_PAGE_MISSING", "", "", 0, 0);
        }
        Map<String, EvidenceVerificationKey> exactKeys = keys == null
                ? Map.of() : Map.copyOf(keys);
        VerificationResult checkpointFailure = verifyCheckpoint(page, previous);
        if (checkpointFailure != null) {
            return checkpointFailure;
        }
        JsonNode response = page.rawResponse();
        JsonNode rawPage = response.path("page");
        try {
            if (!page.request().requestFingerprint().equals(
                    rawPage.path("requestFingerprint").asText())
                    || !page.pageFingerprint().equals(EvidenceVerificationSupport.sha256(
                    without(rawPage, "pageFingerprint")))) {
                return result(Outcome.INVALID, "LIFECYCLE_PAGE_FINGERPRINT_INVALID", page, 0, 0);
            }
            ObjectNode pageIdentity = JSON.createObjectNode();
            pageIdentity.put("schemaVersion", rawPage.path("schemaVersion").asText());
            pageIdentity.put("requestFingerprint", page.request().requestFingerprint());
            pageIdentity.put("pageFingerprint", page.pageFingerprint());
            String expectedPageId = "stability-observation-lifecycle-page-"
                    + EvidenceVerificationSupport.sha256(pageIdentity)
                    .substring("sha256:".length());
            if (!expectedPageId.equals(page.lifecyclePageId())) {
                return result(Outcome.INVALID, "LIFECYCLE_PAGE_IDENTITY_INVALID", page, 0, 0);
            }
            VerificationResult floorFailure = verifyFloorAndHeadFingerprints(page, rawPage);
            if (floorFailure != null) {
                return floorFailure;
            }
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "LIFECYCLE_PAGE_MATERIAL_INVALID", page, 0, 0);
        }

        JsonNode cursorFloor = rawPage.path("startingFloor");
        int verifiedRetirements = 0;
        int verifiedObservations = 0;
        List<JsonNode> rawRetirements = new ArrayList<>();
        rawPage.path("retirements").forEach(rawRetirements::add);
        for (JsonNode retirement : rawRetirements) {
            VerificationProgress progress = verifyRetirement(
                    page, retirement, cursorFloor, exactKeys,
                    verifiedRetirements, verifiedObservations);
            if (progress.failure() != null) {
                return progress.failure();
            }
            cursorFloor = progress.successorFloor();
            verifiedRetirements++;
            verifiedObservations = progress.verifiedObservations();
        }
        if (!canonicallyEqual(cursorFloor, rawPage.path("terminalFloor"))) {
            return result(Outcome.INVALID, "LIFECYCLE_TERMINAL_FLOOR_INVALID", page,
                    verifiedRetirements, verifiedObservations);
        }
        if (!page.hasMore() && !canonicallyEqual(
                cursorFloor, rawPage.path("currentFloor"))) {
            return result(Outcome.INVALID, "LIFECYCLE_CURRENT_FLOOR_INVALID", page,
                    verifiedRetirements, verifiedObservations);
        }
        VerificationResult outer = verifyOuterSignature(
                page, exactKeys, verifiedRetirements, verifiedObservations);
        if (outer != null) {
            return outer;
        }
        LifecycleCheckpoint checkpoint = new LifecycleCheckpoint(
                page.request().suiteId(), page.request().revision(),
                page.request().fingerprint(), page.scopeFingerprint(),
                page.currentFloor().floorFingerprint(), page.head().headFingerprint(),
                page.terminalFloor().retirementGeneration(),
                page.terminalFloor().floorFingerprint(), !page.hasMore());
        return new VerificationResult(Outcome.VERIFIED, "VERIFIED",
                page.lifecyclePageId(), page.attestation().keyId(),
                verifiedRetirements, verifiedObservations, checkpoint);
    }

    /**
     * Performs release-grade first-page verification against a pinned key lifecycle snapshot.
     *
     * @param page strict generation-zero lifecycle page
     * @param keySet complete signed key-lifecycle snapshot
     * @param trustedSnapshotFingerprint fingerprint pinned outside Gateway output
     * @return bounded lifecycle-aware verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            EvidenceVerificationKeySet keySet,
            String trustedSnapshotFingerprint) {
        return verify(page, null, keySet, trustedSnapshotFingerprint);
    }

    /**
     * Performs release-grade continuation verification against a pinned key lifecycle snapshot.
     *
     * @param page strict first or continuation lifecycle page
     * @param previous null for generation zero; verified prior-page checkpoint otherwise
     * @param keySet complete signed key-lifecycle snapshot
     * @param trustedSnapshotFingerprint fingerprint pinned outside Gateway output
     * @return bounded lifecycle-aware verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            LifecycleCheckpoint previous,
            EvidenceVerificationKeySet keySet,
            String trustedSnapshotFingerprint) {
        if (page == null) {
            return result(Outcome.INVALID, "LIFECYCLE_PAGE_MISSING", "", "", 0, 0);
        }
        TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                new TestSuiteEvidenceVerifier(clock).verifyKeySet(
                        keySet, trustedSnapshotFingerprint);
        if (!keySetResult.verified()) {
            return result(Outcome.valueOf(keySetResult.outcome().name()),
                    keySetResult.reasonCode(), page, 0, 0);
        }
        Map<String, EvidenceVerificationKey> keys = new LinkedHashMap<>();
        for (SigningCoordinate coordinate : signingCoordinates(page)) {
            String reason = EvidenceVerificationSupport.signingTimePolicyReason(
                    keySet, coordinate.keyId(), coordinate.signedAt());
            if (!reason.isBlank()) {
                return result(Outcome.POLICY_REJECTED, reason, page, 0, 0);
            }
            EvidenceVerificationKeySet.KeyPolicy policy = keySet.keys().stream()
                    .filter(candidate -> candidate.keyId().equals(coordinate.keyId()))
                    .findFirst().orElse(null);
            if (policy == null) {
                return result(Outcome.KEY_UNAVAILABLE,
                        "EVIDENCE_KEY_NOT_IN_PINNED_SET", page, 0, 0);
            }
            keys.putIfAbsent(policy.keyId(), new EvidenceVerificationKey(
                    TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, policy.keyId(),
                    policy.algorithm(), policy.encodedPublicKey(), policy.notBefore(),
                    policy.state() == EvidenceVerificationKeySet.KeyState.ACTIVE
                            ? "ACTIVE" : "RETIRED", keySet.provider()));
        }
        return verify(page, previous, keys);
    }

    /**
     * Returns every exact signing key id required by one page.
     *
     * @param page strict lifecycle page; null yields an empty set
     * @return immutable distinct key ids used by nested and outer signatures
     */
    public static Set<String> requiredKeyIds(
            TestSuiteStabilityObservationLedgerLifecyclePage page) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (page != null) {
            signingCoordinates(page).forEach(value -> ids.add(value.keyId()));
        }
        return Set.copyOf(ids);
    }

    private static VerificationResult verifyCheckpoint(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            LifecycleCheckpoint previous) {
        if (previous == null) {
            if (page.request().afterRetirementGeneration() != 0
                    || page.startingFloor().retirementGeneration() != 0) {
                return result(Outcome.INVALID, "LIFECYCLE_ROLLOUT_CHECKPOINT_REQUIRED",
                        page, 0, 0);
            }
            return null;
        }
        if (previous.complete()
                || !previous.suiteId().equals(page.request().suiteId())
                || previous.revision() != page.request().revision()
                || !previous.suiteFingerprint().equals(page.request().fingerprint())
                || !previous.scopeFingerprint().equals(page.scopeFingerprint())
                || previous.terminalRetirementGeneration()
                != page.request().afterRetirementGeneration()
                || !previous.terminalFloorFingerprint().equals(
                page.startingFloor().floorFingerprint())
                || !previous.currentFloorFingerprint().equals(
                page.currentFloor().floorFingerprint())
                || !previous.headFingerprint().equals(page.head().headFingerprint())
                || !previous.currentFloorFingerprint().equals(
                page.request().expectedCurrentFloorFingerprint())
                || !previous.headFingerprint().equals(
                page.request().expectedHeadFingerprint())) {
            return result(Outcome.INVALID, "LIFECYCLE_CHECKPOINT_MISMATCH", page, 0, 0);
        }
        return null;
    }

    private static VerificationResult verifyFloorAndHeadFingerprints(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            JsonNode rawPage) {
        for (String name : List.of("startingFloor", "terminalFloor", "currentFloor")) {
            JsonNode floor = rawPage.path(name);
            if (!floor.path("floorFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(without(floor, "floorFingerprint")))) {
                return result(Outcome.INVALID, "LIFECYCLE_FLOOR_FINGERPRINT_INVALID",
                        page, 0, 0);
            }
        }
        JsonNode head = rawPage.path("head");
        if (!head.path("headFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256(without(head, "headFingerprint")))) {
            return result(Outcome.INVALID, "LIFECYCLE_HEAD_FINGERPRINT_INVALID", page, 0, 0);
        }
        return null;
    }

    private static VerificationProgress verifyRetirement(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            JsonNode retirement,
            JsonNode cursorFloor,
            Map<String, EvidenceVerificationKey> keys,
            int verifiedRetirements,
            int verifiedObservations) {
        try {
            JsonNode evidence = retirement.path("evidence");
            JsonNode archive = evidence.path("archiveSegment");
            JsonNode attestation = retirement.path("attestation");
            if (!canonicallyEqual(evidence.path("previousFloor"), cursorFloor)) {
                return failure(page, "LIFECYCLE_RETIREMENT_PREVIOUS_FLOOR_INVALID",
                        verifiedRetirements, verifiedObservations);
            }
            if (!cursorFloor.path("floorFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(
                            without(cursorFloor, "floorFingerprint")))
                    || !evidence.path("pinnedHead").path("headFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(
                            without(evidence.path("pinnedHead"), "headFingerprint")))) {
                return failure(page, "LIFECYCLE_RETIREMENT_PIN_FINGERPRINT_INVALID",
                        verifiedRetirements, verifiedObservations);
            }
            int observations = verifiedObservations;
            List<JsonNode> entries = new ArrayList<>();
            archive.path("retiredEntries").forEach(entries::add);
            entries.add(archive.path("successorEntry"));
            for (JsonNode entry : entries) {
                VerificationResult observationFailure = verifyObservation(
                        page, entry, keys, verifiedRetirements, observations);
                if (observationFailure != null) {
                    return new VerificationProgress(observationFailure, null, observations);
                }
                observations++;
            }
            if (!archive.path("segmentFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(
                            without(archive, "segmentFingerprint")))) {
                return failure(page, "LIFECYCLE_ARCHIVE_FINGERPRINT_INVALID",
                        verifiedRetirements, observations);
            }
            ObjectNode archiveIdentity = archiveIdentity(archive);
            String expectedArchiveId = "stability-observation-archive-"
                    + EvidenceVerificationSupport.sha256(archiveIdentity)
                    .substring("sha256:".length());
            if (!expectedArchiveId.equals(archive.path("segmentId").asText())) {
                return failure(page, "LIFECYCLE_ARCHIVE_IDENTITY_INVALID",
                        verifiedRetirements, observations);
            }
            if (!retirement.path("evidenceFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(evidence))) {
                return failure(page, "LIFECYCLE_RETIREMENT_EVIDENCE_FINGERPRINT_INVALID",
                        verifiedRetirements, observations);
            }
            ObjectNode retirementIdentity = ((ObjectNode) evidence).deepCopy();
            retirementIdentity.remove("retirementId");
            String expectedRetirementId = "stability-observation-retirement-"
                    + EvidenceVerificationSupport.sha256(retirementIdentity)
                    .substring("sha256:".length());
            if (!expectedRetirementId.equals(evidence.path("retirementId").asText())
                    || !retirement.path("attestationFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(attestation))
                    || !retirement.path("retirementFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(
                            without(retirement, "retirementFingerprint")))) {
                return failure(page, "LIFECYCLE_RETIREMENT_FINGERPRINT_INVALID",
                        verifiedRetirements, observations);
            }
            VerificationResult signatureFailure = verifyDetachedSignature(
                    page, attestation, retirementSignatureMaterial(attestation), keys,
                    "RETIREMENT_VERIFICATION_KEY_UNAVAILABLE",
                    "LIFECYCLE_RETIREMENT_SIGNATURE_INVALID",
                    verifiedRetirements, observations);
            if (signatureFailure != null) {
                return new VerificationProgress(signatureFailure, null, observations);
            }
            JsonNode successor = successorFloor(retirement);
            return new VerificationProgress(null, successor, observations);
        } catch (RuntimeException invalid) {
            return failure(page, "LIFECYCLE_RETIREMENT_MATERIAL_INVALID",
                    verifiedRetirements, verifiedObservations);
        }
    }

    private static VerificationResult verifyObservation(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            JsonNode entry,
            Map<String, EvidenceVerificationKey> keys,
            int verifiedRetirements,
            int verifiedObservations) {
        JsonNode observation = entry.path("observation");
        JsonNode evidence = observation.path("evidence");
        JsonNode attestation = observation.path("attestation");
        if (!observation.path("evidenceFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256(evidence))
                || !observation.path("attestationFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256(attestation))
                || !entry.path("entryFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256(without(entry, "entryFingerprint")))) {
            return result(Outcome.INVALID, "LIFECYCLE_OBSERVATION_FINGERPRINT_INVALID",
                    page, verifiedRetirements, verifiedObservations);
        }
        ObjectNode identity = JSON.createObjectNode();
        identity.put("schemaVersion", evidence.path("schemaVersion").asText());
        identity.put("scopeFingerprint", evidence.path("scopeFingerprint").asText());
        identity.set("suiteRef", evidence.path("suiteRef").deepCopy());
        identity.put("sourceRequestFingerprint",
                evidence.path("sourceRequestFingerprint").asText());
        identity.put("stabilityRunId",
                evidence.path("source").path("stabilityRunId").asText());
        identity.put("sourceEvidenceFingerprint",
                evidence.path("source").path("evidenceFingerprint").asText());
        identity.put("sourceAttestationFingerprint",
                evidence.path("source").path("attestationFingerprint").asText());
        String expectedObservationId = "stability-observation-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
        if (!expectedObservationId.equals(evidence.path("observationId").asText())) {
            return result(Outcome.INVALID, "OBSERVATION_IDENTITY_INVALID", page,
                    verifiedRetirements, verifiedObservations);
        }
        return verifyDetachedSignature(page, attestation,
                observationSignatureMaterial(attestation), keys,
                "OBSERVATION_VERIFICATION_KEY_UNAVAILABLE",
                "OBSERVATION_SIGNATURE_INVALID", verifiedRetirements, verifiedObservations);
    }

    private static VerificationResult verifyOuterSignature(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            Map<String, EvidenceVerificationKey> keys,
            int verifiedRetirements,
            int verifiedObservations) {
        JsonNode attestation = page.rawResponse().path("attestation");
        return verifyDetachedSignature(page, attestation,
                pageSignatureMaterial(attestation), keys,
                "LIFECYCLE_VERIFICATION_KEY_UNAVAILABLE",
                "LIFECYCLE_PAGE_SIGNATURE_INVALID", verifiedRetirements,
                verifiedObservations);
    }

    private static VerificationResult verifyDetachedSignature(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            JsonNode attestation,
            JsonNode material,
            Map<String, EvidenceVerificationKey> keys,
            String missingKeyReason,
            String invalidSignatureReason,
            int verifiedRetirements,
            int verifiedObservations) {
        String keyId = attestation.path("keyId").asText();
        EvidenceVerificationKey key = keys.get(keyId);
        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE, missingKeyReason, page,
                    verifiedRetirements, verifiedObservations);
        }
        Instant signedAt = Instant.parse(attestation.path("signedAt").asText());
        String policy = directKeyPolicy(
                key, keyId, attestation.path("algorithm").asText(), signedAt);
        if (!policy.isBlank()) {
            return result(Outcome.POLICY_REJECTED, policy, page,
                    verifiedRetirements, verifiedObservations);
        }
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256(material);
            if (!EvidenceVerificationSupport.verifyEd25519(materialFingerprint,
                    attestation.path("signature").asText(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, invalidSignatureReason, page,
                        verifiedRetirements, verifiedObservations);
            }
            return null;
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(Outcome.INVALID, invalidSignatureReason, page,
                    verifiedRetirements, verifiedObservations);
        }
    }

    private static ObjectNode archiveIdentity(JsonNode archive) {
        ObjectNode identity = JSON.createObjectNode();
        identity.put("schemaVersion", archive.path("schemaVersion").asText());
        identity.put("scopeFingerprint", archive.path("scopeFingerprint").asText());
        identity.set("suiteRef", archive.path("suiteRef").deepCopy());
        identity.put("retirementGeneration", archive.path("retirementGeneration").asLong());
        identity.put("previousObservationId", archive.path("previousObservationId").asText());
        identity.put("previousEntryFingerprint",
                archive.path("previousEntryFingerprint").asText());
        ArrayNode refs = identity.putArray("retiredEntries");
        archive.path("retiredEntries").forEach(entry -> refs.add(entryRef(entry)));
        identity.set("successorEntry", entryRef(archive.path("successorEntry")));
        identity.put("archivedAt", archive.path("archivedAt").asText());
        return identity;
    }

    private static ObjectNode entryRef(JsonNode entry) {
        ObjectNode ref = JSON.createObjectNode();
        ref.put("sequence", entry.path("sequence").asLong());
        ref.put("observationId",
                entry.path("observation").path("evidence").path("observationId").asText());
        ref.put("entryFingerprint", entry.path("entryFingerprint").asText());
        return ref;
    }

    private static JsonNode successorFloor(JsonNode retirement) {
        JsonNode evidence = retirement.path("evidence");
        JsonNode archive = evidence.path("archiveSegment");
        JsonNode retiredLast = archive.path("retiredEntries")
                .path(archive.path("retiredEntries").size() - 1);
        JsonNode successor = archive.path("successorEntry");
        ObjectNode floor = JSON.createObjectNode();
        floor.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_FLOOR_V1);
        floor.put("scopeFingerprint", evidence.path("scopeFingerprint").asText());
        floor.set("suiteRef", evidence.path("suiteRef").deepCopy());
        floor.put("floorSequence", successor.path("sequence").asLong());
        floor.put("previousObservationId", retiredLast.path("observation")
                .path("evidence").path("observationId").asText());
        floor.put("previousEntryFingerprint", retiredLast.path("entryFingerprint").asText());
        floor.put("floorObservationId", successor.path("observation")
                .path("evidence").path("observationId").asText());
        floor.put("floorEntryFingerprint", successor.path("entryFingerprint").asText());
        floor.put("coverageFrom", successor.path("appendedAt").asText());
        floor.put("retirementGeneration", evidence.path("retirementGeneration").asLong());
        floor.put("latestRetirementId", evidence.path("retirementId").asText());
        floor.put("latestRetirementFingerprint",
                retirement.path("retirementFingerprint").asText());
        floor.put("updatedAt", evidence.path("retiredAt").asText());
        floor.put("floorFingerprint", EvidenceVerificationSupport.sha256(floor));
        return floor;
    }

    private static ObjectNode observationSignatureMaterial(JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", attestation.path("schemaVersion").asText());
        material.put("observationId", attestation.path("observationId").asText());
        material.put("observationFingerprint",
                attestation.path("observationFingerprint").asText());
        material.put("sourceEvidenceFingerprint",
                attestation.path("sourceEvidenceFingerprint").asText());
        material.put("sourceAttestationFingerprint",
                attestation.path("sourceAttestationFingerprint").asText());
        material.put("signedAt", attestation.path("signedAt").asText());
        return material;
    }

    private static ObjectNode retirementSignatureMaterial(JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", attestation.path("schemaVersion").asText());
        material.put("retirementId", attestation.path("retirementId").asText());
        material.put("evidenceFingerprint", attestation.path("evidenceFingerprint").asText());
        material.put("archiveSegmentFingerprint",
                attestation.path("archiveSegmentFingerprint").asText());
        material.put("previousFloorFingerprint",
                attestation.path("previousFloorFingerprint").asText());
        material.put("pinnedHeadFingerprint",
                attestation.path("pinnedHeadFingerprint").asText());
        material.put("signedAt", attestation.path("signedAt").asText());
        return material;
    }

    private static ObjectNode pageSignatureMaterial(JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", attestation.path("schemaVersion").asText());
        material.put("lifecyclePageId", attestation.path("lifecyclePageId").asText());
        material.put("requestFingerprint", attestation.path("requestFingerprint").asText());
        material.put("pageFingerprint", attestation.path("pageFingerprint").asText());
        material.put("scopeFingerprint", attestation.path("scopeFingerprint").asText());
        material.put("startingFloorFingerprint",
                attestation.path("startingFloorFingerprint").asText());
        material.put("terminalFloorFingerprint",
                attestation.path("terminalFloorFingerprint").asText());
        material.put("currentFloorFingerprint",
                attestation.path("currentFloorFingerprint").asText());
        material.put("headFingerprint", attestation.path("headFingerprint").asText());
        material.set("retirementRefs", attestation.path("retirementRefs").deepCopy());
        material.put("signedAt", attestation.path("signedAt").asText());
        return material;
    }

    private static List<SigningCoordinate> signingCoordinates(
            TestSuiteStabilityObservationLedgerLifecyclePage page) {
        List<SigningCoordinate> result = new ArrayList<>();
        JsonNode response = page.rawResponse();
        response.path("page").path("retirements").forEach(retirement -> {
            JsonNode archive = retirement.path("evidence").path("archiveSegment");
            archive.path("retiredEntries").forEach(entry ->
                    result.add(coordinate(entry.path("observation").path("attestation"))));
            result.add(coordinate(archive.path("successorEntry")
                    .path("observation").path("attestation")));
            result.add(coordinate(retirement.path("attestation")));
        });
        result.add(coordinate(response.path("attestation")));
        return List.copyOf(result);
    }

    private static SigningCoordinate coordinate(JsonNode attestation) {
        return new SigningCoordinate(attestation.path("keyId").asText(),
                Instant.parse(attestation.path("signedAt").asText()));
    }

    private static String directKeyPolicy(
            EvidenceVerificationKey key,
            String expectedKeyId,
            String algorithm,
            Instant signedAt) {
        if (!key.keyId().equals(expectedKeyId)) {
            return "VERIFICATION_KEY_ID_MISMATCH";
        }
        if (!"Ed25519".equals(algorithm) || !algorithm.equals(key.algorithm())) {
            return "SIGNATURE_ALGORITHM_REJECTED";
        }
        if (!key.verificationAllowed()
                || signedAt.isBefore(key.createdAt().minus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return "VERIFICATION_KEY_POLICY_REJECTED";
        }
        return "";
    }

    private static JsonNode without(JsonNode value, String field) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        return copy;
    }

    private static boolean canonicallyEqual(JsonNode left, JsonNode right) {
        return EvidenceVerificationSupport.sha256(left)
                .equals(EvidenceVerificationSupport.sha256(right));
    }

    private static VerificationProgress failure(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            String reason,
            int verifiedRetirements,
            int verifiedObservations) {
        return new VerificationProgress(result(Outcome.INVALID, reason, page,
                verifiedRetirements, verifiedObservations), null, verifiedObservations);
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            int verifiedRetirements,
            int verifiedObservations) {
        return result(outcome, reason, page.lifecyclePageId(),
                page.attestation().keyId(), verifiedRetirements, verifiedObservations);
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            String pageId,
            String keyId,
            int verifiedRetirements,
            int verifiedObservations) {
        return new VerificationResult(outcome, reason, pageId, keyId,
                verifiedRetirements, verifiedObservations, null);
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record SigningCoordinate(String keyId, Instant signedAt) {
    }

    private record VerificationProgress(
            VerificationResult failure,
            JsonNode successorFloor,
            int verifiedObservations) {
    }
}
