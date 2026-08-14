package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent verification of a Business Mirror pilot acceptance manifest.
 *
 * <p>The verifier proves Schema, content addresses, fixed denominator, evidence-kind closure,
 * time semantics, and derived status. It does not resolve evidence refs or grant customer,
 * ANEKE, business-owner, or target-environment authority.</p>
 */
public final class BusinessMirrorPilotAcceptanceProtocol {
    /** Pilot acceptance manifest v1 wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.businessMirrorPilotAcceptanceManifest.v1";
    /** Packaged strict Schema resource. */
    public static final String SCHEMA_RESOURCE =
            BusinessMirrorProtocol.SCHEMA_RESOURCE_ROOT
                    + "business-mirror-pilot-acceptance-manifest-v1.schema.json";
    /** Packaged cancellation-fee reference fixture. */
    public static final String REFERENCE_FIXTURE_RESOURCE =
            BusinessMirrorProtocol.SCHEMA_RESOURCE_ROOT
                    + "business-mirror-pilot-acceptance-manifest-v1.fixture.json";

    private static final List<String> GATE_IDS = List.of(
            "PACKAGE_DEFINITION_COMPLETE",
            "HIGH_RISK_BRANCH_OBLIGATIONS",
            "ISOLATED_PROPOSAL_REHEARSAL",
            "SAME_SUITE_IMPLEMENTATION_CONFORMANCE",
            "ZERO_EXTERNAL_BUSINESS_WRITES",
            "EVIDENCE_TRACEABILITY",
            "ANEKE_GOVERNANCE_ROUND_TRIP",
            "CHANGE_IMPACT_ANALYSIS",
            "OUTCOME_FIDELITY_FAIL_CLOSED",
            "TARGET_ENVIRONMENT_CERTIFICATION");
    private static final Map<String, String> REQUIRED_GATE_AUTHORITY = Map.ofEntries(
            Map.entry(GATE_IDS.get(0), "RESOURCE_GATEWAY"),
            Map.entry(GATE_IDS.get(1), "CUSTOMER_BUSINESS_OWNER"),
            Map.entry(GATE_IDS.get(2), "RESOURCE_GATEWAY"),
            Map.entry(GATE_IDS.get(3), "RESOURCE_GATEWAY"),
            Map.entry(GATE_IDS.get(4), "CUSTOMER_PLATFORM"),
            Map.entry(GATE_IDS.get(5), "RESOURCE_GATEWAY"),
            Map.entry(GATE_IDS.get(6), "ANEKE"),
            Map.entry(GATE_IDS.get(7), "RESOURCE_GATEWAY"),
            Map.entry(GATE_IDS.get(8), "CUSTOMER_BUSINESS_OWNER"),
            Map.entry(GATE_IDS.get(9), "CUSTOMER_PLATFORM"));
    private static final Map<String, Set<String>> REQUIRED_PASS_EVIDENCE = Map.ofEntries(
            Map.entry(GATE_IDS.get(0), Set.of("DOMAIN_CAPABILITY_PACKAGE",
                    "PACKAGE_READINESS_REPORT", "SCENARIO_DENOMINATOR")),
            Map.entry(GATE_IDS.get(1), Set.of(
                    "SCENARIO_DENOMINATOR", "BUSINESS_ACCEPTANCE_SUITE")),
            Map.entry(GATE_IDS.get(2), Set.of(
                    "PROPOSAL_SIMULATION_EVIDENCE",
                    "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION")),
            Map.entry(GATE_IDS.get(3), Set.of(
                    "IMPLEMENTATION_CONFORMANCE_REPORT", "BUSINESS_ACCEPTANCE_SUITE")),
            Map.entry(GATE_IDS.get(4), Set.of(
                    "MIRROR_EVIDENCE_BUNDLE", "RUNTIME_CERTIFICATION_REPORT")),
            Map.entry(GATE_IDS.get(5), Set.of("PACKAGE_EVIDENCE_INDEX")),
            Map.entry(GATE_IDS.get(6), Set.of(
                    "PACKAGE_REGISTRY_INGEST_BUNDLE", "ANEKE_PACKAGE_GATE_DECISION")),
            Map.entry(GATE_IDS.get(7), Set.of("BUSINESS_ASSET_IMPACT_REPORT")),
            Map.entry(GATE_IDS.get(8), Set.of(
                    "DOMAIN_FIDELITY_PROFILE",
                    "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST")),
            Map.entry(GATE_IDS.get(9), Set.of(
                    "REGIONAL_DATA_PLANE_CERTIFICATION", "RUNTIME_CERTIFICATION_REPORT")));
    private static final String TOO_LARGE =
            "RG.BUSINESS_MIRROR.CLIENT.PILOT_ACCEPTANCE_VALUE_TOO_LARGE";
    private static final String CANONICALIZATION_FAILED =
            "RG.BUSINESS_MIRROR.CLIENT.PILOT_ACCEPTANCE_CANONICALIZATION_FAILED";

    private BusinessMirrorPilotAcceptanceProtocol() {
    }

    /**
     * Verifies one untrusted manifest without granting any external acceptance authority.
     *
     * @param manifest untrusted pilot acceptance manifest
     * @return payload-free verified coordinates and state counts
     * @throws IllegalArgumentException when Schema, address, closure, time, or status fails
     */
    public static VerifiedPilotAcceptance verify(JsonNode manifest) {
        BusinessMirrorSchemaValidator.require(manifest, SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PILOT_ACCEPTANCE_SCHEMA_INVALID");
        require(SCHEMA_VERSION.equals(manifest.path("schemaVersion").asText()),
                "PILOT_ACCEPTANCE_VERSION_UNSUPPORTED");

        ObjectNode denominator = object(manifest.path("scenarioDenominator"));
        String denominatorFingerprint = denominator.path("denominatorFingerprint").asText();
        denominator.put("denominatorFingerprint", "");
        require(denominatorFingerprint.equals(fingerprint(denominator)),
                "PILOT_DENOMINATOR_FINGERPRINT_MISMATCH");

        ObjectNode material = object(manifest);
        String manifestFingerprint = material.path("manifestFingerprint").asText();
        material.put("manifestFingerprint", "");
        require(manifestFingerprint.equals(fingerprint(material)),
                "PILOT_ACCEPTANCE_FINGERPRINT_MISMATCH");

        JsonNode denominatorSource = manifest.path("scenarioDenominator");
        int familyCount = denominatorSource.path("declaredFamilyCount").asInt();
        int highRisk = denominatorSource.path("highRiskObligationCount").asInt();
        int coveredHighRisk = denominatorSource.path("coveredHighRiskObligationCount").asInt();
        int unknownRange = denominatorSource.path("unknownRangeCount").asInt();
        require(familyCount == denominatorSource.path("scenarioFamilyRefs").size()
                        && highRisk > 0 && coveredHighRisk >= 0 && coveredHighRisk <= highRisk
                        && unknownRange == denominatorSource.path("unknownRangeRefs").size(),
                "PILOT_DENOMINATOR_COUNTS_INVALID");

        JsonNode gates = manifest.path("acceptanceGates");
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int evidenceAvailable = 0;
        for (int index = 0; index < GATE_IDS.size(); index++) {
            JsonNode gate = gates.path(index);
            String gateId = gate.path("gateId").asText();
            require(GATE_IDS.get(index).equals(gateId),
                    "PILOT_GATE_DENOMINATOR_INVALID");
            require(REQUIRED_GATE_AUTHORITY.get(gateId)
                            .equals(gate.path("authority").asText()),
                    "PILOT_GATE_AUTHORITY_INVALID");
            String state = gate.path("state").asText();
            switch (state) {
                case "PASSED" -> {
                    passed++;
                    Set<String> actualKinds = kinds(gate.path("evidenceRefs"));
                    require(actualKinds.containsAll(REQUIRED_PASS_EVIDENCE.get(gateId)),
                            "PILOT_GATE_PASS_EVIDENCE_INCOMPLETE");
                }
                case "FAILED" -> {
                    failed++;
                    require(!gate.path("reasonCodes").isEmpty(),
                            "PILOT_GATE_FAILURE_REASON_REQUIRED");
                }
                case "BLOCKED" -> blocked++;
                case "EVIDENCE_AVAILABLE" -> evidenceAvailable++;
                case "NOT_EVALUATED" -> {
                    // Count is derivable from the ten-gate denominator.
                }
                default -> throw invalid("PILOT_GATE_STATE_INVALID");
            }
        }

        JsonNode denominatorRef = artifactRef("SCENARIO_DENOMINATOR",
                denominatorSource.path("denominatorId").asText(),
                denominatorSource.path("revision").asLong(), denominatorFingerprint);
        require(containsRef(gates.path(0).path("evidenceRefs"),
                        manifest.path("packageSnapshotRef"))
                        && containsRef(gates.path(0).path("evidenceRefs"), denominatorRef)
                        && containsRef(gates.path(1).path("evidenceRefs"), denominatorRef),
                "PILOT_CORE_REFERENCE_CLOSURE_INVALID");

        Instant frozenAt = instant(denominatorSource.path("frozenAt").asText());
        Instant assembledAt = instant(manifest.path("assembledAt").asText());
        JsonNode observation = manifest.path("observationWindow");
        Instant plannedFrom = instant(observation.path("plannedFrom").asText());
        Instant plannedTo = instant(observation.path("plannedTo").asText());
        require(!assembledAt.isBefore(frozenAt) && plannedTo.isAfter(plannedFrom),
                "PILOT_ACCEPTANCE_TIME_INVALID");

        String observationStatus = observation.path("status").asText();
        Instant observationStartedAt = observation.path("actualFrom").isTextual()
                ? instant(observation.path("actualFrom").asText()) : null;
        Instant observationCompletedAt = null;
        if ("PLANNED".equals(observationStatus)) {
            require(observation.path("actualFrom").isNull()
                            && observation.path("actualTo").isNull()
                            && observation.path("authoritativeOutcomePopulationRef").isNull(),
                    "PILOT_OBSERVATION_STATE_INVALID");
        } else if ("ACTIVE".equals(observationStatus)) {
            require(observation.path("actualFrom").isTextual()
                            && observation.path("actualTo").isNull()
                            && observation.path("authoritativeOutcomePopulationRef").isNull(),
                    "PILOT_OBSERVATION_STATE_INVALID");
        } else if ("COMPLETED".equals(observationStatus)) {
            Instant actualTo = instant(observation.path("actualTo").asText());
            require(observationStartedAt != null && actualTo.isAfter(observationStartedAt)
                            && "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST".equals(
                            observation.path("authoritativeOutcomePopulationRef")
                                    .path("kind").asText()),
                    "PILOT_OBSERVATION_STATE_INVALID");
            require(containsRef(gates.path(8).path("evidenceRefs"),
                            observation.path("authoritativeOutcomePopulationRef")),
                    "PILOT_OUTCOME_REFERENCE_CLOSURE_INVALID");
            observationCompletedAt = actualTo;
        }

        for (int index = 0; index < gates.size(); index++) {
            JsonNode gate = gates.path(index);
            if (gate.path("assessedAt").isTextual()) {
                Instant assessedAt = instant(gate.path("assessedAt").asText());
                require(!assessedAt.isAfter(assembledAt)
                                && (index > 1 || !assessedAt.isBefore(frozenAt)),
                        "PILOT_ACCEPTANCE_TIME_INVALID");
            }
        }
        require((observationStartedAt == null || !observationStartedAt.isAfter(assembledAt))
                        && (observationCompletedAt == null
                        || !observationCompletedAt.isAfter(assembledAt)),
                "PILOT_ACCEPTANCE_TIME_INVALID");
        require(!"PASSED".equals(gates.path(1).path("state").asText())
                        || coveredHighRisk == highRisk,
                "PILOT_HIGH_RISK_COVERAGE_INCOMPLETE");

        boolean acceptanceReady = passed == GATE_IDS.size()
                && "COMPLETED".equals(observationStatus);
        String customerStatus = manifest.path("customerAcceptance").path("status").asText();
        Instant customerDecidedAt = manifest.path("customerAcceptance").path("decidedAt")
                .isTextual()
                ? instant(manifest.path("customerAcceptance").path("decidedAt").asText())
                : null;
        require(customerDecidedAt == null
                        || !customerDecidedAt.isAfter(assembledAt)
                        && !customerDecidedAt.isBefore(frozenAt),
                "PILOT_ACCEPTANCE_TIME_INVALID");
        String expectedStatus = switch (customerStatus) {
            case "ACCEPTED" -> {
                require(acceptanceReady, "PILOT_CUSTOMER_ACCEPTANCE_UNPROVEN");
                require(customerDecidedAt != null && observationCompletedAt != null
                                && !customerDecidedAt.isBefore(observationCompletedAt),
                        "PILOT_CUSTOMER_ACCEPTANCE_TIME_INVALID");
                yield "CUSTOMER_ACCEPTED";
            }
            case "REJECTED" -> "CUSTOMER_REJECTED";
            case "IN_REVIEW" -> {
                require(acceptanceReady, "PILOT_CUSTOMER_REVIEW_PREMATURE");
                yield "READY_FOR_CUSTOMER_VALIDATION";
            }
            case "NOT_REQUESTED" -> acceptanceReady
                    ? "READY_FOR_CUSTOMER_VALIDATION" : "PREPARING";
            default -> throw invalid("PILOT_CUSTOMER_ACCEPTANCE_STATE_INVALID");
        };
        require(expectedStatus.equals(manifest.path("status").asText()),
                "PILOT_OVERALL_STATUS_INVALID");

        return new VerifiedPilotAcceptance(manifest.path("manifestId").asText(),
                manifest.path("revision").asLong(), manifestFingerprint,
                manifest.path("pilotDomainId").asText(),
                manifest.path("packageSnapshotRef").path("id").asText(),
                denominatorSource.path("denominatorId").asText(), denominatorFingerprint,
                familyCount, highRisk, coveredHighRisk, unknownRange, passed, failed, blocked,
                evidenceAvailable, observationStatus, customerStatus,
                manifest.path("status").asText(), frozenAt, assembledAt);
    }

    private static ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid("PILOT_ACCEPTANCE_OBJECT_REQUIRED");
        }
        return object.deepCopy();
    }

    private static String fingerprint(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value, TOO_LARGE, CANONICALIZATION_FAILED);
    }

    private static Set<String> kinds(JsonNode refs) {
        java.util.Set<String> values = new java.util.HashSet<>();
        refs.forEach(ref -> values.add(ref.path("kind").asText()));
        return Set.copyOf(values);
    }

    private static JsonNode artifactRef(
            String kind, String id, long revision, String refFingerprint) {
        ObjectNode value = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", revision);
        value.put("fingerprint", refFingerprint);
        return value;
    }

    private static boolean containsRef(JsonNode refs, JsonNode expected) {
        for (JsonNode ref : refs) {
            if (ref.path("kind").asText().equals(expected.path("kind").asText())
                    && ref.path("id").asText().equals(expected.path("id").asText())
                    && ref.path("revision").asLong() == expected.path("revision").asLong()
                    && ref.path("fingerprint").asText()
                    .equals(expected.path("fingerprint").asText())) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("PILOT_ACCEPTANCE_TIME_INVALID");
        }
    }

    private static void require(boolean condition, String suffix) {
        if (!condition) {
            throw invalid(suffix);
        }
    }

    private static IllegalArgumentException invalid(String suffix) {
        return new IllegalArgumentException("RG.BUSINESS_MIRROR.CLIENT." + suffix);
    }

    /**
     * Payload-free coordinates and counts safe for CI reports and release-gate diagnostics.
     *
     * @param manifestId immutable manifest identity
     * @param revision exact manifest revision
     * @param manifestFingerprint canonical manifest content address
     * @param pilotDomainId pilot business-domain identity
     * @param packageId exact Package identity
     * @param denominatorId owner-frozen denominator identity
     * @param denominatorFingerprint canonical denominator content address
     * @param scenarioFamilyCount declared scenario-family count
     * @param highRiskObligationCount declared high-risk obligation count
     * @param coveredHighRiskObligationCount covered high-risk obligation count
     * @param unknownRangeCount explicitly visible unknown-range count
     * @param passedGateCount passed gate count
     * @param failedGateCount failed gate count
     * @param blockedGateCount blocked gate count
     * @param evidenceAvailableGateCount unassessed gate count with evidence available
     * @param observationStatus customer observation-window state
     * @param customerAcceptanceStatus external customer decision state
     * @param overallStatus derived manifest state
     * @param frozenAt owner denominator freeze time
     * @param assembledAt manifest assembly time
     */
    public record VerifiedPilotAcceptance(
            String manifestId,
            long revision,
            String manifestFingerprint,
            String pilotDomainId,
            String packageId,
            String denominatorId,
            String denominatorFingerprint,
            int scenarioFamilyCount,
            int highRiskObligationCount,
            int coveredHighRiskObligationCount,
            int unknownRangeCount,
            int passedGateCount,
            int failedGateCount,
            int blockedGateCount,
            int evidenceAvailableGateCount,
            String observationStatus,
            String customerAcceptanceStatus,
            String overallStatus,
            Instant frozenAt,
            Instant assembledAt) {
    }
}
