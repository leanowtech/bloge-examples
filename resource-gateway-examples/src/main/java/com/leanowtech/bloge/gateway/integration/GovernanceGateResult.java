package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ANEKE-authored governance decision bound to one immutable graph draft snapshot.
 */
public record GovernanceGateResult(
        String schemaVersion,
        String gateResultId,
        Target target,
        String status,
        List<Issue> issues,
        Instant producedAt,
        Instant expiresAt,
        String resultFingerprint,
        DecisionBasis decisionBasis
) {
    public static final String SCHEMA_VERSION_V1 = "toolStudio.resourceGateway.gateResult.v1";
    public static final String SCHEMA_VERSION_V2 = "toolStudio.resourceGateway.gateResult.v2";
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.gateResult.v3";

    public GovernanceGateResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        gateResultId = gateResultId == null ? "" : gateResultId.trim();
        target = target == null ? Target.empty() : target;
        status = status == null || status.isBlank() ? "UNKNOWN" : status.trim().toUpperCase();
        issues = issues == null ? List.of() : List.copyOf(issues);
        producedAt = producedAt == null ? Instant.now() : producedAt;
        decisionBasis = decisionBasis == null ? DecisionBasis.empty() : decisionBasis;
        resultFingerprint = resultFingerprint == null || resultFingerprint.isBlank()
                ? computeFingerprint(schemaVersion, gateResultId, target, status, issues, producedAt, expiresAt,
                        decisionBasis)
                : resultFingerprint;
    }

    public GovernanceGateResult(String schemaVersion,
                                String gateResultId,
                                Target target,
                                String status,
                                List<Issue> issues,
                                Instant producedAt,
                                Instant expiresAt,
                                String resultFingerprint) {
        this(schemaVersion, gateResultId, target, status, issues, producedAt, expiresAt, resultFingerprint, null);
    }

    public boolean fingerprintVerified() {
        return resultFingerprint.equals(computeFingerprint(schemaVersion,
                gateResultId, target, status, issues, producedAt, expiresAt, decisionBasis));
    }

    public GovernanceGateResult withIssues(List<Issue> normalizedIssues) {
        return new GovernanceGateResult(schemaVersion, gateResultId, target, status, normalizedIssues,
                producedAt, expiresAt, "", decisionBasis);
    }

    private static String computeFingerprint(String schemaVersion,
                                             String gateResultId,
                                             Target target,
                                             String status,
                                             List<Issue> issues,
                                             Instant producedAt,
                                             Instant expiresAt,
                                             DecisionBasis decisionBasis) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("gateResultId", gateResultId);
        material.put("target", SCHEMA_VERSION_V1.equals(schemaVersion)
                ? Map.of("kind", target.kind(), "draftId", target.draftId(), "revision", target.revision(),
                        "draftFingerprint", target.draftFingerprint())
                : target);
        material.put("status", status);
        material.put("issues", issues);
        material.put("producedAt", producedAt);
        material.put("expiresAt", expiresAt == null ? "" : expiresAt);
        if (SCHEMA_VERSION_V2.equals(schemaVersion)) {
            material.put("decisionBasis", decisionBasis == null ? DecisionBasis.empty() : decisionBasis);
        } else if (!SCHEMA_VERSION_V1.equals(schemaVersion)) {
            material.put("decisionBasis", decisionBasis == null ? DecisionBasis.empty() : decisionBasis);
        }
        return VisualBundleFingerprint.fromMaterial(material);
    }

    public record Target(String kind,
                         String draftId,
                         long revision,
                         String draftFingerprint,
                         String tenantId,
                         String namespace,
                         String environment) {
        public Target {
            kind = kind == null || kind.isBlank() ? "GRAPH_DRAFT" : kind.trim().toUpperCase();
            draftId = draftId == null ? "" : draftId.trim();
            revision = Math.max(0, revision);
            draftFingerprint = draftFingerprint == null ? "" : draftFingerprint.trim();
            tenantId = normalize(tenantId);
            namespace = normalize(namespace);
            environment = normalize(environment);
        }

        public Target(String kind, String draftId, long revision, String draftFingerprint) {
            this(kind, draftId, revision, draftFingerprint, "", "", "");
        }

        static Target empty() {
            return new Target("", "", 0, "", "", "", "");
        }
    }

    public record Issue(String issueId, String severity, String code, String message, String targetPath,
                        String recommendedAction, String deepLink) {
        public Issue {
            issueId = issueId == null ? "" : issueId;
            severity = severity == null || severity.isBlank() ? "BLOCKING" : severity.trim().toUpperCase();
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            targetPath = targetPath == null ? "" : targetPath;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
            deepLink = deepLink == null ? "" : deepLink;
        }

        Issue withDeepLink(String value) {
            return new Issue(issueId, severity, code, message, targetPath, recommendedAction, value);
        }
    }

    public record DecisionBasis(WorkbookRef workbook,
                                String dependencySnapshotFingerprint,
                                List<SuiteRef> contractSuites,
                                List<EvidenceRef> evidence,
                                PolicyRef policy,
                                List<Check> checks,
                                @JsonInclude(JsonInclude.Include.NON_EMPTY)
                                List<SemanticWorkbookRef> semanticWorkbooks) {
        public DecisionBasis {
            workbook = workbook == null ? WorkbookRef.empty() : workbook;
            dependencySnapshotFingerprint = normalize(dependencySnapshotFingerprint);
            contractSuites = contractSuites == null ? List.of() : List.copyOf(contractSuites);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            policy = policy == null ? PolicyRef.empty() : policy;
            checks = checks == null ? List.of() : List.copyOf(checks);
            semanticWorkbooks = semanticWorkbooks == null ? List.of() : List.copyOf(semanticWorkbooks);
        }

        /** Backward-compatible constructor retaining the exact gate-result v2 JSON shape. */
        public DecisionBasis(WorkbookRef workbook,
                             String dependencySnapshotFingerprint,
                             List<SuiteRef> contractSuites,
                             List<EvidenceRef> evidence,
                             PolicyRef policy,
                             List<Check> checks) {
            this(workbook, dependencySnapshotFingerprint, contractSuites, evidence, policy, checks, null);
        }

        static DecisionBasis empty() {
            return new DecisionBasis(null, "", null, null, null, null, null);
        }

        public boolean emptyBasis() {
            return workbook.workbookId().isBlank() && dependencySnapshotFingerprint.isBlank()
                    && contractSuites.isEmpty() && evidence.isEmpty() && policy.policyId().isBlank()
                    && checks.isEmpty() && semanticWorkbooks.isEmpty();
        }

        public List<String> failedRequiredChecks() {
            Map<String, String> statuses = new LinkedHashMap<>();
            checks.forEach(check -> statuses.put(check.kind(), check.status()));
            return policy.requiredChecks().stream()
                    .filter(required -> !"PASSED".equals(statuses.get(required))).toList();
        }
    }

    public record WorkbookRef(String workbookId,
                              long revision,
                              String workbookFingerprint,
                              String sourceBundleFingerprint) {
        public WorkbookRef {
            workbookId = normalize(workbookId);
            revision = Math.max(0, revision);
            workbookFingerprint = normalize(workbookFingerprint);
            sourceBundleFingerprint = normalize(sourceBundleFingerprint);
        }

        static WorkbookRef empty() {
            return new WorkbookRef("", 0, "", "");
        }

        public boolean complete() {
            return !workbookId.isBlank() && revision > 0 && !workbookFingerprint.isBlank()
                    && !sourceBundleFingerprint.isBlank();
        }
    }

    public record SuiteRef(String suiteId, long revision, String fingerprint) {
        public SuiteRef {
            suiteId = normalize(suiteId);
            revision = Math.max(0, revision);
            fingerprint = normalize(fingerprint);
        }

        public String key() {
            return suiteId + "@" + revision + "#" + fingerprint;
        }
    }

    public record EvidenceRef(String runId, String evidenceFingerprint) {
        public EvidenceRef {
            runId = normalize(runId);
            evidenceFingerprint = normalize(evidenceFingerprint);
        }
    }

    /**
     * Reconstructable reference to one exact semantic workbook seed consumed by ANEKE.
     *
     * <p>The evidence list is the complete ordered, verified projection from the source bundle,
     * not a caller-selected subset. Candidate and unavailable counts preserve the bounded
     * projection manifest so Resource Gateway can reproduce the original bundle fingerprint after
     * newer suite runs arrive.</p>
     */
    public record SemanticWorkbookRef(
            SuiteRef suite,
            TestSuite.Target target,
            String bundleFingerprint,
            String projectionStatus,
            int candidateEvidenceCount,
            int unavailableEvidenceCount,
            boolean evidenceTruncated,
            List<SemanticEvidenceRef> evidence
    ) {
        public SemanticWorkbookRef {
            suite = suite == null ? new SuiteRef("", 0, "") : suite;
            bundleFingerprint = normalize(bundleFingerprint);
            projectionStatus = normalize(projectionStatus).toUpperCase();
            candidateEvidenceCount = Math.max(0, candidateEvidenceCount);
            unavailableEvidenceCount = Math.max(0, unavailableEvidenceCount);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        /** @return stable exact-suite identity used for duplicate detection */
        public String key() {
            return suite.key();
        }
    }

    /** Exact terminal semantic aggregate identity needed to reconstruct a workbook seed. */
    public record SemanticEvidenceRef(String suiteRunId, String evidenceFingerprint) {
        public SemanticEvidenceRef {
            suiteRunId = normalize(suiteRunId);
            evidenceFingerprint = normalize(evidenceFingerprint);
        }
    }

    public record PolicyRef(String policyId, String version, List<String> requiredChecks) {
        public PolicyRef {
            policyId = normalize(policyId);
            version = normalize(version);
            requiredChecks = requiredChecks == null ? List.of() : requiredChecks.stream()
                    .map(GovernanceGateResult::normalize).filter(value -> !value.isBlank())
                    .map(String::toUpperCase).distinct().sorted().toList();
        }

        static PolicyRef empty() {
            return new PolicyRef("", "", null);
        }

        public boolean complete() {
            return !policyId.isBlank() && !version.isBlank() && !requiredChecks.isEmpty();
        }
    }

    public record Check(String kind, String status, String reason, List<String> refs) {
        public Check {
            kind = normalize(kind).toUpperCase();
            status = normalize(status).toUpperCase();
            reason = normalize(reason);
            refs = refs == null ? List.of() : refs.stream().map(GovernanceGateResult::normalize)
                    .filter(value -> !value.isBlank()).distinct().toList();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
