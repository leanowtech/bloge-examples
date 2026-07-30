package com.leanowtech.bloge.gateway.visual.authoring.compile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.parse.CompactTypeParser;
import com.leanowtech.bloge.gateway.visual.authoring.parse.FunctionSignatureParser;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic server compiler from the Quick authoring contract to the canonical operator-library contract.
 */
@Service
public final class AuthoringCompiler {

    public static final String COMPILER_VERSION = "1.0.0";
    public static final int MAX_AUTHORING_BYTES = 5 * 1024 * 1024;
    public static final int MAX_CANONICAL_BYTES = 10 * 1024 * 1024;
    public static final int MAX_TYPES = 1_000;
    public static final int MAX_OPERATORS = 1_000;
    public static final int MAX_FUNCTIONS = 2_000;
    public static final int MAX_SIGNATURES_PER_FUNCTION = 20;
    public static final int MAX_FIELDS_PER_OBJECT = 2_000;
    public static final int MAX_TYPE_DEPTH = 32;

    private static final Set<String> STRUCTURED_TYPE_KEYS = Set.of(
            "type",
            "fields",
            "enum",
            "description",
            "additionalProperties",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems"
    );
    private static final Set<String> FUNCTION_TYPE_LABELS = Set.of(
            "any", "unknown", "string", "number", "integer", "boolean",
            "object", "array", "json", "date", "datetime"
    );

    private final ObjectMapper objectMapper;
    private final OperatorLibraryValidator canonicalValidator;
    private final CompactTypeParser typeParser;
    private final FunctionSignatureParser signatureParser;
    private final OperatorArchetypeRegistry archetypes;

    @Autowired
    public AuthoringCompiler(ObjectMapper objectMapper,
                             OperatorLibraryValidator canonicalValidator) {
        this(objectMapper, canonicalValidator, new CompactTypeParser(),
                new FunctionSignatureParser(), new OperatorArchetypeRegistry());
    }

    AuthoringCompiler(ObjectMapper objectMapper,
                      OperatorLibraryValidator canonicalValidator,
                      CompactTypeParser typeParser,
                      FunctionSignatureParser signatureParser,
                      OperatorArchetypeRegistry archetypes) {
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.canonicalValidator = canonicalValidator == null
                ? new OperatorLibraryValidator() : canonicalValidator;
        this.typeParser = typeParser == null ? new CompactTypeParser() : typeParser;
        this.signatureParser = signatureParser == null ? new FunctionSignatureParser() : signatureParser;
        this.archetypes = archetypes == null ? new OperatorArchetypeRegistry() : archetypes;
    }

    public AuthoringCompileResult compile(VisualLibraryAuthoringDocument document) {
        List<AuthoringDiagnostic> diagnostics = new ArrayList<>();
        List<AuthoringCompileResult.ConfirmationRequest> confirmations = new ArrayList<>();
        AuthoringSourceMap.Builder sourceMapBuilder = new AuthoringSourceMap.Builder()
                .add("/", "/")
                .add("/library/id", "/libraryId")
                .add("/library/name", "/displayName")
                .add("/library/version", "/version")
                .add("/library/owner", "/owner");

        if (document == null) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.DOCUMENT_REQUIRED",
                    "Visual library authoring document is required.",
                    "/"));
            return result(null, "", "", sourceMapBuilder.build(), diagnostics, confirmations);
        }

        validateRoot(document, diagnostics, confirmations);
        String authoringFingerprint = fingerprintAuthoring(document, diagnostics);
        TypeCompiler typeCompiler = new TypeCompiler(document.types(), diagnostics, sourceMapBuilder);
        List<OperatorDefinition> operators = compileOperators(
                document, typeCompiler, diagnostics, confirmations, sourceMapBuilder);
        List<OperatorLibrary.BuiltInFunction> functions = compileFunctions(
                document, typeCompiler, diagnostics, sourceMapBuilder);

        AuthoringSourceMap sourceMap = sourceMapBuilder.build();
        OperatorLibrary canonicalLibrary = null;
        String canonicalFingerprint = "";
        if (diagnostics.stream().noneMatch(AuthoringDiagnostic::error)) {
            canonicalLibrary = new OperatorLibrary(
                    "bloge.visualOperatorLibrary.v1",
                    document.library().id(),
                    displayName(document.library().name(), document.library().id()),
                    document.library().version(),
                    document.library().owner(),
                    document.library().status(),
                    functions,
                    operators
            );
            VisualValidationResult validation = canonicalValidator.validate(canonicalLibrary);
            for (VisualDiagnostic diagnostic : validation.diagnostics()) {
                diagnostics.add(AuthoringDiagnostic.fromCanonical(diagnostic, sourceMap));
            }
            canonicalFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, canonicalLibrary, MAX_CANONICAL_BYTES);
        }

        return result(canonicalLibrary, authoringFingerprint, canonicalFingerprint,
                sourceMap, diagnostics, confirmations);
    }

    private void validateRoot(VisualLibraryAuthoringDocument document,
                              List<AuthoringDiagnostic> diagnostics,
                              List<AuthoringCompileResult.ConfirmationRequest> confirmations) {
        if (!VisualLibraryAuthoringDocument.SCHEMA_VERSION.equals(document.schemaVersion())) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.SCHEMA_VERSION_UNSUPPORTED",
                    "Authoring schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(document.schemaVersion(), VisualLibraryAuthoringDocument.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        if (document.library().id().isBlank()) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.LIBRARY_ID_REQUIRED",
                    "Library id is required.",
                    "/library/id"));
        }
        if (document.library().owner().isBlank()) {
            diagnostics.add(AuthoringDiagnostic.warning(
                    "RG.AUTHORING.OWNER_CONFIRMATION_REQUIRED",
                    "Library owner is missing; design preview is allowed but production readiness requires ownership.",
                    "/library/owner"));
            confirmations.add(new AuthoringCompileResult.ConfirmationRequest(
                    "RG.AUTHORING.OWNER_CONFIRMATION_REQUIRED",
                    "/library/owner",
                    "Which team owns this library?",
                    List.of()
            ));
        }
        if (document.operators().isEmpty() && document.functions().isEmpty()) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.LIBRARY_EMPTY",
                    "Authoring document must define at least one operator or function.",
                    "/"));
        }
        boundedSize("types", document.types().size(), MAX_TYPES, "/types", diagnostics);
        boundedSize("operators", document.operators().size(), MAX_OPERATORS, "/operators", diagnostics);
        boundedSize("functions", document.functions().size(), MAX_FUNCTIONS, "/functions", diagnostics);
        if (!document.imports().isEmpty()) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.CROSS_LIBRARY_IMPORT_UNSUPPORTED",
                    "Stage 0 supports local named types only; cross-library imports require a locked resolver.",
                    "/imports"));
        }
    }

    private List<OperatorDefinition> compileOperators(
            VisualLibraryAuthoringDocument document,
            TypeCompiler typeCompiler,
            List<AuthoringDiagnostic> diagnostics,
            List<AuthoringCompileResult.ConfirmationRequest> confirmations,
            AuthoringSourceMap.Builder sourceMap) {
        List<OperatorDefinition> compiled = new ArrayList<>();
        List<Map.Entry<String, VisualLibraryAuthoringDocument.OperatorAuthoring>> entries =
                document.operators().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<String, VisualLibraryAuthoringDocument.OperatorAuthoring> entry = entries.get(index);
            String operatorRef = entry.getKey() == null ? "" : entry.getKey().trim();
            String authoringPath = "/operators/" + pointer(operatorRef);
            String canonicalPath = "/operators/" + index;
            sourceMap.add(authoringPath, canonicalPath)
                    .add(authoringPath + "/name", canonicalPath + "/display/name")
                    .add(authoringPath + "/description", canonicalPath + "/display/description")
                    .add(authoringPath + "/archetype", canonicalPath + "/lowering/mode");

            VisualLibraryAuthoringDocument.OperatorAuthoring operator = entry.getValue();
            if (operator == null) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.OPERATOR_REQUIRED",
                        "Operator '%s' has no definition.".formatted(operatorRef),
                        authoringPath));
                continue;
            }
            OperatorArchetypeRegistry.Archetype archetype = archetypes.find(operator.archetype())
                    .orElseGet(() -> {
                        diagnostics.add(AuthoringDiagnostic.error(
                                "RG.AUTHORING.ARCHETYPE_UNSUPPORTED",
                                "Operator archetype '%s' is unsupported.".formatted(operator.archetype()),
                                authoringPath + "/archetype"));
                        return archetypes.find("pure").orElseThrow();
                    });
            List<OperatorDefinition.Port> inputs = compilePorts(
                    operator.input(), authoringPath + "/input", canonicalPath + "/ports/inputs",
                    typeCompiler);
            List<OperatorDefinition.Port> outputs = compilePorts(
                    operator.output(), authoringPath + "/output", canonicalPath + "/ports/outputs",
                    typeCompiler);
            SchemaEnvelope configSchema = operator.config() == null || operator.config().isNull()
                    ? SchemaEnvelope.opaque()
                    : typeCompiler.compile(
                            operator.config(),
                            authoringPath + "/config",
                            canonicalPath + "/configSchema",
                            new LinkedHashSet<>(),
                            0
                    ).envelope();
            boolean requiresSecrets = resolveSecretPosture(
                    operator, archetype, authoringPath, diagnostics, confirmations);
            String effect = operator.effect().isBlank() ? archetype.effect() : operator.effect();
            String idempotency = operator.idempotency().isBlank()
                    ? archetype.idempotency() : operator.idempotency();
            boolean streaming = Boolean.TRUE.equals(operator.streaming());
            boolean durable = Boolean.TRUE.equals(operator.durable());
            OperatorDefinition.SideEffectProtocol sideEffectProtocol =
                    sideEffectProtocol(operator.runtime(), effect, authoringPath, diagnostics);
            OperatorDefinition.Capabilities capabilities = new OperatorDefinition.Capabilities(
                    effect,
                    idempotency,
                    streaming,
                    durable,
                    requiresSecrets,
                    sideEffectProtocol
            );
            Map<String, Object> loweringParameters = runtimeParameters(operator.runtime());
            if (!"pure".equals(archetype.name()) && !"decision".equals(archetype.name())
                    && loweringParameters.isEmpty()) {
                diagnostics.add(AuthoringDiagnostic.warning(
                        "RG.AUTHORING.RUNTIME_BINDING_CONFIRMATION_REQUIRED",
                        "Operator '%s' uses archetype '%s' but has no runtime facts yet."
                                .formatted(operatorRef, archetype.name()),
                        authoringPath + "/runtime"));
                confirmations.add(new AuthoringCompileResult.ConfirmationRequest(
                        "RG.AUTHORING.RUNTIME_BINDING_CONFIRMATION_REQUIRED",
                        authoringPath + "/runtime",
                        "Which runtime binding implements this operator?",
                        List.of()
                ));
            }
            OperatorDefinition.Lowering lowering = new OperatorDefinition.Lowering(
                    archetype.loweringMode(),
                    "",
                    loweringParameters
            );
            List<String> tags = new ArrayList<>(operator.tags());
            if (!tags.contains(archetype.name())) {
                tags.add(archetype.name());
            }
            compiled.add(new OperatorDefinition(
                    "bloge.visualOperator.v1",
                    operatorRef,
                    operator.version().isBlank()
                            ? document.defaults().operatorVersion()
                            : operator.version(),
                    new OperatorDefinition.Display(
                            displayName(operator.name(), operatorRef),
                            operator.description(),
                            tags.stream().sorted().toList()
                    ),
                    new OperatorDefinition.Source(
                            "user-library", "", "", "", true, document.library().id()),
                    new OperatorDefinition.Ports(inputs, outputs),
                    configSchema,
                    capabilities,
                    OperatorDefinition.Policy.unrestricted(),
                    lowering,
                    List.of()
            ));
        }
        return List.copyOf(compiled);
    }

    private List<OperatorDefinition.Port> compilePorts(
            Map<String, JsonNode> ports,
            String authoringBase,
            String canonicalBase,
            TypeCompiler typeCompiler) {
        List<Map.Entry<String, JsonNode>> entries = ports.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        List<OperatorDefinition.Port> compiled = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<String, JsonNode> entry = entries.get(index);
            String rawName = entry.getKey() == null ? "" : entry.getKey().trim();
            boolean required = !rawName.endsWith("?");
            String portName = required ? rawName : rawName.substring(0, rawName.length() - 1);
            String authoringPath = authoringBase + "/" + pointer(rawName);
            String canonicalPath = canonicalBase + "/" + index;
            typeCompiler.sourceMap.add(authoringPath, canonicalPath)
                    .add(authoringPath, canonicalPath + "/schema");
            CompiledType type = typeCompiler.compile(
                    entry.getValue(), authoringPath, canonicalPath + "/schema",
                    new LinkedHashSet<>(), 0);
            String description = entry.getValue() != null && entry.getValue().isObject()
                    ? entry.getValue().path("description").asText("")
                    : "";
            compiled.add(new OperatorDefinition.Port(portName, type.envelope(), required, description));
        }
        return List.copyOf(compiled);
    }

    private List<OperatorLibrary.BuiltInFunction> compileFunctions(
            VisualLibraryAuthoringDocument document,
            TypeCompiler typeCompiler,
            List<AuthoringDiagnostic> diagnostics,
            AuthoringSourceMap.Builder sourceMap) {
        List<OperatorLibrary.BuiltInFunction> compiled = new ArrayList<>();
        List<Map.Entry<String, VisualLibraryAuthoringDocument.FunctionAuthoring>> entries =
                document.functions().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        for (int functionIndex = 0; functionIndex < entries.size(); functionIndex++) {
            Map.Entry<String, VisualLibraryAuthoringDocument.FunctionAuthoring> entry = entries.get(functionIndex);
            String callableName = entry.getKey() == null ? "" : entry.getKey().trim();
            String authoringPath = "/functions/" + pointer(callableName);
            String canonicalPath = "/builtInFunctions/" + functionIndex;
            sourceMap.add(authoringPath, canonicalPath)
                    .add(authoringPath, canonicalPath + "/name")
                    .add(authoringPath + "/namespace", canonicalPath + "/namespace");
            VisualLibraryAuthoringDocument.FunctionAuthoring function = entry.getValue();
            if (function == null) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.FUNCTION_REQUIRED",
                        "Function '%s' has no definition.".formatted(callableName),
                        authoringPath));
                continue;
            }
            if (!function.name().isBlank() && !callableName.equals(function.name())) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.FUNCTION_NAME_MISMATCH",
                        "Function map key '%s' does not match explicit name '%s'."
                                .formatted(callableName, function.name()),
                        authoringPath + "/name"));
            }
            List<OperatorLibrary.Signature> signatures = new ArrayList<>();
            List<String> signatureSources = function.allSignatures();
            if (signatureSources.isEmpty()) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.SIGNATURE_REQUIRED",
                        "Function '%s' must declare signature or signatures.".formatted(callableName),
                        authoringPath + "/signatures"));
            }
            if (signatureSources.size() > MAX_SIGNATURES_PER_FUNCTION) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.COLLECTION_LIMIT_EXCEEDED",
                        "Function '%s' declares %d signatures; the limit is %d."
                                .formatted(callableName, signatureSources.size(), MAX_SIGNATURES_PER_FUNCTION),
                        authoringPath + "/signatures"));
            }
            int signatureCount = Math.min(signatureSources.size(), MAX_SIGNATURES_PER_FUNCTION);
            for (int signatureIndex = 0; signatureIndex < signatureCount; signatureIndex++) {
                String signatureSource = signatureSources.get(signatureIndex);
                String signaturePath = function.signature().isBlank()
                        ? authoringPath + "/signatures/" + signatureIndex
                        : signatureIndex == 0
                        ? authoringPath + "/signature"
                        : authoringPath + "/signatures/" + (signatureIndex - 1);
                String signatureCanonicalPath = canonicalPath + "/signatures/" + signatureIndex;
                sourceMap.add(signaturePath, signatureCanonicalPath);
                FunctionSignatureParser.ParseResult parsed = signatureParser.parse(signatureSource);
                if (!parsed.valid()) {
                    for (FunctionSignatureParser.ParseIssue issue : parsed.issues()) {
                        diagnostics.add(AuthoringDiagnostic.error(
                                issue.code(), issue.message(), signaturePath, issue.offset()));
                    }
                    continue;
                }
                List<OperatorLibrary.Parameter> parameters = new ArrayList<>();
                for (int parameterIndex = 0;
                     parameterIndex < parsed.signature().parameters().size();
                     parameterIndex++) {
                    FunctionSignatureParser.Parameter parameter =
                            parsed.signature().parameters().get(parameterIndex);
                    String parameterCanonicalPath = signatureCanonicalPath
                            + "/parameters/" + parameterIndex;
                    CompiledType parameterType = typeCompiler.compile(
                            objectMapper.valueToTree(parameter.type().canonicalText()),
                            signaturePath,
                            parameterCanonicalPath + "/schema",
                            new LinkedHashSet<>(),
                            0
                    );
                    parameters.add(new OperatorLibrary.Parameter(
                            parameter.name(),
                            functionTypeLabel(parameter.type(), parameterType.schema()),
                            parameterType.envelope(),
                            parameter.optional(),
                            parameter.variadic(),
                            ""
                    ));
                }
                CompiledType returnType = typeCompiler.compile(
                        objectMapper.valueToTree(parsed.signature().returns().canonicalText()),
                        signaturePath,
                        signatureCanonicalPath + "/returns/schema",
                        new LinkedHashSet<>(),
                        0
                );
                signatures.add(new OperatorLibrary.Signature(
                        parsed.signature().normalized(),
                        "",
                        parameters,
                        new OperatorLibrary.ReturnValue(
                                functionTypeLabel(parsed.signature().returns(), returnType.schema()),
                                returnType.envelope(),
                                ""
                        )
                ));
            }
            compiled.add(new OperatorLibrary.BuiltInFunction(
                    callableName,
                    function.namespace().isBlank()
                            ? document.defaults().namespace()
                            : function.namespace(),
                    displayName(function.name(), callableName),
                    function.description(),
                    function.category(),
                    signatures,
                    function.examples()
            ));
        }
        return List.copyOf(compiled);
    }

    private AuthoringCompileResult result(
            OperatorLibrary canonicalLibrary,
            String authoringFingerprint,
            String canonicalFingerprint,
            AuthoringSourceMap sourceMap,
            List<AuthoringDiagnostic> diagnostics,
            List<AuthoringCompileResult.ConfirmationRequest> confirmations) {
        String archetypeFingerprint = archetypes.fingerprint(objectMapper);
        String grammarVersion = CompactTypeParser.GRAMMAR_VERSION
                + "+" + FunctionSignatureParser.GRAMMAR_VERSION;
        String compileFingerprint = authoringFingerprint.isBlank()
                ? ""
                : VisualBundleFingerprint.fromMaterial(Map.of(
                        "authoringFingerprint", authoringFingerprint,
                        "compilerVersion", COMPILER_VERSION,
                        "grammarVersion", grammarVersion,
                        "archetypeCatalogFingerprint", archetypeFingerprint
                ));
        boolean errors = diagnostics.stream().anyMatch(AuthoringDiagnostic::error);
        boolean strongSchema = diagnostics.stream().noneMatch(diagnostic ->
                "RG.AUTHORING.TYPE_UNRESOLVED".equals(diagnostic.code()));
        boolean importable = canonicalLibrary != null && !errors;
        String state = !importable
                ? "INVALID"
                : strongSchema ? "DESIGN_READY" : "DOCUMENTED_ONLY";
        List<AuthoringReadiness.Gate> gates = diagnostics.stream()
                .map(diagnostic -> new AuthoringReadiness.Gate(
                        diagnostic.code(),
                        diagnostic.level(),
                        diagnostic.message(),
                        diagnostic.authoringPath(),
                        diagnostic.error()
                ))
                .toList();
        AuthoringReadiness readiness = new AuthoringReadiness(
                state,
                importable,
                strongSchema,
                importable && strongSchema,
                false,
                gates
        );
        return new AuthoringCompileResult(
                AuthoringCompileResult.SCHEMA_VERSION,
                "",
                0,
                authoringFingerprint,
                compileFingerprint,
                COMPILER_VERSION,
                grammarVersion,
                archetypeFingerprint,
                AuthoringCompileResult.SERVER_AUTHORITATIVE,
                canonicalLibrary,
                canonicalFingerprint,
                sourceMap.entries(),
                List.copyOf(diagnostics),
                List.copyOf(confirmations),
                readiness,
                null,
                null
        );
    }

    private String fingerprintAuthoring(VisualLibraryAuthoringDocument document,
                                        List<AuthoringDiagnostic> diagnostics) {
        try {
            Map<String, Object> material = objectMapper.convertValue(
                    document, new TypeReference<Map<String, Object>>() {
                    });
            return VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, sortedMap(material), MAX_AUTHORING_BYTES);
        } catch (IllegalArgumentException exception) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.DOCUMENT_LIMIT_EXCEEDED",
                    exception.getMessage(),
                    "/"));
            return "";
        }
    }

    private static boolean resolveSecretPosture(
            VisualLibraryAuthoringDocument.OperatorAuthoring operator,
            OperatorArchetypeRegistry.Archetype archetype,
            String authoringPath,
            List<AuthoringDiagnostic> diagnostics,
            List<AuthoringCompileResult.ConfirmationRequest> confirmations) {
        if (operator.requiresSecrets() != null) {
            return operator.requiresSecrets();
        }
        if (archetype.requiresSecretsDefault() != null) {
            return archetype.requiresSecretsDefault();
        }
        diagnostics.add(AuthoringDiagnostic.error(
                "RG.AUTHORING.SECRET_POSTURE_REQUIRED",
                "Operator archetype '%s' requires an explicit requiresSecrets decision."
                        .formatted(archetype.name()),
                authoringPath + "/requiresSecrets"));
        confirmations.add(new AuthoringCompileResult.ConfirmationRequest(
                "RG.AUTHORING.SECRET_POSTURE_REQUIRED",
                authoringPath + "/requiresSecrets",
                "Does this operator require runtime secrets?",
                List.of("true", "false")
        ));
        return false;
    }

    private OperatorDefinition.SideEffectProtocol sideEffectProtocol(
            JsonNode runtime,
            String effect,
            String authoringPath,
            List<AuthoringDiagnostic> diagnostics) {
        if (!"WRITE_EXTERNAL".equals(effect)) {
            return OperatorDefinition.SideEffectProtocol.defaultFor(effect);
        }
        JsonNode protocol = runtime == null ? null : runtime.get("sideEffectProtocol");
        if (protocol == null || protocol.isNull()) {
            return OperatorDefinition.SideEffectProtocol.defaultFor(effect);
        }
        try {
            return objectMapper.treeToValue(protocol, OperatorDefinition.SideEffectProtocol.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            diagnostics.add(AuthoringDiagnostic.error(
                    "RG.AUTHORING.SIDE_EFFECT_PROTOCOL_INVALID",
                    "sideEffectProtocol cannot be parsed: " + exception.getOriginalMessage(),
                    authoringPath + "/runtime/sideEffectProtocol"));
            return OperatorDefinition.SideEffectProtocol.defaultFor(effect);
        }
    }

    private Map<String, Object> runtimeParameters(JsonNode runtime) {
        if (runtime == null || runtime.isNull() || !runtime.isObject()) {
            return Map.of();
        }
        Map<String, Object> values = objectMapper.convertValue(
                runtime, new TypeReference<Map<String, Object>>() {
                });
        values.remove("sideEffectProtocol");
        return sortedMap(values);
    }

    private static String functionTypeLabel(CompactTypeParser.TypeExpression expression,
                                            Map<String, Object> schema) {
        if (expression.arrayDepth() > 0) {
            return "array";
        }
        if (expression.nullable()) {
            return "any";
        }
        if (expression.primitive()) {
            return expression.baseName();
        }
        Object type = schema.get("type");
        String label = type instanceof String value ? value : "any";
        return FUNCTION_TYPE_LABELS.contains(label) ? label : "any";
    }

    private static String displayName(String explicit, String identifier) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String value = identifier == null ? "" : identifier;
        int separator = Math.max(value.lastIndexOf(':'), value.lastIndexOf('.'));
        String local = separator >= 0 ? value.substring(separator + 1) : value;
        String spaced = local.replace('-', ' ').replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim();
        if (spaced.isBlank()) {
            return value;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static void boundedSize(String label,
                                    int actual,
                                    int maximum,
                                    String path,
                                    List<AuthoringDiagnostic> diagnostics) {
        if (actual <= maximum) {
            return;
        }
        diagnostics.add(AuthoringDiagnostic.error(
                "RG.AUTHORING.COLLECTION_LIMIT_EXCEEDED",
                "Authoring %s count %d exceeds the %d item limit."
                        .formatted(label, actual, maximum),
                path));
    }

    private static String pointer(String value) {
        return (value == null ? "" : value).replace("~", "~0").replace("/", "~1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sortedMap(Map<String, ?> source) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (source == null) {
            return Map.of();
        }
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((nestedKey, nestedValue) ->
                        nested.put(String.valueOf(nestedKey), nestedValue));
                sorted.put(key, sortedMap(nested));
            } else if (value instanceof List<?> list) {
                sorted.put(key, list.stream()
                        .map(AuthoringCompiler::sortedValue)
                        .toList());
            } else {
                sorted.put(key, value);
            }
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    @SuppressWarnings("unchecked")
    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) ->
                    nested.put(String.valueOf(key), nestedValue));
            return sortedMap(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(AuthoringCompiler::sortedValue)
                    .toList();
        }
        return value;
    }

    private record CompiledType(
            Map<String, Object> schema,
            boolean strong,
            boolean success
    ) {
        private CompiledType {
            schema = schema == null ? Map.of() : sortedMap(schema);
        }

        private SchemaEnvelope envelope() {
            return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        }
    }

    private final class TypeCompiler {
        private final Map<String, JsonNode> namedTypes;
        private final List<AuthoringDiagnostic> diagnostics;
        private final AuthoringSourceMap.Builder sourceMap;

        private TypeCompiler(Map<String, JsonNode> namedTypes,
                             List<AuthoringDiagnostic> diagnostics,
                             AuthoringSourceMap.Builder sourceMap) {
            this.namedTypes = namedTypes;
            this.diagnostics = diagnostics;
            this.sourceMap = sourceMap;
        }

        private CompiledType compile(JsonNode node,
                                     String authoringPath,
                                     String canonicalPath,
                                     Set<String> stack,
                                     int depth) {
            sourceMap.add(authoringPath, canonicalPath);
            if (depth > MAX_TYPE_DEPTH) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.TYPE_DEPTH_EXCEEDED",
                        "Type nesting exceeds the %d level limit.".formatted(MAX_TYPE_DEPTH),
                        authoringPath));
                return new CompiledType(Map.of(), false, false);
            }
            if (node == null || node.isNull()) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.TYPE_REQUIRED",
                        "Type definition is required.",
                        authoringPath));
                return new CompiledType(Map.of(), false, false);
            }
            if (node.isTextual()) {
                return compileExpression(node.asText(), authoringPath, canonicalPath, stack, depth);
            }
            if (!node.isObject()) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.TYPE_INVALID",
                        "Type definition must be a compact string or structured object.",
                        authoringPath));
                return new CompiledType(Map.of(), false, false);
            }
            List<String> unsupported = new ArrayList<>();
            node.fieldNames().forEachRemaining(field -> {
                if (!STRUCTURED_TYPE_KEYS.contains(field)) {
                    unsupported.add(field);
                }
            });
            if (!unsupported.isEmpty()) {
                unsupported.stream().sorted().forEach(field ->
                        diagnostics.add(AuthoringDiagnostic.error(
                                "RG.AUTHORING.TYPE_CONSTRAINT_UNSUPPORTED",
                                "Quick authoring does not support schema constraint '%s'.".formatted(field),
                                authoringPath + "/" + pointer(field))));
            }

            JsonNode fields = node.get("fields");
            CompiledType base;
            if (fields != null) {
                base = compileFields(fields, node, authoringPath, canonicalPath, stack, depth);
            } else {
                JsonNode type = node.get("type");
                JsonNode enumValues = node.get("enum");
                if (type != null && type.isTextual()) {
                    base = compileExpression(
                            type.asText(), authoringPath + "/type", canonicalPath,
                            stack, depth + 1);
                } else if (enumValues != null && enumValues.isArray()) {
                    base = new CompiledType(enumBase(enumValues), true, true);
                } else {
                    diagnostics.add(AuthoringDiagnostic.error(
                            "RG.AUTHORING.TYPE_INVALID",
                            "Structured type must declare type, fields, or enum.",
                            authoringPath));
                    return new CompiledType(Map.of(), false, false);
                }
            }

            Map<String, Object> schema = new LinkedHashMap<>(base.schema());
            copyConstraint(node, schema, "minimum");
            copyConstraint(node, schema, "maximum");
            copyConstraint(node, schema, "minLength");
            copyConstraint(node, schema, "maxLength");
            copyConstraint(node, schema, "minItems");
            copyConstraint(node, schema, "maxItems");
            copyConstraint(node, schema, "description");
            if (node.has("enum") && node.get("enum").isArray()) {
                schema.put("enum", objectMapper.convertValue(node.get("enum"), List.class));
            }
            if (node.has("additionalProperties") && node.get("additionalProperties").isBoolean()
                    && "object".equals(schema.get("type"))) {
                schema.put("additionalProperties", node.get("additionalProperties").asBoolean());
            }
            return new CompiledType(schema, base.strong(), base.success() && unsupported.isEmpty());
        }

        private CompiledType compileExpression(String source,
                                               String authoringPath,
                                               String canonicalPath,
                                               Set<String> stack,
                                               int depth) {
            CompactTypeParser.ParseResult parsed = typeParser.parse(source);
            if (!parsed.valid()) {
                CompactTypeParser.ParseIssue issue = parsed.issues().getFirst();
                diagnostics.add(AuthoringDiagnostic.error(
                        issue.code(), issue.message(), authoringPath, issue.offset()));
                return new CompiledType(Map.of(), false, false);
            }
            CompactTypeParser.TypeExpression expression = parsed.expression();
            CompiledType base;
            if (expression.primitive()) {
                base = primitive(expression.baseName(), authoringPath);
            } else {
                JsonNode named = namedTypes.get(expression.baseName());
                if (named == null) {
                    diagnostics.add(AuthoringDiagnostic.error(
                            "RG.AUTHORING.TYPE_REF_NOT_FOUND",
                            "Named type '%s' was not found in this library."
                                    .formatted(expression.baseName()),
                            authoringPath));
                    return new CompiledType(Map.of(), false, false);
                }
                if (!stack.add(expression.baseName())) {
                    diagnostics.add(AuthoringDiagnostic.error(
                            "RG.AUTHORING.TYPE_CYCLE_UNSUPPORTED",
                            "Named type cycle involving '%s' is unsupported in Quick authoring."
                                    .formatted(expression.baseName()),
                            authoringPath));
                    return new CompiledType(Map.of(), false, false);
                }
                String typePath = "/types/" + pointer(expression.baseName());
                sourceMap.add(typePath, canonicalPath);
                base = compile(named, typePath, canonicalPath, stack, depth + 1);
                stack.remove(expression.baseName());
            }
            Map<String, Object> schema = new LinkedHashMap<>(base.schema());
            for (int array = 0; array < expression.arrayDepth(); array++) {
                schema = new LinkedHashMap<>(Map.of(
                        "type", "array",
                        "items", sortedMap(schema)
                ));
            }
            if (expression.nullable()) {
                schema = new LinkedHashMap<>(Map.of(
                        "anyOf", List.of(sortedMap(schema), Map.of("type", "null"))
                ));
            }
            return new CompiledType(schema, base.strong(), base.success());
        }

        private CompiledType compileFields(JsonNode fields,
                                           JsonNode owner,
                                           String authoringPath,
                                           String canonicalPath,
                                           Set<String> stack,
                                           int depth) {
            if (!fields.isObject()) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.TYPE_FIELDS_INVALID",
                        "fields must be an object keyed by field name.",
                        authoringPath + "/fields"));
                return new CompiledType(Map.of(), false, false);
            }
            if (fields.size() > MAX_FIELDS_PER_OBJECT) {
                diagnostics.add(AuthoringDiagnostic.error(
                        "RG.AUTHORING.COLLECTION_LIMIT_EXCEEDED",
                        "Object field count %d exceeds the %d item limit."
                                .formatted(fields.size(), MAX_FIELDS_PER_OBJECT),
                        authoringPath + "/fields"));
            }
            Map<String, Object> properties = new TreeMap<>();
            List<String> required = new ArrayList<>();
            boolean strong = true;
            boolean success = fields.size() <= MAX_FIELDS_PER_OBJECT;
            List<Map.Entry<String, JsonNode>> fieldEntries = new ArrayList<>();
            fieldEntries.addAll(fields.properties());
            fieldEntries.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, JsonNode> field : fieldEntries) {
                String rawName = field.getKey();
                boolean optional = rawName.endsWith("?");
                String fieldName = optional
                        ? rawName.substring(0, rawName.length() - 1) : rawName;
                String fieldPath = authoringPath + "/fields/" + pointer(rawName);
                String fieldCanonicalPath = canonicalPath + "/schema/properties/" + pointer(fieldName);
                CompiledType compiled = compile(
                        field.getValue(), fieldPath, fieldCanonicalPath,
                        new LinkedHashSet<>(stack), depth + 1);
                properties.put(fieldName, compiled.schema());
                if (!optional) {
                    required.add(fieldName);
                }
                strong &= compiled.strong();
                success &= compiled.success();
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", required.stream().sorted().toList());
            schema.put("additionalProperties",
                    owner.has("additionalProperties")
                            ? owner.path("additionalProperties").asBoolean(false)
                            : false);
            return new CompiledType(schema, strong, success);
        }

        private CompiledType primitive(String name, String authoringPath) {
            return switch (name) {
                case "any", "json" -> new CompiledType(Map.of(), true, true);
                case "unknown" -> {
                    diagnostics.add(AuthoringDiagnostic.warning(
                            "RG.AUTHORING.TYPE_UNRESOLVED",
                            "unknown leaves runtime data unconstrained until the author confirms a type.",
                            authoringPath));
                    yield new CompiledType(Map.of(), false, true);
                }
                case "date" -> new CompiledType(Map.of(
                        "type", "string",
                        "format", "date"
                ), true, true);
                case "datetime" -> new CompiledType(Map.of(
                        "type", "string",
                        "format", "date-time"
                ), true, true);
                case "object" -> new CompiledType(Map.of(
                        "type", "object",
                        "additionalProperties", true
                ), true, true);
                default -> new CompiledType(Map.of("type", name), true, true);
            };
        }

        private Map<String, Object> enumBase(JsonNode values) {
            Set<String> types = new LinkedHashSet<>();
            values.forEach(value -> {
                if (value.isTextual()) {
                    types.add("string");
                } else if (value.isIntegralNumber()) {
                    types.add("integer");
                } else if (value.isNumber()) {
                    types.add("number");
                } else if (value.isBoolean()) {
                    types.add("boolean");
                } else if (value.isNull()) {
                    types.add("null");
                }
            });
            Map<String, Object> schema = new LinkedHashMap<>();
            if (types.size() == 1) {
                schema.put("type", types.iterator().next());
            }
            schema.put("enum", objectMapper.convertValue(values, List.class));
            return schema;
        }

        private void copyConstraint(JsonNode source,
                                    Map<String, Object> target,
                                    String key) {
            if (!source.has(key)) {
                return;
            }
            target.put(key, objectMapper.convertValue(source.get(key), Object.class));
        }
    }
}
