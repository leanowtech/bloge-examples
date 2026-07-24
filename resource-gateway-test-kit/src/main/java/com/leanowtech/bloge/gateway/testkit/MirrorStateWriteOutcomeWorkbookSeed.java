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
 * Failure-aware ANEKE workbook seed derived from independently verified v5 mirror evidence.
 *
 * <p>The type exposes one terminal assertion per state-write delegate attempt. It distinguishes
 * committed, replayed, rejected, proven pre-commit failure, and unknown durable commit outcomes
 * without carrying command payloads, response payloads, raw idempotency keys, entity ids, or
 * entity values. {@link #fromVerifiedBundle(JsonNode, EvidenceVerificationKey)} verifies the
 * detached signature and complete graph/state closure before projecting a local seed. A producer
 * seed decoded through {@link #fromPayload(JsonNode)} should be accepted only when its canonical
 * fingerprint and source coordinates equal that local projection.</p>
 *
 * @param seedFingerprint canonical seed fingerprint
 * @param runId exact terminal run
 * @param planFingerprint exact sealed plan
 * @param evidenceBundleFingerprint exact signed bundle
 * @param stateEvidenceRef exact nested failure-aware state evidence
 * @param initialSessionStateRef Session head admitted before execution
 * @param finalSessionStateRef final Session head verified in process
 * @param stateModelRef exact state model
 * @param initialStateRevision initial committed revision
 * @param finalStateRevision final in-process committed revision
 * @param initialWorldFingerprint initial business-world identity
 * @param finalWorldFingerprint final in-process business-world identity
 * @param initialLogicalClock initial deterministic logical time
 * @param finalLogicalClock final deterministic logical time verified in process
 * @param mode observed failure-aware state mode
 * @param runStatus terminal run status
 * @param evidenceClass evidence trust class
 * @param bindingCount state-backed invocation-site count
 * @param accessCount state-read count
 * @param writeAttemptCount terminal write-attempt count
 * @param committedCount newly committed write count
 * @param replayedCount exact replay count
 * @param rejectedCount governed rejection count
 * @param preCommitFailedCount proven non-commit failure count
 * @param commitOutcomeUnknownCount unresolved commit-outcome count
 * @param eventCount payload-free receipt-event count
 * @param stateAdvanced whether a new state revision committed
 * @param writeAttemptAssertions exact ordered attempt assertions
 * @param gateReady whether no conservative publication blocker remains
 * @param blockers deterministic publication blockers
 * @param rawPayload defensive complete seed payload
 */
public record MirrorStateWriteOutcomeWorkbookSeed(
        String seedFingerprint,
        String runId,
        String planFingerprint,
        String evidenceBundleFingerprint,
        MirrorStateTransitionWorkbookSeed.ArtifactRef
                stateEvidenceRef,
        MirrorStateTransitionWorkbookSeed.ArtifactRef
                initialSessionStateRef,
        MirrorStateTransitionWorkbookSeed.ArtifactRef
                finalSessionStateRef,
        MirrorStateTransitionWorkbookSeed.ArtifactRef
                stateModelRef,
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
        int writeAttemptCount,
        int committedCount,
        int replayedCount,
        int rejectedCount,
        int preCommitFailedCount,
        int commitOutcomeUnknownCount,
        int eventCount,
        boolean stateAdvanced,
        List<WriteAttemptAssertion>
                writeAttemptAssertions,
        boolean gateReady,
        List<String> blockers,
        JsonNode rawPayload
) {
    private static final ObjectMapper JSON =
            new ObjectMapper();
    private static final int MAXIMUM_SEED_BYTES =
            64 * 1024 * 1024;
    private static final int MAXIMUM_BINDINGS = 10_000;
    private static final int MAXIMUM_INTERACTIONS = 100_000;
    private static final int MAXIMUM_BLOCKERS = 16;
    private static final Comparator<WriteAttemptAssertion>
            ATTEMPT_ORDER =
            Comparator.comparing(
                    WriteAttemptAssertion::invocationSiteId)
                    .thenComparing(
                            WriteAttemptAssertion::graphPath)
                    .thenComparing(
                            WriteAttemptAssertion::correlationKey)
                    .thenComparingInt(
                            WriteAttemptAssertion::occurrence)
                    .thenComparingInt(
                            WriteAttemptAssertion::attempt);

    /**
     * One exact immutable state-write attempt assertion.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph path
     * @param correlationKey loop or business correlation coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based attempt
     * @param capabilityRef exact virtual-write capability
     * @param writeEffectRef exact lowering effect
     * @param observedStateRef Session head observed before the attempt
     * @param observedStateRevision committed revision observed before the attempt
     * @param observedWorldFingerprint world observed before the attempt
     * @param observedLogicalClock logical time observed before the attempt
     * @param requestFingerprint canonical invocation-input identity
     * @param outcome terminal write outcome
     * @param stage last trustworthy processing stage
     * @param stateDisposition proven state-head effect
     * @param retryable whether governed retry was authorized
     * @param errorCode stable failure code; blank on success
     * @param errorType normalized failure family; blank on success
     * @param failureFingerprint canonical failure identity; blank on success
     * @param transition successful receipt/event assertion, otherwise null
     */
    public record WriteAttemptAssertion(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    capabilityRef,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    writeEffectRef,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String requestFingerprint,
            String outcome,
            String stage,
            String stateDisposition,
            boolean retryable,
            String errorCode,
            String errorType,
            String failureFingerprint,
            MirrorStateTransitionWorkbookSeed.WriteAssertion
                    transition
    ) {
        /** Validates one complete payload-free write-attempt assertion. */
        public WriteAttemptAssertion {
            invocationSiteId = normalize(invocationSiteId);
            graphPath = normalize(graphPath);
            correlationKey = normalize(correlationKey);
            observedWorldFingerprint =
                    normalize(observedWorldFingerprint);
            requestFingerprint =
                    normalize(requestFingerprint);
            outcome = normalize(outcome);
            stage = normalize(stage);
            stateDisposition =
                    normalize(stateDisposition);
            errorCode = normalize(errorCode);
            errorType = normalize(errorType);
            failureFingerprint =
                    normalize(failureFingerprint);
            boolean successful =
                    "COMMITTED".equals(outcome)
                            || "REPLAYED".equals(outcome);
            if (invocationSiteId.isBlank()
                    || graphPath.isBlank()
                    || occurrence < 1 || attempt < 1
                    || capabilityRef == null
                    || !"CAPABILITY".equals(
                    capabilityRef.kind())
                    || writeEffectRef == null
                    || !"WRITE_EFFECT".equals(
                    writeEffectRef.kind())
                    || observedStateRef == null
                    || !"SESSION_STATE".equals(
                    observedStateRef.kind())
                    || observedStateRevision < 0
                    || observedStateRevision
                    == Long.MAX_VALUE
                    || observedStateRef.revision()
                    != observedStateRevision + 1
                    || !isFingerprint(
                    observedWorldFingerprint)
                    || observedLogicalClock == null
                    || !isFingerprint(
                    requestFingerprint)
                    || !List.of(
                    "COMMITTED", "REPLAYED",
                    "REJECTED", "PRE_COMMIT_FAILED",
                    "COMMIT_OUTCOME_UNKNOWN")
                    .contains(outcome)
                    || stage.isBlank()
                    || stateDisposition.isBlank()
                    || successful
                    && (transition == null
                    || retryable
                    || !"COMPLETED".equals(stage)
                    || !errorCode.isBlank()
                    || !errorType.isBlank()
                    || !failureFingerprint.isBlank())
                    || !successful
                    && (transition != null
                    || errorCode.isBlank()
                    || errorType.isBlank()
                    || !isFingerprint(
                            failureFingerprint))) {
                throw new IllegalArgumentException(
                        "Mirror state write-outcome assertion is incomplete");
            }
            if (successful) {
                requireTransitionIdentity(
                        invocationSiteId, graphPath,
                        correlationKey, occurrence,
                        attempt, capabilityRef,
                        writeEffectRef, observedStateRef,
                        observedStateRevision,
                        observedWorldFingerprint,
                        observedLogicalClock,
                        requestFingerprint, outcome,
                        stateDisposition, transition);
            } else {
                requireFailureSemantics(
                        outcome, stage,
                        stateDisposition);
            }
        }

        private static WriteAttemptAssertion from(
                JsonNode value) {
            return new WriteAttemptAssertion(
                    value.path(
                            "invocationSiteId").asText(),
                    value.path("graphPath").asText(),
                    value.path(
                            "correlationKey").asText(),
                    value.path("occurrence").asInt(),
                    value.path("attempt").asInt(),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    value.path(
                                            "capabilityRef")),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    value.path(
                                            "writeEffectRef")),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    value.path(
                                            "observedStateRef")),
                    value.path(
                            "observedStateRevision").asLong(),
                    value.path(
                            "observedWorldFingerprint").asText(),
                    instant(value, "observedLogicalClock"),
                    value.path(
                            "requestFingerprint").asText(),
                    value.path("outcome").asText(),
                    value.path("stage").asText(),
                    value.path(
                            "stateDisposition").asText(),
                    value.path("retryable").asBoolean(),
                    value.path("errorCode").asText(),
                    value.path("errorType").asText(),
                    value.path(
                            "failureFingerprint").asText(),
                    value.has("transition")
                            ? MirrorStateTransitionWorkbookSeed
                            .WriteAssertion.from(
                                    value.path("transition"))
                            : null);
        }

        private String coordinate() {
            return invocationSiteId + '\0'
                    + graphPath + '\0'
                    + correlationKey + '\0'
                    + occurrence + '\0' + attempt;
        }
    }

    /** Validates counts, state-head closure, and conservative publication readiness. */
    public MirrorStateWriteOutcomeWorkbookSeed {
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
        writeAttemptAssertions =
                writeAttemptAssertions == null
                        ? List.of()
                        : List.copyOf(
                                writeAttemptAssertions);
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
                || !"MIRROR_STATE_RUN_EVIDENCE"
                .equals(stateEvidenceRef.kind())
                || stateEvidenceRef.revision() != 3
                || initialSessionStateRef == null
                || finalSessionStateRef == null
                || !"SESSION_STATE".equals(
                initialSessionStateRef.kind())
                || !"SESSION_STATE".equals(
                finalSessionStateRef.kind())
                || !initialSessionStateRef.id().equals(
                finalSessionStateRef.id())
                || stateModelRef == null
                || !"STATE_MODEL".equals(
                stateModelRef.kind())
                || initialStateRevision < 0
                || finalStateRevision
                < initialStateRevision
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
                || !"SERIALIZABLE_READ_WRITE_OUTCOMES"
                .equals(mode)
                || bindingCount < 1
                || bindingCount > MAXIMUM_BINDINGS
                || accessCount < 0
                || accessCount > MAXIMUM_INTERACTIONS
                || writeAttemptCount < 0
                || writeAttemptCount
                > MAXIMUM_INTERACTIONS
                || writeAttemptCount
                != writeAttemptAssertions.size()
                || committedCount < 0
                || replayedCount < 0
                || rejectedCount < 0
                || preCommitFailedCount < 0
                || commitOutcomeUnknownCount < 0
                || writeAttemptCount
                != (long) committedCount
                + replayedCount + rejectedCount
                + preCommitFailedCount
                + commitOutcomeUnknownCount
                || committedCount
                != count(writeAttemptAssertions,
                "COMMITTED")
                || replayedCount
                != count(writeAttemptAssertions,
                "REPLAYED")
                || rejectedCount
                != count(writeAttemptAssertions,
                "REJECTED")
                || preCommitFailedCount
                != count(writeAttemptAssertions,
                "PRE_COMMIT_FAILED")
                || commitOutcomeUnknownCount
                != count(writeAttemptAssertions,
                "COMMIT_OUTCOME_UNKNOWN")
                || eventCount < 0
                || eventCount
                != writeAttemptAssertions.stream()
                .filter(value ->
                        value.transition() != null)
                .map(WriteAttemptAssertion::transition)
                .mapToInt(value ->
                        value.events().size())
                .sum()
                || stateAdvanced
                != (finalStateRevision
                > initialStateRevision)
                || stateAdvanced
                != (committedCount > 0)
                || runStatus.isBlank()
                || evidenceClass.isBlank()
                || gateReady != blockers.isEmpty()
                || gateReady
                && (writeAttemptCount == 0
                || !"PASSED".equals(runStatus)
                || !"CERTIFIABLE".equals(
                        evidenceClass)
                || rejectedCount > 0
                || preCommitFailedCount > 0
                || commitOutcomeUnknownCount > 0)
                || rawPayload == null) {
            throw new IllegalArgumentException(
                    "Mirror state write-outcome workbook seed is incomplete");
        }
        requireOrderedAttempts(
                writeAttemptAssertions);
        requireWriteClosure(
                writeAttemptAssertions,
                initialSessionStateRef,
                finalSessionStateRef,
                initialStateRevision,
                finalStateRevision);
    }

    /**
     * Independently verifies a signed v5 evidence bundle and derives its write-outcome seed.
     *
     * @param bundle decoded portable v5 evidence bundle
     * @param key independently resolved verification key
     * @return deterministic payload-free write-outcome workbook seed
     */
    public static MirrorStateWriteOutcomeWorkbookSeed
    fromVerifiedBundle(
            JsonNode bundle,
            EvidenceVerificationKey key) {
        MirrorEvidenceVerifier.VerificationResult verified =
                new MirrorEvidenceVerifier().verify(
                        bundle, key);
        if (!verified.verified()) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_EVIDENCE_"
                            + verified.reasonCode());
        }
        if (!CapabilityMirrorProtocol
                .MIRROR_EVIDENCE_BUNDLE_V5.equals(
                        bundle.path(
                                "schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_REQUIRES_V5");
        }
        JsonNode evidence = bundle.path("evidence");
        JsonNode state =
                evidence.path("stateEvidence");
        int committed = count(
                state.path("writeAttempts"),
                "COMMITTED");
        int replayed = count(
                state.path("writeAttempts"),
                "REPLAYED");
        int rejected = count(
                state.path("writeAttempts"),
                "REJECTED");
        int preCommitFailed = count(
                state.path("writeAttempts"),
                "PRE_COMMIT_FAILED");
        int unknown = count(
                state.path("writeAttempts"),
                "COMMIT_OUTCOME_UNKNOWN");
        int events = 0;
        for (JsonNode attempt
                : state.path("writeAttempts")) {
            if (attempt.has("transition")) {
                events += attempt.path("transition")
                        .path("events").size();
            }
        }
        TreeSet<String> blockers =
                blockers(evidence, state);
        if (state.path("writeAttempts").isEmpty()) {
            blockers.add(
                    "NO_STATE_WRITE_ATTEMPT_OBSERVED");
        }
        if (rejected > 0) {
            blockers.add(
                    "STATE_WRITE_REJECTION_REQUIRES_EXPECTATION");
        }
        if (preCommitFailed > 0) {
            blockers.add(
                    "STATE_WRITE_PRE_COMMIT_FAILURE");
        }
        if (unknown > 0) {
            blockers.add(
                    "STATE_WRITE_COMMIT_OUTCOME_UNKNOWN");
        }

        ObjectNode seed = JSON.createObjectNode();
        seed.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED_V1);
        seed.put("seedFingerprint", "");
        seed.put(
                "runId",
                evidence.path("runId").asText());
        seed.put(
                "planFingerprint",
                evidence.path(
                        "planFingerprint").asText());
        seed.put(
                "evidenceBundleFingerprint",
                bundle.path(
                        "bundleFingerprint").asText());
        ObjectNode stateEvidenceRef =
                seed.putObject("stateEvidenceRef");
        stateEvidenceRef.put(
                "kind",
                "MIRROR_STATE_RUN_EVIDENCE");
        stateEvidenceRef.put(
                "id",
                evidence.path("runId").asText());
        stateEvidenceRef.put("revision", 3);
        stateEvidenceRef.put(
                "fingerprint",
                state.path(
                        "stateEvidenceFingerprint").asText());
        seed.set(
                "initialSessionStateRef",
                state.path(
                        "sessionStateRef").deepCopy());
        seed.set(
                "finalSessionStateRef",
                state.path(
                        "finalSessionStateRef").deepCopy());
        seed.set(
                "stateModelRef",
                state.path(
                        "stateModelRef").deepCopy());
        seed.put(
                "initialStateRevision",
                state.path("stateRevision").asLong());
        seed.put(
                "finalStateRevision",
                state.path(
                        "finalStateRevision").asLong());
        seed.put(
                "initialWorldFingerprint",
                state.path(
                        "worldFingerprint").asText());
        seed.put(
                "finalWorldFingerprint",
                state.path(
                        "finalWorldFingerprint").asText());
        seed.put(
                "initialLogicalClock",
                state.path("logicalClock").asText());
        seed.put(
                "finalLogicalClock",
                state.path(
                        "finalLogicalClock").asText());
        seed.put("mode", state.path("mode").asText());
        seed.put(
                "runStatus",
                evidence.path("status").asText());
        seed.put(
                "evidenceClass",
                evidence.path(
                        "evidenceClass").asText());
        seed.put(
                "bindingCount",
                state.path(
                        "statefulBindings").size());
        seed.put(
                "accessCount",
                state.path("accesses").size());
        seed.put(
                "writeAttemptCount",
                state.path("writeAttempts").size());
        seed.put("committedCount", committed);
        seed.put("replayedCount", replayed);
        seed.put("rejectedCount", rejected);
        seed.put(
                "preCommitFailedCount",
                preCommitFailed);
        seed.put(
                "commitOutcomeUnknownCount",
                unknown);
        seed.put("eventCount", events);
        seed.put(
                "stateAdvanced",
                state.path(
                        "finalStateRevision").asLong()
                        > state.path(
                        "stateRevision").asLong());
        ArrayNode assertions =
                seed.putArray(
                        "writeAttemptAssertions");
        state.path("writeAttempts").forEach(
                value -> assertions.add(
                        value.deepCopy()));
        seed.put(
                "gateReady", blockers.isEmpty());
        ArrayNode blockerValues =
                seed.putArray("blockers");
        blockers.forEach(blockerValues::add);
        seed.put(
                "seedFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                seed,
                                MAXIMUM_SEED_BYTES));
        return fromPayload(seed);
    }

    /**
     * Validates and decodes one producer or locally projected write-outcome seed.
     *
     * @param payload decoded write-outcome workbook seed
     * @return typed defensive seed
     */
    public static MirrorStateWriteOutcomeWorkbookSeed
    fromPayload(JsonNode payload) {
        CapabilityMirrorSchemaValidator.require(
                payload,
                CapabilityMirrorProtocol
                        .MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.STATE_WRITE_OUTCOME_WORKBOOK_SCHEMA_INVALID");
        ObjectNode material =
                ((ObjectNode) payload).deepCopy();
        String attached = material.path(
                "seedFingerprint").asText();
        material.put("seedFingerprint", "");
        if (!EvidenceVerificationSupport
                .sha256Bounded(
                        material, MAXIMUM_SEED_BYTES)
                .equals(attached)) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_FINGERPRINT_INVALID");
        }
        TreeSet<String> blockers =
                new TreeSet<>();
        payload.path("blockers").forEach(
                value -> blockers.add(value.asText()));
        try {
            return new MirrorStateWriteOutcomeWorkbookSeed(
                    attached,
                    payload.path("runId").asText(),
                    payload.path(
                            "planFingerprint").asText(),
                    payload.path(
                            "evidenceBundleFingerprint").asText(),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    payload.path(
                                            "stateEvidenceRef")),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    payload.path(
                                            "initialSessionStateRef")),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    payload.path(
                                            "finalSessionStateRef")),
                    MirrorStateTransitionWorkbookSeed
                            .ArtifactRef.from(
                                    payload.path(
                                            "stateModelRef")),
                    payload.path(
                            "initialStateRevision").asLong(),
                    payload.path(
                            "finalStateRevision").asLong(),
                    payload.path(
                            "initialWorldFingerprint").asText(),
                    payload.path(
                            "finalWorldFingerprint").asText(),
                    instant(
                            payload,
                            "initialLogicalClock"),
                    instant(
                            payload,
                            "finalLogicalClock"),
                    payload.path("mode").asText(),
                    payload.path(
                            "runStatus").asText(),
                    payload.path(
                            "evidenceClass").asText(),
                    payload.path(
                            "bindingCount").asInt(),
                    payload.path(
                            "accessCount").asInt(),
                    payload.path(
                            "writeAttemptCount").asInt(),
                    payload.path(
                            "committedCount").asInt(),
                    payload.path(
                            "replayedCount").asInt(),
                    payload.path(
                            "rejectedCount").asInt(),
                    payload.path(
                            "preCommitFailedCount").asInt(),
                    payload.path(
                            "commitOutcomeUnknownCount").asInt(),
                    payload.path(
                            "eventCount").asInt(),
                    payload.path(
                            "stateAdvanced").asBoolean(),
                    stream(payload.path(
                            "writeAttemptAssertions"))
                            .map(WriteAttemptAssertion::from)
                            .toList(),
                    payload.path(
                            "gateReady").asBoolean(),
                    List.copyOf(blockers), payload);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(
                    "MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_TIME_INVALID");
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
                    "MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_NOT_GATE_READY:"
                            + String.join(",", blockers));
        }
    }

    /**
     * Returns the complete protocol payload for workbook ingestion.
     *
     * @return defensive complete write-outcome seed payload
     */
    public JsonNode rawPayload() {
        return rawPayload.deepCopy();
    }

    private static void requireTransitionIdentity(
            String site,
            String graphPath,
            String correlation,
            int occurrence,
            int attempt,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    capability,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    effect,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    observedState,
            long observedRevision,
            String observedWorld,
            Instant observedClock,
            String request,
            String outcome,
            String disposition,
            MirrorStateTransitionWorkbookSeed.WriteAssertion
                    transition) {
        boolean committed = "COMMITTED".equals(outcome);
        if (!site.equals(
                transition.invocationSiteId())
                || !graphPath.equals(
                transition.graphPath())
                || !correlation.equals(
                transition.correlationKey())
                || occurrence != transition.occurrence()
                || attempt != transition.attempt()
                || !capability.equals(
                transition.capabilityRef())
                || !effect.equals(
                transition.writeEffectRef())
                || !observedState.equals(
                transition.initialStateRef())
                || observedRevision
                != transition.revisionBefore()
                || !observedWorld.equals(
                transition.initialWorldFingerprint())
                || !observedClock.equals(
                transition.initialLogicalClock())
                || !request.equals(
                transition.requestFingerprint())
                || committed
                == transition.replayed()
                || committed
                && !"ADVANCED".equals(disposition)
                || !committed
                && !"UNCHANGED".equals(disposition)) {
            throw new IllegalArgumentException(
                    "Mirror state write-outcome transition identity is inconsistent");
        }
    }

    private static void requireFailureSemantics(
            String outcome,
            String stage,
            String disposition) {
        boolean valid = switch (outcome) {
            case "REJECTED" ->
                    "UNCHANGED".equals(disposition)
                            && List.of(
                            "RESOLVER_ADMISSION",
                            "COMMAND_ADMISSION",
                            "COMMAND_EVALUATION")
                            .contains(stage);
            case "PRE_COMMIT_FAILED" ->
                    "UNCHANGED".equals(disposition)
                            && List.of(
                            "COMMAND_ADMISSION",
                            "COMMAND_EVALUATION",
                            "COMMIT").contains(stage);
            case "COMMIT_OUTCOME_UNKNOWN" ->
                    "UNKNOWN".equals(disposition)
                            && List.of(
                            "COMMIT",
                            "RESULT_VERIFICATION",
                            "PROCESS_INTERRUPTION")
                            .contains(stage);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Mirror state write-outcome failure semantics are inconsistent");
        }
    }

    private static void requireOrderedAttempts(
            List<WriteAttemptAssertion> attempts) {
        HashSet<String> coordinates = new HashSet<>();
        WriteAttemptAssertion previous = null;
        for (WriteAttemptAssertion attempt : attempts) {
            if (attempt == null
                    || !coordinates.add(
                    attempt.coordinate())
                    || previous != null
                    && ATTEMPT_ORDER.compare(
                    previous, attempt) > 0) {
                throw new IllegalArgumentException(
                        "Mirror state write-outcome attempts are unordered or duplicated");
            }
            previous = attempt;
        }
    }

    private static void requireWriteClosure(
            List<WriteAttemptAssertion> attempts,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    initial,
            MirrorStateTransitionWorkbookSeed.ArtifactRef
                    terminal,
            long initialRevision,
            long finalRevision) {
        List<MirrorStateTransitionWorkbookSeed
                .WriteAssertion> committed =
                attempts.stream()
                        .filter(value ->
                                "COMMITTED".equals(
                                        value.outcome()))
                        .map(WriteAttemptAssertion::transition)
                        .sorted(Comparator.comparingLong(
                                MirrorStateTransitionWorkbookSeed
                                        .WriteAssertion
                                        ::revisionAfter))
                        .toList();
        long expectedRevision = initialRevision;
        MirrorStateTransitionWorkbookSeed.ArtifactRef
                expectedHead = initial;
        HashSet<MirrorStateTransitionWorkbookSeed
                .ArtifactRef> knownHeads =
                new HashSet<>();
        knownHeads.add(initial);
        for (MirrorStateTransitionWorkbookSeed
                .WriteAssertion transition : committed) {
            if (transition.revisionBefore()
                    != expectedRevision
                    || !transition.initialStateRef()
                    .equals(expectedHead)) {
                throw new IllegalArgumentException(
                        "Mirror state write-outcome commit chain is invalid");
            }
            expectedRevision =
                    transition.revisionAfter();
            expectedHead = transition.finalStateRef();
            knownHeads.add(expectedHead);
        }
        if (expectedRevision != finalRevision
                || !expectedHead.equals(terminal)
                || attempts.stream().anyMatch(
                value -> !initial.id().equals(
                        value.observedStateRef().id())
                        || !knownHeads.contains(
                        value.observedStateRef()))) {
            throw new IllegalArgumentException(
                    "Mirror state write-outcome final head is invalid");
        }
    }

    private static TreeSet<String> blockers(
            JsonNode evidence, JsonNode state) {
        TreeSet<String> result = new TreeSet<>();
        if (!"PASSED".equals(
                evidence.path("status").asText())) {
            result.add("RUN_NOT_PASSED");
        }
        if (!"CERTIFIABLE".equals(
                evidence.path(
                        "evidenceClass").asText())) {
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

    private static int count(
            List<WriteAttemptAssertion> attempts,
            String outcome) {
        return Math.toIntExact(
                attempts.stream()
                        .filter(value -> outcome.equals(
                                value.outcome()))
                        .count());
    }

    private static int count(
            JsonNode attempts,
            String outcome) {
        int result = 0;
        for (JsonNode attempt : attempts) {
            if (outcome.equals(
                    attempt.path("outcome").asText())) {
                result++;
            }
        }
        return result;
    }

    private static List<String> orderedBlockers(
            List<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalize(value);
                if (normalized.isBlank()
                        || !normalized.matches(
                        "[A-Z][A-Z0-9_.-]{0,255}")
                        || !result.add(normalized)) {
                    throw new IllegalArgumentException(
                            "Mirror state write-outcome blockers are invalid");
                }
            }
        }
        if (result.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "Mirror state write-outcome blockers exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static java.util.stream.Stream<JsonNode>
    stream(JsonNode values) {
        return java.util.stream.StreamSupport.stream(
                values.spliterator(), false);
    }

    private static Instant instant(
            JsonNode value, String field) {
        return Instant.parse(
                value.path(field).asText());
    }

    private static boolean fingerprints(
            String... values) {
        for (String value : values) {
            if (!isFingerprint(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFingerprint(
            String value) {
        return value != null
                && value.matches(
                "sha256:[a-f0-9]{64}");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
