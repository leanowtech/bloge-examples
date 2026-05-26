package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.DecisionTableViolationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanTermsDecisionExampleTest {

    @Test
    void fluentApi_returnsPrimeLoanTerms() {
        GraphResult result = LoanTermsDecisionExample.execute("prime", "large");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(3.5, ((Number) LoanTermsDecisionExample.terms(result).get("rate")).doubleValue());
        assertEquals(30, ((Number) LoanTermsDecisionExample.terms(result).get("maxTerm")).intValue());
    }

    @Test
    void dsl_returnsStandardLoanTerms() {
        GraphResult result = LoanTermsDecisionDslExample.execute("standard", "small");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertEquals(4.5, ((Number) LoanTermsDecisionExample.terms(result).get("rate")).doubleValue());
        assertEquals(25, ((Number) LoanTermsDecisionExample.terms(result).get("maxTerm")).intValue());
    }

    @Test
    void uniquePolicyReportsAmbiguousMatches() {
        DecisionTableViolationException violation = LoanTermsDecisionExample.ambiguousMatch();

        assertEquals(DecisionTableViolationException.CODE_AMBIGUOUS_MATCH, violation.code());
    }
}