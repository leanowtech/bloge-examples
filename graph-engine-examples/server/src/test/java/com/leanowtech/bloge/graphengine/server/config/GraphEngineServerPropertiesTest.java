package com.leanowtech.bloge.graphengine.server.config;

import com.leanowtech.bloge.graphengine.service.GraphOperationsPolicy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphEngineServerPropertiesTest {

    @Test
    void operationsPropertiesCreateServicePolicy() {
        GraphEngineServerProperties properties = new GraphEngineServerProperties();
        properties.getOperations().setDeadLetterAgeWarning(Duration.ofSeconds(10));
        properties.getOperations().setDeadLetterAgeCritical(Duration.ofSeconds(20));
        properties.getOperations().setSuspendedInstanceAgeWarning(Duration.ofSeconds(30));
        properties.getOperations().setSuspendedInstanceAgeCritical(Duration.ofSeconds(40));

        GraphOperationsPolicy policy = properties.getOperations().toPolicy();

        assertEquals(Duration.ofSeconds(10), policy.deadLetterAgeWarning());
        assertEquals(Duration.ofSeconds(20), policy.deadLetterAgeCritical());
        assertEquals(Duration.ofSeconds(30), policy.suspendedInstanceAgeWarning());
        assertEquals(Duration.ofSeconds(40), policy.suspendedInstanceAgeCritical());
    }

    @Test
    void operationsPropertiesUseDefaultsForNonPositiveValues() {
        GraphEngineServerProperties properties = new GraphEngineServerProperties();
        properties.getOperations().setDeadLetterAgeWarning(Duration.ZERO);
        properties.getOperations().setDeadLetterAgeCritical(Duration.ofSeconds(-1));

        GraphOperationsPolicy policy = properties.getOperations().toPolicy();

        assertEquals(GraphOperationsPolicy.DEFAULT_DEAD_LETTER_AGE_WARNING, policy.deadLetterAgeWarning());
        assertEquals(GraphOperationsPolicy.DEFAULT_DEAD_LETTER_AGE_CRITICAL, policy.deadLetterAgeCritical());
    }

    @Test
    void operationsPropertiesRejectWarningAboveCriticalWhenConverted() {
        GraphEngineServerProperties properties = new GraphEngineServerProperties();
        properties.getOperations().setDeadLetterAgeWarning(Duration.ofSeconds(21));
        properties.getOperations().setDeadLetterAgeCritical(Duration.ofSeconds(20));

        assertThrows(IllegalArgumentException.class, () -> properties.getOperations().toPolicy());
    }
}
