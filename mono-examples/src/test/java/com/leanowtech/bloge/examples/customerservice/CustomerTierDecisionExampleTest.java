package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.DecisionTableViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerTierDecisionExampleTest {

    @Test
    void fluentApi_matchesStaticMembership() {
        GraphResult result = CustomerTierDecisionExample.execute("vip", List.of("partner"));

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("priority", CustomerTierDecisionExample.tier(result));
    }

    @Test
    void dsl_matchesDynamicMembership() {
        GraphResult result = CustomerTierDecisionDslExample.execute("partner", List.of("partner"));

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("preferred", CustomerTierDecisionExample.tier(result));
    }

    @Test
    void dsl_usesOtherwiseFallback() {
        GraphResult result = CustomerTierDecisionDslExample.execute("regular", List.of("partner"));

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals("standard", CustomerTierDecisionExample.tier(result));
    }

    @Test
    void invalidDynamicMembershipParameterUsesStableViolationCode() {
        DecisionTableViolationException violation = CustomerTierDecisionExample.invalidCollectionViolation();

        assertEquals(DecisionTableViolationException.CODE_INVALID_COLLECTION_PARAM, violation.code());
    }
}