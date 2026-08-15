package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;

import java.util.List;

/** Payload-free exact-reference closure report for one governed Scenario Case transition. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioClosureReport(
        String schemaVersion,
        String scenarioId,
        ClosurePhase phase,
        boolean complete,
        List<ClosureCheck> checks
) {
    public static final String SCHEMA_VERSION = "bloge.scenarioClosureReport.v1";

    public enum ClosurePhase { REVIEW_READY, CANONICAL }
    public enum CheckStatus { VERIFIED, MISSING, STALE, WRONG_STATE, INCOMPATIBLE }

    public ScenarioClosureReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Scenario closure schemaVersion");
        }
        scenarioId = required(scenarioId, "scenarioId");
        if (phase == null) throw new IllegalArgumentException("Closure phase is required");
        checks = checks == null ? List.of() : List.copyOf(checks);
        boolean allVerified = !checks.isEmpty()
                && checks.stream().allMatch(check -> check.status() == CheckStatus.VERIFIED);
        if (complete != allVerified) {
            throw new IllegalArgumentException("Scenario closure result contradicts its checks");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClosureCheck(
            String checkId,
            String assetKind,
            ExactAssetRef assetRef,
            String childId,
            CheckStatus status,
            String reasonCode
    ) {
        public ClosureCheck {
            checkId = required(checkId, "checkId");
            assetKind = required(assetKind, "assetKind");
            childId = childId == null ? "" : childId.trim();
            if (status == null) throw new IllegalArgumentException("Check status is required");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (status != CheckStatus.VERIFIED && reasonCode.isEmpty()) {
                throw new IllegalArgumentException("Failed closure check requires reasonCode");
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
