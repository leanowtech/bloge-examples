package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
    void rejectsUnsupportedLibrarySchemaVersion() {
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v2",
                "future-risk",
                "Future risk",
                "1.0.0",
                "team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.library.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                });
    }

    @Test
    void rejectsUnsupportedOperatorSchemaVersion() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition futureOperator = new OperatorDefinition(
                "bloge.visualOperator.v2",
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );

        VisualValidationResult result = validator.validate(libraryWith(futureOperator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/schemaVersion");
                });
    }

    @Test
    void acceptsSupportedLibraryLifecycleStatuses() {
        VisualValidationResult deprecated = validator.validate(libraryWithStatus("deprecated-policy", "deprecated"));
        VisualValidationResult disabled = validator.validate(libraryWithStatus("disabled-policy", "DISABLED"));

        assertThat(deprecated.valid()).isTrue();
        assertThat(disabled.valid()).isTrue();
    }

    @Test
    void rejectsUnsupportedOperatorCapabilities() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities("NETWORK_MAGIC", "MAYBE", false, false),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.operator.capability.effectUnsupported",
                        "visual.operator.capability.idempotencyUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/capabilities/effect",
                        "/operators/0/capabilities/idempotency"
                );
    }

    @Test
    void acceptsCanonicalizedOperatorCapabilities() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities(" read_external ", " idempotent ", false, false),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(operator.capabilities().effect()).isEqualTo("READ_EXTERNAL");
        assertThat(operator.capabilities().idempotency()).isEqualTo("IDEMPOTENT");
    }

    @Test
    void acceptsCanonicalizedLoweringMode() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering(" Transform ", base.lowering().operatorRef(),
                        base.lowering().parameters()),
                base.diagnostics()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(operator.lowering().mode()).isEqualTo("transform");
    }

    @Test
    void rejectsPolicyScopesThatMixWildcardAndConcreteValues() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                new OperatorDefinition.Policy(
                        List.of("*", "demo-tenant"),
                        List.of("local"),
                        List.of("*", "browser")),
                base.lowering(),
                base.diagnostics()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .containsExactly(
                        "visual.operator.policy.scopeWildcardMixed",
                        "visual.operator.policy.scopeWildcardMixed"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .containsExactly(
                        "/operators/0/policy/tenants",
                        "/operators/0/policy/environments"
                );
    }

    @Test
    void acceptsWildcardOnlyPolicyScopes() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                new OperatorDefinition.Policy(List.of("*"), List.of("*"), List.of("*")),
                base.lowering(),
                base.diagnostics()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsUnsupportedLibraryLifecycleStatus() {
        VisualValidationResult result = validator.validate(libraryWithStatus("archived-policy", "ARCHIVED"));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.library.status.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/status");
                });
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
    void rejectsUnsafeOperatorRefAndPortNames() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk badRef",
                "1.0.0",
                new OperatorDefinition.Display("Bad tokens", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("bad.input",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of()),
                                true,
                                "Bad input.")),
                        List.of(new OperatorDefinition.Port("bad-output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Bad output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:safeExecutableRef", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.operator.ref.invalid",
                        "visual.operator.port.name.invalid"
                );
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "visual.operator.port.name.invalid".equals(diagnostic.code()))
                .extracting("target")
                .containsExactly(
                        "/operators/0/ports/inputs/0/name",
                        "/operators/0/ports/outputs/0/name"
                );
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
    void rejectsInvalidObjectUnevaluatedPropertiesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectUnevaluatedProperties",
                "1.0.0",
                new OperatorDefinition.Display("Bad unevaluated properties", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "string",
                                        "unevaluatedProperties", false
                                )),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "unevaluatedProperties", "nope"
                                )),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routing", Map.of(
                                "type", "object",
                                "properties", Map.of("mode", Map.of("type", "string")),
                                "unevaluatedProperties", false,
                                "default", Map.of("mode", "auto", "shadow", "yes"))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectUnevaluatedProperties", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.unevaluatedPropertiesConstraintTypeMismatch",
                        "visual.schema.unevaluatedPropertiesInvalid",
                        "visual.schema.defaultUnknownProperty"
                );
    }

    @Test
    void rejectsUnsupportedMultiConcreteTypeArraysAcrossOperatorDefinitions() {
        OperatorLibrary library = libraryWith(operator(
                "risk:badNullableUnion",
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "score", Map.of("type", List.of("integer", "string", "null")))
                                )),
                                true,
                                "Input.")),
                        List.of()
                ),
                "native"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.schema.typeUnionUnsupported");
    }

    @Test
    void rejectsInvalidEnumSchemaShape() {
        OperatorLibrary library = libraryWith(operator(
                "risk:badEnumSchema",
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "decision", Map.of("type", "string", "enum", "APPROVE"),
                                                "tier", Map.of("type", "string",
                                                        "enum", List.of("LOW", 1, "LOW"))
                                        )
                                )),
                                true,
                                "Bad enum schema."))
                ),
                "native"
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.enumInvalid",
                        "visual.schema.enumDuplicate",
                        "visual.schema.enumTypeMismatch"
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
    void acceptsConstSchemasAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:constPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Const policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "eventType", Map.of("const", "loan.submitted")
                                ), List.of("eventType")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "decision", Map.of("type", "string", "const", "APPROVE")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "strategy", Map.of("type", "string", "const", "strict", "default", "strict")
                ), List.of("strategy")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskConstPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsConstValueThatDoesNotMatchDeclaredSchemaType() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badConstPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad const policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "mode", Map.of("type", "integer", "const", "strict")
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadConstPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.constTypeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/configSchema/schema/properties/mode/const");
                });
    }

    @Test
    void acceptsNumericBoundsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:numericBoundsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Numeric bounds policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "score", Map.of("type", "integer", "minimum", 300, "maximum", 900)
                                ), List.of("score")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "ratio", Map.of(
                                                "type", "number",
                                                "exclusiveMinimum", 0,
                                                "exclusiveMaximum", 1
                                        )
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "threshold", Map.of("type", "integer", "minimum", 300, "maximum", 900, "default", 700)
                ), List.of("threshold")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskNumericBoundsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidNumericBoundsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badNumericBoundsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad numeric bounds policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "score", Map.of("type", "integer", "minimum", "low")
                                ), List.of("score")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "risk", Map.of("type", "integer", "minimum", 900, "maximum", 300)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "threshold", Map.of("type", "integer", "minimum", 300, "maximum", 900, "default", 950)
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadNumericBoundsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.numericConstraintInvalid",
                        "visual.schema.numericBoundsInvalid",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/score/minimum",
                        "/operators/0/ports/outputs/0/schema/schema/properties/risk",
                        "/operators/0/configSchema/schema/properties/threshold/default"
                );
    }

    @Test
    void acceptsNumericMultipleOfAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:numericMultiplePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Numeric multiple policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "score", Map.of("type", "integer", "multipleOf", 5)
                                ), List.of("score")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "ratio", Map.of("type", "number", "multipleOf", 0.25)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "threshold", Map.of("type", "integer", "multipleOf", 10, "default", 700)
                ), List.of("threshold")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskNumericMultiplePolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidNumericMultipleOfAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badNumericMultiplePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad numeric multiple policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "score", Map.of("type", "integer", "multipleOf", 0)
                                ), List.of("score")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "risk", Map.of("type", "string", "multipleOf", 10)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "threshold", Map.of("type", "integer", "multipleOf", 10, "default", 705)
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadNumericMultiplePolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.multipleOfConstraintInvalid",
                        "visual.schema.multipleOfConstraintTypeMismatch",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/score/multipleOf",
                        "/operators/0/ports/outputs/0/schema/schema/properties/risk",
                        "/operators/0/configSchema/schema/properties/threshold/default"
                );
    }

    @Test
    void acceptsStringLengthConstraintsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:stringLengthPolicy",
                "1.0.0",
                new OperatorDefinition.Display("String length policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "customerId", Map.of("type", "string", "minLength", 8, "maxLength", 16)
                                ), List.of("customerId")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "trackingCode", Map.of("type", "string", "minLength", 10, "maxLength", 24)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channel", Map.of("type", "string", "minLength", 3, "maxLength", 12, "default", "web")
                ), List.of("channel")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskStringLengthPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidStringLengthConstraintsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badStringLengthPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad string length policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "customerId", Map.of("type", "string", "minLength", 10, "maxLength", 4)
                                ), List.of("customerId")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "trackingCode", Map.of("type", "string", "maxLength", "short")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channel", Map.of("type", "string", "minLength", 3, "maxLength", 12, "default", "ok")
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadStringLengthPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.stringLengthBoundsInvalid",
                        "visual.schema.stringLengthConstraintInvalid",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/customerId",
                        "/operators/0/ports/outputs/0/schema/schema/properties/trackingCode/maxLength",
                        "/operators/0/configSchema/schema/properties/channel/default"
                );
    }

    @Test
    void acceptsStringPatternsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:stringPatternPolicy",
                "1.0.0",
                new OperatorDefinition.Display("String pattern policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "customerCode", Map.of("type", "string", "pattern", "^[A-Z]{2}\\d{4}$")
                                ), List.of("customerCode")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "trackingCode", Map.of("type", "string", "pattern", "^TRK-[A-Z0-9]{8}$")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channel", Map.of("type", "string", "pattern", "^[A-Z]{3}-\\d{2}$", "default", "WEB-01")
                ), List.of("channel")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskStringPatternPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidStringPatternsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badStringPatternPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad string pattern policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "customerCode", Map.of("type", "string", "pattern", 123)
                                ), List.of("customerCode")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "trackingCode", Map.of("type", "string", "pattern", "[A-Z")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "threshold", Map.of("type", "integer", "pattern", "^\\d+$"),
                        "channel", Map.of("type", "string", "pattern", "^[A-Z]{3}$", "default", "web")
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadStringPatternPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.patternConstraintInvalid",
                        "visual.schema.patternConstraintTypeMismatch",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/customerCode/pattern",
                        "/operators/0/ports/outputs/0/schema/schema/properties/trackingCode/pattern",
                        "/operators/0/configSchema/schema/properties/threshold",
                        "/operators/0/configSchema/schema/properties/channel/default"
                );
    }

    @Test
    void acceptsStringFormatsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:stringFormatPolicy",
                "1.0.0",
                new OperatorDefinition.Display("String format policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "customerEmail", Map.of("type", "string", "format", "email")
                                ), List.of("customerEmail")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "traceId", Map.of("type", "string", "format", "uuid")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "callbackUri", Map.of("type", "string", "format", "uri", "default", "https://callback.example.test/hook"),
                        "ttl", Map.of("type", "duration", "format", "duration", "default", "PT30M")
                ), List.of("callbackUri")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskStringFormatPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidStringFormatsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badStringFormatPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad string format policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "customerEmail", Map.of("type", "string", "format", 123)
                                ), List.of("customerEmail")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "traceId", Map.of("type", "integer", "format", "uuid")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "callbackUri", Map.of("type", "string", "format", "uri", "default", "not a uri"),
                        "phone", Map.of("type", "string", "format", "phone")
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadStringFormatPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.formatConstraintInvalid",
                        "visual.schema.formatConstraintTypeMismatch",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/customerEmail/format",
                        "/operators/0/ports/outputs/0/schema/schema/properties/traceId",
                        "/operators/0/configSchema/schema/properties/callbackUri/default",
                        "/operators/0/configSchema/schema/properties/phone/format"
                );
    }

    @Test
    void acceptsArrayItemBoundsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:arrayBoundsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Array bounds policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "integer"),
                                                "minItems", 1,
                                                "maxItems", 5)
                                ), List.of("items")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "segments", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "minItems", 1,
                                                "maxItems", 3)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channels", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "minItems", 1,
                                "maxItems", 3,
                                "default", List.of("web"))
                ), List.of("channels")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskArrayBoundsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidArrayItemBoundsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badArrayBoundsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad array bounds policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "integer"),
                                                "minItems", 3,
                                                "maxItems", 1)
                                ), List.of("items")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "segments", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "maxItems", "many")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channels", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "minItems", 1,
                                "maxItems", 2,
                                "default", List.of("web", "app", "branch"))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadArrayBoundsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.arrayItemBoundsInvalid",
                        "visual.schema.arrayItemConstraintInvalid",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/items",
                        "/operators/0/ports/outputs/0/schema/schema/properties/segments/maxItems",
                        "/operators/0/configSchema/schema/properties/channels/default"
                );
    }

    @Test
    void acceptsArrayUniqueItemsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:arrayUniquePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Array unique policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "uniqueItems", true)
                                ), List.of("items")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "segments", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "integer"),
                                                "uniqueItems", true)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channels", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "uniqueItems", true,
                                "default", List.of("web", "app"))
                ), List.of("channels")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskArrayUniquePolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidArrayUniqueItemsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badArrayUniquePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad array unique policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "integer"),
                                                "uniqueItems", "yes")
                                ), List.of("items")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "segments", Map.of(
                                                "type", "string",
                                                "uniqueItems", true)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channels", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "uniqueItems", true,
                                "default", List.of("web", "web"))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadArrayUniquePolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.uniqueItemsConstraintInvalid",
                        "visual.schema.uniqueItemsConstraintTypeMismatch",
                        "visual.schema.defaultConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/items/uniqueItems",
                        "/operators/0/ports/outputs/0/schema/schema/properties/segments",
                        "/operators/0/configSchema/schema/properties/channels/default"
                );
    }

    @Test
    void acceptsArrayPrefixItemsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:arrayPrefixItemsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Array prefix-items policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "tuple", tuplePrefixItemsSchema(Map.of())
                                ), List.of("tuple")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "audit", tuplePrefixItemsSchema(Map.of())
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "tuple", tuplePrefixItemsSchema(Map.of(
                                "default", List.of(42, "route", "tail")))
                ), List.of("tuple")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskArrayPrefixItemsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidArrayPrefixItemsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badArrayPrefixItemsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad array prefix-items policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "tuple", tuplePrefixItemsSchema(Map.of(
                                                "prefixItems", List.of("integer")))
                                ), List.of("tuple")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "segments", Map.of(
                                                "type", "string",
                                                "prefixItems", List.of(Map.of("type", "string")))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "tuple", tuplePrefixItemsSchema(Map.of(
                                "default", List.of("bad", "route"))),
                        "fixed", tuplePrefixItemsSchema(Map.of(
                                "const", List.of("bad", "route"))),
                        "choices", tuplePrefixItemsSchema(Map.of(
                                "enum", List.of(List.of("bad", "route"))))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadArrayPrefixItemsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.prefixItemsInvalid",
                        "visual.schema.prefixItemsConstraintTypeMismatch",
                        "visual.schema.defaultTypeMismatch",
                        "visual.schema.constConstraintMismatch",
                        "visual.schema.enumConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/tuple/prefixItems/0",
                        "/operators/0/ports/outputs/0/schema/schema/properties/segments",
                        "/operators/0/configSchema/schema/properties/tuple/default/0",
                        "/operators/0/configSchema/schema/properties/fixed/const",
                        "/operators/0/configSchema/schema/properties/choices/enum/0"
                );
    }

    @Test
    void acceptsArrayContainsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:arrayContainsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Array contains policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "channels", arrayContainsPrimarySchema(Map.of())
                                ), List.of("channels")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "flags", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "contains", Map.of("type", "string", "pattern", "^critical\\."),
                                                "minContains", 1)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channels", arrayContainsPrimarySchema(Map.of(
                                "default", List.of("secondary", "primary")))
                ), List.of("channels")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskArrayContainsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidArrayContainsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badArrayContainsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad array contains policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "channels", arrayContainsPrimarySchema(Map.of(
                                                "minContains", 2,
                                                "maxContains", 1))
                                ), List.of("channels")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "segments", Map.of(
                                                "type", "string",
                                                "contains", Map.of("type", "string")),
                                        "badContains", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "contains", "primary")
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "channels", arrayContainsPrimarySchema(Map.of(
                                "default", List.of("secondary"))),
                        "fixed", arrayContainsPrimarySchema(Map.of(
                                "const", List.of("secondary"))),
                        "choices", arrayContainsPrimarySchema(Map.of(
                                "enum", List.of(List.of("secondary"))))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadArrayContainsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.containsBoundsInvalid",
                        "visual.schema.containsConstraintTypeMismatch",
                        "visual.schema.containsConstraintInvalid",
                        "visual.schema.defaultConstraintMismatch",
                        "visual.schema.constConstraintMismatch",
                        "visual.schema.enumConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/channels",
                        "/operators/0/ports/outputs/0/schema/schema/properties/segments",
                        "/operators/0/ports/outputs/0/schema/schema/properties/badContains/contains",
                        "/operators/0/configSchema/schema/properties/channels/default",
                        "/operators/0/configSchema/schema/properties/fixed/const",
                        "/operators/0/configSchema/schema/properties/choices/enum/0"
                );
    }

    @Test
    void acceptsObjectPropertyBoundsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectBoundsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object bounds policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "filters", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "status", Map.of("type", "string"),
                                                        "region", Map.of("type", "string")
                                                ),
                                                "minProperties", 1,
                                                "maxProperties", 2)
                                ), List.of("filters")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "summary", Map.of(
                                                "type", "object",
                                                "additionalProperties", Map.of("type", "string"),
                                                "minProperties", 1,
                                                "maxProperties", 4)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routing", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "routeMode", Map.of("type", "string"),
                                        "region", Map.of("type", "string")
                                ),
                                "minProperties", 1,
                                "maxProperties", 2,
                                "default", Map.of("routeMode", "auto"))
                ), List.of("routing")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectBoundsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsObjectPropertyNamesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectPropertyNamesPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object property names policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "filters", Map.of(
                                                "type", "object",
                                                "additionalProperties", Map.of("type", "string"),
                                                "propertyNames", Map.of("pattern", "^filter\\.[a-z]+$"))
                                ), List.of("filters")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "facets", Map.of(
                                                "type", "object",
                                                "additionalProperties", Map.of("type", "integer"),
                                                "propertyNames", Map.of("type", "string",
                                                        "enum", List.of("tier", "segment")))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "headers", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "propertyNames", Map.of("pattern", "^X-[A-Za-z0-9-]+$"),
                                "default", Map.of("X-Risk-Mode", "audit"))
                ), List.of("headers")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectPropertyNamesPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsObjectPatternPropertiesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectPatternPropertiesPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object pattern properties policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "metrics", Map.of(
                                                "type", "object",
                                                "additionalProperties", false,
                                                "patternProperties", Map.of(
                                                        "^metric\\.[a-z]+$", Map.of("type", "integer")))
                                ), List.of("metrics")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "labels", Map.of(
                                                "type", "object",
                                                "additionalProperties", false,
                                                "patternProperties", Map.of(
                                                        "^label\\.[a-z]+$", Map.of("type", "string")))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "headers", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "patternProperties", Map.of(
                                        "^X-[A-Za-z0-9-]+$", Map.of("type", "string")),
                                "default", Map.of("X-Risk-Mode", "audit"))
                ), List.of("headers")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectPatternPropertiesPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsObjectUnevaluatedPropertiesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectUnevaluatedPropertiesPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object unevaluated properties policy",
                        "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "filters", Map.of(
                                                "type", "object",
                                                "properties", Map.of("status", Map.of("type", "string")),
                                                "patternProperties", Map.of(
                                                        "^filter\\.[a-z]+$", Map.of("type", "string")),
                                                "unevaluatedProperties", false)
                                ), List.of("filters")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "labels", Map.of(
                                                "type", "object",
                                                "properties", Map.of("status", Map.of("type", "string")),
                                                "unevaluatedProperties", Map.of("type", "string"))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routing", Map.of(
                                "type", "object",
                                "properties", Map.of("routeMode", Map.of("type", "string")),
                                "unevaluatedProperties", Map.of("type", "string"),
                                "default", Map.of("routeMode", "auto", "tenant", "gold"))
                ), List.of("routing")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectUnevaluatedPropertiesPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsNullableTypeArraysAcrossOperatorDefinitions() {
        Map<String, Object> nullableRoutingKey = new LinkedHashMap<>();
        nullableRoutingKey.put("type", List.of("string", "null"));
        nullableRoutingKey.put("default", null);

        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nullableTypePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Nullable type policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "score", Map.of("type", List.of("integer", "null")),
                                        "segment", Map.of("type", List.of("string", "null"))
                                ), List.of("score")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "accepted", Map.of("type", "boolean"),
                                        "reason", Map.of("type", List.of("string", "null"))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routingKey", nullableRoutingKey
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskNullableTypePolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsLocalDefinitionsReferencesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:defsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Definitions policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "applicant", Map.of("$ref", "#/$defs/Applicant")
                                        ),
                                        "required", List.of("applicant"),
                                        "$defs", Map.of(
                                                "Applicant", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "score", Map.of("type", "integer"),
                                                                "segment", Map.of("type", "string")
                                                        ),
                                                        "required", List.of("score"),
                                                        "additionalProperties", false)
                                        )
                                )),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "decision", Map.of("$ref", "#/$defs/Decision")
                                        ),
                                        "$defs", Map.of(
                                                "Decision", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "accepted", Map.of("type", "boolean"),
                                                                "reason", Map.of("type", List.of("string", "null"))
                                                        ),
                                                        "additionalProperties", false)
                                        )
                                )),
                                true,
                                "Output."))
                ),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limits", Map.of("$ref", "#/$defs/Limits")
                        ),
                        "$defs", Map.of(
                                "Limits", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "threshold", Map.of("type", "integer", "minimum", 300),
                                                "routeMode", Map.of("type", "string", "enum", List.of("strict", "relaxed"))
                                        ),
                                        "required", List.of("threshold", "routeMode"),
                                        "additionalProperties", false)
                        )
                )),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskDefsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsLocalDefinitionObjectAllOfAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectAllOfPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object allOf policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "applicant", Map.of(
                                                        "allOf", List.of(
                                                                Map.of("$ref", "#/$defs/BaseApplicant"),
                                                                Map.of(
                                                                        "type", "object",
                                                                        "properties", Map.of(
                                                                                "segment", Map.of("type", "string")
                                                                        ),
                                                                        "required", List.of("segment"),
                                                                        "additionalProperties", false)
                                                        ))
                                        ),
                                        "required", List.of("applicant"),
                                        "$defs", Map.of(
                                                "BaseApplicant", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "score", Map.of("type", "integer")
                                                        ),
                                                        "required", List.of("score"))
                                        )
                                )),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "decision", Map.of(
                                                        "allOf", List.of(
                                                                Map.of("$ref", "#/$defs/BaseDecision"),
                                                                Map.of(
                                                                        "type", "object",
                                                                        "properties", Map.of(
                                                                                "reason", Map.of("type", List.of("string", "null"))
                                                                        ),
                                                                        "additionalProperties", false)
                                                        ))
                                        ),
                                        "$defs", Map.of(
                                                "BaseDecision", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "accepted", Map.of("type", "boolean")
                                                        ),
                                                        "required", List.of("accepted"))
                                        )
                                )),
                                true,
                                "Output."))
                ),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limits", Map.of(
                                        "allOf", List.of(
                                                Map.of("$ref", "#/$defs/BaseLimits"),
                                                Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "routeMode", Map.of("type", "string")
                                                        ),
                                                        "required", List.of("routeMode"),
                                                        "additionalProperties", false)
                                        ))
                        ),
                        "required", List.of("limits"),
                        "$defs", Map.of(
                                "BaseLimits", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "threshold", Map.of("type", "integer", "minimum", 300)
                                        ),
                                        "required", List.of("threshold"))
                        )
                )),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectAllOfPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectDependentRequiredAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectDependentRequiredPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object dependent-required policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "payment", paymentDependentRequiredSchema(Map.of())
                                ), List.of("payment")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "quote", discountDependentRequiredSchema(Map.of())
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "defaults", paymentDependentRequiredSchema(Map.of(
                                "default", Map.of("cardNumber", "4111111111111111", "billingZip", "94105")))
                ), List.of("defaults")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectDependentRequiredPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsObjectDependentSchemasAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectDependentSchemasPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Object dependent-schemas policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "payment", paymentDependentSchemasSchema(Map.of())
                                ), List.of("payment")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "quote", discountDependentSchemasSchema(Map.of())
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "defaults", paymentDependentSchemasSchema(Map.of(
                                "default", Map.of("cardNumber", "4111111111111111", "billingZip", "94105")))
                ), List.of("defaults")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectDependentSchemasPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsInvalidObjectPropertyBoundsAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectBoundsPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad object bounds policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "filters", Map.of(
                                                "type", "object",
                                                "minProperties", 3,
                                                "maxProperties", 1)
                                ), List.of("filters")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "summary", Map.of(
                                                "type", "string",
                                                "minProperties", 1)
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routing", Map.of(
                                "type", "object",
                                "minProperties", 2,
                                "maxProperties", 3,
                                "default", Map.of("routeMode", "auto")),
                        "fixed", Map.of(
                                "type", "object",
                                "minProperties", 2,
                                "const", Map.of("routeMode", "auto"))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectBoundsPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.objectPropertyBoundsInvalid",
                        "visual.schema.objectPropertyConstraintTypeMismatch",
                        "visual.schema.defaultConstraintMismatch",
                        "visual.schema.constConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/filters",
                        "/operators/0/ports/outputs/0/schema/schema/properties/summary",
                        "/operators/0/configSchema/schema/properties/routing/default",
                        "/operators/0/configSchema/schema/properties/fixed/const"
                );
    }

    @Test
    void rejectsInvalidObjectPropertyNamesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectPropertyNamesPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad object property names policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "filters", Map.of(
                                                "type", "object",
                                                "propertyNames", "filter.*"),
                                        "labels", Map.of(
                                                "type", "object",
                                                "additionalProperties", Map.of("type", "string"),
                                                "propertyNames", Map.of("pattern", "^label\\.[a-z]+$"),
                                                "enum", List.of(Map.of("bad", "value")))
                                ), List.of("filters")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "facets", Map.of(
                                                "type", "object",
                                                "propertyNames", Map.of("type", "integer"))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routing", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "propertyNames", Map.of("pattern", "^route\\.[a-z]+$"),
                                "default", Map.of("bad", "auto")),
                        "fixed", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "propertyNames", Map.of("pattern", "^fixed\\.[a-z]+$"),
                                "const", Map.of("bad", "auto"))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectPropertyNamesPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.propertyNamesConstraintInvalid",
                        "visual.schema.propertyNamesConstraintTypeMismatch",
                        "visual.schema.enumConstraintMismatch",
                        "visual.schema.defaultConstraintMismatch",
                        "visual.schema.constConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/filters/propertyNames",
                        "/operators/0/ports/outputs/0/schema/schema/properties/facets/propertyNames",
                        "/operators/0/ports/inputs/0/schema/schema/properties/labels/enum/0",
                        "/operators/0/configSchema/schema/properties/routing/default",
                        "/operators/0/configSchema/schema/properties/fixed/const"
                );
    }

    @Test
    void rejectsInvalidObjectPatternPropertiesAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectPatternPropertiesPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad object pattern properties policy", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "metrics", Map.of(
                                                "type", "object",
                                                "patternProperties", Map.of("[", Map.of("type", "integer"))),
                                        "labels", Map.of(
                                                "type", "object",
                                                "additionalProperties", false,
                                                "patternProperties", Map.of(
                                                        "^label\\.[a-z]+$", Map.of("type", "string")),
                                                "enum", List.of(Map.of("label.status", 7)))
                                ), List.of("metrics")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "facets", Map.of(
                                                "type", "object",
                                                "patternProperties", Map.of("^facet\\.", "string"))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "routing", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "patternProperties", Map.of(
                                        "^route\\.[a-z]+$", Map.of("type", "string")),
                                "default", Map.of("route.mode", 7)),
                        "fixed", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "patternProperties", Map.of(
                                        "^fixed\\.[a-z]+$", Map.of("type", "string")),
                                "const", Map.of("fixed.mode", 7))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectPatternPropertiesPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.patternPropertiesPatternInvalid",
                        "visual.schema.patternPropertiesInvalid",
                        "visual.schema.enumConstraintMismatch",
                        "visual.schema.defaultConstraintMismatch",
                        "visual.schema.constConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/metrics/patternProperties/[",
                        "/operators/0/ports/outputs/0/schema/schema/properties/facets/patternProperties/^facet\\.",
                        "/operators/0/ports/inputs/0/schema/schema/properties/labels/enum/0",
                        "/operators/0/configSchema/schema/properties/routing/default",
                        "/operators/0/configSchema/schema/properties/fixed/const"
                );
    }

    @Test
    void rejectsInvalidObjectDependentRequiredAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectDependentRequiredPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad object dependent-required policy", "Test operator.",
                        List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "payment", paymentDependentRequiredSchema(Map.of(
                                                "properties", Map.of(
                                                        "cardNumber", Map.of("type", "string")),
                                                "dependentRequired", Map.of(
                                                        "cardNumber", List.of("billingZip", "billingZip"))))
                                ), List.of("payment")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "quote", discountDependentRequiredSchema(Map.of(
                                                "dependentRequired", Map.of(
                                                        "missingTrigger", List.of("discountReason"))))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "defaults", paymentDependentRequiredSchema(Map.of(
                                "default", Map.of("cardNumber", "4111111111111111"))),
                        "fixed", paymentDependentRequiredSchema(Map.of(
                                "const", Map.of("cardNumber", "4111111111111111"))),
                        "choices", paymentDependentRequiredSchema(Map.of(
                                "enum", List.of(Map.of("cardNumber", "4111111111111111"))))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectDependentRequiredPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.dependentRequiredUnknown",
                        "visual.schema.dependentRequiredDuplicate",
                        "visual.schema.defaultConstraintMismatch",
                        "visual.schema.constConstraintMismatch",
                        "visual.schema.enumConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/payment/dependentRequired/cardNumber/0",
                        "/operators/0/ports/inputs/0/schema/schema/properties/payment/dependentRequired/cardNumber/1",
                        "/operators/0/ports/outputs/0/schema/schema/properties/quote/dependentRequired/missingTrigger",
                        "/operators/0/configSchema/schema/properties/defaults/default",
                        "/operators/0/configSchema/schema/properties/fixed/const",
                        "/operators/0/configSchema/schema/properties/choices/enum/0"
                );
    }

    @Test
    void rejectsInvalidObjectDependentSchemasAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectDependentSchemasPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad object dependent-schemas policy", "Test operator.",
                        List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                SchemaEnvelope.object(Map.of(
                                        "payment", paymentDependentSchemasSchema(Map.of(
                                                "dependentSchemas", Map.of(
                                                        "cardNumber", "billingZip")))
                                ), List.of("payment")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "quote", discountDependentSchemasSchema(Map.of(
                                                "dependentSchemas", Map.of(
                                                        "missingTrigger", Map.of(
                                                                "properties", Map.of(
                                                                        "discountReason",
                                                                        Map.of("type", "string")),
                                                                "required", List.of("discountReason")))))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "defaults", paymentDependentSchemasSchema(Map.of(
                                "default", Map.of("cardNumber", "4111111111111111"))),
                        "fixed", paymentDependentSchemasSchema(Map.of(
                                "const", Map.of("cardNumber", "4111111111111111"))),
                        "choices", paymentDependentSchemasSchema(Map.of(
                                "enum", List.of(Map.of("cardNumber", "4111111111111111"))))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectDependentSchemasPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.dependentSchemasInvalid",
                        "visual.schema.dependentSchemasUnknown",
                        "visual.schema.defaultConstraintMismatch",
                        "visual.schema.constConstraintMismatch",
                        "visual.schema.enumConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/payment/dependentSchemas/cardNumber",
                        "/operators/0/ports/outputs/0/schema/schema/properties/quote/dependentSchemas/missingTrigger",
                        "/operators/0/configSchema/schema/properties/defaults/default",
                        "/operators/0/configSchema/schema/properties/fixed/const",
                        "/operators/0/configSchema/schema/properties/choices/enum/0"
                );
    }

    @Test
    void rejectsObjectEnumAndConstValuesOutsideDeclaredShapeAcrossOperatorDefinitions() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badObjectValueDomainPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Bad object value domain", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input",
                                SchemaEnvelope.object(Map.of(
                                        "decision", Map.of(
                                                "type", "object",
                                                "required", List.of("code"),
                                                "additionalProperties", false,
                                                "properties", Map.of(
                                                        "code", Map.of("type", "string", "pattern", "^[A-Z]+$"),
                                                        "score", Map.of("type", "integer", "minimum", 0)
                                                ),
                                                "enum", List.of(
                                                        Map.of("score", 10),
                                                        Map.of("code", "ok"),
                                                        Map.of("code", "OK", "extra", true)
                                                ))
                                ), List.of("decision")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "tags", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string", "minLength", 2),
                                                "uniqueItems", true,
                                                "enum", List.of(
                                                        List.of("aa", "aa"),
                                                        List.of("a")
                                                ))
                                ), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.object(Map.of(
                        "fixed", Map.of(
                                "type", "object",
                                "required", List.of("mode"),
                                "additionalProperties", false,
                                "properties", Map.of("mode", Map.of("type", "string")),
                                "const", Map.of("mode", 7))
                ), List.of()),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskBadObjectValueDomainPolicy", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.enumConstraintMismatch",
                        "visual.schema.constConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/schema/properties/decision/enum/0",
                        "/operators/0/ports/inputs/0/schema/schema/properties/decision/enum/1",
                        "/operators/0/ports/inputs/0/schema/schema/properties/decision/enum/2",
                        "/operators/0/ports/outputs/0/schema/schema/properties/tags/enum/0",
                        "/operators/0/ports/outputs/0/schema/schema/properties/tags/enum/1",
                        "/operators/0/configSchema/schema/properties/fixed/const"
                );
    }

    @Test
    void rejectsUnsupportedJsonSchemaKeywordsAcrossOperatorSchemas() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unsupportedSchemaKeywords",
                "1.0.0",
                new OperatorDefinition.Display("Unsupported schemas", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input",
                                SchemaEnvelope.object(Map.of(
                                        "choice", Map.of("oneOf", List.of(
                                                Map.of("type", "string"),
                                                Map.of("type", "integer")
                                        ))
                                ), List.of()),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "customer", Map.of("$ref", "#/$defs/Customer")
                                ), List.of()),
                                true,
                                "Output."))
	                ),
	                SchemaEnvelope.object(Map.of(
	                        "customerCode", Map.of("type", "string", "pattern", "^[A-Z]+$")
	                ), List.of()),
	                OperatorDefinition.Capabilities.pure(),
	                new OperatorDefinition.Lowering("native", "risk:unsupportedSchemaKeywords", Map.of()),
	                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.compositionUnsupported",
                        "visual.schema.refUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
		                .contains(
		                        "/operators/0/ports/inputs/0/schema/schema/properties/choice/oneOf",
		                        "/operators/0/ports/outputs/0/schema/schema/properties/customer/$ref"
		                );
		    }

    @Test
    void rejectsUnsupportedSchemaEnvelopeAcrossOperatorSchemas() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unsupportedSchemaEnvelope",
                "1.0.0",
                new OperatorDefinition.Display("Unsupported schema envelope", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                new SchemaEnvelope("json-schema", "draft-07", Map.of("type", "object")),
                                true,
                                "Input.")),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope("avro", "2020-12", Map.of("type", "object")),
                                true,
                                "Output."))
                ),
                new SchemaEnvelope("json-schema", "draft-04", Map.of("type", "object")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:unsupportedSchemaEnvelope", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.versionUnsupported",
                        "visual.schema.formatUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/operators/0/ports/inputs/0/schema/version",
                        "/operators/0/ports/outputs/0/schema/format",
                        "/operators/0/configSchema/version"
                );
    }

    @Test
    void rejectsConfigSchemaDefaultsThatDoNotMatchDeclaredSchema() {
        OperatorLibrary library = libraryWith(new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badConfigDefaults",
                "1.0.0",
                new OperatorDefinition.Display("Bad config defaults", "Test operator.", List.of("test")),
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
                        "required", List.of("threshold", "mode"),
                        "additionalProperties", false,
                        "properties", Map.of(
                                "threshold", Map.of("type", "integer", "default", "high"),
                                "mode", Map.of("type", "enum", "values", List.of("strict", "relaxed"),
                                        "default", "experimental"),
                                "nested", Map.of(
                                        "type", "object",
                                        "required", List.of("flag"),
                                        "properties", Map.of("flag", Map.of("type", "boolean")),
                                        "default", Map.of())
                        ),
                        "default", Map.of(
                                "threshold", 700,
                                "extra", true
                        )
                )),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:badConfigDefaults", Map.of()),
                List.of()
        ));

        VisualValidationResult result = validator.validate(library);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.defaultTypeMismatch",
                        "visual.schema.defaultEnumMismatch",
                        "visual.schema.defaultRequiredMissing",
                        "visual.schema.defaultUnknownProperty"
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
    void rejectsNativeLoweringWhenInputSchemaUsesReservedDslFieldName() {
        OperatorDefinition operator = operator(
                "risk:reservedInputField",
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("mode", Map.of("type", "string")), List.of("mode")),
                                true,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Output."))
                ),
                "native"
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.dslField.invalid");
                    assertThat(diagnostic.target())
                            .isEqualTo("/operators/0/ports/inputs/0/schema/schema/properties/mode");
                });
    }

    @Test
    void rejectsNativeLoweringWhenConfigSchemaUsesReservedDslFieldName() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("mode", Map.of("type", "string"));
        limits.put("threshold", Map.of("type", "integer"));
        Map<String, Object> config = Map.of(
                "limits", Map.of(
                        "type", "object",
                        "properties", limits,
                        "required", List.of("mode"),
                        "additionalProperties", false
                ),
                "timeout", Map.of("type", "duration")
        );
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:reservedConfigField",
                "1.0.0",
                new OperatorDefinition.Display("Reserved config", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                outputOnlyPorts(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                SchemaEnvelope.object(config, List.of("limits")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:reservedConfigField", Map.of()),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "visual.operator.lowering.dslField.invalid".equals(diagnostic.code()))
                .extracting("target")
                .containsExactly("/operators/0/configSchema/schema/properties/limits/properties/mode");
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
    void rejectsTransformLoweringWhenAssignmentTargetUsesReservedDslFieldName() {
        OperatorDefinition operator = transformOperator(
                "risk:reservedAssignmentTarget",
                Map.of("score", Map.of("type", "integer")),
                List.of("score"),
                Map.of("mode", Map.of("type", "string")),
                List.of("mode"),
                Map.of("mode", "\"strict\"")
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.dslField.invalid");
                    assertThat(diagnostic.target())
                            .isEqualTo("/operators/0/lowering/parameters/assignments/mode");
                });
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
    void rejectsTransformLoweringWhenStaticLiteralAssignmentTypeDoesNotMatchOutputSchema() {
        OperatorDefinition operator = transformOperator(
                "risk:literalOutputMismatch",
                Map.of("score", Map.of("type", "integer")),
                List.of("score"),
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of("accepted", "\"yes\"")
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.assignmentTypeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/lowering/parameters/assignments/accepted");
                    assertThat(diagnostic.message()).contains("\"yes\"").contains("boolean");
                });
    }

    @Test
    void rejectsTransformLoweringWhenPureTemplateAssignmentTypeDoesNotMatchOutputSchema() {
        OperatorDefinition operator = transformOperator(
                "risk:templateOutputMismatch",
                Map.of("decision", Map.of("type", "string")),
                List.of("decision"),
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of("accepted", "{{input.decision}}")
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lowering.assignmentTypeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/operators/0/lowering/parameters/assignments/accepted");
                    assertThat(diagnostic.message()).contains("input.decision").contains("string").contains("boolean");
                });
    }

    @Test
    void acceptsTransformLoweringWhenPureTemplateAssignmentMatchesOutputSchema() {
        OperatorDefinition operator = transformOperator(
                "risk:templateOutputMatch",
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of("accepted", Map.of("type", "boolean")),
                List.of("accepted"),
                Map.of("accepted", "{{input.accepted}}")
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsTransformTemplateReferencesThroughAdditionalPropertiesSchema() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:dynamicTemplateInput",
                "1.0.0",
                new OperatorDefinition.Display("Dynamic template input", "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(),
                                        "additionalProperties", Map.of(
                                                "type", "object",
                                                "properties", Map.of("score", Map.of("type", "integer")),
                                                "required", List.of("score"),
                                                "additionalProperties", false
                                        )
                                )),
                                true,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "{{input.customer.score}} >= 700")
                )),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsTransformTemplateReferencesThroughUnevaluatedPropertiesSchema() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:dynamicUnevaluatedTemplateInput",
                "1.0.0",
                new OperatorDefinition.Display("Dynamic unevaluated template input",
                        "Test operator.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(),
                                        "unevaluatedProperties", Map.of(
                                                "type", "object",
                                                "properties", Map.of("score", Map.of("type", "integer")),
                                                "required", List.of("score"),
                                                "unevaluatedProperties", false
                                        )
                                )),
                                true,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "{{input.customer.score}} >= 700")
                )),
                List.of()
        );

        VisualValidationResult result = validator.validate(libraryWith(operator));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
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

    private static OperatorLibrary libraryWithStatus(String libraryId, String status) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                status,
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
    }

    private static Map<String, Object> paymentDependentRequiredSchema(Map<String, Object> overrides) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "cardNumber", Map.of("type", "string"),
                "billingZip", Map.of("type", "string"),
                "method", Map.of("type", "string")
        ));
        schema.put("additionalProperties", false);
        schema.put("dependentRequired", Map.of("cardNumber", List.of("billingZip")));
        schema.putAll(overrides);
        return schema;
    }

    private static Map<String, Object> tuplePrefixItemsSchema(Map<String, Object> overrides) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("prefixItems", List.of(
                Map.of("type", "integer"),
                Map.of("type", "string")
        ));
        schema.put("items", Map.of("type", "string"));
        schema.put("minItems", 2);
        schema.putAll(overrides);
        return schema;
    }

    private static Map<String, Object> paymentDependentSchemasSchema(Map<String, Object> overrides) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "cardNumber", Map.of("type", "string"),
                "billingZip", Map.of("type", "string"),
                "method", Map.of("type", "string")
        ));
        schema.put("additionalProperties", false);
        schema.put("dependentSchemas", Map.of(
                "cardNumber", Map.of(
                        "properties", Map.of(
                                "billingZip", Map.of("type", "string")),
                        "required", List.of("billingZip"))));
        schema.putAll(overrides);
        return schema;
    }

    private static Map<String, Object> arrayContainsPrimarySchema(Map<String, Object> overrides) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", Map.of("type", "string"));
        schema.put("contains", Map.of("type", "string", "const", "primary"));
        schema.put("minContains", 1);
        schema.putAll(overrides);
        return schema;
    }

    private static Map<String, Object> discountDependentRequiredSchema(Map<String, Object> overrides) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "discountCode", Map.of("type", "string"),
                "discountReason", Map.of("type", "string")
        ));
        schema.put("additionalProperties", false);
        schema.put("dependentRequired", Map.of("discountCode", List.of("discountReason")));
        schema.putAll(overrides);
        return schema;
    }

    private static Map<String, Object> discountDependentSchemasSchema(Map<String, Object> overrides) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "discountCode", Map.of("type", "string"),
                "discountReason", Map.of("type", "string")
        ));
        schema.put("additionalProperties", false);
        schema.put("dependentSchemas", Map.of(
                "discountCode", Map.of(
                        "properties", Map.of(
                                "discountReason", Map.of("type", "string")),
                        "required", List.of("discountReason"))));
        schema.putAll(overrides);
        return schema;
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
