package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.schema;
import static org.assertj.core.api.Assertions.assertThat;

class LogicalResourceContractCompatibilityTest {

    @Test
    void optionalInputAdditionIsCompatible() {
        LogicalResourceContract baseline = contract(objectSchema("id", "string", true), output("integer", true));
        SchemaEnvelope additiveInput = schema(Map.of(
                "id", Map.of("type", "string"),
                "locale", Map.of("type", "string")), List.of("id"), false);

        LogicalResourceContractCompatibility.Report report = LogicalResourceContractCompatibility.analyze(
                baseline, contract(additiveInput, output("integer", true)));

        assertThat(report.status()).isEqualTo(LogicalResourceContractCompatibility.Status.COMPATIBLE);
        assertThat(report.retainsBinding()).isTrue();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void additiveOutputPropertyIsCompatibleWhenBaselineAllowsExtensions() {
        SchemaEnvelope baselineOutput = schema(
                Map.of("result", Map.of("type", "string")), List.of("result"), true);
        SchemaEnvelope additiveOutput = schema(Map.of(
                "result", Map.of("type", "string"),
                "traceId", Map.of("type", "string")), List.of("result"), false);

        LogicalResourceContractCompatibility.Report report = LogicalResourceContractCompatibility.analyze(
                contract(objectSchema("id", "string", true), baselineOutput),
                contract(objectSchema("id", "string", true), additiveOutput));

        assertThat(report.status()).isEqualTo(LogicalResourceContractCompatibility.Status.COMPATIBLE);
        assertThat(report.retainsBinding()).isTrue();
    }

    @Test
    void requiredOutputRemovalIsBreaking() {
        LogicalResourceContractCompatibility.Report report = LogicalResourceContractCompatibility.analyze(
                contract(objectSchema("id", "string", true), output("integer", true)),
                contract(objectSchema("id", "string", true), output("integer", false)));

        assertBreaking(report, "OUTPUT_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void inputTypeWideningIsCompatibleAndNarrowingIsBreaking() {
        LogicalResourceContract integerInput = contract(objectSchema("value", "integer", true), output("string", true));
        LogicalResourceContract numberInput = contract(objectSchema("value", "number", true), output("string", true));

        assertThat(LogicalResourceContractCompatibility.analyze(integerInput, numberInput).status())
                .isEqualTo(LogicalResourceContractCompatibility.Status.COMPATIBLE);
        assertBreaking(LogicalResourceContractCompatibility.analyze(numberInput, integerInput),
                "INPUT_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void outputTypeWideningIsBreakingAndNarrowingIsCompatible() {
        LogicalResourceContract integerOutput = contract(objectSchema("id", "string", true), output("integer", true));
        LogicalResourceContract numberOutput = contract(objectSchema("id", "string", true), output("number", true));

        assertBreaking(LogicalResourceContractCompatibility.analyze(integerOutput, numberOutput),
                "OUTPUT_SCHEMA_INCOMPATIBLE");
        assertThat(LogicalResourceContractCompatibility.analyze(numberOutput, integerOutput).status())
                .isEqualTo(LogicalResourceContractCompatibility.Status.COMPATIBLE);
    }

    @Test
    void additionalPropertiesTighteningIsBreakingForInputs() {
        SchemaEnvelope open = schema(Map.of("id", Map.of("type", "string")), List.of("id"), true);
        SchemaEnvelope closed = schema(Map.of("id", Map.of("type", "string")), List.of("id"), false);

        assertBreaking(LogicalResourceContractCompatibility.analyze(
                contract(open, output("string", true)), contract(closed, output("string", true))),
                "INPUT_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void unknownSemanticsRequiresReviewWithoutBeingClassifiedBreaking() {
        LogicalResourceContract baseline = contract(objectSchema("id", "string", true), output("string", true));
        LogicalResourceContract candidate = new LogicalResourceContract(
                "logical.customer", baseline.inputShape(), baseline.outputShape(), ResponseSemantics.unknown());

        LogicalResourceContractCompatibility.Report report = LogicalResourceContractCompatibility.analyze(
                baseline, candidate);

        assertThat(report.status()).isEqualTo(LogicalResourceContractCompatibility.Status.REVIEW_REQUIRED);
        assertThat(report.retainsBinding()).isTrue();
        assertThat(report.automaticUseAllowed()).isFalse();
        assertThat(report.findings()).extracting(LogicalResourceContractCompatibility.Finding::code)
                .containsExactly("RESPONSE_SEMANTICS_UNKNOWN");
    }

    @Test
    void changedConfirmedSemanticsRequiresReviewWithoutInvalidatingTheBinding() {
        LogicalResourceContract baseline = contract(objectSchema("id", "string", true), output("string", true));
        LogicalResourceContract candidate = new LogicalResourceContract(
                "logical.customer", baseline.inputShape(), baseline.outputShape(),
                ResponseSemantics.confirmed("body.ok == true", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT,
                        ResponseSemantics.Retryability.CONDITIONAL));

        LogicalResourceContractCompatibility.Report report = LogicalResourceContractCompatibility.analyze(
                baseline, candidate);

        assertThat(report.status()).isEqualTo(LogicalResourceContractCompatibility.Status.REVIEW_REQUIRED);
        assertThat(report.retainsBinding()).isTrue();
        assertThat(report.automaticUseAllowed()).isFalse();
        assertThat(report.findings()).extracting(LogicalResourceContractCompatibility.Finding::code)
                .containsExactly("RESPONSE_SEMANTICS_CHANGED");
    }

    @Test
    void unsupportedSchemaKeywordRequiresReviewAndFindingsAreStableAndSanitized() {
        String secret = "sensitive-payload-value";
        LogicalResourceContract baseline = contract(objectSchema("id", "string", true), output("string", true));
        LogicalResourceContract candidate = contract(
                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                        "type", "object", "properties", Map.of("id", Map.of("secretKeyword", secret)))),
                output("string", true));

        LogicalResourceContractCompatibility.Report first = LogicalResourceContractCompatibility.analyze(
                baseline, candidate);
        LogicalResourceContractCompatibility.Report second = LogicalResourceContractCompatibility.analyze(
                baseline, candidate);

        assertThat(first.status()).isEqualTo(LogicalResourceContractCompatibility.Status.REVIEW_REQUIRED);
        assertThat(first.findings()).isEqualTo(second.findings());
        assertThat(first.findings()).extracting(LogicalResourceContractCompatibility.Finding::code)
                .containsExactly("INPUT_SCHEMA_UNKNOWN");
        assertThat(first.findings().toString()).doesNotContain(secret).doesNotContain("secretKeyword");
    }

    private static LogicalResourceContract contract(SchemaEnvelope input, SchemaEnvelope output) {
        return new LogicalResourceContract("logical.customer", input, output,
                confirmed(Map.of("BUSINESS", List.of("NOT_FOUND"))));
    }

    private static SchemaEnvelope output(String type, boolean required) {
        return objectSchema("result", type, required);
    }

    private static void assertBreaking(LogicalResourceContractCompatibility.Report report, String code) {
        assertThat(report.status()).isEqualTo(LogicalResourceContractCompatibility.Status.BREAKING);
        assertThat(report.retainsBinding()).isFalse();
        assertThat(report.findings()).extracting(LogicalResourceContractCompatibility.Finding::code)
                .contains(code);
    }
}
