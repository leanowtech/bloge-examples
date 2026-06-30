package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for user-provided operator library validation.
 */
class OperatorLibraryValidatorTest {

    private final OperatorLibraryValidator validator = new OperatorLibraryValidator();

    @Test
    void acceptsValidLibrary() {
        VisualValidationResult result = validator.validate(VisualCatalogTestSupport.eligibilityLibrary("integer"));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsTransformLoweringWithPortQualifiedTemplateReferences() {
        VisualValidationResult result = validator.validate(VisualCatalogTestSupport.duplicateInputPathLibrary());

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void rejectsEmptyLibrary() {
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "empty",
                "Empty",
                "1.0.0",
                "team",
                "ACTIVE",
                List.of()
        );

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.library.empty"));
    }

    @Test
    void rejectsBlankLibraryIdAndOperatorRef() {
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "",
                "Invalid",
                "1.0.0",
                "team",
                "ACTIVE",
                List.of(operator(
                        "",
                        new OperatorDefinition.Ports(
                                List.of(),
                                List.of(new OperatorDefinition.Port("output",
                                        SchemaEnvelope.object(Map.of(), List.of()),
                                        true,
                                        "Output."))
                        ),
                        "native"
                ))
        );

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.library.id.required",
                        "visual.operator.ref.required"
                );
    }

    @Test
    void rejectsDuplicateOperatorRefsInOneLibrary() {
        OperatorDefinition first = operator(
                "risk:eligibility",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(), List.of()),
                                true,
                                "Output."))
                ),
                "native"
        );
        OperatorDefinition duplicate = operator(
                "risk:eligibility",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(), List.of()),
                                true,
                                "Output."))
                ),
                "native"
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-duplicates",
                "Risk duplicates",
                "1.0.0",
                "team",
                "ACTIVE",
                List.of(first, duplicate)
        );

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.ref.duplicate");
                    assertThat(diagnostic.target()).isEqualTo("/operators/1/operatorRef");
                });
    }

    @Test
    void rejectsSystemReservedOperatorRefs() {
        OperatorDefinition builtInCollision = operator(
                "httpResource",
                outputOnlyPorts(Map.of("ok", Map.of("type", "boolean")), List.of()),
                "native"
        );
        OperatorDefinition resourceNamespaceCollision = operator(
                "resource:loan-applicant-service.getProfile",
                outputOnlyPorts(Map.of("ok", Map.of("type", "boolean")), List.of()),
                "native"
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "reserved-refs",
                "Reserved refs",
                "1.0.0",
                "team",
                "ACTIVE",
                List.of(builtInCollision, resourceNamespaceCollision)
        );

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "visual.operator.ref.reserved".equals(diagnostic.code()))
                .extracting("target")
                .containsExactly("/operators/0/operatorRef", "/operators/1/operatorRef");
    }

    @Test
    void rejectsOperatorWithoutOutputPort() {
        OperatorLibrary library = libraryWith(operator(
                "risk:noOutput",
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of()),
                                true,
                                "Inputs.")),
                        List.of()),
                "native"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.operator.output.required"));
    }

    @Test
    void rejectsInvalidSchemaDetails() {
        OperatorLibrary library = libraryWith(operator(
                "risk:badSchema",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "items", Map.of("type", "array"),
                                                "mystery", Map.of("type", "unknown")
                                        ),
                                        "required", List.of("missing")
                                )),
                                true,
                                "Bad output."))
                ),
                "native"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.requiredUnknown",
                        "visual.schema.arrayItemsMissing",
                        "visual.schema.unsupportedType"
                );
    }

    @Test
    void rejectsInvalidRequiredSchemaShape() {
        OperatorLibrary library = libraryWith(operator(
                "risk:badRequired",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "score", Map.of("type", "integer")
                                        ),
                                        "required", List.of("score", "", 42, "score")
                                )),
                                true,
                                "Bad required."))
                ),
                "native"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.requiredInvalid",
                        "visual.schema.requiredDuplicate"
                );
    }

    @Test
    void rejectsInvalidObjectSchemaStructure() {
        OperatorLibrary library = libraryWith(operator(
                "risk:badObjectSchema",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                                        "type", "object",
                                        "properties", "score",
                                        "additionalProperties", "false"
                                )),
                                true,
                                "Bad object schema."))
                ),
                "native"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.propertiesInvalid",
                        "visual.schema.additionalPropertiesInvalid"
                );
    }

    @Test
    void rejectsInvalidConfigSchemaDetails() {
        OperatorLibrary library = libraryWith(new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badConfigSchema",
                "1.0.0",
                new OperatorDefinition.Display("Bad config schema", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Output."))
                ),
                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "modes", Map.of("type", "array")
                        )
                )),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:badConfigSchema", Map.of()),
                List.of()
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.arrayItemsMissing");
                    assertThat(diagnostic.target()).contains("configSchema");
                });
    }

    @Test
    void rejectsDuplicatePortNamesAndUnsupportedLoweringMode() {
        OperatorLibrary library = libraryWith(operator(
                "risk:badPorts",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(
                                new OperatorDefinition.Port("output", SchemaEnvelope.object(Map.of(), List.of()),
                                        true, "First output."),
                                new OperatorDefinition.Port("output", SchemaEnvelope.object(Map.of(), List.of()),
                                        true, "Second output."))
                ),
                "ai-tool"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.operator.port.duplicate",
                        "visual.operator.lowering.unsupported"
                );
    }

    @Test
    void rejectsNativeLoweringWithoutExecutableOperatorRef() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nativeWithoutExecutableRef",
                "1.0.0",
                new OperatorDefinition.Display("Bad native", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                outputOnlyPorts(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.operatorRef.required");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/lowering/operatorRef");
                });
    }

    @Test
    void rejectsNativeLoweringWithUnsafeExecutableOperatorRef() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nativeWithUnsafeExecutableRef",
                "1.0.0",
                new OperatorDefinition.Display("Bad native", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                outputOnlyPorts(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk badExecutableRef", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.operatorRef.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/lowering/operatorRef");
                });
    }

    @Test
    void rejectsTransformLoweringWithoutAssignments() {
        OperatorDefinition operator = transformOperator(
                "risk:missingAssignments",
                Map.of("score", Map.of("type", "integer")),
                List.of("score"),
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.assignments.required");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/lowering/parameters/assignments");
                });
    }

    @Test
    void rejectsTransformLoweringWhenAssignmentsDoNotMatchOutputSchema() {
        OperatorDefinition operator = transformOperator(
                "risk:unknownAssignmentTarget",
                Map.of("score", Map.of("type", "integer")),
                List.of("score"),
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of("decision", "{{input.score}} >= 700")
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.operator.lowering.assignmentTarget.unknown",
                        "visual.operator.lowering.assignmentTarget.required"
                );
    }

    @Test
    void rejectsTransformLoweringWhenTemplateReferencesUnknownInput() {
        OperatorDefinition operator = transformOperator(
                "risk:unknownTemplateInput",
                Map.of("score", Map.of("type", "integer")),
                List.of("score"),
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of("accepted", "{{input.missingScore}} >= 700")
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.template.unknownInput");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/lowering/parameters/assignments/accepted");
                });
    }

    @Test
    void rejectsTransformLoweringWithUnsupportedOutputPortShape() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unsupportedTransformOutput",
                "1.0.0",
                new OperatorDefinition.Display("Bad transform", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
                                true,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("result",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")),
                                        List.of("accepted")),
                                true,
                                "Result."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "{{input.score}} >= 700")
                )),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.operator.lowering.transformOutputUnsupported"));
    }

    @Test
    void rejectsRawSecretInLoweringParametersWithoutEchoingValue() {
        String rawSecret = "sk-testSecretToken123456";
        OperatorDefinition operator = operator(
                "risk:secretLowering",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Output."))
                ),
                "native",
                Map.of("apiKey", rawSecret)
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.secret.raw");
                    assertThat(diagnostic.message()).doesNotContain(rawSecret);
                    assertThat(diagnostic.target()).contains("lowering").contains("apiKey");
                });
    }

    private static OperatorDefinition transformOperator(String operatorRef,
                                                        Map<String, Object> inputProperties,
                                                        List<String> inputRequired,
                                                        Map<String, Object> outputProperties,
                                                        List<String> outputRequired,
                                                        Map<String, Object> assignments) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef,
                "1.0.0",
                new OperatorDefinition.Display(operatorRef, "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, inputRequired),
                                true,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, outputRequired),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", assignments
                )),
                List.of()
        );
    }

    private static OperatorDefinition.Ports outputOnlyPorts(Map<String, Object> outputProperties,
                                                            List<String> outputRequired) {
        return new OperatorDefinition.Ports(
                List.of(),
                List.of(new OperatorDefinition.Port("output",
                        SchemaEnvelope.object(outputProperties, outputRequired),
                        true,
                        "Output."))
        );
    }

    private static OperatorLibrary libraryWith(OperatorDefinition operator) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-validation-test",
                "Risk validation test",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorDefinition operator(String operatorRef,
                                               OperatorDefinition.Ports ports,
                                               String loweringMode) {
        return operator(operatorRef, ports, loweringMode, Map.of());
    }

    private static OperatorDefinition operator(String operatorRef,
                                               OperatorDefinition.Ports ports,
                                               String loweringMode,
                                               Map<String, Object> loweringParameters) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef,
                "1.0.0",
                new OperatorDefinition.Display(operatorRef, "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                ports,
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering(loweringMode, operatorRef, loweringParameters),
                List.of()
        );
    }
}
