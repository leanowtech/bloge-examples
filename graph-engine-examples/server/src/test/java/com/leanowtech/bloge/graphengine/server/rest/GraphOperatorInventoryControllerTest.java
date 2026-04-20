package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.service.OperatorInventoryEntry;
import com.leanowtech.bloge.graphengine.service.OperatorUsageReference;
import com.leanowtech.bloge.graphengine.service.OperatorUsageSummary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for the operator inventory REST endpoint.
 */
class GraphOperatorInventoryControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphOperatorInventoryController(graphEngineService, scopeResolver));
    }

    @Test
    void queryOperatorInventoryReturnsEntries() throws Exception {
        OperatorUsageReference ref = new OperatorUsageReference(
                "order-flow", "def-1", "1.0.0", "ver-1", GraphVersionStatus.PUBLISHED
        );
        OperatorUsageSummary usage = new OperatorUsageSummary(1, 1, List.of(ref));
        OperatorInventoryEntry entry = new OperatorInventoryEntry(
                "validateOrder",
                "Validates an incoming order",
                "order-team",
                List.of("validation", "orders"),
                "java.lang.String",
                "java.lang.Boolean",
                "{\"kind\":\"typed\",\"type\":\"String\"}",
                "{\"kind\":\"typed\",\"type\":\"Boolean\"}",
                "node validate : validateOrder {}",
                "Requires order.id",
                usage
        );
        graphEngineService.operatorInventoryResult = List.of(entry);

        mockMvc.perform(get("/api/v1/operators").param("pattern", "validate*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("validateOrder"))
                .andExpect(jsonPath("$[0].description").value("Validates an incoming order"))
                .andExpect(jsonPath("$[0].owner").value("order-team"))
                .andExpect(jsonPath("$[0].tags.length()").value(2))
                .andExpect(jsonPath("$[0].tags[0]").value("validation"))
                .andExpect(jsonPath("$[0].inputType").value("java.lang.String"))
                .andExpect(jsonPath("$[0].outputType").value("java.lang.Boolean"))
                .andExpect(jsonPath("$[0].inputSchema").value("{\"kind\":\"typed\",\"type\":\"String\"}"))
                .andExpect(jsonPath("$[0].usageExample").value("node validate : validateOrder {}"))
                .andExpect(jsonPath("$[0].constraintsDescription").value("Requires order.id"))
                .andExpect(jsonPath("$[0].usage.definitionCount").value(1))
                .andExpect(jsonPath("$[0].usage.versionCount").value(1))
                .andExpect(jsonPath("$[0].usage.references[0].definitionKey").value("order-flow"))
                .andExpect(jsonPath("$[0].usage.references[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].usage.references[0].status").value("PUBLISHED"));

        assertEquals("validate*", graphEngineService.operatorInventoryQuery.pattern());
    }

    @Test
    void queryOperatorInventoryDefaultPatternIsWildcard() throws Exception {
        graphEngineService.operatorInventoryResult = List.of();

        mockMvc.perform(get("/api/v1/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertEquals("*", graphEngineService.operatorInventoryQuery.pattern());
    }

    @Test
    void queryOperatorInventoryReturnsMultipleEntries() throws Exception {
        OperatorInventoryEntry first = new OperatorInventoryEntry(
                "enrichOrder", "Enriches order data", "data-team", List.of("enrichment"),
                "java.util.Map", "java.util.Map", "{}", "{}",
                "", "", OperatorUsageSummary.EMPTY
        );
        OperatorInventoryEntry second = new OperatorInventoryEntry(
                "notifyCustomer", "Sends customer notifications", "comms-team", List.of("notification"),
                "java.lang.String", "java.lang.Void", "{}", "{}",
                "", "", new OperatorUsageSummary(2, 3, List.of(
                        new OperatorUsageReference("flow-a", "def-a", "1.0.0", "ver-a1", GraphVersionStatus.PUBLISHED),
                        new OperatorUsageReference("flow-a", "def-a", "2.0.0", "ver-a2", GraphVersionStatus.DRAFT),
                        new OperatorUsageReference("flow-b", "def-b", "1.0.0", "ver-b1", GraphVersionStatus.PUBLISHED)
                ))
        );
        graphEngineService.operatorInventoryResult = List.of(first, second);

        mockMvc.perform(get("/api/v1/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("enrichOrder"))
                .andExpect(jsonPath("$[0].usage.definitionCount").value(0))
                .andExpect(jsonPath("$[1].name").value("notifyCustomer"))
                .andExpect(jsonPath("$[1].usage.definitionCount").value(2))
                .andExpect(jsonPath("$[1].usage.versionCount").value(3))
                .andExpect(jsonPath("$[1].usage.references.length()").value(3));
    }
}
