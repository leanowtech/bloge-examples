package com.leanowtech.bloge.examples.insurance;

import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsurancePremiumDecisionExampleTest {

    @Test
    void fluentApi_pricesYoungSafeApplicant() {
        GraphResult result = InsurancePremiumDecisionExample.execute("young-safe");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(120.0, ((Number) InsurancePremiumDecisionExample.premium(result).get("premium")).doubleValue());
        assertEquals("standard", InsurancePremiumDecisionExample.premium(result).get("tier"));
    }

    @Test
    void dsl_pricesAdultApplicant() {
        GraphResult result = InsurancePremiumDecisionDslExample.execute("adult");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(150.0, ((Number) InsurancePremiumDecisionExample.premium(result).get("premium")).doubleValue());
        assertEquals("standard", InsurancePremiumDecisionExample.premium(result).get("tier"));
    }

    @Test
    void dsl_usesOtherwiseForHighRiskApplicant() {
        GraphResult result = InsurancePremiumDecisionDslExample.execute("senior-risk");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(350.0, ((Number) InsurancePremiumDecisionExample.premium(result).get("premium")).doubleValue());
        assertEquals("high-risk", InsurancePremiumDecisionExample.premium(result).get("tier"));
    }
}