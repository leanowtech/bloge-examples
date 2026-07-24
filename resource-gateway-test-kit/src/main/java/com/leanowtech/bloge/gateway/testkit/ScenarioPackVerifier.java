package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server-independent verifier for a complete governed ScenarioPack closure.
 *
 * <p>The verifier applies the packaged strict Schemas, recomputes every content fingerprint,
 * resolves exact case and assertion references without implicit latest-version lookup, checks
 * enterprise scope and target equality, proves per-plan deterministic-service consistency,
 * rejects shared stateful checkpoints, and enforces active approval at an explicit verification
 * time. It never returns or logs business inputs, outputs, fixture values, Session payloads, or
 * credentials.</p>
 */
public final class ScenarioPackVerifier {
    /** Maximum canonical bytes admitted by the ScenarioPack producer protocol. */
    public static final int MAXIMUM_PACK_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical bytes admitted by the ScenarioCase producer protocol. */
    public static final int MAXIMUM_CASE_BYTES = 512 * 1024;
    /** Maximum canonical bytes admitted by the CaseHandlingAssertion producer protocol. */
    public static final int MAXIMUM_ASSERTION_BYTES = 128 * 1024;
    private static final Set<String> FORBIDDEN_PAYLOAD_KEYS = Set.of(
            "input", "output", "request", "response", "payload",
            "credential", "credentials", "entities", "businessValue");

    /** Creates an independent ScenarioPack closure verifier. */
    public ScenarioPackVerifier() {
    }

    /**
     * Bounded payload-free projection of one verified scenario closure.
     *
     * @param packId stable scenario-pack identity
     * @param revision exact immutable revision
     * @param fingerprint canonical pack fingerprint
     * @param targetCapabilityId exact target capability identity
     * @param caseIds ordered business-reporting case identities
     * @param caseTypes represented business intents
     * @param assertionCount complete resolved assertion count
     * @param statefulCaseCount cases carrying isolated Session checkpoints
     * @param faultCaseCount cases carrying explicit fixture fault rules
     * @param certificationRequired whether policy rejects non-certifiable evidence
     */
    public record VerifiedScenarioPack(
            String packId,
            long revision,
            String fingerprint,
            String targetCapabilityId,
            List<String> caseIds,
            List<String> caseTypes,
            int assertionCount,
            int statefulCaseCount,
            int faultCaseCount,
            boolean certificationRequired
    ) {
        /** Freezes the bounded verification projection. */
        public VerifiedScenarioPack {
            packId = required(packId, "packId");
            fingerprint = canonicalFingerprint(fingerprint, "fingerprint");
            targetCapabilityId = required(
                    targetCapabilityId, "targetCapabilityId");
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            caseTypes = caseTypes == null ? List.of() : List.copyOf(caseTypes);
            if (revision < 1 || caseIds.isEmpty()
                    || assertionCount < 1
                    || statefulCaseCount < 0
                    || faultCaseCount < 0) {
                throw new IllegalArgumentException(
                        "verified scenario projection is invalid");
            }
        }
    }

    /**
     * Verifies one exact pack and its complete resolved case/assertion closure.
     *
     * @param pack strict ScenarioPack payload
     * @param cases exact resolved ScenarioCase payloads; no extras are admitted
     * @param assertions exact resolved CaseHandlingAssertion payloads; no extras are admitted
     * @param verifiedAt explicit policy time used for approval and expiry checks
     * @return bounded payload-free verified projection
     */
    public VerifiedScenarioPack verify(
            JsonNode pack,
            List<JsonNode> cases,
            List<JsonNode> assertions,
            Instant verifiedAt) {
        if (verifiedAt == null) {
            throw invalid("RG.MIRROR.CLIENT.SCENARIO_TIME_INVALID");
        }
        requireSchema(
                pack,
                CapabilityMirrorProtocol.SCENARIO_PACK_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_PACK_SCHEMA_INVALID");
        requireFingerprint(
                pack, MAXIMUM_PACK_BYTES,
                "RG.MIRROR.CLIENT.SCENARIO_PACK_FINGERPRINT_INVALID");
        rejectPayloadKeys(pack);
        verifyLifecycle(pack, verifiedAt);

        Map<ReferenceKey, JsonNode> casesByRef = index(
                cases,
                CapabilityMirrorProtocol.SCENARIO_CASE_SCHEMA_RESOURCE,
                "SCENARIO_CASE",
                "caseId",
                MAXIMUM_CASE_BYTES,
                "RG.MIRROR.CLIENT.SCENARIO_CASE");
        Map<ReferenceKey, JsonNode> assertionsByRef = index(
                assertions,
                CapabilityMirrorProtocol.CASE_HANDLING_ASSERTION_SCHEMA_RESOURCE,
                "CASE_HANDLING_ASSERTION",
                "assertionId",
                MAXIMUM_ASSERTION_BYTES,
                "RG.MIRROR.CLIENT.SCENARIO_ASSERTION");

        List<JsonNode> orderedCases = resolveExact(
                pack.path("caseRefs"), casesByRef,
                "RG.MIRROR.CLIENT.SCENARIO_CASE_CLOSURE_INVALID");
        List<JsonNode> resolvedAssertions = resolveExact(
                pack.path("assertionRefs"), assertionsByRef,
                "RG.MIRROR.CLIENT.SCENARIO_ASSERTION_CLOSURE_INVALID");
        JsonNode packScope = pack.path("scope");
        JsonNode target = pack.path("targetCapabilityRef");
        Set<ReferenceKey> admittedAssertions = assertionsByRef.keySet();
        Map<ReferenceKey, JsonNode> servicesByPlan = new HashMap<>();
        Set<ReferenceKey> sessionCheckpoints = new HashSet<>();
        TreeSet<String> caseTypes = new TreeSet<>();
        List<String> caseIds = new ArrayList<>();
        int stateful = 0;
        int faults = 0;

        for (JsonNode scenarioCase : orderedCases) {
            requireFingerprint(
                    scenarioCase, MAXIMUM_CASE_BYTES,
                    "RG.MIRROR.CLIENT.SCENARIO_CASE_FINGERPRINT_INVALID");
            rejectPayloadKeys(scenarioCase);
            verifyLifecycle(scenarioCase, verifiedAt);
            requireEqual(
                    packScope, scenarioCase.path("scope"),
                    "RG.MIRROR.CLIENT.SCENARIO_SCOPE_INVALID");
            requireEqual(
                    target, scenarioCase.path("targetCapabilityRef"),
                    "RG.MIRROR.CLIENT.SCENARIO_TARGET_INVALID");
            verifyCaseShape(scenarioCase);
            verifyCaseAssertions(
                    scenarioCase.path("assertionRefs"), admittedAssertions);
            ReferenceKey plan = ReferenceKey.from(
                    scenarioCase.path("mirrorPlanRef"));
            JsonNode previousServices = servicesByPlan.putIfAbsent(
                    plan, scenarioCase.path("executionServices"));
            if (previousServices != null
                    && !previousServices.equals(
                    scenarioCase.path("executionServices"))) {
                throw invalid(
                        "RG.MIRROR.CLIENT.SCENARIO_EXECUTION_SERVICES_DRIFT");
            }
            JsonNode checkpoint =
                    scenarioCase.path("sessionCheckpointRef");
            if (!checkpoint.isNull()) {
                stateful++;
                if (!sessionCheckpoints.add(
                        ReferenceKey.from(checkpoint))) {
                    throw invalid(
                            "RG.MIRROR.CLIENT.SCENARIO_SESSION_NOT_ISOLATED");
                }
            }
            String caseType =
                    scenarioCase.path("caseType").asText();
            if ("FAULT".equals(caseType)) {
                faults++;
            }
            caseTypes.add(caseType);
            caseIds.add(scenarioCase.path("caseId").asText());
        }
        for (JsonNode assertion : resolvedAssertions) {
            requireFingerprint(
                    assertion, MAXIMUM_ASSERTION_BYTES,
                    "RG.MIRROR.CLIENT.SCENARIO_ASSERTION_FINGERPRINT_INVALID");
            rejectPayloadKeys(assertion);
            verifyLifecycle(assertion, verifiedAt);
            requireEqual(
                    packScope, assertion.path("scope"),
                    "RG.MIRROR.CLIENT.SCENARIO_SCOPE_INVALID");
            verifyAssertionShape(assertion);
        }
        verifyPackShape(pack, orderedCases.size());

        return new VerifiedScenarioPack(
                pack.path("packId").asText(),
                pack.path("revision").asLong(),
                pack.path("fingerprint").asText(),
                target.path("id").asText(),
                caseIds,
                List.copyOf(caseTypes),
                resolvedAssertions.size(),
                stateful,
                faults,
                pack.at("/policy/certificationRequired")
                        .asBoolean());
    }

    private static Map<ReferenceKey, JsonNode> index(
            List<JsonNode> values,
            String schemaResource,
            String kind,
            String idField,
            int maximumBytes,
            String codePrefix) {
        LinkedHashMap<ReferenceKey, JsonNode> indexed =
                new LinkedHashMap<>();
        for (JsonNode value : values == null ? List.<JsonNode>of() : values) {
            requireSchema(
                    value, schemaResource,
                    codePrefix + "_SCHEMA_INVALID");
            requireFingerprint(
                    value, maximumBytes,
                    codePrefix + "_FINGERPRINT_INVALID");
            ReferenceKey key = new ReferenceKey(
                    kind,
                    value.path(idField).asText(),
                    value.path("revision").asLong(),
                    value.path("fingerprint").asText());
            if (indexed.putIfAbsent(key, value) != null) {
                throw invalid(codePrefix + "_DUPLICATE");
            }
        }
        return Map.copyOf(indexed);
    }

    private static List<JsonNode> resolveExact(
            JsonNode references,
            Map<ReferenceKey, JsonNode> available,
            String failureCode) {
        List<JsonNode> resolved = new ArrayList<>();
        Set<ReferenceKey> consumed = new HashSet<>();
        for (JsonNode reference : references) {
            ReferenceKey key = ReferenceKey.from(reference);
            JsonNode value = available.get(key);
            if (value == null || !consumed.add(key)) {
                throw invalid(failureCode);
            }
            resolved.add(value);
        }
        if (consumed.size() != available.size()) {
            throw invalid(failureCode);
        }
        return List.copyOf(resolved);
    }

    private static void verifyCaseAssertions(
            JsonNode references, Set<ReferenceKey> admitted) {
        for (JsonNode reference : references) {
            if (!admitted.contains(ReferenceKey.from(reference))) {
                throw invalid(
                        "RG.MIRROR.CLIENT.SCENARIO_ASSERTION_CLOSURE_INVALID");
            }
        }
    }

    private static void verifyCaseShape(JsonNode value) {
        String type = value.path("caseType").asText();
        JsonNode faultRules = value.path("faultRuleRefs");
        JsonNode checkpoint = value.path("sessionCheckpointRef");
        if ("FAULT".equals(type) != !faultRules.isEmpty()
                || "STATE_TRANSITION".equals(type) && checkpoint.isNull()) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SCENARIO_CASE_SEMANTICS_INVALID");
        }
    }

    private static void verifyAssertionShape(JsonNode value) {
        String observation = value.path("observation").asText();
        JsonNode selector = value.path("selector");
        JsonNode expectation = value.path("expectation");
        boolean valid = switch (observation) {
            case "GRAPH_OUTPUT_VALUE" ->
                    text(selector, "path")
                            && text(expectation, "valueFingerprint");
            case "GRAPH_OUTPUT_SCHEMA" ->
                    text(selector, "path")
                            && text(expectation, "schemaFingerprint");
            case "NODE_STATUS" ->
                    text(selector, "nodeId")
                            && !expectation.path("statuses").isEmpty();
            case "EDGE_STATUS" ->
                    text(selector, "edgeId")
                            && !expectation.path("statuses").isEmpty();
            case "CAPABILITY_OCCURRENCE" ->
                    !selector.path("capabilityRef").isNull()
                            && (present(expectation, "minimumOccurrences")
                            || present(expectation, "maximumOccurrences"));
            case "INVOCATION_INPUT" ->
                    text(selector, "invocationSiteId")
                            && text(expectation, "valueFingerprint");
            case "ERROR" -> text(expectation, "errorCode");
            case "LATENCY_BUDGET" ->
                    present(expectation, "maximumDurationMillis");
            case "RETRY_BUDGET", "RESOURCE_BUDGET" ->
                    present(expectation, "maximumOccurrences");
            default -> present(expectation, "expectedBoolean")
                    || !expectation.path("statuses").isEmpty();
        };
        long minimum = expectation.path(
                "minimumOccurrences").asLong(-1);
        long maximum = expectation.path(
                "maximumOccurrences").asLong(-1);
        if (!valid || minimum >= 0 && maximum >= 0
                && minimum > maximum) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SCENARIO_ASSERTION_SEMANTICS_INVALID");
        }
    }

    private static void verifyPackShape(JsonNode pack, int caseCount) {
        JsonNode policy = pack.path("policy");
        Duration caseTimeout = duration(
                policy.path("caseTimeout"),
                "RG.MIRROR.CLIENT.SCENARIO_POLICY_INVALID");
        Duration totalTimeout = duration(
                policy.path("totalTimeout"),
                "RG.MIRROR.CLIENT.SCENARIO_POLICY_INVALID");
        if (caseCount > policy.path("maximumCases").asInt()
                || totalTimeout.compareTo(caseTimeout) < 0
                || pack.path("writeEffectRefs").isEmpty()
                != pack.path("stateModelRefs").isEmpty()) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SCENARIO_POLICY_INVALID");
        }
    }

    private static void verifyLifecycle(
            JsonNode value, Instant verifiedAt) {
        JsonNode scope = value.path("scope");
        JsonNode provenance = value.path("provenance");
        if (!scope.path("tenantId").asText().equals(
                provenance.path("tenantId").asText())) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SCENARIO_SCOPE_INVALID");
        }
        Instant createdAt = instant(
                value.path("createdAt"),
                "RG.MIRROR.CLIENT.SCENARIO_TIME_INVALID");
        JsonNode approvedAtValue =
                provenance.path("approvedAt");
        Instant approvedAt = approvedAtValue.isNull()
                ? null : instant(
                approvedAtValue,
                "RG.MIRROR.CLIENT.SCENARIO_TIME_INVALID");
        JsonNode expiresAtValue =
                provenance.path("expiresAt");
        Instant expiresAt = expiresAtValue.isNull()
                ? null : instant(
                expiresAtValue,
                "RG.MIRROR.CLIENT.SCENARIO_TIME_INVALID");
        boolean active = "ACTIVE".equals(
                value.path("lifecycle").asText());
        if (approvedAt != null && createdAt.isAfter(approvedAt)
                || active && (approvedAt == null
                || provenance.path("approvedBy").asText().isBlank()
                || !provenance.path("revocationRef").asText().isBlank()
                || expiresAt != null
                && !verifiedAt.isBefore(expiresAt))) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SCENARIO_LIFECYCLE_INVALID");
        }
    }

    private static void requireSchema(
            JsonNode value, String resource, String failureCode) {
        CapabilityMirrorSchemaValidator.require(
                value, resource, failureCode);
    }

    private static void requireFingerprint(
            JsonNode value, int maximumBytes, String failureCode) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(failureCode);
        }
        ObjectNode material = object.deepCopy();
        material.put("fingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes).equals(
                value.path("fingerprint").asText())) {
            throw invalid(failureCode);
        }
    }

    private static void rejectPayloadKeys(JsonNode value) {
        if (containsForbiddenKey(value)) {
            throw invalid(
                    "RG.MIRROR.CLIENT.SCENARIO_PAYLOAD_FORBIDDEN");
        }
    }

    private static boolean containsForbiddenKey(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (FORBIDDEN_PAYLOAD_KEYS.contains(field.getKey())
                        || containsForbiddenKey(field.getValue())) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsForbiddenKey(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void requireEqual(
            JsonNode expected, JsonNode actual, String failureCode) {
        if (!expected.equals(actual)) {
            throw invalid(failureCode);
        }
    }

    private static boolean text(JsonNode value, String field) {
        return !value.path(field).asText().isBlank();
    }

    private static boolean present(JsonNode value, String field) {
        return value.has(field) && !value.path(field).isNull();
    }

    private static Instant instant(
            JsonNode value, String failureCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            throw invalid(failureCode);
        }
    }

    private static Duration duration(
            JsonNode value, String failureCode) {
        try {
            Duration result = Duration.parse(value.asText());
            if (result.isZero() || result.isNegative()) {
                throw invalid(failureCode);
            }
            return result;
        } catch (DateTimeParseException invalid) {
            throw invalid(failureCode);
        }
    }

    private static String required(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String canonicalFingerprint(
            String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static IllegalArgumentException invalid(
            String code) {
        return new IllegalArgumentException(code);
    }

    private record ReferenceKey(
            String kind,
            String id,
            long revision,
            String fingerprint
    ) {
        private ReferenceKey {
            kind = required(kind, "kind");
            id = required(id, "id");
            fingerprint = canonicalFingerprint(
                    fingerprint, "fingerprint");
            if (revision < 1) {
                throw new IllegalArgumentException(
                        "revision must be positive");
            }
        }

        private static ReferenceKey from(JsonNode value) {
            return new ReferenceKey(
                    value.path("kind").asText(),
                    value.path("id").asText(),
                    value.path("revision").asLong(),
                    value.path("fingerprint").asText());
        }
    }
}
