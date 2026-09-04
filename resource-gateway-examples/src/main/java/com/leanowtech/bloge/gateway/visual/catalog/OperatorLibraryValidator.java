package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.StaticExpressionLiteral;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.compatibilityReason;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.schemaCompatibilityIssue;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.schemaTypeLabel;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.staticExpressionLiteral;

/**
 * Validates user-provided operator libraries before they enter the visual catalog.
 */
@Service
public class OperatorLibraryValidator {

    private static final Set<String> RESERVED_OPERATOR_REFS = Set.of(
            "httpResource",
            "bloge:decisionTable",
            "bloge:transform",
            "bloge:scenarioCall",
            "bloge:instructionCall",
            VisualGraphPublicationOperator.NAME
    );
    private static final Set<String> RESERVED_OPERATOR_REF_PREFIXES = Set.of(
            "resource:",
            "publication:"
    );
    private static final Set<String> RESERVED_EXECUTABLE_OPERATOR_REFS = Set.of(
            "httpResource",
            "bloge:decisionTable",
            "bloge:transform",
            "bloge:scenarioCall",
            "bloge:instructionCall",
            VisualGraphPublicationOperator.NAME
    );
    private static final Set<String> RESERVED_EXECUTABLE_OPERATOR_REF_PREFIXES = Set.of(
            "resource:",
            "publication:"
    );
    private static final Set<String> SUPPORTED_LIBRARY_SCHEMA_VERSIONS = Set.of(
            "bloge.visualOperatorLibrary.v1"
    );
    private static final Set<String> SUPPORTED_OPERATOR_SCHEMA_VERSIONS = Set.of(
            "bloge.visualOperator.v1"
    );
    private static final Set<String> SUPPORTED_LOWERING_MODES = Set.of(
            "native",
            "transform",
            "branch",
            "design",
            "remote-worker",
            "ai-tool",
            "event-source",
            "message-handler",
            "webhook"
    );
    private static final Set<String> SUPPORTED_CAPABILITY_EFFECTS = Set.of(
            "PURE",
            "EXTERNAL",
            "READ_EXTERNAL",
            "WRITE_EXTERNAL"
    );
    private static final Set<String> SUPPORTED_CAPABILITY_IDEMPOTENCY = Set.of(
            "DETERMINISTIC",
            "IDEMPOTENT",
            "NON_IDEMPOTENT",
            "UNKNOWN"
    );
    private static final Set<String> SUPPORTED_FUNCTION_TYPES = Set.of(
            "any",
            "unknown",
            "string",
            "number",
            "integer",
            "boolean",
            "object",
            "array",
            "json",
            "date",
            "datetime"
    );
    private static final Set<String> RESERVED_SOURCE_KINDS = Set.of(
            "resource-descriptor",
            "visual-publication",
            "java-operator",
            "java-streaming-operator",
            "java-suspendable-operator"
    );
    private static final Set<String> SUPPORTED_IMPORTED_SOURCE_KINDS = Set.of(
            "user-library",
            "remote-worker",
            "ai-tool",
            "event-source",
            "message-handler",
            "webhook"
    );
    private static final Set<String> NON_EXECUTABLE_BINDING_SOURCE_KINDS = Set.of(
            "remote-worker",
            "ai-tool",
            "event-source",
            "message-handler",
            "webhook"
    );
    private static final Set<String> NON_EXECUTABLE_LOWERING_MODES = Set.of(
            "design",
            "remote-worker",
            "ai-tool",
            "event-source",
            "message-handler",
            "webhook"
    );
    private static final Set<String> EXECUTION_CONFIG_KEYS = Set.of("timeout", "retryAttempts");
    private static final Set<String> SERVER_MANAGED_LOWERING_PARAMETER_KEYS = Set.of(
            "runtimeBindingApplyKind",
            "runtimeBindingId",
            "adapterActivationId",
            "executableLoweringIntegrationId",
            "integrationRevision"
    );
    private static final Set<String> RESERVED_DSL_FIELD_NAMES = Set.of(
            "graph", "node", "branch", "decision_table", "on", "input", "depends_on",
            "timeout", "retry", "fallback", "execution_mode", "worker_topic", "compensate",
            "saga", "true", "false", "schema", "output", "otherwise", "when", "transform",
            "foreach", "sequential", "in", "loop", "parallel", "until", "carry", "wait",
            "after", "await", "event", "where", "mode", "stream", "streaming", "buffer",
            "let", "import", "as", "script", "exit", "exhausted"
    );
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String ARRAY_INDEX_PATTERN = "\\d+";
    private static final String TEMPLATE_PATH_SEGMENT_PATTERN = "(?:" + IDENTIFIER_PATTERN + "|"
            + ARRAY_INDEX_PATTERN + ")";
    private static final String TEMPLATE_PATH_PATTERN = TEMPLATE_PATH_SEGMENT_PATTERN
            + "(?:\\." + TEMPLATE_PATH_SEGMENT_PATTERN + ")*";
    private static final Pattern VISUAL_OPERATOR_REF = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:(?::|\\.|-)" + IDENTIFIER_PATTERN + ")*");
    private static final Pattern VISUAL_LIBRARY_ID = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:(?::|\\.|-)" + IDENTIFIER_PATTERN + ")*");
    private static final Pattern VERSION_TOKEN = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
    private static final Pattern PORT_NAME = Pattern.compile(IDENTIFIER_PATTERN);
    private static final Pattern EXECUTABLE_OPERATOR_REF = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:(?::|\\.|-)" + IDENTIFIER_PATTERN + ")*");
    private static final Pattern FUNCTION_NAME = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:\\." + IDENTIFIER_PATTERN + ")*");
    private static final Pattern PATH_PATTERN = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:\\." + IDENTIFIER_PATTERN + ")*");
    private static final Pattern ARRAY_INDEX = Pattern.compile(ARRAY_INDEX_PATTERN);
    private static final Pattern TEMPLATE_REFERENCE = Pattern.compile("\\{\\{\\s*((?:input\\.)?"
            + TEMPLATE_PATH_PATTERN + ")\\s*}}");
    private static final Pattern PURE_TEMPLATE_REFERENCE = Pattern.compile("^\\{\\{\\s*((?:input\\.)?"
            + TEMPLATE_PATH_PATTERN + ")\\s*}}$");
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{([^}]*)}}");

    /**
     * @param library user-provided library
     * @return structured validation result
     */
    public VisualValidationResult validate(OperatorLibrary library) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (library == null) {
            diagnostics.add(VisualDiagnostic.error("visual.library.missing",
                    "Operator library is required.",
                    "/"));
            return new VisualValidationResult(false, diagnostics);
        }
        boolean hasOperator = library.operators().stream().anyMatch(java.util.Objects::nonNull);
        boolean hasFunction = library.builtInFunctions().stream().anyMatch(java.util.Objects::nonNull);
        if (!SUPPORTED_LIBRARY_SCHEMA_VERSIONS.contains(library.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error("visual.library.schemaVersion.unsupported",
                    "Operator library schemaVersion '%s' is unsupported; visual authoring supports %s."
                            .formatted(library.schemaVersion(), SUPPORTED_LIBRARY_SCHEMA_VERSIONS),
                    "/schemaVersion"));
        }
        if (library.libraryId().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.id.required",
                    "Operator library must declare a libraryId.",
                    "/libraryId"));
        } else if (!VISUAL_LIBRARY_ID.matcher(library.libraryId()).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.id.invalid",
                    "Operator library id '%s' must be a namespace-safe token."
                            .formatted(library.libraryId()),
                    "/libraryId"));
        }
        if (!VERSION_TOKEN.matcher(library.version()).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.version.invalid",
                    "Operator library version '%s' must use semantic version form MAJOR.MINOR.PATCH."
                            .formatted(library.version()),
                    "/version"));
        }
        if (!OperatorLibrary.isSupportedStatus(library.status())) {
            diagnostics.add(VisualDiagnostic.error("visual.library.status.unsupported",
                    "Operator library status '%s' must be one of ACTIVE, DEPRECATED, or DISABLED."
                            .formatted(library.status()),
                    "/status"));
        }
        validateBuiltInFunctions(library.builtInFunctions(), diagnostics);
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            String operatorPath = "/operators/" + i;
            if (operator == null) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.missing",
                        "Operator library contains a null operator entry.",
                        operatorPath));
                continue;
            }
            if (!SUPPORTED_OPERATOR_SCHEMA_VERSIONS.contains(operator.schemaVersion())) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.schemaVersion.unsupported",
                        "Operator '%s' schemaVersion '%s' is unsupported; visual authoring supports %s."
                                .formatted(operator.operatorRef(), operator.schemaVersion(),
                                        SUPPORTED_OPERATOR_SCHEMA_VERSIONS),
                        operatorPath + "/schemaVersion"));
            }
            if (!VERSION_TOKEN.matcher(operator.operatorVersion()).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.version.invalid",
                        "Operator '%s' version '%s' must use semantic version form MAJOR.MINOR.PATCH."
                                .formatted(operator.operatorRef(), operator.operatorVersion()),
                        operatorPath + "/operatorVersion"));
            }
            if (operator.operatorRef().isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.ref.required",
                        "Operator must declare an operatorRef.",
                        operatorPath + "/operatorRef"));
            } else {
                if (isReservedOperatorRef(operator.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.error("visual.operator.ref.reserved",
                            "OperatorRef '%s' is reserved by built-in or resource-backed visual operators."
                                    .formatted(operator.operatorRef()),
                            operatorPath + "/operatorRef"));
                }
                if (!VISUAL_OPERATOR_REF.matcher(operator.operatorRef()).matches()) {
                    diagnostics.add(VisualDiagnostic.error("visual.operator.ref.invalid",
                            "OperatorRef '%s' must be a namespace-safe visual operator token."
                                    .formatted(operator.operatorRef()),
                            operatorPath + "/operatorRef"));
                }
                if (!operatorRefs.add(operator.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.error("visual.operator.ref.duplicate",
                            "Operator library declares duplicate operatorRef '%s'."
                                    .formatted(operator.operatorRef()),
                            operatorPath + "/operatorRef"));
                }
            }
            validateOperator(operator, operatorPath, diagnostics);
        }
        if (!hasOperator && !hasFunction) {
            diagnostics.add(VisualDiagnostic.error("visual.library.empty",
                    "Operator library must contain at least one operator or built-in function.",
                    "/"));
        }
        return new VisualValidationResult(true, diagnostics);
    }

    private static void validateBuiltInFunctions(List<OperatorLibrary.BuiltInFunction> functions,
                                                 List<VisualDiagnostic> diagnostics) {
        Set<String> callableNames = new LinkedHashSet<>();
        for (int i = 0; i < functions.size(); i++) {
            OperatorLibrary.BuiltInFunction function = functions.get(i);
            String path = "/builtInFunctions/" + i;
            if (function == null) {
                diagnostics.add(VisualDiagnostic.error("visual.function.missing",
                        "Operator library contains a null built-in function entry.",
                        path));
                continue;
            }
            if (function.name().isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.function.name.required",
                        "Built-in function entries must declare a name.",
                        path + "/name"));
            } else if (!FUNCTION_NAME.matcher(function.name()).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.function.name.invalid",
                        "Built-in function name '%s' must be a callable BLOGE expression token."
                                .formatted(function.name()),
                        path + "/name"));
            }
            if (!function.namespace().isBlank()
                    && !VISUAL_LIBRARY_ID.matcher(function.namespace()).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.function.namespace.invalid",
                        "Built-in function namespace '%s' must be a namespace-safe token."
                                .formatted(function.namespace()),
                        path + "/namespace"));
            }
            if (!function.name().isBlank() && !callableNames.add(function.name())) {
                diagnostics.add(VisualDiagnostic.error("visual.function.name.duplicate",
                        "Operator library declares duplicate callable function name '%s'; namespace is provenance metadata and does not change expression resolution."
                                .formatted(function.name()),
                        path + "/name"));
            }
            if (function.signatures().isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.function.signature.required",
                        "Built-in function '%s' must declare at least one signature."
                                .formatted(function.name()),
                        path + "/signatures"));
            }
            for (int signatureIndex = 0; signatureIndex < function.signatures().size(); signatureIndex++) {
                validateFunctionSignature(function, function.signatures().get(signatureIndex),
                        path + "/signatures/" + signatureIndex, diagnostics);
            }
        }
    }

    private static void validateFunctionSignature(OperatorLibrary.BuiltInFunction function,
                                                  OperatorLibrary.Signature signature,
                                                  String path,
                                                  List<VisualDiagnostic> diagnostics) {
        if (signature == null) {
            diagnostics.add(VisualDiagnostic.error("visual.function.signature.missing",
                    "Built-in function '%s' contains a null signature entry."
                            .formatted(function.name()),
                    path));
            return;
        }
        if (signature.label().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.function.signature.label.required",
                    "Built-in function '%s' signature must declare a display label."
                            .formatted(function.name()),
                    path + "/label"));
        }
        validateFunctionType(function, signature.returns().type(), path + "/returns/type", diagnostics);
        if (signature.returns().schema() != null) {
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                    signature.returns().schema(), path + "/returns/schema"));
        }
        Set<String> parameterNames = new LinkedHashSet<>();
        for (int i = 0; i < signature.parameters().size(); i++) {
            OperatorLibrary.Parameter parameter = signature.parameters().get(i);
            String parameterPath = path + "/parameters/" + i;
            if (parameter == null) {
                diagnostics.add(VisualDiagnostic.error("visual.function.parameter.missing",
                        "Built-in function '%s' contains a null parameter entry."
                                .formatted(function.name()),
                        parameterPath));
                continue;
            }
            if (parameter.name().isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.function.parameter.name.required",
                        "Built-in function '%s' parameter must declare a name."
                                .formatted(function.name()),
                        parameterPath + "/name"));
            } else if (!PORT_NAME.matcher(parameter.name()).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.function.parameter.name.invalid",
                        "Built-in function '%s' parameter '%s' must be a single identifier token."
                                .formatted(function.name(), parameter.name()),
                        parameterPath + "/name"));
            } else if (!parameterNames.add(parameter.name())) {
                diagnostics.add(VisualDiagnostic.error("visual.function.parameter.name.duplicate",
                        "Built-in function '%s' declares duplicate parameter '%s'."
                                .formatted(function.name(), parameter.name()),
                        parameterPath + "/name"));
            }
            if (parameter.variadic() && i != signature.parameters().size() - 1) {
                diagnostics.add(VisualDiagnostic.error("visual.function.parameter.variadicPosition",
                        "Built-in function '%s' variadic parameter '%s' must be the final parameter."
                                .formatted(function.name(), parameter.name()),
                        parameterPath + "/variadic"));
            }
            validateFunctionType(function, parameter.type(), parameterPath + "/type", diagnostics);
            if (parameter.schema() != null) {
                diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                        parameter.schema(), parameterPath + "/schema"));
            }
        }
    }

    private static void validateFunctionType(OperatorLibrary.BuiltInFunction function,
                                             String type,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        if (SUPPORTED_FUNCTION_TYPES.contains(type)) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.function.type.unsupported",
                "Built-in function '%s' uses unsupported compact type '%s'; supported types are %s."
                        .formatted(function.name(), type, SUPPORTED_FUNCTION_TYPES),
                path));
    }

    private static boolean isReservedOperatorRef(String operatorRef) {
        return RESERVED_OPERATOR_REFS.contains(operatorRef)
                || RESERVED_OPERATOR_REF_PREFIXES.stream().anyMatch(operatorRef::startsWith);
    }

    private static void validateOperator(OperatorDefinition operator,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        if (!"branch".equals(operator.lowering().mode()) && operator.ports().outputs().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.output.required",
                    "Operator '%s' must declare at least one output port.".formatted(operator.operatorRef()),
                    path + "/ports/outputs"));
        }
        validatePorts(operator, "inputs", operator.ports().inputs(), path + "/ports/inputs", diagnostics);
        validatePorts(operator, "outputs", operator.ports().outputs(), path + "/ports/outputs", diagnostics);
        if (requiresExecutableDslSafeFields(operator.lowering().mode())) {
            validateInputDslFieldNames(operator, path, diagnostics);
            validateOutputDslFieldNames(operator, path, diagnostics);
        }
        diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                operator.configSchema(), path + "/configSchema"));
        validateServerManagedDiagnostics(operator, path + "/diagnostics", diagnostics);
        validateSource(operator, path + "/source", diagnostics);
        validateCapabilities(operator, path + "/capabilities", diagnostics);
        validatePolicy(operator, path + "/policy", diagnostics);
        validateLowering(operator, path + "/lowering", diagnostics);
        diagnostics.addAll(VisualSecretGuard.detectOperatorSecrets(operator, path));
    }

    private static void validateServerManagedDiagnostics(OperatorDefinition operator,
                                                         String path,
                                                         List<VisualDiagnostic> diagnostics) {
        if (operator.diagnostics().isEmpty()) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.diagnostics.managed",
                "Operator '%s' cannot declare diagnostics in an imported operator library; diagnostics are assigned by the visual catalog."
                        .formatted(operator.operatorRef()),
                path));
    }

    private static void validateSource(OperatorDefinition operator,
                                       String path,
                                       List<VisualDiagnostic> diagnostics) {
        if (RESERVED_SOURCE_KINDS.contains(operator.source().kind())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.source.kind.reserved",
                    "Operator '%s' cannot use system-managed source kind '%s' in an imported operator library."
                            .formatted(operator.operatorRef(), operator.source().kind()),
                    path + "/kind"));
            return;
        }
        if (!SUPPORTED_IMPORTED_SOURCE_KINDS.contains(operator.source().kind())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.source.kind.unsupported",
                    "Operator '%s' source kind '%s' is unsupported for imported operator libraries; supported source kinds are %s."
                            .formatted(operator.operatorRef(), operator.source().kind(),
                                    SUPPORTED_IMPORTED_SOURCE_KINDS),
                    path + "/kind"));
        }
        if (NON_EXECUTABLE_BINDING_SOURCE_KINDS.contains(operator.source().kind())
                && !operator.source().kind().equals(operator.lowering().mode())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.source.loweringModeMismatch",
                    "Operator '%s' uses source.kind='%s' but lowering.mode is '%s'; non-executable binding source kinds must use the matching lowering mode."
                            .formatted(operator.operatorRef(), operator.source().kind(),
                                    operator.lowering().mode()),
                    path + "/kind"));
        }
    }

    private static boolean requiresExecutableDslSafeFields(String loweringMode) {
        return !NON_EXECUTABLE_LOWERING_MODES.contains(loweringMode);
    }

    private static void validateCapabilities(OperatorDefinition operator,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        OperatorDefinition.Capabilities capabilities = operator.capabilities();
        if (!SUPPORTED_CAPABILITY_EFFECTS.contains(capabilities.effect())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.capability.effectUnsupported",
                    "Operator '%s' declares unsupported capability effect '%s'; supported effects are %s."
                            .formatted(operator.operatorRef(), capabilities.effect(), SUPPORTED_CAPABILITY_EFFECTS),
                    path + "/effect"));
        }
        if (!SUPPORTED_CAPABILITY_IDEMPOTENCY.contains(capabilities.idempotency())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.capability.idempotencyUnsupported",
                    "Operator '%s' declares unsupported capability idempotency '%s'; supported idempotency values are %s."
                            .formatted(operator.operatorRef(), capabilities.idempotency(),
                                    SUPPORTED_CAPABILITY_IDEMPOTENCY),
                    path + "/idempotency"));
        }
        if (capabilities.streaming()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.capability.streamingRequiresRuntime",
                    "Operator '%s' declares streaming=true; the current visual authoring runtime is request-response only, so drafts using this operator will be blocked until a streaming runtime is enabled."
                            .formatted(operator.operatorRef()),
                    path + "/streaming"));
        }
        if (capabilities.durable()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.capability.durableRequiresRuntime",
                    "Operator '%s' declares durable=true; the current visual authoring runtime is request-response only, so drafts using this operator will be blocked until durable/suspendable execution is enabled."
                            .formatted(operator.operatorRef()),
                    path + "/durable"));
        }
        OperatorDefinition.SideEffectProtocol protocol = capabilities.sideEffectProtocol();
        if (!Set.of("NOT_APPLICABLE", "UNDECLARED", "JOURNALED").contains(protocol.mode())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.sideEffectProtocol.modeUnsupported",
                    "Operator '%s' declares unsupported side-effect protocol mode '%s'."
                            .formatted(operator.operatorRef(), protocol.mode()),
                    path + "/sideEffectProtocol/mode"));
        } else if (capabilities.externalWrite() && "JOURNALED".equals(protocol.mode())
                && !protocol.managedWrite()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.sideEffectProtocol.incomplete",
                    "Operator '%s' declares JOURNALED mode but is missing receipt, reconciliation, reconciler, or source mappings."
                            .formatted(operator.operatorRef()),
                    path + "/sideEffectProtocol"));
        } else if (capabilities.externalWrite() && !protocol.managedWrite()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.sideEffectProtocol.required",
                    "Operator '%s' declares WRITE_EXTERNAL without bloge.sideEffectProtocol.v1; it can be imported for DESIGN authoring but cannot run or publish as EXECUTABLE."
                            .formatted(operator.operatorRef()),
                    path + "/sideEffectProtocol"));
        }
        if (capabilities.requiresSecrets()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.governance.requiresSecrets",
                    "Operator '%s' requires secret-backed execution; review secretRef binding and access controls before importing this operator library."
                            .formatted(operator.operatorRef()),
                    path + "/requiresSecrets"));
        }
        if (!"PURE".equals(capabilities.effect())
                && "NON_IDEMPOTENT".equals(capabilities.idempotency())) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.governance.nonIdempotent",
                    "Operator '%s' declares non-idempotent side effects; review retry, audit, and approval controls before importing this operator library."
                            .formatted(operator.operatorRef()),
                    path + "/idempotency"));
        }
    }

    private static void validatePolicy(OperatorDefinition operator,
                                       String path,
                                       List<VisualDiagnostic> diagnostics) {
        validatePolicyScope(operator, "tenants", operator.policy().tenants(), path + "/tenants", diagnostics);
        validatePolicyScope(operator, "namespaces", operator.policy().namespaces(), path + "/namespaces", diagnostics);
        validatePolicyScope(operator, "environments", operator.policy().environments(), path + "/environments",
                diagnostics);
    }

    private static void validatePolicyScope(OperatorDefinition operator,
                                            String scopeName,
                                            List<String> scope,
                                            String path,
                                            List<VisualDiagnostic> diagnostics) {
        if (scope.size() > 1 && scope.contains("*")) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.policy.scopeWildcardMixed",
                    "Operator '%s' policy.%s mixes wildcard '*' with concrete values; use either '*' or explicit values."
                            .formatted(operator.operatorRef(), scopeName),
                    path));
        }
    }

    private static void validatePorts(OperatorDefinition operator,
                                      String direction,
                                      List<OperatorDefinition.Port> ports,
                                      String path,
                                      List<VisualDiagnostic> diagnostics) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < ports.size(); i++) {
            OperatorDefinition.Port port = ports.get(i);
            if (port == null) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.port.missing",
                        "Operator '%s' declares a null %s port entry."
                                .formatted(operator.operatorRef(), direction),
                        path + "/" + i));
                continue;
            }
            if (!PORT_NAME.matcher(port.name()).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.port.name.invalid",
                        "Operator '%s' declares %s port '%s', but port names must be single identifier tokens."
                                .formatted(operator.operatorRef(), direction, port.name()),
                        path + "/" + i + "/name"));
            }
            if (!seen.add(port.name())) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.port.duplicate",
                        "Operator '%s' declares duplicate %s port '%s'."
                                .formatted(operator.operatorRef(), direction, port.name()),
                        path + "/" + i + "/name"));
            }
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                    port.schema(), path + "/" + i + "/schema"));
        }
    }

    private static void validateInputDslFieldNames(OperatorDefinition operator,
                                                   String operatorPath,
                                                   List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < operator.ports().inputs().size(); i++) {
            OperatorDefinition.Port port = operator.ports().inputs().get(i);
            if (port == null) {
                continue;
            }
            String portPath = operatorPath + "/ports/inputs/" + i;
            if (!isDslFieldName(port.name())) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.dslField.invalid",
                        "Operator '%s' input port '%s' cannot be rendered as a BLOGE DSL input field."
                                .formatted(operator.operatorRef(), port.name()),
                        portPath + "/name"));
            }
            validateDslSchemaPropertyNames(operator,
                    port.schema().schema(),
                    portPath + "/schema/schema",
                    Set.of(),
                    diagnostics);
        }
    }

    private static void validateOutputDslFieldNames(OperatorDefinition operator,
                                                    String operatorPath,
                                                    List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < operator.ports().outputs().size(); i++) {
            OperatorDefinition.Port port = operator.ports().outputs().get(i);
            if (port == null) {
                continue;
            }
            String portPath = operatorPath + "/ports/outputs/" + i;
            if (!"output".equals(port.name()) && !isDslFieldName(port.name())) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.dslField.invalid",
                        "Operator '%s' output port '%s' cannot be rendered as a BLOGE DSL output path segment."
                                .formatted(operator.operatorRef(), port.name()),
                        portPath + "/name"));
            }
            validateDslSchemaPropertyNames(operator,
                    port.schema().schema(),
                    portPath + "/schema/schema",
                    Set.of(),
                    diagnostics);
        }
    }

    private static void validateLowering(OperatorDefinition operator,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        String mode = operator.lowering().mode();
        if (!SUPPORTED_LOWERING_MODES.contains(mode)) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.unsupported",
                    "Operator '%s' uses unsupported lowering mode '%s'."
                            .formatted(operator.operatorRef(), mode),
                    path + "/mode"));
            return;
        }
        validateServerManagedLoweringParameters(operator, path, diagnostics);
        if ("native".equals(mode)) {
            validateNativeLowering(operator, path, diagnostics);
            return;
        }
        if ("branch".equals(mode)) {
            validateBranchLowering(operator, path, diagnostics);
            return;
        }
        if ("design".equals(mode)) {
            validateDesignLowering(operator, path, diagnostics);
            return;
        }
        if ("remote-worker".equals(mode)) {
            validateRemoteWorkerLowering(operator, path, diagnostics);
            return;
        }
        if ("ai-tool".equals(mode)) {
            validateAiToolLowering(operator, path, diagnostics);
            return;
        }
        if ("event-source".equals(mode)) {
            validateEventSourceLowering(operator, path, diagnostics);
            return;
        }
        if ("message-handler".equals(mode)) {
            validateMessageHandlerLowering(operator, path, diagnostics);
            return;
        }
        if ("webhook".equals(mode)) {
            validateWebhookLowering(operator, path, diagnostics);
            return;
        }
        validateTransformLowering(operator, path, diagnostics);
    }

    private static void validateServerManagedLoweringParameters(OperatorDefinition operator,
                                                                String path,
                                                                List<VisualDiagnostic> diagnostics) {
        for (String parameterKey : SERVER_MANAGED_LOWERING_PARAMETER_KEYS) {
            if (!operator.lowering().parameters().containsKey(parameterKey)) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.parameter.managed",
                    "Operator '%s' cannot declare server-managed lowering.parameters.%s in an imported operator library; runtime-binding apply writes this field after evidence review."
                            .formatted(operator.operatorRef(), parameterKey),
                    path + "/parameters/" + parameterKey,
                    Map.of("parameter", parameterKey)));
        }
    }

    private static void validateDesignLowering(OperatorDefinition operator,
                                               String path,
                                               List<VisualDiagnostic> diagnostics) {
        if (!operator.lowering().operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.designOperatorRefUnsupported",
                    "Design-only operator '%s' must not declare lowering.operatorRef because it has no executable binding."
                            .formatted(operator.operatorRef()),
                    path + "/operatorRef"));
        }
    }

    private static void validateRemoteWorkerLowering(OperatorDefinition operator,
                                                     String path,
                                                     List<VisualDiagnostic> diagnostics) {
        validateRuntimeBindingSourceKind(operator, path, "remote-worker", diagnostics);
        validateRuntimeBindingOperatorRefBlank(operator, path, "remote worker", diagnostics);
        validateRequiredStringParameter(operator, path, "workerTopic", "remote worker topic", diagnostics);
    }

    private static void validateAiToolLowering(OperatorDefinition operator,
                                               String path,
                                               List<VisualDiagnostic> diagnostics) {
        validateRuntimeBindingSourceKind(operator, path, "ai-tool", diagnostics);
        validateRuntimeBindingOperatorRefBlank(operator, path, "AI tool", diagnostics);
        validateRequiredStringParameter(operator, path, "toolRef", "AI tool reference", diagnostics);
    }

    private static void validateEventSourceLowering(OperatorDefinition operator,
                                                    String path,
                                                    List<VisualDiagnostic> diagnostics) {
        validateRuntimeBindingSourceKind(operator, path, "event-source", diagnostics);
        validateRuntimeBindingOperatorRefBlank(operator, path, "event source", diagnostics);
        validateRequiredStringParameter(operator, path, "eventType", "event type", diagnostics);
    }

    private static void validateMessageHandlerLowering(OperatorDefinition operator,
                                                       String path,
                                                       List<VisualDiagnostic> diagnostics) {
        validateRuntimeBindingSourceKind(operator, path, "message-handler", diagnostics);
        validateRuntimeBindingOperatorRefBlank(operator, path, "message handler", diagnostics);
        validateRequiredStringParameter(operator, path, "channel", "message channel", diagnostics);
    }

    private static void validateWebhookLowering(OperatorDefinition operator,
                                                String path,
                                                List<VisualDiagnostic> diagnostics) {
        validateRuntimeBindingSourceKind(operator, path, "webhook", diagnostics);
        validateRuntimeBindingOperatorRefBlank(operator, path, "webhook", diagnostics);
        validateRequiredStringParameter(operator, path, "method", "webhook HTTP method", diagnostics);
        validateRequiredStringParameter(operator, path, "path", "webhook path", diagnostics);
    }

    private static void validateRuntimeBindingSourceKind(OperatorDefinition operator,
                                                         String path,
                                                         String expectedSourceKind,
                                                         List<VisualDiagnostic> diagnostics) {
        if (expectedSourceKind.equals(operator.source().kind())) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.sourceKindMismatch",
                "Operator '%s' uses lowering.mode=%s but source.kind is '%s'; use source.kind='%s' so catalog facets and readiness are auditable."
                        .formatted(operator.operatorRef(), expectedSourceKind, operator.source().kind(),
                                expectedSourceKind),
                operatorPath(path) + "/source/kind"));
    }

    private static void validateRuntimeBindingOperatorRefBlank(OperatorDefinition operator,
                                                               String path,
                                                               String bindingLabel,
                                                               List<VisualDiagnostic> diagnostics) {
        if (operator.lowering().operatorRef().isBlank()) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.runtimeOperatorRefUnsupported",
                "Operator '%s' declares %s lowering; lowering.operatorRef must stay blank because this binding is not an executable BLOGE operator in the current runtime."
                        .formatted(operator.operatorRef(), bindingLabel),
                path + "/operatorRef"));
    }

    private static void validateRequiredStringParameter(OperatorDefinition operator,
                                                       String path,
                                                       String parameterName,
                                                       String label,
                                                       List<VisualDiagnostic> diagnostics) {
        Object value = operator.lowering().parameters().get(parameterName);
        if (value instanceof String string && !string.isBlank()) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.parameter.required",
                "Operator '%s' must declare non-empty lowering.parameters.%s for its %s binding."
                        .formatted(operator.operatorRef(), parameterName, label),
                path + "/parameters/" + parameterName));
    }

    private static void validateNativeLowering(OperatorDefinition operator,
                                               String path,
                                               List<VisualDiagnostic> diagnostics) {
        String executableOperatorRef = operator.lowering().operatorRef();
        if (executableOperatorRef.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.operatorRef.required",
                    "Native operator '%s' must declare lowering.operatorRef for the executable BLOGE operator."
                            .formatted(operator.operatorRef()),
                    path + "/operatorRef"));
            return;
        }
        if (!EXECUTABLE_OPERATOR_REF.matcher(executableOperatorRef).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.operatorRef.invalid",
                    "Native operator '%s' lowering.operatorRef '%s' must be a namespace-safe executable token."
                            .formatted(operator.operatorRef(), executableOperatorRef),
                    path + "/operatorRef"));
        }
        if (isReservedExecutableOperatorRef(executableOperatorRef)) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.operatorRef.reserved",
                    "Native operator '%s' cannot lower directly to system-managed executable operator '%s'."
                            .formatted(operator.operatorRef(), executableOperatorRef),
                    path + "/operatorRef"));
        }
        validateNativeDslFieldNames(operator, operatorPath(path), diagnostics);
    }

    private static boolean isReservedExecutableOperatorRef(String executableOperatorRef) {
        return RESERVED_EXECUTABLE_OPERATOR_REFS.contains(executableOperatorRef)
                || RESERVED_EXECUTABLE_OPERATOR_REF_PREFIXES.stream().anyMatch(executableOperatorRef::startsWith);
    }

    private static void validateNativeDslFieldNames(OperatorDefinition operator,
                                                    String operatorPath,
                                                    List<VisualDiagnostic> diagnostics) {
        validateDslSchemaPropertyNames(operator,
                operator.configSchema().schema(),
                operatorPath + "/configSchema/schema",
                EXECUTION_CONFIG_KEYS,
                diagnostics);
    }

    private static void validateTransformLowering(OperatorDefinition operator,
                                                  String path,
                                                  List<VisualDiagnostic> diagnostics) {
        if (operator.ports().outputs().size() != 1
                || operator.ports().outputs().getFirst() == null
                || !"output".equals(operator.ports().outputs().getFirst().name())) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.transformOutputUnsupported",
                    "Transform-lowered operator '%s' must declare exactly one output port named 'output'."
                            .formatted(operator.operatorRef()),
                    path + "/mode"));
            return;
        }

        Map<String, Object> assignments = objectMap(operator.lowering().parameters().get("assignments"));
        if (assignments.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignments.required",
                    "Transform-lowered operator '%s' must declare non-empty lowering.parameters.assignments."
                            .formatted(operator.operatorRef()),
                    path + "/parameters/assignments"));
            return;
        }

        OperatorDefinition.Port output = operator.ports().outputs().getFirst();
        Map<String, Boolean> assignedTargets = new LinkedHashMap<>();
        assignments.forEach((target, rawExpression) -> {
            String assignmentPath = path + "/parameters/assignments/" + target;
            Map<String, Object> targetSchema = validateTransformAssignmentTarget(operator, output, target,
                    assignmentPath, diagnostics);
            boolean guaranteesTargetSchema = validateTransformAssignmentExpression(operator, rawExpression,
                    targetSchema, assignmentPath, diagnostics);
            assignedTargets.put(target, guaranteesTargetSchema);
        });
        validateRequiredTransformOutputs(operator, output, assignedTargets, path + "/parameters/assignments",
                diagnostics);
    }

    private static void validateBranchLowering(OperatorDefinition operator,
                                               String path,
                                               List<VisualDiagnostic> diagnostics) {
        if (!operator.ports().outputs().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchOutputUnsupported",
                    "Branch-lowered operator '%s' must not declare output ports because it lowers to BLOGE branch routing."
                            .formatted(operator.operatorRef()),
                    path + "/mode"));
        }
        if (operator.ports().inputs().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchInputRequired",
                    "Branch-lowered operator '%s' must declare at least one input port used by lowering.parameters.expression."
                            .formatted(operator.operatorRef()),
                    operatorPath(path) + "/ports/inputs"));
        }
        Object rawExpression = operator.lowering().parameters().get("expression");
        if (!(rawExpression instanceof String expression) || expression.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchExpression.required",
                    "Branch-lowered operator '%s' must declare lowering.parameters.expression."
                            .formatted(operator.operatorRef()),
                    path + "/parameters/expression"));
            return;
        }
        validateBranchExpression(operator, expression, path + "/parameters/expression", diagnostics);
    }

    private static void validateBranchExpression(OperatorDefinition operator,
                                                 String expression,
                                                 String path,
                                                 List<VisualDiagnostic> diagnostics) {
        Matcher tokenMatcher = TEMPLATE_TOKEN.matcher(expression);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group(1).trim();
            if (!templateReference(token).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchTemplate.invalid",
                        "Branch template token '{{%s}}' on operator '%s' must reference a declared input path."
                                .formatted(token, operator.operatorRef()),
                        path));
            }
        }

        boolean sawReference = false;
        Matcher referenceMatcher = TEMPLATE_REFERENCE.matcher(expression);
        while (referenceMatcher.find()) {
            sawReference = true;
            String reference = referenceMatcher.group(1);
            String inputPath = reference.startsWith("input.") ? reference.substring("input.".length()) : reference;
            Map<String, Object> sourceSchema = inputPropertyAtPath(operator, inputPath);
            if (sourceSchema == null) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchTemplate.unknownInput",
                        "Branch template reference '{{%s}}' on operator '%s' does not match a declared input path."
                                .formatted(reference, operator.operatorRef()),
                        path));
                continue;
            }
            String type = schemaKind(sourceSchema);
            if ("object".equals(type) || "array".equals(type)) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchExpression.nonScalar",
                        "Branch template reference '{{%s}}' on operator '%s' resolves to %s, but branch routing requires a scalar selector."
                                .formatted(reference, operator.operatorRef(), schemaTypeLabel(sourceSchema)),
                        path));
            }
        }
        if (!sawReference) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.branchExpression.referenceRequired",
                    "Branch-lowered operator '%s' expression must reference at least one declared input path."
                            .formatted(operator.operatorRef()),
                    path));
        }
    }

    private static Map<String, Object> validateTransformAssignmentTarget(OperatorDefinition operator,
                                                                         OperatorDefinition.Port output,
                                                                         String target,
                                                                         String path,
                                                                         List<VisualDiagnostic> diagnostics) {
        if (!PATH_PATTERN.matcher(target).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTarget.invalid",
                    "Transform assignment target '%s' on operator '%s' must be a dotted identifier path."
                            .formatted(target, operator.operatorRef()),
                    path));
            return null;
        }
        validateDslPathSegments(operator, target, path, diagnostics);
        Map<String, Object> targetSchema = propertyAtPath(output.schema(), target);
        if (targetSchema == null) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTarget.unknown",
                    "Transform assignment target '%s' is not declared by output schema on operator '%s'."
                            .formatted(target, operator.operatorRef()),
                    path));
        }
        return targetSchema;
    }

    private static boolean validateTransformAssignmentExpression(OperatorDefinition operator,
                                                                 Object rawExpression,
                                                                 Map<String, Object> targetSchema,
                                                                 String path,
                                                                 List<VisualDiagnostic> diagnostics) {
        if (!(rawExpression instanceof String expression) || expression.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentExpression.invalid",
                    "Transform assignment expression on operator '%s' must be a non-empty string."
                            .formatted(operator.operatorRef()),
                    path));
            return false;
        }

        Matcher tokenMatcher = TEMPLATE_TOKEN.matcher(expression);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group(1).trim();
            if (!templateReference(token).matches()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.template.invalid",
                        "Template token '{{%s}}' on operator '%s' must reference a declared input path."
                                .formatted(token, operator.operatorRef()),
                        path));
            }
        }

        Matcher referenceMatcher = TEMPLATE_REFERENCE.matcher(expression);
        while (referenceMatcher.find()) {
            String reference = referenceMatcher.group(1);
            String inputPath = reference.startsWith("input.") ? reference.substring("input.".length()) : reference;
            if (!inputPathExists(operator, inputPath)) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.template.unknownInput",
                        "Template reference '{{%s}}' on operator '%s' does not match a declared input path."
                                .formatted(reference, operator.operatorRef()),
                        path));
            }
        }
        return validateTransformAssignmentExpressionType(operator, expression, targetSchema, path, diagnostics);
    }

    private static Matcher templateReference(String token) {
        return TEMPLATE_REFERENCE.matcher("{{" + token + "}}");
    }

    private static boolean validateTransformAssignmentExpressionType(OperatorDefinition operator,
                                                                     String expression,
                                                                     Map<String, Object> targetSchema,
                                                                     String path,
                                                                     List<VisualDiagnostic> diagnostics) {
        if (targetSchema == null) {
            return false;
        }

        Matcher template = PURE_TEMPLATE_REFERENCE.matcher(expression.trim());
        if (template.matches()) {
            String reference = template.group(1);
            String inputPath = reference.startsWith("input.") ? reference.substring("input.".length()) : reference;
            Map<String, Object> sourceSchema = inputPropertyAtPath(operator, inputPath);
            if (sourceSchema == null) {
                return false;
            }
            return addAssignmentTypeMismatch(operator, "input." + inputPath, sourceSchema, targetSchema,
                    path, diagnostics);
        }

        Optional<StaticExpressionLiteral> literal = staticExpressionLiteral(expression);
        if (literal.isPresent()) {
            return addAssignmentTypeMismatch(operator, literal.get().label(), literal.get().schema(),
                    targetSchema, path, diagnostics);
        }
        return false;
    }

    private static boolean addAssignmentTypeMismatch(OperatorDefinition operator,
                                                     String sourceLabel,
                                                     Map<String, Object> sourceSchema,
                                                     Map<String, Object> targetSchema,
                                                     String path,
                                                     List<VisualDiagnostic> diagnostics) {
        Optional<String> compatibilityIssue = schemaCompatibilityIssue(sourceSchema, targetSchema);
        if (compatibilityIssue.isEmpty()) {
            return true;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTypeMismatch",
                "Transform assignment '%s' on operator '%s' produces %s, but output schema requires %s."
                        .formatted(sourceLabel, operator.operatorRef(), schemaTypeLabel(sourceSchema),
                                schemaTypeLabel(targetSchema))
                        + compatibilityReason(compatibilityIssue.get()),
                path));
        return false;
    }

    private static void validateRequiredTransformOutputs(OperatorDefinition operator,
                                                         OperatorDefinition.Port output,
                                                         Map<String, Boolean> assignedTargets,
                                                         String path,
                                                         List<VisualDiagnostic> diagnostics) {
        for (String required : requiredPaths(output.schema().schema())) {
            boolean satisfied = assignedTargets.entrySet().stream()
                    .anyMatch(entry -> satisfiesRequiredPath(entry.getKey(), entry.getValue(), required));
            if (!satisfied) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTarget.required",
                        "Transform-lowered operator '%s' must assign required output '%s'."
                                .formatted(operator.operatorRef(), required),
                        path + "/" + required));
            }
        }
    }

    private static boolean inputPathExists(OperatorDefinition operator, String inputPath) {
        return inputPropertyAtPath(operator, inputPath) != null;
    }

    private static Map<String, Object> inputPropertyAtPath(OperatorDefinition operator, String inputPath) {
        if (inputPath.isBlank()) {
            return null;
        }
        String[] segments = inputPath.split("\\.", 2);
        String first = segments[0];
        String rest = segments.length == 2 ? segments[1] : "";
        for (OperatorDefinition.Port port : operator.ports().inputs()) {
            if (port == null) {
                continue;
            }
            if (port.name().equals(first)) {
                return rest.isBlank() ? propertyAtPath(port.schema(), "") : propertyAtPath(port.schema(), rest);
            }
        }
        if (operator.ports().inputs().size() == 1 && operator.ports().inputs().getFirst() != null) {
            return propertyAtPath(operator.ports().inputs().getFirst().schema(), inputPath);
        }
        return null;
    }

    private static boolean satisfiesRequiredPath(String assignmentTarget,
                                                 boolean guaranteesTargetSchema,
                                                 String requiredPath) {
        return assignmentTarget.equals(requiredPath)
                || assignmentTarget.startsWith(requiredPath + ".")
                || (guaranteesTargetSchema && requiredPath.startsWith(assignmentTarget + "."));
    }

    private static Map<String, Object> propertyAtPath(SchemaEnvelope schema, String path) {
        if (path == null || path.isBlank()) {
            return schema.schema();
        }
        Map<String, Object> currentSchema = schema.schema();
        Map<String, Object> current = null;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if ("array".equals(schemaKind(currentSchema))) {
                Integer index = arrayIndexSegment(segment);
                if (index == null) {
                    return null;
                }
                current = arrayItemSchemaForIndex(currentSchema, index);
                if (current == null) {
                    return null;
                }
                currentSchema = current;
                continue;
            }
            current = objectProperty(propertiesOf(currentSchema).get(segment));
            if (current == null) {
                current = patternPropertySchema(currentSchema, segment);
            }
            if (current == null) {
                current = additionalPropertySchema(currentSchema);
                if (current == null) {
                    return null;
                }
            }
            currentSchema = current;
        }
        return current;
    }

    private static Integer arrayIndexSegment(String segment) {
        if (!ARRAY_INDEX.matcher(segment).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
        List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
        if (index < prefixItems.size()) {
            return prefixItems.get(index);
        }
        return objectProperty(schema.get("items"));
    }

    private static List<Map<String, Object>> prefixItemsOf(Map<String, Object> schema) {
        Object raw = schema.get("prefixItems");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> prefixItems = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> itemSchema = objectProperty(value);
            if (itemSchema != null) {
                prefixItems.add(itemSchema);
            }
        }
        return prefixItems;
    }

    private static List<String> requiredPaths(Map<String, Object> schema) {
        List<String> paths = new ArrayList<>();
        collectRequiredPaths(schema, "", paths);
        return paths;
    }

    private static void collectRequiredPaths(Map<String, Object> schema,
                                             String prefix,
                                             List<String> paths) {
        Map<String, Object> properties = propertiesOf(schema);
        for (String required : requiredNamesOf(schema)) {
            Map<String, Object> child = objectProperty(properties.get(required));
            String path = prefix.isBlank() ? required : prefix + "." + required;
            if (child != null && !requiredNamesOf(child).isEmpty()) {
                collectRequiredPaths(child, path, paths);
            } else {
                paths.add(path);
            }
        }
    }

    private static List<String> requiredNamesOf(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                required.add(String.valueOf(item));
            }
        }
        return required;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        Object raw = schema.get("properties");
        if (!(raw instanceof Map<?, ?> properties)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        properties.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static Map<String, Object> additionalPropertySchema(Map<String, Object> schema) {
        Object residual = residualPropertiesPolicy(schema);
        if (Boolean.TRUE.equals(residual)) {
            return Map.of();
        }
        if (residual instanceof Map<?, ?> residualSchema) {
            return objectProperty(residualSchema);
        }
        return null;
    }

    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
        if (schema.containsKey("additionalProperties")) {
            return schema.get("additionalProperties");
        }
        return schema.get("unevaluatedProperties");
    }

    private static Map<String, Object> patternPropertySchema(Map<String, Object> schema, String propertyName) {
        List<Map<String, Object>> matches = matchingPatternPropertySchemas(schema, propertyName);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static List<Map<String, Object>> matchingPatternPropertySchemas(Map<String, Object> schema,
                                                                            String propertyName) {
        Object raw = schema.get("patternProperties");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return List.of();
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String pattern = String.valueOf(entry.getKey());
            if (patternMatches(pattern, propertyName) && entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> copy = new LinkedHashMap<>();
                nested.forEach((key, item) -> copy.put(String.valueOf(key), item));
                matches.add(copy);
            }
        }
        return matches;
    }

    private static boolean patternMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }

    private static void validateDslSchemaPropertyNames(OperatorDefinition operator,
                                                       Map<String, Object> schema,
                                                       String path,
                                                       Set<String> allowedAtCurrentLevel,
                                                       List<VisualDiagnostic> diagnostics) {
        String kind = schemaKind(schema);
        if ("array".equals(kind)) {
            Object prefixItems = schema.get("prefixItems");
            if (prefixItems instanceof List<?> values) {
                for (int i = 0; i < values.size(); i++) {
                    Map<String, Object> itemSchema = objectProperty(values.get(i));
                    if (itemSchema != null) {
                        validateDslSchemaPropertyNames(operator, itemSchema, path + "/prefixItems/" + i,
                                Set.of(), diagnostics);
                    }
                }
            }
            Map<String, Object> items = objectProperty(schema.get("items"));
            if (items != null) {
                validateDslSchemaPropertyNames(operator, items, path + "/items", Set.of(), diagnostics);
            }
            return;
        }
        if (!"object".equals(kind) && !schema.containsKey("properties")) {
            return;
        }
        propertiesOf(schema).forEach((name, child) -> {
            String propertyPath = path + "/properties/" + name;
            if (!allowedAtCurrentLevel.contains(name)) {
                validateDslFieldName(operator, name, propertyPath, diagnostics);
            }
            Map<String, Object> childSchema = objectProperty(child);
            if (childSchema != null) {
                validateDslSchemaPropertyNames(operator, childSchema, propertyPath, Set.of(), diagnostics);
            }
        });
        Object patternProperties = schema.get("patternProperties");
        if (patternProperties instanceof Map<?, ?> rawPatternProperties) {
            rawPatternProperties.forEach((pattern, child) -> {
                Map<String, Object> childSchema = objectProperty(child);
                if (childSchema != null) {
                    validateDslSchemaPropertyNames(operator, childSchema,
                            path + "/patternProperties/" + pattern, Set.of(), diagnostics);
                }
            });
        }
        Object residual = residualPropertiesPolicy(schema);
        if (residual instanceof Map<?, ?>) {
            Map<String, Object> residualSchema = objectProperty(residual);
            if (residualSchema != null) {
                validateDslSchemaPropertyNames(operator, residualSchema,
                        path + "/" + residualPropertiesKeyword(schema), Set.of(), diagnostics);
            }
        }
    }

    private static String residualPropertiesKeyword(Map<String, Object> schema) {
        return schema.containsKey("additionalProperties") ? "additionalProperties" : "unevaluatedProperties";
    }

    private static void validateDslPathSegments(OperatorDefinition operator,
                                                String path,
                                                String diagnosticPath,
                                                List<VisualDiagnostic> diagnostics) {
        for (String segment : path.split("\\.")) {
            validateDslFieldName(operator, segment, diagnosticPath, diagnostics);
        }
    }

    private static void validateDslFieldName(OperatorDefinition operator,
                                             String fieldName,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        if (!isDslFieldName(fieldName)) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.dslField.invalid",
                    "Operator '%s' field '%s' cannot be rendered as a BLOGE DSL field."
                            .formatted(operator.operatorRef(), fieldName),
                    path));
        }
    }

    private static boolean isDslFieldName(String fieldName) {
        return PORT_NAME.matcher(fieldName).matches() && !RESERVED_DSL_FIELD_NAMES.contains(fieldName);
    }

    private static String schemaKind(Map<String, Object> schema) {
        Object kind = schema.get("kind");
        if (kind instanceof String value && !value.isBlank()) {
            return value;
        }
        Object type = schema.get("type");
        if (type instanceof String value && !value.isBlank()) {
            return value;
        }
        if (type instanceof List<?> values) {
            return nullableTypePrimary(values);
        }
        if (schema.containsKey("properties")) {
            return "object";
        }
        if (schema.containsKey("items")) {
            return "array";
        }
        return "";
    }

    private static String nullableTypePrimary(List<?> types) {
        String primary = "";
        int concreteTypes = 0;
        for (Object item : types) {
            if (!(item instanceof String type) || type.isBlank()) {
                return String.valueOf(types);
            }
            if (!"null".equals(type)) {
                primary = type;
                concreteTypes++;
            }
        }
        if (concreteTypes > 1) {
            return String.valueOf(types);
        }
        return primary.isBlank() ? "null" : primary;
    }

    private static String operatorPath(String loweringPath) {
        return loweringPath.endsWith("/lowering")
                ? loweringPath.substring(0, loweringPath.length() - "/lowering".length())
                : loweringPath;
    }

}
