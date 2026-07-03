package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Server-derived review profile for a user-provided operator library.
 *
 * <p>The browser can still build a local preview while the user is editing JSON,
 * but validate/import responses use this profile as the authoritative preflight
 * summary because it is computed after server-side normalization, diagnostics,
 * and runtime-readiness projection.</p>
 *
 * @param schemaVersion profile schema version
 * @param librarySchemaVersion source library schema version
 * @param libraryId library id
 * @param version library version
 * @param status library lifecycle status
 * @param operatorCount number of non-null operators
 * @param inputPortCount total input ports
 * @param outputPortCount total output ports
 * @param requiredInputCount total required input fields or required root ports
 * @param configFieldCount total config fields
 * @param inputFieldCount total input fields
 * @param outputFieldCount total output fields
 * @param dslUnsafeFieldCount fields or ports that cannot be emitted safely into BLOGE DSL
 * @param dynamicSchemaCount dynamic schema surfaces
 * @param streamingOperatorCount streaming operators
 * @param durableOperatorCount durable/suspendable operators
 * @param externalOperatorCount operators with non-pure external effects
 * @param nonIdempotentOperatorCount non-idempotent operators
 * @param secretOperatorCount operators requiring secrets
 * @param policyRestrictedOperatorCount scope-restricted operators
 * @param designOnlyOperatorCount schema/design-only operators
 * @param runtimeExecutableOperatorCount request-response executable operators
 * @param runtimeBlockedOperatorCount operators blocked by the current runtime
 * @param governanceReviewOperatorCount executable operators requiring governance review
 * @param catalogRepairOperatorCount operators with catalog repair blockers
 * @param facets catalog-style facets for the submitted library
 * @param operators per-operator profile rows
 */
public record OperatorLibraryProfile(
        String schemaVersion,
        String librarySchemaVersion,
        String libraryId,
        String version,
        String status,
        int operatorCount,
        int inputPortCount,
        int outputPortCount,
        int requiredInputCount,
        int configFieldCount,
        int inputFieldCount,
        int outputFieldCount,
        int dslUnsafeFieldCount,
        int dynamicSchemaCount,
        int streamingOperatorCount,
        int durableOperatorCount,
        int externalOperatorCount,
        int nonIdempotentOperatorCount,
        int secretOperatorCount,
        int policyRestrictedOperatorCount,
        int designOnlyOperatorCount,
        int runtimeExecutableOperatorCount,
        int runtimeBlockedOperatorCount,
        int governanceReviewOperatorCount,
        int catalogRepairOperatorCount,
        OperatorCatalogFacets facets,
        List<OperatorProfile> operators
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryProfile.v1";
    private static final Pattern DSL_FIELD_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED_DSL_FIELD_NAMES = Set.of(
            "graph", "node", "branch", "decision_table", "on", "input", "depends_on",
            "timeout", "retry", "fallback", "execution_mode", "worker_topic", "compensate",
            "saga", "true", "false", "schema", "output", "otherwise", "when", "transform",
            "foreach", "sequential", "in", "loop", "parallel", "until", "carry", "wait",
            "after", "await", "event", "where", "mode", "stream", "streaming", "buffer",
            "let", "import", "as", "script", "exit", "exhausted"
    );
    private static final String CATALOG_REPAIR_REQUIRED = "catalog-repair-required";
    private static final String RUNTIME_BLOCKED = "runtime-blocked";
    private static final String GOVERNANCE_REVIEW = "governance-review";
    private static final String DESIGN_ONLY = "design-only";
    private static final String RUNTIME_EXECUTABLE = "runtime-executable";

    /**
     * Creates a profile.
     */
    public OperatorLibraryProfile {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        librarySchemaVersion = librarySchemaVersion == null ? "" : librarySchemaVersion;
        libraryId = libraryId == null ? "" : libraryId;
        version = version == null ? "" : version;
        status = status == null ? "" : status;
        facets = facets == null ? OperatorCatalogFacets.from(List.of()) : facets;
        operators = operators == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(operators));
    }

    /**
     * @return an empty profile
     */
    public static OperatorLibraryProfile empty() {
        return new OperatorLibraryProfile(
                SCHEMA_VERSION,
                "",
                "",
                "",
                "",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                OperatorCatalogFacets.from(List.of()),
                List.of()
        );
    }

    /**
     * @param library user-provided operator library
     * @return server-derived profile
     */
    public static OperatorLibraryProfile from(OperatorLibrary library) {
        return from(library, List.of());
    }

    /**
     * @param library user-provided operator library
     * @param diagnostics server validation diagnostics that refine readiness states
     * @return server-derived profile
     */
    public static OperatorLibraryProfile from(OperatorLibrary library, List<VisualDiagnostic> diagnostics) {
        if (library == null) {
            return empty();
        }
        Map<Integer, List<VisualDiagnostic>> diagnosticsByOperator = diagnosticsByOperator(diagnostics);
        List<OperatorDefinition> operators = library.operators().stream()
                .filter(operator -> operator != null)
                .toList();
        List<OperatorProfile> operatorProfiles = new ArrayList<>();
        Totals totals = new Totals();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            if (operator == null) {
                continue;
            }
            OperatorProfile profile = OperatorProfile.from(operator, diagnosticsByOperator.getOrDefault(i, List.of()));
            operatorProfiles.add(profile);
            totals.add(profile);
        }
        return new OperatorLibraryProfile(
                SCHEMA_VERSION,
                library.schemaVersion(),
                library.libraryId(),
                library.version(),
                library.status(),
                operatorProfiles.size(),
                totals.inputPortCount,
                totals.outputPortCount,
                totals.requiredInputCount,
                totals.configFieldCount,
                totals.inputFieldCount,
                totals.outputFieldCount,
                totals.dslUnsafeFieldCount,
                totals.dynamicSchemaCount,
                totals.streamingOperatorCount,
                totals.durableOperatorCount,
                totals.externalOperatorCount,
                totals.nonIdempotentOperatorCount,
                totals.secretOperatorCount,
                totals.policyRestrictedOperatorCount,
                totals.designOnlyOperatorCount,
                totals.runtimeExecutableOperatorCount,
                totals.runtimeBlockedOperatorCount,
                totals.governanceReviewOperatorCount,
                totals.catalogRepairOperatorCount,
                facetsFrom(operators, operatorProfiles),
                operatorProfiles
        );
    }

    /**
     * Per-operator profile row.
     *
     * @param label display label
     * @param operatorRef operator ref
     * @param loweringMode lowering mode
     * @param inputPortCount input port count
     * @param outputPortCount output port count
     * @param requiredInputCount required input count
     * @param configFieldCount config field count
     * @param inputFieldCount input field count
     * @param outputFieldCount output field count
     * @param dslUnsafeFieldCount DSL-unsafe field/port count
     * @param dynamicSchemaCount dynamic schema surface count
     * @param streaming streaming operator
     * @param durable durable/suspendable operator
     * @param external non-pure external effect
     * @param nonIdempotent non-idempotent operator
     * @param requiresSecrets requires secret binding
     * @param policyRestricted tenant/namespace/environment scope restricted
     * @param designOnly design-only lowering
     * @param runtimeReadinessState server-derived runtime readiness state
     * @param runtimeReadinessLevel runtime readiness severity level
     * @param runtimeReadinessTitle runtime readiness title
     * @param runtimeReadinessSummary runtime readiness summary
     * @param policySummary compact policy summary
     * @param inputUnionSummary compact input union summary
     * @param outputUnionSummary compact output union summary
     * @param configUnionSummary compact config union summary
     * @param inputFields displayable input fields
     * @param outputFields displayable output fields
     * @param configFields displayable config fields
     */
    public record OperatorProfile(
            String label,
            String operatorRef,
            String loweringMode,
            int inputPortCount,
            int outputPortCount,
            int requiredInputCount,
            int configFieldCount,
            int inputFieldCount,
            int outputFieldCount,
            int dslUnsafeFieldCount,
            int dynamicSchemaCount,
            boolean streaming,
            boolean durable,
            boolean external,
            boolean nonIdempotent,
            boolean requiresSecrets,
            boolean policyRestricted,
            boolean designOnly,
            String runtimeReadinessState,
            String runtimeReadinessLevel,
            String runtimeReadinessTitle,
            String runtimeReadinessSummary,
            String policySummary,
            String inputUnionSummary,
            String outputUnionSummary,
            String configUnionSummary,
            List<FieldProfile> inputFields,
            List<FieldProfile> outputFields,
            List<FieldProfile> configFields
    ) {
        public OperatorProfile {
            label = label == null ? "" : label;
            operatorRef = operatorRef == null ? "" : operatorRef;
            loweringMode = loweringMode == null ? "" : loweringMode;
            runtimeReadinessState = runtimeReadinessState == null ? "" : runtimeReadinessState;
            runtimeReadinessLevel = runtimeReadinessLevel == null ? "" : runtimeReadinessLevel;
            runtimeReadinessTitle = runtimeReadinessTitle == null ? "" : runtimeReadinessTitle;
            runtimeReadinessSummary = runtimeReadinessSummary == null ? "" : runtimeReadinessSummary;
            policySummary = policySummary == null ? "" : policySummary;
            inputUnionSummary = inputUnionSummary == null ? "" : inputUnionSummary;
            outputUnionSummary = outputUnionSummary == null ? "" : outputUnionSummary;
            configUnionSummary = configUnionSummary == null ? "" : configUnionSummary;
            inputFields = inputFields == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(inputFields));
            outputFields = outputFields == null ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(outputFields));
            configFields = configFields == null ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(configFields));
        }

        static OperatorProfile from(OperatorDefinition operator, List<VisualDiagnostic> diagnostics) {
            List<OperatorDefinition.Port> inputPorts = safePorts(operator.ports().inputs());
            List<OperatorDefinition.Port> outputPorts = safePorts(operator.ports().outputs());
            PortStats inputStats = inputPorts.stream()
                    .map(port -> portStats(port, false))
                    .reduce(PortStats.empty(), PortStats::add);
            PortStats outputStats = outputPorts.stream()
                    .map(port -> portStats(port, true))
                    .reduce(PortStats.empty(), PortStats::add);
            List<FieldProfile> inputFields = portFields(inputPorts, false);
            List<FieldProfile> outputFields = portFields(outputPorts, true);
            List<FieldProfile> configFields = OperatorLibraryProfile.configFields(operator.configSchema());
            int configUnsafe = (int) configFields.stream()
                    .filter(field -> !field.dslPathSafe())
                    .count();
            String sourceKind = OperatorCatalogFacets.normalizeFacetValue(operator.source().kind());
            String loweringMode = OperatorCatalogFacets.normalizeFacetValue(operator.lowering().mode());
            boolean streaming = operator.capabilities().streaming()
                    || "java-streaming-operator".equals(sourceKind);
            boolean durable = operator.capabilities().durable()
                    || "java-suspendable-operator".equals(sourceKind);
            String effect = operator.capabilities().effect();
            String idempotency = operator.capabilities().idempotency();
            PolicyProfile policy = policyProfile(operator.policy());
            ReadinessProfile readiness = readinessProfile(operator, diagnostics);
            return new OperatorProfile(
                    operator.display().name().isBlank() ? operator.operatorRef() : operator.display().name(),
                    operator.operatorRef(),
                    loweringMode,
                    inputPorts.size(),
                    outputPorts.size(),
                    inputStats.requiredCount(),
                    configFields.size(),
                    inputStats.fieldCount(),
                    outputStats.fieldCount(),
                    inputStats.dslUnsafeCount() + outputStats.dslUnsafeCount() + configUnsafe,
                    inputStats.dynamicSchemaCount() + outputStats.dynamicSchemaCount()
                            + OperatorLibraryProfile.dynamicSchemaCount(operator.configSchema().schema()),
                    streaming,
                    durable,
                    !"PURE".equals(effect),
                    "NON_IDEMPOTENT".equals(idempotency),
                    operator.capabilities().requiresSecrets(),
                    policy.restricted(),
                    DESIGN_ONLY.equals(readiness.state()),
                    readiness.state(),
                    readiness.level(),
                    readiness.title(),
                    readiness.summary(),
                    policy.summary(),
                    portUnionSummary(inputPorts),
                    portUnionSummary(outputPorts),
                    unionSummary(operator.configSchema()),
                    inputFields,
                    outputFields,
                    configFields
            );
        }
    }

    /**
     * Displayable schema field summary.
     *
     * @param port containing port, empty for config
     * @param path schema path, empty for root
     * @param required whether field is required
     * @param dslPathSafe whether path can be emitted safely into BLOGE DSL
     * @param title schema title annotation
     * @param description schema description annotation
     * @param examplesSummary compact schema examples annotation
     * @param defaultSummary compact schema default annotation
     * @param commentSummary compact schema $comment annotation
     */
    public record FieldProfile(
            String port,
            String path,
            boolean required,
            boolean dslPathSafe,
            String title,
            String description,
            String examplesSummary,
            String defaultSummary,
            String commentSummary
    ) {
        public FieldProfile {
            port = port == null ? "" : port;
            path = path == null ? "" : path;
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            examplesSummary = examplesSummary == null ? "" : examplesSummary;
            defaultSummary = defaultSummary == null ? "" : defaultSummary;
            commentSummary = commentSummary == null ? "" : commentSummary;
        }
    }

    private record FieldScan(String path, Map<String, Object> schema, boolean required, boolean dslPathSafe) {
    }

    private record PortStats(int fieldCount, int requiredCount, int dslUnsafeCount, int dynamicSchemaCount) {
        static PortStats empty() {
            return new PortStats(0, 0, 0, 0);
        }

        PortStats add(PortStats other) {
            return new PortStats(
                    fieldCount + other.fieldCount,
                    requiredCount + other.requiredCount,
                    dslUnsafeCount + other.dslUnsafeCount,
                    dynamicSchemaCount + other.dynamicSchemaCount
            );
        }
    }

    private record PolicyProfile(boolean restricted, String summary) {
    }

    private record ScopeProfile(boolean restricted, String label) {
    }

    private record ReadinessProfile(String state, String level, String title, String summary) {
    }

    private static final class Totals {
        private int inputPortCount;
        private int outputPortCount;
        private int requiredInputCount;
        private int configFieldCount;
        private int inputFieldCount;
        private int outputFieldCount;
        private int dslUnsafeFieldCount;
        private int dynamicSchemaCount;
        private int streamingOperatorCount;
        private int durableOperatorCount;
        private int externalOperatorCount;
        private int nonIdempotentOperatorCount;
        private int secretOperatorCount;
        private int policyRestrictedOperatorCount;
        private int designOnlyOperatorCount;
        private int runtimeExecutableOperatorCount;
        private int runtimeBlockedOperatorCount;
        private int governanceReviewOperatorCount;
        private int catalogRepairOperatorCount;

        private void add(OperatorProfile profile) {
            inputPortCount += profile.inputPortCount();
            outputPortCount += profile.outputPortCount();
            requiredInputCount += profile.requiredInputCount();
            configFieldCount += profile.configFieldCount();
            inputFieldCount += profile.inputFieldCount();
            outputFieldCount += profile.outputFieldCount();
            dslUnsafeFieldCount += profile.dslUnsafeFieldCount();
            dynamicSchemaCount += profile.dynamicSchemaCount();
            streamingOperatorCount += profile.streaming() ? 1 : 0;
            durableOperatorCount += profile.durable() ? 1 : 0;
            externalOperatorCount += profile.external() ? 1 : 0;
            nonIdempotentOperatorCount += profile.nonIdempotent() ? 1 : 0;
            secretOperatorCount += profile.requiresSecrets() ? 1 : 0;
            policyRestrictedOperatorCount += profile.policyRestricted() ? 1 : 0;
            designOnlyOperatorCount += profile.designOnly() ? 1 : 0;
            runtimeExecutableOperatorCount += RUNTIME_EXECUTABLE.equals(profile.runtimeReadinessState()) ? 1 : 0;
            runtimeBlockedOperatorCount += RUNTIME_BLOCKED.equals(profile.runtimeReadinessState()) ? 1 : 0;
            governanceReviewOperatorCount += GOVERNANCE_REVIEW.equals(profile.runtimeReadinessState()) ? 1 : 0;
            catalogRepairOperatorCount += CATALOG_REPAIR_REQUIRED.equals(profile.runtimeReadinessState()) ? 1 : 0;
        }
    }

    private static Map<Integer, List<VisualDiagnostic>> diagnosticsByOperator(List<VisualDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<VisualDiagnostic>> byOperator = new LinkedHashMap<>();
        for (VisualDiagnostic diagnostic : diagnostics) {
            Integer index = operatorIndexFromTarget(diagnostic == null ? "" : diagnostic.target());
            if (index == null) {
                continue;
            }
            byOperator.computeIfAbsent(index, ignored -> new ArrayList<>()).add(diagnostic);
        }
        return byOperator;
    }

    private static Integer operatorIndexFromTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String[] segments = target.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (!"operators".equals(segments[i])) {
                continue;
            }
            try {
                return Integer.parseInt(segments[i + 1]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static OperatorCatalogFacets facetsFrom(List<OperatorDefinition> operators,
                                                    List<OperatorProfile> profiles) {
        Map<String, Integer> sourceKinds = new TreeMap<>();
        Map<String, Integer> operatorLibraryIds = new TreeMap<>();
        Map<String, Integer> loweringModes = new TreeMap<>();
        Map<String, Integer> capabilities = new TreeMap<>();
        Map<String, Integer> runtimeReadinessStates = new TreeMap<>();
        for (int i = 0; i < operators.size(); i++) {
            OperatorDefinition operator = operators.get(i);
            OperatorProfile profile = profiles.get(i);
            increment(sourceKinds, OperatorCatalogFacets.normalizeFacetValue(operator.source().kind()));
            increment(operatorLibraryIds, operator.source().libraryId());
            increment(loweringModes, OperatorCatalogFacets.normalizeFacetValue(operator.lowering().mode()));
            for (String capability : OperatorCatalogFacets.capabilityValues(operator)) {
                increment(capabilities, capability);
            }
            increment(runtimeReadinessStates, profile.runtimeReadinessState());
        }
        return new OperatorCatalogFacets(
                profiles.size(),
                new LinkedHashMap<>(sourceKinds),
                new LinkedHashMap<>(operatorLibraryIds),
                new LinkedHashMap<>(loweringModes),
                new LinkedHashMap<>(capabilities),
                new LinkedHashMap<>(runtimeReadinessStates)
        );
    }

    private static ReadinessProfile readinessProfile(OperatorDefinition operator, List<VisualDiagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return new ReadinessProfile(
                    CATALOG_REPAIR_REQUIRED,
                    "error",
                    "Catalog repair required",
                    "Server validation found catalog errors that must be fixed before this operator can be imported."
            );
        }
        if (diagnostics.stream().anyMatch(diagnostic ->
                "visual.operator.lowering.operatorRefUnresolved".equals(diagnostic.code()))) {
            return new ReadinessProfile(
                    RUNTIME_BLOCKED,
                    "warning",
                    "Runtime binding unresolved",
                    "Native lowering points at an executable operatorRef that is not visible in the current runtime inventory."
            );
        }
        OperatorDefinition.RuntimeReadiness readiness = operator.runtimeReadiness();
        if (readiness == null) {
            return new ReadinessProfile("", "info", "", "");
        }
        return new ReadinessProfile(
                OperatorCatalogFacets.normalizeFacetValue(readiness.state()),
                readiness.level(),
                readiness.title(),
                readiness.summary()
        );
    }

    private static List<OperatorDefinition.Port> safePorts(List<OperatorDefinition.Port> ports) {
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        return ports.stream()
                .filter(port -> port != null)
                .toList();
    }

    private static PortStats portStats(OperatorDefinition.Port port, boolean output) {
        List<FieldScan> fields = schemaFields(port.schema());
        int fieldCount = fields.isEmpty() ? 1 : fields.size();
        int requiredCount = (int) fields.stream()
                .filter(FieldScan::required)
                .count();
        if (requiredCount == 0 && port.required()) {
            requiredCount = 1;
        }
        boolean portSafe = output ? outputPortDslPathSafe(port) : inputPortDslPathSafe(port);
        int unsafeCount = (int) fields.stream()
                .filter(field -> !field.dslPathSafe())
                .count();
        if (!portSafe) {
            unsafeCount += 1;
        }
        return new PortStats(fieldCount, requiredCount, unsafeCount, dynamicSchemaCount(port.schema().schema()));
    }

    private static List<FieldProfile> portFields(List<OperatorDefinition.Port> ports, boolean output) {
        List<FieldProfile> fields = new ArrayList<>();
        for (OperatorDefinition.Port port : ports) {
            List<FieldScan> scanned = schemaFields(port.schema());
            List<FieldScan> leaves = scanned.stream()
                    .filter(field -> !hasSchemaProperties(field.schema()))
                    .toList();
            List<FieldScan> preferred = leaves.isEmpty() ? scanned : leaves;
            boolean portSafe = output ? outputPortDslPathSafe(port) : inputPortDslPathSafe(port);
            if (preferred.isEmpty()) {
                fields.add(fieldProfile(port.name(), "", port.schema().schema(), port.required(), portSafe));
                continue;
            }
            for (FieldScan field : preferred) {
                fields.add(fieldProfile(port.name(), field.path(), field.schema(), field.required(),
                        field.dslPathSafe() && portSafe));
            }
        }
        return fields;
    }

    private static List<FieldProfile> configFields(SchemaEnvelope schema) {
        List<FieldScan> scanned = schemaFields(schema);
        List<FieldScan> leaves = scanned.stream()
                .filter(field -> !hasSchemaProperties(field.schema()))
                .toList();
        List<FieldScan> preferred = leaves.isEmpty() ? scanned : leaves;
        List<FieldScan> unionFields = scanned.stream()
                .filter(field -> hasUnionBranches(field.schema()))
                .toList();
        List<FieldProfile> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FieldScan field : unionFields) {
            if (seen.add(field.path())) {
                fields.add(fieldProfile("", field.path(), field.schema(), field.required(), field.dslPathSafe()));
            }
        }
        for (FieldScan field : preferred) {
            if (seen.add(field.path())) {
                fields.add(fieldProfile("", field.path(), field.schema(), field.required(), field.dslPathSafe()));
            }
        }
        return fields;
    }

    private static FieldProfile fieldProfile(String port,
                                             String path,
                                             Map<String, Object> schema,
                                             boolean required,
                                             boolean dslPathSafe) {
        Map<String, Object> safeSchema = schema == null ? Map.of() : schema;
        return new FieldProfile(
                port,
                path,
                required,
                dslPathSafe,
                stringAnnotation(safeSchema.get("title")),
                stringAnnotation(safeSchema.get("description")),
                examplesSummary(safeSchema.get("examples")),
                valueSummary(safeSchema.get("default")),
                stringAnnotation(safeSchema.get("$comment"))
        );
    }

    private static List<FieldScan> schemaFields(SchemaEnvelope schemaEnvelope) {
        Map<String, Object> schema = schemaEnvelope == null ? Map.of() : schemaEnvelope.schema();
        return schemaFieldsFromSchema(schema, "", true, true);
    }

    private static List<FieldScan> schemaFieldsFromSchema(Map<String, Object> schema,
                                                          String prefix,
                                                          boolean parentRequired,
                                                          boolean prefixDslPathSafe) {
        Map<String, Object> normalized = schema == null ? Map.of() : schema;
        Map<String, Object> properties = objectMap(normalized.get("properties"));
        Set<String> required = requiredSet(normalized.get("required"));
        List<FieldScan> fields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> child = objectMap(entry.getValue());
            String path = prefix.isBlank() ? name : prefix + "." + name;
            boolean fieldRequired = parentRequired && required.contains(name);
            boolean fieldDslPathSafe = prefixDslPathSafe && isDslFieldName(name);
            boolean hasNestedRequired = requiredSet(child.get("required")).isEmpty() == false;
            fields.add(new FieldScan(path, child, fieldRequired && !hasNestedRequired, fieldDslPathSafe));
            fields.addAll(schemaFieldsFromSchema(child, path, fieldRequired, fieldDslPathSafe));
        }
        if (!schemaTypeIncludesArray(normalized)) {
            return fields;
        }
        List<Map<String, Object>> itemSchemas = arrayItemSchemas(normalized);
        for (int i = 0; i < itemSchemas.size(); i++) {
            Map<String, Object> itemSchema = itemSchemas.get(i);
            String path = prefix.isBlank() ? String.valueOf(i) : prefix + "." + i;
            fields.add(new FieldScan(path, itemSchema, false, prefixDslPathSafe));
            fields.addAll(schemaFieldsFromSchema(itemSchema, path, false, prefixDslPathSafe));
        }
        return fields;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Set<String> requiredSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static boolean hasSchemaProperties(Map<String, Object> schema) {
        return !objectMap(schema.get("properties")).isEmpty();
    }

    private static boolean schemaTypeIncludesArray(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof String string) {
            return "array".equals(string);
        }
        if (type instanceof List<?> list) {
            return list.stream().map(String::valueOf).anyMatch("array"::equals);
        }
        return false;
    }

    private static List<Map<String, Object>> arrayItemSchemas(Map<String, Object> schema) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object prefixItems = schema.get("prefixItems");
        if (prefixItems instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> itemSchema = objectMap(item);
                if (!itemSchema.isEmpty()) {
                    items.add(itemSchema);
                }
            }
        }
        Map<String, Object> uniformItems = objectMap(schema.get("items"));
        if (!uniformItems.isEmpty()) {
            items.add(uniformItems);
        }
        return items;
    }

    private static int dynamicSchemaCount(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return 0;
        }
        int count = 0;
        if (!objectMap(schema.get("additionalProperties")).isEmpty()) {
            count += 1;
        }
        if (!objectMap(schema.get("unevaluatedProperties")).isEmpty()) {
            count += 1;
        }
        count += objectMap(schema.get("patternProperties")).size();
        for (Object child : objectMap(schema.get("properties")).values()) {
            count += dynamicSchemaCount(objectMap(child));
        }
        Object prefixItems = schema.get("prefixItems");
        if (prefixItems instanceof List<?> list) {
            for (Object item : list) {
                count += dynamicSchemaCount(objectMap(item));
            }
        }
        count += dynamicSchemaCount(objectMap(schema.get("items")));
        return count;
    }

    private static boolean hasUnionBranches(Map<String, Object> schema) {
        return unionBranchCount(schema, "oneOf") > 0 || unionBranchCount(schema, "anyOf") > 0;
    }

    private static String portUnionSummary(List<OperatorDefinition.Port> ports) {
        List<String> summaries = new ArrayList<>();
        for (OperatorDefinition.Port port : ports) {
            for (String summary : unionDescriptors(port.schema().schema(), "")) {
                summaries.add(port.name() + "." + summary);
            }
        }
        return visibleSummary(summaries, 3);
    }

    private static String unionSummary(SchemaEnvelope schema) {
        return visibleSummary(unionDescriptors(schema == null ? Map.of() : schema.schema(), ""), 4);
    }

    private static List<String> unionDescriptors(Map<String, Object> schema, String path) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        List<String> descriptors = new ArrayList<>();
        addUnionDescriptor(descriptors, schema, path, "oneOf");
        addUnionDescriptor(descriptors, schema, path, "anyOf");
        for (Map.Entry<String, Object> entry : objectMap(schema.get("properties")).entrySet()) {
            descriptors.addAll(unionDescriptors(objectMap(entry.getValue()),
                    path.isBlank() ? entry.getKey() : path + "." + entry.getKey()));
        }
        Map<String, Object> items = objectMap(schema.get("items"));
        if (!items.isEmpty()) {
            descriptors.addAll(unionDescriptors(items, path.isBlank() ? "[]" : path + "[]"));
        }
        Object prefixItems = schema.get("prefixItems");
        if (prefixItems instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                descriptors.addAll(unionDescriptors(objectMap(list.get(i)),
                        (path.isBlank() ? "[]" : path) + "[" + i + "]"));
            }
        }
        return descriptors;
    }

    private static void addUnionDescriptor(List<String> descriptors,
                                           Map<String, Object> schema,
                                           String path,
                                           String keyword) {
        int count = unionBranchCount(schema, keyword);
        if (count == 0) {
            return;
        }
        descriptors.add((path.isBlank() ? "(root)" : path) + " " + keyword + "<" + count + ">");
    }

    private static int unionBranchCount(Map<String, Object> schema, String keyword) {
        Object branches = schema.get(keyword);
        if (!(branches instanceof List<?> list)) {
            return 0;
        }
        return (int) list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .count();
    }

    private static String stringAnnotation(Object value) {
        return compactText(value instanceof String text ? text : "");
    }

    private static String examplesSummary(Object value) {
        if (value instanceof List<?> list) {
            List<String> examples = list.stream()
                    .map(OperatorLibraryProfile::valueSummary)
                    .filter(item -> !item.isBlank())
                    .toList();
            return visibleSummary(examples, 2);
        }
        return valueSummary(value);
    }

    private static String valueSummary(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return compactText(text);
        }
        return compactText(String.valueOf(value));
    }

    private static String compactText(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 117) + "...";
    }

    private static String visibleSummary(List<String> values, int limit) {
        if (values.isEmpty()) {
            return "";
        }
        int visible = Math.min(values.size(), limit);
        String summary = String.join(", ", values.subList(0, visible));
        int remaining = values.size() - visible;
        return remaining > 0 ? summary + " +" + remaining + " more" : summary;
    }

    private static PolicyProfile policyProfile(OperatorDefinition.Policy policy) {
        ScopeProfile tenants = policyScope(policy.tenants());
        ScopeProfile namespaces = policyScope(policy.namespaces());
        ScopeProfile environments = policyScope(policy.environments());
        List<String> parts = new ArrayList<>();
        if (tenants.restricted()) {
            parts.add("tenants " + tenants.label());
        }
        if (namespaces.restricted()) {
            parts.add("namespaces " + namespaces.label());
        }
        if (environments.restricted()) {
            parts.add("env " + environments.label());
        }
        return new PolicyProfile(!parts.isEmpty(), String.join("; ", parts));
    }

    private static ScopeProfile policyScope(List<String> scope) {
        List<String> normalized = scope == null ? List.of() : scope.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        boolean restricted = !normalized.isEmpty() && !normalized.contains("*");
        List<String> visible = normalized.stream().limit(3).toList();
        String label = String.join(", ", visible);
        if (normalized.size() > visible.size()) {
            label += " +" + (normalized.size() - visible.size());
        }
        return new ScopeProfile(restricted, label);
    }

    private static boolean inputPortDslPathSafe(OperatorDefinition.Port port) {
        return isDslFieldName(port.name());
    }

    private static boolean outputPortDslPathSafe(OperatorDefinition.Port port) {
        return "output".equals(port.name()) || isDslFieldName(port.name());
    }

    private static boolean isDslFieldName(String value) {
        return value != null && DSL_FIELD_IDENTIFIER.matcher(value).matches()
                && !RESERVED_DSL_FIELD_NAMES.contains(value);
    }

    private static void increment(Map<String, Integer> counts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        counts.merge(value, 1, Integer::sum);
    }
}
