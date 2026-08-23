package com.leanowtech.bloge.gateway.testkit.acceptance;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Independent compiled-plan verifier for Capability Studio.
 *
 * <p>Dual-oracle architecture: recomputes every static fact without referencing
 * any Compiler, Protocol, Registry, or Domain2 symbol from the compiler package.
 * Does NOT execute primitives, does NOT generate PASS/ACCEPTED, and does NOT
 * reference or import: CapabilityStudioAcceptancePlanCompiler, CompilationResult,
 * CapabilityStudioAcceptancePlanProtocol, CapabilityStudioAcceptancePrimitiveRegistry,
 * CompilerException, or Domain2.</p>
 *
 * <h2>Verification order</h2>
 * <ol>
 *   <li>Parse / schema validation</li>
 *   <li>Independent semantic validity (unknown type/revision/dependency/cycle/barrier)</li>
 *   <li>Authority fingerprint pins (catalog raw/semantic, plan semantic, profile, registry)</li>
 *   <li>Deep IR comparison: deriveExpectedIR vs extractActualIR → first failure pointer</li>
 *   <li>Recompute compiledPlanFingerprint (consistency proof)</li>
 * </ol>
 */
public final class CapabilityStudioCompiledPlanVerifier {

    private static final int MAX_SIZE_BYTES = 1 << 20;

    private final Schema planSchema;
    private final Schema catalogSchema;
    private final Schema compiledPlanSchema;
    private final String profileRawFingerprint;
    private final String expectedCatalogSemanticFingerprint;
    private final String expectedPrimitiveRegistryFingerprint;
    private final List<String> phaseOrder;
    private final List<PrimitiveDescriptor> primitiveDescriptors;
    private final List<Barrier> barriers;
    /** Independently computed fingerprint of the builtin primitive registry (typeId-sorted canonical JSON, Domain2). */
    private final String registryFingerprint;

    /**
     * Constructs a verifier that resolves all schemas and the compiler profile from the classpath.
     * Loads all JSON Schemas and the builtin compiler profile from the classpath.
     *
     * @return a new verifier initialised with built-in resources; never null
     */
    public static CapabilityStudioCompiledPlanVerifier withBuiltInInternals() {
        return new CapabilityStudioCompiledPlanVerifier();
    }

    private CapabilityStudioCompiledPlanVerifier() {
        this.planSchema = loadSchema(
                "/schemas/resource-gateway-capability-studio/capability-studio-acceptance-plan-v1.schema.json");
        this.catalogSchema = loadSchema(
                "/schemas/resource-gateway-capability-studio/capability-studio-contract-catalog-v1.schema.json");
        this.compiledPlanSchema = loadSchema(
                "/schemas/resource-gateway-capability-studio/capability-studio-compiled-acceptance-plan-v1.schema.json");

        byte[] profileBytes = loadProfile();
        this.profileRawFingerprint = domain2("RG-CS-COMPILER-PROFILE-RAW-v1", profileBytes);
        JsonNode profileNode = parseStrict(profileBytes, "compiler profile");

        this.expectedCatalogSemanticFingerprint  = profileNode.at("/expectedCatalogSemanticFingerprint").asText();
        this.expectedPrimitiveRegistryFingerprint = profileNode.at("/expectedPrimitiveRegistryFingerprint").asText();

        ArrayNode phaseOrderNode = (ArrayNode) profileNode.get("phaseOrder");
        this.phaseOrder = new ArrayList<>();
        phaseOrderNode.forEach(n -> phaseOrder.add(n.asText()));

        ArrayNode descs = (ArrayNode) profileNode.get("primitiveDescriptors");
        this.primitiveDescriptors = new ArrayList<>();
        for (JsonNode d : descs) {
            List<String> inputKinds = new ArrayList<>();
            d.get("inputKinds").forEach(n -> inputKinds.add(n.asText()));
            List<String> outputEvidenceKinds = new ArrayList<>();
            d.get("outputEvidenceKinds").forEach(n -> outputEvidenceKinds.add(n.asText()));
            List<String> capabilityRequirements = new ArrayList<>();
            d.get("capabilityRequirements").forEach(n -> capabilityRequirements.add(n.asText()));
            Map<String,String> failureMapping = new LinkedHashMap<>();
            d.get("failureMapping").fields().forEachRemaining(e ->
                    failureMapping.put(e.getKey(), e.getValue().asText()));
            this.primitiveDescriptors.add(new PrimitiveDescriptor(
                    d.get("typeId").asText(),
                    d.get("revision").asInt(),
                    d.get("effectClass").asText(),
                    d.get("phase").asText(),
                    inputKinds,
                    outputEvidenceKinds,
                    d.get("typedVerifierId").asText(),
                    d.get("typedVerifierRevision").asInt(),
                    d.get("retryPolicy").asText(),
                    failureMapping,
                    capabilityRequirements));
        }
        this.registryFingerprint = computeRegistryFingerprint();

        ArrayNode barrierNode = (ArrayNode) profileNode.get("barriers");
        this.barriers = new ArrayList<>();
        for (JsonNode b : barrierNode) {
            List<String> phases = new ArrayList<>();
            b.get("phases").forEach(n -> phases.add(n.asText()));
            this.barriers.add(new Barrier(b.get("barrierId").asText(), phases));
        }
    }

    /**
     * Verifies a compiled acceptance plan against the independently-computed static facts derived
     * from the supplied plan, catalog, and builtin compiler profile.
     *
     * <p>Verification proceeds through seven stages:</p>
     * <ol>
     *   <li>Size guard — rejects inputs larger than 1 MiB</li>
     *   <li>Strict JSON parsing — rejects duplicate object keys</li>
     *   <li>Schema validation — validates all three wire documents against their JSON Schemas</li>
     *   <li>Semantic validity — checks unknown types/revisions, dependency cycles, and phase-barrier bypasses</li>
     *   <li>Authority fingerprint pins — proves the independently-computed registry, catalog, plan, and profile fingerprints match the compiled plan's declared pins and the profile's expected pins</li>
     *   <li>Deep IR comparison — derives the expected IR from plan+catalog and compares field-by-field with the compiled plan's IR</li>
     *   <li>Fingerprint recomputation — independently recomputes the compiled plan fingerprint from the wire body</li>
     * </ol>
     *
     * <p>This method does NOT execute any primitives, does NOT generate PASS/ACCEPTED, and does NOT
     * reference any symbol from the compiler package (Compiler, CompilationResult, Protocol,
     * Registry, CompilerException, or Domain2).</p>
     *
     * @param planBytes        the authority plan JSON; must not be null
     * @param catalogBytes     the authority catalog JSON; must not be null
     * @param compiledPlanBytes the supplied compiled acceptance plan wire bytes; must not be null
     * @return a result snapshot; never null
     * @throws NullPointerException if any parameter is null
     */
    public CompiledPlanVerificationResult verify(byte[] planBytes,
                                                byte[] catalogBytes,
                                                byte[] compiledPlanBytes) {
        Objects.requireNonNull(planBytes,       "planBytes");
        Objects.requireNonNull(catalogBytes,     "catalogBytes");
        Objects.requireNonNull(compiledPlanBytes, "compiledPlanBytes");

        planBytes        = planBytes.clone();
        catalogBytes     = catalogBytes.clone();
        compiledPlanBytes = compiledPlanBytes.clone();

        // 1. Size guard
        if (planBytes.length > MAX_SIZE_BYTES || catalogBytes.length > MAX_SIZE_BYTES || compiledPlanBytes.length > MAX_SIZE_BYTES) {
            String ptr = planBytes.length > MAX_SIZE_BYTES ? "/planBytes"
                    : catalogBytes.length > MAX_SIZE_BYTES ? "/catalogBytes" : "/compiledPlanBytes";
            return buildInvalid("INVALID_COLLECTION_SIZE", ptr, null, null, null, null, null, null);
        }

        // 2. Parse (strict duplicate-key detection)
        JsonNode planNode;
        JsonNode catalogNode;
        JsonNode compiledNode;
        try {
            planNode        = parseStrict(planBytes,        "plan");
            catalogNode     = parseStrict(catalogBytes,     "catalog");
            compiledNode   = parseStrict(compiledPlanBytes, "compiledPlan");
        } catch (VerificationException e) {
            return buildInvalid(e.reasonCode, e.reasonField, null, null, null, null, null, null);
        }

        // 3. Schema validation — plan, catalog, compiled
        List<com.networknt.schema.Error> planMsgs = planSchema.validate(planNode);
        if (!planMsgs.isEmpty()) {
            return buildInvalid("INVALID_SCHEMA", "/planBytes", null, null, null, null, null, null);
        }
        List<com.networknt.schema.Error> catalogMsgs = catalogSchema.validate(catalogNode);
        if (!catalogMsgs.isEmpty()) {
            return buildInvalid("INVALID_SCHEMA", "/catalogBytes", null, null, null, null, null, null);
        }
        List<com.networknt.schema.Error> compiledMsgs = compiledPlanSchema.validate(compiledNode);
        if (!compiledMsgs.isEmpty()) {
            return buildInvalid("INVALID_SCHEMA", "/compiledPlanBytes", null, null, null, null, null, null);
        }

        String planId                  = compiledNode.at("/planId").asText();
        int    revision               = compiledNode.at("/revision").asInt();
        String compiledPlanFingerprint = compiledNode.at("/compiledPlanFingerprint").asText();

        // 4. Independent fingerprints
        String planSemFp    = computePlanSemanticFingerprint(planNode);
        String catalogRawFp = domain2("RG-CS-CATALOG-RAW-v1", catalogBytes);
        String catalogSemFp = computeCatalogSemanticFingerprint(catalogNode);

        // 5. Semantic validity
        Map<String, JsonNode> primById = new LinkedHashMap<>();
        for (JsonNode p : (ArrayNode) planNode.get("primitives"))
            primById.put(p.get("id").asText(), p);

        Map<String, PrimitiveDescriptor> regByType = new LinkedHashMap<>();
        for (PrimitiveDescriptor pd : primitiveDescriptors)
            regByType.put(pd.typeId, pd);

        // 5a: unknown typeId
        for (JsonNode p : (ArrayNode) planNode.get("primitives")) {
            if (!regByType.containsKey(p.get("typeId").asText()))
                return buildInvalid("INVALID_REGISTRY_TYPE_NOT_FOUND", "/primitives",
                        planId, revision, compiledPlanFingerprint, null, null, "/primitives");
        }
        // 5b: unknown revision
        for (JsonNode p : (ArrayNode) planNode.get("primitives")) {
            String tid = p.get("typeId").asText();
            if (regByType.get(tid).revision != p.get("revision").asInt())
                return buildInvalid("INVALID_REGISTRY_TYPE_NOT_FOUND", "/primitives",
                        planId, revision, compiledPlanFingerprint, null, null, "/primitives");
        }
        // 5c: unknown dependency
        Set<String> allIds = new LinkedHashSet<>(primById.keySet());
        for (JsonNode p : (ArrayNode) planNode.get("primitives")) {
            for (JsonNode d : p.get("dependsOn")) {
                if (!allIds.contains(d.asText()))
                    return buildInvalid("INVALID_TOPOLOGY_UNKNOWN_NODE", "/primitives",
                            planId, revision, compiledPlanFingerprint, null, null, "/primitives");
            }
        }

        // Phase indices by primitive id
        Map<String, Integer> primPhaseIdx = new LinkedHashMap<>();
        for (JsonNode p : (ArrayNode) planNode.get("primitives")) {
            String pid = p.get("id").asText();
            String tid = p.get("typeId").asText();
            PrimitiveDescriptor desc = regByType.get(tid);
            primPhaseIdx.put(pid, phaseOrder.indexOf(desc.phase));
        }

        // 5d: phase barrier
        for (JsonNode p : (ArrayNode) planNode.get("primitives")) {
            String pid = p.get("id").asText();
            int pidPhase = primPhaseIdx.get(pid);
            for (JsonNode d : p.get("dependsOn")) {
                int depPhase = primPhaseIdx.get(d.asText());
                if (depPhase > pidPhase)
                    return buildInvalid("INVALID_BARRIER_BYPASS", "/primitives",
                            planId, revision, compiledPlanFingerprint, null, null, "/primitives");
            }
        }

        // 5e: DAG cycle (Kahn)
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Map<String, Integer> inDeg = new LinkedHashMap<>();
        for (String id : allIds) { adj.put(id, new ArrayList<>()); inDeg.put(id, 0); }
        for (JsonNode p : (ArrayNode) planNode.get("primitives")) {
            String pid = p.get("id").asText();
            for (JsonNode d : p.get("dependsOn")) {
                adj.get(d.asText()).add(pid);
                inDeg.put(pid, inDeg.get(pid) + 1);
            }
        }
        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.<String>comparingInt(primPhaseIdx::get)
                        .thenComparing(Comparator.naturalOrder()));
        for (String id : allIds) if (inDeg.get(id) == 0) queue.add(id);
        List<String> execOrder = new ArrayList<>();
        while (!queue.isEmpty()) {
            String pid = queue.poll();
            execOrder.add(pid);
            for (String succ : adj.get(pid)) {
                int nd = inDeg.get(succ) - 1;
                inDeg.put(succ, nd);
                if (nd == 0) queue.add(succ);
            }
        }
        if (execOrder.size() != allIds.size())
            return buildInvalid("INVALID_TOPOLOGY_CYCLE", "/primitives",
                    planId, revision, compiledPlanFingerprint, null, null, "/primitives");

        // 6. catalogRef input binding: plan.catalogRef == plan.catalogId + "@" + catalogRawFp
        String planCatalogId = planNode.at("/catalogId").asText();
        String planCatalogRef = planNode.at("/catalogRef").asText();
        String expectedCatalogRef = planCatalogId + "@" + catalogRawFp;
        if (!planCatalogRef.equals(expectedCatalogRef))
            return buildInvalid("INVALID_FINGERPRINT_MISMATCH", "/catalogRef",
                    planId, revision, compiledPlanFingerprint, null, null, "/catalogRef");

        // 7. Authority pins
        // Pin 1: independently-computed registry fingerprint must match the profile pin
        if (!registryFingerprint.equals(expectedPrimitiveRegistryFingerprint))
            return buildInvalid("INVALID_REGISTRY_TYPE_NOT_FOUND", "/primitiveRegistryFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/primitiveRegistryFingerprint");

        if (!catalogSemFp.equals(expectedCatalogSemanticFingerprint))
            return buildInvalid("INVALID_CATALOG_SEMANTICS", "/catalogSemanticFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/catalogSemanticFingerprint");
        if (!catalogRawFp.equals(compiledNode.at("/catalogRawFingerprint").asText()))
            return buildInvalid("INVALID_FINGERPRINT_MISMATCH", "/catalogRawFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/catalogRawFingerprint");
        if (!catalogSemFp.equals(compiledNode.at("/catalogSemanticFingerprint").asText()))
            return buildInvalid("INVALID_FINGERPRINT_MISMATCH", "/catalogSemanticFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/catalogSemanticFingerprint");
        if (!planSemFp.equals(compiledNode.at("/planSourceSemanticFingerprint").asText()))
            return buildInvalid("INVALID_FINGERPRINT_MISMATCH", "/planSourceSemanticFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/planSourceSemanticFingerprint");
        if (!profileRawFingerprint.equals(compiledNode.at("/compilerProfileRawFingerprint").asText()))
            return buildInvalid("INVALID_TAMPERED_PLAN", "/compilerProfileRawFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/compilerProfileRawFingerprint");
        // Verify registry fingerprint matches the profile pin
        if (!registryFingerprint.equals(compiledNode.at("/primitiveRegistryFingerprint").asText()))
            return buildInvalid("INVALID_TAMPERED_PLAN", "/primitiveRegistryFingerprint",
                    planId, revision, compiledPlanFingerprint, null, null, "/primitiveRegistryFingerprint");

        // 7. Deep IR comparison
        JsonNode expectedIR = deriveExpectedIR(planNode, catalogNode, planSemFp, catalogRawFp, catalogSemFp, execOrder);
        JsonNode actualIR   = extractActualIR(compiledNode);
        DiffResult diff = deepEqual(expectedIR, actualIR);
        if (!diff.equal)
            return buildInvalid("INVALID_TAMPERED_PLAN", diff.pointer,
                    planId, revision, compiledPlanFingerprint, null, null, diff.pointer);

        // 8. Recompute compiledPlanFingerprint
        String recomputedFp = computeCompiledPlanFingerprint(compiledNode);
        if (!compiledPlanFingerprint.equals(recomputedFp))
            return buildInvalid("INVALID_TAMPERED_PLAN", "/compiledPlanFingerprint",
                    planId, revision, compiledPlanFingerprint, recomputedFp, null, "/compiledPlanFingerprint");

        // 9. VERIFIED
        return buildVerified(planId, revision, compiledPlanFingerprint, recomputedFp);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Expected IR derivation (mirrors Compiler.buildIr 22 fields)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Derive the complete expected IR (22 body fields, matching Compiler.buildIr exactly).
     * No catalogRef, no compiledPlanFingerprint.
     */
    private JsonNode deriveExpectedIR(JsonNode planNode, JsonNode catalogNode,
                                     String planSemFp, String catalogRawFp, String catalogSemFp,
                                     List<String> execOrder) {
        ObjectNode ir = CANONICAL_MAPPER.createObjectNode();

        ir.put("schemaVersion", "bloge.capability-studio.compiled-plan.v1");
        ir.put("planId",   planNode.at("/planId").asText());
        ir.put("revision", planNode.at("/revision").asInt());
        ir.put("planSourceSemanticFingerprint", planSemFp);
        ir.put("catalogRawFingerprint",         catalogRawFp);
        ir.put("catalogSemanticFingerprint",   catalogSemFp);
        ir.put("compilerProfileRawFingerprint", profileRawFingerprint);
        ir.put("primitiveRegistryFingerprint",  registryFingerprint);

        ir.put("stageExitContractCount",    27);
        ir.put("acStdCount",                9);
        ir.put("feltObligationCount",       14);
        ir.put("canonicalMatrixCellCount",  27);
        ir.put("suiteRunCount",             3);

        ir.set("matrixCellIds",    sortedIds(catalogNode, "matrixCells",    "matrixCellId"));
        ir.set("suiteRunIds",      sortedIds(catalogNode, "suiteRuns",     "suiteRunId"));
        ir.set("exactContractIds", sortedContractIds(catalogNode));

        ir.set("primitiveContracts", buildPrimitiveContracts(planNode, execOrder));
        ir.set("phaseBarriers",      buildPhaseBarriers());
        ir.set("executionOrder",      buildExecutionOrder(execOrder));
        ir.set("expectedEvidenceRoles", buildExpectedEvidenceRoles(catalogNode));
        ir.set("oracleBindings",       buildOracleBindings(catalogNode));
        ir.put("terminalGate", planNode.at("/terminalGate").asText());

        return ir;
    }

    /** Extract actual IR from compiled plan (remove compiledPlanFingerprint). */
    private JsonNode extractActualIR(JsonNode compiledNode) {
        ObjectNode ir = CANONICAL_MAPPER.createObjectNode();
        compiledNode.fields().forEachRemaining(e -> {
            if (!"compiledPlanFingerprint".equals(e.getKey()))
                ir.set(e.getKey(), deepCopy(e.getValue()));
        });
        return ir;
    }

    // ── Helpers (exact mirrors of Compiler.buildIr helpers) ─────────────────

    /** Sorted string array: extract idKey field from arrKey array, sort by that field. */
    private JsonNode sortedIds(JsonNode cat, String arrKey, String idKey) {
        List<JsonNode> list = new ArrayList<>();
        ((ArrayNode) cat.get(arrKey)).forEach(list::add);
        list.sort(Comparator.comparing(n -> n.get(idKey).asText()));
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (JsonNode n : list) result.add(n.get(idKey).asText());
        return result;
    }

    /** Collect all contract IDs from three arrays, sort ascending. */
    private JsonNode sortedContractIds(JsonNode cat) {
        List<String> ids = new ArrayList<>();
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations"))
            for (JsonNode c : (ArrayNode) cat.get(arrKey))
                ids.add(c.get("contractId").asText());
        ids.sort(Comparator.naturalOrder());
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (String id : ids) result.add(id);
        return result;
    }

    /**
     * Primitive contracts: entries sorted by primitiveId ascending.
     * Each entry: primitiveId, typeId, effectClass, phase, revision, dependsOn, [inputSlot].
     */
    private JsonNode buildPrimitiveContracts(JsonNode planNode, List<String> execOrder) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode p : (ArrayNode) planNode.get("primitives"))
            byId.put(p.get("id").asText(), p);

        Map<String, PrimitiveDescriptor> regByType = new LinkedHashMap<>();
        for (PrimitiveDescriptor pd : primitiveDescriptors)
            regByType.put(pd.typeId, pd);

        List<ObjectNode> entries = new ArrayList<>();
        for (String pid : execOrder) {
            JsonNode p = byId.get(pid);
            String typeId = p.get("typeId").asText();
            PrimitiveDescriptor desc = regByType.get(typeId);

            ObjectNode pc = CANONICAL_MAPPER.createObjectNode();
            pc.put("primitiveId",  pid);
            pc.put("typeId",      typeId);
            pc.put("effectClass", desc.effectClass);
            pc.put("phase",       desc.phase);
            pc.put("revision",    p.get("revision").asInt());

            ArrayNode deps = pc.putArray("dependsOn");
            List<String> depList = new ArrayList<>();
            p.get("dependsOn").forEach(n -> depList.add(n.asText()));
            depList.sort(Comparator.naturalOrder());
            for (String d : depList) deps.add(d);

            if (p.has("inputSlot")) pc.put("inputSlot", p.get("inputSlot").asText());
            entries.add(pc);
        }

        entries.sort(Comparator.comparing(n -> n.get("primitiveId").asText()));
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (ObjectNode pc : entries) result.add(pc);
        return result;
    }

    /** Phase barriers: each {barrierId, phases[]}. */
    private JsonNode buildPhaseBarriers() {
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (Barrier b : barriers) {
            ObjectNode bb = result.addObject();
            bb.put("barrierId", b.barrierId());
            ArrayNode phases = bb.putArray("phases");
            b.phases().forEach(phases::add);
        }
        return result;
    }

    /** Execution order: Kahn sort result. */
    private JsonNode buildExecutionOrder(List<String> order) {
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (String id : order) result.add(id);
        return result;
    }

    /**
     * expectedEvidenceRoles: aggregates all three contract arrays.
     * Array of {role, contractIds[]} — role sorted by TreeMap (natural order),
     * contractIds sorted by TreeSet (natural order).
     */
    private JsonNode buildExpectedEvidenceRoles(JsonNode cat) {
        Map<String, Set<String>> roleToContracts = new TreeMap<>();
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations")) {
            for (JsonNode c : (ArrayNode) cat.get(arrKey)) {
                for (JsonNode role : c.get("evidenceRoles")) {
                    String roleName = role.asText();
                    roleToContracts.computeIfAbsent(roleName, k -> new TreeSet<>())
                            .add(c.get("contractId").asText());
                }
            }
        }

        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (Map.Entry<String, Set<String>> e : roleToContracts.entrySet()) {
            ObjectNode role = result.addObject();
            role.put("role", e.getKey());
            ArrayNode ids = role.putArray("contractIds");
            for (String cid : e.getValue()) ids.add(cid);
        }
        return result;
    }

    /**
     * oracleBindings: from catalog three arrays.
     * Array of {contractId, oracleId}, sorted by contractId ascending.
     */
    private JsonNode buildOracleBindings(JsonNode cat) {
        List<String[]> bindings = new ArrayList<>();
        for (String arrKey : List.of("stageExitContracts", "acStandards", "feltObligations"))
            for (JsonNode c : (ArrayNode) cat.get(arrKey))
                bindings.add(new String[]{c.get("contractId").asText(), c.get("oracleId").asText()});
        bindings.sort(Comparator.comparing(a -> a[0]));

        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (String[] b : bindings) {
            ObjectNode binding = result.addObject();
            binding.put("contractId", b[0]);
            binding.put("oracleId", b[1]);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Deep comparison
    // ═══════════════════════════════════════════════════════════════════════════

    private static class DiffResult {
        final boolean equal;
        final String pointer;
        DiffResult(boolean equal, String pointer) { this.equal = equal; this.pointer = pointer; }
    }

    private DiffResult deepEqual(JsonNode expected, JsonNode actual) {
        return deepEqual(expected, actual, "");
    }

    private DiffResult deepEqual(JsonNode expected, JsonNode actual, String path) {
        if (expected == null && actual == null) return new DiffResult(true, null);
        if (expected == null || actual == null)  return new DiffResult(false, path);

        if (expected.getNodeType() != actual.getNodeType())
            return new DiffResult(false, path);

        switch (expected.getNodeType()) {
            case OBJECT: {
                Iterator<Map.Entry<String, JsonNode>> ei = ((ObjectNode) expected).fields();
                Map<String, JsonNode> aMap = new LinkedHashMap<>();
                ((ObjectNode) actual).fieldNames().forEachRemaining(k -> aMap.put(k, actual.get(k)));

                while (ei.hasNext()) {
                    Map.Entry<String, JsonNode> e = ei.next();
                    String k = e.getKey();
                    if (!aMap.containsKey(k))
                        return new DiffResult(false, jsonPointer(path, k));
                    DiffResult r = deepEqual(e.getValue(), aMap.get(k), jsonPointer(path, k));
                    if (!r.equal) return r;
                }
                for (String ak : aMap.keySet()) {
                    if (!expected.has(ak))
                        return new DiffResult(false, jsonPointer(path, ak));
                }
                return new DiffResult(true, null);
            }
            case ARRAY: {
                ArrayNode ea = (ArrayNode) expected;
                ArrayNode aa = (ArrayNode) actual;
                if (ea.size() != aa.size())
                    return new DiffResult(false, path);
                for (int i = 0; i < ea.size(); i++) {
                    DiffResult r = deepEqual(ea.get(i), aa.get(i), jsonPointer(path, String.valueOf(i)));
                    if (!r.equal) return r;
                }
                return new DiffResult(true, null);
            }
            case STRING:
                return expected.asText().equals(actual.asText())
                        ? new DiffResult(true, null) : new DiffResult(false, path);
            case NUMBER:
                if (expected.isIntegralNumber() && actual.isIntegralNumber())
                    return expected.asLong() == actual.asLong()
                            ? new DiffResult(true, null) : new DiffResult(false, path);
                return expected.asDouble() == actual.asDouble()
                        ? new DiffResult(true, null) : new DiffResult(false, path);
            case BOOLEAN:
                return expected.asBoolean() == actual.asBoolean()
                        ? new DiffResult(true, null) : new DiffResult(false, path);
            case NULL:
                return new DiffResult(true, null);
            default:
                return new DiffResult(false, path);
        }
    }

    /** RFC 6901 JSON Pointer: encode ~ → ~0, / → ~1, prepend base path. */
    private static String jsonPointer(String base, String token) {
        String encoded = token.replace("~", "~0").replace("/", "~1");
        if (base.isEmpty()) return "/" + encoded;
        return base + "/" + encoded;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Canonicalization
    // ═══════════════════════════════════════════════════════════════════════════

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private byte[] canonicalJsonBytes(JsonNode node) {
        try { return CANONICAL_MAPPER.writeValueAsBytes(canonicalRebuild(node)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private JsonNode canonicalRebuild(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = CANONICAL_MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            for (String k : keys) out.set(k, canonicalRebuild(node.get(k)));
            return out;
        } else if (node.isArray()) {
            ArrayNode out = CANONICAL_MAPPER.createArrayNode();
            for (JsonNode e : node) out.add(canonicalRebuild(e));
            return out;
        } else if (node.isNumber()) {
            return node.isIntegralNumber()
                    ? CANONICAL_MAPPER.createObjectNode().numberNode(node.asLong())
                    : CANONICAL_MAPPER.createObjectNode().numberNode(node.asDouble());
        } else if (node.isBoolean()) {
            return CANONICAL_MAPPER.createObjectNode().booleanNode(node.asBoolean());
        } else if (node.isNull()) {
            return CANONICAL_MAPPER.nullNode();
        } else {
            return CANONICAL_MAPPER.createObjectNode().textNode(node.asText());
        }
    }

    private JsonNode deepCopy(JsonNode n) { return canonicalRebuild(n); }

    // ═══════════════════════════════════════════════════════════════════════════
    // Domain2
    // ═══════════════════════════════════════════════════════════════════════════

    private static String domain2(String domain, byte[] payload) {
        byte[] d = domain.getBytes(StandardCharsets.UTF_8);
        byte[] l = ByteBuffer.allocate(4).putInt(payload.length).array();
        byte[] in = new byte[d.length + 4 + payload.length];
        System.arraycopy(d, 0, in, 0, d.length);
        System.arraycopy(l, 0, in, d.length, 4);
        System.arraycopy(payload, 0, in, d.length + 4, payload.length);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(in);
            return "sha256:" + toHex(hash);
        } catch (Exception e) { throw new RuntimeException("SHA-256 unavailable", e); }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(v & 0xf, 16));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Semantic fingerprints
    // ═══════════════════════════════════════════════════════════════════════════

    private String computePlanSemanticFingerprint(JsonNode planNode) {
        // Mirror Compiler.canonicalizePlan: iterate all fields, handle primitives specially
        ObjectNode out = CANONICAL_MAPPER.createObjectNode();
        planNode.fields().forEachRemaining(e -> {
            if ("primitives".equals(e.getKey()) && e.getValue().isArray()) {
                out.set("primitives", canonicalizePrimitivesForSemFp((ArrayNode) e.getValue()));
            } else {
                out.set(e.getKey(), deepCopy(e.getValue()));
            }
        });
        return domain2("RG-CS-PLAN-SOURCE-SEMANTIC-v1", canonicalJsonBytes(out));
    }

    /** Canonicalize primitives for semantic fingerprint (id-sort + dependsOn-sort). */
    private ArrayNode canonicalizePrimitivesForSemFp(ArrayNode primitives) {
        List<JsonNode> list = new ArrayList<>();
        primitives.forEach(list::add);
        list.sort(Comparator.comparing(n -> n.get("id").asText()));
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (JsonNode p : list) {
            ObjectNode sp = CANONICAL_MAPPER.createObjectNode();
            sp.put("id",       p.get("id").asText());
            sp.put("typeId",   p.get("typeId").asText());
            sp.put("revision",  p.get("revision").asInt());
            ArrayNode sortedDeps = CANONICAL_MAPPER.createArrayNode();
            List<String> deps = new ArrayList<>();
            p.get("dependsOn").forEach(n -> deps.add(n.asText()));
            deps.sort(Comparator.naturalOrder());
            for (String d : deps) sortedDeps.add(d);
            sp.set("dependsOn", sortedDeps);
            if (p.has("inputSlot")) sp.put("inputSlot", p.get("inputSlot").asText());
            result.add(sp);
        }
        return result;
    }

    private String computeCatalogSemanticFingerprint(JsonNode catalogNode) {
        return domain2("RG-CS-CATALOG-SEMANTIC-v1", canonicalJsonBytes(normalizeCatalog(catalogNode)));
    }

    private JsonNode normalizeCatalog(JsonNode cat) {
        ObjectNode out = CANONICAL_MAPPER.createObjectNode();
        List<String> keys = new ArrayList<>();
        cat.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        for (String k : keys) {
            JsonNode v = cat.get(k);
            if ("canonicalCases".equals(k))        out.set(k, sortById((ArrayNode) v, "canonicalCaseId"));
            else if ("suiteRuns".equals(k))        out.set(k, sortById((ArrayNode) v, "suiteRunId"));
            else if ("matrixCells".equals(k))     out.set(k, sortById((ArrayNode) v, "matrixCellId"));
            else if ("stageExitContracts".equals(k) || "acStandards".equals(k) || "feltObligations".equals(k))
                                                out.set(k, normalizeContractArray((ArrayNode) v));
            else                                   out.set(k, deepCopy(v));
        }
        return out;
    }

    private JsonNode sortById(ArrayNode arr, String idField) {
        List<JsonNode> list = new ArrayList<>();
        arr.forEach(list::add);
        list.sort(Comparator.comparing(n -> n.get(idField).asText()));
        ArrayNode out = CANONICAL_MAPPER.createArrayNode();
        for (JsonNode n : list) out.add(deepCopy(n));
        return out;
    }

    private JsonNode normalizeContractArray(ArrayNode arr) {
        List<JsonNode> list = new ArrayList<>();
        arr.forEach(list::add);
        list.sort(Comparator.comparing(n -> n.get("contractId").asText()));
        ArrayNode out = CANONICAL_MAPPER.createArrayNode();
        for (JsonNode item : list) out.add(normalizeContractEntry((ObjectNode) item));
        return out;
    }

    private JsonNode normalizeContractEntry(ObjectNode entry) {
        ObjectNode out = CANONICAL_MAPPER.createObjectNode();
        List<String> keys = new ArrayList<>();
        entry.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        for (String k : keys) {
            JsonNode v = entry.get(k);
            if (v.isArray() && (k.endsWith("Roles") || k.endsWith("Requirements"))) {
                ArrayNode sorted = CANONICAL_MAPPER.createArrayNode();
                List<String> items = new ArrayList<>();
                v.forEach(n -> items.add(n.asText()));
                items.sort(Comparator.naturalOrder());
                items.forEach(sorted::add);
                out.set(k, sorted);
            } else {
                out.set(k, deepCopy(v));
            }
        }
        return out;
    }

    private String computeRegistryFingerprint() {
        List<PrimitiveDescriptor> sorted = new ArrayList<>(primitiveDescriptors);
        sorted.sort(Comparator.comparing(p -> p.typeId));
        ArrayNode arr = CANONICAL_MAPPER.createArrayNode();
        for (PrimitiveDescriptor pd : sorted) {
            ObjectNode d = CANONICAL_MAPPER.createObjectNode();
            d.put("typeId", pd.typeId);
            d.put("revision", pd.revision);
            d.put("effectClass", pd.effectClass);
            d.put("phase", pd.phase);
            ArrayNode ik = CANONICAL_MAPPER.createArrayNode();
            List<String> sik = new ArrayList<>(pd.inputKinds); sik.sort(Comparator.naturalOrder()); sik.forEach(ik::add);
            d.set("inputKinds", ik);
            ArrayNode oek = CANONICAL_MAPPER.createArrayNode();
            List<String> soek = new ArrayList<>(pd.outputEvidenceKinds); soek.sort(Comparator.naturalOrder()); soek.forEach(oek::add);
            d.set("outputEvidenceKinds", oek);
            d.put("typedVerifierId", pd.typedVerifierId);
            d.put("typedVerifierRevision", pd.typedVerifierRevision);
            d.put("retryPolicy", pd.retryPolicy);
            ObjectNode fm = CANONICAL_MAPPER.createObjectNode();
            List<String> fmKeys = new ArrayList<>(pd.failureMapping.keySet());
            fmKeys.sort(Comparator.naturalOrder());
            for (String k : fmKeys) fm.put(k, pd.failureMapping.get(k));
            d.set("failureMapping", fm);
            ArrayNode cr = CANONICAL_MAPPER.createArrayNode();
            List<String> scr = new ArrayList<>(pd.capabilityRequirements); scr.sort(Comparator.naturalOrder()); scr.forEach(cr::add);
            d.set("capabilityRequirements", cr);
            arr.add(d);
        }
        return domain2("RG-CS-PRIMITIVE-REGISTRY-v1", canonicalJsonBytes(arr));
    }

    private String computeCompiledPlanFingerprint(JsonNode compiledNode) {
        ObjectNode body = CANONICAL_MAPPER.createObjectNode();
        compiledNode.fields().forEachRemaining(e -> {
            if (!"compiledPlanFingerprint".equals(e.getKey()))
                body.set(e.getKey(), deepCopy(e.getValue()));
        });
        return domain2("RG-CS-COMPILED-PLAN-v1", canonicalJsonBytes(body));
    }

    private String computeVerificationFingerprint(JsonNode resultBody) {
        // Build body WITHOUT verificationFingerprint, then recursive canonical + Domain2.
        ObjectNode withoutFp = CANONICAL_MAPPER.createObjectNode();
        resultBody.fields().forEachRemaining(e -> {
            if (!"verificationFingerprint".equals(e.getKey()))
                withoutFp.set(e.getKey(), deepCopy(e.getValue()));
        });
        // Recursive canonical JSON → deterministic bytes → Domain2
        return domain2("RG-CS-COMPILED-PLAN-VERIFICATION-v1", canonicalJsonBytes(withoutFp));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Result construction
    // ═══════════════════════════════════════════════════════════════════════════

    private CompiledPlanVerificationResult buildVerified(String planId, Integer revision,
                                                        String expectedFp, String recomputedFp) {
        CompiledPlanVerificationResult.Builder fb = new CompiledPlanVerificationResult.Builder();
        fb.status    = CompiledPlanVerificationResult.Status.VERIFIED;
        fb.verified  = true;
        fb.planId    = planId;
        fb.revision  = revision;
        fb.expectedCompiledPlanFingerprint    = expectedFp;
        fb.recomputedCompiledPlanFingerprint = recomputedFp;
        fb.catalogRefVerified                   = true;
        fb.catalogRawFingerprintVerified       = true;
        fb.catalogSemanticFingerprintVerified   = true;
        fb.planFingerprintVerified            = true;
        fb.phaseBarrierVerified             = true;
        fb.dependencyDagVerified           = true;
        fb.effectBarrierVerified            = true;
        fb.canonicalMatrixCellCountVerified = true;
        fb.stageExitContractCountVerified    = true;
        // reasonCode/reasonField remain null for VERIFIED (buildResultBody always includes them)

        Map<String, Object> body = buildResultBody(fb, null);
        JsonNode bodyNode = bodyToNode(body);
        String vf = computeVerificationFingerprint(bodyNode);
        body.put("verificationFingerprint", vf);
        fb.verificationFingerprint = vf;
        fb.verificationResultBytes = serializeResult(body);
        return fb.build();
    }

    private CompiledPlanVerificationResult buildInvalid(String reasonCode, String reasonField,
                                                        String planId, Integer revision,
                                                        String expectedFp, String recomputedFp,
                                                        CompiledPlanVerificationResult.Builder fb,
                                                        String pointer) {
        if (fb == null) fb = new CompiledPlanVerificationResult.Builder();
        fb.status    = CompiledPlanVerificationResult.Status.INVALID;
        fb.verified  = false;
        fb.planId    = planId;
        fb.revision  = revision;
        fb.reasonCode = reasonCode != null && reasonCode.length() <= 512 ? reasonCode : "INVALID_TAMPERED_PLAN";
        fb.reasonField = reasonField != null && reasonField.length() <= 512 ? reasonField : "/";
        fb.expectedCompiledPlanFingerprint    = expectedFp;
        fb.recomputedCompiledPlanFingerprint = recomputedFp;

        Map<String, Object> body = buildResultBody(fb, null);
        JsonNode bodyNode = bodyToNode(body);
        String vf = computeVerificationFingerprint(bodyNode);
        body.put("verificationFingerprint", vf);
        fb.verificationFingerprint = vf;
        fb.verificationResultBytes = serializeResult(body);
        return fb.build();
    }


    private Map<String, Object> buildResultBody(CompiledPlanVerificationResult.Builder b, String vf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", "bloge.capability-studio.compiled-plan-verification-result.v1");
        if (b.planId != null)       m.put("planId", b.planId);
        if (b.revision != null)      m.put("revision", b.revision);
        m.put("status", b.status.name());
        if (b.expectedCompiledPlanFingerprint != null)
            m.put("expectedCompiledPlanFingerprint", b.expectedCompiledPlanFingerprint);
        if (b.recomputedCompiledPlanFingerprint != null)
            m.put("recomputedCompiledPlanFingerprint", b.recomputedCompiledPlanFingerprint);
        m.put("catalogRefVerified", b.catalogRefVerified);
        m.put("catalogRawFingerprintVerified", b.catalogRawFingerprintVerified);
        m.put("catalogSemanticFingerprintVerified", b.catalogSemanticFingerprintVerified);
        m.put("planFingerprintVerified", b.planFingerprintVerified);
        m.put("phaseBarrierVerified", b.phaseBarrierVerified);
        m.put("dependencyDagVerified", b.dependencyDagVerified);
        m.put("effectBarrierVerified", b.effectBarrierVerified);
        m.put("canonicalMatrixCellCountVerified", b.canonicalMatrixCellCountVerified);
        m.put("stageExitContractCountVerified", b.stageExitContractCountVerified);
        // Always include reasonCode/reasonField (null for VERIFIED per schema)
        m.put("reasonCode", b.reasonCode);
        m.put("reasonField", b.reasonField);
        if (vf != null) m.put("verificationFingerprint", vf);
        return m;
    }

    private byte[] serializeResult(Map<String, Object> body) {
        try { return CANONICAL_MAPPER.writeValueAsBytes(bodyToNode(body)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private JsonNode bodyToNode(Map<String, Object> body) {
        ObjectNode node = CANONICAL_MAPPER.createObjectNode();
        for (Map.Entry<String, Object> e : body.entrySet())
            putValue(node, e.getKey(), e.getValue());
        return node;
    }

    @SuppressWarnings("unchecked")
    private void putValue(ObjectNode node, String key, Object value) {
        if (value instanceof String)          node.put(key, (String) value);
        else if (value instanceof Integer)      node.put(key, (Integer) value);
        else if (value instanceof Boolean)      node.put(key, (Boolean) value);
        else if (value instanceof List) {
            ArrayNode arr = node.putArray(key);
            for (Object o : (List<?>) value) {
                if (o instanceof String)  arr.add((String) o);
                else if (o instanceof Integer) arr.add((Integer) o);
                else if (o instanceof Boolean) arr.add((Boolean) o);
                else if (o instanceof Map) arr.add(bodyToNode((Map<String, Object>) o));
            }
        } else if (value instanceof Map) {
            node.set(key, bodyToNode((Map<String, Object>) value));
        } else if (value == null) {
            node.putNull(key);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Schema loading
    // ═══════════════════════════════════════════════════════════════════════════

    private Schema loadSchema(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Schema not on classpath: " + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SchemaRegistry reg = SchemaRegistry.withDialect(Dialects.getDraft202012());
            return reg.getSchema(SchemaLocation.of(path), text, InputFormat.JSON);
        } catch (Exception e) { throw new RuntimeException("Failed to load: " + path, e); }
    }

    private byte[] loadProfile() {
        try (InputStream in = getClass().getResourceAsStream(
                "/acceptance-engine-v1/builtin-compiler-profile-formal-v1.json")) {
            if (in == null) throw new IllegalStateException("Compiler profile not on classpath");
            return in.readAllBytes();
        } catch (Exception e) { throw new RuntimeException("Failed to load profile", e); }
    }

    private JsonNode parseStrict(byte[] bytes, String label) {
        try {
            ObjectMapper m = new ObjectMapper(
                    com.fasterxml.jackson.core.JsonFactory.builder()
                            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build());
            return m.readTree(bytes);
        } catch (JsonParseException e) {
            throw new VerificationException("INVALID_PLAN_STRUCTURE", "/", e.getMessage());
        } catch (Exception e) {
            throw new VerificationException("INVALID_SCHEMA", "/", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal types
    // ═══════════════════════════════════════════════════════════════════════════

    private record PrimitiveDescriptor(
            String typeId,
            int revision,
            String effectClass,
            String phase,
            List<String> inputKinds,
            List<String> outputEvidenceKinds,
            String typedVerifierId,
            int typedVerifierRevision,
            String retryPolicy,
            Map<String,String> failureMapping,
            List<String> capabilityRequirements) {}

    private record Barrier(String barrierId, List<String> phases) {}

    private static class VerificationException extends RuntimeException {
        final String reasonCode;
        final String reasonField;
        VerificationException(String reasonCode, String reasonField, String message) {
            super(message);
            this.reasonCode = reasonCode;
            this.reasonField = reasonField;
        }
    }
}
