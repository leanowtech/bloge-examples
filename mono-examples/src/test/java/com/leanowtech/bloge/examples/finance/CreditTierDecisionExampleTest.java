package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditTierDecisionExampleTest {

    @Test
    void fluentApi_returnsPlatinumTier() {
        GraphResult result = CreditTierDecisionExample.execute("prime");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("platinum", CreditTierDecisionExample.tierValue(result));
    }

    @Test
    void fluentApi_returnsGoldFromChainedRange() {
        GraphResult result = CreditTierDecisionExample.execute("gold");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("gold", CreditTierDecisionExample.tierValue(result));
    }

    @Test
    void fluentApi_usesOtherwiseFallback() {
        GraphResult result = CreditTierDecisionExample.execute("declined");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("rejected", CreditTierDecisionExample.tierValue(result));
    }

    @Test
    void dsl_returnsPlatinumTier() {
        GraphResult result = CreditTierDecisionDslExample.execute("prime");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("platinum", CreditTierDecisionExample.tierValue(result));
    }

    @Test
    void dsl_returnsGoldFromChainedRange() {
        GraphResult result = CreditTierDecisionDslExample.execute("gold");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("gold", CreditTierDecisionExample.tierValue(result));
    }
}