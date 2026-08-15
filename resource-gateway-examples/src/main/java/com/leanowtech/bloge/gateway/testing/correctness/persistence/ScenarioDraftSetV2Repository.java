package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Scope-exact CAS store and bounded Matrix projection for governed Scenario v2. */
public interface ScenarioDraftSetV2Repository {

    Optional<StoredScenarioDraftSetV2> findHead(
            EnterpriseScope scope, String scenarioDraftSetId);

    Optional<StoredScenarioDraftSetV2> findRevision(
            EnterpriseScope scope, String scenarioDraftSetId, long revision);

    List<StoredScenarioDraftSetV2> revisions(
            EnterpriseScope scope, String scenarioDraftSetId);

    Optional<StoredScenarioDraftSetV2> saveIfRevision(
            long expectedRevision,
            ScenarioDraftSetV2 candidate,
            PrincipalRef actor);

    ScenarioCasePage pageByTarget(
            EnterpriseScope scope,
            ExactTargetRef target,
            String cursor,
            int limit);

    Set<String> fulfilledObligationIds(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef inventoryRef);

    record ScenarioCasePage(long total, List<ScenarioCaseSummary> rows, String nextCursor) {
        public ScenarioCasePage {
            rows = rows == null ? List.of() : List.copyOf(rows);
            nextCursor = nextCursor == null ? "" : nextCursor.trim();
            if (total < rows.size()) {
                throw new IllegalArgumentException("Scenario Case total is smaller than page rows");
            }
        }
    }

    record ScenarioCaseSummary(
            ExactAssetRef scenarioDraftSetRef,
            String caseId,
            String caseFingerprint,
            String name,
            String businessIntent,
            String caseType,
            RiskLevel risk,
            PrincipalRef owner,
            String lifecycle,
            int obligationCount,
            int oracleCount,
            int assertionSetCount,
            int dependencyCount,
            String reviewStatus,
            List<String> tags
    ) {
        public ScenarioCaseSummary {
            if (scenarioDraftSetRef == null || risk == null || owner == null) {
                throw new IllegalArgumentException("Scenario Case summary coordinate is required");
            }
            caseId = required(caseId, "caseId");
            caseFingerprint = fingerprint(caseFingerprint);
            name = required(name, "name");
            businessIntent = required(businessIntent, "businessIntent");
            caseType = required(caseType, "caseType");
            lifecycle = required(lifecycle, "lifecycle");
            reviewStatus = required(reviewStatus, "reviewStatus");
            tags = tags == null ? List.of() : List.copyOf(tags);
            if (obligationCount < 0 || oracleCount < 0 || assertionSetCount < 0
                    || dependencyCount < 0) {
                throw new IllegalArgumentException("Scenario Case summary counts are invalid");
            }
        }

        private static String fingerprint(String value) {
            String normalized = required(value, "caseFingerprint");
            if (!normalized.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Exact caseFingerprint is required");
            }
            return normalized;
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }
}
