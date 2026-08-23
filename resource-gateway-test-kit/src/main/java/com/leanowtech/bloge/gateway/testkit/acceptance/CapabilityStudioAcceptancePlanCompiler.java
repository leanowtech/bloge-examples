package com.leanowtech.bloge.gateway.testkit.acceptance;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/**
 * Compile-only compiler for Capability Studio Acceptance Plans.
 * <p>
 * Does NOT execute primitives, does NOT generate PASS/ACCEPTED,
 * does NOT modify formalPassCount. Produces a lossless IR (CompiledAcceptancePlan)
 * from caller-bounded plan and catalog bytes.
 * <p>
 * Thread-safe for concurrent compilation of independent inputs.
 */
public final class CapabilityStudioAcceptancePlanCompiler {

    private static final int MAX_SIZE_BYTES = 1 << 20; // 1 MiB

    private final CapabilityStudioAcceptancePlanProtocol protocol;
    private final CapabilityStudioAcceptancePrimitiveRegistry registry;

    /**
     * Factory: constructs a compiler with built-in registry and protocol.
     *
     * @return a new compiler using the built-in primitive registry and protocol profile
     */
    public static CapabilityStudioAcceptancePlanCompiler withBuiltInInternals() {
        return new CapabilityStudioAcceptancePlanCompiler();
    }
    CapabilityStudioAcceptancePlanCompiler() {
        this.protocol  = new CapabilityStudioAcceptancePlanProtocol();
        this.registry = new CapabilityStudioAcceptancePrimitiveRegistry(protocol.getProfileNode());
    }

    /**
     * Compiles a bounded acceptance plan and its contract catalog.
     *
     * @param planBytes     AcceptancePlan bytes, ≤ 1 MiB (defensive clone made)
     * @param catalogBytes  ContractCatalog bytes, ≤ 1 MiB (defensive clone made)
     * @return immutable CompilationResult with compiled plan bytes and Domain2 fingerprint
     * @throws CompilerException on any INVALID reason
     * @throws NullPointerException if either argument is null
     */
    public CompilationResult compile(byte[] planBytes, byte[] catalogBytes) throws CompilerException {
        // 0. Defensive copies
        planBytes    = Objects.requireNonNull(planBytes,    "planBytes").clone();
        catalogBytes = Objects.requireNonNull(catalogBytes, "catalogBytes").clone();

        // 1. Size bounds
        if (planBytes.length > MAX_SIZE_BYTES)
            bad(CompilerException.ReasonCode.INVALID_COLLECTION_SIZE, "/planBytes",
                    "planBytes exceeds 1 MiB limit: " + planBytes.length);
        if (catalogBytes.length > MAX_SIZE_BYTES)
            bad(CompilerException.ReasonCode.INVALID_COLLECTION_SIZE, "/catalogBytes",
                    "catalogBytes exceeds 1 MiB limit: " + catalogBytes.length);

        // 2. Strict JSON parse (duplicate-key detection)
        JsonNode planNode    = protocol.parseStrict(planBytes,    "plan");
        JsonNode catalogNode = protocol.parseStrict(catalogBytes, "catalog");

        // 3. Draft2020-12 schema validation (schema first, before semantic IDs)
        protocol.validateSchema(
                "bloge.capability-studio.acceptance-plan.v1",    planBytes,    "/planBytes");
        protocol.validateSchema(
                "bloge.capability-studio.contract-catalog.v1", catalogBytes, "/catalogBytes");

        // 4. catalogId binding - use INVALID_CATALOG_SEMANTICS per spec
        String planCatId   = planNode.get("catalogId").asText();
        String catalogCatId = catalogNode.get("catalogId").asText();
        if (!planCatId.equals(catalogCatId))
            bad(CompilerException.ReasonCode.INVALID_CATALOG_SEMANTICS, "/catalogId",
                    "Plan catalogId '" + planCatId + "' != Catalog catalogId '" + catalogCatId + '\'');

        // 5. catalogRef binding: plan.catalogRef == catalogId + "@" + Domain2(catalog raw)
        String catalogRef       = planNode.get("catalogRef").asText();
        String catalogRawFp      = protocol.computeCatalogRawFingerprint(catalogBytes);
        String expectedCatalogRef = planCatId + "@" + catalogRawFp;
        if (!catalogRef.equals(expectedCatalogRef))
            bad(CompilerException.ReasonCode.INVALID_FINGERPRINT_MISMATCH, "/catalogRef",
                    "catalogRef mismatch: expected '" + expectedCatalogRef + "', got '" + catalogRef + '\'');

        // 6. Catalog semantic fingerprint must match profile pin
        String catalogSemFp  = protocol.computeCatalogSemanticFingerprint(catalogNode);
        String expectedSemFp = protocol.getExpectedCatalogSemanticFingerprint();
        if (!catalogSemFp.equals(expectedSemFp))
            bad(CompilerException.ReasonCode.INVALID_CATALOG_SEMANTICS, "/catalogId",
                    "Catalog semantic fingerprint mismatch: expected '" + expectedSemFp
                    + "', got '" + catalogSemFp + '\'');

        // 7. Catalog structural counts
        validateCatalogCounts(catalogNode);

        // 8. Catalog ID uniqueness across three contract arrays
        validateCatalogIdUniqueness(catalogNode);

        // 9. Primitive validation
        List<JsonNode> primitives = collectAndValidatePrimitives(planNode);

        // 10. Topological sort + barrier validation
        List<String> executionOrder = topologicalSort(primitives);

        // 11. Compute fingerprints
        String planSemFp  = protocol.computePlanSemanticFingerprint(planNode);
        String registryFp = registry.fingerprint();
        String profileFp  = protocol.getProfileRawFingerprint();

        // 12. Build 22-field IR body (no selfHash)
        ObjectNode ir = buildIr(planNode, catalogNode,
                planSemFp, catalogRawFp, catalogSemFp, profileFp, registryFp,
                primitives, executionOrder);

        // 13. Compute compiledPlanFingerprint from 22-field canonical JSON (Domain2 RG-CS-COMPILED-PLAN-v1)
        byte[] irBytes = protocol.canonicalJsonBytes(ir);
        String compiledFp = Domain2.compute("RG-CS-COMPILED-PLAN-v1", irBytes);
        ir.put("compiledPlanFingerprint", compiledFp);

        byte[] compiledBytes = protocol.canonicalJsonBytes(ir);

        // 14. Final schema validation of compiled plan
        protocol.validateSchema(
                "bloge.capability-studio.compiled-plan.v1", compiledBytes, "/");

        return new CompilationResult(compiledBytes, compiledFp);
    }

    // ── Primitive validation ──────────────────────────────────────────────────

    private List<JsonNode> collectAndValidatePrimitives(JsonNode planNode) {
        ArrayNode planPrimitives = (ArrayNode) planNode.get("primitives");
        List<JsonNode> primitives = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        for (JsonNode p : planPrimitives) {
            String id = p.get("id").asText();
            if (!seenIds.add(id))
                bad(CompilerException.ReasonCode.INVALID_PLAN_STRUCTURE, "/primitives",
                        "Duplicate primitive id: " + id);

            String typeId = p.get("typeId").asText();
            int revision  = p.get("revision").asInt();

            // Registry lookup (throws INVALID_REGISTRY_TYPE_NOT_FOUND if unknown)
            CapabilityStudioAcceptancePrimitiveRegistry.PrimitiveDescriptor desc = registry.get(typeId);

            // Revision match
            if (desc.revision() != revision)
                bad(CompilerException.ReasonCode.INVALID_REGISTRY_REVISION_MISMATCH, "/primitives",
                        "Primitive '" + id + "' revision " + revision
                        + " != descriptor revision " + desc.revision());

            primitives.add(p);
        }
        return primitives;
    }

    // ── Topological sort + barrier check ─────────────────────────────────────

    private List<String> topologicalSort(List<JsonNode> primitives) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode p : primitives) byId.put(p.get("id").asText(), p);

        Set<String> allIds = new LinkedHashSet<>(byId.keySet());
        Set<String> depIds = new LinkedHashSet<>();
        for (JsonNode p : primitives)
            for (JsonNode d : p.get("dependsOn")) depIds.add(d.asText());

        // Unknown dependencies
        Set<String> unknown = new LinkedHashSet<>(depIds);
        unknown.removeAll(allIds);
        if (!unknown.isEmpty())
            bad(CompilerException.ReasonCode.INVALID_TOPOLOGY_UNKNOWN_NODE, "/primitives",
                    "Unknown primitive id in dependsOn: " + unknown.iterator().next());

        // In-degree and adjacency list
        Map<String, Integer> inDeg = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : allIds) { inDeg.put(id, 0); adj.put(id, new ArrayList<>()); }
        for (JsonNode p : primitives) {
            String pid = p.get("id").asText();
            for (JsonNode d : p.get("dependsOn")) {
                String did = d.asText();
                adj.get(did).add(pid);
                inDeg.merge(pid, 1, Integer::sum);
            }
        }

        // Phase-index map for stable ordering and barrier validation
        Map<String, Integer> phaseIdx = new LinkedHashMap<>();
        Map<String, String> phaseOf = new LinkedHashMap<>();
        for (JsonNode p : primitives) {
            String pid = p.get("id").asText();
            String t   = p.get("typeId").asText();
            var desc   = registry.get(t);
            int idx = registry.phaseIndex(desc.phase());
            phaseIdx.put(pid, idx);
            phaseOf.put(pid, desc.phase());
        }

        // Phase barrier rules - check each edge independently BEFORE Kahn
        // Rule: A dependsOn B implies phase(B) <= phase(A), i.e., if phase(B) > phase(A) -> BARRIER_BYPASS
        for (JsonNode p : primitives) {
            String pid = p.get("id").asText();
            int predPhase = phaseIdx.get(pid);
            for (JsonNode d : p.get("dependsOn")) {
                String did = d.asText();
                int depPhase = phaseIdx.get(did);
                // A dependsOn B means phase(B) must be <= phase(A)
                if (depPhase > predPhase) {
                    bad(CompilerException.ReasonCode.INVALID_BARRIER_BYPASS, "/primitives",
                            "Primitive '" + pid + "' (phase " + phaseOf.get(pid) +
                            ") depends on '" + did + "' (phase " + phaseOf.get(did) +
                            "): dependency has later phase");
                }
            }
        }

        // Kahn with stable queue: sorted by (phaseIndex, id) - stable numeric phase index
        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.<String>comparingInt(phaseIdx::get)
                        .thenComparing(Comparator.naturalOrder()));
        for (String id : allIds)
            if (inDeg.get(id) == 0) queue.add(id);

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String pid = queue.poll();
            order.add(pid);
            for (String succ : adj.get(pid)) {
                int newDeg = inDeg.get(succ) - 1;
                inDeg.put(succ, newDeg);
                if (newDeg == 0) queue.add(succ);
            }
        }

        // Cycle detection
        if (order.size() != allIds.size())
            bad(CompilerException.ReasonCode.INVALID_TOPOLOGY_CYCLE, "/primitives",
                    "Cycle detected in dependsOn graph");

        return order;
    }

    // ── Catalog structural validation ─────────────────────────────────────────

    private void validateCatalogCounts(JsonNode cat) {
        checkCount(cat, "stageExitContracts", 27,
                CompilerException.ReasonCode.INVALID_STAGE_EXIT_CONTRACT_COUNT);
        checkCount(cat, "acStandards",  9,   CompilerException.ReasonCode.INVALID_COLLECTION_SIZE);
        checkCount(cat, "feltObligations", 14, CompilerException.ReasonCode.INVALID_COLLECTION_SIZE);
        checkCount(cat, "canonicalCases",  9, CompilerException.ReasonCode.INVALID_COLLECTION_SIZE);
        checkCount(cat, "suiteRuns",        3, CompilerException.ReasonCode.INVALID_COLLECTION_SIZE);
        checkCount(cat, "matrixCells",     27, CompilerException.ReasonCode.INVALID_COLLECTION_SIZE);
    }

    private void checkCount(JsonNode node, String field, int expected,
                            CompilerException.ReasonCode code) {
        int actual = node.get(field).size();
        if (actual != expected)
            bad(code, "/" + field,
                    "Expected " + expected + " entries in " + field + ", got " + actual);
    }

    private void validateCatalogIdUniqueness(JsonNode cat) {
        Set<String> contractIds = new LinkedHashSet<>();
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations"))
            for (JsonNode c : (ArrayNode) cat.get(arrKey)) {
                String id = c.get("contractId").asText();
                if (!contractIds.add(id))
                    bad(CompilerException.ReasonCode.INVALID_CATALOG_SEMANTICS, "/" + arrKey,
                            "Duplicate contractId: " + id);
            }
        if (contractIds.size() != 50)
            bad(CompilerException.ReasonCode.INVALID_CATALOG_SEMANTICS, "/contractId",
                    "Expected 50 unique contractIds across three arrays, got " + contractIds.size());

        // canonicalCases unique
        Set<String> caseIds = new LinkedHashSet<>();
        for (JsonNode c : (ArrayNode) cat.get("canonicalCases")) {
            String id = c.get("canonicalCaseId").asText();
            if (!caseIds.add(id))
                bad(CompilerException.ReasonCode.INVALID_COLLECTION_SIZE, "/canonicalCases",
                        "Duplicate canonicalCaseId: " + id);
        }
        // suiteRuns unique
        Set<String> runIds = new LinkedHashSet<>();
        for (JsonNode r : (ArrayNode) cat.get("suiteRuns")) {
            String id = r.get("suiteRunId").asText();
            if (!runIds.add(id))
                bad(CompilerException.ReasonCode.INVALID_COLLECTION_SIZE, "/suiteRuns",
                        "Duplicate suiteRunId: " + id);
        }
        // matrixCells unique
        Set<String> cellIds = new LinkedHashSet<>();
        for (JsonNode c : (ArrayNode) cat.get("matrixCells")) {
            String id = c.get("matrixCellId").asText();
            if (!cellIds.add(id))
                bad(CompilerException.ReasonCode.INVALID_COLLECTION_SIZE, "/matrixCells",
                        "Duplicate matrixCellId: " + id);
        }
    }

    // ── IR assembly (22 fields) ─────────────────────────────────────────────

    private ObjectNode buildIr(JsonNode planNode, JsonNode catalogNode,
            String planSemFp, String catRawFp, String catSemFp,
            String profileFp, String registryFp,
            List<JsonNode> primitives, List<String> executionOrder) {

        ObjectNode ir = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createObjectNode();

        ir.put("schemaVersion", "bloge.capability-studio.compiled-plan.v1");
        ir.put("planId",   planNode.get("planId").asText());
        ir.put("revision",  planNode.get("revision").asInt());
        ir.put("planSourceSemanticFingerprint", planSemFp);
        ir.put("catalogRawFingerprint",          catRawFp);
        ir.put("catalogSemanticFingerprint",    catSemFp);
        ir.put("compilerProfileRawFingerprint",  profileFp);
        ir.put("primitiveRegistryFingerprint", registryFp);
        ir.put("stageExitContractCount",    27);
        ir.put("acStdCount",                 9);
        ir.put("feltObligationCount",       14);
        ir.put("canonicalMatrixCellCount",  27);
        ir.put("suiteRunCount",              3);

        ir.set("matrixCellIds",    sortedIds(catalogNode, "matrixCells",    "matrixCellId"));
        ir.set("suiteRunIds",     sortedIds(catalogNode, "suiteRuns",     "suiteRunId"));
        ir.set("exactContractIds", sortedContractIds(catalogNode));
    //         primitiveContracts sorted by primitiveId ascending, executionOrder keeps Kahn result separately
        ir.set("primitiveContracts", buildPrimitiveContracts(primitives, executionOrder));
        ir.set("phaseBarriers",     buildPhaseBarriers());
        ir.set("executionOrder",     buildExecutionOrder(executionOrder));
        // expectedEvidenceRoles aggregates all three contract arrays (50 contracts total)
        ir.set("expectedEvidenceRoles", buildExpectedEvidenceRoles(catalogNode));
        ir.set("oracleBindings",      buildOracleBindings(catalogNode));
        ir.put("terminalGate", planNode.get("terminalGate").asText());

        return ir;
    }

    private ArrayNode sortedIds(JsonNode cat, String arrKey, String idKey) {
        List<JsonNode> list = new ArrayList<>();
        ((ArrayNode) cat.get(arrKey)).forEach(list::add);
        list.sort(Comparator.comparing(n -> n.get(idKey).asText()));
        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (JsonNode n : list) result.add(n.get(idKey).asText());
        return result;
    }

    private ArrayNode sortedContractIds(JsonNode cat) {
        List<String> ids = new ArrayList<>();
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations"))
            for (JsonNode c : (ArrayNode) cat.get(arrKey))
                ids.add(c.get("contractId").asText());
        ids.sort(Comparator.naturalOrder());
        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (String id : ids) result.add(id);
        return result;
    }

    //     primitiveContracts sorted by primitiveId ascending, executionOrder field keeps Kahn result
    private ArrayNode buildPrimitiveContracts(List<JsonNode> primitives,
                                             List<String> executionOrder) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode p : primitives) byId.put(p.get("id").asText(), p);

        // Build contract entries but sort by primitiveId for output
        List<ObjectNode> entries = new ArrayList<>();
        for (String pid : executionOrder) {
            JsonNode p = byId.get(pid);
            String typeId = p.get("typeId").asText();
            var desc = registry.get(typeId);

            ObjectNode pc = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createObjectNode();
            pc.put("primitiveId",  pid);
            pc.put("typeId",      typeId);
            pc.put("effectClass", desc.effectClass());
            pc.put("phase",       desc.phase());
            pc.put("revision",    p.get("revision").asInt());

            ArrayNode deps = pc.putArray("dependsOn");
            List<String> depList = new ArrayList<>();
            p.get("dependsOn").forEach(n -> depList.add(n.asText()));
            depList.sort(Comparator.naturalOrder());
            for (String d : depList) deps.add(d);

            if (p.has("inputSlot"))
                pc.put("inputSlot", p.get("inputSlot").asText());
            entries.add(pc);
        }

        //         Sort output by primitiveId ascending
        entries.sort(Comparator.comparing(n -> n.get("primitiveId").asText()));

        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (ObjectNode pc : entries) result.add(pc);
        return result;
    }

    private ArrayNode buildPhaseBarriers() {
        JsonNode barriers = protocol.getProfileNode().get("barriers");
        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (JsonNode b : barriers) {
            ObjectNode bb = result.addObject();
            bb.put("barrierId", b.get("barrierId").asText());
            ArrayNode phases = bb.putArray("phases");
            for (JsonNode p : b.get("phases")) phases.add(p.asText());
        }
        return result;
    }

    private ArrayNode buildExecutionOrder(List<String> order) {
        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (String id : order) result.add(id);
        return result;
    }

    //     expectedEvidenceRoles aggregates all three contract arrays (stageExitContracts, acStandards, feltObligations)
    @SuppressWarnings("unchecked")
    private ArrayNode buildExpectedEvidenceRoles(JsonNode cat) {
        Map<String, Set<String>> roleToContracts = new TreeMap<>();
        //         Iterate over all three contract arrays, not just stageExitContracts
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations")) {
            for (JsonNode c : (ArrayNode) cat.get(arrKey)) {
                String contractId = c.get("contractId").asText();
                for (JsonNode role : c.get("evidenceRoles")) {
                    String roleName = role.asText();
                    roleToContracts.computeIfAbsent(roleName, k -> new TreeSet<>()).add(contractId);
                }
            }
        }

        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (Map.Entry<String, Set<String>> e : roleToContracts.entrySet()) {
            ObjectNode role = result.addObject();
            role.put("role", e.getKey());
            ArrayNode ids = role.putArray("contractIds");
            for (String cid : e.getValue()) ids.add(cid);
        }
        return result;
    }

    private ArrayNode buildOracleBindings(JsonNode cat) {
        List<String[]> bindings = new ArrayList<>();
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations"))
            for (JsonNode c : (ArrayNode) cat.get(arrKey))
                bindings.add(new String[]{
                        c.get("contractId").asText(),
                        c.get("oracleId").asText()
                });
        bindings.sort(Comparator.comparing(a -> a[0]));

        ArrayNode result = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
        for (String[] b : bindings) {
            ObjectNode binding = result.addObject();
            binding.put("contractId", b[0]);
            binding.put("oracleId",  b[1]);
        }
        return result;
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static void bad(CompilerException.ReasonCode code, String field, String msg) {
        throw new CompilerException(code, field, msg);
    }
}
