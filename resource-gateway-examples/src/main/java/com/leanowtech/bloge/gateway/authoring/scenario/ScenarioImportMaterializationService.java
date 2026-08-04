package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-authoritative bounded CSV/JSON to Scenario materializer.
 *
 * <p>The service treats the browser plan as untrusted. It parses the raw source again with a
 * mature CSV parser or Jackson, recomputes source/mapping/plan fingerprints, checks the exact
 * target and Contract closure, applies only a finite converter set, validates the resulting
 * canonical Scenario set, and persists only the payload-free receipt plus materialized asset.</p>
 */
public final class ScenarioImportMaterializationService {

    private static final int SERVER_MAX_BYTES = 1_048_576;
    private static final int SERVER_MAX_ROWS = 500;
    private static final int SERVER_MAX_COLUMNS = 100;
    private static final int SERVER_MAX_CELL_BYTES = 32_768;
    private static final int SERVER_MAX_DEPTH = 16;
    private static final int SERVER_MAX_ITEMS = 50_000;
    private static final int FINGERPRINT_MAX_BYTES = 16 * 1_048_576;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> CASE_TYPES = Set.of(
            "GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION", "PROPERTY");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> MAPPING_REASONS = Set.of(
            "EXACT_PATH", "EXACT_NAME", "NORMALIZED_NAME", "MANUAL");
    private static final Set<String> CONFLICT_POLICIES = Set.of(
            "FAIL", "APPEND", "REPLACE_EXACT_ID");
    private static final Set<String> IDENTITY_POLICIES = Set.of(
            "CANONICAL_ROW_HASH", "SOURCE_COLUMN");

    private final ObjectMapper mapper;
    private final ScenarioDraftSetAuthoringService authoring;
    private final ScenarioImportReceiptRepository receipts;
    private final Clock clock;

    /** Creates the exact materialization boundary. */
    public ScenarioImportMaterializationService(
            ObjectMapper mapper,
            ScenarioDraftSetAuthoringService authoring,
            ScenarioImportReceiptRepository receipts,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Replays and materializes one exact import plan.
     *
     * @param request untrusted bounded source and plan
     * @param identity verified authoring identity
     * @return durable idempotent result
     */
    public ScenarioImportMaterializationResult materialize(
            ScenarioImportMaterializationRequest request,
            IntegrationRequestContext identity) {
        identity.requireComplete();
        if (request == null
                || !ScenarioImportMaterializationRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || request.plan() == null || !request.plan().isObject()
                || request.draftSet() == null || request.sourceText() == null) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.REQUEST_INVALID",
                    "A versioned source, plan, and canonical base Scenario set are required.");
        }
        ObjectNode plan = (ObjectNode) request.plan();
        requireText(plan, "schemaVersion", "bloge.scenarioMaterializationPlan.v1", identity);
        Budget budget = budget(plan.path("budget"), identity);
        ParsedSource source = parseSource(request.sourceText(), plan.path("source"), budget, identity);
        verifyFingerprints(plan, request.sourceText(), source, identity);
        verifyClosure(plan, request.draftSet(), source, identity);

        String planFingerprint = plan.path("planFingerprint").asText();
        ScenarioDraftSet.EnterpriseScope scope = request.draftSet().scope();
        Optional<ScenarioImportMaterializationResult> retained = receipts.find(scope, planFingerprint);
        if (retained.isPresent()) {
            return retained.get();
        }

        ScenarioImportMaterializationResult candidate = buildResult(
                source, plan, request.draftSet(), request.templateScenarioId(), identity);
        ScenarioValidationReport validation = authoring.validate(candidate.draftSet(), identity);
        if (!validation.valid()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.MATERIALIZED_INVALID",
                    "Materialized Scenarios do not satisfy the exact current Contract.");
        }
        return receipts.saveIfAbsent(scope, planFingerprint, candidate);
    }

    private ScenarioImportMaterializationResult buildResult(
            ParsedSource source,
            ObjectNode plan,
            ScenarioDraftSet draftSet,
            String templateScenarioId,
            IntegrationRequestContext identity) {
        ObjectNode template = template(draftSet, templateScenarioId);
        Set<String> selected = textSet(plan.path("rowSelection"));
        Map<String, ObjectNode> existing = new LinkedHashMap<>();
        for (ScenarioDraftSet.ScenarioDraft scenario : draftSet.scenarios()) {
            existing.put(scenario.scenarioId(), mapper.valueToTree(scenario));
        }
        List<ObjectNode> imported = new ArrayList<>();
        ArrayNode receiptRows = mapper.createArrayNode();
        List<String> acceptedIds = new ArrayList<>();
        Set<String> seenIdentities = new HashSet<>();
        String conflictPolicy = plan.path("conflictPolicy").asText("FAIL");
        JsonNode identityPolicy = plan.path("rowIdentityPolicy");
        for (SourceRow row : source.rows().stream()
                .filter(candidate -> selected.contains(candidate.rowId()))
                .toList()) {
            String identityValue = row.rowFingerprint();
            String scenarioId = "";
            try {
                identityValue = rowIdentity(row, identityPolicy, identity);
                if (!seenIdentities.add(identityValue)) {
                    throw rowProblem("RG.SCENARIO_IMPORT.IDENTITY_DUPLICATE");
                }
                String identityFingerprint = fingerprint(Map.of("identity", identityValue));
                scenarioId = "import-" + fingerprint(Map.of(
                        "source", source.fingerprint(),
                        "identity", identityValue,
                        "target", plan.path("target"))).substring(7, 23);
                ObjectNode scenario = applyBindings(
                        template.deepCopy(), scenarioId, row, plan.path("bindings"), identity);
                ObjectNode previous = existing.get(scenarioId);
                String status = previous == null ? "CREATED"
                        : previous.equals(scenario) ? "UNCHANGED"
                        : "REPLACE_EXACT_ID".equals(conflictPolicy) ? "REPLACED" : "REJECTED";
                if ("REJECTED".equals(status)) {
                    receiptRows.add(receiptRow(identityFingerprint, row.rowFingerprint(), scenarioId,
                            status, "RG.SCENARIO_IMPORT.CONFLICT"));
                    continue;
                }
                imported.add(scenario);
                existing.put(scenarioId, scenario);
                acceptedIds.add(scenarioId);
                receiptRows.add(receiptRow(identityFingerprint, row.rowFingerprint(), scenarioId, status, ""));
            } catch (RowProblem problem) {
                receiptRows.add(receiptRow(
                        fingerprint(Map.of("identity", identityValue)),
                        row.rowFingerprint(), scenarioId,
                        "REJECTED", problem.code()));
            }
        }

        ArrayNode scenarios = mapper.createArrayNode();
        Set<String> accepted = Set.copyOf(acceptedIds);
        draftSet.scenarios().stream()
                .filter(scenario -> !accepted.contains(scenario.scenarioId()))
                .map(mapper::<JsonNode>valueToTree)
                .forEach(scenarios::add);
        imported.forEach(scenarios::add);

        Instant materializedAt = clock.instant();
        ObjectNode receiptMaterial = mapper.createObjectNode();
        receiptMaterial.put("schemaVersion", "bloge.scenarioMaterializationReceipt.v1");
        receiptMaterial.put("receiptId", "scenario-import-"
                + plan.path("planFingerprint").asText().substring(7, 23));
        receiptMaterial.put("planFingerprint", plan.path("planFingerprint").asText());
        receiptMaterial.put("sourceFingerprint", source.fingerprint());
        receiptMaterial.put("mappingFingerprint", plan.path("mappingFingerprint").asText());
        receiptMaterial.put("contractFingerprint", plan.path("contractFingerprint").asText());
        receiptMaterial.put("targetFingerprint", plan.path("target").path("fingerprint").asText());
        receiptMaterial.put("rowCount", selected.size());
        receiptMaterial.put("acceptedRowCount", acceptedIds.size());
        receiptMaterial.put("rejectedRowCount", receiptRows.size() - acceptedIds.size());
        receiptMaterial.set("rowIdentityPolicy", identityPolicy.deepCopy());
        receiptMaterial.set("materializedScenarioIds", mapper.valueToTree(acceptedIds));
        receiptMaterial.set("rows", receiptRows);
        receiptMaterial.put("actor", identity.actorId());
        receiptMaterial.put("materializedAt", materializedAt.toString());
        String classification = plan.path("source").path("classification").asText();
        receiptMaterial.put("classification", classification);
        ObjectNode receipt = receiptMaterial.deepCopy();
        receipt.put("receiptFingerprint", fingerprint(receiptMaterial));

        ObjectNode resultTree = mapper.valueToTree(draftSet);
        resultTree.set("scenarios", scenarios);
        ObjectNode metadata = resultTree.withObject("metadata");
        metadata.put("classification", classification);
        metadata.put("updatedAt", materializedAt.toString());
        metadata.withObject("provenance").set("scenarioImportReceipt", receipt);
        ScenarioDraftSet resultDraft = mapper.convertValue(resultTree, ScenarioDraftSet.class);
        rejectSecrets(resultDraft, identity);
        return new ScenarioImportMaterializationResult("", resultDraft, receipt);
    }

    private ParsedSource parseSource(
            String text,
            JsonNode source,
            Budget budget,
            IntegrationRequestContext identity) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > budget.maxBytes()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.BYTES_EXCEEDED",
                    "Source exceeds the admitted byte budget.");
        }
        if (text.indexOf('\0') >= 0 || text.indexOf('\uFFFD') >= 0) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.ENCODING_INVALID",
                    "Source is not valid, clean UTF-8 text.");
        }
        String kind = source.path("kind").asText();
        String parser = source.path("parser").asText();
        if (!"UTF-8".equals(source.path("encoding").asText())
                || ("CSV".equals(kind) && !"papaparse-v5".equals(parser))
                || ("JSON".equals(kind) && !"json-standard-v1".equals(parser))) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.PARSER_UNSUPPORTED",
                    "Source encoding or parser version is unsupported.");
        }
        List<Map<String, JsonNode>> rows = switch (kind) {
            case "CSV" -> parseCsv(text, source.path("delimiter").asText(","), budget, identity);
            case "JSON" -> parseJson(text, budget, identity);
            default -> throw badRequest(identity, "RG.SCENARIO_IMPORT.KIND_INVALID",
                    "Only CSV and JSON source snapshots are supported.");
        };
        Set<String> columns = new LinkedHashSet<>();
        rows.forEach(row -> columns.addAll(row.keySet()));
        if (columns.size() > budget.maxColumns()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.COLUMNS_EXCEEDED",
                    "Source exceeds the admitted column budget.");
        }
        Map<String, Integer> occurrences = new HashMap<>();
        List<SourceRow> projected = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, JsonNode> values = rows.get(index);
            String rowFingerprint = fingerprint(values);
            int occurrence = occurrences.merge(rowFingerprint, 1, Integer::sum);
            projected.add(new SourceRow(
                    "row-" + rowFingerprint.substring(7, 23) + "-" + occurrence,
                    index, rowFingerprint, Map.copyOf(values)));
        }
        return new ParsedSource("", projected, Set.copyOf(columns));
    }

    private List<Map<String, JsonNode>> parseCsv(
            String text,
            String delimiter,
            Budget budget,
            IntegrationRequestContext identity) {
        if (delimiter.length() != 1) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.DELIMITER_INVALID",
                    "CSV delimiter must be one character.");
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter.charAt(0))
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = CSVParser.builder()
                .setReader(new StringReader(text))
                .setFormat(format)
                .get()) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) return List.of();
            List<String> headers = new ArrayList<>();
            for (String value : records.getFirst()) {
                String header = value.replaceFirst("^\\uFEFF", "").trim();
                if (header.isEmpty()) {
                    throw badRequest(identity, "RG.SCENARIO_IMPORT.HEADER_EMPTY",
                            "Every CSV column requires a non-empty header.");
                }
                headers.add(header);
            }
            if (new HashSet<>(headers).size() != headers.size()) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.HEADER_DUPLICATE",
                        "CSV contains duplicate headers.");
            }
            if (headers.size() > budget.maxColumns()) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.COLUMNS_EXCEEDED",
                        "Source exceeds the admitted column budget.");
            }
            List<Map<String, JsonNode>> rows = new ArrayList<>();
            for (CSVRecord record : records.subList(1, records.size())) {
                boolean blank = record.stream().allMatch(value -> value.trim().isEmpty());
                if (blank) continue;
                if (record.size() > headers.size()) {
                    throw badRequest(identity, "RG.SCENARIO_IMPORT.COLUMN_OVERFLOW",
                            "A CSV row contains more cells than the header.");
                }
                Map<String, JsonNode> row = new LinkedHashMap<>();
                for (int index = 0; index < record.size(); index++) {
                    TextNode value = TextNode.valueOf(record.get(index));
                    requireCell(value, budget, identity);
                    row.put(pointer(headers.get(index)), value);
                }
                rows.add(row);
            }
            if (rows.size() > budget.maxRows()) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.ROWS_EXCEEDED",
                        "Source exceeds the admitted row budget.");
            }
            return rows;
        } catch (IOException | IllegalArgumentException exception) {
            if (exception instanceof IntegrationProblemException problem) throw problem;
            throw badRequest(identity, "RG.SCENARIO_IMPORT.CSV_INVALID",
                    "CSV could not be parsed within the supported dialect.");
        }
    }

    private List<Map<String, JsonNode>> parseJson(
            String text,
            Budget budget,
            IntegrationRequestContext identity) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException exception) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.JSON_INVALID", "JSON source is malformed.");
        }
        JsonStats stats = stats(root, 0);
        if (stats.depth() > budget.maxDepth()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.DEPTH_EXCEEDED",
                    "JSON source exceeds the admitted nesting depth.");
        }
        if (stats.items() > budget.maxItems()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.ITEMS_EXCEEDED",
                    "JSON source exceeds the admitted item budget.");
        }
        if (!root.isArray() || root.size() > budget.maxRows()) {
            throw badRequest(identity, root.isArray()
                            ? "RG.SCENARIO_IMPORT.ROWS_EXCEEDED"
                            : "RG.SCENARIO_IMPORT.JSON_SHAPE_INVALID",
                    root.isArray() ? "Source exceeds the admitted row budget."
                            : "JSON source must be an array of objects.");
        }
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (JsonNode rowNode : root) {
            if (!rowNode.isObject()) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.JSON_SHAPE_INVALID",
                        "JSON source must be an array of objects.");
            }
            Map<String, JsonNode> row = new LinkedHashMap<>();
            flatten(rowNode, List.of(), row, budget, identity);
            rows.add(row);
        }
        return rows;
    }

    private void verifyFingerprints(
            ObjectNode plan,
            String sourceText,
            ParsedSource parsed,
            IntegrationRequestContext identity) {
        JsonNode source = plan.path("source");
        ObjectNode sourceMaterial = mapper.createObjectNode();
        sourceMaterial.put("kind", source.path("kind").asText());
        sourceMaterial.put("encoding", source.path("encoding").asText());
        sourceMaterial.put("delimiter", source.path("delimiter").asText());
        sourceMaterial.put("parser", source.path("parser").asText());
        sourceMaterial.put("text", sourceText);
        String sourceFingerprint = fingerprint(sourceMaterial);
        parsed.fingerprint(sourceFingerprint);
        requireFingerprint(source.path("fingerprint").asText(), sourceFingerprint,
                "RG.SCENARIO_IMPORT.SOURCE_DRIFT", identity);
        String mappingFingerprint = fingerprint(plan.path("bindings"));
        requireFingerprint(plan.path("mappingFingerprint").asText(), mappingFingerprint,
                "RG.SCENARIO_IMPORT.MAPPING_DRIFT", identity);
        ObjectNode planMaterial = plan.deepCopy();
        planMaterial.remove("planFingerprint");
        requireFingerprint(plan.path("planFingerprint").asText(), fingerprint(planMaterial),
                "RG.SCENARIO_IMPORT.PLAN_DRIFT", identity);
    }

    private void verifyClosure(
            ObjectNode plan,
            ScenarioDraftSet draftSet,
            ParsedSource source,
            IntegrationRequestContext identity) {
        if (!plan.path("contractFingerprint").asText().equals(draftSet.contractFingerprint())) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.CONTRACT_DRIFT",
                    "Contract fingerprint changed after the plan was created.");
        }
        ContractDraft.Target plannedTarget;
        try {
            plannedTarget = mapper.treeToValue(plan.path("target"), ContractDraft.Target.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.TARGET_INVALID",
                    "Materialization target is malformed.");
        }
        if (!plannedTarget.equals(draftSet.target())) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.TARGET_DRIFT",
                    "Target coordinate changed after the plan was created.");
        }
        String classification = plan.path("source").path("classification").asText();
        if (!CLASSIFICATIONS.contains(classification) || !identity.hasClearanceAtLeast(classification)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO_IMPORT.CLASSIFICATION_FORBIDDEN",
                    "Caller clearance does not admit this source classification.",
                    identity.correlationId(), Map.of()));
        }
        Set<String> rowIds = source.rows().stream().map(SourceRow::rowId).collect(
                java.util.stream.Collectors.toSet());
        Set<String> selected = textSet(plan.path("rowSelection"));
        if (selected.isEmpty() || !rowIds.containsAll(selected)) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.SELECTION_INVALID",
                    "Row selection is empty or references another source snapshot.");
        }
        Set<String> targetIds = new HashSet<>();
        JsonNode declaredSemantics = plan.path("valueSemantics");
        if (!plan.path("bindings").isArray() || !declaredSemantics.isObject()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.BINDING_INVALID",
                    "Mappings and their declared value semantics must be structured collections.");
        }
        if (!CONFLICT_POLICIES.contains(plan.path("conflictPolicy").asText())) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.CONFLICT_POLICY_INVALID",
                    "The requested conflict policy is unsupported.");
        }
        Set<String> converters = Set.of("IDENTITY", "STRING", "NUMBER", "BOOLEAN", "JSON");
        Set<String> semantics = Set.of("VALUE", "NULL", "MISSING", "EMPTY", "DEFAULT");
        Set<String> targetKinds = Set.of(
                "NAME", "CASE_TYPE", "TAGS", "GIVEN", "DEPENDENCY_OUTPUT", "ASSERTION_EXPECTED");
        for (JsonNode binding : plan.path("bindings")) {
            String sourcePath = binding.path("sourcePath").asText();
            String targetId = binding.path("target").path("targetId").asText();
            if (!source.columns().contains(sourcePath) || targetId.isBlank() || !targetIds.add(targetId)) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.BINDING_INVALID",
                        "Mappings must reference unique available source and target columns.");
            }
            double confidence = binding.path("confidence").asDouble(-1);
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1
                    || !MAPPING_REASONS.contains(binding.path("reason").asText())) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.BINDING_INVALID",
                        "Mapping confidence and reason must use the finite import protocol.");
            }
            if (confidence < 0.95 && !binding.path("confirmed").asBoolean()) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.BINDING_CONFIRMATION_REQUIRED",
                        "Low-confidence mappings require explicit confirmation.");
            }
            String bindingId = binding.path("bindingId").asText();
            String valueSemantics = binding.path("valueSemantics").asText();
            if (!semantics.contains(valueSemantics)
                    || !valueSemantics.equals(declaredSemantics.path(bindingId).asText())
                    || !converters.contains(binding.path("converter").asText())
                    || !targetKinds.contains(binding.path("target").path("kind").asText())) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.BINDING_SEMANTICS_INVALID",
                        "Mapping semantics, converter, or target kind is unsupported or inconsistent.");
            }
            if ("DEFAULT".equals(binding.path("valueSemantics").asText())
                    && !binding.has("defaultValue")) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.DEFAULT_REQUIRED",
                        "Default semantics require an explicit default value.");
            }
        }
        JsonNode identityPolicy = plan.path("rowIdentityPolicy");
        if (!identityPolicy.isObject()
                || !IDENTITY_POLICIES.contains(identityPolicy.path("kind").asText())) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.IDENTITY_INVALID",
                    "The row identity policy is unsupported.");
        }
        if ("SOURCE_COLUMN".equals(identityPolicy.path("kind").asText())
                && !source.columns().contains(identityPolicy.path("sourcePath").asText())) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.IDENTITY_INVALID",
                    "The selected identity column is not present in the source.");
        }
    }

    private ObjectNode applyBindings(
            ObjectNode scenario,
            String scenarioId,
            SourceRow row,
            JsonNode bindings,
            IntegrationRequestContext identity) {
        scenario.put("scenarioId", scenarioId);
        scenario.put("name", "Imported " + (row.canonicalIndex() + 1));
        scenario.withObject("given").put("provenance", "IMPORTED");
        for (JsonNode binding : bindings) {
            Resolution resolved = resolve(row, binding);
            if (resolved.action() == ResolutionAction.SKIP) continue;
            JsonNode target = binding.path("target");
            if (resolved.action() == ResolutionAction.REMOVE) {
                removeTargetValue(scenario, target);
                continue;
            }
            JsonNode value = convert(resolved.value(), binding.path("converter").asText(), identity);
            switch (target.path("kind").asText()) {
                case "NAME" -> scenario.put("name", value.asText());
                case "CASE_TYPE" -> {
                    String caseType = value.asText().toUpperCase(Locale.ROOT);
                    if (!CASE_TYPES.contains(caseType)) throw rowProblem("RG.SCENARIO_IMPORT.CASE_TYPE_INVALID");
                    scenario.put("caseType", caseType);
                }
                case "TAGS" -> scenario.set("tags", tags(value));
                case "GIVEN" -> setPath(scenario.withObject("given").withObject("input"),
                        target.path("valuePath"), value);
                case "DEPENDENCY_OUTPUT" -> setDependencyOutput(
                        scenario, target.path("dependencyId").asText(), target.path("valuePath"), value);
                case "ASSERTION_EXPECTED" -> setAssertionExpected(
                        scenario, target.path("assertionId").asText(), target.path("valuePath"), value);
                default -> throw rowProblem("RG.SCENARIO_IMPORT.TARGET_INVALID");
            }
        }
        if (scenario.path("name").asText().isBlank()) throw rowProblem("RG.SCENARIO_IMPORT.NAME_REQUIRED");
        return scenario;
    }

    private Resolution resolve(SourceRow row, JsonNode binding) {
        String sourcePath = binding.path("sourcePath").asText();
        String semantics = binding.path("valueSemantics").asText("VALUE");
        JsonNode value = row.values().get(sourcePath);
        if (value == null) {
            return "DEFAULT".equals(semantics)
                    ? Resolution.set(binding.path("defaultValue"))
                    : removable(binding.path("target")) ? Resolution.remove() : Resolution.skip();
        }
        if (!value.isTextual() || !value.asText().isEmpty()) return Resolution.set(value);
        return switch (semantics) {
            case "NULL" -> Resolution.set(NullNode.instance);
            case "MISSING" -> removable(binding.path("target")) ? Resolution.remove() : Resolution.skip();
            case "DEFAULT" -> Resolution.set(binding.path("defaultValue"));
            default -> Resolution.set(TextNode.valueOf(""));
        };
    }

    private JsonNode convert(JsonNode value, String converter, IntegrationRequestContext identity) {
        if (value.isNull() || converter.isBlank() || "IDENTITY".equals(converter)) return value.deepCopy();
        try {
            return switch (converter) {
                case "STRING" -> TextNode.valueOf(value.asText());
                case "NUMBER" -> {
                    if (value.isTextual() && value.asText().trim().isEmpty()) {
                        throw new NumberFormatException();
                    }
                    double number = value.isNumber() ? value.asDouble() : Double.parseDouble(value.asText().trim());
                    if (!Double.isFinite(number)) throw new NumberFormatException();
                    yield JsonNodeFactory.instance.numberNode(number);
                }
                case "BOOLEAN" -> {
                    String normalized = value.asText().trim().toLowerCase(Locale.ROOT);
                    if (value.isBoolean()) yield value.deepCopy();
                    if ("true".equals(normalized) || "1".equals(normalized)) yield JsonNodeFactory.instance.booleanNode(true);
                    if ("false".equals(normalized) || "0".equals(normalized)) yield JsonNodeFactory.instance.booleanNode(false);
                    throw rowProblem("RG.SCENARIO_IMPORT.BOOLEAN_INVALID");
                }
                case "JSON" -> value.isTextual() ? mapper.readTree(value.asText()) : value.deepCopy();
                default -> throw rowProblem("RG.SCENARIO_IMPORT.CONVERTER_INVALID");
            };
        } catch (NumberFormatException exception) {
            throw rowProblem("RG.SCENARIO_IMPORT.NUMBER_INVALID");
        } catch (JsonProcessingException exception) {
            throw rowProblem("RG.SCENARIO_IMPORT.CELL_JSON_INVALID");
        }
    }

    private void setDependencyOutput(
            ObjectNode scenario,
            String dependencyId,
            JsonNode path,
            JsonNode value) {
        for (JsonNode dependency : scenario.withArray("dependencies")) {
            if (dependencyId.equals(dependency.path("dependencyId").asText()) && dependency.isObject()) {
                ObjectNode behavior = ((ObjectNode) dependency).withObject("behavior");
                if (path.isArray() && path.isEmpty()) {
                    behavior.set("output", value.deepCopy());
                } else {
                    ObjectNode output = behavior.path("output").isObject()
                            ? (ObjectNode) behavior.path("output") : behavior.putObject("output");
                    setPath(output, path, value);
                }
                ((ObjectNode) dependency).put("origin", "IMPORTED");
                return;
            }
        }
        throw rowProblem("RG.SCENARIO_IMPORT.DEPENDENCY_TARGET_MISSING");
    }

    private void removeTargetValue(ObjectNode scenario, JsonNode target) {
        switch (target.path("kind").asText()) {
            case "GIVEN" -> removePath(
                    scenario.withObject("given").withObject("input"), target.path("valuePath"));
            case "DEPENDENCY_OUTPUT" -> removeDependencyOutput(
                    scenario, target.path("dependencyId").asText(), target.path("valuePath"));
            case "ASSERTION_EXPECTED" -> removeAssertionExpected(
                    scenario, target.path("assertionId").asText(), target.path("valuePath"));
            default -> {
                // Case metadata keeps its generated or template fallback when the source is absent.
            }
        }
    }

    private boolean removable(JsonNode target) {
        return Set.of("GIVEN", "DEPENDENCY_OUTPUT", "ASSERTION_EXPECTED")
                .contains(target.path("kind").asText());
    }

    private void removeDependencyOutput(ObjectNode scenario, String dependencyId, JsonNode path) {
        for (JsonNode dependency : scenario.withArray("dependencies")) {
            if (dependencyId.equals(dependency.path("dependencyId").asText()) && dependency.isObject()) {
                ObjectNode behavior = ((ObjectNode) dependency).withObject("behavior");
                if (path.isArray() && path.isEmpty()) behavior.remove("output");
                else if (behavior.path("output").isObject()) removePath((ObjectNode) behavior.path("output"), path);
                ((ObjectNode) dependency).put("origin", "IMPORTED");
                return;
            }
        }
        throw rowProblem("RG.SCENARIO_IMPORT.DEPENDENCY_TARGET_MISSING");
    }

    private void removeAssertionExpected(ObjectNode scenario, String assertionId, JsonNode path) {
        for (JsonNode assertion : scenario.withObject("then").withArray("assertions")) {
            if (assertionId.equals(assertion.path("assertionId").asText()) && assertion.isObject()) {
                ObjectNode object = (ObjectNode) assertion;
                if (path.isArray() && path.isEmpty()) object.remove("expected");
                else if (object.path("expected").isObject()) removePath((ObjectNode) object.path("expected"), path);
                return;
            }
        }
        throw rowProblem("RG.SCENARIO_IMPORT.ASSERTION_TARGET_MISSING");
    }

    private void setAssertionExpected(
            ObjectNode scenario,
            String assertionId,
            JsonNode path,
            JsonNode value) {
        for (JsonNode assertion : scenario.withObject("then").withArray("assertions")) {
            if (assertionId.equals(assertion.path("assertionId").asText()) && assertion.isObject()) {
                ObjectNode object = (ObjectNode) assertion;
                if (path.isArray() && path.isEmpty()) object.set("expected", value.deepCopy());
                else {
                    ObjectNode expected = object.path("expected").isObject()
                            ? (ObjectNode) object.path("expected") : object.putObject("expected");
                    setPath(expected, path, value);
                }
                return;
            }
        }
        throw rowProblem("RG.SCENARIO_IMPORT.ASSERTION_TARGET_MISSING");
    }

    private void setPath(ObjectNode root, JsonNode path, JsonNode value) {
        if (!path.isArray() || path.isEmpty()) {
            throw rowProblem("RG.SCENARIO_IMPORT.TARGET_PATH_INVALID");
        }
        ObjectNode current = root;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index).asText();
            current = current.path(segment).isObject()
                    ? (ObjectNode) current.path(segment) : current.putObject(segment);
        }
        current.set(path.get(path.size() - 1).asText(), value.deepCopy());
    }

    private void removePath(ObjectNode root, JsonNode path) {
        if (!path.isArray() || path.isEmpty()) return;
        ObjectNode current = root;
        for (int index = 0; index < path.size() - 1; index++) {
            JsonNode next = current.path(path.get(index).asText());
            if (!next.isObject()) return;
            current = (ObjectNode) next;
        }
        current.remove(path.get(path.size() - 1).asText());
    }

    private ArrayNode tags(JsonNode value) {
        ArrayNode result = mapper.createArrayNode();
        if (value.isArray()) value.forEach(entry -> result.add(entry.asText().trim()));
        else for (String tag : value.asText().split(",")) if (!tag.trim().isEmpty()) result.add(tag.trim());
        return result;
    }

    private String rowIdentity(
            SourceRow row,
            JsonNode policy,
            IntegrationRequestContext identity) {
        if (!"SOURCE_COLUMN".equals(policy.path("kind").asText())) return row.rowFingerprint();
        JsonNode value = row.values().get(policy.path("sourcePath").asText());
        if (value == null) throw rowProblem("RG.SCENARIO_IMPORT.IDENTITY_MISSING");
        if (value.isNull() || value.asText().trim().isEmpty()) throw rowProblem("RG.SCENARIO_IMPORT.IDENTITY_EMPTY");
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw rowProblem("RG.SCENARIO_IMPORT.IDENTITY_INVALID");
        }
    }

    private ObjectNode template(ScenarioDraftSet draftSet, String templateScenarioId) {
        ScenarioDraftSet.ScenarioDraft selected = draftSet.scenarios().stream()
                .filter(scenario -> scenario.scenarioId().equals(normalized(templateScenarioId)))
                .findFirst()
                .orElseGet(() -> draftSet.scenarios().stream().findFirst().orElse(emptyTemplate()));
        return mapper.valueToTree(selected);
    }

    private ScenarioDraftSet.ScenarioDraft emptyTemplate() {
        return new ScenarioDraftSet.ScenarioDraft("", "", "", ScenarioDraftSet.CaseType.GOLDEN,
                List.of(), ScenarioDraftSet.Given.empty(), List.of(), ScenarioDraftSet.Then.empty());
    }

    private void rejectSecrets(ScenarioDraftSet draftSet, IntegrationRequestContext identity) {
        var secrets = VisualSecretGuard.detectRawSecrets(mapper.convertValue(draftSet, Object.class), "/");
        if (!secrets.isEmpty()) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.RAW_SECRET_FORBIDDEN",
                    "Raw secret material cannot be materialized; bind a secretRef instead.");
        }
    }

    private void flatten(
            JsonNode value,
            List<String> path,
            Map<String, JsonNode> target,
            Budget budget,
            IntegrationRequestContext identity) {
        if (value.isObject() && !value.isEmpty()) {
            value.fields().forEachRemaining(entry -> flatten(entry.getValue(), append(path, entry.getKey()),
                    target, budget, identity));
            return;
        }
        requireCell(value, budget, identity);
        target.put("/" + path.stream().map(ScenarioImportMaterializationService::escapePointer)
                .reduce((left, right) -> left + "/" + right).orElse(""), value.deepCopy());
    }

    private void requireCell(JsonNode value, Budget budget, IntegrationRequestContext identity) {
        try {
            if (mapper.writeValueAsBytes(value).length > budget.maxCellBytes()) {
                throw badRequest(identity, "RG.SCENARIO_IMPORT.CELL_BYTES_EXCEEDED",
                        "A source cell exceeds the admitted byte budget.");
            }
        } catch (JsonProcessingException exception) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.CELL_INVALID",
                    "A source cell is not valid JSON data.");
        }
    }

    private JsonStats stats(JsonNode value, int depth) {
        if (!value.isContainerNode()) return new JsonStats(depth, 1);
        int maxDepth = depth;
        int items = 1;
        for (JsonNode child : value) {
            JsonStats nested = stats(child, depth + 1);
            maxDepth = Math.max(maxDepth, nested.depth());
            items += nested.items();
        }
        return new JsonStats(maxDepth, items);
    }

    private Budget budget(JsonNode value, IntegrationRequestContext identity) {
        int maxBytes = bounded(value.path("maxBytes").asInt(), SERVER_MAX_BYTES);
        int maxRows = bounded(value.path("maxRows").asInt(), SERVER_MAX_ROWS);
        int maxColumns = bounded(value.path("maxColumns").asInt(), SERVER_MAX_COLUMNS);
        if (maxBytes < 1 || maxRows < 1 || maxColumns < 1) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.BUDGET_INVALID",
                    "Positive import byte, row, and column budgets are required.");
        }
        return new Budget(maxBytes, maxRows, maxColumns,
                SERVER_MAX_CELL_BYTES, SERVER_MAX_DEPTH, SERVER_MAX_ITEMS);
    }

    private int bounded(int requested, int serverMaximum) {
        return requested < 1 ? -1 : Math.min(requested, serverMaximum);
    }

    private void requireText(
            ObjectNode object,
            String field,
            String required,
            IntegrationRequestContext identity) {
        if (!required.equals(object.path(field).asText())) {
            throw badRequest(identity, "RG.SCENARIO_IMPORT.PROTOCOL_UNSUPPORTED",
                    "Scenario materialization plan version is unsupported.");
        }
    }

    private void requireFingerprint(
            String supplied,
            String expected,
            String code,
            IntegrationRequestContext identity) {
        if (!FINGERPRINT.matcher(supplied).matches() || !supplied.equals(expected)) {
            throw badRequest(identity, code, "Scenario import fingerprint closure does not match.");
        }
    }

    private Set<String> textSet(JsonNode value) {
        if (!value.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        value.forEach(entry -> {
            if (entry.isTextual() && !entry.asText().isBlank()) result.add(entry.asText());
        });
        return Set.copyOf(result);
    }

    private ObjectNode receiptRow(
            String identityFingerprint,
            String rowFingerprint,
            String scenarioId,
            String status,
            String diagnosticCode) {
        ObjectNode row = mapper.createObjectNode();
        row.put("identityFingerprint", identityFingerprint);
        row.put("rowFingerprint", rowFingerprint);
        row.put("scenarioId", scenarioId);
        row.put("status", status);
        row.put("diagnosticCode", diagnosticCode);
        return row;
    }

    private String fingerprint(Object value) {
        return ScenarioImportFingerprint.of(mapper, value, FINGERPRINT_MAX_BYTES);
    }

    private IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String pointer(String header) {
        return "/" + escapePointer(header);
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static List<String> append(List<String> source, String value) {
        List<String> result = new ArrayList<>(source);
        result.add(value);
        return List.copyOf(result);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static RowProblem rowProblem(String code) {
        return new RowProblem(code);
    }

    private record Budget(
            int maxBytes,
            int maxRows,
            int maxColumns,
            int maxCellBytes,
            int maxDepth,
            int maxItems) {
    }

    private static final class ParsedSource {
        private String fingerprint;
        private final List<SourceRow> rows;
        private final Set<String> columns;

        private ParsedSource(String fingerprint, List<SourceRow> rows, Set<String> columns) {
            this.fingerprint = fingerprint;
            this.rows = List.copyOf(rows);
            this.columns = Set.copyOf(columns);
        }

        private String fingerprint() {
            return fingerprint;
        }

        private void fingerprint(String value) {
            fingerprint = value;
        }

        private List<SourceRow> rows() {
            return rows;
        }

        private Set<String> columns() {
            return columns;
        }
    }

    private record SourceRow(
            String rowId,
            int canonicalIndex,
            String rowFingerprint,
            Map<String, JsonNode> values) {
    }

    private record JsonStats(int depth, int items) {
    }

    private enum ResolutionAction {
        SET,
        REMOVE,
        SKIP
    }

    private record Resolution(ResolutionAction action, JsonNode value) {
        private static Resolution set(JsonNode value) {
            return new Resolution(ResolutionAction.SET, value);
        }

        private static Resolution remove() {
            return new Resolution(ResolutionAction.REMOVE, NullNode.instance);
        }

        private static Resolution skip() {
            return new Resolution(ResolutionAction.SKIP, NullNode.instance);
        }
    }

    private static final class RowProblem extends RuntimeException {
        private final String code;

        private RowProblem(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
