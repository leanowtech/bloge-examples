package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dependency-light independent verifier for one authoritative outcome observation.
 *
 * <p>The verifier links no Resource Gateway server or Spring class. It validates the packaged
 * strict Schema, independently derives pending/censored/match/mismatch/conflict state from the
 * complete watermark and fact closure, checks pre-treatment cohort selection and attribution
 * identity, enforces the signed attestation time, and recomputes the content address. It verifies
 * the cheap local Resource Gateway Ed25519 seal before invoking the caller's external
 * business-authority closure, so invalid artifacts cannot amplify customer-ledger traffic. Both
 * trust boundaries must pass.</p>
 */
public final class AuthoritativeOutcomeObservationVerifier {
    /** Maximum canonical observation bytes admitted to hashing. */
    public static final int MAXIMUM_OBSERVATION_BYTES =
            4 * 1024 * 1024;
    /** Maximum domain-separated attestation material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    /** Creates a dependency-light verifier using packaged Schemas and caller-supplied trust. */
    public AuthoritativeOutcomeObservationVerifier() {
    }

    /** Bounded verification outcome. */
    public enum Outcome {
        /** Schema, derivation, authorities, content address, and RG signature all passed. */
        VERIFIED,
        /** Structure, semantic closure, content address, or signature is invalid. */
        INVALID,
        /** The business authority-set verifier was not supplied. */
        AUTHORITY_UNAVAILABLE,
        /** The exact Resource Gateway verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or key lifecycle policy rejects the observation. */
        POLICY_REJECTED
    }

    /**
     * External verifier for the exact outcome authority-set, watermarks, and source records.
     *
     * <p>The callback must resolve and authenticate every referenced authority artifact through
     * its own governed trust channel. Returning true means only that external closure passed; this
     * verifier still independently checks protocol semantics and the Resource Gateway seal.</p>
     */
    @FunctionalInterface
    public interface AuthorityClosureVerifier {
        /**
         * Verifies one structurally and semantically valid observation.
         *
         * @param observation defensive copy after Schema, semantics, address, and RG seal verify
         * @return true only when every external authority coordinate is trusted
         */
        boolean verify(JsonNode observation);
    }

    /**
     * Payload-free result safe for CI and governance logs.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param observationId observation identity, or blank when unavailable
     * @param observationFingerprint content address, or blank when unavailable
     * @param unitId owner inventory unit, or blank when unavailable
     * @param reconciliation derived reconciliation, or blank when unavailable
     * @param keyId Resource Gateway verification key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String observationId,
            String observationFingerprint,
            String unitId,
            String reconciliation,
            String keyId
    ) {
        /** Normalizes one bounded log-safe result. */
        public VerificationResult {
            reasonCode = normalized(reasonCode, 255);
            observationId = normalized(
                    observationId, 512);
            observationFingerprint = normalized(
                    observationFingerprint, 128);
            unitId = normalized(unitId, 512);
            reconciliation = normalized(
                    reconciliation, 32);
            keyId = normalized(keyId, 255);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Outcome observation verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only when every independent verification step passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded observation and both trust boundaries.
     *
     * @param observation decoded observation
     * @param key Resource Gateway public key resolved by {@code observationSeal.keyId}
     * @param authorityVerifier external business-authority verifier; {@code null} is unavailable
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode observation,
            EvidenceVerificationKey key,
            AuthorityClosureVerifier authorityVerifier) {
        return verify(
                observation,
                key,
                authorityVerifier,
                Instant.now());
    }

    /**
     * Independently verifies one decoded observation at a caller-owned trusted time.
     *
     * @param observation decoded observation
     * @param key Resource Gateway public key resolved by {@code observationSeal.keyId}
     * @param authorityVerifier external business-authority verifier; {@code null} is unavailable
     * @param verificationTime trusted consumer verification time
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode observation,
            EvidenceVerificationKey key,
            AuthorityClosureVerifier authorityVerifier,
            Instant verificationTime) {
        Coordinates coordinates =
                Coordinates.from(observation);
        if (verificationTime == null) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "OUTCOME_OBSERVATION_VERIFICATION_TIME_INVALID",
                    coordinates);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    observation,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_OBSERVATION_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_OBSERVATION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
                    coordinates);
        }
        ObservationTimes times;
        try {
            times = verifySemantics(
                    observation);
            verifyFingerprint(observation);
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_OBSERVATION_CLOSURE_INVALID",
                    coordinates);
        }
        JsonNode seal = observation.path(
                "observationSeal");
        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "OUTCOME_OBSERVATION_VERIFICATION_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!key.keyId().equals(
                text(seal, "keyId"))) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_OBSERVATION_KEY_ID_MISMATCH",
                    coordinates);
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                text(seal, "algorithm"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "OUTCOME_OBSERVATION_SIGNATURE_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = instant(
                    seal.path("signedAt"),
                    "OUTCOME_OBSERVATION_SEAL_TIME_INVALID");
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates);
        }
        if (!key.verificationAllowed()
                || times.attestedAt().isBefore(
                key.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))
                || times.attestedAt().isAfter(
                verificationTime.plus(
                        MAXIMUM_CLOCK_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "OUTCOME_OBSERVATION_KEY_POLICY_REJECTED",
                    coordinates);
        }
        if (signedAt.isBefore(
                times.attestedAt().minus(
                        MAXIMUM_CLOCK_SKEW))
                || signedAt.isAfter(
                times.attestedAt().plus(
                        MAXIMUM_CLOCK_SKEW))) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_OBSERVATION_SEAL_TIME_INVALID",
                    coordinates);
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            attestationMaterial(observation),
                            MAXIMUM_ATTESTATION_BYTES);
            if (!materialFingerprint.equals(
                    text(seal, "materialFingerprint"))) {
                return result(
                        Outcome.INVALID,
                        "OUTCOME_OBSERVATION_ATTESTATION_MATERIAL_INVALID",
                        coordinates);
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint,
                    text(seal, "signature"),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "OUTCOME_OBSERVATION_SIGNATURE_INVALID",
                        coordinates);
            }
        } catch (GeneralSecurityException
                 | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
        if (authorityVerifier == null) {
            return result(
                    Outcome.AUTHORITY_UNAVAILABLE,
                    "OUTCOME_AUTHORITY_VERIFIER_UNAVAILABLE",
                    coordinates);
        }
        try {
            if (!authorityVerifier.verify(
                    observation.deepCopy())) {
                return result(
                        Outcome.INVALID,
                        "OUTCOME_AUTHORITY_CLOSURE_REJECTED",
                        coordinates);
            }
        } catch (RuntimeException rejected) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_AUTHORITY_CLOSURE_REJECTED",
                    coordinates);
        }
        return result(
                Outcome.VERIFIED,
                "VERIFIED",
                coordinates);
    }

    private static ObservationTimes verifySemantics(
            JsonNode observation) {
        JsonNode selection = observation.path(
                "selectionProof");
        JsonNode window = observation.path(
                "attributionWindow");
        Instant actionAt = instant(
                window.path("actionOccurredAt"),
                "OUTCOME_ATTRIBUTION_TIME_INVALID");
        Instant opensAt = instant(
                window.path("opensAt"),
                "OUTCOME_ATTRIBUTION_TIME_INVALID");
        Instant closesAt = instant(
                window.path("closesAt"),
                "OUTCOME_ATTRIBUTION_TIME_INVALID");
        Instant selectedAt = instant(
                selection.path("selectedAt"),
                "OUTCOME_COHORT_SELECTION_INVALID");
        Instant reconciledAt = instant(
                observation.path("reconciledAt"),
                "OUTCOME_RECONCILIATION_TIME_INVALID");
        Instant attestedAt = instant(
                observation.path("attestedAt"),
                "OUTCOME_ATTESTATION_TIME_INVALID");
        long eligible = selection.path(
                "eligiblePopulationSize").asLong();
        long selected = selection.path(
                "selectedPopulationSize").asLong();
        long ordinal = selection.path(
                "sampleOrdinal").asLong();
        if (!selectedAt.isBefore(actionAt)
                || opensAt.isBefore(actionAt)
                || !closesAt.isAfter(opensAt)
                || Duration.between(
                opensAt, closesAt)
                .compareTo(Duration.ofDays(365)) > 0
                || reconciledAt.isBefore(actionAt)
                || attestedAt.isBefore(reconciledAt)
                || eligible < 1
                || selected < 1
                || selected > eligible
                || ordinal < 1
                || ordinal > selected
                || "CENSUS".equals(
                text(selection, "selectionMode"))
                && selected != eligible) {
            fail("OUTCOME_COHORT_SELECTION_INVALID");
        }
        WatermarkClosure watermarks =
                verifyWatermarks(
                        observation.path(
                                "authorityWatermarks"),
                        reconciledAt);
        FactClosure facts = verifyFacts(
                observation,
                watermarks.authorityIds,
                opensAt,
                closesAt,
                reconciledAt);
        String derived;
        if (watermarks.minimumEventTime
                .isBefore(closesAt)) {
            derived = "PENDING";
        } else if (facts.outcomes.isEmpty()) {
            derived = "CENSORED";
        } else if (facts.outcomes.size() > 1) {
            derived = "CONFLICT";
        } else if (facts.outcomes.contains(
                text(
                        observation,
                        "modelOutcomeFingerprint"))) {
            derived = "MATCH";
        } else {
            derived = "MISMATCH";
        }
        if (!derived.equals(
                text(observation, "reconciliation"))) {
            fail("OUTCOME_RECONCILIATION_DERIVATION_INVALID");
        }
        if (observation.path(
                "evidenceComplete").asBoolean()
                != facts.complete) {
            fail("OUTCOME_EVIDENCE_COMPLETENESS_INVALID");
        }
        return new ObservationTimes(
                reconciledAt, attestedAt);
    }

    private static WatermarkClosure verifyWatermarks(
            JsonNode values,
            Instant reconciledAt) {
        Set<String> authorities = new HashSet<>();
        Set<String> refs = new HashSet<>();
        String previous = "";
        Instant minimum = null;
        for (JsonNode watermark : values) {
            String authority = text(
                    watermark, "authorityId");
            Instant through = instant(
                    watermark.path("eventTimeThrough"),
                    "OUTCOME_AUTHORITY_WATERMARK_INVALID");
            Instant published = instant(
                    watermark.path("publishedAt"),
                    "OUTCOME_AUTHORITY_WATERMARK_INVALID");
            String ref = artifactIdentity(
                    watermark.path("watermarkRef"));
            if (!authorities.add(authority)
                    || !refs.add(ref)
                    || authority.compareTo(previous) <= 0
                    || through.isAfter(published)
                    || published.isAfter(reconciledAt)) {
                fail("OUTCOME_AUTHORITY_WATERMARK_INVALID");
            }
            previous = authority;
            minimum = minimum == null
                    || through.isBefore(minimum)
                    ? through : minimum;
        }
        if (minimum == null) {
            fail("OUTCOME_AUTHORITY_WATERMARK_INVALID");
        }
        return new WatermarkClosure(
                Set.copyOf(authorities),
                minimum);
    }

    private static FactClosure verifyFacts(
            JsonNode observation,
            Set<String> authorities,
            Instant opensAt,
            Instant closesAt,
            Instant reconciledAt) {
        Set<String> refs = new HashSet<>();
        Set<String> outcomes = new HashSet<>();
        FactOrder previous = null;
        boolean complete = true;
        String subject = text(
                observation, "subjectFingerprint");
        String attribution = text(
                observation,
                "attributionKeyFingerprint");
        for (JsonNode fact : observation.path(
                "authorityFacts")) {
            String authority = text(
                    fact, "authorityId");
            Instant occurred = instant(
                    fact.path("occurredAt"),
                    "OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
            Instant recorded = instant(
                    fact.path("recordedAt"),
                    "OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
            String sourceRef = artifactIdentity(
                    fact.path("sourceRef"));
            FactOrder order = new FactOrder(
                    authority,
                    occurred,
                    text(
                            fact.path("sourceRef"),
                            "fingerprint"));
            if (!authorities.contains(authority)
                    || !refs.add(sourceRef)
                    || previous != null
                    && previous.compareTo(order) >= 0
                    || !subject.equals(
                    text(
                            fact,
                            "subjectFingerprint"))
                    || !attribution.equals(
                    text(
                            fact,
                            "attributionKeyFingerprint"))
                    || occurred.isBefore(opensAt)
                    || occurred.isAfter(closesAt)
                    || recorded.isBefore(occurred)
                    || recorded.isAfter(reconciledAt)) {
                fail("OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
            }
            previous = order;
            outcomes.add(
                    text(
                            fact,
                            "outcomeFingerprint"));
            complete &= fact.path(
                    "evidenceComplete").asBoolean();
        }
        return new FactClosure(
                Set.copyOf(outcomes),
                complete);
    }

    private static void verifyFingerprint(
            JsonNode observation) {
        if (!text(
                observation,
                "observationFingerprint").equals(
                EvidenceVerificationSupport
                        .sha256Bounded(
                                producerFingerprintMaterial(
                                        observation),
                                MAXIMUM_OBSERVATION_BYTES))) {
            fail("OUTCOME_OBSERVATION_FINGERPRINT_INVALID");
        }
    }

    /**
     * Rebuilds the producer's exact canonical content-address material.
     *
     * @param observation decoded observation
     * @return complete material with address and seal blanked before canonical sorting
     */
    static ObjectNode producerFingerprintMaterial(
            JsonNode observation) {
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        copy(
                material,
                observation,
                "schemaVersion",
                "observationId",
                "revision");
        material.put("observationFingerprint", "");
        material.set(
                "scope",
                ordered(
                        observation.path("scope"),
                        "tenantId",
                        "organizationId",
                        "projectId",
                        "environmentId",
                        "region"));
        material.set(
                "inventoryRef",
                artifactRef(
                        observation.path(
                                "inventoryRef")));
        copy(material, observation, "unitId");
        for (String field : List.of(
                "scenarioCaseRef",
                "targetCapabilityRef",
                "outcomeDefinitionRef",
                "attributionPolicyRef",
                "authoritySetRef")) {
            material.set(
                    field,
                    artifactRef(
                            observation.path(field)));
        }
        JsonNode selection = observation.path(
                "selectionProof");
        ObjectNode orderedSelection =
                JsonNodeFactory.instance.objectNode();
        orderedSelection.set(
                "cohortRef",
                artifactRef(
                        selection.path("cohortRef")));
        orderedSelection.set(
                "samplingFrameRef",
                artifactRef(
                        selection.path(
                                "samplingFrameRef")));
        copy(
                orderedSelection,
                selection,
                "stratumId",
                "inclusionFingerprint",
                "selectedAt",
                "eligiblePopulationSize",
                "selectedPopulationSize",
                "sampleOrdinal",
                "selectionMode");
        material.set(
                "selectionProof",
                orderedSelection);
        copy(
                material,
                observation,
                "subjectFingerprint",
                "attributionKeyFingerprint",
                "modelOutcomeFingerprint");
        material.set(
                "attributionWindow",
                ordered(
                        observation.path(
                                "attributionWindow"),
                        "actionOccurredAt",
                        "opensAt",
                        "closesAt"));
        copy(
                material,
                observation,
                "reconciledAt",
                "attestedAt");
        ArrayNode watermarks =
                material.putArray(
                        "authorityWatermarks");
        observation.path("authorityWatermarks")
                .forEach(value -> {
                    ObjectNode ordered =
                            JsonNodeFactory.instance
                                    .objectNode();
                    copy(
                            ordered,
                            value,
                            "authorityId");
                    ordered.set(
                            "watermarkRef",
                            artifactRef(
                                    value.path(
                                            "watermarkRef")));
                    copy(
                            ordered,
                            value,
                            "eventTimeThrough",
                            "publishedAt");
                    watermarks.add(ordered);
                });
        ArrayNode facts =
                material.putArray("authorityFacts");
        observation.path("authorityFacts")
                .forEach(value -> {
                    ObjectNode ordered =
                            JsonNodeFactory.instance
                                    .objectNode();
                    copy(
                            ordered,
                            value,
                            "authorityId");
                    ordered.set(
                            "sourceRef",
                            artifactRef(
                                    value.path(
                                            "sourceRef")));
                    copy(
                            ordered,
                            value,
                            "subjectFingerprint",
                            "attributionKeyFingerprint",
                            "outcomeFingerprint",
                            "occurredAt",
                            "recordedAt",
                            "evidenceComplete");
                    facts.add(ordered);
                });
        copy(
                material,
                observation,
                "reconciliation",
                "evidenceComplete");
        material.set(
                "observationSeal",
                unsignedSeal());
        return material;
    }

    /**
     * Rebuilds the producer's domain-separated signing material.
     *
     * @param observation decoded content-addressed observation
     * @return exact attestation material before canonical sorting
     */
    static ObjectNode attestationMaterial(
            JsonNode observation) {
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_OBSERVATION_V1");
        copy(
                material,
                observation,
                "schemaVersion",
                "observationId",
                "revision");
        material.set(
                "inventoryRef",
                artifactRef(
                        observation.path(
                                "inventoryRef")));
        copy(
                material,
                observation,
                "unitId",
                "reconciledAt",
                "attestedAt",
                "observationFingerprint");
        return material;
    }

    private static ObjectNode artifactRef(
            JsonNode source) {
        return ordered(
                source,
                "kind",
                "id",
                "revision",
                "fingerprint");
    }

    private static String artifactIdentity(
            JsonNode source) {
        return text(source, "kind") + "\u0000"
                + text(source, "id") + "\u0000"
                + source.path("revision").asLong()
                + "\u0000"
                + text(source, "fingerprint");
    }

    private static ObjectNode ordered(
            JsonNode source,
            String... fields) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        copy(value, source, fields);
        return value;
    }

    private static void copy(
            ObjectNode target,
            JsonNode source,
            String... fields) {
        for (String field : fields) {
            target.set(
                    field,
                    source.path(field)
                            .deepCopy());
        }
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode seal =
                JsonNodeFactory.instance.objectNode();
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", "");
        seal.put("algorithm", "");
        seal.put("keyId", "");
        seal.put(
                "signedAt",
                Instant.EPOCH.toString());
        seal.put("signature", "");
        return seal;
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.observationId,
                coordinates.observationFingerprint,
                coordinates.unitId,
                coordinates.reconciliation,
                coordinates.keyId);
    }

    private static String text(
            JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static Instant instant(
            JsonNode value,
            String reasonCode) {
        try {
            if (value == null || !value.isTextual()) {
                fail(reasonCode);
            }
            String encoded = value.textValue();
            Instant exact = Instant.parse(encoded);
            if (!exact.toString().equals(encoded)) {
                fail(reasonCode);
            }
            return exact;
        } catch (DateTimeParseException invalid) {
            fail(reasonCode);
            throw new IllegalStateException(
                    "unreachable");
        }
    }

    private static String normalized(
            String value, int maximum) {
        String result = value == null
                ? "" : value.trim();
        result = result.replaceAll(
                "\\p{Cntrl}", "?");
        return result.length() <= maximum
                ? result
                : result.substring(0, maximum);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private record WatermarkClosure(
            Set<String> authorityIds,
            Instant minimumEventTime
    ) {
    }

    private record FactClosure(
            Set<String> outcomes,
            boolean complete
    ) {
    }

    private record ObservationTimes(
            Instant reconciledAt,
            Instant attestedAt
    ) {
    }

    private record FactOrder(
            String authorityId,
            Instant occurredAt,
            String sourceFingerprint
    ) implements Comparable<FactOrder> {
        @Override
        public int compareTo(FactOrder other) {
            int authority = authorityId.compareTo(
                    other.authorityId);
            if (authority != 0) {
                return authority;
            }
            int occurred = occurredAt.compareTo(
                    other.occurredAt);
            return occurred != 0
                    ? occurred
                    : sourceFingerprint.compareTo(
                    other.sourceFingerprint);
        }
    }

    private record Coordinates(
            String observationId,
            String observationFingerprint,
            String unitId,
            String reconciliation,
            String keyId
    ) {
        private static Coordinates from(
                JsonNode observation) {
            JsonNode source = observation == null
                    ? JsonNodeFactory.instance
                    .objectNode()
                    : observation;
            return new Coordinates(
                    text(source, "observationId"),
                    text(
                            source,
                            "observationFingerprint"),
                    text(source, "unitId"),
                    text(source, "reconciliation"),
                    text(
                            source.path(
                                    "observationSeal"),
                            "keyId"));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }
}
