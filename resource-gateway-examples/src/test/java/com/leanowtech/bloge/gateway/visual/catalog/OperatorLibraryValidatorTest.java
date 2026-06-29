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
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef,
                "1.0.0",
                new OperatorDefinition.Display(operatorRef, "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                ports,
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering(loweringMode, operatorRef, Map.of()),
                List.of()
        );
    }
}
