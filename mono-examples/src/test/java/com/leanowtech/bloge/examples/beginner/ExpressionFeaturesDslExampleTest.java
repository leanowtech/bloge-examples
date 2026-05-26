package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionFeaturesDslExampleTest {

    @Test
    void dslEvaluatesIndexAccessInterpolationAndWhenExpression() {
        GraphResult result = ExpressionFeaturesDslExample.execute("Ada");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        var values = ExpressionFeaturesDslExample.values(result);
        assertEquals("apple", values.get("firstItem"));
        assertEquals("cherry", values.get("lastItem"));
        assertEquals("banana", values.get("safeItem"));
        assertEquals(750, ((Number) values.get("goldScore")).intValue());
        assertEquals("Hello Ada! You have 3 items.", values.get("greeting"));
        assertEquals(10, ((Number) values.get("tierBonus")).intValue());
    }
}