package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic comparison of two independently verified, root-signed Scenario batch workbooks.
 *
 * <p>The comparison never reads mutable job projections and does not assign a synthetic quality
 * score. It binds the reviewed remediation lineage, both exact workbook identities and seals, and
 * every ordered entry projection. Resolved, remaining, and introduced blockers are set
 * differences over signed source facts.</p>
 *
 * @param schemaVersion exact comparison protocol version
 * @param comparisonFingerprint canonical address with this field blanked
 * @param scope complete enterprise namespace
 * @param remediationId exact reviewed remediation
 * @param lineageFingerprint exact submitted decision lineage
 * @param remediationPlanFingerprint exact frozen successor plan
 * @param receiptFingerprint exact successor-admission receipt
 * @param predecessor blocked signed-workbook commitment
 * @param successor terminal signed-workbook commitment
 * @param gateTransition source-derived gate transition
 * @param resolvedBlockers predecessor blockers absent from the successor
 * @param remainingBlockers blockers retained by both workbooks
 * @param introducedBlockers successor blockers absent from the predecessor
 * @param entries complete ordered entry comparisons
 */
public record ScenarioRehearsalRemediationComparison(
        String schemaVersion,
        String comparisonFingerprint,
        CapabilitySnapshot.Scope scope,
        String remediationId,
        String lineageFingerprint,
        String remediationPlanFingerprint,
        String receiptFingerprint,
        WorkbookSnapshot predecessor,
        WorkbookSnapshot successor,
        GateTransition gateTransition,
        List<String> resolvedBlockers,
        List<String> remainingBlockers,
        List<String> introducedBlockers,
        List<EntryComparison> entries
) {
    /** Current deterministic signed-workbook comparison version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationComparison.v1";
    /** Maximum canonical comparison bytes admitted to content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            20 * 1024 * 1024;
    private static final Pattern REMEDIATION_ID =
            Pattern.compile("scenario-remediation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Enforces complete source, entry, gate, and blocker-difference closure. */
    public ScenarioRehearsalRemediationComparison {
        schemaVersion = version(schemaVersion);
        comparisonFingerprint = optionalFingerprint(
                comparisonFingerprint,
                "comparisonFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        remediationId = remediationId(remediationId);
        lineageFingerprint = fingerprint(
                lineageFingerprint, "lineageFingerprint");
        remediationPlanFingerprint = fingerprint(
                remediationPlanFingerprint,
                "remediationPlanFingerprint");
        receiptFingerprint = fingerprint(
                receiptFingerprint, "receiptFingerprint");
        predecessor = Objects.requireNonNull(
                predecessor, "predecessor");
        successor = Objects.requireNonNull(
                successor, "successor");
        if (!predecessor.scope().equals(scope)
                || !successor.scope().equals(scope)
                || predecessor.jobId().equals(
                successor.jobId())
                || predecessor.gateReady()) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison workbook coordinates are inconsistent");
        }
        gateTransition = Objects.requireNonNull(
                gateTransition, "gateTransition");
        GateTransition expectedTransition =
                GateTransition.from(
                        predecessor.gateReady(),
                        successor.gateReady());
        if (gateTransition != expectedTransition) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison gate transition is not source-derived");
        }
        resolvedBlockers = machineCodes(
                resolvedBlockers, "resolvedBlocker");
        remainingBlockers = machineCodes(
                remainingBlockers, "remainingBlocker");
        introducedBlockers = machineCodes(
                introducedBlockers, "introducedBlocker");
        BlockerDiff rootDiff = BlockerDiff.between(
                predecessor.blockers(),
                successor.blockers());
        if (!resolvedBlockers.equals(rootDiff.resolved())
                || !remainingBlockers.equals(
                rootDiff.remaining())
                || !introducedBlockers.equals(
                rootDiff.introduced())) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison blockers are not source-derived");
        }
        entries = entries == null
                ? List.of() : List.copyOf(entries);
        if (entries.isEmpty()
                || entries.size()
                != predecessor.summary().totalItems()
                || entries.size()
                != successor.summary().totalItems()) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison entries differ from source workbooks");
        }
        for (int index = 0; index < entries.size(); index++) {
            EntryComparison entry =
                    Objects.requireNonNull(
                            entries.get(index), "entry");
            if (entry.entryIndex() != index) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison entries must preserve manifest order");
            }
        }
        requireWorkbookProjection(
                predecessor,
                entries.stream()
                        .map(EntryComparison::predecessor)
                        .toList());
        requireWorkbookProjection(
                successor,
                entries.stream()
                        .map(EntryComparison::successor)
                        .toList());
    }

    /** Exact truth-preserving gate transition between the two signed sources. */
    public enum GateTransition {
        /** A blocked predecessor became gate ready. */
        RESOLVED,
        /** Both workbooks remain blocked. */
        STILL_BLOCKED,
        /** A gate-ready predecessor became blocked. */
        REGRESSED,
        /** Both workbooks remain gate ready. */
        STILL_READY;

        private static GateTransition from(
                boolean predecessor,
                boolean successor) {
            if (!predecessor && successor) {
                return RESOLVED;
            }
            if (!predecessor) {
                return STILL_BLOCKED;
            }
            return successor ? STILL_READY : REGRESSED;
        }
    }

    /**
     * Exact commitment and bounded correctness summary of one signed batch workbook.
     *
     * @param workbookSchemaVersion exact workbook protocol version
     * @param scope complete enterprise namespace
     * @param jobId exact terminal batch
     * @param seedFingerprint exact deterministic workbook identity
     * @param requestFingerprint exact batch request address
     * @param manifestFingerprint exact ordered manifest address
     * @param evidenceBundleFingerprint exact signed batch evidence
     * @param evidenceIndexFingerprint exact signed terminal index
     * @param workbookSeal exact detached root signature
     * @param status terminal batch status
     * @param summary exact batch item counters
     * @param correctnessSummary aggregated child case/assertion counters
     * @param gateReady source-derived publication readiness
     * @param blockers exact sorted source blockers
     */
    public record WorkbookSnapshot(
            String workbookSchemaVersion,
            CapabilitySnapshot.Scope scope,
            String jobId,
            String seedFingerprint,
            String requestFingerprint,
            String manifestFingerprint,
            String evidenceBundleFingerprint,
            String evidenceIndexFingerprint,
            VisualRunEvidenceSeal workbookSeal,
            ScenarioRehearsalBatchJob.Status status,
            ScenarioRehearsalBatchJob.Summary summary,
            CorrectnessSummary correctnessSummary,
            boolean gateReady,
            List<String> blockers
    ) {
        /** Validates one bounded signed-workbook commitment. */
        public WorkbookSnapshot {
            if (!ScenarioRehearsalBatchWorkbookSeed
                    .SCHEMA_VERSION.equals(
                    workbookSchemaVersion)) {
                throw new IllegalArgumentException(
                        "unsupported Scenario batch workbook source");
            }
            scope = Objects.requireNonNull(scope, "scope");
            if (!ScenarioRehearsalBatchIdentity
                    .hasCanonicalShape(jobId)) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison jobId is invalid");
            }
            seedFingerprint = fingerprint(
                    seedFingerprint, "seedFingerprint");
            requestFingerprint = fingerprint(
                    requestFingerprint, "requestFingerprint");
            manifestFingerprint = fingerprint(
                    manifestFingerprint,
                    "manifestFingerprint");
            evidenceBundleFingerprint = fingerprint(
                    evidenceBundleFingerprint,
                    "evidenceBundleFingerprint");
            evidenceIndexFingerprint = fingerprint(
                    evidenceIndexFingerprint,
                    "evidenceIndexFingerprint");
            workbookSeal = Objects.requireNonNull(
                    workbookSeal, "workbookSeal");
            if (!workbookSeal.signed()
                    || !VisualRunEvidenceSeal
                    .SCHEMA_VERSION.equals(
                    workbookSeal.schemaVersion())
                    || !"Ed25519".equals(
                    workbookSeal.algorithm())) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison requires a signed workbook");
            }
            status = Objects.requireNonNull(
                    status, "status");
            if (!status.terminal()) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison requires terminal workbooks");
            }
            summary = Objects.requireNonNull(
                    summary, "summary");
            correctnessSummary = Objects.requireNonNull(
                    correctnessSummary,
                    "correctnessSummary");
            blockers = machineCodes(
                    blockers, "workbookBlocker");
            if (gateReady != blockers.isEmpty()) {
                throw new IllegalArgumentException(
                        "Scenario remediation workbook gate decision is inconsistent");
            }
        }
    }

    /**
     * Aggregated child correctness counters derived from bounded workbook entry projections.
     *
     * @param evidenceBackedEntries entries carrying a child workbook commitment
     * @param totalCases total terminal Scenario cases
     * @param passedCases passing Scenario cases
     * @param failedCases failed Scenario cases
     * @param indeterminateCases indeterminate Scenario cases
     * @param assertionResults total handling-assertion results
     * @param blockerFailures failed blocker assertions
     * @param blockerIndeterminate indeterminate blocker assertions
     * @param warningFailures failed warning assertions
     * @param warningIndeterminate indeterminate warning assertions
     */
    public record CorrectnessSummary(
            int evidenceBackedEntries,
            int totalCases,
            int passedCases,
            int failedCases,
            int indeterminateCases,
            int assertionResults,
            int blockerFailures,
            int blockerIndeterminate,
            int warningFailures,
            int warningIndeterminate
    ) {
        /** Rejects impossible aggregate child counters. */
        public CorrectnessSummary {
            if (evidenceBackedEntries < 0
                    || totalCases < 0
                    || passedCases < 0
                    || failedCases < 0
                    || indeterminateCases < 0
                    || assertionResults < 0
                    || blockerFailures < 0
                    || blockerIndeterminate < 0
                    || warningFailures < 0
                    || warningIndeterminate < 0
                    || passedCases + failedCases
                    + indeterminateCases != totalCases
                    || blockerFailures + blockerIndeterminate
                    + warningFailures + warningIndeterminate
                    > assertionResults) {
                throw new IllegalArgumentException(
                        "Scenario remediation correctness counters are inconsistent");
            }
        }

        private static CorrectnessSummary from(
                List<EntrySnapshot> entries) {
            int evidenceBacked = 0;
            int total = 0;
            int passed = 0;
            int failed = 0;
            int indeterminate = 0;
            int assertions = 0;
            int blockerFailures = 0;
            int blockerIndeterminate = 0;
            int warningFailures = 0;
            int warningIndeterminate = 0;
            for (EntrySnapshot entry : entries) {
                ScenarioRehearsalResult.Summary summary =
                        entry.summary();
                if (summary == null) {
                    continue;
                }
                evidenceBacked++;
                total += summary.totalCases();
                passed += summary.passedCases();
                failed += summary.failedCases();
                indeterminate +=
                        summary.indeterminateCases();
                assertions += summary.assertionResults();
                blockerFailures += summary.blockerFailures();
                blockerIndeterminate +=
                        summary.blockerIndeterminate();
                warningFailures += summary.warningFailures();
                warningIndeterminate +=
                        summary.warningIndeterminate();
            }
            return new CorrectnessSummary(
                    evidenceBacked,
                    total,
                    passed,
                    failed,
                    indeterminate,
                    assertions,
                    blockerFailures,
                    blockerIndeterminate,
                    warningFailures,
                    warningIndeterminate);
        }
    }

    /**
     * Bounded signed-source projection for one manifest entry.
     *
     * @param compiledPlanRef exact compiled plan
     * @param status terminal item status
     * @param failureCode stable infrastructure or execution failure
     * @param runId exact child run, blank without evidence
     * @param childEvidenceBundleFingerprint child evidence address, blank without evidence
     * @param childWorkbookSeedFingerprint child workbook address, blank without evidence
     * @param scenarioPackRef exact Scenario pack, or {@code null} without child evidence
     * @param targetCapabilityRef exact target capability, or {@code null} without child evidence
     * @param outcome child business outcome, or {@code null} without child evidence
     * @param summary child case/assertion counters, or {@code null} without child evidence
     * @param gateReady whether this entry is publishable
     * @param blockers exact source-derived entry blockers
     */
    public record EntrySnapshot(
            MirrorArtifactRef compiledPlanRef,
            ScenarioRehearsalBatchItemPage.Status status,
            String failureCode,
            String runId,
            String childEvidenceBundleFingerprint,
            String childWorkbookSeedFingerprint,
            MirrorArtifactRef scenarioPackRef,
            MirrorArtifactRef targetCapabilityRef,
            ScenarioCaseRehearsalResult.Outcome outcome,
            ScenarioRehearsalResult.Summary summary,
            boolean gateReady,
            List<String> blockers
    ) {
        /** Enforces evidence, child projection, and gate correspondence. */
        public EntrySnapshot {
            compiledPlanRef = requireKind(
                    compiledPlanRef,
                    "COMPILED_REHEARSAL_PLAN",
                    "compiledPlanRef");
            status = Objects.requireNonNull(
                    status, "status");
            if (!status.terminal()) {
                throw new IllegalArgumentException(
                        "Scenario remediation entry must be terminal");
            }
            failureCode = optionalMachineCode(
                    failureCode, "failureCode");
            runId = runId == null ? "" : runId.trim();
            childEvidenceBundleFingerprint =
                    optionalFingerprint(
                            childEvidenceBundleFingerprint,
                            "childEvidenceBundleFingerprint");
            childWorkbookSeedFingerprint =
                    optionalFingerprint(
                            childWorkbookSeedFingerprint,
                            "childWorkbookSeedFingerprint");
            boolean evidenceBacked = !runId.isBlank();
            if (evidenceBacked
                    != (!childEvidenceBundleFingerprint
                    .isBlank()
                    && !childWorkbookSeedFingerprint
                    .isBlank()
                    && scenarioPackRef != null
                    && targetCapabilityRef != null
                    && outcome != null
                    && summary != null)) {
                throw new IllegalArgumentException(
                        "Scenario remediation entry child evidence is incomplete");
            }
            if (evidenceBacked) {
                if (!ScenarioRehearsalRunIdentity
                        .hasCanonicalShape(runId)) {
                    throw new IllegalArgumentException(
                            "Scenario remediation child runId is invalid");
                }
                scenarioPackRef = requireKind(
                        scenarioPackRef,
                        "SCENARIO_PACK",
                        "scenarioPackRef");
                targetCapabilityRef = requireKind(
                        targetCapabilityRef,
                        "CAPABILITY",
                        "targetCapabilityRef");
            }
            blockers = machineCodes(
                    blockers, "entryBlocker");
            if (gateReady != blockers.isEmpty()
                    || gateReady
                    && status
                    != ScenarioRehearsalBatchItemPage.Status
                    .PASSED) {
                throw new IllegalArgumentException(
                        "Scenario remediation entry gate decision is inconsistent");
            }
        }

        /** @return whether the signed batch anchors one child workbook */
        public boolean evidenceBacked() {
            return !runId.isBlank();
        }

        private static EntrySnapshot from(
                ScenarioRehearsalBatchWorkbookSeed
                        .EntryResult entry) {
            ScenarioRehearsalBatchWorkbookSeed.ChildWorkbook
                    child = entry.childWorkbook();
            List<String> blockers =
                    entryBlockers(entry);
            return new EntrySnapshot(
                    entry.compiledPlanRef(),
                    entry.status(),
                    entry.failureCode(),
                    entry.runId(),
                    entry.childEvidenceBundleFingerprint(),
                    entry.childWorkbookSeedFingerprint(),
                    child == null
                            ? null : child.scenarioPackRef(),
                    child == null
                            ? null : child.targetCapabilityRef(),
                    child == null ? null : child.outcome(),
                    child == null ? null : child.summary(),
                    blockers.isEmpty(),
                    blockers);
        }
    }

    /**
     * Exact before/after projection and blocker set difference for one ordered entry.
     *
     * @param entryIndex immutable manifest index
     * @param entryId caller-stable entry identity
     * @param planChanged whether the compiled plan reference changed
     * @param gateTransition source-derived entry gate transition
     * @param resolvedBlockers predecessor entry blockers absent from the successor
     * @param remainingBlockers blockers retained by both entry projections
     * @param introducedBlockers successor entry blockers absent from the predecessor
     * @param predecessor predecessor entry projection
     * @param successor successor entry projection
     */
    public record EntryComparison(
            int entryIndex,
            String entryId,
            boolean planChanged,
            GateTransition gateTransition,
            List<String> resolvedBlockers,
            List<String> remainingBlockers,
            List<String> introducedBlockers,
            EntrySnapshot predecessor,
            EntrySnapshot successor
    ) {
        /** Enforces exact entry identity, transition, and blocker differences. */
        public EntryComparison {
            if (entryIndex < 0
                    || entryIndex
                    >= ScenarioRehearsalBatchRequest
                    .MAXIMUM_ENTRIES
                    || entryId == null
                    || entryId.isBlank()) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison entry identity is invalid");
            }
            predecessor = Objects.requireNonNull(
                    predecessor, "predecessor");
            successor = Objects.requireNonNull(
                    successor, "successor");
            if (planChanged
                    != !predecessor.compiledPlanRef().equals(
                    successor.compiledPlanRef())) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison plan change is not source-derived");
            }
            gateTransition = Objects.requireNonNull(
                    gateTransition, "gateTransition");
            if (gateTransition != GateTransition.from(
                    predecessor.gateReady(),
                    successor.gateReady())) {
                throw new IllegalArgumentException(
                        "Scenario remediation entry gate transition is not source-derived");
            }
            resolvedBlockers = machineCodes(
                    resolvedBlockers, "resolvedEntryBlocker");
            remainingBlockers = machineCodes(
                    remainingBlockers, "remainingEntryBlocker");
            introducedBlockers = machineCodes(
                    introducedBlockers,
                    "introducedEntryBlocker");
            BlockerDiff diff = BlockerDiff.between(
                    predecessor.blockers(),
                    successor.blockers());
            if (!resolvedBlockers.equals(diff.resolved())
                    || !remainingBlockers.equals(
                    diff.remaining())
                    || !introducedBlockers.equals(
                    diff.introduced())) {
                throw new IllegalArgumentException(
                        "Scenario remediation entry blockers are not source-derived");
            }
        }

        private static EntryComparison from(
                ScenarioRehearsalBatchWorkbookSeed
                        .EntryResult predecessor,
                ScenarioRehearsalBatchWorkbookSeed
                        .EntryResult successor) {
            if (predecessor.entryIndex()
                    != successor.entryIndex()
                    || !predecessor.entryId().equals(
                    successor.entryId())) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison entry identity drifted");
            }
            EntrySnapshot before =
                    EntrySnapshot.from(predecessor);
            EntrySnapshot after =
                    EntrySnapshot.from(successor);
            BlockerDiff diff = BlockerDiff.between(
                    before.blockers(), after.blockers());
            return new EntryComparison(
                    predecessor.entryIndex(),
                    predecessor.entryId(),
                    !before.compiledPlanRef().equals(
                            after.compiledPlanRef()),
                    GateTransition.from(
                            before.gateReady(),
                            after.gateReady()),
                    diff.resolved(),
                    diff.remaining(),
                    diff.introduced(),
                    before,
                    after);
        }
    }

    /**
     * Projects one submitted lineage and two independently verified signed workbooks.
     *
     * @param mapper canonical protocol mapper
     * @param lineage independently verified submitted remediation lineage
     * @param predecessor independently verified blocked predecessor workbook
     * @param successor independently verified terminal successor workbook
     * @return deterministic content-addressed comparison
     */
    public static ScenarioRehearsalRemediationComparison project(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationLineage lineage,
            ScenarioRehearsalBatchWorkbookSeed predecessor,
            ScenarioRehearsalBatchWorkbookSeed successor) {
        ObjectMapper exactMapper =
                Objects.requireNonNull(mapper, "mapper");
        ScenarioRehearsalRemediationLineage exactLineage =
                Objects.requireNonNull(lineage, "lineage");
        ScenarioRehearsalBatchWorkbookSeed before =
                Objects.requireNonNull(
                        predecessor, "predecessor");
        ScenarioRehearsalBatchWorkbookSeed after =
                Objects.requireNonNull(
                        successor, "successor");
        exactLineage.verify(exactMapper);
        before.verify(exactMapper);
        after.verify(exactMapper);
        ScenarioRehearsalRemediationPlan plan =
                exactLineage.plan();
        ScenarioRehearsalRemediationReceipt receipt =
                exactLineage.receipt();
        if (exactLineage.state()
                != ScenarioRehearsalRemediationRepository.State
                .SUBMITTED
                || receipt == null
                || !before.scope().equals(plan.scope())
                || !after.scope().equals(plan.scope())
                || !before.jobId().equals(
                plan.predecessorJobId())
                || !before.seedFingerprint().equals(
                plan.predecessorWorkbookSeedFingerprint())
                || !before.evidenceBundleFingerprint().equals(
                plan.predecessorEvidenceBundleFingerprint())
                || before.status() != plan.predecessorStatus()
                || !before.blockers().equals(
                plan.predecessorBlockers())
                || !after.jobId().equals(
                receipt.successorJobId())
                || !after.requestFingerprint().equals(
                plan.successorRequestFingerprint())
                || !receipt.successorRequestFingerprint()
                .equals(plan.successorRequestFingerprint())
                || before.entries().size()
                != after.entries().size()
                || after.entries().size()
                != plan.successorRequest().entries().size()) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison sources do not close over the lineage");
        }
        requirePlanClosure(plan, before, after);
        List<EntryComparison> entries =
                new ArrayList<>(before.entries().size());
        for (int index = 0;
             index < before.entries().size();
             index++) {
            entries.add(EntryComparison.from(
                    before.entries().get(index),
                    after.entries().get(index)));
        }
        WorkbookSnapshot beforeSnapshot =
                workbookSnapshot(
                        exactMapper, before, entries.stream()
                                .map(EntryComparison::predecessor)
                                .toList());
        WorkbookSnapshot afterSnapshot =
                workbookSnapshot(
                        exactMapper, after, entries.stream()
                                .map(EntryComparison::successor)
                                .toList());
        BlockerDiff diff = BlockerDiff.between(
                before.blockers(), after.blockers());
        ScenarioRehearsalRemediationComparison material =
                new ScenarioRehearsalRemediationComparison(
                        SCHEMA_VERSION,
                        "",
                        plan.scope(),
                        plan.remediationId(),
                        exactLineage.lineageFingerprint(),
                        plan.planFingerprint(),
                        receipt.receiptFingerprint(),
                        beforeSnapshot,
                        afterSnapshot,
                        GateTransition.from(
                                before.gateReady(),
                                after.gateReady()),
                        diff.resolved(),
                        diff.remaining(),
                        diff.introduced(),
                        entries);
        ScenarioRehearsalRemediationComparison sealed =
                material.withFingerprint(
                        ProtocolFingerprint.ofBounded(
                                exactMapper,
                                material,
                                MAXIMUM_CANONICAL_BYTES));
        sealed.verify(exactMapper);
        return sealed;
    }

    /**
     * Recomputes both signed-workbook commitment materials and the complete comparison address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        ObjectMapper exactMapper =
                Objects.requireNonNull(mapper, "mapper");
        requireWorkbookSeal(exactMapper, predecessor);
        requireWorkbookSeal(exactMapper, successor);
        if (comparisonFingerprint.isBlank()
                || !ProtocolFingerprint.ofBounded(
                exactMapper,
                withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES)
                .equals(comparisonFingerprint)) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison fingerprint mismatch");
        }
    }

    private static void requirePlanClosure(
            ScenarioRehearsalRemediationPlan plan,
            ScenarioRehearsalBatchWorkbookSeed before,
            ScenarioRehearsalBatchWorkbookSeed after) {
        Map<Integer, ScenarioRehearsalRemediationPreviewRequest
                .PlanReplacement> replacements =
                new HashMap<>();
        for (ScenarioRehearsalRemediationPreviewRequest
                .PlanReplacement replacement
                : plan.replacements()) {
            replacements.put(
                    replacement.entryIndex(),
                    replacement);
        }
        for (int index = 0;
             index < after.entries().size();
             index++) {
            ScenarioRehearsalBatchWorkbookSeed.EntryResult
                    predecessor = before.entries().get(index);
            ScenarioRehearsalBatchWorkbookSeed.EntryResult
                    successor = after.entries().get(index);
            ScenarioRehearsalBatchRequest.Entry frozen =
                    plan.successorRequest().entries()
                            .get(index);
            ScenarioRehearsalRemediationPreviewRequest
                    .PlanReplacement replacement =
                    replacements.get(index);
            boolean invalid = predecessor.entryIndex()
                    != index
                    || successor.entryIndex() != index
                    || !predecessor.entryId().equals(
                    successor.entryId())
                    || !successor.entryId().equals(
                    frozen.entryId())
                    || !successor.compiledPlanRef().equals(
                    frozen.compiledPlanRef());
            if (replacement == null) {
                invalid |= !predecessor.compiledPlanRef()
                        .equals(successor.compiledPlanRef());
            } else {
                invalid |= !replacement.entryId().equals(
                        successor.entryId())
                        || !replacement
                        .expectedCompiledPlanRef()
                        .equals(
                                predecessor
                                        .compiledPlanRef())
                        || !replacement
                        .replacementCompiledPlanRef()
                        .equals(
                                successor
                                        .compiledPlanRef());
            }
            if (invalid) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison differs from the frozen plan");
            }
        }
    }

    private static WorkbookSnapshot workbookSnapshot(
            ObjectMapper mapper,
            ScenarioRehearsalBatchWorkbookSeed source,
            List<EntrySnapshot> entries) {
        if (!source.workbookSeal().signed()
                || !source.workbookSeal()
                .materialFingerprint().equals(
                        source.attestationMaterialFingerprint(
                                mapper))) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison source workbook seal is invalid");
        }
        return new WorkbookSnapshot(
                source.schemaVersion(),
                source.scope(),
                source.jobId(),
                source.seedFingerprint(),
                source.requestFingerprint(),
                source.manifestFingerprint(),
                source.evidenceBundleFingerprint(),
                source.evidenceIndexFingerprint(),
                source.workbookSeal(),
                source.status(),
                source.summary(),
                CorrectnessSummary.from(entries),
                source.gateReady(),
                source.blockers());
    }

    private static void requireWorkbookSeal(
            ObjectMapper mapper,
            WorkbookSnapshot source) {
        String expected =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        new WorkbookAttestationMaterial(
                                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_WORKBOOK_V1",
                                source.workbookSchemaVersion(),
                                source.jobId(),
                                source.seedFingerprint(),
                                source.evidenceBundleFingerprint(),
                                source.evidenceIndexFingerprint()),
                        ScenarioRehearsalBatchWorkbookSeed
                                .MAXIMUM_ATTESTATION_BYTES);
        if (!expected.equals(
                source.workbookSeal()
                        .materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison workbook commitment is invalid");
        }
    }

    private static void requireWorkbookProjection(
            WorkbookSnapshot source,
            List<EntrySnapshot> entries) {
        if (!summary(entries).equals(source.summary())
                || !CorrectnessSummary.from(entries).equals(
                source.correctnessSummary())
                || !rootBlockers(
                source.status(), entries).equals(
                source.blockers())) {
            throw new IllegalArgumentException(
                    "Scenario remediation comparison workbook projection is inconsistent");
        }
    }

    private static ScenarioRehearsalBatchJob.Summary summary(
            List<EntrySnapshot> entries) {
        int passed = 0;
        int failed = 0;
        int indeterminate = 0;
        int cancelled = 0;
        for (EntrySnapshot entry : entries) {
            switch (entry.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case INDETERMINATE -> indeterminate++;
                case CANCELLED -> cancelled++;
                case PENDING, RUNNING ->
                        throw new IllegalArgumentException(
                                "Scenario remediation comparison entry is not terminal");
            }
        }
        return new ScenarioRehearsalBatchJob.Summary(
                entries.size(),
                entries.size(),
                passed,
                failed,
                indeterminate,
                cancelled);
    }

    private static List<String> rootBlockers(
            ScenarioRehearsalBatchJob.Status status,
            List<EntrySnapshot> entries) {
        TreeSet<String> blockers = new TreeSet<>();
        if (status
                != ScenarioRehearsalBatchJob.Status.SUCCEEDED) {
            blockers.add("BATCH_STATUS_" + status.name());
        }
        for (EntrySnapshot entry : entries) {
            switch (entry.status()) {
                case FAILED ->
                        blockers.add("BATCH_ITEM_FAILED");
                case INDETERMINATE ->
                        blockers.add(
                                "BATCH_ITEM_INDETERMINATE");
                case CANCELLED ->
                        blockers.add(
                                "BATCH_ITEM_CANCELLED");
                case PASSED -> {
                }
                case PENDING, RUNNING ->
                        throw new IllegalArgumentException(
                                "Scenario remediation comparison entry is not terminal");
            }
            if (!entry.evidenceBacked()
                    && entry.status()
                    != ScenarioRehearsalBatchItemPage.Status
                    .CANCELLED) {
                blockers.add("CHILD_EVIDENCE_MISSING");
            } else if (entry.evidenceBacked()
                    && !entry.gateReady()) {
                blockers.add("CHILD_WORKBOOK_BLOCKED");
            }
        }
        return List.copyOf(blockers);
    }

    private static List<String> entryBlockers(
            ScenarioRehearsalBatchWorkbookSeed
                    .EntryResult entry) {
        TreeSet<String> blockers = new TreeSet<>();
        if (entry.status()
                != ScenarioRehearsalBatchItemPage.Status
                .PASSED) {
            blockers.add(
                    "ENTRY_STATUS_"
                            + entry.status().name());
        }
        if (!entry.failureCode().isBlank()) {
            blockers.add(entry.failureCode());
        }
        if (!entry.evidenceBacked()
                && entry.status()
                != ScenarioRehearsalBatchItemPage.Status
                .CANCELLED) {
            blockers.add("CHILD_EVIDENCE_MISSING");
        } else if (entry.evidenceBacked()) {
            blockers.addAll(
                    entry.childWorkbook().blockers());
        }
        return List.copyOf(blockers);
    }

    private ScenarioRehearsalRemediationComparison
    withFingerprint(String value) {
        return new ScenarioRehearsalRemediationComparison(
                schemaVersion,
                value,
                scope,
                remediationId,
                lineageFingerprint,
                remediationPlanFingerprint,
                receiptFingerprint,
                predecessor,
                successor,
                gateTransition,
                resolvedBlockers,
                remainingBlockers,
                introducedBlockers,
                entries);
    }

    /** Keeps source coordinates and signatures out of generic logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalRemediationComparison[transition="
                + gateTransition + ", entries="
                + entries.size() + "]";
    }

    private record BlockerDiff(
            List<String> resolved,
            List<String> remaining,
            List<String> introduced
    ) {
        private static BlockerDiff between(
                List<String> predecessor,
                List<String> successor) {
            TreeSet<String> before =
                    new TreeSet<>(predecessor);
            TreeSet<String> after =
                    new TreeSet<>(successor);
            TreeSet<String> resolved =
                    new TreeSet<>(before);
            resolved.removeAll(after);
            TreeSet<String> remaining =
                    new TreeSet<>(before);
            remaining.retainAll(after);
            TreeSet<String> introduced =
                    new TreeSet<>(after);
            introduced.removeAll(before);
            return new BlockerDiff(
                    List.copyOf(resolved),
                    List.copyOf(remaining),
                    List.copyOf(introduced));
        }
    }

    private record WorkbookAttestationMaterial(
            String domain,
            String schemaVersion,
            String jobId,
            String seedFingerprint,
            String evidenceBundleFingerprint,
            String evidenceIndexFingerprint
    ) {
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }

    private static List<String> machineCodes(
            List<String> values,
            String field) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String exact =
                        value == null ? "" : value.trim();
                if (!MACHINE_CODE.matcher(exact)
                        .matches()
                        || !result.add(exact)) {
                    throw new IllegalArgumentException(
                            field + " values must be unique machine codes");
                }
            }
        }
        if (result.size()
                > ScenarioRehearsalBatchWorkbookSeed
                .MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    field + " values exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static String version(String value) {
        String exact =
                value == null || value.isBlank()
                        ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation comparison schemaVersion");
        }
        return exact;
    }

    private static String remediationId(
            String value) {
        String exact = value == null ? "" : value.trim();
        if (!REMEDIATION_ID.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "remediationId is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalMachineCode(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()
                && !MACHINE_CODE.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
