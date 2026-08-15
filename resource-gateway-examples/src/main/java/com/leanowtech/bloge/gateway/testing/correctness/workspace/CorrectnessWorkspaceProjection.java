package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Payload-free aggregate read model for one exact correctness-authoring target. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessWorkspaceProjection(
        String schemaVersion,
        String queryFingerprint,
        ExactTargetRef target,
        DefinitionSummary definition,
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
        CommandPolicy commandPolicy,
        DeepLinks deepLinks
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessWorkspaceProjection.v1";

    public CorrectnessWorkspaceProjection {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        queryFingerprint = exactFingerprint(queryFingerprint, "queryFingerprint");
        if (target == null || definition == null || coverage == null || oracleAssertions == null
                || cases == null
                || fixtures == null || reviews == null || verdict == null
                || commandPolicy == null || deepLinks == null) {
            throw new IllegalArgumentException("Correctness Workspace projection is incomplete");
        }
        staleReasons = staleReasons == null ? List.of() : List.copyOf(staleReasons);
        capabilities = normalizedList(capabilities);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OracleAssertionSummary(
            Availability availability,
            int oracleTotal,
            int proposedOracles,
            int approvedOracles,
            int supersededOracles,
            int assertionSetTotal,
            int draftAssertionSets,
            int validAssertionSets,
            int staleAssertionSets,
            int unsupportedAssertionSets
    ) {
        public OracleAssertionSummary {
            availability = availability == null ? Availability.UNAVAILABLE : availability;
            if (oracleTotal < 0 || proposedOracles < 0 || approvedOracles < 0
                    || supersededOracles < 0
                    || proposedOracles + approvedOracles + supersededOracles != oracleTotal
                    || assertionSetTotal < 0 || draftAssertionSets < 0 || validAssertionSets < 0
                    || staleAssertionSets < 0 || unsupportedAssertionSets < 0
                    || draftAssertionSets + validAssertionSets + staleAssertionSets
                            != assertionSetTotal
                    || unsupportedAssertionSets > assertionSetTotal) {
                throw new IllegalArgumentException("Oracle and Assertion summary counts are invalid");
            }
        }

        public static OracleAssertionSummary unavailable() {
            return new OracleAssertionSummary(
                    Availability.UNAVAILABLE, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DefinitionSummary(
            ExactAssetRef definitionRef,
            String title,
            String businessIntent,
            List<String> successCriteria,
            RiskLevel riskLevel,
            PrincipalRef owner,
            String lifecycle
    ) {
        public DefinitionSummary {
            if (definitionRef == null || riskLevel == null || owner == null) {
                throw new IllegalArgumentException("Definition summary coordinate is required");
            }
            title = required(title, "title");
            businessIntent = required(businessIntent, "businessIntent");
            successCriteria = normalizedList(successCriteria);
            lifecycle = required(lifecycle, "lifecycle");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageSummary(
            Availability availability,
            ExactAssetRef inventoryRef,
            String lifecycle,
            int total,
            int fulfilled,
            int waived,
            int uncovered
    ) {
        public CoverageSummary {
            availability = availability == null ? Availability.UNAVAILABLE : availability;
            lifecycle = normalized(lifecycle);
            if (total < 0 || fulfilled < 0 || waived < 0 || uncovered < 0
                    || fulfilled + waived + uncovered > total) {
                throw new IllegalArgumentException("Coverage summary counts are invalid");
            }
            if (availability == Availability.AVAILABLE && inventoryRef == null) {
                throw new IllegalArgumentException("Available coverage requires an exact inventory ref");
            }
        }

        public static CoverageSummary unavailable() {
            return new CoverageSummary(Availability.UNAVAILABLE, null, "", 0, 0, 0, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CasePage(
            Availability availability,
            ExactAssetRef scenarioDraftSetRef,
            long total,
            List<CaseSummary> rows,
            String nextCursor,
            String queryFingerprint
    ) {
        public CasePage {
            availability = availability == null ? Availability.UNAVAILABLE : availability;
            rows = rows == null ? List.of() : List.copyOf(rows);
            nextCursor = normalized(nextCursor);
            queryFingerprint = exactFingerprint(queryFingerprint, "case queryFingerprint");
            if (total < rows.size()) {
                throw new IllegalArgumentException("Case total cannot be smaller than the returned page");
            }
            if (availability == Availability.AVAILABLE && scenarioDraftSetRef == null) {
                throw new IllegalArgumentException("Available cases require an exact Scenario ref");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseSummary(
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
        public CaseSummary {
            caseId = required(caseId, "caseId");
            caseFingerprint = exactFingerprint(caseFingerprint, "caseFingerprint");
            name = required(name, "name");
            businessIntent = required(businessIntent, "businessIntent");
            caseType = required(caseType, "caseType");
            if (risk == null || owner == null) {
                throw new IllegalArgumentException("Case risk and owner are required");
            }
            lifecycle = required(lifecycle, "lifecycle");
            reviewStatus = required(reviewStatus, "reviewStatus");
            if (obligationCount < 0 || oracleCount < 0 || assertionSetCount < 0
                    || dependencyCount < 0) {
                throw new IllegalArgumentException("Case summary counts must not be negative");
            }
            tags = normalizedList(tags);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FixtureCatalogSummary(
            Availability availability,
            int total,
            int active,
            int stale,
            List<FixtureSummary> rows
    ) {
        public FixtureCatalogSummary {
            availability = availability == null ? Availability.UNAVAILABLE : availability;
            rows = rows == null ? List.of() : List.copyOf(rows);
            if (total < 0 || active < 0 || stale < 0 || active + stale > total
                    || rows.size() > total) {
                throw new IllegalArgumentException("Fixture summary counts are invalid");
            }
        }

        public static FixtureCatalogSummary unavailable() {
            return new FixtureCatalogSummary(Availability.UNAVAILABLE, 0, 0, 0, List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FixtureSummary(
            ExactAssetRef descriptorRef,
            String name,
            String variantKey,
            String lifecycle,
            String classification,
            ExactSchemaRef schemaRef,
            String materialFingerprint,
            int usageCount
    ) {
        public FixtureSummary {
            if (descriptorRef == null || schemaRef == null) {
                throw new IllegalArgumentException("Fixture descriptor and schema refs are required");
            }
            name = required(name, "name");
            variantKey = required(variantKey, "variantKey");
            lifecycle = required(lifecycle, "lifecycle");
            classification = required(classification, "classification");
            materialFingerprint = exactFingerprint(materialFingerprint, "materialFingerprint");
            if (usageCount < 0) throw new IllegalArgumentException("usageCount must not be negative");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewSummary(int pending, int approved, int rejected, int stale) {
        public ReviewSummary {
            if (pending < 0 || approved < 0 || rejected < 0 || stale < 0) {
                throw new IllegalArgumentException("Review summary counts must not be negative");
            }
        }

        public static ReviewSummary empty() {
            return new ReviewSummary(0, 0, 0, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicationSummary(ExactAssetRef publicationRef, String lifecycle, Instant publishedAt) {
        public PublicationSummary {
            if (publicationRef == null || publishedAt == null) {
                throw new IllegalArgumentException("Publication summary coordinate is required");
            }
            lifecycle = required(lifecycle, "lifecycle");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunSummary(
            String runId,
            Instant finishedAt,
            String executionStatus,
            String assertionStatus,
            ExactAssetRef evidenceRef
    ) {
        public RunSummary {
            runId = required(runId, "runId");
            if (finishedAt == null) throw new IllegalArgumentException("finishedAt is required");
            executionStatus = required(executionStatus, "executionStatus");
            assertionStatus = required(assertionStatus, "assertionStatus");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StaleReason(String code, String assetKind, ExactAssetRef assetRef) {
        public StaleReason {
            code = required(code, "code");
            assetKind = required(assetKind, "assetKind");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandPolicy(Map<String, CommandAvailability> commands) {
        public CommandPolicy {
            commands = commands == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(commands));
        }

        public static CommandPolicy readOnly() {
            return new CommandPolicy(Map.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandAvailability(boolean allowed, String reasonCode) {
        public CommandAvailability {
            reasonCode = normalized(reasonCode);
            if (!allowed && reasonCode.isEmpty()) {
                throw new IllegalArgumentException("Denied command requires a reasonCode");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeepLinks(
            String workspace,
            String definition,
            String cases,
            String fixtures,
            String lastRun
    ) {
        public DeepLinks {
            workspace = required(workspace, "workspace deep link");
            definition = required(definition, "definition deep link");
            cases = required(cases, "cases deep link");
            fixtures = required(fixtures, "fixtures deep link");
            lastRun = normalized(lastRun);
        }
    }

    public enum Availability { AVAILABLE, UNAVAILABLE }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(CorrectnessWorkspaceProjection::normalized)
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
    }

    private static String exactFingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
