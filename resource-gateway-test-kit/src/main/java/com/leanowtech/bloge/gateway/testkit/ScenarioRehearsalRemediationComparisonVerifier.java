package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Dependency-light offline verifier for a reviewed Scenario remediation comparison.
 *
 * <p>The comparison is not a new quality score. This verifier reconstructs it from the submitted
 * decision lineage and the complete predecessor and successor workbook seeds. It independently
 * derives every root and entry blocker, gate transition, correctness counter, plan replacement,
 * and content address before accepting the projection.</p>
 *
 * <p>The two workbook arguments must first pass
 * {@link ScenarioRehearsalBatchWorkbookVerifier}; that verifier opens their signed evidence,
 * retention, and workbook-seal closure. {@link ResourceGatewayTestClient} enforces that ordering.
 * Keeping signature verification at the source boundary prevents this projection verifier from
 * treating a copied signature object as proof by itself.</p>
 */
public final class ScenarioRehearsalRemediationComparisonVerifier {
    /** Maximum canonical comparison bytes admitted to content-address verification. */
    public static final int MAXIMUM_COMPARISON_BYTES = 20 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates a verifier with fixed v1 comparison semantics. */
    public ScenarioRehearsalRemediationComparisonVerifier() {
    }

    /** Bounded comparison verification outcome. */
    public enum Outcome {
        /** All source, plan, projection, transition, and fingerprint checks passed. */
        VERIFIED,
        /** At least one schema, source closure, projection, or fingerprint check failed. */
        INVALID
    }

    /**
     * Payload-free verification result suitable for CI and governance ingestion.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param remediationId exact remediation identity, or blank when unavailable
     * @param comparisonFingerprint exact accepted comparison address, or blank when unavailable
     * @param gateTransition verified root transition, or blank when unavailable
     * @param entryCount number of verified ordered entries
     * @param resolvedBlockers independently derived resolved root blockers
     * @param remainingBlockers independently derived remaining root blockers
     * @param introducedBlockers independently derived introduced root blockers
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String remediationId,
            String comparisonFingerprint,
            String gateTransition,
            int entryCount,
            List<String> resolvedBlockers,
            List<String> remainingBlockers,
            List<String> introducedBlockers
    ) {
        /** Validates one bounded, log-safe result. */
        public VerificationResult {
            remediationId = normalized(remediationId);
            comparisonFingerprint = normalized(comparisonFingerprint);
            gateTransition = normalized(gateTransition);
            resolvedBlockers = copy(resolvedBlockers);
            remainingBlockers = copy(remainingBlockers);
            introducedBlockers = copy(introducedBlockers);
            if (outcome == null
                    || reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    || entryCount < 0
                    || entryCount > 256
                    || resolvedBlockers.size() > 16
                    || remainingBlockers.size() > 16
                    || introducedBlockers.size() > 16) {
                throw new IllegalArgumentException(
                        "Scenario remediation comparison verification result is invalid");
            }
        }

        /**
         * Reports whether the complete comparison source closure passed verification.
         *
         * @return true only for a fully reconstructed comparison
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    /**
     * Reconstructs one comparison from already verified source workbooks and decision lineage.
     *
     * @param comparison decoded v1 comparison payload
     * @param lineage decoded submitted remediation lineage
     * @param predecessor independently signature-verified predecessor batch workbook
     * @param successor independently signature-verified successor batch workbook
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode comparison,
            JsonNode lineage,
            JsonNode predecessor,
            JsonNode successor) {
        Coordinates coordinates = Coordinates.from(comparison);
        try {
            requireSchemas(
                    comparison, lineage,
                    predecessor, successor);
        } catch (RuntimeException invalid) {
            return invalid(
                    "SCENARIO_REMEDIATION_COMPARISON_SCHEMA_INVALID",
                    coordinates);
        }
        ScenarioRehearsalRemediationVerifier.VerificationResult
                lineageVerification =
                new ScenarioRehearsalRemediationVerifier()
                        .verify(lineage);
        if (!lineageVerification.verified()) {
            return invalid(
                    "SCENARIO_REMEDIATION_COMPARISON_LINEAGE_"
                            + lineageVerification.reasonCode(),
                    coordinates);
        }
        try {
            verifySelfFingerprint(comparison);
            ObjectNode expected = project(
                    lineage, predecessor, successor);
            expected.put(
                    "comparisonFingerprint",
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    expected,
                                    MAXIMUM_COMPARISON_BYTES));
            if (!expected.equals(comparison)) {
                fail(projectionFailure(
                        expected, comparison));
            }
            return new VerificationResult(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    text(expected, "remediationId"),
                    text(expected, "comparisonFingerprint"),
                    text(expected, "gateTransition"),
                    expected.path("entries").size(),
                    arrayText(expected.path("resolvedBlockers")),
                    arrayText(expected.path("remainingBlockers")),
                    arrayText(expected.path("introducedBlockers")));
        } catch (VerificationFailure failure) {
            return invalid(failure.reasonCode, coordinates);
        } catch (RuntimeException invalid) {
            return invalid(
                    "SCENARIO_REMEDIATION_COMPARISON_CLOSURE_INVALID",
                    coordinates);
        }
    }

    private static ObjectNode project(
            JsonNode lineage,
            JsonNode predecessor,
            JsonNode successor) {
        JsonNode plan = lineage.path("plan");
        JsonNode receipt = lineage.path("receipt");
        requireLineageClosure(
                lineage, plan, receipt,
                predecessor, successor);

        SourceProjection before =
                sourceProjection(predecessor);
        SourceProjection after =
                sourceProjection(successor);
        requirePlanClosure(
                plan, predecessor, successor);
        if (before.entries().size()
                != after.entries().size()) {
            fail("SCENARIO_REMEDIATION_COMPARISON_ENTRY_COUNT_DRIFT");
        }

        ArrayNode entries = JSON.createArrayNode();
        for (int index = 0;
             index < before.entries().size();
             index++) {
            EntryProjection predecessorEntry =
                    before.entries().get(index);
            EntryProjection successorEntry =
                    after.entries().get(index);
            JsonNode beforeSource =
                    predecessor.path("entries").get(index);
            JsonNode afterSource =
                    successor.path("entries").get(index);
            if (beforeSource.path("entryIndex").asInt(-1)
                    != index
                    || afterSource.path("entryIndex").asInt(-1)
                    != index
                    || !beforeSource.path("entryId")
                    .equals(afterSource.path("entryId"))) {
                fail("SCENARIO_REMEDIATION_COMPARISON_ENTRY_IDENTITY_DRIFT");
            }
            entries.add(entryComparison(
                    index,
                    text(beforeSource, "entryId"),
                    predecessorEntry,
                    successorEntry));
        }

        BlockerDiff diff = BlockerDiff.between(
                before.blockers(), after.blockers());
        ObjectNode expected = JSON.createObjectNode();
        expected.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_COMPARISON_V1);
        expected.put("comparisonFingerprint", "");
        expected.set("scope", plan.path("scope").deepCopy());
        expected.put(
                "remediationId",
                text(plan, "remediationId"));
        expected.put(
                "lineageFingerprint",
                text(lineage, "lineageFingerprint"));
        expected.put(
                "remediationPlanFingerprint",
                text(plan, "planFingerprint"));
        expected.put(
                "receiptFingerprint",
                text(receipt, "receiptFingerprint"));
        expected.set("predecessor", before.snapshot());
        expected.set("successor", after.snapshot());
        expected.put(
                "gateTransition",
                transition(false, after.gateReady()));
        expected.set(
                "resolvedBlockers",
                array(diff.resolved()));
        expected.set(
                "remainingBlockers",
                array(diff.remaining()));
        expected.set(
                "introducedBlockers",
                array(diff.introduced()));
        expected.set("entries", entries);
        return expected;
    }

    private static void requireLineageClosure(
            JsonNode lineage,
            JsonNode plan,
            JsonNode receipt,
            JsonNode predecessor,
            JsonNode successor) {
        if (!"SUBMITTED".equals(
                text(lineage, "state"))
                || receipt.isMissingNode()
                || receipt.isNull()
                || !plan.path("scope")
                .equals(predecessor.path("scope"))
                || !plan.path("scope")
                .equals(successor.path("scope"))
                || !plan.path("remediationId")
                .equals(receipt.path("remediationId"))
                || !plan.path("planFingerprint")
                .equals(receipt.path(
                        "remediationPlanFingerprint"))
                || !plan.path("predecessorJobId")
                .equals(predecessor.path("jobId"))
                || !plan.path(
                        "predecessorWorkbookSeedFingerprint")
                .equals(predecessor.path(
                        "seedFingerprint"))
                || !plan.path(
                        "predecessorEvidenceBundleFingerprint")
                .equals(predecessor.path(
                        "evidenceBundleFingerprint"))
                || !plan.path("predecessorStatus")
                .equals(predecessor.path("status"))
                || !arrayText(
                plan.path("predecessorBlockers"))
                .equals(arrayText(
                        predecessor.path("blockers")))
                || !receipt.path("predecessorJobId")
                .equals(predecessor.path("jobId"))
                || !receipt.path("successorJobId")
                .equals(successor.path("jobId"))
                || !plan.path(
                        "successorRequestFingerprint")
                .equals(receipt.path(
                        "successorRequestFingerprint"))
                || !plan.path(
                        "successorRequestFingerprint")
                .equals(successor.path(
                        "requestFingerprint"))) {
            fail("SCENARIO_REMEDIATION_COMPARISON_LINEAGE_CLOSURE_INVALID");
        }
    }

    private static void requirePlanClosure(
            JsonNode plan,
            JsonNode predecessor,
            JsonNode successor) {
        JsonNode frozenEntries =
                plan.path("successorRequest")
                        .path("entries");
        JsonNode beforeEntries =
                predecessor.path("entries");
        JsonNode afterEntries =
                successor.path("entries");
        if (beforeEntries.size() != afterEntries.size()
                || afterEntries.size()
                != frozenEntries.size()) {
            fail("SCENARIO_REMEDIATION_COMPARISON_PLAN_COUNT_INVALID");
        }
        Map<Integer, JsonNode> replacements =
                new HashMap<>();
        for (JsonNode replacement
                : plan.path("replacements")) {
            replacements.put(
                    replacement.path("entryIndex")
                            .asInt(-1),
                    replacement);
        }
        for (int index = 0;
             index < afterEntries.size();
             index++) {
            JsonNode before =
                    beforeEntries.get(index);
            JsonNode after =
                    afterEntries.get(index);
            JsonNode frozen =
                    frozenEntries.get(index);
            JsonNode replacement =
                    replacements.get(index);
            boolean invalid =
                    before.path("entryIndex")
                            .asInt(-1) != index
                            || after.path("entryIndex")
                            .asInt(-1) != index
                            || !before.path("entryId")
                            .equals(after.path("entryId"))
                            || !after.path("entryId")
                            .equals(frozen.path("entryId"))
                            || !after.path("compiledPlanRef")
                            .equals(frozen.path(
                                    "compiledPlanRef"));
            if (replacement == null) {
                invalid |= !before.path(
                                "compiledPlanRef")
                        .equals(after.path(
                                "compiledPlanRef"));
            } else {
                invalid |= !replacement.path("entryId")
                        .equals(after.path("entryId"))
                        || !replacement.path(
                                "expectedCompiledPlanRef")
                        .equals(before.path(
                                "compiledPlanRef"))
                        || !replacement.path(
                                "replacementCompiledPlanRef")
                        .equals(after.path(
                                "compiledPlanRef"));
            }
            if (invalid) {
                fail("SCENARIO_REMEDIATION_COMPARISON_PLAN_CLOSURE_INVALID");
            }
        }
    }

    private static SourceProjection sourceProjection(
            JsonNode source) {
        List<EntryProjection> entries =
                stream(source.path("entries"))
                        .map(ScenarioRehearsalRemediationComparisonVerifier
                                ::entryProjection)
                        .toList();
        ObjectNode expectedSummary =
                batchSummary(entries);
        List<String> blockers =
                rootBlockers(
                        text(source, "status"),
                        entries);
        boolean gateReady = blockers.isEmpty();
        if (!expectedSummary.equals(
                source.path("summary"))
                || !blockers.equals(arrayText(
                source.path("blockers")))
                || gateReady
                != source.path("gateReady")
                .asBoolean()) {
            fail("SCENARIO_REMEDIATION_COMPARISON_SOURCE_PROJECTION_INVALID");
        }
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put(
                "workbookSchemaVersion",
                text(source, "schemaVersion"));
        snapshot.set(
                "scope",
                source.path("scope").deepCopy());
        snapshot.put("jobId", text(source, "jobId"));
        snapshot.put(
                "seedFingerprint",
                text(source, "seedFingerprint"));
        snapshot.put(
                "requestFingerprint",
                text(source, "requestFingerprint"));
        snapshot.put(
                "manifestFingerprint",
                text(source, "manifestFingerprint"));
        snapshot.put(
                "evidenceBundleFingerprint",
                text(source, "evidenceBundleFingerprint"));
        snapshot.put(
                "evidenceIndexFingerprint",
                text(source, "evidenceIndexFingerprint"));
        snapshot.set(
                "workbookSeal",
                source.path("workbookSeal").deepCopy());
        snapshot.put("status", text(source, "status"));
        snapshot.set("summary", expectedSummary);
        snapshot.set(
                "correctnessSummary",
                correctnessSummary(entries));
        snapshot.put("gateReady", gateReady);
        snapshot.set("blockers", array(blockers));
        return new SourceProjection(
                snapshot, entries, gateReady, blockers);
    }

    private static EntryProjection entryProjection(
            JsonNode source) {
        String status = text(source, "status");
        String failureCode =
                text(source, "failureCode");
        String runId = text(source, "runId");
        JsonNode child = source.path("childWorkbook");
        boolean evidenceBacked = !runId.isBlank();
        if (evidenceBacked == (child.isNull()
                || child.isMissingNode())) {
            fail("SCENARIO_REMEDIATION_COMPARISON_CHILD_CLOSURE_INVALID");
        }
        if (evidenceBacked
                && (!source.path("compiledPlanRef")
                .equals(child.path("compiledPlanRef"))
                || !source.path("runId")
                .equals(child.path("runId"))
                || !source.path(
                "childEvidenceBundleFingerprint")
                .equals(child.path(
                        "evidenceBundleFingerprint"))
                || !source.path(
                "childWorkbookSeedFingerprint")
                .equals(child.path(
                        "seedFingerprint")))) {
            fail("SCENARIO_REMEDIATION_COMPARISON_CHILD_CLOSURE_INVALID");
        }
        TreeSet<String> blockers =
                new TreeSet<>();
        if (!"PASSED".equals(status)) {
            blockers.add("ENTRY_STATUS_" + status);
        }
        if (!failureCode.isBlank()) {
            blockers.add(failureCode);
        }
        if (!evidenceBacked
                && !"CANCELLED".equals(status)) {
            blockers.add("CHILD_EVIDENCE_MISSING");
        } else if (evidenceBacked) {
            blockers.addAll(arrayText(
                    child.path("blockers")));
        }
        boolean gateReady = blockers.isEmpty();
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.set(
                "compiledPlanRef",
                source.path("compiledPlanRef").deepCopy());
        snapshot.put("status", status);
        snapshot.put("failureCode", failureCode);
        snapshot.put("runId", runId);
        snapshot.put(
                "childEvidenceBundleFingerprint",
                text(source,
                        "childEvidenceBundleFingerprint"));
        snapshot.put(
                "childWorkbookSeedFingerprint",
                text(source,
                        "childWorkbookSeedFingerprint"));
        if (evidenceBacked) {
            snapshot.set(
                    "scenarioPackRef",
                    child.path("scenarioPackRef")
                            .deepCopy());
            snapshot.set(
                    "targetCapabilityRef",
                    child.path("targetCapabilityRef")
                            .deepCopy());
            snapshot.put(
                    "outcome",
                    text(child, "outcome"));
            snapshot.set(
                    "summary",
                    child.path("summary").deepCopy());
        } else {
            snapshot.putNull("scenarioPackRef");
            snapshot.putNull("targetCapabilityRef");
            snapshot.putNull("outcome");
            snapshot.putNull("summary");
        }
        snapshot.put("gateReady", gateReady);
        snapshot.set(
                "blockers",
                array(List.copyOf(blockers)));
        return new EntryProjection(
                snapshot,
                status,
                evidenceBacked,
                evidenceBacked
                        ? child.path("summary")
                        : null,
                gateReady,
                List.copyOf(blockers));
    }

    private static ObjectNode entryComparison(
            int index,
            String entryId,
            EntryProjection predecessor,
            EntryProjection successor) {
        BlockerDiff diff = BlockerDiff.between(
                predecessor.blockers(),
                successor.blockers());
        ObjectNode value = JSON.createObjectNode();
        value.put("entryIndex", index);
        value.put("entryId", entryId);
        value.put(
                "planChanged",
                !predecessor.snapshot()
                        .path("compiledPlanRef")
                        .equals(successor.snapshot()
                                .path("compiledPlanRef")));
        value.put(
                "gateTransition",
                transition(
                        predecessor.gateReady(),
                        successor.gateReady()));
        value.set(
                "resolvedBlockers",
                array(diff.resolved()));
        value.set(
                "remainingBlockers",
                array(diff.remaining()));
        value.set(
                "introducedBlockers",
                array(diff.introduced()));
        value.set(
                "predecessor",
                predecessor.snapshot());
        value.set(
                "successor",
                successor.snapshot());
        return value;
    }

    private static ObjectNode batchSummary(
            List<EntryProjection> entries) {
        int passed = 0;
        int failed = 0;
        int indeterminate = 0;
        int cancelled = 0;
        for (EntryProjection entry : entries) {
            switch (entry.status()) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "INDETERMINATE" -> indeterminate++;
                case "CANCELLED" -> cancelled++;
                default -> fail(
                        "SCENARIO_REMEDIATION_COMPARISON_ITEM_STATUS_INVALID");
            }
        }
        ObjectNode summary = JSON.createObjectNode();
        summary.put("totalItems", entries.size());
        summary.put("completedItems", entries.size());
        summary.put("passedItems", passed);
        summary.put("failedItems", failed);
        summary.put(
                "indeterminateItems",
                indeterminate);
        summary.put("cancelledItems", cancelled);
        return summary;
    }

    private static ObjectNode correctnessSummary(
            List<EntryProjection> entries) {
        String[] counters = {
                "totalCases",
                "passedCases",
                "failedCases",
                "indeterminateCases",
                "assertionResults",
                "blockerFailures",
                "blockerIndeterminate",
                "warningFailures",
                "warningIndeterminate"
        };
        int[] totals = new int[counters.length];
        int evidenceBacked = 0;
        for (EntryProjection entry : entries) {
            if (!entry.evidenceBacked()) {
                continue;
            }
            evidenceBacked++;
            for (int index = 0;
                 index < counters.length;
                 index++) {
                totals[index] +=
                        entry.summary()
                                .path(counters[index])
                                .asInt();
            }
        }
        ObjectNode summary = JSON.createObjectNode();
        summary.put(
                "evidenceBackedEntries",
                evidenceBacked);
        for (int index = 0;
             index < counters.length;
             index++) {
            summary.put(counters[index], totals[index]);
        }
        return summary;
    }

    private static List<String> rootBlockers(
            String status,
            List<EntryProjection> entries) {
        TreeSet<String> blockers = new TreeSet<>();
        if (!"SUCCEEDED".equals(status)) {
            blockers.add("BATCH_STATUS_" + status);
        }
        for (EntryProjection entry : entries) {
            switch (entry.status()) {
                case "FAILED" ->
                        blockers.add("BATCH_ITEM_FAILED");
                case "INDETERMINATE" ->
                        blockers.add(
                                "BATCH_ITEM_INDETERMINATE");
                case "CANCELLED" ->
                        blockers.add(
                                "BATCH_ITEM_CANCELLED");
                case "PASSED" -> {
                }
                default -> fail(
                        "SCENARIO_REMEDIATION_COMPARISON_ITEM_STATUS_INVALID");
            }
            if (!entry.evidenceBacked()
                    && !"CANCELLED".equals(
                    entry.status())) {
                blockers.add("CHILD_EVIDENCE_MISSING");
            } else if (entry.evidenceBacked()
                    && !entry.gateReady()) {
                blockers.add("CHILD_WORKBOOK_BLOCKED");
            }
        }
        return List.copyOf(blockers);
    }

    private static void verifySelfFingerprint(
            JsonNode comparison) {
        ObjectNode material =
                ((ObjectNode) comparison).deepCopy();
        String actual =
                text(material,
                        "comparisonFingerprint");
        material.put("comparisonFingerprint", "");
        String expected =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                material,
                                MAXIMUM_COMPARISON_BYTES);
        if (!expected.equals(actual)) {
            fail("SCENARIO_REMEDIATION_COMPARISON_FINGERPRINT_INVALID");
        }
    }

    private static void requireSchemas(
            JsonNode comparison,
            JsonNode lineage,
            JsonNode predecessor,
            JsonNode successor) {
        CapabilityMirrorSchemaValidator.require(
                comparison,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_COMPARISON_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_COMPARISON_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                lineage,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_LINEAGE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_LINEAGE_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                predecessor,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_PREDECESSOR_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                successor,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_SUCCESSOR_SCHEMA_INVALID");
    }

    private static String transition(
            boolean predecessor,
            boolean successor) {
        if (!predecessor && successor) {
            return "RESOLVED";
        }
        if (!predecessor) {
            return "STILL_BLOCKED";
        }
        return successor ? "STILL_READY" : "REGRESSED";
    }

    private static ArrayNode array(
            List<String> values) {
        ArrayNode result = JSON.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static List<String> arrayText(
            JsonNode value) {
        return stream(value)
                .map(JsonNode::asText)
                .toList();
    }

    private static java.util.stream.Stream<JsonNode>
    stream(JsonNode value) {
        return value == null || !value.isArray()
                ? java.util.stream.Stream.empty()
                : java.util.stream.StreamSupport.stream(
                        value.spliterator(), false);
    }

    private static String text(
            JsonNode value,
            String field) {
        return value == null
                ? "" : value.path(field)
                .asText("");
    }

    private static String normalized(
            String value) {
        return value == null ? "" : value.trim();
    }

    private static String projectionFailure(
            ObjectNode expected,
            JsonNode actual) {
        String field =
                firstDifference(
                        expected, actual, "");
        if (!field.isBlank()) {
            return "SCENARIO_REMEDIATION_COMPARISON_PROJECTION_"
                    + field.replaceAll(
                    "([a-z])([A-Z])", "$1_$2")
                    .replaceAll("[^A-Za-z0-9]+", "_")
                    .toUpperCase(
                            java.util.Locale.ROOT)
                    + "_INVALID";
        }
        return "SCENARIO_REMEDIATION_COMPARISON_PROJECTION_INVALID";
    }

    private static String firstDifference(
            JsonNode expected,
            JsonNode actual,
            String prefix) {
        if (expected.isObject()
                && actual.isObject()) {
            java.util.Iterator<String> fields =
                    expected.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String path = prefix.isBlank()
                        ? field : prefix + "_" + field;
                JsonNode expectedChild =
                        expected.path(field);
                JsonNode actualChild =
                        actual.path(field);
                if (!expectedChild.equals(
                        actualChild)) {
                    return firstDifference(
                            expectedChild,
                            actualChild,
                            path);
                }
            }
        }
        return prefix;
    }

    private static VerificationResult invalid(
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                Outcome.INVALID,
                reasonCode,
                coordinates.remediationId(),
                coordinates.comparisonFingerprint(),
                coordinates.gateTransition(),
                coordinates.entryCount(),
                List.of(),
                List.of(),
                List.of());
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private record Coordinates(
            String remediationId,
            String comparisonFingerprint,
            String gateTransition,
            int entryCount
    ) {
        private static Coordinates from(
                JsonNode comparison) {
            return new Coordinates(
                    text(comparison,
                            "remediationId"),
                    text(comparison,
                            "comparisonFingerprint"),
                    text(comparison,
                            "gateTransition"),
                    comparison == null
                            ? 0 : comparison.path(
                                    "entries").size());
        }
    }

    private record SourceProjection(
            ObjectNode snapshot,
            List<EntryProjection> entries,
            boolean gateReady,
            List<String> blockers
    ) {
    }

    private record EntryProjection(
            ObjectNode snapshot,
            String status,
            boolean evidenceBacked,
            JsonNode summary,
            boolean gateReady,
            List<String> blockers
    ) {
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

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
