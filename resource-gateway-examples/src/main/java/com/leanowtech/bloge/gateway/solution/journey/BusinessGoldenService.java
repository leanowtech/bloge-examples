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
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts complete business-language GOLDEN cases to the governed case-set authority.
 * MCP responses expose only counts, lifecycle and fingerprints. The protected material contains
 * the original business case, while every implementation-bound controlled plan is ephemeral and
 * can be recompiled after rules or bindings change without changing the business approval.
 */
@Service
public final class BusinessGoldenService {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final AgentTddStateRepository states;
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
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.fixtureCompiler = new BusinessFixtureCompiler(states, mapper);
    }

    /** Proposes complete cases atomically without executing them or making their Oracle effective. */
    public Map<String, Object> propose(JsonNode arguments, IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        String solutionRef = requiredText(arguments, "solutionRef");
        JsonNode cases = arguments.path("cases");
        if (!cases.isArray() || cases.isEmpty()) throw schema();
        String caseSetRef = "caseSet:journey:" + requiredText(arguments, "journeyRef").substring("journey:".length());
        String key = requiredText(arguments, "idempotencyKey");
        String requestFingerprint = fingerprint(arguments);
        JsonNode response = states.executeOnce(scope, "rg.solution.golden.propose", key, requestFingerprint, () -> {
            ObjectNode data = mapper.createObjectNode();
            data.put("caseSetRef", caseSetRef);
            data.put("toolRef", solutionRef);
            data.put("journeyRef", requiredText(arguments, "journeyRef"));
            ArrayNode rows = data.putArray("rows");
            cases.forEach(raw -> rows.add(protectCase(
                    prepareCase(scope, solutionRef, raw), requestFingerprint, identity)));
            AgentTddStoredAsset stored = states.save(scope, AgentTddMutationService.CASE_SET, caseSetRef, data);
            return mapper.valueToTree(Map.of("caseSetRef", caseSetRef, "revision", stored.revision(),
                    "caseSummaries", summaries(stored.data().path("rows")),
                    "proposalStatus", "PENDING", "awaiting", "human-approval"));
        });
        return mapper.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private ObjectNode protectCase(PreparedCase prepared,
                                   String proposalFingerprint,
                                   IntegrationRequestContext identity) {
        ObjectNode businessCase = prepared.businessCase();
        BusinessFixtureCompiler.BusinessCaseValidation validation = prepared.validation();
        String businessFingerprint = businessCase.path("businessCaseFingerprint").asText();
        String goldenFingerprint = businessApprovalFingerprint(businessCase, validation);
        businessCase.put("goldenCaseFingerprint", goldenFingerprint);
        JsonNode receipt = materials.write(validation.solutionRef(), validation.solutionRevision(),
                validation.solutionContractFingerprint(), businessCase.path("caseId").asText(),
                goldenFingerprint, proposalFingerprint, businessCase, identity);
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("caseId", businessCase.path("caseId").asText());
        metadata.put("category", "GOLDEN");
        metadata.put("layer", "integration");
        metadata.put("oracleOwner", businessCase.path("oracleOwner").asText());
        metadata.put("lifecycle", "DRAFT");
        metadata.put("qualityState", "DESIGNED_NOT_RUN");
        metadata.put("businessCaseFingerprint", businessFingerprint);
        metadata.put("goldenCaseFingerprint", goldenFingerprint);
        metadata.put("factCount", businessCase.path("givenFacts").size());
        metadata.put("assumptionCount", businessCase.path("dependencyAssumptions").size());
        metadata.put("expectedShapeFingerprint", fingerprint(businessCase.path("expectedOutcome")));
        metadata.set("businessContractVector",
                mapper.valueToTree(validation.businessContractVector()));
        metadata.set("materialReceipt", receipt);
        ObjectNode proposal = metadata.putObject("proposedOracle");
        proposal.put("status", "PENDING");
        proposal.put("oracleOwner", businessCase.path("oracleOwner").asText());
        proposal.put("proposedBy", identity.actorId());
        proposal.put("proposalFingerprint", goldenFingerprint);
        return metadata;
    }

    private String businessApprovalFingerprint(
            JsonNode businessCase, BusinessFixtureCompiler.BusinessCaseValidation validation) {
        ObjectNode approval = mapper.createObjectNode();
        approval.put("businessCaseFingerprint", businessCase.path("businessCaseFingerprint").asText());
        approval.set("referencedBusinessContractVector",
                mapper.valueToTree(validation.businessContractVector()));
        return fingerprint(approval);
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

    private PreparedCase prepareCase(String scope, String solutionRef, JsonNode raw) {
        String caseId = requiredText(raw, "caseId");
        String intent = requiredText(raw, "businessIntent");
        String owner = requiredText(raw, "oracleOwner");
        if (!raw.path("givenFacts").isArray() || !raw.path("expectedOutcome").isObject()
                || !raw.path("dependencyAssumptions").isArray()) throw schema();
        BusinessFixtureCompiler.BusinessCaseValidation validation =
                fixtureCompiler.validateBusinessCase(scope, solutionRef, raw);
        ObjectNode expected = (ObjectNode) raw.path("expectedOutcome").deepCopy();
        if (!expected.has("result") || !expected.has("reasoningClass")) throw schema();
        ObjectNode businessCase = mapper.createObjectNode();
        businessCase.put("caseId", caseId);
        businessCase.put("businessIntent", intent);
        businessCase.set("givenFacts", raw.path("givenFacts").deepCopy());
        businessCase.set("dependencyAssumptions", raw.path("dependencyAssumptions").deepCopy());
        businessCase.set("expectedOutcome", expected);
        businessCase.put("oracleOwner", owner);
        businessCase.put("businessCaseFingerprint", fingerprint(businessCase));
        return new PreparedCase(businessCase, validation);
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

    /** Separates the durable business approval subject from its disposable execution plan. */
    private record PreparedCase(
            ObjectNode businessCase,
            BusinessFixtureCompiler.BusinessCaseValidation validation
    ) {
        private PreparedCase {
            businessCase = Objects.requireNonNull(businessCase, "businessCase").deepCopy();
            validation = Objects.requireNonNull(validation, "validation");
        }

        @Override
        public ObjectNode businessCase() {
            return businessCase.deepCopy();
        }
    }
}
