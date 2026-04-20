package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphDefinitionControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphDefinitionController(graphEngineService, scopeResolver));
    }

    @Test
    void createDefinitionReturnsCreatedResource() throws Exception {
        GraphDefinition definition = definition("def-1", "order-flow");
        graphEngineService.createDefinitionResult = definition;

        mockMvc.perform(post("/api/v1/graphs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionKey": "order-flow",
                                  "displayName": "Order Flow"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/graphs/order-flow")))
                .andExpect(jsonPath("$.definitionId").value("def-1"))
                .andExpect(jsonPath("$.definitionKey").value("order-flow"))
                .andExpect(jsonPath("$.tenantId").value("default"))
                .andExpect(jsonPath("$.namespace").value("default"));

        assertEquals("order-flow", graphEngineService.createDefinitionCommand.definitionKey());
        assertEquals("default", graphEngineService.createDefinitionCommand.tenantId());
        assertEquals("default", graphEngineService.createDefinitionCommand.namespace());
    }

    @Test
    void updateDefinitionResolvesDefinitionByKeyBeforeDelegating() throws Exception {
        GraphDefinition existing = definition("def-1", "order-flow");
        GraphDefinition updated = new GraphDefinition(
                existing.definitionId(),
                existing.definitionKey(),
                existing.tenantId(),
                existing.namespace(),
                "Renamed flow",
                "Updated description",
                existing.category(),
                existing.labels(),
                existing.ownerTeam(),
                existing.rbacPolicy(),
                existing.status(),
                existing.revision() + 1,
                existing.createdAt(),
                java.time.Instant.now()
        );
        graphEngineService.getDefinitionByKeyResult = existing;
        graphEngineService.updateDefinitionResult = updated;

        mockMvc.perform(put("/api/v1/graphs/order-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 2,
                                  "displayName": "Renamed flow",
                                  "description": "Updated description"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed flow"));

        assertEquals("order-flow", graphEngineService.definitionLookupKey);
        assertEquals("default", graphEngineService.definitionLookupTenantId);
        assertEquals("default", graphEngineService.definitionLookupNamespace);
        assertEquals("def-1", graphEngineService.updateDefinitionCommand.definitionId());
        assertEquals(2L, graphEngineService.updateDefinitionCommand.expectedRevision());
        assertEquals("Renamed flow", graphEngineService.updateDefinitionCommand.displayName());
    }

    @Test
    void archiveDefinitionUsesExpectedRevisionQueryParameter() throws Exception {
        GraphDefinition existing = definition("def-1", "order-flow");
        GraphDefinition archived = new GraphDefinition(
                existing.definitionId(),
                existing.definitionKey(),
                existing.tenantId(),
                existing.namespace(),
                existing.displayName(),
                existing.description(),
                existing.category(),
                existing.labels(),
                existing.ownerTeam(),
                existing.rbacPolicy(),
                GraphDefinitionStatus.ARCHIVED,
                existing.revision() + 1,
                existing.createdAt(),
                java.time.Instant.now()
        );
        graphEngineService.getDefinitionByKeyResult = existing;
        graphEngineService.archiveDefinitionResult = archived;

        mockMvc.perform(delete("/api/v1/graphs/order-flow")
                        .queryParam("expectedRevision", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        assertEquals("def-1", graphEngineService.archiveDefinitionId);
        assertEquals(2L, graphEngineService.archiveExpectedRevision);
    }

    @Test
    void accessDeniedReturnsForbidden() throws Exception {
        graphEngineService.createDefinitionResult = null;
        graphEngineService.createDefinitionOverride = () -> {
            throw new com.leanowtech.bloge.graphengine.service.GraphEngineServiceException(
                    com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode.ACCESS_DENIED,
                    "Access denied: caller does not have 'admin' permission"
            );
        };

        mockMvc.perform(post("/api/v1/graphs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionKey": "restricted-flow",
                                  "displayName": "Restricted"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }
}
