package com.leanowtech.bloge.gateway.capabilitystudio;

import java.util.List;

/**
 * Payload-free, fail-closed read model for one canonical Capability Studio child run.
 *
 * <p>All references in this projection are either copied from independently verified canonical
 * authorities or explicitly derived from the current effective binding plan. No request, response,
 * fixture, or edge value is part of this protocol.</p>
 */
public record CapabilityStudioGovernedRunEvidenceProjection(
        String schemaVersion,
        String verificationStatus,
        String baselineId,
        String projectionFingerprint,
        Scenario scenario,
        ExactRef graphRef,
        ExactRef capabilityRef,
        ExactRef contractRef,
        ExactRef datasetRef,
        ExactRef caseRef,
        RuntimeTargetRef runtimeTarget,
        BindingPlan bindingPlan,
        Run run,
        String focusNodeId,
        CapabilityStudioDataLensProjection dataLens) {

    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.governed-run-evidence.v1";
    public static final String EXACT_VERIFIED = "EXACT_VERIFIED";

    public record ExactRef(String kind, String id, long revision, String fingerprint) {
    }

    /** Stable BindingPlan material; the enclosing ref fingerprint is intentionally excluded. */
    public record BindingPlanFingerprintMaterial(
            String refKind,
            String refId,
            long refRevision,
            ExactRef fixtureBundleRef,
            String effectiveExecutionPlanFingerprint,
            List<ExactRef> behaviorRefs,
            List<ExactRef> dependencyRefs,
            boolean fallbackToReal,
            String sourceMapFingerprint,
            String provenanceFingerprint) {
        public BindingPlanFingerprintMaterial {
            behaviorRefs = behaviorRefs == null ? List.of() : List.copyOf(behaviorRefs);
            dependencyRefs = dependencyRefs == null ? List.of() : List.copyOf(dependencyRefs);
        }
    }

    /** Complete stable projection material; only projectionFingerprint is excluded. */
    public record FingerprintMaterial(
            String schemaVersion,
            String verificationStatus,
            String baselineId,
            Scenario scenario,
            ExactRef graphRef,
            ExactRef capabilityRef,
            ExactRef contractRef,
            ExactRef datasetRef,
            ExactRef caseRef,
            RuntimeTargetRef runtimeTarget,
            BindingPlan bindingPlan,
            Run run,
            String focusNodeId,
            CapabilityStudioDataLensProjection dataLens) {
    }

    public record RuntimeTargetRef(String kind, String id, String fingerprint) {
    }

    public record Owner(String id, String name) {
    }

    public record Scenario(
            String caseId,
            String name,
            String businessIntent,
            String category,
            String lifecycle,
            String qualityState,
            Owner owner,
            ExactRef scenarioRef,
            ExactRef caseRef,
            ExactRef sourceRef,
            ExactRef oracleRef,
            List<ExactRef> applicableContractRefs) {
        public Scenario {
            applicableContractRefs = applicableContractRefs == null
                    ? List.of() : List.copyOf(applicableContractRefs);
        }
    }

    public record BindingPlan(
            ExactRef ref,
            ExactRef fixtureBundleRef,
            String effectiveExecutionPlanFingerprint,
            List<ExactRef> behaviorRefs,
            List<ExactRef> dependencyRefs,
            boolean fallbackToReal,
            String sourceMapFingerprint,
            String provenanceFingerprint) {
        public BindingPlan {
            behaviorRefs = behaviorRefs == null ? List.of() : List.copyOf(behaviorRefs);
            dependencyRefs = dependencyRefs == null ? List.of() : List.copyOf(dependencyRefs);
        }

        public BindingPlanFingerprintMaterial fingerprintMaterial() {
            return new BindingPlanFingerprintMaterial(
                    ref == null ? "" : ref.kind(),
                    ref == null ? "" : ref.id(),
                    ref == null ? 0 : ref.revision(),
                    fixtureBundleRef,
                    effectiveExecutionPlanFingerprint,
                    behaviorRefs,
                    dependencyRefs,
                    fallbackToReal,
                    sourceMapFingerprint,
                    provenanceFingerprint);
        }
    }

    public record Run(
            String runId,
            String status,
            String evidenceClass,
            String evidenceFingerprint,
            String semanticResultFingerprint,
            int assertionsEvaluated,
            int assertionsPassed,
            int fixtureControlsEvaluated,
            int fixtureControlsSatisfied) {
    }

    public FingerprintMaterial fingerprintMaterial() {
        return new FingerprintMaterial(
                schemaVersion, verificationStatus, baselineId, scenario, graphRef, capabilityRef,
                contractRef, datasetRef, caseRef, runtimeTarget, bindingPlan, run, focusNodeId,
                dataLens);
    }
}
