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
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

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

    /** Creates the business GOLDEN boundary over canonical Solution entities and case sets. */
    public BusinessGoldenService(AgentTddStateRepository states, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = new SolutionEntityRegistry(states, mapper);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Proposes complete cases atomically without executing them or making their Oracle effective. */
    public Map<String, Object> propose(JsonNode arguments, IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        String solutionRef = requiredText(arguments, "solutionRef");
        JsonNode cases = arguments.path("cases");
        if (!cases.isArray() || cases.isEmpty()) throw schema();
        SolutionContract solution = requireSolution(scope, solutionRef);
        String caseSetRef = "caseSet:journey:" + requiredText(arguments, "journeyRef").substring("journey:".length());
        String key = requiredText(arguments, "idempotencyKey");
        String requestFingerprint = fingerprint(arguments);
        JsonNode response = states.executeOnce(scope, "rg.solution.golden.propose", key, requestFingerprint, () -> {
            ObjectNode data = mapper.createObjectNode();
            data.put("caseSetRef", caseSetRef);
            data.put("toolRef", solutionRef);
            data.put("journeyRef", requiredText(arguments, "journeyRef"));
            ArrayNode rows = data.putArray("rows");
            cases.forEach(raw -> rows.add(compileCase(scope, solution, raw, identity)));
            AgentTddStoredAsset stored = states.save(scope, AgentTddMutationService.CASE_SET, caseSetRef, data);
            return mapper.valueToTree(Map.of("caseSetRef", caseSetRef, "revision", stored.revision(),
                    "caseSummaries", summaries(stored.data().path("rows")),
                    "proposalStatus", "PENDING", "awaiting", "human-approval"));
        });
        return mapper.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() { });
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
        ObjectNode given = mapper.createObjectNode();
        ArrayNode contractVector = mapper.createArrayNode();
        raw.path("givenFacts").forEach(fact -> {
            String name = requiredText(fact, "factName");
            List<Map.Entry<String, String>> matches = solution.inputs().entrySet().stream()
                    .filter(entry -> featureMatches(registry.requireFeature(scope, entry.getValue()), name, entry))
                    .toList();
            if (matches.size() != 1 || !fact.has("value")) throw ambiguous("fact");
            var match = matches.getFirst();
            FeatureContract feature = registry.requireFeature(scope, match.getValue());
            given.set(match.getKey(), fact.path("value").deepCopy());
            ObjectNode coordinate = contractVector.addObject();
            coordinate.put("semanticKey", feature.businessDefinition().semanticKey());
            coordinate.put("contractFingerprint", fingerprint(feature.contractIdentity()));
        });
        ObjectNode assumptions = mapper.createObjectNode();
        raw.path("dependencyAssumptions").forEach(assumption -> {
            String name = requiredText(assumption, "capabilityName");
            List<InstructionContract> matches = solution.instructions().stream()
                    .map(ref -> registry.requireInstruction(scope, ref))
                    .filter(instruction -> instruction.instructionRef().equals(name)
                            || instruction.businessSemantics().equalsIgnoreCase(name)).toList();
            if (matches.size() != 1) throw ambiguous("dependency");
            String outcome = requiredText(assumption, "outcome").toUpperCase(java.util.Locale.ROOT);
            if (!List.of("RETURNS", "UNAVAILABLE", "SUCCEEDS_WITHOUT_EFFECT",
                    "FAILS_WITHOUT_EFFECT", "MUST_NOT_BE_USED").contains(outcome)) throw schema();
            ObjectNode compiled = assumptions.putObject(matches.getFirst().instructionRef());
            compiled.put("outcome", outcome);
            if (assumption.has("value")) compiled.set("value", assumption.path("value").deepCopy());
            ObjectNode coordinate = contractVector.addObject();
            coordinate.put("semanticKey", matches.getFirst().instructionRef());
            coordinate.put("contractFingerprint", fingerprint(matches.getFirst().contractIdentity()));
        });
        ObjectNode expected = (ObjectNode) raw.path("expectedOutcome").deepCopy();
        if (!expected.has("result") || !expected.has("reasoningClass")) throw schema();
        ObjectNode expect = mapper.createObjectNode();
        expect.set("result", expected.path("result").deepCopy());
        expect.set("reasoning", expected.path("reasoningClass").deepCopy());
        ObjectNode fingerprintMaterial = mapper.createObjectNode();
        fingerprintMaterial.put("businessIntent", intent);
        fingerprintMaterial.set("canonicalGivenFacts", given);
        fingerprintMaterial.set("canonicalDependencyAssumptions", assumptions);
        fingerprintMaterial.set("expectedOutcome", expected);
        fingerprintMaterial.put("oracleOwner", owner);
        fingerprintMaterial.set("referencedBusinessContractVector", contractVector);
        String goldenFingerprint = fingerprint(fingerprintMaterial);

        ObjectNode row = mapper.createObjectNode();
        row.put("caseId", caseId); row.put("category", "GOLDEN"); row.put("layer", "integration");
        row.put("intent", intent); row.set("given", given); row.set("stubs", mapper.createObjectNode());
        row.set("controlledAssumptions", assumptions); row.put("oracleOwner", owner);
        row.put("lifecycle", "DRAFT"); row.put("qualityState", "DESIGNED_NOT_RUN");
        row.put("goldenCaseFingerprint", goldenFingerprint);
        ObjectNode proposal = row.putObject("proposedOracle");
        proposal.set("expect", expect); proposal.put("oracleOwner", owner); proposal.put("status", "PENDING");
        proposal.put("proposedBy", identity.actorId()); proposal.put("proposalFingerprint", goldenFingerprint);
        return row;
    }

    private boolean featureMatches(FeatureContract feature, String name, Map.Entry<String, String> input) {
        return input.getKey().equalsIgnoreCase(name) || input.getValue().equalsIgnoreCase(name)
                || feature.businessSemantics().equalsIgnoreCase(name)
                || feature.businessDefinition().semanticKey().equalsIgnoreCase(name);
    }

    private List<Map<String, Object>> summaries(JsonNode rows) {
        List<Map<String, Object>> values = new ArrayList<>();
        rows.forEach(row -> values.add(Map.of(
                "caseId", row.path("caseId").asText(), "lifecycle", row.path("lifecycle").asText(),
                "approvalState", row.at("/proposedOracle/status").asText("ABSENT"),
                "goldenCaseFingerprint", row.path("goldenCaseFingerprint").asText(),
                "factCount", row.path("given").size(),
                "assumptionCount", row.path("controlledAssumptions").size(),
                "expectedShapeFingerprint", fingerprint(row.at("/proposedOracle/expect")))));
        return List.copyOf(values);
    }

    private SolutionContract requireSolution(String scope, String ref) {
        try { return registry.requireSolution(scope, ref); }
        catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "Solution is unavailable.");
        }
    }
    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }
    private static AgentTddToolException ambiguous(String kind) {
        return new AgentTddToolException("BUSINESS_ASSUMPTION_AMBIGUOUS",
                "A business " + kind + " did not resolve to exactly one current capability.");
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
