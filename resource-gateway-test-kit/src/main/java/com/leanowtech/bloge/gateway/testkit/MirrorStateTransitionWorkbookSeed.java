package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Payload-free ANEKE transition-workbook seed derived from independently verified v4 evidence.
 *
 * <p>The type exposes exact state-head, transaction-receipt, idempotent-replay, and event
 * coordinates without exposing command payloads, response payloads, raw entity ids, or raw
 * idempotency keys. {@link #fromVerifiedBundle(JsonNode, EvidenceVerificationKey)} first verifies
 * the detached evidence signature and the complete nested transition closure. A seed read from a
 * producer can be decoded with {@link #fromPayload(JsonNode)}, but release decisions should compare
 * it with a locally projected seed.</p>
 *
 * @param seedFingerprint canonical seed fingerprint
 * @param runId exact terminal run
 * @param planFingerprint exact sealed plan
 * @param evidenceBundleFingerprint exact signed bundle
 * @param stateEvidenceRef exact nested state-transition evidence
 * @param initialSessionStateRef Session head admitted before execution
 * @param finalSessionStateRef Session head visible after execution
 * @param stateModelRef exact state model
 * @param initialStateRevision initial committed state revision
 * @param finalStateRevision final committed state revision
 * @param initialWorldFingerprint initial business-world identity
 * @param finalWorldFingerprint final business-world identity
 * @param initialLogicalClock initial deterministic logical time
 * @param finalLogicalClock final deterministic logical time
 * @param mode observed state access mode
 * @param runStatus terminal run status
 * @param evidenceClass evidence trust class
 * @param bindingCount state-backed invocation-site count
 * @param accessCount state-read count
 * @param liveEntityCount live entity hit count
 * @param absentCount absent key count
 * @param tombstonedCount tombstone count
 * @param transitionCount virtual-write count
 * @param committedTransitionCount newly committed write count
 * @param replayedTransitionCount exact replay count
 * @param eventCount payload-free receipt-event count
 * @param stateAdvanced whether a new state revision committed
 * @param writeAssertions exact ordered transaction assertions
 * @param gateReady whether no conservative publication blocker remains
 * @param blockers deterministic publication blockers
 * @param rawPayload defensive complete seed payload
 */
public record MirrorStateTransitionWorkbookSeed(
        String seedFingerprint,
        String runId,
        String planFingerprint,
        String evidenceBundleFingerprint,
        ArtifactRef stateEvidenceRef,
        ArtifactRef initialSessionStateRef,
        ArtifactRef finalSessionStateRef,
        ArtifactRef stateModelRef,
        long initialStateRevision,
        long finalStateRevision,
        String initialWorldFingerprint,
        String finalWorldFingerprint,
        Instant initialLogicalClock,
        Instant finalLogicalClock,
        String mode,
        String runStatus,
        String evidenceClass,
        int bindingCount,
        int accessCount,
        int liveEntityCount,
        int absentCount,
        int tombstonedCount,
        int transitionCount,
        int committedTransitionCount,
        int replayedTransitionCount,
        int eventCount,
        boolean stateAdvanced,
        List<WriteAssertion> writeAssertions,
        boolean gateReady,
        List<String> blockers,
        JsonNode rawPayload
) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAXIMUM_SEED_BYTES =
            64 * 1024 * 1024;
    private static final int MAXIMUM_BINDINGS = 10_000;
    private static final int MAXIMUM_INTERACTIONS = 100_000;
    private static final int MAXIMUM_EVENTS = 12_800_000;
    private static final int MAXIMUM_BLOCKERS = 16;
    private static final Comparator<WriteAssertion> WRITE_ORDER =
            Comparator.comparing(WriteAssertion::invocationSiteId)
                    .thenComparing(WriteAssertion::correlationKey)
                    .thenComparingInt(WriteAssertion::occurrence)
                    .thenComparingInt(WriteAssertion::attempt);

    /**
     * One exact immutable protocol artifact reference.
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
            if (kind.isBlank() || id.isBlank()
                    || revision < 1
                    || !isFingerprint(fingerprint)) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook artifact reference is incomplete");
            }
        }

        /**
         * Decodes one strict artifact reference already admitted by a protocol schema.
         *
         * @param value decoded artifact-reference object
         * @return validated immutable reference
         */
        public static ArtifactRef from(JsonNode value) {
            return new ArtifactRef(
                    value.path("kind").asText(),
                    value.path("id").asText(),
                    value.path("revision").asLong(),
                    value.path("fingerprint").asText());
        }
    }

    /**
     * One exact committed or replayed virtual-write assertion.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph path
     * @param correlationKey loop or business correlation coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based attempt
     * @param capabilityRef exact virtual-write capability
     * @param writeEffectRef exact lowering effect
     * @param initialStateRef state head before the command
     * @param finalStateRef state head after the command
     * @param revisionBefore revision before the command
     * @param revisionAfter revision after the command
     * @param initialWorldFingerprint world before the command
     * @param finalWorldFingerprint world after the command
     * @param initialLogicalClock logical time before the command
     * @param finalLogicalClock logical time after the command
     * @param requestFingerprint canonical invocation-input identity
     * @param idempotencyKeyFingerprint hash of the raw command key
     * @param commandFingerprint exact Session command identity
     * @param receiptFingerprint exact transaction receipt
     * @param responseFingerprint exact command-response identity
     * @param resultingWorldFingerprint world claimed by the receipt
     * @param committedAt governed logical commit time
     * @param replayed whether an existing receipt was replayed
     * @param events exact payload-free receipt events
     */
    public record WriteAssertion(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            ArtifactRef capabilityRef,
            ArtifactRef writeEffectRef,
            ArtifactRef initialStateRef,
            ArtifactRef finalStateRef,
            long revisionBefore,
            long revisionAfter,
            String initialWorldFingerprint,
            String finalWorldFingerprint,
            Instant initialLogicalClock,
            Instant finalLogicalClock,
            String requestFingerprint,
            String idempotencyKeyFingerprint,
            String commandFingerprint,
            String receiptFingerprint,
            String responseFingerprint,
            String resultingWorldFingerprint,
            Instant committedAt,
            boolean replayed,
            List<EventAssertion> events
    ) {
        /** Validates one complete payload-free write assertion. */
        public WriteAssertion {
            invocationSiteId = normalize(invocationSiteId);
            graphPath = normalize(graphPath);
            correlationKey = normalize(correlationKey);
            initialWorldFingerprint =
                    normalize(initialWorldFingerprint);
            finalWorldFingerprint =
                    normalize(finalWorldFingerprint);
            requestFingerprint = normalize(requestFingerprint);
            idempotencyKeyFingerprint =
                    normalize(idempotencyKeyFingerprint);
            commandFingerprint =
                    normalize(commandFingerprint);
            receiptFingerprint =
                    normalize(receiptFingerprint);
            responseFingerprint =
                    normalize(responseFingerprint);
            resultingWorldFingerprint =
                    normalize(resultingWorldFingerprint);
            events = events == null ? List.of()
                    : List.copyOf(events);
            if (invocationSiteId.isBlank()
                    || graphPath.isBlank()
                    || occurrence < 1 || attempt < 1
                    || capabilityRef == null
                    || !"CAPABILITY".equals(capabilityRef.kind())
                    || writeEffectRef == null
                    || !"WRITE_EFFECT".equals(
                    writeEffectRef.kind())
                    || initialStateRef == null
                    || finalStateRef == null
                    || !"SESSION_STATE".equals(
                    initialStateRef.kind())
                    || !"SESSION_STATE".equals(
                    finalStateRef.kind())
                    || !initialStateRef.id().equals(
                    finalStateRef.id())
                    || revisionBefore < 0
                    || revisionAfter < revisionBefore
                    || revisionBefore == Long.MAX_VALUE
                    || revisionAfter == Long.MAX_VALUE
                    || initialStateRef.revision()
                    != revisionBefore + 1
                    || finalStateRef.revision()
                    != revisionAfter + 1
                    || !fingerprints(
                    initialWorldFingerprint,
                    finalWorldFingerprint,
                    requestFingerprint,
                    idempotencyKeyFingerprint,
                    commandFingerprint,
                    receiptFingerprint,
                    responseFingerprint,
                    resultingWorldFingerprint)
                    || initialLogicalClock == null
                    || finalLogicalClock == null
                    || committedAt == null
                    || finalLogicalClock.isBefore(
                    initialLogicalClock)
                    || events.isEmpty()
                    || events.size() > 128) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook write assertion is incomplete");
            }
            requireOrderedEvents(events);
            if (replayed
                    && (!initialStateRef.equals(finalStateRef)
                    || revisionBefore != revisionAfter
                    || !initialWorldFingerprint.equals(
                    finalWorldFingerprint)
                    || !initialLogicalClock.equals(
                    finalLogicalClock))
                    || !replayed
                    && (revisionAfter != revisionBefore + 1
                    || !resultingWorldFingerprint.equals(
                    finalWorldFingerprint)
                    || events.stream().anyMatch(
                    event -> event.stateRevision()
                            != revisionAfter))) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook write progression is inconsistent");
            }
        }

        /**
         * Decodes one strict successful transition assertion.
         *
         * @param value decoded write assertion
         * @return validated payload-free transition assertion
         */
        public static WriteAssertion from(JsonNode value) {
            return new WriteAssertion(
                    value.path("invocationSiteId").asText(),
                    value.path("graphPath").asText(),
                    value.path("correlationKey").asText(),
                    value.path("occurrence").asInt(),
                    value.path("attempt").asInt(),
                    ArtifactRef.from(value.path("capabilityRef")),
                    ArtifactRef.from(value.path("writeEffectRef")),
                    ArtifactRef.from(value.path("initialStateRef")),
                    ArtifactRef.from(value.path("finalStateRef")),
                    value.path("revisionBefore").asLong(),
                    value.path("revisionAfter").asLong(),
                    value.path("initialWorldFingerprint").asText(),
                    value.path("finalWorldFingerprint").asText(),
                    instant(value, "initialLogicalClock"),
                    instant(value, "finalLogicalClock"),
                    value.path("requestFingerprint").asText(),
                    value.path("idempotencyKeyFingerprint").asText(),
                    value.path("commandFingerprint").asText(),
                    value.path("receiptFingerprint").asText(),
                    value.path("responseFingerprint").asText(),
                    value.path("resultingWorldFingerprint").asText(),
                    instant(value, "committedAt"),
                    value.path("replayed").asBoolean(),
                    stream(value.path("events"))
                            .map(EventAssertion::from).toList());
        }

        private String coordinate() {
            return invocationSiteId + '\0'
                    + correlationKey + '\0' + occurrence
                    + '\0' + attempt;
        }
    }

    /**
     * One payload-free entity event nested under a transaction receipt.
     *
     * @param eventIdFingerprint hash of the internal event id
     * @param stateRevision committed transaction revision
     * @param mutationId owner-governed mutation alias
     * @param operation transition operation
     * @param entityType owner-governed entity type
     * @param entityIdentityFingerprint hash of entity type and raw id
     * @param beforeFingerprint prior entity identity, or blank
     * @param afterFingerprint resulting entity identity, or blank
     * @param occurredAt governed logical event time
     * @param eventFingerprint exact sealed event identity
     */
    public record EventAssertion(
            String eventIdFingerprint,
            long stateRevision,
            String mutationId,
            String operation,
            String entityType,
            String entityIdentityFingerprint,
            String beforeFingerprint,
            String afterFingerprint,
            Instant occurredAt,
            String eventFingerprint
    ) {
        /** Validates one complete payload-free event assertion. */
        public EventAssertion {
            eventIdFingerprint =
                    normalize(eventIdFingerprint);
            mutationId = normalize(mutationId);
            operation = normalize(operation);
            entityType = normalize(entityType);
            entityIdentityFingerprint =
                    normalize(entityIdentityFingerprint);
            beforeFingerprint =
                    normalize(beforeFingerprint);
            afterFingerprint = normalize(afterFingerprint);
            eventFingerprint =
                    normalize(eventFingerprint);
            if (!isFingerprint(eventIdFingerprint)
                    || stateRevision < 1
                    || mutationId.isBlank()
                    || !List.of("CREATE", "UPDATE", "DELETE")
                    .contains(operation)
                    || entityType.isBlank()
                    || !isFingerprint(
                    entityIdentityFingerprint)
                    || !optionalFingerprint(
                    beforeFingerprint)
                    || !optionalFingerprint(afterFingerprint)
                    || occurredAt == null
                    || !isFingerprint(eventFingerprint)) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook event assertion is incomplete");
            }
        }

        private static EventAssertion from(JsonNode value) {
            return new EventAssertion(
                    value.path("eventIdFingerprint").asText(),
                    value.path("stateRevision").asLong(),
                    value.path("mutationId").asText(),
                    value.path("operation").asText(),
                    value.path("entityType").asText(),
                    value.path("entityIdentityFingerprint").asText(),
                    value.path("beforeFingerprint").asText(),
                    value.path("afterFingerprint").asText(),
                    instant(value, "occurredAt"),
                    value.path("eventFingerprint").asText());
        }
    }

    /** Validates cross-field counts, state progression, and publication readiness. */
    public MirrorStateTransitionWorkbookSeed {
        seedFingerprint = normalize(seedFingerprint);
        runId = normalize(runId);
        planFingerprint = normalize(planFingerprint);
        evidenceBundleFingerprint =
                normalize(evidenceBundleFingerprint);
        initialWorldFingerprint =
                normalize(initialWorldFingerprint);
        finalWorldFingerprint =
                normalize(finalWorldFingerprint);
        mode = normalize(mode);
        runStatus = normalize(runStatus);
        evidenceClass = normalize(evidenceClass);
        writeAssertions = writeAssertions == null
                ? List.of() : List.copyOf(writeAssertions);
        blockers = orderedBlockers(blockers);
        rawPayload = rawPayload == null
                ? null : rawPayload.deepCopy();
        if (!fingerprints(
                seedFingerprint, planFingerprint,
                evidenceBundleFingerprint,
                initialWorldFingerprint,
                finalWorldFingerprint)
                || runId.isBlank()
                || stateEvidenceRef == null
                || !"MIRROR_STATE_RUN_EVIDENCE".equals(
                stateEvidenceRef.kind())
                || stateEvidenceRef.revision() != 2
                || initialSessionStateRef == null
                || finalSessionStateRef == null
                || !"SESSION_STATE".equals(
                initialSessionStateRef.kind())
                || !"SESSION_STATE".equals(
                finalSessionStateRef.kind())
                || !initialSessionStateRef.id().equals(
                finalSessionStateRef.id())
                || stateModelRef == null
                || !"STATE_MODEL".equals(stateModelRef.kind())
                || initialStateRevision < 0
                || finalStateRevision < initialStateRevision
                || initialStateRevision == Long.MAX_VALUE
                || finalStateRevision == Long.MAX_VALUE
                || initialSessionStateRef.revision()
                != initialStateRevision + 1
                || finalSessionStateRef.revision()
                != finalStateRevision + 1
                || initialLogicalClock == null
                || finalLogicalClock == null
                || finalLogicalClock.isBefore(
                initialLogicalClock)
                || !"SERIALIZABLE_READ_WRITE".equals(mode)
                || bindingCount < 1
                || bindingCount > MAXIMUM_BINDINGS
                || accessCount < 0
                || accessCount > MAXIMUM_INTERACTIONS
                || liveEntityCount < 0
                || absentCount < 0
                || tombstonedCount < 0
                || accessCount
                != (long) liveEntityCount + absentCount
                + tombstonedCount
                || transitionCount < 0
                || transitionCount > MAXIMUM_INTERACTIONS
                || transitionCount != writeAssertions.size()
                || transitionCount
                != (long) committedTransitionCount
                + replayedTransitionCount
                || committedTransitionCount < 0
                || replayedTransitionCount < 0
                || eventCount < 0
                || eventCount > MAXIMUM_EVENTS
                || committedTransitionCount
                != writeAssertions.stream()
                .filter(value -> !value.replayed()).count()
                || replayedTransitionCount
                != writeAssertions.stream()
                .filter(WriteAssertion::replayed).count()
                || eventCount != writeAssertions.stream()
                .mapToInt(value -> value.events().size())
                .sum()
                || stateAdvanced
                != (finalStateRevision
                > initialStateRevision)
                || stateAdvanced
                != (committedTransitionCount > 0)
                || runStatus.isBlank()
                || evidenceClass.isBlank()
                || gateReady != blockers.isEmpty()
                || gateReady
                && (transitionCount == 0
                || !"PASSED".equals(runStatus)
                || !"CERTIFIABLE".equals(evidenceClass))
                || rawPayload == null) {
            throw new IllegalArgumentException(
                    "Mirror state transition workbook seed is incomplete");
        }
        requireOrderedWrites(writeAssertions);
        requireWriteClosure(
                writeAssertions, initialSessionStateRef,
                finalSessionStateRef, initialStateRevision,
                finalStateRevision);
    }

    /**
     * Independently verifies a signed v4 evidence bundle and derives its transition seed.
     *
     * @param bundle decoded portable v4 evidence bundle
     * @param key independently resolved verification key
     * @return deterministic payload-free transition-workbook seed
     */
    public static MirrorStateTransitionWorkbookSeed
    fromVerifiedBundle(
            JsonNode bundle, EvidenceVerificationKey key) {
        MirrorEvidenceVerifier.VerificationResult verified =
                new MirrorEvidenceVerifier().verify(bundle, key);
        if (!verified.verified()) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_TRANSITION_WORKBOOK_EVIDENCE_"
                            + verified.reasonCode());
        }
        if (!CapabilityMirrorProtocol
                .MIRROR_EVIDENCE_BUNDLE_V4.equals(
                        bundle.path("schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_TRANSITION_WORKBOOK_REQUIRES_V4");
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
                        "MIRROR_STATE_TRANSITION_WORKBOOK_OUTCOME_INVALID");
            }
        }
        int committed = 0;
        int replayed = 0;
        int events = 0;
        for (JsonNode transition : state.path("transitions")) {
            if (transition.path("replayed").asBoolean()) {
                replayed++;
            } else {
                committed++;
            }
            events += transition.path("events").size();
        }
        TreeSet<String> blockers = blockers(evidence, state);
        if (state.path("transitions").isEmpty()) {
            blockers.add("NO_STATE_TRANSITION_OBSERVED");
        }

        ObjectNode seed = JSON.createObjectNode();
        seed.put("schemaVersion", CapabilityMirrorProtocol
                .MIRROR_STATE_TRANSITION_WORKBOOK_SEED_V1);
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
        stateEvidenceRef.put("revision", 2);
        stateEvidenceRef.put("fingerprint",
                state.path("stateEvidenceFingerprint")
                        .asText());
        seed.set("initialSessionStateRef",
                state.path("sessionStateRef").deepCopy());
        seed.set("finalSessionStateRef",
                state.path("finalSessionStateRef").deepCopy());
        seed.set("stateModelRef",
                state.path("stateModelRef").deepCopy());
        seed.put("initialStateRevision",
                state.path("stateRevision").asLong());
        seed.put("finalStateRevision",
                state.path("finalStateRevision").asLong());
        seed.put("initialWorldFingerprint",
                state.path("worldFingerprint").asText());
        seed.put("finalWorldFingerprint",
                state.path("finalWorldFingerprint").asText());
        seed.put("initialLogicalClock",
                state.path("logicalClock").asText());
        seed.put("finalLogicalClock",
                state.path("finalLogicalClock").asText());
        seed.put("mode", state.path("mode").asText());
        seed.put("runStatus",
                evidence.path("status").asText());
        seed.put("evidenceClass",
                evidence.path("evidenceClass").asText());
        seed.put("bindingCount",
                state.path("statefulBindings").size());
        seed.put("accessCount",
                state.path("accesses").size());
        seed.put("liveEntityCount", live);
        seed.put("absentCount", absent);
        seed.put("tombstonedCount", tombstoned);
        seed.put("transitionCount",
                state.path("transitions").size());
        seed.put("committedTransitionCount", committed);
        seed.put("replayedTransitionCount", replayed);
        seed.put("eventCount", events);
        seed.put("stateAdvanced",
                state.path("finalStateRevision").asLong()
                        > state.path("stateRevision").asLong());
        ArrayNode assertions =
                seed.putArray("writeAssertions");
        state.path("transitions").forEach(
                transition -> assertions.add(
                        transition.deepCopy()));
        seed.put("gateReady", blockers.isEmpty());
        ArrayNode blockerValues = seed.putArray("blockers");
        blockers.forEach(blockerValues::add);
        seed.put("seedFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        seed, MAXIMUM_SEED_BYTES));
        return fromPayload(seed);
    }

    /**
     * Validates and decodes one producer or locally projected transition seed.
     *
     * <p>This method validates strict Schema, self-fingerprint, state arithmetic, count closure,
     * transaction ordering, and committed-head closure. It does not replace verification of the
     * source evidence signature.</p>
     *
     * @param payload decoded transition-workbook seed payload
     * @return typed defensive seed
     */
    public static MirrorStateTransitionWorkbookSeed
    fromPayload(JsonNode payload) {
        CapabilityMirrorSchemaValidator.require(
                payload,
                CapabilityMirrorProtocol
                        .MIRROR_STATE_TRANSITION_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.STATE_TRANSITION_WORKBOOK_SCHEMA_INVALID");
        ObjectNode material =
                ((ObjectNode) payload).deepCopy();
        String attached =
                material.path("seedFingerprint").asText();
        material.put("seedFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_SEED_BYTES).equals(attached)) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_TRANSITION_WORKBOOK_FINGERPRINT_INVALID");
        }
        TreeSet<String> blockers = new TreeSet<>();
        payload.path("blockers").forEach(
                value -> blockers.add(value.asText()));
        try {
            return new MirrorStateTransitionWorkbookSeed(
                    attached,
                    payload.path("runId").asText(),
                    payload.path("planFingerprint").asText(),
                    payload.path(
                            "evidenceBundleFingerprint").asText(),
                    ArtifactRef.from(
                            payload.path("stateEvidenceRef")),
                    ArtifactRef.from(payload.path(
                            "initialSessionStateRef")),
                    ArtifactRef.from(payload.path(
                            "finalSessionStateRef")),
                    ArtifactRef.from(
                            payload.path("stateModelRef")),
                    payload.path(
                            "initialStateRevision").asLong(),
                    payload.path(
                            "finalStateRevision").asLong(),
                    payload.path(
                            "initialWorldFingerprint").asText(),
                    payload.path(
                            "finalWorldFingerprint").asText(),
                    instant(payload, "initialLogicalClock"),
                    instant(payload, "finalLogicalClock"),
                    payload.path("mode").asText(),
                    payload.path("runStatus").asText(),
                    payload.path("evidenceClass").asText(),
                    payload.path("bindingCount").asInt(),
                    payload.path("accessCount").asInt(),
                    payload.path("liveEntityCount").asInt(),
                    payload.path("absentCount").asInt(),
                    payload.path("tombstonedCount").asInt(),
                    payload.path("transitionCount").asInt(),
                    payload.path(
                            "committedTransitionCount").asInt(),
                    payload.path(
                            "replayedTransitionCount").asInt(),
                    payload.path("eventCount").asInt(),
                    payload.path("stateAdvanced").asBoolean(),
                    stream(payload.path("writeAssertions"))
                            .map(WriteAssertion::from).toList(),
                    payload.path("gateReady").asBoolean(),
                    List.copyOf(blockers), payload);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_TRANSITION_WORKBOOK_TIME_INVALID");
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
                    "MIRROR_STATE_TRANSITION_WORKBOOK_NOT_GATE_READY:"
                            + String.join(",", blockers));
        }
    }

    /**
     * Returns the complete protocol payload for workbook ingestion.
     *
     * @return defensive complete transition seed payload
     */
    @Override
    public JsonNode rawPayload() {
        return rawPayload.deepCopy();
    }

    private static TreeSet<String> blockers(
            JsonNode evidence, JsonNode state) {
        TreeSet<String> result = new TreeSet<>();
        if (!"PASSED".equals(
                evidence.path("status").asText())) {
            result.add("RUN_NOT_PASSED");
        }
        if (!"CERTIFIABLE".equals(
                evidence.path("evidenceClass").asText())) {
            result.add("EVIDENCE_NOT_CERTIFIABLE");
        }
        if (!evidence.path("limitations").isEmpty()
                || !evidence.path("isolation")
                .path("limitations").isEmpty()) {
            result.add("RUN_EVIDENCE_LIMITED");
        }
        if (!state.path("limitations").isEmpty()) {
            result.add("STATE_EVIDENCE_LIMITED");
        }
        return result;
    }

    private static void requireOrderedWrites(
            List<WriteAssertion> writes) {
        HashSet<String> coordinates = new HashSet<>();
        WriteAssertion previous = null;
        for (WriteAssertion write : writes) {
            if (write == null
                    || !coordinates.add(write.coordinate())
                    || previous != null
                    && WRITE_ORDER.compare(
                    previous, write) > 0) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook writes are unordered or duplicated");
            }
            previous = write;
        }
    }

    private static void requireWriteClosure(
            List<WriteAssertion> writes,
            ArtifactRef initial,
            ArtifactRef terminal,
            long initialRevision,
            long finalRevision) {
        long expectedRevision = initialRevision;
        ArtifactRef expectedHead = initial;
        for (WriteAssertion write : writes) {
            if (!initial.id().equals(
                    write.initialStateRef().id())
                    || write.revisionBefore()
                    < initialRevision
                    || write.revisionAfter()
                    > finalRevision) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook write falls outside the run range");
            }
        }
        for (WriteAssertion write : writes.stream()
                .filter(value -> !value.replayed())
                .sorted(Comparator.comparingLong(
                        WriteAssertion::revisionAfter))
                .toList()) {
            if (write.revisionBefore()
                    != expectedRevision
                    || !write.initialStateRef().equals(
                    expectedHead)) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook committed writes do not form one chain");
            }
            expectedRevision = write.revisionAfter();
            expectedHead = write.finalStateRef();
        }
        if (expectedRevision != finalRevision
                || !expectedHead.equals(terminal)) {
            throw new IllegalArgumentException(
                    "Mirror transition workbook does not reach the final head");
        }
    }

    private static void requireOrderedEvents(
            List<EventAssertion> events) {
        String previous = null;
        HashSet<String> identities = new HashSet<>();
        for (EventAssertion event : events) {
            if (event == null
                    || !identities.add(
                    event.eventIdFingerprint())
                    || previous != null
                    && previous.compareTo(
                    event.eventIdFingerprint()) > 0) {
                throw new IllegalArgumentException(
                        "Mirror transition workbook events are unordered or duplicated");
            }
            previous = event.eventIdFingerprint();
        }
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
                            "Mirror transition workbook blockers are invalid");
                }
            }
        }
        if (ordered.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "Mirror transition workbook blockers exceed the protocol bound");
        }
        return List.copyOf(ordered);
    }

    private static java.util.stream.Stream<JsonNode> stream(
            JsonNode array) {
        return java.util.stream.StreamSupport.stream(
                array.spliterator(), false);
    }

    private static Instant instant(
            JsonNode value, String field) {
        return Instant.parse(value.path(field).asText());
    }

    private static boolean fingerprints(String... values) {
        for (String value : values) {
            if (!isFingerprint(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFingerprint(String value) {
        return normalize(value).matches(
                "sha256:[0-9a-f]{64}");
    }

    private static boolean optionalFingerprint(
            String value) {
        String normalized = normalize(value);
        return normalized.isEmpty()
                || isFingerprint(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
