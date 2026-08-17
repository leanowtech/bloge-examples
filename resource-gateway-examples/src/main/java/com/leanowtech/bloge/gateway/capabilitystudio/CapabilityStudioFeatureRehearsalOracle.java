package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Development-only business Oracle for the frozen Feature rehearsal cases.
 *
 * <p>The Oracle is intentionally outside the BLOGE runtime. It consumes the projection of the
 * actual {@code TestRunEvidence}; it never changes the graph, injects an output, or turns a
 * runtime {@code PASSED} into business success without checking the business invariant.</p>
 */
public final class CapabilityStudioFeatureRehearsalOracle {
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    private final ObjectMapper objectMapper;

    public CapabilityStudioFeatureRehearsalOracle(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").findAndRegisterModules();
    }

    public CapabilityStudioFeatureRehearsalOracle() {
        this(new ObjectMapper());
    }

    /** Evaluates one case. Payload-visible projection is required for business assertions. */
    public Evaluation evaluate(CapabilityStudioFeatureRehearsalProjection projection) {
        return evaluate(projection, List.of());
    }

    /** Internal overload receiving operator facts from the actual graph assembly. */
    Evaluation evaluate(
            CapabilityStudioFeatureRehearsalProjection projection,
            List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints) {
        Objects.requireNonNull(projection, "projection");
        if (projection.dataLens().permissionMode()
                != CapabilityStudioDataLensProjection.PermissionMode.PAYLOAD_VISIBLE) {
            return failure(projection, "Oracle requires payload-visible internal evidence",
                    "业务 Oracle 需要读取受控的内部运行结果。", "未提供 payload-visible projection");
        }
        return switch (projection.scenario().id()) {
            case "case-standard-cancellation-fee" -> standard(projection);
            case "case-rider-not-responsible" -> responsibility(projection, "RIDER",
                    "RIDER_NOT_AT_FAULT", "WAIVE_CANCELLATION_FEE", "乘客无责不产生乘客取消费用");
            case "case-driver-responsible" -> responsibility(projection, "DRIVER",
                    "DRIVER_LATE", "APPLY_DRIVER_RESPONSIBILITY_RULE", "司机责任归因与处理动作一致");
            case "case-city-policy-missing" -> cityPolicyMissing(projection);
            case "case-compensation-history-empty" -> compensationHistoryEmpty(projection);
            case "case-compensation-history-timeout" -> timeout(projection);
            case "case-forbidden-write-effect" -> forbiddenWrite(projection, operatorFootprints);
            case "case-policy-revision-regression" -> policyRevision(projection);
            case "case-duplicate-cancellation" -> failure(projection,
                    "duplicate case requires three observations",
                    "重复请求需要通过多次相同输入运行证明幂等。", "single-run evaluation is insufficient");
            default -> failure(projection, "unknown case", "未定义该案例的业务 Oracle。", "caseId");
        };
    }

    /** Evaluates the three canonical observations of one Case, including duplicate idempotency. */
    public Evaluation evaluate(List<CapabilityStudioFeatureRehearsalProjection> observations) {
        return evaluate(observations, List.of());
    }

    /** Internal batch overload preserving the graph's side-effect facts outside the v1 wire DTO. */
    Evaluation evaluate(
            List<CapabilityStudioFeatureRehearsalProjection> observations,
            List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints) {
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("observations must not be empty");
        }
        String caseId = observations.getFirst().scenario().id();
        if ("case-duplicate-cancellation".equals(caseId)) {
            return duplicate(observations);
        }
        Evaluation first = evaluate(observations.getFirst(), operatorFootprints);
        if (!PASS.equals(first.status())) {
            return first;
        }
        String semantic = observations.getFirst().run().semanticFingerprint();
        String expectedTerminalStatus = expectedTerminalStatus(caseId);
        boolean stable = observations.stream().allMatch(value ->
                semantic.equals(value.run().semanticFingerprint())
                        && expectedTerminalStatus.equals(value.run().status())
                        && value.run().realExternalCallCount() == 0);
        if (!stable) {
            return failure(observations.getFirst(),
                    "business fingerprint or runtime status changed between rounds",
                    "三轮业务结论必须稳定且不产生真实外部调用。", "round stability");
        }
        return first;
    }

    /** Returns a business-only fingerprint independent of run id, duration, and payload details. */
    String businessFingerprint(CapabilityStudioFeatureRehearsalProjection projection) {
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("caseId", projection.scenario().id());
        business.put("runStatus", projection.run().status());
        business.put("decision", decisionBusinessMaterial(projection));
        business.put("timeoutNode", nodeStatus(projection, "compensationHistoryLookup"));
        return ProtocolFingerprint.of(objectMapper, business);
    }

    private Evaluation standard(CapabilityStudioFeatureRehearsalProjection projection) {
        return expectDecision(projection, "oracle-standard-cancellation-fee",
                "标准案例应按城市政策给出自动报价结论。", "AUTO_QUOTE",
                "CANCELLATION_CONTEXT_READY", "NONE", "标准取消费用业务结论正确");
    }

    private Evaluation responsibility(
            CapabilityStudioFeatureRehearsalProjection projection,
            String owner,
            String reason,
            String action,
            String expected) {
        Map<?, ?> responsibility = nodeOutput(projection, "responsibilityLookup");
        if (!owner.equals(text(responsibility.get("owner")))
                || !reason.equals(text(responsibility.get("reasonCode")))) {
            return failure(projection, "responsibility fixture output does not match the case",
                    expected, "owner/reasonCode diverged");
        }
        return expectDecision(projection,
                "oracle-" + projection.scenario().id().substring("case-".length()),
                expected, action, reason, "NONE", expected);
    }

    private Evaluation cityPolicyMissing(CapabilityStudioFeatureRehearsalProjection projection) {
        if (!nodeOutput(projection, "cityPolicyLookup").isEmpty()) {
            return failure(projection, "policy lookup was not empty",
                    "缺失城市政策必须阻断自动报价并转人工。", "policy payload was present");
        }
        return expectDecision(projection, "oracle-city-policy-missing",
                "缺失城市政策必须阻断自动报价并转人工。", "MANUAL_REVIEW",
                "CITY_POLICY_MISSING", "NONE", "城市政策缺失安全降级正确");
    }

    private Evaluation compensationHistoryEmpty(CapabilityStudioFeatureRehearsalProjection projection) {
        Map<?, ?> history = nodeOutput(projection, "compensationHistoryLookup");
        Map<?, ?> decision = nodeOutput(projection, "cancellationDecision");
        boolean empty = history.isEmpty()
                || (Boolean.FALSE.equals(history.get("hasHistory"))
                && history.get("records") instanceof List<?> records && records.isEmpty());
        if (!empty || !"COMPENSATION_HISTORY_EMPTY".equals(text(decision.get("informationGap")))) {
            return failure(projection, "empty compensation history was not explicitly surfaced",
                    "空历史必须被表达为信息缺口，不能被误判为已有补偿。", decisionSummary(decision));
        }
        return pass(projection, "oracle-compensation-history-empty",
                "空历史必须被表达为信息缺口。", "补偿历史信息缺口已被显式表达。");
    }

    private Evaluation timeout(CapabilityStudioFeatureRehearsalProjection projection) {
        String compensationStatus = nodeStatus(projection, "compensationHistoryLookup");
        boolean downstreamCancelled = List.of("aggregateCancellationContext", "cancellationDecision")
                .stream().map(node -> nodeStatus(projection, node))
                .allMatch(status -> List.of("CANCELLED", "SKIPPED", "NOT_RUN", "NOT_INVOKED")
                        .contains(status));
        if (!"TIMED_OUT".equals(projection.run().status())
                || !"TIMEOUT".equals(compensationStatus) || !downstreamCancelled) {
            return failure(projection, "timeout did not cancel downstream feature nodes",
                    "历史查询超时必须保留因果链并取消下游。",
                    projection.run().status() + "/" + compensationStatus);
        }
        return pass(projection, "oracle-compensation-history-timeout",
                "历史查询超时必须保留因果链并取消下游。", "TIMEOUT -> downstream cancelled");
    }

    private Evaluation forbiddenWrite(
            CapabilityStudioFeatureRehearsalProjection projection,
            List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints) {
        if (operatorFootprints.isEmpty()) {
            return failure(projection, "operator side-effect footprint was not supplied",
                    "禁写结论必须同时检查实际 Graph operator 与运行 Trace。",
                    "operatorFootprints");
        }
        boolean writeOperator = operatorFootprints.stream()
                .anyMatch(operator -> operator.sideEffectType() == SideEffectType.WRITE
                        || operator.sideEffectType() == SideEffectType.MIXED
                        || operator.operatorRef().toLowerCase().contains("write"));
        boolean writeTrace = projection.dataLens().nodes().stream()
                .anyMatch(node -> node.operatorRef().toLowerCase().contains("write"));
        if (writeOperator || writeTrace || projection.run().realExternalCallCount() != 0) {
            return failure(projection, "write capability or write trace was observed",
                    "只读演示不能包含 WRITE operator、WRITE trace 或真实调用。",
                    "writeOperator=" + writeOperator + ", writeTrace=" + writeTrace);
        }
        return pass(projection, "oracle-forbidden-write-effect",
                "只读演示不能包含 WRITE operator、WRITE trace 或真实调用。",
                "graph operators and execution trace contain no WRITE");
    }

    private Evaluation policyRevision(CapabilityStudioFeatureRehearsalProjection projection) {
        Map<?, ?> policy = nodeOutput(projection, "cityPolicyLookup");
        Map<?, ?> decision = nodeOutput(projection, "cancellationDecision");
        boolean controlled = "SZ-CANCEL-2026.08-R2".equals(text(policy.get("version")));
        boolean frozenOutcome = "AUTO_QUOTE".equals(text(decision.get("action")))
                && "CANCELLATION_CONTEXT_READY".equals(text(decision.get("reasonCode")))
                && "PLATFORM_POLICY_CONTEXT".equals(text(decision.get("responsibilityReason")));
        if (!controlled || !frozenOutcome) {
            return failure(projection, "policy revision changed the frozen business outcome",
                    "受控政策版本变化不得改变冻结责任和动作。", decisionSummary(decision));
        }
        return pass(projection, "oracle-policy-revision-regression",
                "受控政策版本变化不得改变冻结责任和动作。",
                "controlled revision observed; frozen responsibility/action unchanged");
    }

    private Evaluation duplicate(List<CapabilityStudioFeatureRehearsalProjection> observations) {
        if (observations.size() < 2) {
            return failure(observations.getFirst(), "duplicate evidence has fewer than two runs",
                    "重复输入必须产生不同 runId 且保持同一业务结论。", "one run");
        }
        Evaluation businessEvaluation = expectDecision(observations.getFirst(),
                "oracle-duplicate-cancellation",
                "重复取消输入应保持冻结的自动报价业务结论。", "AUTO_QUOTE",
                "CANCELLATION_CONTEXT_READY", "NONE", "重复取消业务结论正确");
        if (!PASS.equals(businessEvaluation.status())) {
            return businessEvaluation;
        }
        String semantic = observations.getFirst().run().semanticFingerprint();
        String business = decisionBusinessFingerprint(observations.getFirst());
        boolean stable = observations.stream().allMatch(value ->
                "PASSED".equals(value.run().status())
                        && value.run().realExternalCallCount() == 0
                        && semantic.equals(value.run().semanticFingerprint())
                        && business.equals(decisionBusinessFingerprint(value)));
        boolean distinctRuns = observations.stream().map(value -> value.run().runId()).distinct().count()
                == observations.size();
        if (!stable || !distinctRuns) {
            return failure(observations.getFirst(), "duplicate business outcome was not idempotent",
                    "重复输入必须产生不同 runId 且保持同一业务结论。",
                    "stable=" + stable + ", distinctRunIds=" + distinctRuns);
        }
        return pass(observations.getFirst(), "oracle-duplicate-cancellation",
                "重复输入必须产生不同 runId 且保持同一业务结论。",
                "same business outcome across distinct runIds");
    }

    private static String expectedTerminalStatus(String caseId) {
        return "case-compensation-history-timeout".equals(caseId) ? "TIMED_OUT" : "PASSED";
    }

    private Evaluation expectDecision(
            CapabilityStudioFeatureRehearsalProjection projection,
            String assertionId,
            String expectedSummary,
            String action,
            String reason,
            String informationGap,
            String actualSummary) {
        Map<?, ?> decision = nodeOutput(projection, "cancellationDecision");
        boolean runtimePass = "PASSED".equals(projection.run().status());
        boolean matches = runtimePass && action.equals(text(decision.get("action")))
                && reason.equals(text(decision.get("reasonCode")))
                && informationGap.equals(text(decision.get("informationGap")));
        return matches ? pass(projection, assertionId, expectedSummary, actualSummary)
                : failure(projection, actualSummary, expectedSummary, decisionSummary(decision));
    }

    private Evaluation pass(CapabilityStudioFeatureRehearsalProjection projection,
                            String assertionId, String expected, String actual) {
        return new Evaluation(assertionId, PASS, expected, actual,
                projection.dataLens().fingerprint());
    }

    private Evaluation failure(CapabilityStudioFeatureRehearsalProjection projection,
                               String actual, String expected, String detail) {
        String assertionId = "oracle-" + projection.scenario().id().substring("case-".length());
        return new Evaluation(assertionId, FAIL, expected,
                actual + " (" + detail + ")", projection.dataLens().fingerprint());
    }

    private static Map<?, ?> nodeOutput(
            CapabilityStudioFeatureRehearsalProjection projection, String nodeId) {
        return projection.dataLens().nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .map(CapabilityStudioDataLensProjection.Node::output)
                .map(value -> value instanceof HttpResourceOutput output ? output.payload() : value)
                .filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value)
                .orElse(Map.of());
    }

    private static String nodeStatus(
            CapabilityStudioFeatureRehearsalProjection projection, String nodeId) {
        return projection.dataLens().nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .map(CapabilityStudioDataLensProjection.Node::status)
                .findFirst().orElse("");
    }

    private String decisionBusinessFingerprint(
            CapabilityStudioFeatureRehearsalProjection projection) {
        return ProtocolFingerprint.of(objectMapper, decisionBusinessMaterial(projection));
    }

    private static Map<String, Object> decisionBusinessMaterial(
            CapabilityStudioFeatureRehearsalProjection projection) {
        Map<?, ?> decision = nodeOutput(projection, "cancellationDecision");
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("action", decision.get("action"));
        business.put("reasonCode", decision.get("reasonCode"));
        business.put("responsibilityReason", decision.get("responsibilityReason"));
        business.put("informationGap", decision.get("informationGap"));
        return business;
    }

    private static String decisionSummary(Map<?, ?> decision) {
        return "action=" + text(decision.get("action"))
                + ", reasonCode=" + text(decision.get("reasonCode"))
                + ", responsibilityReason=" + text(decision.get("responsibilityReason"))
                + ", informationGap=" + text(decision.get("informationGap"));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Evaluation(
            String assertionId,
            String status,
            String expectedSummary,
            String actualSummary,
            String actualFingerprint) {
        public Evaluation {
            assertionId = Objects.requireNonNull(assertionId, "assertionId");
            status = Objects.requireNonNull(status, "status");
            expectedSummary = Objects.requireNonNull(expectedSummary, "expectedSummary");
            actualSummary = Objects.requireNonNull(actualSummary, "actualSummary");
            actualFingerprint = Objects.requireNonNull(actualFingerprint, "actualFingerprint");
        }
    }
}
