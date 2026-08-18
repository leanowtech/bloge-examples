package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministically aggregates local Capability Studio evidence into an honest Stage Acceptance
 * Result v2.
 *
 * <p>This builder is deliberately a development-evidence boundary, not an authority boundary.
 * It emits root {@code BLOCKED} unless a direct local failure is observed; target-environment
 * attestation, deployment egress observation, external evidence authenticity and owner signoffs
 * are represented by the v2
 * schema's required null/empty projections. A later caller-owned authority layer may produce a
 * separate result after it has independently supplied and verified those facts.</p>
 *
 * <p>The builder owns the nine checks in their protocol order. Missing local checks become
 * {@code NOT_RUN}; AC-STD-06 and AC-STD-09 are projected as {@code BLOCKED}, while AC-STD-01 is
 * projected as {@code FAIL} for a dirty or unknown candidate and otherwise {@code BLOCKED}.
 * This class has no API that can establish external prerequisites.</p>
 */
public final class CapabilityStudioStageAcceptanceResultV2Builder {
    /** Result schema version emitted by this builder. */
    public static final String SCHEMA_VERSION =
            "bloge.capabilityStudioStageAcceptanceResult.v2";
    /** Result kind required by the v2 schema. */
    public static final String RESULT_KIND = "STAGE_EXIT";
    /** Maximum UTF-8 wire document accepted by the existing verifier. */
    public static final int MAXIMUM_RESULT_BYTES =
            CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RESULT_ID = Pattern.compile("SAR-[A-Za-z0-9._-]{1,120}");
    private static final Pattern SAFE_REF = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[A-Fa-f0-9]{64}");
    private static final Pattern SOURCE_COMMIT = Pattern.compile("[A-Fa-f0-9]{7,64}");
    private static final Set<String> CHECK_IDS = Set.of(
            "AC-STD-01", "AC-STD-02", "AC-STD-03", "AC-STD-04", "AC-STD-05",
            "AC-STD-06", "AC-STD-07", "AC-STD-08", "AC-STD-09");
    private static final List<String> CHECK_ORDER = List.of(
            "AC-STD-01", "AC-STD-02", "AC-STD-03", "AC-STD-04", "AC-STD-05",
            "AC-STD-06", "AC-STD-07", "AC-STD-08", "AC-STD-09");
    private static final Set<String> EXTERNAL_BLOCKED_CHECKS = Set.of(
            "AC-STD-01", "AC-STD-06", "AC-STD-09");
    private static final List<String> DIAGNOSTIC_ORDER = List.of(
            "RUN_NOT_STARTED", "ENVIRONMENT_ATTESTATION_UNAVAILABLE",
            "DEPLOYMENT_EGRESS_UNAVAILABLE", "SIGNOFFS_UNAVAILABLE",
            "CANDIDATE_NOT_CLEAN", "ACCEPTANCE_CHECK_FAILED",
            "ACCEPTANCE_CHECK_BLOCKED", "ACCEPTANCE_CHECK_NOT_RUN");

    private final String resultId;
    private final int revision;
    private final String contractId;
    private final String contractRevision;
    private final CandidateBuild candidateBuild;
    private final ExactRef baselineRef;
    private final ExactRef demoPackRef;
    private final String candidateIntentFingerprint;
    private final String environmentFingerprint;
    private final ExecutionWindow executionWindow;
    private final Map<String, Check> checks = new LinkedHashMap<>();
    private final Map<String, EvidenceRef> evidence = new LinkedHashMap<>();

    /**
     * Creates a builder bound to one immutable candidate execution.
     *
     * @param resultId result artifact identity
     * @param revision result artifact revision
     * @param contractId acceptance contract identity
     * @param contractRevision acceptance contract revision
     * @param candidateBuild immutable candidate build binding
     * @param baselineRef exact local baseline reference
     * @param demoPackRef exact local demo-pack reference
     * @param candidateIntentFingerprint candidate execution intent fingerprint
     * @param environmentFingerprint observed local execution environment fingerprint
     * @param executionWindow execution timestamps; start and completion are both null only for a
     *                        pre-execution blocked result
     */
    public CapabilityStudioStageAcceptanceResultV2Builder(
            String resultId,
            int revision,
            String contractId,
            String contractRevision,
            CandidateBuild candidateBuild,
            ExactRef baselineRef,
            ExactRef demoPackRef,
            String candidateIntentFingerprint,
            String environmentFingerprint,
            ExecutionWindow executionWindow) {
        this.resultId = requirePattern(resultId, "resultId", RESULT_ID);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        this.revision = revision;
        this.contractId = requireSafeRef(contractId, "contractId");
        this.contractRevision = requireSafeRef(contractRevision, "contractRevision");
        this.candidateBuild = Objects.requireNonNull(candidateBuild, "candidateBuild is required");
        this.baselineRef = Objects.requireNonNull(baselineRef, "baselineRef is required");
        this.demoPackRef = Objects.requireNonNull(demoPackRef, "demoPackRef is required");
        this.candidateIntentFingerprint = requireFingerprint(
                candidateIntentFingerprint, "candidateIntentFingerprint");
        this.environmentFingerprint = requireFingerprint(
                environmentFingerprint, "environmentFingerprint");
        this.executionWindow = Objects.requireNonNull(
                executionWindow, "executionWindow is required");
        if (candidateBuild.sourceTreeStatus() != SourceTreeStatus.CLEAN
                && executionWindow.startedAt() == null) {
            throw new IllegalArgumentException(
                    "a non-clean candidate cannot be represented before execution");
        }
    }

    /**
     * Adds one local acceptance check. Each check can be supplied at most once. The three checks
     * owned by candidate, environment, egress and signoff authority are derived by this builder
     * and cannot be supplied by callers.
     *
     * @param check typed local check projection
     * @return this builder
     */
    public CapabilityStudioStageAcceptanceResultV2Builder recordCheck(Check check) {
        Objects.requireNonNull(check, "check is required");
        if (EXTERNAL_BLOCKED_CHECKS.contains(check.checkId())) {
            throw new IllegalArgumentException(
                    check.checkId() + " is authority-derived and cannot be recorded locally");
        }
        if (check.status() == CheckStatus.FAIL
                && executionWindow.startedAt() == null) {
            throw new IllegalArgumentException(
                    "a local acceptance failure requires a completed execution window");
        }
        if (checks.putIfAbsent(check.checkId(), check) != null) {
            throw new IllegalStateException("acceptance check already recorded: " + check.checkId());
        }
        return this;
    }

    /**
     * Adds one payload-free evidence coordinate. Evidence IDs are immutable and unique within a
     * result; the emitted catalog is sorted by evidence ID regardless of insertion order.
     *
     * @param evidenceRef local evidence coordinate
     * @return this builder
     */
    public CapabilityStudioStageAcceptanceResultV2Builder recordEvidence(EvidenceRef evidenceRef) {
        Objects.requireNonNull(evidenceRef, "evidenceRef is required");
        if (evidence.putIfAbsent(evidenceRef.evidenceId(), evidenceRef) != null) {
            throw new IllegalStateException(
                    "evidence already recorded: " + evidenceRef.evidenceId());
        }
        return this;
    }

    /**
     * Produces a detached, strict, payload-free v2 result. The result is validated by the existing
     * schema and semantic verifier before it is returned.
     *
     * @return honest root BLOCKED or FAIL result
     */
    public ObjectNode build() {
        validateEvidenceReferences();
        CheckStatus rootStatus = rootStatus();
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("resultId", resultId);
        result.put("revision", revision);
        result.put("contractId", contractId);
        result.put("contractRevision", contractRevision);
        result.put("resultKind", RESULT_KIND);
        result.put("status", rootStatus.name());
        result.put("decidedAt", executionWindow.decidedAt());

        ObjectNode binding = result.putObject("candidateExecutionBinding");
        writeCandidate(binding.putObject("candidateBuild"));
        binding.put("candidateIntentFingerprint", candidateIntentFingerprint);
        writeExactRef(binding.putObject("baselineRef"), baselineRef);
        writeExactRef(binding.putObject("demoPackRef"), demoPackRef);
        binding.put("environmentFingerprint", environmentFingerprint);
        if (executionWindow.startedAt() == null) {
            binding.putNull("executionStartedAt");
            binding.putNull("evidenceCompletedAt");
        } else {
            binding.put("executionStartedAt", executionWindow.startedAt());
            binding.put("evidenceCompletedAt", executionWindow.evidenceCompletedAt());
        }

        result.putNull("environmentAttestation");
        result.putNull("deploymentEgressObservation");

        ArrayNode checkArray = result.putArray("acceptanceChecks");
        for (String checkId : CHECK_ORDER) {
            Check check = checks.get(checkId);
            CheckStatus status = effectiveStatus(checkId, check);
            ObjectNode target = checkArray.addObject();
            target.put("checkId", checkId);
            target.put("status", status.name());
            ArrayNode ids = target.putArray("evidenceIds");
            if (check != null) {
                List<String> evidenceIds = new ArrayList<>(check.evidenceIds());
                evidenceIds.sort(Comparator.naturalOrder());
                evidenceIds.forEach(ids::add);
            }
        }

        ArrayNode evidenceArray = result.putArray("evidenceRefs");
        List<EvidenceRef> evidenceRefs = new ArrayList<>(evidence.values());
        evidenceRefs.sort(Comparator.comparing(EvidenceRef::evidenceId));
        for (EvidenceRef ref : evidenceRefs) {
            writeEvidence(evidenceArray.addObject(), ref);
        }

        result.put("evidenceClosureFingerprint",
                CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result));
        result.putArray("signoffs");
        writeDiagnostics(result.putArray("diagnostics"));

        validate(result, Instant.parse(executionWindow.decidedAt()));
        return result;
    }

    /**
     * Produces the exact UTF-8 JSON wire artifact.
     *
     * @return deterministic UTF-8 result bytes
     */
    public byte[] buildBytes() {
        try {
            return JSON.writeValueAsBytes(build());
        } catch (Exception failure) {
            throw new IllegalStateException("stage acceptance result cannot be serialized", failure);
        }
    }

    /**
     * Alias for producer-oriented callers.
     *
     * @return strict Stage Acceptance Result v2 artifact
     */
    public ObjectNode produce() {
        return build();
    }

    private CheckStatus effectiveStatus(String checkId, Check check) {
        if ("AC-STD-01".equals(checkId)
                && candidateBuild.sourceTreeStatus() != SourceTreeStatus.CLEAN) {
            return CheckStatus.FAIL;
        }
        if (EXTERNAL_BLOCKED_CHECKS.contains(checkId)) {
            return CheckStatus.BLOCKED;
        }
        return check == null ? CheckStatus.NOT_RUN : check.status();
    }

    private CheckStatus rootStatus() {
        if (candidateBuild.sourceTreeStatus() != SourceTreeStatus.CLEAN
                || CHECK_ORDER.stream().anyMatch(id -> !EXTERNAL_BLOCKED_CHECKS.contains(id)
                && checks.get(id) != null
                && checks.get(id).status() == CheckStatus.FAIL)) {
            return CheckStatus.FAIL;
        }
        return CheckStatus.BLOCKED;
    }

    private void writeCandidate(ObjectNode target) {
        target.put("buildRef", candidateBuild.buildRef());
        target.put("revision", candidateBuild.revision());
        target.put("sourceCommit", candidateBuild.sourceCommit());
        target.put("sourceTreeStatus", candidateBuild.sourceTreeStatus().name());
        target.put("artifactFingerprint", candidateBuild.artifactFingerprint());
    }

    private static void writeExactRef(ObjectNode target, ExactRef ref) {
        target.put("exactRef", ref.exactRef());
        target.put("fingerprint", ref.fingerprint());
    }

    private static void writeEvidence(ObjectNode target, EvidenceRef ref) {
        target.put("evidenceId", ref.evidenceId());
        target.put("exactRef", ref.exactRef());
        target.put("fingerprint", ref.fingerprint());
        target.put("status", "AVAILABLE");
    }

    private void writeDiagnostics(ArrayNode target) {
        List<String> codes = new ArrayList<>();
        if (executionWindow.startedAt() == null) {
            addDiagnostic(codes, "RUN_NOT_STARTED");
        }
        addDiagnostic(codes, "ENVIRONMENT_ATTESTATION_UNAVAILABLE");
        addDiagnostic(codes, "DEPLOYMENT_EGRESS_UNAVAILABLE");
        addDiagnostic(codes, "SIGNOFFS_UNAVAILABLE");
        if (candidateBuild.sourceTreeStatus() != SourceTreeStatus.CLEAN) {
            addDiagnostic(codes, "CANDIDATE_NOT_CLEAN");
        }
        if (rootStatus() == CheckStatus.FAIL) {
            addDiagnostic(codes, "ACCEPTANCE_CHECK_FAILED");
        }
        addDiagnostic(codes, "ACCEPTANCE_CHECK_BLOCKED");
        if (CHECK_ORDER.stream().anyMatch(id -> effectiveStatus(id, checks.get(id))
                == CheckStatus.NOT_RUN)) {
            addDiagnostic(codes, "ACCEPTANCE_CHECK_NOT_RUN");
        }
        for (String code : DIAGNOSTIC_ORDER) {
            if (codes.contains(code)) {
                target.addObject().put("code", code);
            }
        }
    }

    private static void addDiagnostic(List<String> codes, String code) {
        if (!codes.contains(code)) {
            codes.add(code);
        }
    }

    private void validateEvidenceReferences() {
        for (String checkId : CHECK_ORDER) {
            Check check = checks.get(checkId);
            if (check != null) {
                for (String evidenceId : check.evidenceIds()) {
                    if (!evidence.containsKey(evidenceId)) {
                        throw new IllegalStateException(
                                "acceptance check references unknown evidence: " + evidenceId);
                    }
                }
            }
        }
    }

    private static void validate(ObjectNode result, Instant now) {
        if (!CapabilityStudioSchemaSupport.validate(
                result, CapabilityStudioSchemaSupport.STAGE_ACCEPTANCE_RESULT_V2_RESOURCE).isEmpty()) {
            throw new IllegalStateException(
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
        }
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult verification =
                new CapabilityStudioStageAcceptanceResultV2Verifier().verify(result, now);
        if (!verification.verified()) {
            throw new IllegalStateException(verification.errorCode());
        }
    }

    private static String requireSafeRef(String value, String field) {
        return requirePattern(value, field, SAFE_REF);
    }

    private static String requireFingerprint(String value, String field) {
        return requirePattern(value, field, FINGERPRINT);
    }

    private static String requirePattern(String value, String field, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has invalid protocol shape");
        }
        return value;
    }

    private static String normalizeTimestamp(String value, String field, boolean nullable) {
        if (value == null && nullable) {
            return null;
        }
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant");
        }
    }

    /**
     * Candidate build data frozen at construction time.
     *
     * @param buildRef build reference
     * @param revision candidate revision
     * @param sourceCommit source commit
     * @param sourceTreeStatus source tree state
     * @param artifactFingerprint candidate artifact fingerprint
     */
    public record CandidateBuild(
            String buildRef,
            String revision,
            String sourceCommit,
            SourceTreeStatus sourceTreeStatus,
            String artifactFingerprint) {
        /** Validates the candidate build binding. */
        public CandidateBuild {
            buildRef = requireSafeRef(buildRef, "candidateBuild.buildRef");
            revision = requireSafeRef(revision, "candidateBuild.revision");
            if (sourceCommit == null || !SOURCE_COMMIT.matcher(sourceCommit).matches()) {
                throw new IllegalArgumentException("candidateBuild.sourceCommit has invalid shape");
            }
            sourceTreeStatus = Objects.requireNonNull(
                    sourceTreeStatus, "candidateBuild.sourceTreeStatus is required");
            artifactFingerprint = requireFingerprint(
                    artifactFingerprint, "candidateBuild.artifactFingerprint");
        }
    }

    /**
     * Exact, payload-free coordinate for a baseline, demo pack or other evidence object.
     *
     * @param exactRef exact artifact reference
     * @param fingerprint artifact fingerprint
     */
    public record ExactRef(String exactRef, String fingerprint) {
        /** Validates the exact reference. */
        public ExactRef {
            exactRef = requireSafeRef(exactRef, "exactRef");
            fingerprint = requireFingerprint(fingerprint, "fingerprint");
        }
    }

    /**
     * Local evidence coordinate; no payload or arbitrary fields can be supplied.
     *
     * @param evidenceId evidence identity
     * @param exactRef exact evidence reference
     * @param fingerprint evidence fingerprint
     */
    public record EvidenceRef(String evidenceId, String exactRef, String fingerprint) {
        /** Validates the evidence coordinate. */
        public EvidenceRef {
            evidenceId = requireSafeRef(evidenceId, "evidenceRef.evidenceId");
            exactRef = requireSafeRef(exactRef, "evidenceRef.exactRef");
            fingerprint = requireFingerprint(fingerprint, "evidenceRef.fingerprint");
        }
    }

    /**
     * One local acceptance-check projection.
     *
     * @param checkId acceptance check identity
     * @param status check status
     * @param evidenceIds referenced evidence identities
     */
    public record Check(String checkId, CheckStatus status, List<String> evidenceIds) {
        /** Validates the check projection and evidence references. */
        public Check {
            if (checkId == null || !CHECK_IDS.contains(checkId)) {
                throw new IllegalArgumentException("checkId is not an AC-STD id");
            }
            status = Objects.requireNonNull(status, "check status is required");
            if (evidenceIds == null) {
                evidenceIds = List.of();
            } else {
                List<String> copied = new ArrayList<>(evidenceIds);
                Set<String> unique = new LinkedHashSet<>();
                for (String evidenceId : copied) {
                    unique.add(requireSafeRef(evidenceId, "check.evidenceIds"));
                }
                if (unique.size() != copied.size()) {
                    throw new IllegalArgumentException("check.evidenceIds must be unique");
                }
                evidenceIds = List.copyOf(copied);
            }
            if (status == CheckStatus.PASS && evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("PASS check requires evidenceIds");
            }
        }

        /**
         * Creates a check with no attached local evidence.
         *
         * @param checkId acceptance check identity
         * @param status check status
         * @return check projection
         */
        public static Check of(String checkId, CheckStatus status) {
            return new Check(checkId, status, List.of());
        }
    }

    /** v2 acceptance-check states. */
    public enum CheckStatus {
        /** Check passed locally. */
        PASS,
        /** Check failed locally. */
        FAIL,
        /** Check is blocked by an external authority. */
        BLOCKED,
        /** Check has not run. */
        NOT_RUN
    }

    /** Candidate source-tree state. */
    public enum SourceTreeStatus {
        /** Candidate source tree is clean. */
        CLEAN,
        /** Candidate source tree contains uncommitted changes. */
        DIRTY,
        /** Candidate source tree state is unknown. */
        UNKNOWN
    }

    /**
     * Execution timestamps; start and completion are both null only for pre-execution blocking.
     *
     * @param startedAt execution start timestamp
     * @param evidenceCompletedAt evidence completion timestamp
     * @param decidedAt result decision timestamp
     */
    public record ExecutionWindow(String startedAt, String evidenceCompletedAt, String decidedAt) {
        /** Validates timestamp presence and chronological order. */
        public ExecutionWindow {
            startedAt = normalizeTimestamp(startedAt, "executionStartedAt", true);
            evidenceCompletedAt = normalizeTimestamp(evidenceCompletedAt, "evidenceCompletedAt", true);
            decidedAt = normalizeTimestamp(decidedAt, "decidedAt", false);
            if ((startedAt == null) != (evidenceCompletedAt == null)) {
                throw new IllegalArgumentException(
                        "executionStartedAt and evidenceCompletedAt must both be set or null");
            }
            if (startedAt != null
                    && (Instant.parse(evidenceCompletedAt).isBefore(Instant.parse(startedAt))
                    || Instant.parse(decidedAt).isBefore(Instant.parse(evidenceCompletedAt)))) {
                throw new IllegalArgumentException("execution timestamps are out of order");
            }
        }

        /**
         * Creates a completed execution window.
         *
         * @param startedAt execution start timestamp
         * @param evidenceCompletedAt evidence completion timestamp
         * @param decidedAt result decision timestamp
         * @return completed execution window
         */
        public static ExecutionWindow completed(
                String startedAt, String evidenceCompletedAt, String decidedAt) {
            return new ExecutionWindow(startedAt, evidenceCompletedAt, decidedAt);
        }

        /**
         * Creates a pre-execution window for a blocked result.
         *
         * @param decidedAt result decision timestamp
         * @return pre-execution window
         */
        public static ExecutionWindow notStarted(String decidedAt) {
            return new ExecutionWindow(null, null, decidedAt);
        }
    }
}
