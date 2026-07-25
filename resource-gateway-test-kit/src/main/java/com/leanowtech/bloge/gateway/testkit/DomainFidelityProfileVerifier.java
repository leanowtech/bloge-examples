package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Dependency-light independent verifier for a signed domain fidelity profile.
 *
 * <p>The verifier does not trust a producer-computed score, denominator, confidence interval, or
 * signature by itself. It first validates the strict packaged schemas, proves the owner-approved
 * inventory content address, closes every profile unit against that inventory, independently
 * rebuilds freshness, per-dimension Wilson intervals, abstention debt, source composition and
 * limitations, then verifies the profile content address and domain-separated Ed25519 seal. No
 * Spring or Resource Gateway server implementation class is linked into the Test Kit.</p>
 *
 * <p>The result is intentionally payload-free and bounded so it can be written to CI or governance
 * logs without leaking Scenario fixtures or business values.</p>
 */
public final class DomainFidelityProfileVerifier {
    /** Maximum canonical bytes admitted for one owner-approved inventory. */
    public static final int MAXIMUM_INVENTORY_BYTES = 8 * 1024 * 1024;
    /** Maximum canonical bytes admitted for one fidelity profile. */
    public static final int MAXIMUM_PROFILE_BYTES = 16 * 1024 * 1024;
    /** Maximum canonical bytes admitted for signature material. */
    public static final int MAXIMUM_ATTESTATION_BYTES = 16 * 1024;

    private static final double TOLERANCE = 1.0e-12d;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_DOMAIN_FIDELITY_PROFILE_V1";
    private static final String CONFIDENCE_METHOD = "WILSON_95_V1";
    private static final Set<String> SOURCE_ARTIFACT_KINDS =
            Set.of(
                    "AUTHORITATIVE_OUTCOME_OBSERVATION",
                    "FIDELITY_SHADOW_COMPARISON",
                    "SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED",
                    "SCENARIO_REHEARSAL_WORKBOOK_SEED");
    private static final Set<String> ABSTENTION_REASONS =
            Set.of(
                    "ASSERTION_EVIDENCE_INDETERMINATE",
                    "DIMENSION_ASSERTION_ABSENT",
                    "EVIDENCE_NOT_CERTIFIABLE",
                    "OUTCOME_AUTHORITY_UNAVAILABLE",
                    "REQUEST_SPACE_EVIDENCE_UNAVAILABLE",
                    "SOURCE_EVIDENCE_INCOMPLETE");

    /** Creates a dependency-light verifier using only packaged schemas and caller-supplied keys. */
    public DomainFidelityProfileVerifier() {
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Schema, closure, arithmetic, content addresses, key policy, and signature all passed. */
        VERIFIED,
        /** Structure, semantics, arithmetic, content address, or signature is invalid. */
        INVALID,
        /** The exact profile verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or key lifecycle policy rejects the profile. */
        POLICY_REJECTED
    }

    /**
     * Payload-free result safe for CI and governance logs.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param domainId business-domain identity, or blank when unavailable
     * @param profileFingerprint profile content address, or blank when unavailable
     * @param assessment independently checked completeness state, or blank when unavailable
     * @param limitations independently checked closed limitations
     * @param keyId profile-seal key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String domainId,
            String profileFingerprint,
            String assessment,
            List<String> limitations,
            String keyId
    ) {
        /** Normalizes and bounds one log-safe result. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            domainId = bounded(normalized(domainId), 512);
            profileFingerprint =
                    bounded(normalized(profileFingerprint), 128);
            assessment = bounded(normalized(assessment), 64);
            keyId = bounded(normalized(keyId), 255);
            limitations = limitations == null
                    ? List.of() : List.copyOf(limitations);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || limitations.size() > 8
                    || limitations.stream().anyMatch(
                    value -> normalized(value).length() > 64)) {
                throw new IllegalArgumentException(
                        "Domain fidelity verification result is invalid");
            }
        }

        /**
         * Reports whether all independent verification steps passed.
         *
         * @return true only for a fully verified profile and inventory closure
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded profile against its exact owner-approved inventory.
     *
     * @param profile decoded signed v1 domain fidelity profile
     * @param inventory decoded owner-approved v1 denominator
     * @param key public key resolved by {@code profile.profileSeal.keyId}; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode profile,
            JsonNode inventory,
            EvidenceVerificationKey key) {
        Coordinates coordinates = Coordinates.from(profile);
        try {
            requireSchemas(profile, inventory);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "DOMAIN_FIDELITY_SCHEMA_INVALID",
                    coordinates);
        }

        try {
            verifyInventory(inventory);
            verifyInventoryClosure(profile, inventory);
            verifyProjection(profile, inventory);
            verifyProfileFingerprint(profile);
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "DOMAIN_FIDELITY_CLOSURE_INVALID",
                    coordinates);
        }

        JsonNode seal = profile.path("profileSeal");
        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "DOMAIN_FIDELITY_VERIFICATION_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!key.keyId().equals(text(seal, "keyId"))) {
            return result(
                    Outcome.INVALID,
                    "DOMAIN_FIDELITY_VERIFICATION_KEY_ID_MISMATCH",
                    coordinates);
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                text(seal, "algorithm"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "DOMAIN_FIDELITY_SIGNATURE_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = instant(
                    seal.path("signedAt"),
                    "DOMAIN_FIDELITY_SEAL_TIME_INVALID");
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates);
        }
        Instant measuredAt = instant(
                profile.path("measuredAt"),
                "DOMAIN_FIDELITY_MEASUREMENT_TIME_INVALID");
        if (!key.verificationAllowed()
                || signedAt.isBefore(measuredAt)
                || signedAt.isBefore(
                key.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "DOMAIN_FIDELITY_VERIFICATION_KEY_POLICY_REJECTED",
                    coordinates);
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            attestationMaterial(profile),
                            MAXIMUM_ATTESTATION_BYTES);
            if (!materialFingerprint.equals(
                    text(seal, "materialFingerprint"))) {
                return result(
                        Outcome.INVALID,
                        "DOMAIN_FIDELITY_ATTESTATION_MATERIAL_INVALID",
                        coordinates);
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint,
                    text(seal, "signature"),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "DOMAIN_FIDELITY_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(
                    Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "DOMAIN_FIDELITY_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static void requireSchemas(
            JsonNode profile, JsonNode inventory) {
        CapabilityMirrorSchemaValidator.require(
                inventory,
                CapabilityMirrorProtocol
                        .DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.DOMAIN_FIDELITY_INVENTORY_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                profile,
                CapabilityMirrorProtocol
                        .DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.DOMAIN_FIDELITY_PROFILE_SCHEMA_INVALID");
    }

    private static void verifyInventory(JsonNode inventory) {
        ObjectNode material =
                inventory.deepCopy();
        material.put("fingerprint", "");
        if (!text(inventory, "fingerprint").equals(
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_INVENTORY_BYTES))) {
            fail("DOMAIN_FIDELITY_INVENTORY_FINGERPRINT_INVALID");
        }

        JsonNode provenance = inventory.path("provenance");
        JsonNode scope = inventory.path("scope");
        Instant approvedAt = instant(
                provenance.path("approvedAt"),
                "DOMAIN_FIDELITY_INVENTORY_TIME_INVALID");
        Instant effectiveAt = instant(
                inventory.path("effectiveAt"),
                "DOMAIN_FIDELITY_INVENTORY_TIME_INVALID");
        Instant inventoryExpiresAt = instant(
                inventory.path("expiresAt"),
                "DOMAIN_FIDELITY_INVENTORY_TIME_INVALID");
        Instant provenanceExpiresAt = instant(
                provenance.path("expiresAt"),
                "DOMAIN_FIDELITY_INVENTORY_TIME_INVALID");
        if (!"OWNER".equals(text(provenance, "sourceType"))
                || !"ACTIVE".equals(text(inventory, "lifecycle"))
                || !text(scope, "tenantId").equals(
                text(provenance, "tenantId"))
                || text(provenance, "approvedBy").isBlank()
                || !text(provenance, "revocationRef").isBlank()
                || effectiveAt.isBefore(approvedAt)
                || !inventoryExpiresAt.isAfter(effectiveAt)
                || !inventoryExpiresAt.equals(
                provenanceExpiresAt)) {
            fail("DOMAIN_FIDELITY_INVENTORY_APPROVAL_INVALID");
        }

        String previousUnit = "";
        Set<String> unitIds = new HashSet<>();
        Set<String> scenarioRefs = new HashSet<>();
        for (JsonNode unit : inventory.path("units")) {
            String unitId = text(unit, "unitId");
            String scenarioRef = canonicalRef(
                    unit.path("scenarioCaseRef"));
            List<String> dimensions =
                    arrayText(unit.path("requiredDimensions"));
            if (!unitIds.add(unitId)
                    || !scenarioRefs.add(scenarioRef)
                    || unitId.compareTo(previousUnit) <= 0
                    || !canonical(dimensions)
                    || !dimensions.contains("BEHAVIOR")
                    || !dimensions.contains("CONTRACT")
                    || "STATE_TRANSITION".equals(
                    text(unit, "caseType"))
                    && !dimensions.contains("STATE_TRANSITION")
                    || "FAULT".equals(text(unit, "caseType"))
                    && !dimensions.contains(
                    "ERROR_DISTRIBUTION")) {
                fail("DOMAIN_FIDELITY_INVENTORY_UNIT_CLOSURE_INVALID");
            }
            previousUnit = unitId;
        }
    }

    private static void verifyInventoryClosure(
            JsonNode profile, JsonNode inventory) {
        JsonNode inventoryRef = profile.path("inventoryRef");
        if (!profile.path("scope").equals(
                inventory.path("scope"))
                || !profile.path("domainId").equals(
                inventory.path("domainId"))
                || !profile.path("taxonomyRef").equals(
                inventory.path("taxonomyRef"))
                || !"DOMAIN_FIDELITY_INVENTORY".equals(
                text(inventoryRef, "kind"))
                || !text(inventoryRef, "id").equals(
                text(inventory, "inventoryId"))
                || inventoryRef.path("revision").asLong()
                != inventory.path("revision").asLong()
                || !text(inventoryRef, "fingerprint").equals(
                text(inventory, "fingerprint"))) {
            fail("DOMAIN_FIDELITY_INVENTORY_REFERENCE_MISMATCH");
        }

        Instant measuredAt = instant(
                profile.path("measuredAt"),
                "DOMAIN_FIDELITY_MEASUREMENT_TIME_INVALID");
        Instant effectiveAt = instant(
                inventory.path("effectiveAt"),
                "DOMAIN_FIDELITY_INVENTORY_TIME_INVALID");
        Instant expiresAt = instant(
                inventory.path("expiresAt"),
                "DOMAIN_FIDELITY_INVENTORY_TIME_INVALID");
        if (measuredAt.isBefore(effectiveAt)
                || !measuredAt.isBefore(expiresAt)) {
            fail("DOMAIN_FIDELITY_INVENTORY_WINDOW_INVALID");
        }
    }

    private static void verifyProjection(
            JsonNode profile, JsonNode inventory) {
        Duration freshness = duration(
                profile.path("policy").path("freshnessWindow"));
        int minimumAssessed = profile.path("policy")
                .path("minimumAssessedUnits").asInt();
        Instant measuredAt = instant(
                profile.path("measuredAt"),
                "DOMAIN_FIDELITY_MEASUREMENT_TIME_INVALID");

        List<JsonNode> inventoryUnits = elements(
                inventory.path("units"));
        List<JsonNode> assessments = elements(
                profile.path("unitAssessments"));
        if (inventoryUnits.size() != assessments.size()) {
            fail("DOMAIN_FIDELITY_UNIT_DENOMINATOR_DRIFT");
        }
        for (int index = 0;
             index < inventoryUnits.size();
             index++) {
            verifyUnit(
                    inventoryUnits.get(index),
                    assessments.get(index),
                    freshness,
                    measuredAt);
        }

        Derived derived = derive(
                assessments, minimumAssessed, measuredAt);
        if (!profile.path("denominator").equals(
                derived.denominator)
                || !sameMetrics(
                profile.path("dimensions"),
                derived.dimensions)
                || !sameDebt(
                profile.path("abstentionDebt"),
                derived.abstentionDebt)
                || !sameComposition(
                profile.path("sourceComposition"),
                derived.sourceComposition)
                || !text(profile, "assessment").equals(
                derived.assessment)
                || !arrayText(profile.path("limitations"))
                .equals(derived.limitations)
                || !instant(
                profile.path("validUntil"),
                "DOMAIN_FIDELITY_VALID_UNTIL_INVALID")
                .equals(derived.validUntil)) {
            fail("DOMAIN_FIDELITY_DERIVED_ARITHMETIC_INVALID");
        }
    }

    private static void verifyUnit(
            JsonNode inventoryUnit,
            JsonNode assessment,
            Duration freshness,
            Instant measuredAt) {
        List<String> required =
                arrayText(
                        inventoryUnit.path(
                                "requiredDimensions"));
        List<String> actual =
                elements(assessment.path("results"))
                        .stream()
                        .map(result ->
                                text(result, "dimension"))
                        .toList();
        if (!inventoryUnit.path("unitId").equals(
                assessment.path("unitId"))
                || !inventoryUnit.path("scenarioCaseRef")
                .equals(assessment.path("scenarioCaseRef"))
                || !required.equals(actual)
                || !canonical(actual)) {
            fail("DOMAIN_FIDELITY_UNIT_CLOSURE_INVALID");
        }

        JsonNode sourceRef = assessment.path("sourceRef");
        boolean missing = sourceRef.isNull();
        if (missing) {
            if (!assessment.path("observedAt").isNull()
                    || !assessment.path("expiresAt").isNull()
                    || !"UNKNOWN".equals(
                    text(assessment, "sourceMode"))
                    || elements(assessment.path("results"))
                    .stream().anyMatch(result ->
                            !"MISSING".equals(
                                    text(result, "outcome"))
                                    || !"NO_ELIGIBLE_EVIDENCE"
                                    .equals(text(
                                            result,
                                            "reason")))) {
                fail("DOMAIN_FIDELITY_MISSING_SOURCE_INVALID");
            }
            return;
        }
        if (!SOURCE_ARTIFACT_KINDS.contains(
                text(sourceRef, "kind"))) {
            fail("DOMAIN_FIDELITY_SOURCE_KIND_REJECTED");
        }
        Instant observedAt = instant(
                assessment.path("observedAt"),
                "DOMAIN_FIDELITY_SOURCE_TIME_INVALID");
        Instant expiresAt = instant(
                assessment.path("expiresAt"),
                "DOMAIN_FIDELITY_SOURCE_TIME_INVALID");
        if (observedAt.isAfter(measuredAt)
                || !observedAt.plus(freshness)
                .equals(expiresAt)) {
            fail("DOMAIN_FIDELITY_SOURCE_TIME_INVALID");
        }
        boolean stale = !expiresAt.isAfter(measuredAt);
        for (JsonNode result
                : assessment.path("results")) {
            verifyOutcomeReason(result);
            if (stale != "STALE".equals(
                    text(result, "outcome"))) {
                fail("DOMAIN_FIDELITY_FRESHNESS_INVALID");
            }
        }
    }

    private static void verifyOutcomeReason(JsonNode result) {
        String outcome = text(result, "outcome");
        String reason = text(result, "reason");
        boolean valid = switch (outcome) {
            case "PASS" -> "ASSERTIONS_PASSED".equals(reason);
            case "FAIL" -> "ASSERTION_FAILED".equals(reason);
            case "ABSTAINED" ->
                    ABSTENTION_REASONS.contains(reason);
            case "STALE" -> "EVIDENCE_STALE".equals(reason);
            case "MISSING" ->
                    "NO_ELIGIBLE_EVIDENCE".equals(reason);
            default -> false;
        };
        if (!valid) {
            fail("DOMAIN_FIDELITY_OUTCOME_REASON_INVALID");
        }
    }

    private static Derived derive(
            List<JsonNode> assessments,
            int minimumAssessed,
            Instant measuredAt) {
        Map<String, Map<String, Integer>> dimensionCounts =
                new TreeMap<>();
        Map<String, Integer> reasonCounts =
                new TreeMap<>();
        Map<String, Integer> sourceCounts =
                new HashMap<>();
        for (JsonNode assessment : assessments) {
            sourceCounts.merge(
                    text(assessment, "sourceMode"),
                    1,
                    Integer::sum);
            for (JsonNode result
                    : assessment.path("results")) {
                String dimension = text(
                        result, "dimension");
                String outcome = text(result, "outcome");
                dimensionCounts
                        .computeIfAbsent(
                                dimension,
                                ignored -> new HashMap<>())
                        .merge(outcome, 1, Integer::sum);
                if ("ABSTAINED".equals(outcome)) {
                    reasonCounts.merge(
                            text(result, "reason"),
                            1,
                            Integer::sum);
                }
            }
        }

        ObjectNode denominator =
                JsonNodeFactory.instance.objectNode();
        denominator.put("totalUnits", assessments.size());
        ArrayNode dimensionDenominators =
                denominator.putArray("dimensions");
        int totalObligations = 0;
        for (Map.Entry<String, Map<String, Integer>> entry
                : dimensionCounts.entrySet()) {
            int required = sum(entry.getValue());
            totalObligations += required;
            ObjectNode dimension =
                    dimensionDenominators.addObject();
            dimension.put("dimension", entry.getKey());
            dimension.put("requiredUnits", required);
        }
        denominator.put(
                "totalObligations", totalObligations);
        ObjectNode orderedDenominator =
                JsonNodeFactory.instance.objectNode();
        orderedDenominator.put(
                "totalUnits", assessments.size());
        orderedDenominator.put(
                "totalObligations", totalObligations);
        orderedDenominator.set(
                "dimensions", dimensionDenominators);

        ArrayNode metrics = JsonNodeFactory.instance.arrayNode();
        boolean stalePresent = false;
        boolean lowSample = false;
        boolean partialCoverage = false;
        boolean abstentionPresent = false;
        boolean outcomeUncalibrated = false;
        boolean requestSpaceUnmeasured = false;
        for (Map.Entry<String, Map<String, Integer>> entry
                : dimensionCounts.entrySet()) {
            Map<String, Integer> counts = entry.getValue();
            int passed = count(counts, "PASS");
            int failed = count(counts, "FAIL");
            int abstained = count(counts, "ABSTAINED");
            int stale = count(counts, "STALE");
            int missing = count(counts, "MISSING");
            int assessed = passed + failed;
            int fresh = assessed + abstained;
            int required = fresh + stale + missing;
            String sufficiency = assessed == 0
                    ? "NO_ASSESSED_EVIDENCE"
                    : assessed < minimumAssessed
                    ? "BELOW_MINIMUM_SAMPLE"
                    : abstained + stale + missing > 0
                    ? "PARTIAL_COVERAGE"
                    : "MEASURED";
            ObjectNode metric = metrics.addObject();
            metric.put("dimension", entry.getKey());
            metric.put("requiredUnits", required);
            metric.put("freshEvidenceUnits", fresh);
            metric.put("assessedUnits", assessed);
            metric.put("passedUnits", passed);
            metric.put("failedUnits", failed);
            metric.put("abstainedUnits", abstained);
            metric.put("staleUnits", stale);
            metric.put("missingUnits", missing);
            metric.put(
                    "coverageRatio", ratio(fresh, required));
            metric.put(
                    "abstentionRatio",
                    ratio(abstained, required));
            if (assessed == 0) {
                metric.putNull("confidence");
            } else {
                metric.set(
                        "confidence",
                        wilson(passed, assessed));
            }
            metric.put("sufficiency", sufficiency);
            stalePresent |= stale > 0;
            lowSample |= !"MEASURED".equals(sufficiency)
                    && !"PARTIAL_COVERAGE".equals(
                    sufficiency);
            partialCoverage |= "PARTIAL_COVERAGE".equals(
                    sufficiency);
            abstentionPresent |= abstained > 0;
            if ("OUTCOME".equals(entry.getKey())) {
                outcomeUncalibrated =
                        !"MEASURED".equals(sufficiency);
            }
            if ("REQUEST_SPACE".equals(entry.getKey())) {
                requestSpaceUnmeasured =
                        !"MEASURED".equals(sufficiency);
            }
        }

        ObjectNode debt = JsonNodeFactory.instance.objectNode();
        int abstainedObligations =
                reasonCounts.values().stream()
                        .mapToInt(Integer::intValue)
                        .sum();
        debt.put("totalObligations", totalObligations);
        debt.put(
                "abstainedObligations",
                abstainedObligations);
        debt.put(
                "ratio",
                ratio(
                        abstainedObligations,
                        totalObligations));
        ArrayNode reasons = debt.putArray("reasons");
        reasonCounts.forEach((reason, count) -> {
            ObjectNode value = reasons.addObject();
            value.put("reason", reason);
            value.put("count", count);
        });

        ObjectNode composition =
                JsonNodeFactory.instance.objectNode();
        int totalUnits = assessments.size();
        int recorded = count(sourceCounts, "RECORDED");
        int synthesized =
                count(sourceCounts, "SYNTHESIZED");
        int ownerDeclared =
                count(sourceCounts, "OWNER_DECLARED");
        int authoritative =
                count(sourceCounts, "AUTHORITATIVE");
        int unknown = count(sourceCounts, "UNKNOWN");
        composition.put("totalUnits", totalUnits);
        composition.put("recordedUnits", recorded);
        composition.put(
                "synthesizedUnits", synthesized);
        composition.put(
                "ownerDeclaredUnits", ownerDeclared);
        composition.put(
                "authoritativeUnits", authoritative);
        composition.put("unknownUnits", unknown);
        composition.put(
                "synthesizedRatio",
                ratio(synthesized, totalUnits));
        composition.put(
                "unknownRatio",
                ratio(unknown, totalUnits));

        String assessment = stalePresent
                ? "STALE"
                : lowSample
                ? "INSUFFICIENT_EVIDENCE"
                : partialCoverage
                ? "PARTIAL"
                : "COMPLETE";
        Set<String> limitations = new TreeSet<>();
        if (abstentionPresent) {
            limitations.add("ABSTENTION_PRESENT");
        }
        if (abstentionPresent || stalePresent
                || dimensionCounts.values().stream()
                .anyMatch(counts ->
                        count(counts, "MISSING") > 0)) {
            limitations.add("COVERAGE_INCOMPLETE");
        }
        if (stalePresent) {
            limitations.add("EVIDENCE_STALE");
        }
        if (lowSample) {
            limitations.add("LOW_SAMPLE");
        }
        if (outcomeUncalibrated) {
            limitations.add("OUTCOME_UNCALIBRATED");
        }
        if (requestSpaceUnmeasured) {
            limitations.add("REQUEST_SPACE_UNMEASURED");
        }
        if (synthesized > 0) {
            limitations.add(
                    "SYNTHESIZED_SOURCE_PRESENT");
        }
        if (unknown > 0) {
            limitations.add("SOURCE_MODE_UNKNOWN");
        }
        Instant validUntil = assessments.stream()
                .filter(unit -> !unit.path("expiresAt").isNull())
                .map(unit -> instant(
                        unit.path("expiresAt"),
                        "DOMAIN_FIDELITY_VALID_UNTIL_INVALID"))
                .filter(value -> value.isAfter(measuredAt))
                .min(Comparator.naturalOrder())
                .orElse(measuredAt);
        return new Derived(
                orderedDenominator,
                metrics,
                debt,
                composition,
                assessment,
                List.copyOf(limitations),
                validUntil);
    }

    private static ObjectNode wilson(
            int passed, int assessed) {
        double point = (double) passed / assessed;
        double z = 1.959963984540054d;
        double denominator =
                1.0d + z * z / assessed;
        double center =
                point + z * z / (2.0d * assessed);
        double spread = z * Math.sqrt(
                point * (1.0d - point) / assessed
                        + z * z
                        / (4.0d * assessed * assessed));
        ObjectNode confidence =
                JsonNodeFactory.instance.objectNode();
        confidence.put("point", point);
        confidence.put(
                "lowerBound",
                Math.max(
                        0.0d,
                        (center - spread) / denominator));
        confidence.put(
                "upperBound",
                Math.min(
                        1.0d,
                        (center + spread) / denominator));
        confidence.put("method", CONFIDENCE_METHOD);
        return confidence;
    }

    private static boolean sameMetrics(
            JsonNode actual, ArrayNode expected) {
        if (!actual.isArray()
                || actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0;
             index < expected.size();
             index++) {
            JsonNode left = actual.get(index);
            JsonNode right = expected.get(index);
            for (String field : List.of(
                    "dimension", "requiredUnits",
                    "freshEvidenceUnits", "assessedUnits",
                    "passedUnits", "failedUnits",
                    "abstainedUnits", "staleUnits",
                    "missingUnits", "sufficiency")) {
                if (!left.path(field).equals(
                        right.path(field))) {
                    return false;
                }
            }
            if (!near(
                    left.path("coverageRatio").asDouble(),
                    right.path("coverageRatio").asDouble())
                    || !near(
                    left.path("abstentionRatio")
                            .asDouble(),
                    right.path("abstentionRatio")
                            .asDouble())
                    || !sameConfidence(
                    left.path("confidence"),
                    right.path("confidence"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameConfidence(
            JsonNode actual, JsonNode expected) {
        if (actual.isNull() || expected.isNull()) {
            return actual.isNull() && expected.isNull();
        }
        return actual.path("method").equals(
                expected.path("method"))
                && near(
                actual.path("point").asDouble(),
                expected.path("point").asDouble())
                && near(
                actual.path("lowerBound").asDouble(),
                expected.path("lowerBound").asDouble())
                && near(
                actual.path("upperBound").asDouble(),
                expected.path("upperBound").asDouble());
    }

    private static boolean sameDebt(
            JsonNode actual, ObjectNode expected) {
        return actual.path("totalObligations").equals(
                expected.path("totalObligations"))
                && actual.path("abstainedObligations")
                .equals(expected.path(
                        "abstainedObligations"))
                && near(
                actual.path("ratio").asDouble(),
                expected.path("ratio").asDouble())
                && actual.path("reasons").equals(
                expected.path("reasons"));
    }

    private static boolean sameComposition(
            JsonNode actual, ObjectNode expected) {
        for (String field : List.of(
                "totalUnits", "recordedUnits",
                "synthesizedUnits", "ownerDeclaredUnits",
                "authoritativeUnits", "unknownUnits")) {
            if (!actual.path(field).equals(
                    expected.path(field))) {
                return false;
            }
        }
        return near(
                actual.path("synthesizedRatio").asDouble(),
                expected.path("synthesizedRatio").asDouble())
                && near(
                actual.path("unknownRatio").asDouble(),
                expected.path("unknownRatio").asDouble());
    }

    private static void verifyProfileFingerprint(
            JsonNode profile) {
        ObjectNode material = profile.deepCopy();
        material.put("profileFingerprint", "");
        material.set("profileSeal", unsignedSeal());
        if (!text(profile, "profileFingerprint").equals(
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_PROFILE_BYTES))) {
            fail("DOMAIN_FIDELITY_PROFILE_FINGERPRINT_INVALID");
        }
    }

    private static ObjectNode attestationMaterial(
            JsonNode profile) {
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        material.put("domain", SIGNATURE_DOMAIN);
        material.set(
                "schemaVersion",
                profile.path("schemaVersion").deepCopy());
        material.set(
                "domainId",
                profile.path("domainId").deepCopy());
        material.set(
                "inventoryRef",
                profile.path("inventoryRef").deepCopy());
        material.set(
                "measuredAt",
                profile.path("measuredAt").deepCopy());
        material.set(
                "profileFingerprint",
                profile.path(
                        "profileFingerprint").deepCopy());
        return material;
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
                "1970-01-01T00:00:00Z");
        seal.put("signature", "");
        return seal;
    }

    private static Duration duration(JsonNode value) {
        try {
            Duration result =
                    Duration.parse(value.asText());
            if (result.compareTo(
                    Duration.ofHours(1)) < 0
                    || result.compareTo(
                    Duration.ofDays(365)) > 0) {
                fail("DOMAIN_FIDELITY_FRESHNESS_POLICY_INVALID");
            }
            return result;
        } catch (DateTimeParseException
                 | ArithmeticException invalid) {
            fail("DOMAIN_FIDELITY_FRESHNESS_POLICY_INVALID");
            throw new IllegalStateException("unreachable");
        }
    }

    private static Instant instant(
            JsonNode value, String reasonCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            fail(reasonCode);
            throw new IllegalStateException("unreachable");
        }
    }

    private static String canonicalRef(JsonNode ref) {
        return text(ref, "kind") + "\u0000"
                + text(ref, "id") + "\u0000"
                + ref.path("revision").asLong() + "\u0000"
                + text(ref, "fingerprint");
    }

    private static List<JsonNode> elements(JsonNode value) {
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return List.copyOf(result);
    }

    private static List<String> arrayText(JsonNode value) {
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(
                item.asText()));
        return List.copyOf(result);
    }

    private static boolean canonical(List<String> values) {
        return values.equals(
                values.stream()
                        .distinct()
                        .sorted()
                        .toList());
    }

    private static int sum(Map<String, Integer> counts) {
        return counts.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static int count(
            Map<String, Integer> counts, String key) {
        return counts.getOrDefault(key, 0);
    }

    private static double ratio(
            int numerator, int denominator) {
        return (double) numerator / denominator;
    }

    private static boolean near(
            double left, double right) {
        return Math.abs(left - right) <= TOLERANCE;
    }

    private static String text(
            JsonNode value, String field) {
        return value.path(field).asText();
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.domainId,
                coordinates.profileFingerprint,
                coordinates.assessment,
                coordinates.limitations,
                coordinates.keyId);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(
            String value, int maximumLength) {
        return value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }

    private record Derived(
            ObjectNode denominator,
            ArrayNode dimensions,
            ObjectNode abstentionDebt,
            ObjectNode sourceComposition,
            String assessment,
            List<String> limitations,
            Instant validUntil
    ) {
    }

    private record Coordinates(
            String domainId,
            String profileFingerprint,
            String assessment,
            List<String> limitations,
            String keyId
    ) {
        private static Coordinates from(JsonNode profile) {
            if (profile == null || !profile.isObject()) {
                return new Coordinates(
                        "", "", "", List.of(), "");
            }
            List<String> limitations =
                    profile.path("limitations").isArray()
                            ? arrayText(
                            profile.path("limitations"))
                            : List.of();
            if (limitations.size() > 8) {
                limitations = limitations.subList(0, 8);
            }
            return new Coordinates(
                    text(profile, "domainId"),
                    text(profile, "profileFingerprint"),
                    text(profile, "assessment"),
                    limitations,
                    text(profile.path("profileSeal"), "keyId"));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
