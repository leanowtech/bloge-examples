package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

import java.util.List;

/** HTTP projection and isolated tutorial-branch authoring surface for Capability Studio. */
@RestController
@RequestMapping("/api/capability-studio")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.capability-studio.demo", name = "enabled", havingValue = "true")
public final class CapabilityStudioDemoController {
    private final CapabilityStudioGoldenDemoPack pack;
    private final CapabilityStudioTutorialBranchAuthority tutorialBranch;

    /** Creates the projection controller from injected validated pack and durable authority. */
    public CapabilityStudioDemoController(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioTutorialBranchAuthority tutorialBranch) {
        this.pack = pack;
        this.tutorialBranch = tutorialBranch;
    }

    /** Returns names, references, behavior summaries, and counts without fixture material. */
    @GetMapping("/demo-pack")
    public DemoPackProjection demoPack() {
        return DemoPackProjection.from(pack);
    }

    /** Returns the truthful, initially unexecuted GP acceptance baseline. */
    @GetMapping("/acceptance-baseline")
    public AcceptanceBaselineProjection acceptanceBaseline() {
        return AcceptanceBaselineProjection.from(pack);
    }

    /** Returns the current business-shaped dependency behavior for the fixed tutorial branch. */
    @GetMapping("/tutorial-branch")
    public TutorialBranchProjection tutorialBranch() {
        return tutorialBranchProjection(tutorialBranch.current());
    }

    /** Saves the controlled compensation-history behavior with optimistic concurrency. */
    @PutMapping("/tutorial-branch/behaviors/compensation-history")
    public TutorialBranchProjection updateTutorialBranchBehavior(
            @RequestBody CapabilityStudioTutorialBranchBehaviorUpdateRequest request) {
        return tutorialBranchProjection(tutorialBranch.save(request));
    }

    /** Runs a no-side-effect preflight bound to the current tutorial revision and fingerprint. */
    @PostMapping("/tutorial-branch/preflight")
    public TutorialBranchPreflightProjection preflightTutorialBranch() {
        tutorialBranch.preflight();
        CapabilityStudioTutorialBranchAuthority.State current = tutorialBranch.current();
        return new TutorialBranchPreflightProjection(
                "ISOLATED", 0, 0, false, CapabilityStudioTutorialBranchAuthority.BRANCH_ID,
                current.revision(), current.fingerprint());
    }

    private TutorialBranchProjection tutorialBranchProjection(
            CapabilityStudioTutorialBranchAuthority.State state) {
        CapabilityStudioTutorialBranchAuthority.Behavior value = state.behavior();
        return new TutorialBranchProjection(
                CapabilityStudioTutorialBranchAuthority.BRANCH_ID,
                state.revision(),
                state.fingerprint(),
                tutorialBranch.canonicalBaselineFingerprint(),
                new TutorialBranchBehaviorProjection(
                        CapabilityStudioTutorialBranchAuthority.DEPENDENCY_ID,
                        CapabilityStudioTutorialBranchAuthority.DEPENDENCY_NAME,
                        value.condition(), value.behavior(), value.durationMs()));
    }

    /** Maps semantic authoring failures to the frozen business error shape. */
    @ExceptionHandler(CapabilityStudioTutorialBranchException.class)
    public ResponseEntity<CapabilityStudioError> handleTutorialBranchFailure(
            CapabilityStudioTutorialBranchException failure) {
        return ResponseEntity.status(failure.status()).body(new CapabilityStudioError(
                failure.code(), failure.whatHappened(), failure.impact(),
                failure.recoveryAction(), failure.field()));
    }

    /** Maps malformed JSON and strict unknown-field failures to the frozen business error shape. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CapabilityStudioError> handleUnreadableTutorialBranchRequest(
            HttpMessageNotReadableException failure) {
        String message = failure.getMostSpecificCause() == null
                ? "请求体无法解析。"
                : failure.getMostSpecificCause().getMessage();
        String field = message != null && message.contains("Unknown field:")
                ? message.substring(message.indexOf("Unknown field:") + "Unknown field:".length()).trim()
                : null;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new CapabilityStudioError(
                "RG.CAPABILITY_STUDIO.REQUEST_INVALID",
                field == null ? "请求体格式无效。" : "请求包含未支持的字段。",
                "本次依赖表现没有保存。",
                "只提交 condition、behavior、durationMs 和 expectedRevision，并修正字段值后重试。",
                field));
    }

    /** Maps direct invocation and decoder failures that surface as IllegalArgumentException. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CapabilityStudioError> handleInvalidTutorialBranchRequest(
            IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(new CapabilityStudioError(
                "RG.CAPABILITY_STUDIO.REQUEST_INVALID",
                "请求字段无效。", "本次依赖表现没有保存。",
                "只提交业务字段并修正字段值后重试。", null));
    }

    public record TutorialBranchProjection(
            String branchId,
            long revision,
            String fingerprint,
            String canonicalBaselineFingerprint,
            TutorialBranchBehaviorProjection behavior) {
    }

    public record TutorialBranchBehaviorProjection(
            String dependencyId,
            String dependencyName,
            String condition,
            String behavior,
            long durationMs) {
    }

    public record TutorialBranchPreflightProjection(
            String mode,
            int unresolvedDependencies,
            int realExternalCallCount,
            boolean fallbackToReal,
            String branchId,
            long revision,
            String fingerprint) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CapabilityStudioError(
            String code,
            String whatHappened,
            String impact,
            String recoveryAction,
            String field) {
    }

    public record DemoPackProjection(
            String packId,
            int revision,
            String packFingerprint,
            String displayName,
            String summary,
            CapabilityStudioGoldenDemoPack.Owner owner,
            String readiness,
            String acceptanceStatus,
            BaselineSummary canonicalBaseline,
            TutorialBranchSummary tutorialBranch,
            Cardinality cardinality,
            List<CapabilitySummary> apiCapabilities,
            List<CapabilitySummary> featureCapabilities,
            List<CapabilitySummary> toolCapabilities,
            List<ScenarioSummary> scenarios) {
        static DemoPackProjection from(CapabilityStudioGoldenDemoPack pack) {
            return new DemoPackProjection(
                    pack.packId(),
                    pack.revision(),
                    pack.packFingerprint(),
                    pack.toolCapabilities().getFirst().name(),
                    pack.toolCapabilities().getFirst().description(),
                    pack.owner(),
                    pack.readiness(),
                    "NO_GO",
                    BaselineSummary.from(pack.canonicalBaseline()),
                    TutorialBranchSummary.from(pack.tutorialBranch()),
                    new Cardinality(pack.apiCapabilities().size(), pack.featureCapabilities().size(),
                            pack.toolCapabilities().size(), pack.scenarios().size()),
                    summaries(pack.apiCapabilities()),
                    summaries(pack.featureCapabilities()),
                    summaries(pack.toolCapabilities()),
                    pack.scenarios().stream().map(ScenarioSummary::from).toList());
        }

        private static List<CapabilitySummary> summaries(List<CapabilityStudioGoldenDemoPack.Capability> values) {
            return values.stream().map(CapabilitySummary::from).toList();
        }
    }

    public record Cardinality(int api, int feature, int tool, int scenarios) {
    }

    public record BaselineSummary(
            String id,
            String name,
            String purpose,
            String status,
            CapabilityStudioGoldenDemoPack.ExactRef ref,
            int assetCount,
            int scenarioCount) {
        static BaselineSummary from(CapabilityStudioGoldenDemoPack.CanonicalBaseline value) {
            return new BaselineSummary(
                    value.id(), "Canonical Baseline",
                    "Immutable exact-reference baseline for repeatable review",
                    value.immutable() ? "IMMUTABLE" : "MUTABLE",
                    value.ref(), value.assetRefs().size(), value.scenarioRefs().size());
        }
    }

    public record CapabilitySummary(
            String id,
            String name,
            String kind,
            String description,
            CapabilityStudioGoldenDemoPack.ExactRef ref,
            CapabilityStudioGoldenDemoPack.Owner owner,
            CapabilityStudioGoldenDemoPack.ExactRef contractRef,
            CapabilityStudioGoldenDemoPack.ContractSummary contract,
            String sideEffect,
            String sla,
            String readiness,
            List<CapabilityStudioGoldenDemoPack.ExactRef> dependencyRefs) {
        static CapabilitySummary from(CapabilityStudioGoldenDemoPack.Capability value) {
            return new CapabilitySummary(value.id(), value.name(), value.ref().kind(), value.description(),
                    value.ref(), value.owner(), value.contractRef(), value.contract(), value.sideEffect(),
                    value.sla(), value.readiness(), value.dependencyRefs());
        }
    }

    public record ScenarioSummary(
            String id,
            String name,
            CapabilityStudioGoldenDemoPack.ExactRef ref,
            CapabilityStudioGoldenDemoPack.Owner owner,
            CapabilityStudioGoldenDemoPack.ExactRef contractRef,
            CapabilityStudioGoldenDemoPack.ExactRef sourceRef,
            CapabilityStudioGoldenDemoPack.ExactRef oracleRef,
            CapabilityStudioGoldenDemoPack.SourceSummary source,
            CapabilityStudioGoldenDemoPack.OracleSummary oracle,
            List<CapabilityStudioGoldenDemoPack.ExactRef> applicableContractRefs,
            int applicableContractCount,
            String category,
            String expectedResult,
            String lifecycle,
            String qualityState,
            List<CapabilityStudioGoldenDemoPack.DependencyBehavior> dependencyBehaviors) {
            static ScenarioSummary from(CapabilityStudioGoldenDemoPack.TestScenario value) {
                return new ScenarioSummary(value.id(), value.name(), value.ref(), value.owner(), value.contractRef(),
                    value.sourceRef(), value.oracleRef(), value.source(), value.oracle(),
                    value.applicableContractRefs(), value.applicableContractRefs().size(), value.category(),
                    value.expectedResult(), value.lifecycle(), value.qualityState(), value.dependencyBehaviors());
            }
    }

    public record TutorialBranchSummary(
            String id,
            String name,
            String purpose,
            String status,
            CapabilityStudioGoldenDemoPack.ExactRef ref,
            CapabilityStudioGoldenDemoPack.ExactRef baseBaselineRef,
            List<CapabilityStudioGoldenDemoPack.BehaviorOverride> behaviorOverrides) {
        static TutorialBranchSummary from(CapabilityStudioGoldenDemoPack.TutorialBranch value) {
            return new TutorialBranchSummary(
                    value.id(), "Tutorial Branch",
                    "Isolated branch for the controlled compensation-history timeout",
                    "ISOLATED_NOT_RUN", value.ref(), value.baseBaselineRef(),
                    value.behaviorOverrides());
        }
    }

    public record AcceptanceBaselineProjection(
            String packId,
            String status,
            String readiness,
            Cardinality cardinality,
            String canonicalBaseline,
            String tutorialBranch,
            List<AcceptanceGate> gates,
            IsolationIntent isolationIntent) {
        static AcceptanceBaselineProjection from(CapabilityStudioGoldenDemoPack pack) {
            List<AcceptanceGate> gates = java.util.stream.IntStream.rangeClosed(1, 10)
                    .mapToObj(index -> new AcceptanceGate("GP-%02d".formatted(index), "NOT_RUN",
                            "Stage 0 baseline has no runtime evidence yet"))
                    .toList();
            return new AcceptanceBaselineProjection(
                    pack.packId(), "NO_GO", "BASELINE_READY_EVIDENCE_PENDING",
                    new Cardinality(pack.apiCapabilities().size(), pack.featureCapabilities().size(),
                            pack.toolCapabilities().size(), pack.scenarios().size()),
                    pack.canonicalBaseline().ref().fingerprint(),
                    pack.tutorialBranch().ref().fingerprint(),
                    gates,
                    new IsolationIntent(null, "NOT_RUN", "DECLARED_ISOLATION_INTENT_ONLY"));
        }
    }

    public record AcceptanceGate(String id, String status, String reason) {
    }

    public record IsolationIntent(Integer realExternalCallCount, String evidenceStatus, String basis) {
    }
}
