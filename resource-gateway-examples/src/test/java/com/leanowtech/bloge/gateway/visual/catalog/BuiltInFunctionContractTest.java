package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the callable identity used by validation, registries, and catalog assembly.
 */
class BuiltInFunctionContractTest {

    @Test
    void displayAndProvenanceMetadataDoNotChangeCallableFingerprint() {
        OperatorLibrary.BuiltInFunction first = function(
                "risk.normalize",
                "risk-a",
                "Normalize score",
                "integer",
                false,
                "number"
        );
        OperatorLibrary.BuiltInFunction renamed = new OperatorLibrary.BuiltInFunction(
                first.name(),
                "risk-b",
                "Normalize a bureau score",
                "Updated documentation.",
                "another-category",
                List.of(new OperatorLibrary.Signature(
                        "normalize(value)",
                        "Updated overload help.",
                        first.signatures().getFirst().parameters(),
                        new OperatorLibrary.ReturnValue("number", null, "Updated return help.")
                )),
                List.of("risk.normalize(inputs.score)")
        );

        assertThat(BuiltInFunctionContract.callableFingerprint(renamed))
                .isEqualTo(BuiltInFunctionContract.callableFingerprint(first));
        assertThat(BuiltInFunctionContract.compatible(first, renamed)).isTrue();
    }

    @Test
    void parameterAndReturnContractsChangeCallableFingerprint() {
        OperatorLibrary.BuiltInFunction baseline = function(
                "risk.normalize",
                "risk",
                "Normalize score",
                "integer",
                false,
                "number"
        );
        OperatorLibrary.BuiltInFunction optionalParameter = function(
                "risk.normalize",
                "risk",
                "Normalize score",
                "integer",
                true,
                "number"
        );
        OperatorLibrary.BuiltInFunction changedReturn = function(
                "risk.normalize",
                "risk",
                "Normalize score",
                "integer",
                false,
                "string"
        );

        assertThat(BuiltInFunctionContract.compatible(baseline, optionalParameter)).isFalse();
        assertThat(BuiltInFunctionContract.compatible(baseline, changedReturn)).isFalse();
    }

    @Test
    void nestedSchemaContentParticipatesInCallableFingerprint() {
        OperatorLibrary.BuiltInFunction integerField = schemaFunction("integer");
        OperatorLibrary.BuiltInFunction stringField = schemaFunction("string");

        assertThat(BuiltInFunctionContract.callableFingerprint(integerField))
                .isNotEqualTo(BuiltInFunctionContract.callableFingerprint(stringField));
    }

    private static OperatorLibrary.BuiltInFunction function(String name,
                                                            String namespace,
                                                            String displayName,
                                                            String parameterType,
                                                            boolean optional,
                                                            String returnType) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                namespace,
                displayName,
                "Function documentation.",
                "risk",
                List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter(
                                "value", parameterType, null, optional, false, "Input value.")),
                        new OperatorLibrary.ReturnValue(returnType, null, "Normalized value.")
                )),
                List.of(name + "(inputs.value)")
        );
    }

    private static OperatorLibrary.BuiltInFunction schemaFunction(String fieldType) {
        SchemaEnvelope schema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of(
                        "type", "object",
                        "properties", Map.of("score", Map.of("type", fieldType))
                )
        );
        return new OperatorLibrary.BuiltInFunction(
                "risk.readScore",
                "risk",
                "Read score",
                "",
                "risk",
                List.of(new OperatorLibrary.Signature(
                        "risk.readScore(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter("value", "object", schema, false, false, "")),
                        OperatorLibrary.ReturnValue.any()
                )),
                List.of()
        );
    }
}
