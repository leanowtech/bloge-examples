package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts complete business-language GOLDEN cases to the existing governed case-set authority.
 * MCP responses expose only counts, lifecycle and fingerprints; fact values and expected outcomes
 * stay inside the durable review material and current controlled execution.
 */
@Service
public final class BusinessGoldenService {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;
    private final BusinessGoldenMaterialStore materials;
    private final BusinessFixtureCompiler fixtureCompiler;

    /** Creates a focused boundary whose material vault remains fail-closed until supplied. */
    public BusinessGoldenService(AgentTddStateRepository states, ObjectMapper mapper) {
        this(states, mapper, new BusinessGoldenMaterialStore((com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService) null, mapper));
    }

    /** Creates the Spring boundary over canonical entities, case metadata and protected material. */
    @Autowired
    public BusinessGoldenService(AgentTddStateRepository states, ObjectMapper mapper,
                                 BusinessGoldenMaterialStore materials) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = new SolutionEntityRegistry(states, mapper);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.fixtureCompiler = new BusinessFixtureCompiler(registry, mapper);
    }

    /** Proposes complete cases atomically without executing them or making their Oracle effective. */
    public Map<String, Object> propose(JsonNode arguments, IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        String solutionRef = requiredText(arguments, "solutionRef");
        JsonNode cases = arguments.path("cases");
        if (!cases.isArray() || cases.isEmpty()) throw schema();
        SolutionEntityRegistry.RegisteredEntity registered = requireRegisteredSolution(scope, solutionRef);
        SolutionContract solution = registry.requireSolution(scope, solutionRef);
        String caseSetRef = "caseSet:journey:" + requiredText(arguments, "journeyRef").substring("journey:".length());
        String key = requiredText(arguments, "idempotencyKey");
        String requestFingerprint = fingerprint(arguments);
        JsonNode response = states.executeOnce(scope, "rg.solution.golden.propose", key, requestFingerprint, () -> {
            ObjectNode data = mapper.createObjectNode();
            data.put("caseSetRef", caseSetRef);
            data.put("toolRef", solutionRef);
            data.put("journeyRef", requiredText(arguments, "journeyRef"));
            ArrayNode rows = data.putArray("rows");
            cases.forEach(raw -> {
                ObjectNode compiled = compileCase(scope, solution, raw, identity);
                rows.add(protectCase(compiled, registered, requestFingerprint, identity));
            });
            AgentTddStoredAsset stored = states.save(scope, AgentTddMutationService.CASE_SET, caseSetRef, data);
            return mapper.valueToTree(Map.of("caseSetRef", caseSetRef, "revision", stored.revision(),
                    "caseSummaries", summaries(stored.data().path("rows")),
                    "proposalStatus", "PENDING", "awaiting", "human-approval"));
        });
        return mapper.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private ObjectNode protectCase(ObjectNode compiled,
                                   SolutionEntityRegistry.RegisteredEntity solution,
                                   String proposalFingerprint,
                                   IntegrationRequestContext identity) {
        String goldenFingerprint = compiled.path("goldenCaseFingerprint").asText();
        JsonNode receipt = materials.write(solution.ref(), solution.revision(),
                solution.contractFingerprint(), compiled.path("caseId").asText(), goldenFingerprint,
                proposalFingerprint, compiled, identity);
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("caseId", compiled.path("caseId").asText());
        metadata.put("category", "GOLDEN");
        metadata.put("layer", "integration");
        metadata.put("oracleOwner", compiled.path("oracleOwner").asText());
        metadata.put("lifecycle", "DRAFT");
        metadata.put("qualityState", "DESIGNED_NOT_RUN");
        metadata.put("goldenCaseFingerprint", goldenFingerprint);
        metadata.put("factCount", compiled.path("given").size());
        metadata.put("assumptionCount", compiled.path("controlledAssumptions").size());
        metadata.put("expectedShapeFingerprint", fingerprint(compiled.at("/proposedOracle/expect")));
        metadata.put("controlledAssumptionPlanFingerprint",
                compiled.path("controlledAssumptionPlanFingerprint").asText());
        metadata.put("featureValuesFingerprint", compiled.path("featureValuesFingerprint").asText());
        metadata.put("dependencyPlanFingerprint", compiled.path("dependencyPlanFingerprint").asText());
        metadata.set("businessContractVector", compiled.path("businessContractVector").deepCopy());
        metadata.set("materialReceipt", receipt);
        ObjectNode proposal = metadata.putObject("proposedOracle");
        proposal.put("status", "PENDING");
        proposal.put("oracleOwner", compiled.at("/proposedOracle/oracleOwner").asText());
        proposal.put("proposedBy", compiled.at("/proposedOracle/proposedBy").asText());
        proposal.put("proposalFingerprint", goldenFingerprint);
        return metadata;
    }

    /** Lists safe summaries without returning business case material. */
    public Map<String, Object> list(JsonNode arguments, IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        String solutionRef = requiredText(arguments, "solutionRef");
        AgentTddStoredAsset stored = states.list(scope, AgentTddMutationService.CASE_SET).stream()
                .filter(asset -> solutionRef.equals(asset.data().path("toolRef").asText()))
                .filter(asset -> requiredText(arguments, "journeyRef").equals(
                        asset.data().path("journeyRef").asText()))
                .findFirst().orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Business GOLDEN set was not found."));
        String lifecycle = text(arguments, "lifecycle").toUpperCase(java.util.Locale.ROOT);
        List<Map<String, Object>> values = summaries(stored.data().path("rows")).stream()
                .filter(value -> lifecycle.isBlank() || lifecycle.equals(value.get("lifecycle")))
                .toList();
        boolean active = values.stream().anyMatch(value -> "ACTIVE".equals(value.get("lifecycle")));
        return Map.of("caseSetRef", stored.assetRef(), "revision", stored.revision(),
                "caseSummaries", values, "approvalState", active ? "APPROVED" : "PENDING");
    }

    private ObjectNode compileCase(String scope, SolutionContract solution, JsonNode raw,
                                   IntegrationRequestContext identity) {
        String caseId = requiredText(raw, "caseId");
        String intent = requiredText(raw, "businessIntent");
        String owner = requiredText(raw, "oracleOwner");
        if (!raw.path("givenFacts").isArray() || !raw.path("expectedOutcome").isObject()
                || !raw.path("dependencyAssumptions").isArray()) throw schema();
        BusinessFixtureCompiler.ControlledAssumptionPlan plan = fixtureCompiler.compile(scope, solution, raw);
        ObjectNode expected = (ObjectNode) raw.path("expectedOutcome").deepCopy();
        if (!expected.has("result") || !expected.has("reasoningClass")) throw schema();
        ObjectNode expect = mapper.createObjectNode();
        expect.set("result", expected.path("result").deepCopy());
        expect.set("reasoning", expected.path("reasoningClass").deepCopy());
        ObjectNode fingerprintMaterial = mapper.createObjectNode();
        fingerprintMaterial.put("businessIntent", intent);
        fingerprintMaterial.set("canonicalGivenFacts", plan.given());
        fingerprintMaterial.set("canonicalDependencyAssumptions", plan.dependencyAssumptions());
        fingerprintMaterial.set("expectedOutcome", expected);
        fingerprintMaterial.put("oracleOwner", owner);
        fingerprintMaterial.set("referencedBusinessContractVector",
                mapper.valueToTree(plan.businessContractVector()));
        fingerprintMaterial.put("controlledAssumptionPlanFingerprint", plan.planFingerprint());
        fingerprintMaterial.put("caseId", caseId);
        String goldenFingerprint = fingerprint(fingerprintMaterial);

        ObjectNode row = mapper.createObjectNode();
        row.put("caseId", caseId); row.put("category", "GOLDEN"); row.put("layer", "integration");
        row.put("intent", intent); row.set("given", plan.given()); row.set("stubs", mapper.createObjectNode());
        row.set("controlledAssumptions", plan.dependencyAssumptions()); row.put("oracleOwner", owner);
        row.put("lifecycle", "DRAFT"); row.put("qualityState", "DESIGNED_NOT_RUN");
        row.put("goldenCaseFingerprint", goldenFingerprint);
        row.set("businessContractVector", mapper.valueToTree(plan.businessContractVector()));
        row.put("featureValuesFingerprint", plan.featureValuesFingerprint());
        row.put("dependencyPlanFingerprint", plan.dependencyPlanFingerprint());
        row.put("controlledAssumptionPlanFingerprint", plan.planFingerprint());
        ObjectNode proposal = row.putObject("proposedOracle");
        proposal.set("expect", expect); proposal.put("oracleOwner", owner); proposal.put("status", "PENDING");
        proposal.put("proposedBy", identity.actorId()); proposal.put("proposalFingerprint", goldenFingerprint);
        return row;
    }

    private List<Map<String, Object>> summaries(JsonNode rows) {
        List<Map<String, Object>> values = new ArrayList<>();
        rows.forEach(row -> values.add(Map.of(
                "caseId", row.path("caseId").asText(), "lifecycle", row.path("lifecycle").asText(),
                "approvalState", row.at("/proposedOracle/status").asText("ABSENT"),
                "goldenCaseFingerprint", row.path("goldenCaseFingerprint").asText(),
                "factCount", row.path("factCount").asInt(row.path("given").size()),
                "assumptionCount", row.path("assumptionCount").asInt(
                        row.path("controlledAssumptions").size()),
                "expectedShapeFingerprint", row.path("expectedShapeFingerprint").asText(
                        fingerprint(row.at("/proposedOracle/expect"))))));
        return List.copyOf(values);
    }

    private SolutionEntityRegistry.RegisteredEntity requireRegisteredSolution(String scope, String ref) {
        try { return registry.requireRegisteredSolution(scope, ref); }
        catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "Solution is unavailable.");
        }
    }
    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }
    private static AgentTddToolException schema() {
        return new AgentTddToolException("SCHEMA_NONCONFORMANT", "Business GOLDEN cases are incomplete.");
    }
    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }
    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field); if (value.isBlank()) throw schema(); return value;
    }
}
