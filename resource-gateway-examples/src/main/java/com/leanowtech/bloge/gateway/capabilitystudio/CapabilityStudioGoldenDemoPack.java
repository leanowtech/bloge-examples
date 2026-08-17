package com.leanowtech.bloge.gateway.capabilitystudio;

import java.util.List;
import java.util.Objects;

/**
 * Read-only metadata authority for the Capability Studio Stage 0 golden pack.
 *
 * <p>This model deliberately contains references and behavior summaries only. It is not a
 * simulator, does not hold fixture material, and cannot invoke an external resource.</p>
 */
public record CapabilityStudioGoldenDemoPack(
        int schemaVersion,
        String packId,
        int revision,
        String packFingerprint,
        String displayName,
        Owner owner,
        String readiness,
        CanonicalBaseline canonicalBaseline,
        List<Capability> apiCapabilities,
        List<Capability> featureCapabilities,
        List<Capability> toolCapabilities,
        List<ExactRef> supportingRefs,
        List<TestScenario> scenarios,
        TutorialBranch tutorialBranch) {

    public CapabilityStudioGoldenDemoPack {
        apiCapabilities = immutable(apiCapabilities, "apiCapabilities");
        featureCapabilities = immutable(featureCapabilities, "featureCapabilities");
        toolCapabilities = immutable(toolCapabilities, "toolCapabilities");
        supportingRefs = immutable(supportingRefs, "supportingRefs");
        scenarios = immutable(scenarios, "scenarios");
        Objects.requireNonNull(canonicalBaseline, "canonicalBaseline");
        Objects.requireNonNull(tutorialBranch, "tutorialBranch");
        Objects.requireNonNull(owner, "owner");
    }

    private static <T> List<T> immutable(List<T> value, String name) {
        return List.copyOf(Objects.requireNonNull(value, name));
    }

    /** A Stage 0 metadata coordinate; runtime material is intentionally absent. */
    public record ExactRef(String kind, String id, int revision, String fingerprint) {
    }

    /** Human owner identity without credentials or customer data. */
    public record Owner(String id, String name) {
    }

    /** A contract-backed API, feature, or tool capability. */
    public record Capability(
            String id,
            String name,
            String description,
            ExactRef ref,
            Owner owner,
            ExactRef contractRef,
            ContractSummary contract,
            String sideEffect,
            String sla,
            String readiness,
            List<ExactRef> dependencyRefs) {
        public Capability {
            dependencyRefs = List.copyOf(Objects.requireNonNull(dependencyRefs, "dependencyRefs"));
        }
    }

    /** A canonical test scenario with enough metadata to explain its obligation. */
    public record TestScenario(
            String id,
            String name,
            ExactRef ref,
            Owner owner,
            ExactRef contractRef,
            ExactRef sourceRef,
            ExactRef oracleRef,
            SourceSummary source,
            OracleSummary oracle,
            List<ExactRef> applicableContractRefs,
            String category,
            String expectedResult,
            String lifecycle,
            String qualityState,
            List<DependencyBehavior> dependencyBehaviors) {
        public TestScenario {
            applicableContractRefs = List.copyOf(
                    Objects.requireNonNull(applicableContractRefs, "applicableContractRefs"));
            dependencyBehaviors = List.copyOf(
                    Objects.requireNonNull(dependencyBehaviors, "dependencyBehaviors"));
        }
    }

    /** A dependency behavior summary; it is not an executable stub. */
    public record DependencyBehavior(ExactRef dependencyRef, String behavior, String summary) {
    }

    /** Contract metadata for form and governance projections; no example values are included. */
    public record ContractSummary(
            List<InputField> inputs,
            List<String> successOutputs,
            List<ErrorSummary> errors) {
        public ContractSummary {
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            successOutputs = List.copyOf(Objects.requireNonNull(successOutputs, "successOutputs"));
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        }
    }

    public record InputField(
            String name,
            String label,
            String type,
            boolean required,
            boolean sensitive,
            String description) {
    }

    public record ErrorSummary(
            String code,
            String meaning,
            boolean retryable,
            String suggestedAction) {
    }

    public record SourceSummary(String displayName, String type) {
    }

    public record OracleSummary(String displayName, String summary) {
    }

    /** Immutable reference set used as the content-addressed canonical baseline. */
    public record CanonicalBaseline(
            String id,
            ExactRef ref,
            boolean immutable,
            List<ExactRef> assetRefs,
            List<ExactRef> scenarioRefs) {
        public CanonicalBaseline {
            assetRefs = List.copyOf(Objects.requireNonNull(assetRefs, "assetRefs"));
            scenarioRefs = List.copyOf(Objects.requireNonNull(scenarioRefs, "scenarioRefs"));
        }
    }

    /** Isolated teaching branch that overrides one dependency behavior without changing baseline. */
    public record TutorialBranch(
            String id,
            ExactRef ref,
            ExactRef baseBaselineRef,
            List<BehaviorOverride> behaviorOverrides) {
        public TutorialBranch {
            behaviorOverrides = List.copyOf(
                    Objects.requireNonNull(behaviorOverrides, "behaviorOverrides"));
        }
    }

    public record BehaviorOverride(
            ExactRef scenarioRef,
            ExactRef dependencyRef,
            String behavior,
            String summary) {
    }
}
