package com.leanowtech.bloge.graphengine.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphOperationsPolicyTest {

    @Test
    void defaultPolicyUsesProductDefaults() {
        GraphOperationsPolicy policy = GraphOperationsPolicy.defaultPolicy();

        assertEquals(Duration.ofMinutes(5), policy.deadLetterAgeWarning());
        assertEquals(Duration.ofMinutes(30), policy.deadLetterAgeCritical());
        assertEquals(Duration.ofMinutes(15), policy.suspendedInstanceAgeWarning());
        assertEquals(Duration.ofHours(2), policy.suspendedInstanceAgeCritical());
    }

    @Test
    void nullOrNonPositiveValuesFallBackToDefaults() {
        GraphOperationsPolicy policy = new GraphOperationsPolicy(null, Duration.ZERO, Duration.ofSeconds(-1), null);

        assertEquals(Duration.ofMinutes(5), policy.deadLetterAgeWarning());
        assertEquals(Duration.ofMinutes(30), policy.deadLetterAgeCritical());
        assertEquals(Duration.ofMinutes(15), policy.suspendedInstanceAgeWarning());
        assertEquals(Duration.ofHours(2), policy.suspendedInstanceAgeCritical());
    }

    @Test
    void warningThresholdMustNotExceedCriticalThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new GraphOperationsPolicy(
                        Duration.ofMinutes(31),
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(15),
                        Duration.ofHours(2)
                ));
    }

    @Test
    void runtimeSupportUsesSuppliedOperationsPolicy() {
        GraphOperationsPolicy policy = new GraphOperationsPolicy(
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                Duration.ofSeconds(30),
                Duration.ofSeconds(40)
        );

        GraphEngineRuntimeSupport runtimeSupport = GraphEngineRuntimeSupport.builder()
                .operationsPolicy(policy)
                .build();

        assertEquals(policy, runtimeSupport.operationsPolicy());
    }
}
