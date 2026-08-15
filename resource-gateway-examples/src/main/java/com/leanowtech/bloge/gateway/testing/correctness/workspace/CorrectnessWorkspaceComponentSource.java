package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CasePage;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CommandPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CoverageSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureCatalogSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.OracleAssertionSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.PublicationSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.RunSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.StaleReason;

import java.util.List;

/** Narrow metadata-only port used to compose assets owned by later correctness epics. */
@FunctionalInterface
public interface CorrectnessWorkspaceComponentSource {

    Components load(Coordinate coordinate, PageRequest pageRequest);

    record Coordinate(
            EnterpriseScope scope,
            ExactAssetRef definitionRef,
            ExactTargetRef target,
            ExactAssetRef activeInventoryRef
    ) {
        public Coordinate {
            if (scope == null || definitionRef == null || target == null) {
                throw new IllegalArgumentException("Workspace component coordinate is incomplete");
            }
        }
    }

    record PageRequest(String cursor, int limit, String queryFingerprint) {
        public PageRequest {
            cursor = cursor == null ? "" : cursor.trim();
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
            if (queryFingerprint == null
                    || !queryFingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Exact page query fingerprint is required");
            }
        }
    }

    record Components(
            CoverageSummary coverage,
            OracleAssertionSummary oracleAssertions,
            CasePage cases,
            FixtureCatalogSummary fixtures,
            ReviewSummary reviews,
            PublicationSummary lastPublication,
            RunSummary lastRun,
            CorrectnessVerdict verdict,
            List<StaleReason> staleReasons,
            List<String> capabilities,
            CommandPolicy commandPolicy
    ) {
        public Components {
            if (coverage == null || oracleAssertions == null || cases == null
                    || fixtures == null || reviews == null
                    || verdict == null || commandPolicy == null) {
                throw new IllegalArgumentException("Workspace components are incomplete");
            }
            staleReasons = staleReasons == null ? List.of() : List.copyOf(staleReasons);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }
    }
}
