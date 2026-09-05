package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.SolutionTestingService;
import com.leanowtech.bloge.gateway.agenttdd.SolutionGovernanceService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Persists business journey identity and derives the current stage from associated authoritative
 * assets. Stage, allowed tools and blockers are never stored as client-controlled state.
 */
@Service
public final class BusinessJourneyService {
    public static final String JOURNEY = "BUSINESS_JOURNEY";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final AgentTddStateRepository states;
    private final ObjectMapper mapper;

    /** Creates deterministic navigation over the existing durable asset repository. */
    public BusinessJourneyService(AgentTddStateRepository states, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates an idempotent journey while storing only a fingerprint of the business goal. */
    public Map<String, Object> start(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String intentKind = requiredText(arguments, "intentKind");
        if (!List.of("CREATE_SOLUTION", "REVISE_SOLUTION", "RUN_SOLUTION", "REVIEW", "PUBLISH",
                "INSPECT_OPERATIONS", "MAINTAIN_PLATFORM_CAPABILITY").contains(intentKind)) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Unsupported journey intent.");
        }
        String goal = requiredText(arguments, "businessGoal");
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        String scope = AgentTddMutationService.scopeKey(identity);
        String requestFingerprint = fingerprint(arguments);
        JsonNode response = states.executeOnce(scope, "rg.journey.start", idempotencyKey, requestFingerprint, () -> {
            String journeyRef = "journey:" + UUID.randomUUID();
            ObjectNode data = mapper.createObjectNode();
            data.put("schemaVersion", "rg.businessJourney.v1");
            data.put("journeyRef", journeyRef);
            data.put("intentKind", intentKind);
            data.put("surface", "BUSINESS_SOLUTION");
            data.put("businessGoalFingerprint", fingerprint(Map.of("businessGoal", goal)));
            data.put("targetSolutionRef", text(arguments, "targetRef"));
            data.put("createdBy", identity.actorId());
            data.put("status", "ACTIVE");
            data.putArray("associations");
            AgentTddStoredAsset stored = states.save(scope, JOURNEY, journeyRef, data);
            return mapper.valueToTree(project(stored));
        });
        return mapper.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    /** Re-derives one journey and rejects a stale client revision. */
    public Map<String, Object> next(JsonNode arguments, IntegrationRequestContext identity) {
        AgentTddStoredAsset journey = require(arguments, identity);
        long expected = arguments.path("expectedRevision").asLong(-1);
        if (expected != journey.revision()) throw stale();
        return project(journey);
    }

    /**
     * Executes one journey-scoped action under a revision lock, then associates the returned asset
     * and advances only the journey revision in the same repository atomic unit.
     */
    public Map<String, Object> executeAction(String toolName, JsonNode arguments,
                                             IntegrationRequestContext identity,
                                             Supplier<Map<String, Object>> action) {
        AgentTddStoredAsset observed = require(arguments, identity);
        long expected = arguments.path("expectedJourneyRevision").asLong(-1);
        if (expected != observed.revision()) throw stale();
        String scope = AgentTddMutationService.scopeKey(identity);
        return states.executeAtomically(() -> {
            AgentTddStoredAsset locked = states.lockRevision(scope, JOURNEY, observed.assetRef(), expected);
            Projection projection = derive(locked);
            if (!projection.allowedNextTools().contains(toolName)) {
                throw new AgentTddToolException("JOURNEY_ACTION_NOT_ALLOWED",
                        "The requested action is not allowed in the current journey stage.");
            }
            if ("rg.solution.compose".equals(toolName)) {
                String supplied = requiredText(arguments, "solutionContextFingerprint");
                if (!supplied.equals(projection.solutionContextFingerprint())) {
                    throw new AgentTddToolException("SOLUTION_CONTEXT_STALE",
                            "Business solution context changed before composition.", Map.of(), true);
                }
                if (arguments instanceof ObjectNode mutable) {
                    mutable.put("authoringContextFingerprint", supplied);
                }
            }
            Map<String, Object> result = action.get();
            ObjectNode updated = (ObjectNode) locked.data().deepCopy();
            associate(updated, result);
            states.saveIfRevision(scope, JOURNEY, locked.assetRef(), expected, updated);
            return result;
        });
    }

    /** Associates an explicit case set after a governed GOLDEN proposal. */
    public void associateCaseSet(JsonNode arguments, String caseSetRef,
                                 IntegrationRequestContext identity) {
        executeAction("rg.solution.golden.propose", arguments, identity,
                () -> Map.of("caseSetRef", caseSetRef));
    }

    /** Resolves the case set associated with a journey so business baseline needs no technical ref. */
    public String associatedCaseSet(JsonNode arguments, IntegrationRequestContext identity) {
        AgentTddStoredAsset journey = require(arguments, identity);
        for (JsonNode association : journey.data().path("associations")) {
            if ("CASE_SET".equals(association.path("assetKind").asText())) {
                return association.path("assetRef").asText();
            }
        }
        throw new AgentTddToolException("GOLDEN_REQUIRES_APPROVAL", "No business GOLDEN set is associated.");
    }

    private AgentTddStoredAsset require(JsonNode arguments, IntegrationRequestContext identity) {
        String ref = requiredText(arguments, "journeyRef");
        return states.find(AgentTddMutationService.scopeKey(identity), JOURNEY, ref)
                .orElseThrow(() -> new AgentTddToolException("JOURNEY_NOT_FOUND", "Journey was not found."));
    }

    private Map<String, Object> project(AgentTddStoredAsset journey) {
        Projection projection = derive(journey);
        return Map.ofEntries(
                Map.entry("journeyRef", journey.assetRef()), Map.entry("revision", journey.revision()),
                Map.entry("surface", "BUSINESS_SOLUTION"), Map.entry("stage", projection.stage()),
                Map.entry("stageStatus", projection.stageStatus()),
                Map.entry("requiredBusinessDimensions", List.of(
                        "decisionFacts", "rules", "otherwise", "dispositions", "goldenExamples")),
                Map.entry("facts", projection.facts()), Map.entry("blockingReasons", projection.blockingReasons()),
                Map.entry("allowedNextTools", projection.allowedNextTools()),
                Map.entry("forbiddenUntilResolved", projection.forbiddenUntilResolved()),
                Map.entry("solutionContextFingerprint", projection.solutionContextFingerprint()),
                Map.entry("responsibleRole", projection.responsibleRole()),
                Map.entry("businessQuestion", projection.businessQuestion()),
                Map.entry("nextAction", projection.nextAction()));
    }

    private Projection derive(AgentTddStoredAsset journey) {
        String scope = journey.scopeKey();
        Map<String, List<String>> refs = associations(journey.data().path("associations"));
        boolean feature = !refs.getOrDefault("FEATURE", List.of()).isEmpty();
        boolean scenario = !refs.getOrDefault("SCENARIO", List.of()).isEmpty();
        boolean instruction = !refs.getOrDefault("INSTRUCTION", List.of()).isEmpty();
        boolean solution = !refs.getOrDefault("SOLUTION", List.of()).isEmpty();
        boolean caseSet = !refs.getOrDefault("CASE_SET", List.of()).isEmpty();
        boolean activeGolden = refs.getOrDefault("CASE_SET", List.of()).stream()
                .flatMap(ref -> states.find(scope, AgentTddMutationService.CASE_SET, ref).stream())
                .anyMatch(asset -> iterable(asset.data().path("rows")).stream().anyMatch(row ->
                        "GOLDEN".equals(row.path("category").asText())
                                && "ACTIVE".equals(row.path("lifecycle").asText())));
        boolean evidence = refs.getOrDefault("SOLUTION", List.of()).stream()
                .anyMatch(ref -> hasCurrentGreenEvidence(scope, ref, refs));
        boolean speccingFeature = refs.getOrDefault("FEATURE", List.of()).stream()
                .flatMap(ref -> states.find(scope, SolutionEntityRegistry.FEATURE, ref).stream())
                .anyMatch(asset -> asset.data().path("speccing").asBoolean());
        boolean speccingInstruction = refs.getOrDefault("INSTRUCTION", List.of()).stream()
                .flatMap(ref -> states.find(scope, SolutionEntityRegistry.INSTRUCTION, ref).stream())
                .anyMatch(asset -> asset.data().path("speccing").asBoolean());

        if (!feature) return projection("DISCOVERING", "READY",
                List.of("rg.library.overview.get", "rg.capability.search", "rg.entity.list", "rg.entity.get",
                        "rg.feature.define", "rg.journey.next"), List.of(), "BUSINESS_OWNER",
                "这项政策需要依据哪些业务事实作判断？", "确认已有事实能力或定义新的业务事实。", journey);
        if (speccingFeature) return projection("WAITING_FEATURE_ENGINEERING", "BLOCKED",
                List.of("rg.feature.define", "rg.feature.handoff", "rg.entity.get", "rg.journey.next"),
                List.of("rg.scenario.define", "rg.solution.compose"), "FEATURE_ENGINEER", "",
                "等待特征工程完成，不需要业务负责人补充技术信息。", journey);
        if (!scenario) return projection("DEFINING_RULES", "READY",
                List.of("rg.feature.define", "rg.scenario.define", "rg.journey.next"), List.of("rg.solution.compose"),
                "BUSINESS_OWNER", "这些事实如何共同决定业务处置？", "定义规则和兜底处置。", journey);
        if (!instruction) return projection("DEFINING_ACTIONS", "READY",
                List.of("rg.instruction.define", "rg.journey.next"), List.of("rg.solution.compose"),
                "BUSINESS_OWNER", "每条规则应产生什么结果并如何解释？", "定义处置及解释。", journey);
        if (!solution) return projection("COMPOSING", "READY",
                List.of("rg.instruction.define", "rg.solution.compose", "rg.journey.next"),
                List.of("rg.solution.commit"),
                "CODING_AGENT", "", "组合当前事实、规则和处置。", journey);
        if (!caseSet || !activeGolden) return projection("WAITING_GOLDEN_APPROVAL", "BLOCKED",
                List.of("rg.solution.golden.propose", "rg.solution.golden.list", "rg.journey.next"),
                List.of("rg.solution.baseline", "rg.solution.commit"), "BUSINESS_OWNER", "",
                "提交并独立批准完整业务案例。", journey);
        if (!evidence) return projection("TESTING", "READY",
                List.of("rg.solution.golden.list", "rg.solution.baseline", "rg.journey.next"),
                List.of("rg.solution.commit"), "CODING_AGENT", "", "运行受控 RED/GREEN 基线。", journey);
        if (speccingInstruction) return projection("WAITING_WRITE_ENGINEERING", "BLOCKED",
                List.of("rg.engineering.handoff", "rg.solution.readiness", "rg.journey.next"),
                List.of("rg.solution.commit"), "INSTRUCTION_ENGINEER", "",
                "等待写能力实现和对账，不需要业务负责人提供技术定义。", journey);
        boolean signed = refs.getOrDefault("SOLUTION", List.of()).stream()
                .anyMatch(solutionRef -> hasCurrentSignoff(scope, solutionRef));
        boolean published = refs.getOrDefault("SOLUTION", List.of()).stream().anyMatch(solutionRef ->
                states.list(scope, SolutionGovernanceService.PUBLICATION).stream().anyMatch(publication ->
                        solutionRef.equals(publication.data().path("solutionRef").asText())));
        if (published) return projection("PUBLISHED", "READY",
                List.of("rg.solution.readiness", "rg.solution.performance", "rg.journey.next"), List.of(),
                "BUSINESS_OWNER", "", "观察当前版本的业务结果。", journey);
        if (signed) return projection("PUBLISHABLE", "READY",
                List.of("rg.solution.readiness", "rg.solution.publish", "rg.journey.next"), List.of(),
                "BUSINESS_OWNER", "", "确认当前门禁并发布不可变版本。", journey);
        return projection("WAITING_SIGNOFF", "READY",
                List.of("rg.solution.commit", "rg.solution.readiness", "rg.journey.next"), List.of(),
                "BUSINESS_OWNER", "", "提交当前证据供独立签署。", journey);
    }

    private boolean hasCurrentGreenEvidence(String scope, String solutionRef,
                                            Map<String, List<String>> refs) {
        AgentTddStoredAsset evidence = states.find(scope, SolutionTestingService.SOLUTION_EVIDENCE, solutionRef)
                .orElse(null);
        AgentTddStoredAsset solution = states.find(scope, SolutionEntityRegistry.SOLUTION, solutionRef)
                .orElse(null);
        if (evidence == null || solution == null || !"GREEN".equals(evidence.data().path("side").asText())
                || !evidence.data().path("businessBacklog").isArray()
                || !evidence.data().path("businessBacklog").isEmpty()
                || evidence.data().path("solutionRevision").asLong(-1) != solution.revision()
                || !solution.data().path("contractFingerprint").asText()
                    .equals(evidence.data().path("solutionContractFingerprint").asText())) return false;
        String caseSetRef = evidence.data().path("caseSetRef").asText();
        return refs.getOrDefault("CASE_SET", List.of()).contains(caseSetRef)
                && states.find(scope, AgentTddMutationService.CASE_SET, caseSetRef)
                .map(current -> current.revision() == evidence.data().path("caseSetRevision").asLong(-1))
                .orElse(false);
    }

    private boolean hasCurrentSignoff(String scope, String solutionRef) {
        AgentTddStoredAsset solution = states.find(scope, SolutionEntityRegistry.SOLUTION, solutionRef)
                .orElse(null);
        AgentTddStoredAsset evidence = states.find(scope, SolutionTestingService.SOLUTION_EVIDENCE, solutionRef)
                .orElse(null);
        if (solution == null || evidence == null) return false;
        return states.list(scope, SolutionGovernanceService.SIGNOFF).stream().anyMatch(signoff ->
                "APPROVED".equals(signoff.data().path("status").asText())
                        && solutionRef.equals(signoff.data().path("solutionRef").asText())
                        && signoff.data().path("solutionRevision").asLong(-1) == solution.revision()
                        && solution.data().path("contractFingerprint").asText().equals(
                                signoff.data().path("solutionContractFingerprint").asText())
                        && evidence.data().path("goldenSetId").asText().equals(
                                signoff.data().path("goldenSetId").asText())
                        && evidence.fingerprint().equals(signoff.data().path("evidenceFingerprint").asText()));
    }

    private Projection projection(String stage, String status, List<String> allowed, List<String> forbidden,
                                  String role, String question, String action, AgentTddStoredAsset journey) {
        String context = "COMPOSING".equals(stage) || journey.data().path("associations").size() >= 3
                ? currentContext(journey) : "";
        return new Projection(stage, status, List.of(), status.equals("BLOCKED")
                ? List.of(stage.equals("WAITING_FEATURE_ENGINEERING") ? "FEATURE_BINDING_REQUIRED"
                : "GOLDEN_REQUIRES_APPROVAL") : List.of(), allowed, forbidden, context, role, question, action);
    }

    /** Fingerprints the current associated contracts, not the revisions observed when associated. */
    private String currentContext(AgentTddStoredAsset journey) {
        List<Map<String, Object>> vector = new ArrayList<>();
        for (JsonNode association : journey.data().path("associations")) {
            String kind = association.path("assetKind").asText();
            String ref = association.path("assetRef").asText();
            states.find(journey.scopeKey(), storageKind(kind), ref).ifPresent(asset -> vector.add(Map.of(
                    "kind", kind, "ref", ref, "revision", asset.revision(),
                    "contractFingerprint", asset.data().path("contractFingerprint").asText(""))));
        }
        return fingerprint(vector);
    }

    private static String storageKind(String associationKind) {
        return switch (associationKind) {
            case "FEATURE" -> SolutionEntityRegistry.FEATURE;
            case "SCENARIO" -> SolutionEntityRegistry.SCENARIO;
            case "INSTRUCTION" -> SolutionEntityRegistry.INSTRUCTION;
            case "SOLUTION" -> SolutionEntityRegistry.SOLUTION;
            case "CASE_SET" -> AgentTddMutationService.CASE_SET;
            default -> associationKind;
        };
    }

    private void associate(ObjectNode journey, Map<String, Object> result) {
        String kind = "";
        String ref = "";
        for (var coordinate : List.of(
                Map.entry("featureId", "FEATURE"), Map.entry("scenarioId", "SCENARIO"),
                Map.entry("instructionId", "INSTRUCTION"), Map.entry("solutionRef", "SOLUTION"),
                Map.entry("caseSetRef", "CASE_SET"))) {
            Object value = result.get(coordinate.getKey());
            if (value != null && !value.toString().isBlank()) { kind = coordinate.getValue(); ref = value.toString(); break; }
        }
        if (ref.isBlank()) return;
        ArrayNode next = mapper.createArrayNode();
        for (JsonNode current : journey.path("associations")) {
            if (!(kind.equals(current.path("assetKind").asText()) && ref.equals(current.path("assetRef").asText())))
                next.add(current.deepCopy());
        }
        ObjectNode association = next.addObject();
        association.put("assetKind", kind); association.put("assetRef", ref);
        Object revision = result.get("revision");
        association.put("revision", revision instanceof Number number ? number.longValue() : 0);
        journey.set("associations", next);
    }

    private static Map<String, List<String>> associations(JsonNode values) {
        LinkedHashMap<String, List<String>> indexed = new LinkedHashMap<>();
        for (JsonNode value : values) indexed.computeIfAbsent(value.path("assetKind").asText(), ignored -> new ArrayList<>())
                .add(value.path("assetRef").asText());
        return indexed;
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> values = new ArrayList<>(); if (array.isArray()) array.forEach(values::add); return values;
    }
    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }
    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field); if (value.isBlank()) throw new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", field + " is required."); return value;
    }
    private static AgentTddToolException stale() {
        return new AgentTddToolException("JOURNEY_REVISION_STALE", "Journey revision changed.", Map.of(), true);
    }

    private record Projection(String stage, String stageStatus, List<Map<String, Object>> facts,
                              List<String> blockingReasons, List<String> allowedNextTools,
                              List<String> forbiddenUntilResolved, String solutionContextFingerprint,
                              String responsibleRole, String businessQuestion, String nextAction) { }
}
