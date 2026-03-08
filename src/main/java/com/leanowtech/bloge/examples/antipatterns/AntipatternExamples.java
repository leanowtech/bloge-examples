package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;

/**
 * Common antipatterns when building bloge graphs.
 * Each inner class demonstrates a mistake and explains the correct approach.
 */
public class AntipatternExamples {

    // ──────────────────────────────────────────────────────────────────
    // 1. Over-granular operators
    // ──────────────────────────────────────────────────────────────────

    public record RenameInput(String oldFieldName, String newFieldName, String value) {}
    public record RenameOutput(String fieldName, String value) {}

    // ❌ ANTIPATTERN: This should be a transform, not an operator.
    // A field rename is just data mapping — use a transform block instead.
    //
    // ✅ CORRECT: In DSL, use:
    //   transform renamed {
    //     newFieldName = someNode.output.oldFieldName
    //   }
    static final Operator<RenameInput, RenameOutput> FIELD_RENAME_OPERATOR = (input, ctx) -> {
        return new RenameOutput(input.newFieldName(), input.value());
    };

    // ──────────────────────────────────────────────────────────────────
    // 2. God operator
    // ──────────────────────────────────────────────────────────────────

    public record OrderRequest(String userId, java.util.List<String> productIds) {}
    public record OrderResult(String orderId, double total, String status) {}

    // ❌ ANTIPATTERN: This operator does fetching, validation, pricing, AND order creation.
    // Should be split into 4 separate operators following the Single Capability Principle.
    //
    // ✅ CORRECT: Split into fetchUser, validateProducts, calcPrice, createOrder
    //   as shown in OrderProcessingExample.
    static final Operator<OrderRequest, OrderResult> GOD_ORDER_OPERATOR = (input, ctx) -> {
        // fetch user
        Thread.sleep(50);
        boolean valid = input.userId() != null;
        // validate products
        Thread.sleep(80);
        // calculate price
        double total = input.productIds().size() * 29.99;
        double tax = total * 0.08;
        // create order
        return new OrderResult("ORD-99999", total + tax, valid ? "CONFIRMED" : "REJECTED");
    };

    // ──────────────────────────────────────────────────────────────────
    // 3. Business logic in InputAssembler
    // ──────────────────────────────────────────────────────────────────

    public record PricingInput(double subtotal, double tax, double total) {}
    public record PricingOutput(double total) {}

    // ❌ ANTIPATTERN: Business logic (pricing calculation) in the input assembler.
    // InputAssembler should only do data routing and format adaptation.
    //
    // ✅ CORRECT: Move the pricing logic into a dedicated CalcPrice operator
    //   and have the input assembler simply route outputs:
    //     .input((results, ctx) -> new PricingInput(
    //             results.get("calcPrice", PriceResult.class).total()))
    //
    // Example of the bad pattern in graph builder code:
    //   .node("checkout", CHECKOUT_OPERATOR)
    //       .input((results, ctx) -> {
    //           double subtotal = results.get("products", ProductList.class)
    //                   .items().stream().mapToDouble(Product::price).sum();
    //           double tax = subtotal * 0.08;
    //           return new PricingInput(subtotal, tax, subtotal + tax);  // ← business logic!
    //       })
    static final Operator<PricingInput, PricingOutput> CHECKOUT_OPERATOR = (input, ctx) -> {
        return new PricingOutput(input.total());
    };

    // ──────────────────────────────────────────────────────────────────
    // 4. Missing idempotency declaration
    // ──────────────────────────────────────────────────────────────────

    public record SaveInput(String id, String data) {}
    public record SaveOutput(boolean success) {}

    // ❌ ANTIPATTERN: This operator performs writes but doesn't declare idempotency.
    // The default is Idempotency.UNKNOWN, which prevents the framework from making
    // safe retry decisions.
    //
    // ✅ CORRECT: Override idempotency() to return NOT_IDEMPOTENT (or IDEMPOTENT
    //   if the write is safe to retry, e.g., upsert semantics).
    static final Operator<SaveInput, SaveOutput> BAD_SAVE_OPERATOR = (input, ctx) -> {
        // persists to database — not idempotent!
        return new SaveOutput(true);
    };

    // ✅ This is the correct version:
    static final Operator<SaveInput, SaveOutput> GOOD_SAVE_OPERATOR = new Operator<>() {
        @Override
        public SaveOutput execute(SaveInput input, OperatorContext ctx) {
            return new SaveOutput(true);
        }

        @Override
        public Idempotency idempotency() {
            return Idempotency.NOT_IDEMPOTENT;
        }
    };
}
