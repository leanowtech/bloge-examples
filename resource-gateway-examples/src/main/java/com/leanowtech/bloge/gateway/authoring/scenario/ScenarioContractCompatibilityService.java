package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.ChangeKind;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.Classification;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.Finding;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.FindingClassification;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.ImpactStatus;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.MigrationAction;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.MigrationKind;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.ScenarioImpact;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.Scope;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Produces fail-closed Contract compatibility, Scenario impact, and migration reports.
 *
 * <p>The analyzer intentionally supports a bounded deterministic JSON Schema subset. Remaining
 * composition or conditional keywords become {@code REVIEW_REQUIRED}; they are never guessed to be
 * compatible. Local references and safely mergeable {@code allOf} fragments are already resolved
 * by {@link SchemaEnvelope} before this boundary.</p>
 */
public final class ScenarioContractCompatibilityService {

    private static final int MAX_REPORT_BYTES = 4 * 1_048_576;
    private static final Set<String> KNOWN_KEYWORDS = Set.of(
            "$schema", "$id", "$defs", "$comment",
            "title", "description", "examples", "default", "deprecated", "readOnly", "writeOnly",
            "type", "properties", "required", "additionalProperties",
            "enum", "const", "format",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "maxLength", "pattern",
            "minItems", "maxItems", "uniqueItems", "items",
            "minProperties", "maxProperties",
            "x-bloge-renamed-from");
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "const", "format", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems",
            "additionalProperties", "minProperties", "maxProperties");

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper canonical report fingerprint serializer
     */
    public ScenarioContractCompatibilityService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Compares the Contract captured with one Scenario revision to the current authoritative target.
     *
     * @param source exact retained Scenario revision
     * @param baseline immutable Contract snapshot, null for legacy revisions created before capture
     * @param current current authoritative Contract
     * @return deterministic compatibility and migration report
     */
    public ContractCompatibilityReport analyze(
            StoredScenarioDraftSet source,
            ScenarioContractBaseline baseline,
            ContractDraft current) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(current, "current");
        ScenarioDraftSet draftSet = source.draftSet();
        String currentFingerprint = current.fingerprint(objectMapper);
        String baselineFingerprint = baseline == null
                ? draftSet.contractFingerprint() : baseline.contractFingerprint();
        String policy = current.compatibilityPolicy().mode();
        List<PendingFinding> pending = new ArrayList<>();
        Map<FieldKey, SchemaField> candidateFields = new LinkedHashMap<>();

        if (baseline == null || baseline.contract() == null) {
            pending.add(opaque(Scope.CONTRACT, "",
                    "RG.CONTRACT.BASELINE_UNAVAILABLE",
                    "This retained Scenario revision predates Contract baseline capture."));
        } else {
            ContractDraft previous = baseline.contract();
            compareSchema(Scope.INPUT, previous.inputSchema(), current.inputSchema(), policy,
                    pending, candidateFields);
            compareSchema(Scope.OUTPUT, previous.outputSchema(), current.outputSchema(), policy,
                    pending, candidateFields);
            compareContractSemantics(previous, current, pending);
        }

        if (!draftSet.target().fingerprint().equals(current.target().fingerprint())) {
            pending.add(new PendingFinding(
                    Scope.CONTRACT,
                    "",
                    "",
                    ChangeKind.TARGET_CHANGED,
                    FindingClassification.REVIEW_REQUIRED,
                    "RG.CONTRACT.TARGET_CHANGED",
                    "The target implementation fingerprint changed and must be revalidated.",
                    Map.of(
                            "baselineTargetFingerprint", draftSet.target().fingerprint(),
                            "currentTargetFingerprint", current.target().fingerprint())));
        }

        pending.sort(Comparator
                .comparing((PendingFinding finding) -> finding.scope().name())
                .thenComparing(PendingFinding::path)
                .thenComparing(finding -> finding.change().name())
                .thenComparing(PendingFinding::code));
        List<Finding> findings = numberedFindings(pending);
        ImpactProjection projection = projectImpact(draftSet, findings, candidateFields);
        Classification classification = classification(findings, baselineFingerprint,
                currentFingerprint, draftSet.target(), current.target());
        String reportFingerprint = fingerprint(source, current, baselineFingerprint,
                currentFingerprint, policy, classification, findings,
                projection.impacts(), projection.migrations());
        return new ContractCompatibilityReport(
                "",
                draftSet.scenarioDraftSetId(),
                source.revision(),
                current.target(),
                baselineFingerprint,
                currentFingerprint,
                policy,
                classification,
                findings,
                projection.impacts(),
                projection.migrations(),
                Instant.now(),
                reportFingerprint);
    }

    private void compareSchema(
            Scope scope,
            SchemaEnvelope previous,
            SchemaEnvelope current,
            String policy,
            List<PendingFinding> findings,
            Map<FieldKey, SchemaField> candidateFields) {
        FlattenedSchema oldSchema = flatten(scope, previous);
        FlattenedSchema newSchema = flatten(scope, current);
        newSchema.fields().forEach((path, field) ->
                candidateFields.put(new FieldKey(scope, path), field));
        oldSchema.opaquePaths().forEach(path -> findings.add(opaque(
                scope, path, "RG.CONTRACT.SCHEMA_OPAQUE",
                "The baseline schema uses semantics outside the deterministic compatibility subset.")));
        newSchema.opaquePaths().forEach(path -> findings.add(opaque(
                scope, path, "RG.CONTRACT.SCHEMA_OPAQUE",
                "The current schema uses semantics outside the deterministic compatibility subset.")));

        Map<String, SchemaField> oldFields = new LinkedHashMap<>(oldSchema.fields());
        Map<String, SchemaField> newFields = new LinkedHashMap<>(newSchema.fields());
        oldFields.remove("");
        newFields.remove("");

        Map<String, String> renames = new LinkedHashMap<>();
        for (SchemaField candidate : newFields.values()) {
            if (!candidate.renamedFrom().isBlank() && oldFields.containsKey(candidate.renamedFrom())
                    && !newFields.containsKey(candidate.renamedFrom())) {
                renames.put(candidate.renamedFrom(), candidate.path());
            }
        }
        renames.forEach((from, to) -> {
            SchemaField oldField = oldFields.remove(from);
            SchemaField newField = newFields.remove(to);
            findings.add(new PendingFinding(
                    scope,
                    to,
                    from,
                    ChangeKind.RENAMED,
                    classify(scope, ChangeKind.RENAMED, oldField, newField, policy),
                    "RG.CONTRACT.FIELD_RENAMED",
                    "Field " + from + " was explicitly renamed to " + to + ".",
                    shapeDetails(oldField, newField)));
            compareField(scope, oldField, newField, policy, findings);
        });

        for (Map.Entry<String, SchemaField> entry : oldFields.entrySet()) {
            SchemaField currentField = newFields.remove(entry.getKey());
            if (currentField == null) {
                findings.add(new PendingFinding(
                        scope,
                        entry.getKey(),
                        "",
                        ChangeKind.REMOVED,
                        classify(scope, ChangeKind.REMOVED, entry.getValue(), null, policy),
                        "RG.CONTRACT.FIELD_REMOVED",
                        "Field " + entry.getKey() + " was removed.",
                        shapeDetails(entry.getValue(), null)));
            } else {
                compareField(scope, entry.getValue(), currentField, policy, findings);
            }
        }
        newFields.forEach((path, field) -> findings.add(new PendingFinding(
                scope,
                path,
                "",
                ChangeKind.ADDED,
                classify(scope, ChangeKind.ADDED, null, field, policy),
                "RG.CONTRACT.FIELD_ADDED",
                "Field " + path + " was added.",
                shapeDetails(null, field))));
    }

    private static void compareField(
            Scope scope,
            SchemaField previous,
            SchemaField current,
            String policy,
            List<PendingFinding> findings) {
        if (previous.required() != current.required()) {
            findings.add(new PendingFinding(
                    scope,
                    current.path(),
                    previous.path(),
                    ChangeKind.REQUIRED_CHANGED,
                    classify(scope, ChangeKind.REQUIRED_CHANGED, previous, current, policy),
                    "RG.CONTRACT.REQUIRED_CHANGED",
                    "Requiredness changed for " + current.path() + ".",
                    shapeDetails(previous, current)));
        }
        if (!previous.types().equals(current.types())) {
            findings.add(new PendingFinding(
                    scope,
                    current.path(),
                    previous.path(),
                    ChangeKind.TYPE_CHANGED,
                    classify(scope, ChangeKind.TYPE_CHANGED, previous, current, policy),
                    "RG.CONTRACT.TYPE_CHANGED",
                    "Accepted type set changed for " + current.path() + ".",
                    shapeDetails(previous, current)));
        }
        if (!previous.enumValues().equals(current.enumValues())) {
            findings.add(new PendingFinding(
                    scope,
                    current.path(),
                    previous.path(),
                    ChangeKind.ENUM_CHANGED,
                    classify(scope, ChangeKind.ENUM_CHANGED, previous, current, policy),
                    "RG.CONTRACT.ENUM_CHANGED",
                    "Allowed enum values changed for " + current.path() + ".",
                    shapeDetails(previous, current)));
        }
        if (!previous.constraints().equals(current.constraints())) {
            findings.add(new PendingFinding(
                    scope,
                    current.path(),
                    previous.path(),
                    ChangeKind.CONSTRAINT_CHANGED,
                    FindingClassification.REVIEW_REQUIRED,
                    "RG.CONTRACT.CONSTRAINT_CHANGED",
                    "Validation constraints changed for " + current.path() + ".",
                    Map.of(
                            "baselineKeywords", previous.constraints().keySet().stream().sorted().toList(),
                            "currentKeywords", current.constraints().keySet().stream().sorted().toList())));
        }
    }

    private static void compareContractSemantics(
            ContractDraft previous,
            ContractDraft current,
            List<PendingFinding> findings) {
        if (!Objects.equals(previous.executionSemantics(), current.executionSemantics())
                || !Objects.equals(previous.errorContract(), current.errorContract())
                || !Objects.equals(previous.invariants(), current.invariants())
                || !Objects.equals(previous.fieldMetadata(), current.fieldMetadata())
                || previous.source() != current.source()
                || previous.confidence() != current.confidence()) {
            findings.add(new PendingFinding(
                    Scope.CONTRACT,
                    "",
                    "",
                    ChangeKind.OPAQUE,
                    FindingClassification.REVIEW_REQUIRED,
                    "RG.CONTRACT.SEMANTICS_CHANGED",
                    "Non-schema Contract semantics changed and require explicit review.",
                    Map.of(
                            "baselineConfidence", previous.confidence().name(),
                            "currentConfidence", current.confidence().name())));
        }
    }

    private static FindingClassification classify(
            Scope scope,
            ChangeKind change,
            SchemaField previous,
            SchemaField current,
            String policy) {
        if ("NONE".equals(policy)) {
            return FindingClassification.REVIEW_REQUIRED;
        }
        if (change == ChangeKind.RENAMED || change == ChangeKind.REMOVED) {
            return FindingClassification.BREAKING;
        }
        if (change == ChangeKind.ADDED) {
            if (scope == Scope.INPUT) {
                return current != null && current.required()
                        ? FindingClassification.BREAKING : FindingClassification.COMPATIBLE;
            }
            return "STRICT".equals(policy)
                    ? FindingClassification.BREAKING : FindingClassification.COMPATIBLE;
        }
        if (change == ChangeKind.REQUIRED_CHANGED) {
            if (scope == Scope.INPUT) {
                return current.required()
                        ? FindingClassification.BREAKING : FindingClassification.COMPATIBLE;
            }
            return current.required()
                    ? FindingClassification.COMPATIBLE : FindingClassification.BREAKING;
        }
        if (change == ChangeKind.TYPE_CHANGED) {
            if (scope == Scope.INPUT) {
                return current.types().containsAll(previous.types())
                        ? FindingClassification.COMPATIBLE : FindingClassification.BREAKING;
            }
            return previous.types().containsAll(current.types())
                    ? FindingClassification.COMPATIBLE : FindingClassification.BREAKING;
        }
        if (change == ChangeKind.ENUM_CHANGED) {
            if (scope == Scope.INPUT) {
                return current.enumValues().containsAll(previous.enumValues())
                        ? FindingClassification.COMPATIBLE : FindingClassification.BREAKING;
            }
            return previous.enumValues().containsAll(current.enumValues())
                    ? FindingClassification.COMPATIBLE : FindingClassification.BREAKING;
        }
        return FindingClassification.REVIEW_REQUIRED;
    }

    private static FlattenedSchema flatten(Scope scope, SchemaEnvelope envelope) {
        Map<String, SchemaField> fields = new LinkedHashMap<>();
        Set<String> opaque = new LinkedHashSet<>();
        flattenNode("", false, envelope == null ? Map.of() : envelope.schema(), fields, opaque, 0);
        return new FlattenedSchema(fields, opaque);
    }

    private static void flattenNode(
            String path,
            boolean required,
            Map<String, Object> schema,
            Map<String, SchemaField> fields,
            Set<String> opaque,
            int depth) {
        if (depth > 64) {
            opaque.add(path);
            return;
        }
        Set<String> unknown = new LinkedHashSet<>(schema.keySet());
        unknown.removeAll(KNOWN_KEYWORDS);
        if (!unknown.isEmpty() || schema.containsKey("$ref")
                || schema.containsKey("allOf") || schema.containsKey("anyOf")
                || schema.containsKey("oneOf") || schema.containsKey("not")
                || schema.containsKey("if") || schema.containsKey("then")
                || schema.containsKey("else") || schema.containsKey("patternProperties")
                || schema.containsKey("dependentSchemas") || schema.containsKey("unevaluatedProperties")) {
            opaque.add(path);
        }
        SchemaField field = new SchemaField(
                path,
                required,
                types(schema.get("type")),
                values(schema.get("enum")),
                constraints(schema),
                stringValue(schema.get("x-bloge-renamed-from")),
                schema.containsKey("default"));
        fields.put(path, field);

        Object rawProperties = schema.get("properties");
        if (rawProperties instanceof Map<?, ?> properties) {
            Set<String> requiredNames = stringSet(schema.get("required"));
            properties.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        String name = String.valueOf(entry.getKey());
                        if (entry.getValue() instanceof Map<?, ?> child) {
                            flattenNode(path + "/" + escape(name), requiredNames.contains(name),
                                    stringMap(child), fields, opaque, depth + 1);
                        } else {
                            opaque.add(path + "/" + escape(name));
                        }
                    });
        }
        Object rawItems = schema.get("items");
        if (rawItems instanceof Map<?, ?> items) {
            flattenNode(path + "/*", true, stringMap(items), fields, opaque, depth + 1);
        } else if (rawItems instanceof List<?>) {
            opaque.add(path + "/*");
        }
    }

    private static ImpactProjection projectImpact(
            ScenarioDraftSet draftSet,
            List<Finding> findings,
            Map<FieldKey, SchemaField> candidateFields) {
        Map<String, MutableImpact> impacts = new LinkedHashMap<>();
        Map<String, List<String>> findingScenarioIds = new LinkedHashMap<>();
        for (Finding finding : findings) {
            for (ScenarioDraftSet.ScenarioDraft scenario : draftSet.scenarios()) {
                if (affects(scenario, finding)) {
                    MutableImpact impact = impacts.computeIfAbsent(
                            scenario.scenarioId(), ignored -> new MutableImpact());
                    impact.findingIds.add(finding.findingId());
                    impact.paths.add(finding.path());
                    findingScenarioIds.computeIfAbsent(finding.findingId(), ignored -> new ArrayList<>())
                            .add(scenario.scenarioId());
                }
            }
        }

        List<MigrationAction> migrations = new ArrayList<>();
        int actionSequence = 1;
        for (Finding finding : findings) {
            List<String> scenarioIds = findingScenarioIds.getOrDefault(finding.findingId(), List.of())
                    .stream().distinct().sorted().toList();
            if (scenarioIds.isEmpty() && finding.scope() != Scope.CONTRACT) {
                continue;
            }
            MigrationSpec migration = migrationFor(
                    finding, candidateFields.get(new FieldKey(finding.scope(), finding.path())));
            if (migration == null) {
                continue;
            }
            String actionId = "M-" + String.format("%03d", actionSequence++);
            migrations.add(new MigrationAction(
                    actionId,
                    migration.kind(),
                    finding.scope(),
                    finding.previousPath(),
                    finding.path(),
                    migration.automatic(),
                    scenarioIds,
                    migration.rationale()));
            scenarioIds.forEach(id -> impacts.computeIfAbsent(id, ignored -> new MutableImpact())
                    .migrationKinds.add(migration));
        }

        List<ScenarioImpact> projected = impacts.entrySet().stream()
                .map(entry -> new ScenarioImpact(
                        entry.getKey(),
                        impactStatus(entry.getValue()),
                        new ArrayList<>(entry.getValue().findingIds),
                        new ArrayList<>(entry.getValue().paths)))
                .sorted(Comparator.comparing(ScenarioImpact::scenarioId))
                .toList();
        return new ImpactProjection(projected, migrations);
    }

    private static boolean affects(ScenarioDraftSet.ScenarioDraft scenario, Finding finding) {
        if (finding.scope() == Scope.CONTRACT) {
            return true;
        }
        if (finding.scope() == Scope.INPUT) {
            String path = finding.change() == ChangeKind.RENAMED
                    ? finding.previousPath() : finding.path();
            boolean present = hasPath(scenario.given().input(), path);
            if (finding.change() == ChangeKind.ADDED
                    || (finding.change() == ChangeKind.REQUIRED_CHANGED
                    && Boolean.TRUE.equals(finding.details().get("currentRequired")))) {
                return !hasPath(scenario.given().input(), finding.path());
            }
            return present;
        }
        return scenario.then().assertions().stream()
                .filter(assertion -> assertion.scope() == ScenarioDraftSet.AssertionScope.OUTPUT_PATH)
                .anyMatch(assertion -> relatedPaths(assertion.path(), finding.path())
                        || relatedPaths(assertion.path(), finding.previousPath()));
    }

    private static MigrationSpec migrationFor(Finding finding, SchemaField current) {
        if (finding.scope() == Scope.INPUT && finding.change() == ChangeKind.ADDED
                && Boolean.TRUE.equals(finding.details().get("currentRequired"))) {
            if (current != null && current.hasDefault()) {
                return new MigrationSpec(MigrationKind.ADD_DEFAULT, true,
                        "Add the Contract default to each affected Given input.");
            }
            return new MigrationSpec(MigrationKind.SET_REQUIRED_VALUE, false,
                    "Provide an explicit value for the new required input.");
        }
        if (finding.scope() == Scope.INPUT && finding.change() == ChangeKind.REMOVED) {
            return new MigrationSpec(MigrationKind.REMOVE_INPUT, true,
                    "Remove the field no longer accepted by the Contract.");
        }
        if (finding.scope() == Scope.INPUT && finding.change() == ChangeKind.RENAMED) {
            return new MigrationSpec(MigrationKind.RENAME_INPUT, true,
                    "Move the existing Given value to the declared replacement path.");
        }
        if (finding.scope() == Scope.OUTPUT && finding.change() == ChangeKind.RENAMED) {
            return new MigrationSpec(MigrationKind.REBIND_OUTPUT_ASSERTION, true,
                    "Rebind affected output assertions to the declared replacement path.");
        }
        if (finding.change() == ChangeKind.TYPE_CHANGED
                || finding.change() == ChangeKind.ENUM_CHANGED
                || finding.change() == ChangeKind.CONSTRAINT_CHANGED) {
            return new MigrationSpec(MigrationKind.CONVERT_VALUE, false,
                    "Review and convert affected values against the new field semantics.");
        }
        if (finding.classification() == FindingClassification.REVIEW_REQUIRED
                || finding.scope() == Scope.CONTRACT) {
            return new MigrationSpec(MigrationKind.MANUAL_REVIEW, false,
                    "Review this change explicitly before rebasing and rerunning.");
        }
        return null;
    }

    private static ImpactStatus impactStatus(MutableImpact impact) {
        if (impact.migrationKinds.stream().anyMatch(spec ->
                spec.kind() == MigrationKind.MANUAL_REVIEW)) {
            return ImpactStatus.REVIEW_REQUIRED;
        }
        if (impact.migrationKinds.stream().anyMatch(spec -> !spec.automatic())) {
            return ImpactStatus.BLOCKED;
        }
        return ImpactStatus.MIGRATION_AVAILABLE;
    }

    private static Classification classification(
            List<Finding> findings,
            String baselineFingerprint,
            String currentFingerprint,
            ContractDraft.Target baselineTarget,
            ContractDraft.Target currentTarget) {
        if (findings.isEmpty() && baselineFingerprint.equals(currentFingerprint)
                && baselineTarget.fingerprint().equals(currentTarget.fingerprint())) {
            return Classification.UNCHANGED;
        }
        if (findings.stream().anyMatch(finding ->
                finding.classification() == FindingClassification.REVIEW_REQUIRED)) {
            return Classification.REVIEW_REQUIRED;
        }
        if (findings.stream().anyMatch(finding ->
                finding.classification() == FindingClassification.BREAKING)) {
            return Classification.BREAKING;
        }
        return Classification.COMPATIBLE;
    }

    private String fingerprint(
            StoredScenarioDraftSet source,
            ContractDraft current,
            String baselineFingerprint,
            String currentFingerprint,
            String policy,
            Classification classification,
            List<Finding> findings,
            List<ScenarioImpact> impacts,
            List<MigrationAction> migrations) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", ContractCompatibilityReport.SCHEMA_VERSION);
        content.put("scenarioDraftSetId", source.scenarioDraftSetId());
        content.put("scenarioRevision", source.revision());
        content.put("target", current.target());
        content.put("baselineContractFingerprint", baselineFingerprint);
        content.put("currentContractFingerprint", currentFingerprint);
        content.put("policy", policy);
        content.put("classification", classification);
        content.put("findings", findings);
        content.put("impactedScenarios", impacts);
        content.put("migrations", migrations);
        return VisualBundleFingerprint.fromCanonicalValue(objectMapper, content, MAX_REPORT_BYTES);
    }

    private static List<Finding> numberedFindings(List<PendingFinding> pending) {
        List<Finding> findings = new ArrayList<>(pending.size());
        for (int index = 0; index < pending.size(); index++) {
            PendingFinding finding = pending.get(index);
            findings.add(new Finding(
                    "F-" + String.format("%03d", index + 1),
                    finding.scope(),
                    finding.path(),
                    finding.previousPath(),
                    finding.change(),
                    finding.classification(),
                    finding.code(),
                    finding.message(),
                    finding.details()));
        }
        return findings;
    }

    private static PendingFinding opaque(
            Scope scope,
            String path,
            String code,
            String message) {
        return new PendingFinding(
                scope,
                path,
                "",
                ChangeKind.OPAQUE,
                FindingClassification.REVIEW_REQUIRED,
                code,
                message,
                Map.of());
    }

    private static Map<String, Object> shapeDetails(
            SchemaField previous,
            SchemaField current) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("baselineTypes", previous == null ? List.of() : previous.types());
        details.put("currentTypes", current == null ? List.of() : current.types());
        details.put("baselineRequired", previous != null && previous.required());
        details.put("currentRequired", current != null && current.required());
        details.put("baselineEnumCount", previous == null ? 0 : previous.enumValues().size());
        details.put("currentEnumCount", current == null ? 0 : current.enumValues().size());
        details.put("currentHasDefault", current != null && current.hasDefault());
        return details;
    }

    private static Set<String> types(Object raw) {
        if (raw instanceof Collection<?> values) {
            return values.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        String value = stringValue(raw);
        return value.isBlank() ? Set.of() : Set.of(value);
    }

    private static Set<String> values(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        return values.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, Object> constraints(Map<String, Object> schema) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        CONSTRAINT_KEYWORDS.stream().sorted().forEach(keyword -> {
            if (schema.containsKey(keyword)) {
                constraints.put(keyword, schema.get(keyword));
            }
        });
        return constraints;
    }

    private static Set<String> stringSet(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static boolean hasPath(Object root, String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return root != null;
        }
        Object current = root;
        for (String token : pointer.substring(1).split("/", -1)) {
            if (!(current instanceof Map<?, ?> map)) {
                return false;
            }
            String key = token.replace("~1", "/").replace("~0", "~");
            if (!map.containsKey(key)) {
                return false;
            }
            current = map.get(key);
        }
        return true;
    }

    private static boolean relatedPaths(String left, String right) {
        if (left == null || right == null || right.isBlank()) {
            return left == null || left.isBlank();
        }
        if (left.isBlank()) {
            return true;
        }
        return left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record FieldKey(Scope scope, String path) {
    }

    private record SchemaField(
            String path,
            boolean required,
            Set<String> types,
            Set<String> enumValues,
            Map<String, Object> constraints,
            String renamedFrom,
            boolean hasDefault) {
    }

    private record FlattenedSchema(
            Map<String, SchemaField> fields,
            Set<String> opaquePaths) {
    }

    private record PendingFinding(
            Scope scope,
            String path,
            String previousPath,
            ChangeKind change,
            FindingClassification classification,
            String code,
            String message,
            Map<String, Object> details) {
    }

    private record MigrationSpec(MigrationKind kind, boolean automatic, String rationale) {
    }

    private record ImpactProjection(
            List<ScenarioImpact> impacts,
            List<MigrationAction> migrations) {
    }

    private static final class MutableImpact {
        private final Set<String> findingIds = new LinkedHashSet<>();
        private final Set<String> paths = new LinkedHashSet<>();
        private final List<MigrationSpec> migrationKinds = new ArrayList<>();
    }
}
