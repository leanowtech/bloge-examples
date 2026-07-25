package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic payload-free ANEKE correctness-workbook seed for one Scenario batch.
 *
 * <p>The seed binds a signed terminal batch index, its signed retention registration, and every
 * child workbook fingerprint already anchored by the batch evidence. It keeps only bounded child
 * correctness summaries and blockers, so a 256-entry batch cannot multiply every Scenario case
 * payload into one unbounded response. A consumer can traverse the exact child workbook identities
 * when it needs case-level detail.</p>
 *
 * @param schemaVersion workbook-seed protocol version
 * @param seedFingerprint canonical fingerprint with this field blanked
 * @param scope complete enterprise namespace
 * @param jobId exact terminal batch
 * @param requestId exact batch idempotency identity
 * @param requestFingerprint exact original request content address
 * @param manifestFingerprint exact ordered execution manifest
 * @param terminalJobFingerprint exact terminal job projection
 * @param evidenceBundleFingerprint exact signed batch evidence
 * @param evidenceIndexFingerprint exact signed terminal index
 * @param evidenceKeyId batch evidence signing-key identity
 * @param workbookSeal detached signature over this deterministic seed identity
 * @param retentionProof stable signed batch-retention registration
 * @param status terminal batch execution status
 * @param summary server-derived terminal item counters
 * @param entries complete ordered entry correctness projections
 * @param gateReady whether every execution and child workbook is publishable
 * @param blockers sorted bounded batch publication blockers
 */
public record ScenarioRehearsalBatchWorkbookSeed(
        String schemaVersion,
        String seedFingerprint,
        CapabilitySnapshot.Scope scope,
        String jobId,
        String requestId,
        String requestFingerprint,
        String manifestFingerprint,
        String terminalJobFingerprint,
        String evidenceBundleFingerprint,
        String evidenceIndexFingerprint,
        String evidenceKeyId,
        VisualRunEvidenceSeal workbookSeal,
        ScenarioRehearsalBatchRetentionEvent retentionProof,
        ScenarioRehearsalBatchJob.Status status,
        ScenarioRehearsalBatchJob.Summary summary,
        List<EntryResult> entries,
        boolean gateReady,
        List<String> blockers
) {
    /** Current Scenario batch correctness-workbook seed version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1";
    /** Maximum canonical seed bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            8 * 1024 * 1024;
    /** Maximum canonical bytes admitted to detached-attestation fingerprinting. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    /** Maximum deterministic publication blockers represented by one seed. */
    public static final int MAXIMUM_BLOCKERS = 16;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Enforces complete ordered source closure and independently derived gate readiness. */
    public ScenarioRehearsalBatchWorkbookSeed {
        schemaVersion = version(schemaVersion);
        seedFingerprint = optionalFingerprint(
                seedFingerprint, "seedFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        jobId = identifier(jobId, "jobId");
        if (!ScenarioRehearsalBatchIdentity
                .hasCanonicalShape(jobId)) {
            throw new IllegalArgumentException(
                    "batch workbook jobId must be canonical");
        }
        requestId = identifier(requestId, "requestId");
        requestFingerprint = fingerprint(
                requestFingerprint, "requestFingerprint");
        manifestFingerprint = fingerprint(
                manifestFingerprint, "manifestFingerprint");
        terminalJobFingerprint = fingerprint(
                terminalJobFingerprint,
                "terminalJobFingerprint");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        evidenceIndexFingerprint = fingerprint(
                evidenceIndexFingerprint,
                "evidenceIndexFingerprint");
        evidenceKeyId = verificationKeyId(
                evidenceKeyId, "evidenceKeyId");
        workbookSeal = workbookSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : workbookSeal;
        if (workbookSeal.signed()
                && (!VisualRunEvidenceSeal.SCHEMA_VERSION.equals(
                workbookSeal.schemaVersion())
                || !"Ed25519".equals(
                workbookSeal.algorithm()))) {
            throw new IllegalArgumentException(
                    "batch workbook seal is invalid");
        }
        retentionProof = Objects.requireNonNull(
                retentionProof, "retentionProof");
        if (retentionProof.revision() != 1
                || retentionProof.type()
                != ScenarioRehearsalBatchRetentionEvent.Type
                .RETENTION_REGISTERED
                || !retentionProof.scope().equals(scope)
                || !retentionProof.jobId().equals(jobId)
                || !retentionProof.requestId().equals(requestId)
                || !retentionProof.manifestFingerprint().equals(
                manifestFingerprint)
                || !retentionProof.evidenceBundleFingerprint()
                .equals(evidenceBundleFingerprint)
                || !retentionProof.evidenceSeal().signed()
                || !retentionProof.evidenceSeal()
                .materialFingerprint().equals(
                        retentionProof.eventFingerprint())) {
            throw new IllegalArgumentException(
                    "batch workbook retention proof is not a signed registration event");
        }
        status = Objects.requireNonNull(status, "status");
        if (!status.terminal()) {
            throw new IllegalArgumentException(
                    "batch workbook requires a terminal job");
        }
        summary = Objects.requireNonNull(summary, "summary");
        entries = entries == null
                ? List.of() : List.copyOf(entries);
        if (entries.isEmpty()
                || entries.size()
                > ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES
                || entries.size() != summary.totalItems()) {
            throw new IllegalArgumentException(
                    "batch workbook entries differ from terminal summary");
        }
        Set<String> entryIds = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            EntryResult entry = Objects.requireNonNull(
                    entries.get(index), "entry");
            if (entry.entryIndex() != index
                    || !entryIds.add(entry.entryId())) {
                throw new IllegalArgumentException(
                        "batch workbook entries must be ordered and unique");
            }
        }
        if (!summary(entries).equals(summary)
                || !statusMatches(status, summary)) {
            throw new IllegalArgumentException(
                    "batch workbook status or summary is not entry-derived");
        }
        blockers = orderedBlockers(blockers);
        List<String> expected =
                deriveBlockers(status, entries);
        if (!blockers.equals(expected)
                || gateReady != blockers.isEmpty()) {
            throw new IllegalArgumentException(
                    "batch workbook gate readiness must be source-derived");
        }
    }

    /**
     * One bounded entry projection linked to an optional evidence-backed child workbook.
     *
     * @param entryIndex zero-based immutable manifest position
     * @param entryId caller-authored stable entry identity
     * @param compiledPlanRef exact compiled Scenario plan
     * @param childRequestId exact child aggregate idempotency identity
     * @param expectedRunId deterministic child aggregate identity
     * @param status terminal queue item status
     * @param attemptCount infrastructure claim count
     * @param runId actual child aggregate identity, blank without evidence
     * @param childEvidenceBundleFingerprint child signed evidence, blank without evidence
     * @param childWorkbookSeedFingerprint child workbook address, blank without evidence
     * @param failureCode stable item failure, blank on pass
     * @param childWorkbook bounded correctness projection, null without evidence
     */
    public record EntryResult(
            int entryIndex,
            String entryId,
            MirrorArtifactRef compiledPlanRef,
            String childRequestId,
            String expectedRunId,
            ScenarioRehearsalBatchItemPage.Status status,
            int attemptCount,
            String runId,
            String childEvidenceBundleFingerprint,
            String childWorkbookSeedFingerprint,
            String failureCode,
            ChildWorkbook childWorkbook
    ) {
        /** Enforces item, manifest, and child-workbook correspondence. */
        public EntryResult {
            if (entryIndex < 0
                    || entryIndex
                    >= ScenarioRehearsalBatchRequest
                    .MAXIMUM_ENTRIES) {
                throw new IllegalArgumentException(
                        "batch workbook entryIndex is invalid");
            }
            entryId = identifier(entryId, "entryId");
            compiledPlanRef = requireKind(
                    compiledPlanRef,
                    "COMPILED_REHEARSAL_PLAN",
                    "compiledPlanRef");
            childRequestId = identifier(
                    childRequestId, "childRequestId");
            expectedRunId = identifier(
                    expectedRunId, "expectedRunId");
            if (!ScenarioRehearsalRunIdentity
                    .hasCanonicalShape(expectedRunId)) {
                throw new IllegalArgumentException(
                        "batch workbook expectedRunId must be canonical");
            }
            status = Objects.requireNonNull(status, "status");
            if (!status.terminal()
                    || attemptCount < 0
                    || attemptCount > 5) {
                throw new IllegalArgumentException(
                        "batch workbook item lifecycle is invalid");
            }
            runId = optionalIdentifier(runId, "runId");
            childEvidenceBundleFingerprint =
                    optionalFingerprint(
                            childEvidenceBundleFingerprint,
                            "childEvidenceBundleFingerprint");
            childWorkbookSeedFingerprint =
                    optionalFingerprint(
                            childWorkbookSeedFingerprint,
                            "childWorkbookSeedFingerprint");
            failureCode = optionalMachineCode(
                    failureCode, "failureCode");
            boolean evidenceBacked =
                    !runId.isBlank()
                            && !childEvidenceBundleFingerprint.isBlank()
                            && !childWorkbookSeedFingerprint.isBlank();
            boolean noEvidence =
                    runId.isBlank()
                            && childEvidenceBundleFingerprint.isBlank()
                            && childWorkbookSeedFingerprint.isBlank();
            if (!(evidenceBacked || noEvidence)
                    || evidenceBacked
                    != (childWorkbook != null)
                    || evidenceBacked
                    && (!runId.equals(expectedRunId)
                    || !runId.equals(childWorkbook.runId())
                    || !childRequestId.equals(
                    childWorkbook.requestId())
                    || !compiledPlanRef.equals(
                    childWorkbook.compiledPlanRef())
                    || !childEvidenceBundleFingerprint.equals(
                    childWorkbook.evidenceBundleFingerprint())
                    || !childWorkbookSeedFingerprint.equals(
                    childWorkbook.seedFingerprint())
                    || outcome(status)
                    != childWorkbook.outcome())
                    || status
                    == ScenarioRehearsalBatchItemPage.Status.PASSED
                    && !evidenceBacked
                    || status
                    == ScenarioRehearsalBatchItemPage.Status.PASSED
                    && !failureCode.isBlank()) {
                throw new IllegalArgumentException(
                        "batch workbook child closure is inconsistent");
            }
        }

        /** @return whether signed batch evidence anchors a child workbook */
        public boolean evidenceBacked() {
            return childWorkbook != null;
        }
    }

    /**
     * Bounded child correctness projection whose full seed remains separately addressable.
     *
     * @param schemaVersion child workbook protocol version
     * @param seedFingerprint exact child workbook address
     * @param runId exact child aggregate run
     * @param requestId exact child aggregate request
     * @param compiledPlanRef exact child compiled plan
     * @param scenarioPackRef exact authored Scenario pack
     * @param targetCapabilityRef exact rehearsed capability
     * @param evidenceBundleFingerprint exact child signed evidence
     * @param resultFingerprint exact child aggregate result
     * @param evidenceKeyId child evidence signing-key identity
     * @param retentionProofFingerprint child signed retention registration identity
     * @param outcome child business correctness outcome
     * @param summary child case/assertion counters
     * @param gateReady child publication readiness
     * @param blockers sorted child publication blockers
     */
    public record ChildWorkbook(
            String schemaVersion,
            String seedFingerprint,
            String runId,
            String requestId,
            MirrorArtifactRef compiledPlanRef,
            MirrorArtifactRef scenarioPackRef,
            MirrorArtifactRef targetCapabilityRef,
            String evidenceBundleFingerprint,
            String resultFingerprint,
            String evidenceKeyId,
            String retentionProofFingerprint,
            ScenarioCaseRehearsalResult.Outcome outcome,
            ScenarioRehearsalResult.Summary summary,
            boolean gateReady,
            List<String> blockers
    ) {
        /** Validates one exact, bounded child workbook reference and gate projection. */
        public ChildWorkbook {
            if (!ScenarioRehearsalWorkbookSeed.SCHEMA_VERSION
                    .equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "unsupported child workbook schema");
            }
            seedFingerprint = fingerprint(
                    seedFingerprint, "seedFingerprint");
            runId = identifier(runId, "runId");
            if (!ScenarioRehearsalRunIdentity
                    .hasCanonicalShape(runId)) {
                throw new IllegalArgumentException(
                        "child workbook runId must be canonical");
            }
            requestId = identifier(requestId, "requestId");
            compiledPlanRef = requireKind(
                    compiledPlanRef,
                    "COMPILED_REHEARSAL_PLAN",
                    "compiledPlanRef");
            scenarioPackRef = requireKind(
                    scenarioPackRef,
                    "SCENARIO_PACK",
                    "scenarioPackRef");
            targetCapabilityRef = requireKind(
                    targetCapabilityRef,
                    "CAPABILITY",
                    "targetCapabilityRef");
            evidenceBundleFingerprint = fingerprint(
                    evidenceBundleFingerprint,
                    "evidenceBundleFingerprint");
            resultFingerprint = fingerprint(
                    resultFingerprint, "resultFingerprint");
            evidenceKeyId = verificationKeyId(
                    evidenceKeyId, "evidenceKeyId");
            retentionProofFingerprint = fingerprint(
                    retentionProofFingerprint,
                    "retentionProofFingerprint");
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            summary = Objects.requireNonNull(
                    summary, "summary");
            blockers = ScenarioRehearsalBatchWorkbookSeed
                    .orderedChildBlockers(blockers);
            if (gateReady != blockers.isEmpty()) {
                throw new IllegalArgumentException(
                        "child workbook gate readiness is inconsistent");
            }
        }

        private static ChildWorkbook from(
                ScenarioRehearsalWorkbookSeed seed) {
            return new ChildWorkbook(
                    seed.schemaVersion(),
                    seed.seedFingerprint(),
                    seed.runId(),
                    seed.requestId(),
                    seed.compiledPlanRef(),
                    seed.scenarioPackRef(),
                    seed.targetCapabilityRef(),
                    seed.evidenceBundleFingerprint(),
                    seed.resultFingerprint(),
                    seed.evidenceKeyId(),
                    seed.retentionProof().eventFingerprint(),
                    seed.outcome(),
                    seed.summary(),
                    seed.gateReady(),
                    seed.blockers());
        }
    }

    /**
     * Projects verified immutable batch and child sources into one deterministic seed.
     *
     * @param mapper canonical protocol mapper
     * @param bundle independently verified signed terminal batch evidence
     * @param retentionState verified active batch-retention projection
     * @param retentionEvents verified complete batch-retention event chain
     * @param childWorkbooks exact child workbook seeds keyed by run id
     * @return sealed deterministic payload-free batch workbook seed
     */
    public static ScenarioRehearsalBatchWorkbookSeed project(
            ObjectMapper mapper,
            ScenarioRehearsalBatchEvidenceBundle bundle,
            ScenarioRehearsalBatchRetentionState retentionState,
            List<ScenarioRehearsalBatchRetentionEvent>
                    retentionEvents,
            Map<String, ScenarioRehearsalWorkbookSeed>
                    childWorkbooks) {
        Objects.requireNonNull(mapper, "mapper");
        ScenarioRehearsalBatchEvidenceBundle exactBundle =
                Objects.requireNonNull(bundle, "bundle");
        ScenarioRehearsalBatchEvidenceIndex index =
                exactBundle.index();
        ScenarioRehearsalBatchRetentionState state =
                Objects.requireNonNull(
                        retentionState, "retentionState");
        List<ScenarioRehearsalBatchRetentionEvent> events =
                retentionEvents == null
                        ? List.of()
                        : List.copyOf(retentionEvents);
        if (events.isEmpty()
                || state.status()
                != ScenarioRehearsalBatchRetentionState.Status
                .RETAINED
                || !state.scope().equals(
                index.job().scope())
                || !state.jobId().equals(index.job().jobId())
                || !state.requestId().equals(
                index.job().requestId())
                || !state.manifestFingerprint().equals(
                index.manifest().manifestFingerprint())
                || !state.evidenceBundleFingerprint().equals(
                exactBundle.bundleFingerprint())) {
            throw new IllegalArgumentException(
                    "batch workbook sources do not form one active closure");
        }
        ScenarioRehearsalBatchRetentionEvent registration =
                events.getFirst();
        if (registration.revision() != 1
                || registration.type()
                != ScenarioRehearsalBatchRetentionEvent.Type
                .RETENTION_REGISTERED
                || !registration.scope().equals(
                state.scope())
                || !registration.jobId().equals(
                state.jobId())
                || !registration.requestId().equals(
                state.requestId())
                || !registration.manifestFingerprint().equals(
                state.manifestFingerprint())
                || !registration.evidenceBundleFingerprint()
                .equals(state.evidenceBundleFingerprint())
                || !registration.evidenceSeal().signed()
                || !registration.evidenceSeal()
                .materialFingerprint().equals(
                        registration.eventFingerprint())
                || !registration.retainUntil().equals(
                state.retainUntil())) {
            throw new IllegalArgumentException(
                    "batch workbook retention registration is invalid");
        }
        Map<String, ScenarioRehearsalWorkbookSeed> children =
                normalizedChildren(mapper, childWorkbooks);
        Set<String> used = new HashSet<>();
        List<EntryResult> entries =
                java.util.stream.IntStream.range(
                                0, index.items().size())
                        .mapToObj(position -> projectEntry(
                                index, position, children, used))
                        .toList();
        if (used.size() != children.size()) {
            throw new IllegalArgumentException(
                    "batch workbook includes an unreferenced child seed");
        }
        List<String> blockers = deriveBlockers(
                index.job().status(), entries);
        ScenarioRehearsalBatchWorkbookSeed material =
                new ScenarioRehearsalBatchWorkbookSeed(
                        SCHEMA_VERSION,
                        "",
                        index.job().scope(),
                        index.job().jobId(),
                        index.job().requestId(),
                        index.job().requestFingerprint(),
                        index.manifest().manifestFingerprint(),
                        index.job().recordFingerprint(),
                        exactBundle.bundleFingerprint(),
                        index.indexFingerprint(),
                        exactBundle.attestation().keyId(),
                        VisualRunEvidenceSeal.unsigned(),
                        registration,
                        index.job().status(),
                        index.job().summary(),
                        entries,
                        blockers.isEmpty(),
                        blockers);
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper,
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and checks this seed's self-fingerprint. */
    public void verify(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (!ProtocolFingerprint.ofBounded(
                mapper,
                withFingerprintAndSeal(
                        "",
                        VisualRunEvidenceSeal.unsigned()),
                MAXIMUM_CANONICAL_BYTES)
                .equals(seedFingerprint)) {
            throw new IllegalArgumentException(
                    "Scenario batch workbook seed fingerprint mismatch");
        }
    }

    /** @return identical seed carrying a replacement canonical fingerprint */
    public ScenarioRehearsalBatchWorkbookSeed withFingerprint(
            String fingerprint) {
        return withFingerprintAndSeal(
                fingerprint, workbookSeal);
    }

    /**
     * Attaches one detached seal without changing the deterministic seed content address.
     *
     * @param seal exact detached Ed25519 signature
     * @return identical deterministic seed carrying the supplied seal
     */
    public ScenarioRehearsalBatchWorkbookSeed withWorkbookSeal(
            VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                seedFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /**
     * Reconstructs the domain-separated material signed by {@link #workbookSeal()}.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 signing material
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (seedFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "batch workbook must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_WORKBOOK_V1",
                        schemaVersion,
                        jobId,
                        seedFingerprint,
                        evidenceBundleFingerprint,
                        evidenceIndexFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    private ScenarioRehearsalBatchWorkbookSeed
    withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new ScenarioRehearsalBatchWorkbookSeed(
                schemaVersion,
                fingerprint,
                scope,
                jobId,
                requestId,
                requestFingerprint,
                manifestFingerprint,
                terminalJobFingerprint,
                evidenceBundleFingerprint,
                evidenceIndexFingerprint,
                evidenceKeyId,
                seal,
                retentionProof,
                status,
                summary,
                entries,
                gateReady,
                blockers);
    }

    /** Keeps evidence and child coordinates out of generic logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalBatchWorkbookSeed[status="
                + status + ", entries=" + entries.size()
                + ", gateReady=" + gateReady + "]";
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String jobId,
            String seedFingerprint,
            String evidenceBundleFingerprint,
            String evidenceIndexFingerprint
    ) {
    }

    private static Map<String, ScenarioRehearsalWorkbookSeed>
    normalizedChildren(
            ObjectMapper mapper,
            Map<String, ScenarioRehearsalWorkbookSeed> values) {
        Map<String, ScenarioRehearsalWorkbookSeed> children =
                new LinkedHashMap<>();
        if (values == null) {
            return Map.of();
        }
        for (Map.Entry<String, ScenarioRehearsalWorkbookSeed>
                candidate : values.entrySet()) {
            String runId = identifier(
                    candidate.getKey(), "childRunId");
            ScenarioRehearsalWorkbookSeed seed =
                    Objects.requireNonNull(
                            candidate.getValue(),
                            "childWorkbook");
            seed.verify(mapper);
            if (!runId.equals(seed.runId())
                    || children.putIfAbsent(runId, seed) != null) {
                throw new IllegalArgumentException(
                        "batch workbook child identities are inconsistent");
            }
        }
        return Map.copyOf(children);
    }

    private static EntryResult projectEntry(
            ScenarioRehearsalBatchEvidenceIndex index,
            int position,
            Map<String, ScenarioRehearsalWorkbookSeed>
                    children,
            Set<String> used) {
        ScenarioRehearsalBatchRequest.Entry request =
                index.request().entries().get(position);
        ScenarioRehearsalBatchManifest.Entry manifest =
                index.manifest().entries().get(position);
        ScenarioRehearsalBatchItemPage.Item item =
                index.items().get(position);
        ScenarioRehearsalWorkbookSeed child =
                item.runId().isBlank()
                        ? null : children.get(item.runId());
        if (!item.runId().isBlank()
                && (child == null
                || !used.add(item.runId()))) {
            throw new IllegalArgumentException(
                    "batch workbook child seed is missing or duplicated");
        }
        if (!request.entryId().equals(
                manifest.entryId())
                || !request.compiledPlanRef().equals(
                manifest.compiledPlanRef())
                || !item.compiledPlanRef().equals(
                manifest.compiledPlanRef())
                || !item.childRequestId().equals(
                manifest.aggregateRequestId())) {
            throw new IllegalArgumentException(
                    "batch workbook entry differs from signed manifest");
        }
        return new EntryResult(
                position,
                manifest.entryId(),
                manifest.compiledPlanRef(),
                manifest.aggregateRequestId(),
                manifest.aggregateRunId(),
                item.status(),
                item.attemptCount(),
                item.runId(),
                item.evidenceBundleFingerprint(),
                item.workbookSeedFingerprint(),
                item.failureCode(),
                child == null
                        ? null : ChildWorkbook.from(child));
    }

    private static ScenarioRehearsalBatchJob.Summary summary(
            List<EntryResult> entries) {
        int passed = count(
                entries,
                ScenarioRehearsalBatchItemPage.Status.PASSED);
        int failed = count(
                entries,
                ScenarioRehearsalBatchItemPage.Status.FAILED);
        int indeterminate = count(
                entries,
                ScenarioRehearsalBatchItemPage.Status
                .INDETERMINATE);
        int cancelled = count(
                entries,
                ScenarioRehearsalBatchItemPage.Status
                .CANCELLED);
        return new ScenarioRehearsalBatchJob.Summary(
                entries.size(),
                entries.size(),
                passed,
                failed,
                indeterminate,
                cancelled);
    }

    private static int count(
            List<EntryResult> entries,
            ScenarioRehearsalBatchItemPage.Status status) {
        return Math.toIntExact(entries.stream()
                .filter(value -> value.status() == status)
                .count());
    }

    private static ScenarioCaseRehearsalResult.Outcome outcome(
            ScenarioRehearsalBatchItemPage.Status status) {
        return switch (status) {
            case PASSED ->
                    ScenarioCaseRehearsalResult.Outcome.PASS;
            case FAILED ->
                    ScenarioCaseRehearsalResult.Outcome.FAIL;
            case INDETERMINATE ->
                    ScenarioCaseRehearsalResult.Outcome
                    .INDETERMINATE;
            case PENDING, RUNNING, CANCELLED -> null;
        };
    }

    private static boolean statusMatches(
            ScenarioRehearsalBatchJob.Status status,
            ScenarioRehearsalBatchJob.Summary summary) {
        if (status
                == ScenarioRehearsalBatchJob.Status.SUCCEEDED) {
            return summary.passedItems()
                    == summary.totalItems();
        }
        return summary.passedItems()
                != summary.totalItems();
    }

    private static List<String> deriveBlockers(
            ScenarioRehearsalBatchJob.Status status,
            List<EntryResult> entries) {
        TreeSet<String> blockers = new TreeSet<>();
        if (status
                != ScenarioRehearsalBatchJob.Status.SUCCEEDED) {
            blockers.add("BATCH_STATUS_" + status.name());
        }
        for (EntryResult entry : entries) {
            switch (entry.status()) {
                case FAILED ->
                        blockers.add("BATCH_ITEM_FAILED");
                case INDETERMINATE ->
                        blockers.add(
                                "BATCH_ITEM_INDETERMINATE");
                case CANCELLED ->
                        blockers.add("BATCH_ITEM_CANCELLED");
                case PASSED -> {
                }
                case PENDING, RUNNING ->
                        throw new IllegalArgumentException(
                                "batch workbook item is not terminal");
            }
            if (!entry.evidenceBacked()
                    && entry.status()
                    != ScenarioRehearsalBatchItemPage.Status
                    .CANCELLED) {
                blockers.add("CHILD_EVIDENCE_MISSING");
            } else if (entry.evidenceBacked()
                    && !entry.childWorkbook().gateReady()) {
                blockers.add("CHILD_WORKBOOK_BLOCKED");
            }
        }
        return List.copyOf(blockers);
    }

    private static List<String> orderedBlockers(
            List<String> values) {
        TreeSet<String> result =
                machineCodes(values, "blocker");
        if (result.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "batch workbook blockers exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static List<String> orderedChildBlockers(
            List<String> values) {
        TreeSet<String> result =
                machineCodes(values, "childBlocker");
        if (result.size()
                > ScenarioRehearsalWorkbookSeed
                .MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "child workbook blockers exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static TreeSet<String> machineCodes(
            List<String> values,
            String field) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String code = identifier(value, field);
                if (!MACHINE_CODE.matcher(code).matches()
                        || !result.add(code)) {
                    throw new IllegalArgumentException(
                            field + " values must be unique machine codes");
                }
            }
        }
        return result;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef required =
                Objects.requireNonNull(value, field);
        if (!kind.equals(required.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return required;
    }

    private static String version(String value) {
        String normalized =
                value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch workbook seed version");
        }
        return normalized;
    }

    private static String identifier(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String verificationKeyId(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > 1_024
                || normalized.contains("\r")
                || normalized.contains("\n")) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to 1024 safe characters");
        }
        return normalized;
    }

    private static String optionalMachineCode(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !MACHINE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }
}
