package com.leanowtech.bloge.gateway.capabilitystudio;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

import java.util.List;

/** Payload-free read-only HTTP projection for the Stage 0 Capability Studio demo. */
@RestController
@RequestMapping("/api/capability-studio")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.capability-studio.demo", name = "enabled", havingValue = "true")
public final class CapabilityStudioDemoController {
    private final CapabilityStudioGoldenDemoPack pack;

    /** Creates the projection controller from one already validated pack. */
    public CapabilityStudioDemoController(CapabilityStudioGoldenDemoPack pack) {
        this.pack = pack;
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
