package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaAware;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.TypedSchema;
import com.leanowtech.bloge.core.schema.UnionSchema;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Java operator inventory projection into the visual catalog contract.
 */
class JavaOperatorInventoryProjectorTest {

    @Test
    void projectsRegisteredJavaOperatorSchemaIntoVisualOperatorDefinition() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("quotePrice", new QuotePriceOperator());

        OperatorDefinition operator = new JavaOperatorInventoryProjector(registry).project().getFirst();

        assertThat(operator.operatorRef()).isEqualTo("quotePrice");
        assertThat(operator.source().kind()).isEqualTo("java-operator");
        assertThat(operator.display().description()).isEqualTo("Quotes a demo product price.");
        assertThat(operator.display().tags()).contains("java", "pricing");
        assertThat(operator.capabilities().effect()).isEqualTo("READ_EXTERNAL");
        assertThat(operator.capabilities().idempotency()).isEqualTo("IDEMPOTENT");
        assertThat(operator.capabilities().durable()).isFalse();
        assertThat(operator.lowering().mode()).isEqualTo("native");
        assertThat(operator.lowering().operatorRef()).isEqualTo("quotePrice");
        assertThat(operator.ports().inputs().getFirst().schema().properties())
                .containsKeys("sku", "quantity");
        assertThat(operator.ports().inputs().getFirst().schema().required())
                .containsExactly("sku", "quantity");
        assertThat(operator.ports().outputs().getFirst().schema().properties())
                .containsKeys("sku", "price", "currency");
        assertThat(operator.diagnostics()).isEmpty();
        assertThat(VisualSchemaValidator.validateEnvelope(
                operator.ports().inputs().getFirst().schema(), "/input"))
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
        assertThat(VisualSchemaValidator.validateEnvelope(
                operator.ports().outputs().getFirst().schema(), "/output"))
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
    }

    @Test
    void projectsUnionSchemaAsVisualOneOfSchema() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("unionOutput", new UnionOutputOperator());

        OperatorDefinition operator = new JavaOperatorInventoryProjector(registry).project().getFirst();

        Map<String, Object> schema = operator.ports().outputs().getFirst().schema().schema();
        assertThat(schema.get("oneOf"))
                .isEqualTo(List.of(
                        Map.of("type", "string"),
                        Map.of("type", "integer")
                ));
        assertThat(operator.diagnostics()).isEmpty();
        assertThat(VisualSchemaValidator.validateEnvelope(
                operator.ports().outputs().getFirst().schema(), "/output"))
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
        assertThat(VisualSchemaCompatibility.schemaCompatibilityIssue(schema, Map.of("type", "integer")))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("source oneOf branch 0")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void projectsSuspendableJavaOperatorAsExplicitCatalogSourceKind() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("awaitApproval", new AwaitApprovalOperator());

        OperatorDefinition operator = new JavaOperatorInventoryProjector(registry).project().getFirst();

        assertThat(operator.operatorRef()).isEqualTo("awaitApproval");
        assertThat(operator.source().kind()).isEqualTo("java-suspendable-operator");
        assertThat(operator.display().tags()).contains("java", "suspendable", "human-task");
        assertThat(operator.capabilities().streaming()).isFalse();
        assertThat(operator.capabilities().durable()).isTrue();
        assertThat(operator.capabilities().effect()).isEqualTo("WRITE_EXTERNAL");
        assertThat(operator.capabilities().idempotency()).isEqualTo("NON_IDEMPOTENT");
        assertThat(operator.runtimeReadiness().state()).isEqualTo("RUNTIME_BLOCKED");
        assertThat(operator.runtimeReadiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(operator.lowering().mode()).isEqualTo("native");
        assertThat(operator.lowering().operatorRef()).isEqualTo("awaitApproval");
        assertThat(operator.ports().outputs().getFirst().description())
                .startsWith("Java suspendable output type:");
        assertThat(VisualSchemaValidator.validateEnvelope(
                operator.ports().outputs().getFirst().schema(), "/output"))
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
    }

    @Test
    void skipsReservedBuiltInOperatorRefsAlreadyDeclaredByVisualCatalog() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("httpResource", new QuotePriceOperator());

        assertThat(new JavaOperatorInventoryProjector(registry).project()).isEmpty();
    }

    private record QuoteRequest(String sku, int quantity) {
    }

    private record QuoteResponse(String sku, double price, String currency) {
    }

    private record ApprovalRequest(String applicationId) {
    }

    private record ApprovalResponse(String decision) {
    }

    @OperatorMeta(
            tags = {"pricing"},
            description = "Quotes a demo product price.",
            owner = "catalog-team"
    )
    private static final class QuotePriceOperator implements Operator<QuoteRequest, QuoteResponse> {
        @Override
        public QuoteResponse execute(QuoteRequest input, OperatorContext ctx) {
            return new QuoteResponse(input.sku(), input.quantity() * 12.5, "USD");
        }

        @Override
        public Idempotency idempotency() {
            return Idempotency.IDEMPOTENT;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class UnionOutputOperator
            implements Operator<Map<String, Object>, Object>, SchemaAware {
        @Override
        public Object execute(Map<String, Object> input, OperatorContext ctx) {
            return input.get("value");
        }

        @Override
        public SchemaDescriptor inputSchema() {
            return OpaqueSchema.INSTANCE;
        }

        @Override
        public SchemaDescriptor outputSchema() {
            return new UnionSchema(List.of(new TypedSchema(String.class), new TypedSchema(Integer.class)), "");
        }
    }

    @OperatorMeta(
            tags = {"human-task"},
            description = "Suspends while waiting for manual approval."
    )
    private static final class AwaitApprovalOperator
            implements SuspendableOperator<ApprovalRequest, ApprovalResponse> {
        @Override
        public OperatorResult<ApprovalResponse> execute(ApprovalRequest input, OperatorContext ctx) {
            return OperatorResult.suspend("await-approval");
        }

        @Override
        public Idempotency idempotency() {
            return Idempotency.NOT_IDEMPOTENT;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.WRITE;
        }
    }
}
