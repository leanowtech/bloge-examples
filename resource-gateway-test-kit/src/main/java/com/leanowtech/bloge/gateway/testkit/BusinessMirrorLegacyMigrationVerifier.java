package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Registry-free semantic verification for Legacy Graph Package migration projections. */
public final class BusinessMirrorLegacyMigrationVerifier {
    private static final String INVALID =
            "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_INVALID";
    private static final String CATALOG_INVALID =
            "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_CATALOG_INVALID";
    private static final String FINGERPRINT_MISMATCH =
            "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_FINGERPRINT_MISMATCH";

    private BusinessMirrorLegacyMigrationVerifier() {
    }

    /**
     * Applies strict Schema, binding, readiness-gap, trust, and fingerprint verification.
     *
     * @param projection decoded Legacy Graph Package projection
     * @return payload-free verified projection identity and gap counts
     * @throws IllegalArgumentException when any structural or semantic invariant fails
     */
    public static VerifiedProjection verifyProjection(JsonNode projection) {
        BusinessMirrorSchemaValidator.require(projection,
                BusinessMirrorProtocol.LEGACY_GRAPH_PROJECTION_SCHEMA_RESOURCE, INVALID);
        JsonNode draft = projection.path("packageDraft");
        BusinessMirrorProtocol.requirePackageDraft(draft);
        require(projection.path("scope").equals(draft.path("scope")));
        require(draft.path("revision").asLong() == 0L
                && "DRAFT".equals(draft.path("lifecycle").asText())
                && "INFERRED".equals(draft.path("provenance").path("sourceType").asText())
                && draft.path("provenance").path("approvedBy").asText().isEmpty());

        String graphName = projection.path("graphName").asText();
        JsonNode graphRef = projection.path("sourceGraphRef");
        JsonNode contractRef = projection.path("sourceContractRef");
        JsonNode capabilityRef = projection.path("projectedCapabilityRef");
        JsonNode closureRef = projection.path("capabilityClosureRef");
        require(("built-in:" + graphName).equals(graphRef.path("id").asText()));
        require(("built-in:" + graphName + ":contract")
                .equals(contractRef.path("id").asText()));
        require(capabilityRef.path("id").asText().equals(closureRef.path("id").asText()));
        require(draft.path("graphRefs").size() == 1
                && graphRef.equals(draft.path("graphRefs").get(0))
                && draft.path("capabilityRefs").isEmpty()
                && contractRef.equals(draft.path("packageContractRef")));

        Set<String> provenanceRefs = canonicalSet(draft.path("provenance").path("sourceRefs"));
        require(provenanceRefs.containsAll(Set.of(
                canonical(graphRef), canonical(contractRef), canonical(capabilityRef),
                canonical(closureRef))));
        for (JsonNode suiteRef : projection.path("discoveredTestSuiteRefs")) {
            require(provenanceRefs.contains(canonical(suiteRef)));
        }

        Set<String> expectedReadiness = readinessBlockers(draft);
        Set<String> actualReadiness = new LinkedHashSet<>();
        Set<String> gapCodes = new HashSet<>();
        String previous = "";
        boolean blocking = false;
        for (JsonNode gap : projection.path("gaps")) {
            String code = gap.path("code").asText();
            require(gapCodes.add(code) && (previous.isEmpty() || previous.compareTo(code) < 0));
            previous = code;
            blocking |= "BLOCKING".equals(gap.path("severity").asText());
            if ("PACKAGE_READINESS".equals(gap.path("origin").asText())) {
                require("BLOCKING".equals(gap.path("severity").asText()));
                actualReadiness.add(code);
            }
        }
        require(expectedReadiness.equals(actualReadiness));
        require(gapCodes.containsAll(Set.of(
                "GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING",
                "MIRROR_PLAN_MISSING",
                "LEGACY_PROJECTION_OWNER_APPROVAL_MISSING")));
        if (!projection.path("discoveredTestSuiteRefs").isEmpty()) {
            require(gapCodes.contains(
                    "DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE"));
        }
        require((blocking ? "BLOCKED" : "READY_FOR_OWNER_REVIEW")
                .equals(projection.path("status").asText()));

        ObjectNode unsigned = projection.deepCopy();
        unsigned.put("projectionFingerprint", "");
        String expectedFingerprint = BusinessMirrorCanonical.fingerprint(unsigned,
                "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_CANONICALIZATION_FAILED");
        if (!expectedFingerprint.equals(projection.path("projectionFingerprint").asText())) {
            throw invalid(FINGERPRINT_MISMATCH);
        }
        return new VerifiedProjection(graphName, draft.path("packageId").asText(),
                projection.path("projectionFingerprint").asText(), expectedReadiness.size(),
                gapCodes.size(), canonical(projection.path("scope")));
    }

    /**
     * Verifies a bounded, graph-name ordered, single-Scope projection catalog.
     *
     * @param catalog decoded Legacy Graph projection catalog
     * @return payload-free verified item count and Scope fingerprint
     * @throws IllegalArgumentException when catalog or nested projection verification fails
     */
    public static VerifiedCatalog verifyCatalog(JsonNode catalog) {
        BusinessMirrorSchemaValidator.require(catalog,
                BusinessMirrorProtocol.LEGACY_GRAPH_PROJECTION_CATALOG_SCHEMA_RESOURCE,
                CATALOG_INVALID);
        String scopeFingerprint = canonical(catalog.path("scope"));
        String previous = "";
        int count = 0;
        for (JsonNode projection : catalog.path("items")) {
            VerifiedProjection verified = verifyProjection(projection);
            require(verified.scopeFingerprint().equals(scopeFingerprint)
                    && (previous.isEmpty() || previous.compareTo(verified.graphName()) < 0));
            previous = verified.graphName();
            count++;
        }
        return new VerifiedCatalog(count, scopeFingerprint);
    }

    private static Set<String> readinessBlockers(JsonNode draft) {
        Set<String> blockers = new LinkedHashSet<>();
        JsonNode business = draft.path("businessDefinition");
        addIfBlank(blockers, business, "domainId", "BUSINESS_DOMAIN_MISSING");
        if (business.path("problemTaxonomyRef").isNull()) {
            blockers.add("PROBLEM_TAXONOMY_MISSING");
        }
        addIfBlank(blockers, business, "problemCode", "PROBLEM_CODE_MISSING");
        addIfBlank(blockers, business, "businessGoal", "BUSINESS_GOAL_MISSING");
        addIfBlank(blockers, business, "expectedOutcome", "EXPECTED_OUTCOME_MISSING");
        addIfBlank(blockers, business, "accountableOwner", "ACCOUNTABLE_OWNER_MISSING");
        if (draft.path("packageContractRef").isNull()) {
            blockers.add("PACKAGE_CONTRACT_MISSING");
        }
        if (draft.path("capabilityRefs").isEmpty() && draft.path("graphRefs").isEmpty()) {
            blockers.add("EXECUTABLE_PROJECTION_MISSING");
        }
        if (draft.path("scenarioInventoryRef").isNull()) {
            blockers.add("SCENARIO_INVENTORY_MISSING");
        }
        addIfEmpty(blockers, draft, "scenarioPackRefs", "SCENARIO_PACK_MISSING");
        addIfEmpty(blockers, draft, "solutionRefs", "SOLUTION_BINDING_MISSING");
        addIfEmpty(blockers, draft, "carrierRefs", "SERVICE_CARRIER_BINDING_MISSING");
        addIfEmpty(blockers, draft, "channelRefs", "CHANNEL_BINDING_MISSING");
        if (draft.path("fidelityInventoryRef").isNull()) {
            blockers.add("FIDELITY_INVENTORY_MISSING");
        }
        addIfEmpty(blockers, draft, "outcomeDefinitionRefs", "OUTCOME_DEFINITION_MISSING");
        String risk = business.path("riskClass").asText();
        if (("HIGH".equals(risk) || "CRITICAL".equals(risk))
                && draft.path("stateModelRefs").isEmpty()) {
            blockers.add("HIGH_RISK_STATE_MODEL_MISSING");
        }
        if (("HIGH".equals(risk) || "CRITICAL".equals(risk))
                && draft.path("effectModelRefs").isEmpty()) {
            blockers.add("HIGH_RISK_EFFECT_MODEL_MISSING");
        }
        return Set.copyOf(blockers);
    }

    private static void addIfBlank(
            Set<String> blockers, JsonNode parent, String field, String code) {
        if (parent.path(field).asText().isBlank()) {
            blockers.add(code);
        }
    }

    private static void addIfEmpty(
            Set<String> blockers, JsonNode parent, String field, String code) {
        if (parent.path(field).isEmpty()) {
            blockers.add(code);
        }
    }

    private static Set<String> canonicalSet(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(canonical(value)));
        return result;
    }

    private static String canonical(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value,
                "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.LEGACY_GRAPH_PROJECTION_CANONICALIZATION_FAILED");
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw invalid(INVALID);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Payload-free identity of one verified migration projection.
     *
     * @param graphName exact source Graph name
     * @param packageId stable projected Package id
     * @param projectionFingerprint verified canonical projection fingerprint
     * @param readinessGapCount number of gaps derived from Package readiness obligations
     * @param totalGapCount number of all readiness and migration-policy gaps
     * @param scopeFingerprint payload-free canonical fingerprint of the enterprise Scope
     */
    public record VerifiedProjection(
            String graphName,
            String packageId,
            String projectionFingerprint,
            int readinessGapCount,
            int totalGapCount,
            String scopeFingerprint
    ) {
    }

    /**
     * Payload-free identity of one verified projection catalog.
     *
     * @param itemCount number of verified projections
     * @param scopeFingerprint payload-free canonical fingerprint of the shared enterprise Scope
     */
    public record VerifiedCatalog(int itemCount, String scopeFingerprint) {
    }
}
