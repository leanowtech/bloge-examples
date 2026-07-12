package com.leanowtech.bloge.gateway.integration;

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
        String resultFingerprint
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.gateResult.v1";

    public GovernanceGateResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        gateResultId = gateResultId == null ? "" : gateResultId.trim();
        target = target == null ? Target.empty() : target;
        status = status == null || status.isBlank() ? "UNKNOWN" : status.trim().toUpperCase();
        issues = issues == null ? List.of() : List.copyOf(issues);
        producedAt = producedAt == null ? Instant.now() : producedAt;
        resultFingerprint = resultFingerprint == null || resultFingerprint.isBlank()
                ? computeFingerprint(gateResultId, target, status, issues, producedAt, expiresAt)
                : resultFingerprint;
    }

    public boolean fingerprintVerified() {
        return resultFingerprint.equals(computeFingerprint(
                gateResultId, target, status, issues, producedAt, expiresAt));
    }

    public GovernanceGateResult withIssues(List<Issue> normalizedIssues) {
        return new GovernanceGateResult(schemaVersion, gateResultId, target, status, normalizedIssues,
                producedAt, expiresAt, "");
    }

    private static String computeFingerprint(String gateResultId,
                                             Target target,
                                             String status,
                                             List<Issue> issues,
                                             Instant producedAt,
                                             Instant expiresAt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("gateResultId", gateResultId);
        material.put("target", target);
        material.put("status", status);
        material.put("issues", issues);
        material.put("producedAt", producedAt);
        material.put("expiresAt", expiresAt == null ? "" : expiresAt);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    public record Target(String kind, String draftId, long revision, String draftFingerprint) {
        public Target {
            kind = kind == null || kind.isBlank() ? "GRAPH_DRAFT" : kind.trim().toUpperCase();
            draftId = draftId == null ? "" : draftId.trim();
            revision = Math.max(0, revision);
            draftFingerprint = draftFingerprint == null ? "" : draftFingerprint.trim();
        }

        static Target empty() {
            return new Target("", "", 0, "");
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
}
