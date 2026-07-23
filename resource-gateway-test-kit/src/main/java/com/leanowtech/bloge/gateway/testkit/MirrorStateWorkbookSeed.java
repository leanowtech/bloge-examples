package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.TreeSet;

/**
 * Payload-free ANEKE correctness-workbook seed derived from independently verified state evidence.
 *
 * <p>The seed is intentionally smaller than a correctness workbook. It supplies stable evidence
 * coordinates, state-head identity, access counts, and conservative publication blockers while
 * leaving ownership, policy, approval, and release decisions to ANEKE. Projection first runs
 * {@link MirrorEvidenceVerifier}; a producer-supplied seed is therefore never trusted merely
 * because its JSON shape is valid.</p>
 *
 * @param seedFingerprint canonical seed fingerprint
 * @param runId exact terminal run
 * @param planFingerprint exact sealed plan
 * @param evidenceBundleFingerprint exact signed bundle
 * @param stateEvidenceRef exact nested state evidence
 * @param sessionStateRef exact immutable Session head
 * @param stateModelRef exact state model
 * @param stateRevision zero-based committed state revision
 * @param worldFingerprint canonical business-world identity
 * @param logicalClock deterministic Session logical time
 * @param mode state access mode observed by the run
 * @param bindingCount state-backed invocation-site count
 * @param accessCount state access count
 * @param liveEntityCount live entity hit count
 * @param absentCount absent key count
 * @param tombstonedCount tombstone count
 * @param runStatus terminal run status
 * @param evidenceClass evidence trust class
 * @param gateReady whether no publication blocker remains
 * @param blockers deterministic publication blockers
 * @param rawPayload defensive complete seed payload
 */
public record MirrorStateWorkbookSeed(
        String seedFingerprint,
        String runId,
        String planFingerprint,
        String evidenceBundleFingerprint,
        ArtifactRef stateEvidenceRef,
        ArtifactRef sessionStateRef,
        ArtifactRef stateModelRef,
        long stateRevision,
        String worldFingerprint,
        Instant logicalClock,
        String mode,
        int bindingCount,
        int accessCount,
        int liveEntityCount,
        int absentCount,
        int tombstonedCount,
        String runStatus,
        String evidenceClass,
        boolean gateReady,
        List<String> blockers,
        JsonNode rawPayload
) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAXIMUM_SEED_BYTES = 1024 * 1024;
    private static final int MAXIMUM_BINDINGS = 10_000;
    private static final int MAXIMUM_ACCESSES = 100_000;
    private static final int MAXIMUM_BLOCKERS = 16;

    /**
     * One exact protocol artifact reference.
     *
     * @param kind stable artifact kind
     * @param id artifact identity
     * @param revision positive immutable revision
     * @param fingerprint canonical artifact fingerprint
     */
    public record ArtifactRef(
            String kind,
            String id,
            long revision,
            String fingerprint
    ) {
        /** Validates one exact payload-free artifact coordinate. */
        public ArtifactRef {
            kind = normalize(kind);
            id = normalize(id);
            fingerprint = normalize(fingerprint);
            if (kind.isBlank() || id.isBlank() || revision < 1
                    || !MirrorStateWorkbookSeed.fingerprint(
                    fingerprint)) {
                throw new IllegalArgumentException(
                        "Mirror workbook artifact reference is incomplete");
            }
        }

        private static ArtifactRef from(JsonNode value) {
            return new ArtifactRef(
                    value.path("kind").asText(),
                    value.path("id").asText(),
                    value.path("revision").asLong(),
                    value.path("fingerprint").asText());
        }
    }

    /** Validates typed identities and protects the complete seed from caller mutation. */
    public MirrorStateWorkbookSeed {
        seedFingerprint = normalize(seedFingerprint);
        runId = normalize(runId);
        planFingerprint = normalize(planFingerprint);
        evidenceBundleFingerprint =
                normalize(evidenceBundleFingerprint);
        worldFingerprint = normalize(worldFingerprint);
        mode = normalize(mode);
        runStatus = normalize(runStatus);
        evidenceClass = normalize(evidenceClass);
        blockers = orderedBlockers(blockers);
        rawPayload = rawPayload == null
                ? null : rawPayload.deepCopy();
        if (!fingerprint(seedFingerprint) || runId.isBlank()
                || !fingerprint(planFingerprint)
                || !fingerprint(evidenceBundleFingerprint)
                || stateEvidenceRef == null
                || !"MIRROR_STATE_RUN_EVIDENCE".equals(
                stateEvidenceRef.kind())
                || sessionStateRef == null
                || !"SESSION_STATE".equals(sessionStateRef.kind())
                || stateModelRef == null
                || !"STATE_MODEL".equals(stateModelRef.kind())
                || stateRevision < 0
                || stateRevision == Long.MAX_VALUE
                || sessionStateRef.revision() != stateRevision + 1
                || !fingerprint(worldFingerprint)
                || logicalClock == null
                || !"READ_ONLY_SNAPSHOT".equals(mode)
                || bindingCount < 1
                || bindingCount > MAXIMUM_BINDINGS
                || accessCount < 0
                || accessCount > MAXIMUM_ACCESSES
                || liveEntityCount < 0
                || liveEntityCount > MAXIMUM_ACCESSES
                || absentCount < 0 || tombstonedCount < 0
                || absentCount > MAXIMUM_ACCESSES
                || tombstonedCount > MAXIMUM_ACCESSES
                || accessCount
                != (long) liveEntityCount + absentCount
                + tombstonedCount
                || runStatus.isBlank()
                || evidenceClass.isBlank()
                || gateReady != blockers.isEmpty()
                || gateReady
                && (!"PASSED".equals(runStatus)
                || !"CERTIFIABLE".equals(evidenceClass))
                || rawPayload == null) {
            throw new IllegalArgumentException(
                    "Mirror state workbook seed is incomplete");
        }
    }

    /**
     * Independently verifies a signed stateful evidence bundle and derives its ANEKE seed.
     *
     * @param bundle decoded portable v3 evidence bundle
     * @param key independently resolved verification key
     * @return deterministic payload-free workbook seed
     */
    public static MirrorStateWorkbookSeed fromVerifiedBundle(
            JsonNode bundle, EvidenceVerificationKey key) {
        MirrorEvidenceVerifier.VerificationResult verified =
                new MirrorEvidenceVerifier().verify(bundle, key);
        if (!verified.verified()) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WORKBOOK_EVIDENCE_"
                            + verified.reasonCode());
        }
        if (!CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V3
                .equals(bundle.path("schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WORKBOOK_REQUIRES_V3");
        }
        JsonNode evidence = bundle.path("evidence");
        JsonNode state = evidence.path("stateEvidence");
        int live = 0;
        int absent = 0;
        int tombstoned = 0;
        for (JsonNode access : state.path("accesses")) {
            switch (access.path("outcome").asText()) {
                case "LIVE_ENTITY" -> live++;
                case "ABSENT" -> absent++;
                case "TOMBSTONED" -> tombstoned++;
                default -> throw new IllegalArgumentException(
                        "MIRROR_STATE_WORKBOOK_OUTCOME_INVALID");
            }
        }
        TreeSet<String> blockers = new TreeSet<>();
        if (!"PASSED".equals(
                evidence.path("status").asText())) {
            blockers.add("RUN_NOT_PASSED");
        }
        if (!"CERTIFIABLE".equals(
                evidence.path("evidenceClass").asText())) {
            blockers.add("EVIDENCE_NOT_CERTIFIABLE");
        }
        if (!evidence.path("limitations").isEmpty()
                || !evidence.path("isolation")
                .path("limitations").isEmpty()) {
            blockers.add("RUN_EVIDENCE_LIMITED");
        }
        if (!state.path("limitations").isEmpty()) {
            blockers.add("STATE_EVIDENCE_LIMITED");
        }

        ObjectNode seed = JSON.createObjectNode();
        seed.put("schemaVersion",
                CapabilityMirrorProtocol
                        .MIRROR_STATE_WORKBOOK_SEED_V1);
        seed.put("seedFingerprint", "");
        seed.put("runId", evidence.path("runId").asText());
        seed.put("planFingerprint",
                evidence.path("planFingerprint").asText());
        seed.put("evidenceBundleFingerprint",
                bundle.path("bundleFingerprint").asText());
        ObjectNode stateEvidenceRef =
                seed.putObject("stateEvidenceRef");
        stateEvidenceRef.put(
                "kind", "MIRROR_STATE_RUN_EVIDENCE");
        stateEvidenceRef.put("id",
                evidence.path("runId").asText());
        stateEvidenceRef.put("revision", 1);
        stateEvidenceRef.put("fingerprint",
                state.path("stateEvidenceFingerprint").asText());
        seed.set("sessionStateRef",
                state.path("sessionStateRef").deepCopy());
        seed.set("stateModelRef",
                state.path("stateModelRef").deepCopy());
        seed.put("stateRevision",
                state.path("stateRevision").asLong());
        seed.put("worldFingerprint",
                state.path("worldFingerprint").asText());
        seed.put("logicalClock",
                state.path("logicalClock").asText());
        seed.put("mode", state.path("mode").asText());
        seed.put("runStatus",
                evidence.path("status").asText());
        seed.put("evidenceClass",
                evidence.path("evidenceClass").asText());
        seed.put("bindingCount",
                state.path("statefulBindings").size());
        seed.put("accessCount", state.path("accesses").size());
        seed.put("liveEntityCount", live);
        seed.put("absentCount", absent);
        seed.put("tombstonedCount", tombstoned);
        seed.put("gateReady", blockers.isEmpty());
        ArrayNode blockerValues = seed.putArray("blockers");
        blockers.forEach(blockerValues::add);
        seed.put("seedFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        seed, MAXIMUM_SEED_BYTES));
        return fromPayload(seed);
    }

    /**
     * Validates and decodes one producer or locally projected seed.
     *
     * <p>This method verifies the seed's own fingerprint only. Use
     * {@link #fromVerifiedBundle(JsonNode, EvidenceVerificationKey)} before release decisions.</p>
     *
     * @param payload decoded workbook-seed payload
     * @return typed defensive seed
     */
    public static MirrorStateWorkbookSeed fromPayload(
            JsonNode payload) {
        CapabilityMirrorSchemaValidator.require(
                payload,
                CapabilityMirrorProtocol
                        .MIRROR_STATE_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.STATE_WORKBOOK_SCHEMA_INVALID");
        ObjectNode material = ((ObjectNode) payload).deepCopy();
        String attached =
                material.path("seedFingerprint").asText();
        material.put("seedFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_SEED_BYTES).equals(attached)) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WORKBOOK_FINGERPRINT_INVALID");
        }
        TreeSet<String> blockers = new TreeSet<>();
        payload.path("blockers").forEach(
                value -> blockers.add(value.asText()));
        try {
            return new MirrorStateWorkbookSeed(
                    attached, payload.path("runId").asText(),
                    payload.path("planFingerprint").asText(),
                    payload.path("evidenceBundleFingerprint").asText(),
                    ArtifactRef.from(
                            payload.path("stateEvidenceRef")),
                    ArtifactRef.from(
                            payload.path("sessionStateRef")),
                    ArtifactRef.from(
                            payload.path("stateModelRef")),
                    payload.path("stateRevision").asLong(),
                    payload.path("worldFingerprint").asText(),
                    Instant.parse(
                            payload.path("logicalClock").asText()),
                    payload.path("mode").asText(),
                    payload.path("bindingCount").asInt(),
                    payload.path("accessCount").asInt(),
                    payload.path("liveEntityCount").asInt(),
                    payload.path("absentCount").asInt(),
                    payload.path("tombstonedCount").asInt(),
                    payload.path("runStatus").asText(),
                    payload.path("evidenceClass").asText(),
                    payload.path("gateReady").asBoolean(),
                    List.copyOf(blockers), payload);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WORKBOOK_TIME_INVALID");
        }
    }

    /**
     * Fails closed when the seed is not eligible for a publication gate.
     *
     * @throws IllegalStateException with stable blocker codes when not ready
     */
    public void requireGateReady() {
        if (!gateReady) {
            throw new IllegalStateException(
                    "MIRROR_STATE_WORKBOOK_NOT_GATE_READY:"
                            + String.join(",", blockers));
        }
    }

    /**
     * Returns the complete protocol payload for downstream workbook ingestion.
     *
     * @return defensive complete seed payload
     */
    @Override
    public JsonNode rawPayload() {
        return rawPayload.deepCopy();
    }

    private static boolean fingerprint(String value) {
        return normalize(value).matches(
                "sha256:[0-9a-f]{64}");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> orderedBlockers(
            List<String> values) {
        TreeSet<String> ordered = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String blocker = normalize(value);
                if (!blocker.matches(
                        "[A-Z][A-Z0-9_.-]{0,255}")
                        || !ordered.add(blocker)) {
                    throw new IllegalArgumentException(
                            "Mirror state workbook blockers are invalid");
                }
            }
        }
        if (ordered.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "Mirror state workbook blockers exceed the protocol bound");
        }
        return List.copyOf(ordered);
    }
}
