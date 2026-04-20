package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphDeploymentControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphDeploymentController(graphEngineService, scopeResolver));
    }

    @Test
    void createDeploymentBindsPolymorphicRoutingPolicy() throws Exception {
        GraphDeployment deployment = deployment("dep-1", "approval-flow", new VersionRoutingPolicy.Pinned("1.0.0"));
        graphEngineService.createDeploymentResult = deployment;

        mockMvc.perform(post("/api/v1/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionKey": "approval-flow",
                                  "environment": "production",
                                  "active": true,
                                  "routingPolicy": {
                                    "type": "pinned",
                                    "version": "1.0.0"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deploymentId").value("dep-1"))
                .andExpect(jsonPath("$.routingPolicy.type").value("pinned"))
                .andExpect(jsonPath("$.routingPolicy.version").value("1.0.0"));

        assertEquals("default", graphEngineService.createDeploymentCommand.tenantId());
        assertEquals("default", graphEngineService.createDeploymentCommand.namespace());
        VersionRoutingPolicy.Pinned pinned = assertInstanceOf(
                VersionRoutingPolicy.Pinned.class,
                graphEngineService.createDeploymentCommand.routingPolicy()
        );
        assertEquals("1.0.0", pinned.version());
    }
}
