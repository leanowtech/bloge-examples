package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModularCheckoutDslExampleTest {

    @Test
    void compilesCheckoutGraphWithClasspathImports() {
        var registry = new DefaultOperatorRegistry();

        var graph = ModularCheckoutDslExample.buildGraph(registry);

        assertEquals("modularCheckout", graph.name());
        assertTrue(graph.nodes().containsKey(ModularCheckoutDslExample.NODE_PAYMENT));
        assertTrue(graph.nodes().containsKey(ModularCheckoutDslExample.NODE_INVENTORY));
    }

    @Test
    void execute_runsImportedPaymentAndInventorySubGraphs() {
        GraphResult result = ModularCheckoutDslExample.execute("CHK-1001", "CUST-42");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());

        Map<String, Object> checkout = ModularCheckoutDslExample.checkoutSummary(result);
        assertEquals("CHK-1001", checkout.get("checkoutId"));
        assertEquals("ORD-IMPORT-1001", checkout.get("orderId"));
        assertEquals("READY_TO_CAPTURE", checkout.get("status"));
        assertEquals("AUTHORIZED", checkout.get("paymentStatus"));
        assertEquals("RSV-ORD-IMPORT-1001", checkout.get("reservationId"));

        Map<String, Object> payment = ModularCheckoutDslExample.paymentOutput(result);
        assertEquals("AUTHORIZED", ((Map<?, ?>) payment.get("paymentResult")).get("paymentStatus"));

        Map<String, Object> inventory = ModularCheckoutDslExample.inventoryOutput(result);
        assertEquals(true, ((Map<?, ?>) inventory.get("inventoryResult")).get("reserved"));
    }
}