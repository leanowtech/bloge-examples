package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Payload-free persisted evidence, live freshness and draft gate contracts.
 */
public final class AuthoringTestEvidenceProtocol {

    public static final String POLICY_VERSION =
            "visual-authoring-test-evidence-gate.v1";
    private static final Pattern CASE_PSEUDONYM =
            Pattern.compile("^case:sha256:[a-f0-9]{64}$");
    private static final Pattern TEST_REF_PSEUDONYM =
            Pattern.compile("^test-ref:sha256:[a-f0-9]{64}$");

    private AuthoringTestEvidenceProtocol() {
    }

    public enum AssetKind {
        OPERATOR,
        FUNCTION
    }

    public enum FreshnessStatus {
        CURRENT,
        STALE
    }

    public enum GateStatus {
        PASSED,
        BLOCKED
    }

    public enum StaleReason {
        POLICY_VERSION_CHANGED,
        AUTHORING_FINGERPRINT_CHANGED,
        CANONICAL_FINGERPRINT_CHANGED,
        ASSET_MISSING,
        ARTIFACT_FINGERPRINT_CHANGED,
        RUNTIME_FINGERPRINT_CHANGED,
        EXECUTION_PROFILE_CHANGED
    }

    public enum GateReason {
        DRAFT_NOT_IMPORTABLE,
        MISSING_EVIDENCE,
        EVIDENCE_STALE,
        LATEST_RUN_FAILED,
        INSUFFICIENT_CASE_COVERAGE,
        INSUFFICIENT_ASSERTION_COVERAGE,
        FUNCTION_NOT_BOUND
    }

    /**
     * Aggregate counters only; no input, output, argument or actual value is retained.
     */
    public record Coverage(
            int inputPortSchemaValidated,
            int configSchemaValidated,
            int mockedOutputSchemaValidated,
            int mockedOutputCount,
            int assertionCount
    ) {
        public Coverage {
            inputPortSchemaValidated = Math.max(0, inputPortSchemaValidated);
            configSchemaValidated = Math.max(0, configSchemaValidated);
            mockedOutputSchemaValidated = Math.max(0, mockedOutputSchemaValidated);
            mockedOutputCount = Math.max(0, mockedOutputCount);
            assertionCount = Math.max(0, assertionCount);
        }
    }

    /**
     * One payload-free case outcome.
     */
    public record CaseSummary(
            String caseId,
            String kind,
            String status,
            boolean passed,
            int assertionCount,
            long durationMicros,
            String errorCode,
            List<String> diagnosticCodes
    ) {
        public CaseSummary {
            caseId = pseudonym(caseId, "test-case", "case", CASE_PSEUDONYM);
            kind = normalized(kind, "UNSPECIFIED").toUpperCase(Locale.ROOT);
            status = normalized(status, "UNKNOWN").toUpperCase(Locale.ROOT);
            assertionCount = Math.max(0, assertionCount);
            durationMicros = Math.max(0, durationMicros);
            errorCode = normalized(errorCode, "");
            diagnosticCodes = strings(diagnosticCodes);
        }
    }

    /**
     * Immutable signed record. Its material fingerprint excludes only itself and the detached seal.
     */
    public record EvidenceRecord(
            String schemaVersion,
            AuthoringTestScope scope,
            String runId,
            AssetKind assetKind,
            String assetRef,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String artifactFingerprint,
            String runtimeFingerprint,
            String executionProfile,
            String suiteFingerprint,
            String sourceEvidenceFingerprint,
            String policyVersion,
            String proofMode,
            String bindingStatus,
            boolean passed,
            int totalCases,
            int passedCases,
            int failedCases,
            int requiredCaseCount,
            Coverage coverage,
            List<CaseSummary> cases,
            List<String> declaredTestRefs,
            List<String> diagnosticCodes,
            Instant executedAt,
            String actorId,
            boolean payloadPersisted,
            String materialFingerprint,
            VisualRunEvidenceSeal seal
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringTestEvidenceRecord.v1";

        public EvidenceRecord {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            runId = normalized(runId, "");
            assetKind = assetKind == null ? AssetKind.OPERATOR : assetKind;
            assetRef = normalized(assetRef, "");
            draftId = normalized(draftId, "");
            authoringRevision = Math.max(0, authoringRevision);
            authoringFingerprint = normalized(authoringFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            artifactFingerprint = normalized(artifactFingerprint, "");
            runtimeFingerprint = normalized(runtimeFingerprint, "");
            executionProfile = normalized(executionProfile, "");
            suiteFingerprint = normalized(suiteFingerprint, "");
            sourceEvidenceFingerprint = normalized(sourceEvidenceFingerprint, "");
            policyVersion = normalized(policyVersion, POLICY_VERSION);
            proofMode = normalized(proofMode, "UNKNOWN").toUpperCase(Locale.ROOT);
            bindingStatus = normalized(bindingStatus, "").toUpperCase(Locale.ROOT);
            totalCases = Math.max(0, totalCases);
            passedCases = Math.max(0, passedCases);
            failedCases = Math.max(0, failedCases);
            requiredCaseCount = Math.max(1, requiredCaseCount);
            coverage = coverage == null ? new Coverage(0, 0, 0, 0, 0) : coverage;
            cases = cases == null ? List.of() : List.copyOf(cases);
            declaredTestRefs = pseudonyms(
                    declaredTestRefs,
                    "test-ref",
                    TEST_REF_PSEUDONYM);
            diagnosticCodes = strings(diagnosticCodes);
            executedAt = executedAt == null ? Instant.EPOCH : executedAt;
            actorId = normalized(actorId, "");
            payloadPersisted = false;
            materialFingerprint = normalized(materialFingerprint, "");
            seal = seal == null ? VisualRunEvidenceSeal.unsigned() : seal;
        }

        EvidenceRecord withIntegrity(
                String fingerprint,
                VisualRunEvidenceSeal evidenceSeal) {
            return new EvidenceRecord(
                    schemaVersion,
                    scope,
                    runId,
                    assetKind,
                    assetRef,
                    draftId,
                    authoringRevision,
                    authoringFingerprint,
                    canonicalFingerprint,
                    artifactFingerprint,
                    runtimeFingerprint,
                    executionProfile,
                    suiteFingerprint,
                    sourceEvidenceFingerprint,
                    policyVersion,
                    proofMode,
                    bindingStatus,
                    passed,
                    totalCases,
                    passedCases,
                    failedCases,
                    requiredCaseCount,
                    coverage,
                    cases,
                    declaredTestRefs,
                    diagnosticCodes,
                    executedAt,
                    actorId,
                    false,
                    fingerprint,
                    evidenceSeal);
        }
    }

    /**
     * Immutable evidence plus a freshness decision calculated against live state.
     */
    public record EvidenceView(
            String schemaVersion,
            EvidenceRecord evidence,
            String integrityStatus,
            FreshnessStatus freshness,
            List<StaleReason> staleReasons,
            long observedDraftRevision,
            String observedAuthoringFingerprint,
            String observedCanonicalFingerprint,
            Instant evaluatedAt
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringTestEvidenceView.v1";

        public EvidenceView {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            integrityStatus = normalized(integrityStatus, "VERIFIED")
                    .toUpperCase(Locale.ROOT);
            freshness = freshness == null ? FreshnessStatus.STALE : freshness;
            staleReasons = staleReasons == null ? List.of() : List.copyOf(staleReasons);
            observedDraftRevision = Math.max(0, observedDraftRevision);
            observedAuthoringFingerprint = normalized(observedAuthoringFingerprint, "");
            observedCanonicalFingerprint = normalized(observedCanonicalFingerprint, "");
            evaluatedAt = evaluatedAt == null ? Instant.EPOCH : evaluatedAt;
        }
    }

    /**
     * One current asset's deterministic gate decision.
     */
    public record AssetGate(
            AssetKind assetKind,
            String assetRef,
            GateStatus status,
            List<GateReason> reasons,
            String evidenceRunId,
            String evidenceFingerprint,
            FreshnessStatus freshness,
            int requiredCases,
            int observedCases,
            int observedAssertions,
            String proofMode
    ) {
        public AssetGate {
            assetKind = assetKind == null ? AssetKind.OPERATOR : assetKind;
            assetRef = normalized(assetRef, "");
            status = status == null ? GateStatus.BLOCKED : status;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            evidenceRunId = normalized(evidenceRunId, "");
            evidenceFingerprint = normalized(evidenceFingerprint, "");
            freshness = freshness == null ? FreshnessStatus.STALE : freshness;
            requiredCases = Math.max(1, requiredCases);
            observedCases = Math.max(0, observedCases);
            observedAssertions = Math.max(0, observedAssertions);
            proofMode = normalized(proofMode, "");
        }
    }

    /**
     * Draft-level TEST_EVIDENCED gate. It is deliberately not a production-readiness decision.
     */
    public record DraftGate(
            String schemaVersion,
            AuthoringTestScope scope,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String policyVersion,
            GateStatus status,
            String achievedMaturity,
            int requiredAssets,
            int satisfiedAssets,
            List<GateReason> reasons,
            List<AssetGate> assets,
            Instant evaluatedAt
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringTestEvidenceGate.v1";

        public DraftGate {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            draftId = normalized(draftId, "");
            authoringRevision = Math.max(0, authoringRevision);
            authoringFingerprint = normalized(authoringFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            policyVersion = normalized(policyVersion, POLICY_VERSION);
            status = status == null ? GateStatus.BLOCKED : status;
            achievedMaturity = normalized(achievedMaturity, "DESIGN_READY");
            requiredAssets = Math.max(0, requiredAssets);
            satisfiedAssets = Math.max(0, satisfiedAssets);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            assets = assets == null ? List.of() : List.copyOf(assets);
            evaluatedAt = evaluatedAt == null ? Instant.EPOCH : evaluatedAt;
        }
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> normalized(value, ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> pseudonyms(
            List<String> values,
            String prefix,
            Pattern accepted) {
        return values == null ? List.of() : values.stream()
                .map(value -> normalized(value, ""))
                .filter(value -> !value.isBlank())
                .map(value -> pseudonym(value, "", prefix, accepted))
                .distinct()
                .toList();
    }

    private static String pseudonym(
            String value,
            String fallback,
            String prefix,
            Pattern accepted) {
        String normalized = normalized(value, fallback);
        if (accepted.matcher(normalized).matches()) {
            return normalized;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return prefix + ":sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
