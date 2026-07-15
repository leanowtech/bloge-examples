package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Run-scoped, payload-frozen replay dependencies resolved before execution-control compilation.
 *
 * <p>Only this internal object carries canonical replay JSON. Public plans and evidence receive
 * payload-free dependency projections. The runtime therefore never rereads replay storage and
 * cannot silently fall back to the real operator when a governed value expires mid-run.</p>
 *
 * @param byReference exact canonical reference to frozen payload map
 */
public record ResolvedReplayPayloads(Map<String, Payload> byReference) {

    /** Freezes payloads in canonical reference order. */
    public ResolvedReplayPayloads {
        Map<String, Payload> ordered = new TreeMap<>();
        if (byReference != null) {
            byReference.forEach((reference, payload) -> {
                String normalized = normalized(reference);
                Payload safe = Objects.requireNonNull(payload, "replay payload");
                if (!normalized.equals(safe.canonicalRef())) {
                    throw new IllegalArgumentException("Replay payload map key must equal its canonicalRef.");
                }
                if (ordered.putIfAbsent(normalized, safe) != null) {
                    throw new IllegalArgumentException("Duplicate replay payload reference: " + normalized);
                }
            });
        }
        byReference = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    /**
     * Creates the neutral replay dependency set used by fixtures without REPLAY rules.
     *
     * @return an empty replay dependency set
     */
    public static ResolvedReplayPayloads empty() {
        return new ResolvedReplayPayloads(Map.of());
    }

    /**
     * Lists the exact content-addressed references frozen into this run.
     *
     * @return exact resolved references
     */
    public Set<String> references() {
        return byReference.keySet();
    }

    /**
     * Resolves one payload already frozen into this run.
     *
     * @param canonicalRef exact fixture behavior reference
     * @return frozen payload
     * @throws IllegalArgumentException when preflight failed to freeze the dependency
     */
    public Payload require(String canonicalRef) {
        Payload payload = byReference.get(normalized(canonicalRef));
        if (payload == null) {
            throw new IllegalArgumentException("Replay payload was not frozen into the execution plan.");
        }
        return payload;
    }

    /**
     * Projects replay dependencies into the payload-free public plan representation.
     *
     * @return payload-free dependency projections included in plan identity
     */
    public List<EffectiveExecutionPlan.ReplayDependency> planDependencies() {
        return byReference.values().stream().map(Payload::planDependency).toList();
    }

    /**
     * Determines whether every replay source may contribute certifiable evidence.
     *
     * @return whether every replay source is certification eligible
     */
    public boolean certificationEligible() {
        return byReference.values().stream().allMatch(Payload::certificationEligible);
    }

    /**
     * Collects bounded, payload-free certification gaps from all dependencies.
     *
     * @return sorted certification gaps prefixed by exact replay reference
     */
    public List<String> certificationGaps() {
        return byReference.values().stream().flatMap(payload -> payload.certificationGaps().stream()
                        .map(gap -> payload.canonicalRef() + ":" + gap))
                .distinct().sorted().toList();
    }

    /**
     * One immutable replay value. Canonical JSON is intentionally absent from its string form.
     *
     * @param canonicalRef exact content-addressed replay reference
     * @param classification governed data classification
     * @param canonicalJson frozen JSON representation used only by the runtime
     * @param sourceRunId signed source run id
     * @param sourceNodeId exact source node id
     * @param sourceAttempt exact source attempt
     * @param sourceRunFingerprint signed run material fingerprint
     * @param sourcePayloadFingerprint detached source payload fingerprint
     * @param expiresAt source-capped replay expiry
     * @param certificationEligible whether the source is eligible for certifiable evidence
     * @param certificationGaps bounded reasons preventing certification
     */
    public record Payload(
            String canonicalRef,
            String classification,
            String canonicalJson,
            String sourceRunId,
            String sourceNodeId,
            int sourceAttempt,
            String sourceRunFingerprint,
            String sourcePayloadFingerprint,
            Instant expiresAt,
            boolean certificationEligible,
            List<String> certificationGaps
    ) {
        /** Validates exact identity and freezes payload-free provenance. */
        public Payload {
            canonicalRef = normalized(canonicalRef);
            ReplayPayloadRef.parse(canonicalRef);
            classification = normalized(classification).toUpperCase(java.util.Locale.ROOT);
            canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
            sourceRunId = normalized(sourceRunId);
            sourceNodeId = normalized(sourceNodeId);
            if (sourceAttempt <= 0) {
                throw new IllegalArgumentException("sourceAttempt must be positive");
            }
            sourceRunFingerprint = normalized(sourceRunFingerprint);
            sourcePayloadFingerprint = normalized(sourcePayloadFingerprint);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            certificationGaps = certificationGaps == null ? List.of()
                    : List.copyOf(new ArrayList<>(certificationGaps));
        }

        /**
         * Materializes a fresh value so one invocation cannot mutate another invocation's replay.
         *
         * @param objectMapper application protocol mapper
         * @param operatorRef frozen operator binding reference
         * @return fresh JSON-shaped value, or the platform HTTP output type where required
         */
        public Object materialize(ObjectMapper objectMapper, String operatorRef) {
            Objects.requireNonNull(objectMapper, "objectMapper");
            try {
                if ("httpResource".equals(operatorRef)) {
                    return objectMapper.readValue(canonicalJson, HttpResourceOutput.class);
                }
                return objectMapper.readValue(canonicalJson, Object.class);
            } catch (JsonProcessingException failure) {
                throw new TestControlException("REPLAY_PAYLOAD_MATERIALIZATION_FAILED",
                        "REPLAY", "Frozen replay payload cannot be materialized for the target binding.");
            }
        }

        /**
         * Projects this frozen value into its payload-free public plan representation.
         *
         * @return payload-free plan dependency projection
         */
        public EffectiveExecutionPlan.ReplayDependency planDependency() {
            ReplayPayloadRef ref = ReplayPayloadRef.parse(canonicalRef);
            return new EffectiveExecutionPlan.ReplayDependency(canonicalRef,
                    ref.replayPayloadId(), ref.revision(), ref.fingerprint(), classification,
                    sourceRunId, sourceNodeId, sourceAttempt, sourceRunFingerprint,
                    sourcePayloadFingerprint, expiresAt, certificationEligible, certificationGaps);
        }

        /** Prevents canonical payload JSON from entering logs or exception diagnostics. */
        @Override
        public String toString() {
            return "Payload[canonicalRef=" + canonicalRef + ", classification=" + classification
                    + ", certificationEligible=" + certificationEligible + "]";
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
