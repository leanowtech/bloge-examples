package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicableDiscountsDecisionExampleTest {

    @Test
    void fluentApi_collectsMultipleDiscounts() {
        GraphResult result = ApplicableDiscountsDecisionExample.execute("vip-high");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(List.of("loyalty_5pct", "loyalty_extra_3pct", "coupon_10pct", "vip_free_shipping"),
                ApplicableDiscountsDecisionExample.discountItems(result));
    }

    @Test
    void dsl_collectsCouponDiscount() {
        GraphResult result = ApplicableDiscountsDecisionDslExample.execute("coupon");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(List.of("coupon_10pct"), ApplicableDiscountsDecisionExample.discountItems(result));
    }

    @Test
    void collectPolicyReturnsEmptyItemsWithoutOtherwise() {
        GraphResult result = ApplicableDiscountsDecisionDslExample.execute("regular");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(List.of(), ApplicableDiscountsDecisionExample.discountItems(result));
    }
}