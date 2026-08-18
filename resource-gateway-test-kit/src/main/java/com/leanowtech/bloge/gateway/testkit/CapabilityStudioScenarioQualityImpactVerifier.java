package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent offline verifier for the GP-09 Scenario Quality and Impact projection.
 *
 * <p>The projection is a metadata-only quality report. It proves that the declared scenario
 * inventory, quality counters and impact graph are internally consistent; it does not prove
 * semantic desensitization of source data. Payload and execution material must remain in their
 * owning authorities.</p>
 */
public final class CapabilityStudioScenarioQualityImpactVerifier {
    /** Maximum raw or canonical representation accepted by this verifier. */
    public static final int MAXIMUM_PROJECTION_BYTES = 8 * 1024 * 1024;
    /** Wire schema version verified by this class. */
    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.scenario-quality-impact.v1";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> SCOPE_FIELDS = List.of(
            "tenantId", "organizationId", "projectId", "environmentId", "region");
    private static final Set<String> FORBIDDEN_FIELD_TOKENS = Set.of(
            "payload", "request", "response", "body", "fixture", "mock", "replay",
            "secret", "password", "credential", "authorization", "accesstoken",
            "apikey", "privatekey", "cleartext", "plaintext");

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** The projection passed the strict schema and all cross-field checks. */
        NONE,
        /** The wire document violates schema, payload boundary, or size rules. */
        SCHEMA,
        /** The document is schema-valid but contradicts its declared semantics. */
        SEMANTIC
    }

    /**
     * Payload-free result suitable for CI and governance logs.
     *
     * @param failureKind stable schema or semantic classification
     * @param checks completed check groups
     * @param errorCode stable protocol error code, or {@code null} on success
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable protocol result. */
        public VerificationResult {
            if (failureKind == null) {
                throw new IllegalArgumentException("failureKind is required");
            }
            checks = checks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(checks));
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("errorCode is not a protocol code");
            }
        }

        /**
         * Returns true only when every protocol check passed.
         *
         * @return whether the projection is fully verified
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    private record GraphEvaluation(
            VerificationResult result,
            Map<String, Integer> impactedAssetCounts,
            Set<String> impactedAssetRefs,
            int orphanCaseCount,
            Map<String, Integer> nodeCounts,
            Set<String> dependencyClosedCases) {
    }

    private record DeclaredNode(String kind, String status) {
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioScenarioQualityImpactVerifier() {
    }

    /**
     * Verifies a decoded GP-09 projection.
     *
     * @param projection decoded quality and impact projection
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode projection) {
        VerificationResult schema = verifySchema(projection);
        if (!schema.verified()) {
            return schema;
        }
        VerificationResult payload = verifyPayloadBoundary(projection);
        if (!payload.verified()) {
            return payload;
        }
        VerificationResult fingerprint = verifyProjectionFingerprint(projection);
        if (!fingerprint.verified()) {
            return fingerprint;
        }
        VerificationResult scope = verifyScopeClosure(projection);
        if (!scope.verified()) {
            return scope;
        }
        VerificationResult order = verifyStableOrderAndUniqueness(projection);
        if (!order.verified()) {
            return order;
        }
        VerificationResult cases = verifyCases(projection);
        if (!cases.verified()) {
            return cases;
        }
        GraphEvaluation graph = verifyImpactGraph(projection);
        if (!graph.result().verified()) {
            return graph.result();
        }
        VerificationResult summary = verifySummary(projection, graph);
        if (!summary.verified()) {
            return summary;
        }
        VerificationResult readiness = verifyReadiness(projection, graph);
        if (!readiness.verified()) {
            return readiness;
        }
        return valid(
                "SCHEMA",
                "PAYLOAD_BOUNDARY",
                "PROJECTION_FINGERPRINT",
                "SCOPE_EXACT_REFERENCE_CLOSURE",
                "STABLE_ORDER_UNIQUENESS",
                "CASE_CLOSURE",
                "IMPACT_GRAPH_CLOSURE",
                "SUMMARY_CARDINALITY",
                "READINESS_SEMANTICS");
    }

    /**
     * Verifies a UTF-8 JSON document, applying the byte limit before parsing.
     *
     * @param wireBytes UTF-8 JSON document bytes
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_PROJECTION_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (JsonProcessingException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_INVALID_JSON");
        } catch (java.io.IOException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode projection) {
        if (projection == null
                || !projection.isObject()
                || !SCHEMA_VERSION.equals(projection.path("schemaVersion").asText())) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_INVALID");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    projection,
                    CapabilityStudioSchemaSupport.SCENARIO_QUALITY_IMPACT_RESOURCE).isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA");
    }

    private static VerificationResult verifyPayloadBoundary(JsonNode projection) {
        if (containsForbiddenField(projection)) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_PAYLOAD_FIELD_FORBIDDEN");
        }
        return valid("PAYLOAD_BOUNDARY");
    }

    private static boolean containsForbiddenField(JsonNode value) {
        if (value == null || value.isValueNode()) {
            return false;
        }
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!"payloadexposure".equals(normalized)
                        && !"maskingstatus".equals(normalized)
                        && FORBIDDEN_FIELD_TOKENS.stream().anyMatch(normalized::contains)) {
                    return true;
                }
                if (containsForbiddenField(field.getValue())) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsForbiddenField(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static VerificationResult verifyProjectionFingerprint(JsonNode projection) {
        ObjectNode material = projection.deepCopy();
        material.putNull("projectionFingerprint");
        final String expected;
        try {
            expected = EvidenceVerificationSupport.sha256Bounded(
                    material, MAXIMUM_PROJECTION_BYTES);
        } catch (IllegalArgumentException tooLarge) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SIZE_LIMIT");
        }
        if (!expected.equals(projection.path("projectionFingerprint").asText())) {
            return semanticFailure(
                    "PROJECTION_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_PROJECTION_FINGERPRINT_MISMATCH");
        }
        return valid("PROJECTION_FINGERPRINT");
    }

    private static VerificationResult verifyScopeClosure(JsonNode projection) {
        JsonNode datasetRef = projection.path("datasetRef");
        List<JsonNode> references = new ArrayList<>();
        references.add(datasetRef);
        references.add(projection.path("targetRef"));
        for (JsonNode dataCase : projection.path("cases")) {
            references.add(dataCase.path("caseRef"));
            addReference(references, dataCase.path("sourceRef"));
            addReference(references, dataCase.path("oracleRef"));
            dataCase.path("contractRefs").forEach(references::add);
            dataCase.path("dependencyRefs").forEach(references::add);
        }
        for (JsonNode node : projection.path("impactGraph").path("nodes")) {
            references.add(node.path("ref"));
        }
        for (JsonNode reference : references) {
            if (!sameScope(datasetRef.path("scope"), reference.path("scope"))) {
                return semanticFailure(
                        "SCOPE_EXACT_REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CROSS_SCOPE_REFERENCE");
            }
        }
        return valid("SCOPE_EXACT_REFERENCE_CLOSURE");
    }

    private static void addReference(List<JsonNode> references, JsonNode candidate) {
        if (candidate != null && !candidate.isNull()) {
            references.add(candidate);
        }
    }

    private static VerificationResult verifyStableOrderAndUniqueness(JsonNode projection) {
        if (!isSortedUnique(projection.path("cases"),
                Comparator.comparing(node -> node.path("caseRef").path("id").asText()))) {
            return semanticFailure(
                    "STABLE_ORDER_UNIQUENESS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_ORDER_INVALID");
        }
        Set<String> blockerCodes = new HashSet<>();
        for (JsonNode blocker : projection.path("admission").path("blockers")) {
            if (!blockerCodes.add(blocker.path("code").asText())) {
                return semanticFailure(
                        "STABLE_ORDER_UNIQUENESS",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_DUPLICATE_BLOCKER");
            }
        }
        for (JsonNode dataCase : projection.path("cases")) {
            if (!isSortedUnique(dataCase.path("contractRefs"),
                    CapabilityStudioScenarioQualityImpactVerifier::compareRefs)
                    || !isSortedUnique(dataCase.path("dependencyRefs"),
                    CapabilityStudioScenarioQualityImpactVerifier::compareRefs)) {
                return semanticFailure(
                        "STABLE_ORDER_UNIQUENESS",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_REFERENCE_ORDER_INVALID");
            }
        }
        if (!isSortedUnique(projection.path("impactGraph").path("nodes"),
                Comparator.comparing((JsonNode node) -> node.path("kind").asText())
                        .thenComparing(node -> node.path("id").asText()))) {
            return semanticFailure(
                    "STABLE_ORDER_UNIQUENESS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_ORDER_INVALID");
        }
        if (!isSortedUnique(projection.path("impactGraph").path("edges"),
                Comparator.comparing((JsonNode edge) -> edge.path("source").asText())
                        .thenComparing(edge -> edge.path("target").asText())
                        .thenComparing(edge -> edge.path("relation").asText())
                        .thenComparing(edge -> edge.path("id").asText()))) {
            return semanticFailure(
                    "STABLE_ORDER_UNIQUENESS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_EDGE_ORDER_INVALID");
        }
        return valid("STABLE_ORDER_UNIQUENESS");
    }

    private static int compareRefs(JsonNode left, JsonNode right) {
        int kind = left.path("kind").asText().compareTo(right.path("kind").asText());
        if (kind != 0) {
            return kind;
        }
        int id = left.path("id").asText().compareTo(right.path("id").asText());
        if (id != 0) {
            return id;
        }
        int revision = Integer.compare(
                left.path("revision").asInt(), right.path("revision").asInt());
        if (revision != 0) {
            return revision;
        }
        return left.path("fingerprint").asText()
                .compareTo(right.path("fingerprint").asText());
    }

    private static boolean isSortedUnique(JsonNode array, Comparator<JsonNode> comparator) {
        JsonNode previous = null;
        for (JsonNode current : array) {
            if (previous != null && comparator.compare(previous, current) >= 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static VerificationResult verifyCases(JsonNode projection) {
        for (JsonNode dataCase : projection.path("cases")) {
            String caseId = dataCase.path("caseRef").path("id").asText();
            if (dataCase.path("owner").isNull()) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_OWNER_MISSING");
            }
            boolean sourceRefMissing = dataCase.path("sourceRef").isNull();
            boolean sourceMissing = dataCase.path("source").isNull();
            if (sourceRefMissing != sourceMissing || sourceRefMissing) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_SOURCE_MISSING");
            }
            boolean oracleRefMissing = dataCase.path("oracleRef").isNull();
            boolean oracleMissing = dataCase.path("oracle").isNull();
            if (oracleRefMissing != oracleMissing || oracleRefMissing) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_ORACLE_MISSING");
            }
            if (dataCase.path("contractRefs").isEmpty()) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_CONTRACT_MISSING");
            }
            if (dataCase.path("dependencyRefs").isEmpty()) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_DEPENDENCY_MISSING");
            }
            if ("STALE".equals(dataCase.path("lifecycle").asText())
                    && !"STALE".equals(dataCase.path("freshnessStatus").asText())) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_FRESHNESS_MISMATCH");
            }
            if (caseId.isEmpty()) {
                return semanticFailure(
                        "CASE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_REFERENCE_INVALID");
            }
        }
        return valid("CASE_CLOSURE");
    }

    private static GraphEvaluation verifyImpactGraph(JsonNode projection) {
        Map<String, DeclaredNode> declaredRefs = declaredRefs(projection);
        Map<String, JsonNode> nodes = new HashMap<>();
        Map<String, String> nodeIdsByRef = new HashMap<>();
        Map<String, Integer> nodeCounts = new HashMap<>();
        String targetNodeId = null;
        String declaredTargetNodeId = nodeId("TARGET", projection.path("targetRef"));
        for (JsonNode node : projection.path("impactGraph").path("nodes")) {
            String id = node.path("id").asText();
            String kind = node.path("kind").asText();
            JsonNode ref = node.path("ref");
            String exactRefKey = refKey(ref);
            if (!id.equals(nodeId(kind, ref))) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_ID_INVALID");
            }
            if (!nodeKindMatchesRef(kind, ref.path("kind").asText())) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_REF_KIND_INVALID");
            }
            if (nodes.put(id, node) != null
                    || nodeIdsByRef.put(exactRefKey, id) != null) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_DUPLICATE_NODE");
            }
            if ("TARGET".equals(kind)) {
                if (targetNodeId != null
                        || !declaredTargetNodeId.equals(id)
                        || !refKey(projection.path("targetRef")).equals(exactRefKey)
                        || !"DRAFT".equals(node.path("status").asText())) {
                    return graphFailure(
                            "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_TARGET_NODE_INVALID");
                }
                targetNodeId = id;
            } else {
                DeclaredNode declared = declaredRefs.get(exactRefKey);
                if (declared == null) {
                    return graphFailure(
                            "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_UNDECLARED_NODE");
                }
                if (!declared.kind().equals(kind)) {
                    return graphFailure(
                            "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_DECLARATION_MISMATCH");
                }
                if (!declared.status().equals(node.path("status").asText())) {
                    return graphFailure(
                            "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_STATUS_INVALID");
                }
            }
            nodeCounts.merge(kind, 1, Integer::sum);
        }
        for (Map.Entry<String, DeclaredNode> declared : declaredRefs.entrySet()) {
            String id = nodeIdsByRef.get(declared.getKey());
            if (id == null || !id.startsWith(declared.getValue().kind() + ":")) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_DECLARED_NODE_MISSING");
            }
        }
        if (targetNodeId == null || nodeCounts.getOrDefault("TARGET", 0) != 1) {
            return graphFailure(
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_TARGET_NODE_INVALID");
        }

        String datasetNodeId = nodeId("DATASET", projection.path("datasetRef"));
        Map<String, JsonNode> casesByNodeId = new HashMap<>();
        Set<String> allowedEdgePairs = new HashSet<>();
        for (JsonNode dataCase : projection.path("cases")) {
            JsonNode caseRef = dataCase.path("caseRef");
            String caseNodeId = nodeId("DATA_CASE", caseRef);
            casesByNodeId.put(caseNodeId, dataCase);
            allowedEdgePairs.add(edgeKey(datasetNodeId, caseNodeId, "CONTAINS"));
            allowedEdgePairs.add(edgeKey(
                    caseNodeId, nodeId("SOURCE", dataCase.path("sourceRef")), "SOURCED_BY"));
            allowedEdgePairs.add(edgeKey(
                    caseNodeId, nodeId("ORACLE", dataCase.path("oracleRef")), "CHECKED_BY"));
            for (JsonNode contract : dataCase.path("contractRefs")) {
                allowedEdgePairs.add(edgeKey(
                        caseNodeId, nodeId("CONTRACT", contract), "VALIDATES"));
            }
            for (JsonNode dependency : dataCase.path("dependencyRefs")) {
                allowedEdgePairs.add(edgeKey(
                        caseNodeId, nodeId("DEPENDENCY", dependency), "CONTROLS"));
            }
            allowedEdgePairs.add(edgeKey(caseNodeId, targetNodeId, "VALIDATES_TARGET"));
        }

        Set<String> edgeIds = new HashSet<>();
        Set<String> edgePairs = new HashSet<>();
        Map<String, Set<String>> targetsByCase = new HashMap<>();
        for (JsonNode edge : projection.path("impactGraph").path("edges")) {
            String source = edge.path("source").asText();
            String target = edge.path("target").asText();
            String relation = edge.path("relation").asText();
            String edgeKey = edgeKey(source, target, relation);
            if (!edgeIds.add(edge.path("id").asText())
                    || !edgePairs.add(edgeKey)
                    || !nodes.containsKey(source)
                    || !nodes.containsKey(target)) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_EDGE_CLOSURE_INVALID");
            }
            if (!validRelation(nodes.get(source).path("kind").asText(),
                    nodes.get(target).path("kind").asText(), relation)
                    || !allowedEdgePairs.contains(edgeKey)) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_EDGE_RELATION_INVALID");
            }
            if ("VALIDATES_TARGET".equals(relation)) {
                targetsByCase.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
            }
        }

        Map<String, Integer> impactedCounts = new HashMap<>();
        Set<String> globallyImpactedRefs = new HashSet<>();
        Set<String> dependencyClosedCases = new HashSet<>();
        int orphanCases = 0;
        for (Map.Entry<String, JsonNode> entry : casesByNodeId.entrySet()) {
            String caseNodeId = entry.getKey();
            JsonNode dataCase = entry.getValue();
            if (!edgePairs.contains(edgeKey(datasetNodeId, caseNodeId, "CONTAINS"))) {
                orphanCases++;
            }
            if (!edgePairs.contains(edgeKey(
                    caseNodeId, nodeId("SOURCE", dataCase.path("sourceRef")), "SOURCED_BY"))
                    || !edgePairs.contains(edgeKey(
                    caseNodeId, nodeId("ORACLE", dataCase.path("oracleRef")), "CHECKED_BY"))) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_EVIDENCE_EDGE_MISSING");
            }
            Set<String> impactedRefs = new HashSet<>();
            for (JsonNode contract : dataCase.path("contractRefs")) {
                if (!edgePairs.contains(edgeKey(
                        caseNodeId, nodeId("CONTRACT", contract), "VALIDATES"))) {
                    return graphFailure(
                            "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CONTRACT_CLOSURE_INVALID");
                }
                impactedRefs.add(refKey(contract));
            }
            boolean dependenciesClosed = true;
            for (JsonNode dependency : dataCase.path("dependencyRefs")) {
                if (!edgePairs.contains(edgeKey(
                        caseNodeId, nodeId("DEPENDENCY", dependency), "CONTROLS"))) {
                    dependenciesClosed = false;
                }
                impactedRefs.add(refKey(dependency));
            }
            if (!dependenciesClosed) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_RUNTIME_CLOSURE_INVALID");
            }
            dependencyClosedCases.add(dataCase.path("caseRef").path("id").asText());
            Set<String> targetIds = targetsByCase.getOrDefault(caseNodeId, Set.of());
            if (targetIds.isEmpty()) {
                return graphFailure(
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_TARGET_CLOSURE_INVALID");
            }
            for (String targetId : targetIds) {
                impactedRefs.add(refKey(nodes.get(targetId).path("ref")));
            }
            impactedCounts.put(
                    dataCase.path("caseRef").path("id").asText(), impactedRefs.size());
            globallyImpactedRefs.addAll(impactedRefs);
        }
        return new GraphEvaluation(
                valid("IMPACT_GRAPH_CLOSURE"),
                impactedCounts,
                globallyImpactedRefs,
                orphanCases,
                nodeCounts,
                dependencyClosedCases);
    }

    private static Map<String, DeclaredNode> declaredRefs(JsonNode projection) {
        Map<String, DeclaredNode> declared = new HashMap<>();
        declared.put(refKey(projection.path("datasetRef")), new DeclaredNode("DATASET", "BLOCKED"));
        declared.put(refKey(projection.path("targetRef")), new DeclaredNode("TARGET", "DRAFT"));
        for (JsonNode dataCase : projection.path("cases")) {
            declared.put(refKey(dataCase.path("caseRef")),
                    new DeclaredNode("DATA_CASE", dataCase.path("lifecycle").asText()));
            declared.put(refKey(dataCase.path("sourceRef")), new DeclaredNode("SOURCE", "BLOCKED"));
            declared.put(refKey(dataCase.path("oracleRef")), new DeclaredNode("ORACLE", "DRAFT"));
            for (JsonNode contract : dataCase.path("contractRefs")) {
                declared.put(refKey(contract), new DeclaredNode("CONTRACT", "DRAFT"));
            }
            for (JsonNode dependency : dataCase.path("dependencyRefs")) {
                declared.put(refKey(dependency), new DeclaredNode("DEPENDENCY", "DRAFT"));
            }
        }
        return declared;
    }

    private static String edgeKey(String source, String target, String relation) {
        return source + "|" + target + "|" + relation;
    }

    private static String nodeId(String nodeKind, JsonNode ref) {
        return nodeKind + ":" + ref.path("id").asText();
    }

    private static boolean validRelation(String sourceKind, String targetKind, String relation) {
        return switch (relation) {
            case "CONTAINS" -> "DATASET".equals(sourceKind) && "DATA_CASE".equals(targetKind);
            case "SOURCED_BY" -> "DATA_CASE".equals(sourceKind) && "SOURCE".equals(targetKind);
            case "CHECKED_BY" -> "DATA_CASE".equals(sourceKind) && "ORACLE".equals(targetKind);
            case "VALIDATES" -> "DATA_CASE".equals(sourceKind) && "CONTRACT".equals(targetKind);
            case "CONTROLS" -> "DATA_CASE".equals(sourceKind) && "DEPENDENCY".equals(targetKind);
            case "VALIDATES_TARGET" -> "DATA_CASE".equals(sourceKind) && "TARGET".equals(targetKind);
            default -> false;
        };
    }

    private static boolean nodeKindMatchesRef(String nodeKind, String refKind) {
        return switch (nodeKind) {
            case "SOURCE" -> "SOURCE".equals(refKind);
            case "ORACLE" -> "ORACLE".equals(refKind);
            case "DEPENDENCY" -> "API".equals(refKind);
            case "TARGET" -> "TOOL".equals(refKind) || "FEATURE".equals(refKind);
            default -> nodeKind.equals(refKind);
        };
    }

    private static VerificationResult verifySummary(JsonNode projection, GraphEvaluation graph) {
        JsonNode summary = projection.path("summary");
        int caseCount = projection.path("cases").size();
        if (summary.path("caseCount").asInt() != caseCount
                || summary.path("sourceCount").asInt() != graph.nodeCounts().getOrDefault("SOURCE", 0)
                || summary.path("oracleCount").asInt() != graph.nodeCounts().getOrDefault("ORACLE", 0)
                || summary.path("contractCount").asInt() != graph.nodeCounts().getOrDefault("CONTRACT", 0)
                || summary.path("dependencyCount").asInt() != graph.nodeCounts().getOrDefault("DEPENDENCY", 0)
                || summary.path("targetCount").asInt() != graph.nodeCounts().getOrDefault("TARGET", 0)
                || summary.path("impactedAssetCount").asInt() != graph.impactedAssetRefs().size()
                || summary.path("orphanCaseCount").asInt() != graph.orphanCaseCount()) {
            return semanticFailure(
                    "SUMMARY_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SUMMARY_MISMATCH");
        }
        for (JsonNode dataCase : projection.path("cases")) {
            String caseId = dataCase.path("caseRef").path("id").asText();
            if (dataCase.path("impactedAssetCount").asInt()
                    != graph.impactedAssetCounts().getOrDefault(caseId, -1)) {
                return semanticFailure(
                        "SUMMARY_CARDINALITY",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_IMPACT_COUNT_MISMATCH");
            }
        }
        return valid("SUMMARY_CARDINALITY");
    }

    private static VerificationResult verifyReadiness(JsonNode projection, GraphEvaluation graph) {
        JsonNode admission = projection.path("admission");
        JsonNode quality = projection.path("quality");
        int caseCount = projection.path("cases").size();
        int active = countCases(projection, "ACTIVE");
        int draft = countCases(projection, "DRAFT");
        int stale = countCases(projection, "STALE");
        if (active + draft + stale != caseCount
                || admission.path("activeCaseCount").asInt() != active
                || admission.path("draftCaseCount").asInt() != draft
                || admission.path("staleCaseCount").asInt() != stale) {
            return semanticFailure(
                    "READINESS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_ADMISSION_COUNTS_MISMATCH");
        }
        String expectedFreshness = expectedFreshness(projection);
        if (!expectedFreshness.equals(quality.path("freshnessStatus").asText())) {
            return semanticFailure(
                    "READINESS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_FRESHNESS_MISMATCH");
        }
        if (!admission.path("status").asText().equals(quality.path("status").asText())) {
            return semanticFailure(
                    "READINESS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_STATUS_MISMATCH");
        }
        int owners = 0;
        int sources = 0;
        int oracles = 0;
        int contracts = 0;
        int dependencies = 0;
        for (JsonNode dataCase : projection.path("cases")) {
            owners += dataCase.path("owner").isNull() ? 0 : 1;
            sources += dataCase.path("sourceRef").isNull() || dataCase.path("source").isNull() ? 0 : 1;
            oracles += dataCase.path("oracleRef").isNull() || dataCase.path("oracle").isNull() ? 0 : 1;
            contracts += dataCase.path("contractRefs").isEmpty() ? 0 : 1;
            dependencies += graph.dependencyClosedCases().contains(
                    dataCase.path("caseRef").path("id").asText()) ? 1 : 0;
        }
        if (!coverageEquals(quality, "ownerCoveragePercent", owners, caseCount)
                || !coverageEquals(quality, "sourceCoveragePercent", sources, caseCount)
                || !coverageEquals(quality, "oracleCoveragePercent", oracles, caseCount)
                || !coverageEquals(quality, "contractCoveragePercent", contracts, caseCount)
                || !coverageEquals(quality, "behaviorClosurePercent", dependencies, caseCount)) {
            return semanticFailure(
                    "READINESS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_COVERAGE_MISMATCH");
        }
        Set<String> blockerCodes = new HashSet<>();
        for (JsonNode blocker : admission.path("blockers")) {
            blockerCodes.add(blocker.path("code").asText());
        }
        if ("UNVERIFIED".equals(expectedFreshness)
                && !blockerCodes.contains("FRESHNESS_EVIDENCE_MISSING")) {
            return semanticFailure(
                    "READINESS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_FRESHNESS_EVIDENCE_MISSING");
        }
        if ("BLOCKED".equals(admission.path("status").asText())
                && admission.path("blockers").isEmpty()) {
            return semanticFailure(
                    "READINESS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_BLOCKED_WITHOUT_BLOCKER");
        }
        if ("READY".equals(admission.path("status").asText())) {
            if (active == 0
                    || !admission.path("blockers").isEmpty()
                    || !"CURRENT".equals(expectedFreshness)
                    || !allCoverageIs100(quality)
                    || graph.orphanCaseCount() != 0) {
                return semanticFailure(
                        "READINESS_SEMANTICS",
                        "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_READY_CONTRADICTION");
            }
        }
        return valid("READINESS_SEMANTICS");
    }

    private static int countCases(JsonNode projection, String lifecycle) {
        int count = 0;
        for (JsonNode dataCase : projection.path("cases")) {
            if (lifecycle.equals(dataCase.path("lifecycle").asText())) {
                count++;
            }
        }
        return count;
    }

    private static String expectedFreshness(JsonNode projection) {
        boolean unverified = false;
        for (JsonNode dataCase : projection.path("cases")) {
            String freshness = dataCase.path("freshnessStatus").asText();
            if ("STALE".equals(freshness)) {
                return "STALE";
            }
            if ("UNVERIFIED".equals(freshness)) {
                unverified = true;
            }
        }
        return unverified ? "UNVERIFIED" : "CURRENT";
    }

    private static boolean coverageEquals(JsonNode quality, String field, int covered, int total) {
        int expected = total == 0 ? 0 : (int) Math.round(covered * 100.0 / total);
        return quality.path(field).asInt(Integer.MIN_VALUE) == expected;
    }

    private static boolean allCoverageIs100(JsonNode quality) {
        return quality.path("ownerCoveragePercent").asInt() == 100
                && quality.path("sourceCoveragePercent").asInt() == 100
                && quality.path("oracleCoveragePercent").asInt() == 100
                && quality.path("contractCoveragePercent").asInt() == 100
                && quality.path("behaviorClosurePercent").asInt() == 100;
    }

    private static String refKey(JsonNode ref) {
        JsonNode scope = ref.path("scope");
        return ref.path("kind").asText() + "|" + ref.path("id").asText()
                + "|" + ref.path("revision").asInt()
                + "|" + ref.path("fingerprint").asText()
                + "|" + ref.path("authority").asText()
                + "|" + scope.path("tenantId").asText()
                + "|" + scope.path("organizationId").asText()
                + "|" + scope.path("projectId").asText()
                + "|" + scope.path("environmentId").asText()
                + "|" + scope.path("region").asText();
    }

    private static boolean sameScope(JsonNode left, JsonNode right) {
        for (String field : SCOPE_FIELDS) {
            if (!left.path(field).asText().equals(right.path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private static GraphEvaluation graphFailure(String code) {
        return new GraphEvaluation(semanticFailure("IMPACT_GRAPH_CLOSURE", code),
                Map.of(), Set.of(), 0, Map.of(), Set.of());
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE, Set.of(checks), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode);
    }
}
