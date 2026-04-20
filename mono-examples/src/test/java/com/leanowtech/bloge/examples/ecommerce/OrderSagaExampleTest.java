package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.SagaCompensationMode;
import com.leanowtech.bloge.core.model.SagaFailurePolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class OrderSagaExampleTest {

    @Test
    void javaApi_failureExecutesCompensationInReverseOrder() {
        GraphResult result = OrderSagaExample.execute(true);

        assertFalse(result.isSuccess());
        assertEquals(2, result.compensationResults().size());
        assertEquals("chargePayment", result.compensationResults().get(0).nodeId());
        assertEquals("refund", ((OrderSagaExample.CompensationAck) result.compensationResults().get(0).output()).action());
        assertEquals("reserveInventory", result.compensationResults().get(1).nodeId());
        assertEquals("release", ((OrderSagaExample.CompensationAck) result.compensationResults().get(1).output()).action());
    }

    @Test
    void dsl_failureExecutesCompensationInReverseOrder() {
        GraphResult result = OrderSagaDslExample.execute(true);

        assertFalse(result.isSuccess());
        assertEquals(2, result.compensationResults().size());
        assertEquals("chargePayment", result.compensationResults().get(0).nodeId());
        assertEquals("refund", ((Map<String, Object>) result.compensationResults().get(0).output()).get("action"));
        assertEquals("reserveInventory", result.compensationResults().get(1).nodeId());
        assertEquals("release", ((Map<String, Object>) result.compensationResults().get(1).output()).get("action"));
    }

    @Test
    void successLeavesCompensationResultsEmpty() {
        assertTrue(OrderSagaExample.execute(false).compensationResults().isEmpty());
        assertTrue(OrderSagaDslExample.execute(false).compensationResults().isEmpty());
    }

    @Test
    void examplesExposeSagaPolicyAndCompensationRetryConfiguration() {
        var javaGraph = OrderSagaExample.buildGraph();
        assertEquals(SagaCompensationMode.BACKWARD, javaGraph.sagaConfig().mode());
        assertEquals(SagaFailurePolicy.COMPENSATE, javaGraph.sagaConfig().onFailure());
        assertEquals(2, javaGraph.sagaConfig().maxCompensationRetries());
        assertEquals(1, javaGraph.nodes().get("chargePayment").compensation().resilience().retryAttempts());

        var dslGraph = OrderSagaDslExample.loadGraph();
        assertEquals(SagaCompensationMode.BACKWARD, dslGraph.sagaConfig().mode());
        assertEquals(SagaFailurePolicy.COMPENSATE, dslGraph.sagaConfig().onFailure());
        assertEquals(2, dslGraph.sagaConfig().maxCompensationRetries());
        assertEquals(1, dslGraph.nodes().get("chargePayment").compensation().resilience().retryAttempts());
    }
}
