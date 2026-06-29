package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates user-provided operator libraries before they enter the visual catalog.
 */
@Service
public class OperatorLibraryValidator {

    private static final Set<String> SUPPORTED_LOWERING_MODES = Set.of("native", "transform");

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
            } else if (!operatorRefs.add(operator.operatorRef())) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.ref.duplicate",
                        "Operator library declares duplicate operatorRef '%s'."
                                .formatted(operator.operatorRef()),
                        operatorPath + "/operatorRef"));
            }
            validateOperator(operator, operatorPath, diagnostics);
        }
        return new VisualValidationResult(true, diagnostics);
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
        }
    }
}
