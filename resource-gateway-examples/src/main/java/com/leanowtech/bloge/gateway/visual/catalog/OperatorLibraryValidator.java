package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates user-provided operator libraries before they enter the visual catalog.
 */
@Service
public class OperatorLibraryValidator {

    private static final Set<String> RESERVED_OPERATOR_REFS = Set.of(
            "httpResource",
            "bloge:decisionTable",
            "bloge:transform"
    );
    private static final Set<String> SUPPORTED_LOWERING_MODES = Set.of("native", "transform");
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final Pattern VISUAL_OPERATOR_REF = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:(?::|\\.|-)" + IDENTIFIER_PATTERN + ")*");
    private static final Pattern PORT_NAME = Pattern.compile(IDENTIFIER_PATTERN);
    private static final Pattern EXECUTABLE_OPERATOR_REF = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:(?::|\\.|-)" + IDENTIFIER_PATTERN + ")*");
    private static final Pattern PATH_PATTERN = Pattern.compile(
            IDENTIFIER_PATTERN + "(?:\\." + IDENTIFIER_PATTERN + ")*");
    private static final Pattern TEMPLATE_REFERENCE = Pattern.compile("\\{\\{\\s*((?:input\\.)?"
            + IDENTIFIER_PATTERN + "(?:\\." + IDENTIFIER_PATTERN + ")*)\\s*}}");
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
        if (library.operators().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.empty",
                    "Operator library must contain at least one operator.",
                    "/operators"));
        }
        if (library.libraryId().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.id.required",
                    "Operator library must declare a libraryId.",
                    "/libraryId"));
        }
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            String operatorPath = "/operators/" + i;
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
        return new VisualValidationResult(true, diagnostics);
    }

    private static boolean isReservedOperatorRef(String operatorRef) {
        return RESERVED_OPERATOR_REFS.contains(operatorRef) || operatorRef.startsWith("resource:");
    }

    private static void validateOperator(OperatorDefinition operator,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        if (operator.ports().outputs().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.output.required",
                    "Operator '%s' must declare at least one output port.".formatted(operator.operatorRef()),
                    path + "/ports/outputs"));
        }
        validatePorts(operator, "inputs", operator.ports().inputs(), path + "/ports/inputs", diagnostics);
        validatePorts(operator, "outputs", operator.ports().outputs(), path + "/ports/outputs", diagnostics);
        diagnostics.addAll(VisualSchemaValidator.validateSchema(
                operator.configSchema().schema(), path + "/configSchema/schema"));
        validateLowering(operator, path + "/lowering", diagnostics);
        diagnostics.addAll(VisualSecretGuard.detectOperatorSecrets(operator, path));
    }

    private static void validatePorts(OperatorDefinition operator,
                                      String direction,
                                      List<OperatorDefinition.Port> ports,
                                      String path,
                                      List<VisualDiagnostic> diagnostics) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < ports.size(); i++) {
            OperatorDefinition.Port port = ports.get(i);
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
            diagnostics.addAll(VisualSchemaValidator.validateSchema(
                    port.schema().schema(), path + "/" + i + "/schema/schema"));
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
        if ("native".equals(mode)) {
            validateNativeLowering(operator, path, diagnostics);
            return;
        }
        validateTransformLowering(operator, path, diagnostics);
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
    }

    private static void validateTransformLowering(OperatorDefinition operator,
                                                  String path,
                                                  List<VisualDiagnostic> diagnostics) {
        if (operator.ports().outputs().size() != 1
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
        Set<String> assignedTargets = new LinkedHashSet<>();
        assignments.forEach((target, rawExpression) -> {
            String assignmentPath = path + "/parameters/assignments/" + target;
            validateTransformAssignmentTarget(operator, output, target, assignmentPath, diagnostics);
            assignedTargets.add(target);
            validateTransformAssignmentExpression(operator, rawExpression, assignmentPath, diagnostics);
        });
        validateRequiredTransformOutputs(operator, output, assignedTargets, path + "/parameters/assignments",
                diagnostics);
    }

    private static void validateTransformAssignmentTarget(OperatorDefinition operator,
                                                          OperatorDefinition.Port output,
                                                          String target,
                                                          String path,
                                                          List<VisualDiagnostic> diagnostics) {
        if (!PATH_PATTERN.matcher(target).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTarget.invalid",
                    "Transform assignment target '%s' on operator '%s' must be a dotted identifier path."
                            .formatted(target, operator.operatorRef()),
                    path));
            return;
        }
        if (propertyAtPath(output.schema(), target) == null) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTarget.unknown",
                    "Transform assignment target '%s' is not declared by output schema on operator '%s'."
                            .formatted(target, operator.operatorRef()),
                    path));
        }
    }

    private static void validateTransformAssignmentExpression(OperatorDefinition operator,
                                                              Object rawExpression,
                                                              String path,
                                                              List<VisualDiagnostic> diagnostics) {
        if (!(rawExpression instanceof String expression) || expression.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentExpression.invalid",
                    "Transform assignment expression on operator '%s' must be a non-empty string."
                            .formatted(operator.operatorRef()),
                    path));
            return;
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
    }

    private static Matcher templateReference(String token) {
        return TEMPLATE_REFERENCE.matcher("{{" + token + "}}");
    }

    private static void validateRequiredTransformOutputs(OperatorDefinition operator,
                                                         OperatorDefinition.Port output,
                                                         Set<String> assignedTargets,
                                                         String path,
                                                         List<VisualDiagnostic> diagnostics) {
        for (String required : requiredPaths(output.schema().schema())) {
            boolean satisfied = assignedTargets.stream().anyMatch(target -> satisfiesRequiredPath(target, required));
            if (!satisfied) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.assignmentTarget.required",
                        "Transform-lowered operator '%s' must assign required output '%s'."
                                .formatted(operator.operatorRef(), required),
                        path + "/" + required));
            }
        }
    }

    private static boolean inputPathExists(OperatorDefinition operator, String inputPath) {
        if (inputPath.isBlank()) {
            return false;
        }
        String[] segments = inputPath.split("\\.", 2);
        String first = segments[0];
        String rest = segments.length == 2 ? segments[1] : "";
        for (OperatorDefinition.Port port : operator.ports().inputs()) {
            if (port.name().equals(first) && !rest.isBlank() && propertyAtPath(port.schema(), rest) != null) {
                return true;
            }
        }
        if (operator.ports().inputs().size() == 1) {
            return propertyAtPath(operator.ports().inputs().getFirst().schema(), inputPath) != null;
        }
        return false;
    }

    private static boolean satisfiesRequiredPath(String assignmentTarget, String requiredPath) {
        return assignmentTarget.equals(requiredPath)
                || assignmentTarget.startsWith(requiredPath + ".")
                || requiredPath.startsWith(assignmentTarget + ".");
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
            current = objectProperty(propertiesOf(currentSchema).get(segment));
            if (current == null) {
                return allowsAdditionalProperties(currentSchema) ? Map.of() : null;
            }
            currentSchema = current;
        }
        return current;
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

    private static boolean allowsAdditionalProperties(Map<String, Object> schema) {
        Object additional = schema.get("additionalProperties");
        return Boolean.TRUE.equals(additional) || additional instanceof Map<?, ?>;
    }

}
