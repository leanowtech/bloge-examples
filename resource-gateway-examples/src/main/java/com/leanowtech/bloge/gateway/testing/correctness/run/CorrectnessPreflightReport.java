package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Canonical payload-free safety projection for one exact correctness run selection. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessPreflightReport(
        String schemaVersion,
        CorrectnessRunRequest.PublicationRef publicationRef,
        ExactTargetRef target,
        ExactAssetRef compiledTestSuiteRef,
        CorrectnessRunRequest.Selection selection,
        ProofLevel proofLevel,
        List<CasePlan> cases,
        RiskSummary riskSummary,
        List<Blocker> blockers,
        String preflightFingerprint
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessPreflightReport.v1";

    public enum ProofLevel {
        STRUCTURAL,
        SIMULATED_BUSINESS,
        CONTROLLED_INTEGRATION,
        REPLAY_DERIVED
    }

    public CorrectnessPreflightReport {
        schemaVersion = version(schemaVersion);
        if (publicationRef == null || target == null || compiledTestSuiteRef == null
                || selection == null || proofLevel == null || riskSummary == null) {
            throw new IllegalArgumentException("Complete correctness preflight identity is required");
        }
        cases = cases == null ? List.of() : cases.stream()
                .sorted(Comparator.comparing(CasePlan::caseId)).toList();
        blockers = blockers == null ? List.of() : blockers.stream()
                .distinct().sorted(Comparator.comparing(Blocker::code)).toList();
        preflightFingerprint = exactFingerprint(
                preflightFingerprint, "preflightFingerprint");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CasePlan(
            String caseId,
            TestSuite.CaseType caseType,
            ExactAssetRef fixtureBundleRef,
            String executionPlanFingerprint,
            List<InvocationResolution> invocationSites,
            List<RulePolicy> rulePolicies,
            List<ServiceBinding> executionServices,
            int replayDependencyCount
    ) {
        public CasePlan {
            caseId = required(caseId, "caseId");
            if (caseType == null || fixtureBundleRef == null) {
                throw new IllegalArgumentException("Case type and Fixture ref are required");
            }
            executionPlanFingerprint = exactFingerprint(
                    executionPlanFingerprint, "executionPlanFingerprint");
            invocationSites = invocationSites == null ? List.of() : invocationSites.stream()
                    .sorted(Comparator.comparing(InvocationResolution::invocationSiteId)).toList();
            rulePolicies = rulePolicies == null ? List.of() : rulePolicies.stream()
                    .sorted(Comparator.comparing(RulePolicy::ruleId)).toList();
            executionServices = executionServices == null ? List.of() : executionServices.stream()
                    .sorted(Comparator.comparing(ServiceBinding::service)).toList();
            if (replayDependencyCount < 0) {
                throw new IllegalArgumentException("replayDependencyCount must not be negative");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvocationResolution(
            String invocationSiteId,
            String graphPath,
            String nodeId,
            String operatorRef,
            String resourceRef,
            String functionRef,
            String runtimeBindingFingerprint,
            InvocationSite.InvocationKind invocationKind,
            String sideEffectType,
            EffectiveExecutionPlan.Resolution resolution,
            FixtureRule.BehaviorKind behavior,
            FixtureRule.DoubleBoundary boundary,
            List<String> ruleRefs,
            String fidelity
    ) {
        public InvocationResolution {
            invocationSiteId = required(invocationSiteId, "invocationSiteId");
            graphPath = required(graphPath, "graphPath");
            nodeId = required(nodeId, "nodeId");
            operatorRef = trimmed(operatorRef);
            resourceRef = trimmed(resourceRef);
            functionRef = trimmed(functionRef);
            runtimeBindingFingerprint = exactFingerprint(
                    runtimeBindingFingerprint, "runtimeBindingFingerprint");
            sideEffectType = required(sideEffectType, "sideEffectType")
                    .toUpperCase(Locale.ROOT);
            if (invocationKind == null || resolution == null || behavior == null || boundary == null) {
                throw new IllegalArgumentException("Complete invocation resolution is required");
            }
            ruleRefs = ruleRefs == null ? List.of() : ruleRefs.stream()
                    .map(value -> required(value, "ruleRef")).distinct().sorted().toList();
            fidelity = required(fidelity, "fidelity");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RulePolicy(
            String ruleId,
            FixtureRule.BehaviorKind behavior,
            FixtureRule.DoubleBoundary boundary,
            boolean required,
            int minUses,
            int maxUses,
            FixtureRule.UnmatchedAction onUnmatched,
            FixtureRule.ExhaustedAction onExhausted,
            FixtureRule.SchemaCheckMode schemaCheckMode
    ) {
        public RulePolicy {
            ruleId = CorrectnessPreflightReport.required(ruleId, "ruleId");
            if (behavior == null || boundary == null || onUnmatched == null
                    || onExhausted == null || schemaCheckMode == null
                    || minUses < 0 || maxUses < 0) {
                throw new IllegalArgumentException("Complete bounded rule policy is required");
            }
        }

        public boolean mayFallbackToReal() {
            return onUnmatched == FixtureRule.UnmatchedAction.ALLOW_REAL
                    || onExhausted == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceBinding(
            String service,
            String mode,
            boolean available,
            boolean deterministic,
            String configurationFingerprint,
            List<String> consumers,
            List<String> certificationGaps
    ) {
        public ServiceBinding {
            service = required(service, "service");
            mode = required(mode, "mode");
            configurationFingerprint = exactFingerprint(
                    configurationFingerprint, "configurationFingerprint");
            consumers = strings(consumers);
            certificationGaps = strings(certificationGaps);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskSummary(
            int realCount,
            int mockedCount,
            int faultCount,
            int replayCount,
            int observeCount,
            int deniedCount,
            int fallbackToRealCount,
            int transportBoundaryCount,
            int secretRequirementCount,
            boolean logicalClockConfigured,
            List<String> sideEffectTypes
    ) {
        public RiskSummary {
            if (realCount < 0 || mockedCount < 0 || faultCount < 0 || replayCount < 0
                    || observeCount < 0 || deniedCount < 0 || fallbackToRealCount < 0
                    || transportBoundaryCount < 0 || secretRequirementCount < 0) {
                throw new IllegalArgumentException("Preflight risk counters must not be negative");
            }
            sideEffectTypes = strings(sideEffectTypes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Blocker(String code, String messageId, String caseId) {
        public Blocker {
            code = required(code, "code");
            messageId = required(messageId, "messageId");
            caseId = trimmed(caseId);
        }
    }

    private static String version(String value) {
        String normalized = trimmed(value);
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported correctness preflight report schemaVersion");
        }
        return normalized;
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> required(value, "value")).distinct().sorted().toList();
    }

    private static String required(String value, String field) {
        String normalized = trimmed(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String exactFingerprint(String value, String field) {
        String normalized = required(value, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
