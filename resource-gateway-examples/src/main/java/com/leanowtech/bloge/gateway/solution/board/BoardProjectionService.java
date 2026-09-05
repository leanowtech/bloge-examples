package com.leanowtech.bloge.gateway.solution.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.SolutionGovernanceService;
import com.leanowtech.bloge.gateway.agenttdd.SolutionTestingService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.feature.FeatureHandoffService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects one governed Solution into the five business panels used for human review.
 *
 * <p>This service never returns DSL source, graph structure, implementation bindings, evaluator
 * references, or compiler objects. Case facts and expected results are joined only for the
 * separately authenticated no-store reviewer endpoint.</p>
 */
@Service
public final class BoardProjectionService {
    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final SolutionGovernanceService governance;
    private final ObjectMapper mapper;

    /** Creates the business projection over canonical contracts and durable governance evidence. */
    public BoardProjectionService(AgentTddStateRepository states, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.registry = new SolutionEntityRegistry(states, mapper);
        this.governance = new SolutionGovernanceService(states, registry, mapper);
    }

    /** Returns the current five-panel view inside the exact authenticated scope. */
    public BoardView project(String solutionRef, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String scope = AgentTddMutationService.scopeKey(identity);
        SolutionContract solution;
        ScenarioContract scenario;
        try {
            solution = registry.requireSolution(scope, solutionRef);
            scenario = registry.requireScenario(scope, solution.rootScenarioRef());
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
        Map<String, String> factLabels = featureLabels(scope, solution);
        return new BoardView(solution.solutionRef(), solution.problem(),
                ruleMatrix(scenario, factLabels), dispositions(scope, solution),
                redGreen(scope, solution), featureCards(scope, solution),
                publishCard(governance.readiness(solutionRef, identity)));
    }

    private Map<String, String> featureLabels(String scope, SolutionContract solution) {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        solution.inputs().forEach((input, featureRef) -> {
            try {
                labels.put(input, registry.requireFeature(scope, featureRef).businessSemantics());
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                labels.put(input, input);
            }
        });
        return Map.copyOf(labels);
    }

    private RuleMatrixView ruleMatrix(ScenarioContract scenario, Map<String, String> labels) {
        List<String> conditions = scenario.inputs().stream()
                .map(input -> labels.getOrDefault(input, input)).toList();
        List<RuleRow> rules = scenario.rules().stream().map(rule -> {
            LinkedHashMap<String, String> cells = new LinkedHashMap<>();
            rule.when().fields().forEachRemaining(entry -> cells.put(
                    labels.getOrDefault(entry.getKey(), entry.getKey()), predicate(entry.getValue())));
            return new RuleRow(rule.ruleId(), Map.copyOf(cells), disposition(rule.outlet()));
        }).toList();
        return new RuleMatrixView(conditions, rules, disposition(scenario.otherwise()));
    }

    private List<DispositionCard> dispositions(String scope, SolutionContract solution) {
        List<DispositionCard> cards = new ArrayList<>();
        for (String ref : solution.instructions()) {
            try {
                InstructionContract instruction = registry.requireInstruction(scope, ref);
                JsonNode fields = instruction.output().path("result").path("type").path("fields");
                List<ResultField> resultFields = new ArrayList<>();
                fields.fields().forEachRemaining(entry -> resultFields.add(
                        new ResultField(entry.getKey(), businessType(entry.getValue()))));
                resultFields.sort(Comparator.comparing(ResultField::name));
                ReconciliationCard reconciliation = instruction.writeGovernance() == null ? null
                        : new ReconciliationCard(instruction.writeGovernance().downstreamSystem(),
                        instruction.writeGovernance().reconciliationKey());
                cards.add(new DispositionCard(displayName(ref),
                        instruction.effect() == InstructionContract.Effect.WRITE
                                ? "写入业务系统" : "只读处置",
                        List.copyOf(resultFields), reconciliation,
                        instruction.speccing() ? "待工程实现" : "就绪"));
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                cards.add(new DispositionCard(displayName(ref), "未知", List.of(), null, "不可用"));
            }
        }
        return List.copyOf(cards);
    }

    private List<FeatureCard> featureCards(String scope, SolutionContract solution) {
        List<FeatureCard> cards = new ArrayList<>();
        solution.inputs().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                FeatureContract feature = registry.requireFeature(scope, entry.getValue());
                String handoffStatus = states.find(scope, FeatureHandoffService.FEATURE_HANDOFF,
                                feature.featureRef()).map(asset -> asset.data().path("status").asText())
                        .orElse("");
                cards.add(new FeatureCard(feature.featureRef(), feature.businessSemantics(),
                        businessType(feature.output().path("type")), sourceText(feature.evaluationKind()),
                        feature.speccing() ? "设计态" : "就绪", handoffStatus,
                        !feature.evaluationKind().interactive()));
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                cards.add(new FeatureCard(entry.getValue(), entry.getKey(), "未知", "未知",
                        "不可用", "", false));
            }
        });
        return List.copyOf(cards);
    }

    private RedGreenView redGreen(String scope, SolutionContract solution) {
        AgentTddStoredAsset evidence = states.find(
                scope, SolutionTestingService.SOLUTION_EVIDENCE, solution.solutionRef()).orElse(null);
        if (evidence == null) return new RedGreenView("未测试", new LayerCount(0, 0), List.of(), List.of());
        Map<String, JsonNode> goldenById = new LinkedHashMap<>();
        states.find(scope, AgentTddMutationService.CASE_SET,
                        evidence.data().path("caseSetRef").asText(solution.goldenRef()))
                .ifPresent(asset -> asset.data().path("rows").forEach(row ->
                        goldenById.put(row.path("caseId").asText(), row)));
        List<CaseRow> rows = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        for (JsonNode result : evidence.data().path("cases")) {
            boolean passing = result.path("verdict").asText().endsWith("PASS");
            if (passing) pass++; else fail++;
            JsonNode golden = goldenById.get(result.path("caseId").asText());
            Map<String, Object> given = golden == null ? Map.of()
                    : objectMap(golden.path("given"));
            JsonNode expectedResult = golden == null ? mapper.createObjectNode()
                    : golden.path("expect").path("result");
            rows.add(new CaseRow(result.path("caseId").asText(), given,
                    objectMap(expectedResult), displayName(result.path("instructionRef").asText()),
                    passing ? "绿" : "红"));
        }
        List<BacklogItem> backlog = new ArrayList<>();
        evidence.data().path("businessBacklog").forEach(item -> backlog.add(new BacklogItem(
                item.path("caseId").asText(), businessReason(item.path("reason").asText()),
                item.path("owner").asText())));
        return new RedGreenView("GREEN".equals(evidence.data().path("side").asText()) ? "绿线" : "红线",
                new LayerCount(pass, fail), List.copyOf(rows), List.copyOf(backlog));
    }

    private PublishCard publishCard(Map<String, Object> readiness) {
        Map<?, ?> gates = readiness.get("gates") instanceof Map<?, ?> values ? values : Map.of();
        return new PublishCard(new PublishGates(
                Boolean.TRUE.equals(gates.get("logicGreen")),
                Boolean.TRUE.equals(gates.get("implementationBound")),
                Boolean.TRUE.equals(gates.get("writeReconciled")),
                Boolean.TRUE.equals(gates.get("ownerSignoff"))),
                Boolean.TRUE.equals(readiness.get("publishable")));
    }

    private Map<String, Object> objectMap(JsonNode value) {
        return value != null && value.isObject()
                ? mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { })
                : Map.of();
    }

    private static String predicate(JsonNode value) {
        if (!value.isObject()) return "等于 " + value.asText();
        if (value.has("eq")) return "等于 " + value.path("eq").asText();
        if (value.has("ne")) return "不等于 " + value.path("ne").asText();
        if (value.has("in")) return "属于 " + value.path("in");
        return "按已声明条件判断";
    }

    private static String disposition(ScenarioContract.Outlet outlet) {
        return outlet.kind() == ScenarioContract.OutletKind.TERMINAL
                ? outlet.terminalKind() : displayName(outlet.ref());
    }

    private static String displayName(String ref) {
        String value = ref == null ? "" : ref;
        int separator = value.indexOf(':');
        if (separator >= 0) value = value.substring(separator + 1);
        return value.replace('-', ' ').trim();
    }

    private static String sourceText(FeatureContract.EvaluationKind kind) {
        return switch (kind) {
            case API -> "业务接口";
            case DAG -> "计算规则";
            case MODEL -> "模型判断";
            case INSTRUCTION_RESULT -> "处置结果";
            case USER_COMPONENT -> "用户选择";
            case USER_CONVERSATION -> "用户对话";
        };
    }

    private static String businessType(JsonNode type) {
        if (type == null || type.isMissingNode()) return "未知";
        if (type.isTextual()) return switch (type.asText().toLowerCase(java.util.Locale.ROOT)) {
            case "string" -> "文本";
            case "boolean" -> "是/否";
            case "number", "decimal" -> "数值";
            case "integer" -> "整数";
            default -> "结构化结果";
        };
        if (type.has("enum")) return "选项";
        return "结构化结果";
    }

    private static String businessReason(String code) {
        return code != null && code.endsWith("FAIL") ? "实际处置与应然不一致" : "需要业务复核";
    }

    /** Complete five-panel business review view. */
    public record BoardView(String solutionName, String problem, RuleMatrixView ruleMatrix,
                            List<DispositionCard> dispositions, RedGreenView redGreen,
                            List<FeatureCard> featureCards, PublishCard publishCard) { }
    /** Business rule table. */
    public record RuleMatrixView(List<String> conditions, List<RuleRow> rules, String otherwise) { }
    /** One rule row expressed as fact labels and plain predicates. */
    public record RuleRow(String ruleId, Map<String, String> cells, String disposition) { }
    /** One possible business disposition. */
    public record DispositionCard(String instructionName, String effectText,
                                  List<ResultField> resultFields,
                                  ReconciliationCard reconciliation, String state) { }
    /** One named field in a disposition result. */
    public record ResultField(String name, String type) { }
    /** Business-visible write reconciliation coordinates. */
    public record ReconciliationCard(String downstream, String reconciliationKey) { }
    /** Latest red/green review panel. */
    public record RedGreenView(String side, LayerCount byLayer,
                               List<CaseRow> cases, List<BacklogItem> backlog) { }
    /** Passing and failing counts for the current integrated business line. */
    public record LayerCount(int pass, int fail) { }
    /** One human-review case joined from GOLDEN and payload-free evidence. */
    public record CaseRow(String caseId, Map<String, Object> givenFacts,
                          Map<String, Object> expected, String actual, String verdict) { }
    /** One business-owned correction item. */
    public record BacklogItem(String caseId, String reason, String owner) { }
    /** One Feature card with no implementation reference or token. */
    public record FeatureCard(String featureName, String fact, String type, String sourceText,
                              String state, String handoffStatus, boolean tokenCapability) { }
    /** Publication decision panel. */
    public record PublishCard(PublishGates gates, boolean publishable) { }
    /** Business publication gate statuses. */
    public record PublishGates(boolean logicGreen, boolean implementationBound,
                               boolean writeReconciled, boolean ownerSignoff) { }
}
