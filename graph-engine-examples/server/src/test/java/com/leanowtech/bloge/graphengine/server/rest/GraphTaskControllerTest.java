package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.model.GraphTaskStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphTaskControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphTaskController(graphEngineService));
    }

    @Test
    void completeTaskDelegatesToService() throws Exception {
        GraphTask completed = task("task-1", "exec-1", "approval-flow", GraphTaskStatus.COMPLETED);
        graphEngineService.completeTaskResult = completed;

        mockMvc.perform(post("/api/v1/tasks/task-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "reviewer",
                                  "output": {
                                    "approved": true
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.candidateUsers[0]").value("alice"))
                .andExpect(jsonPath("$.candidateUsers[1]").value("bob"));

        assertEquals("task-1", graphEngineService.completeTaskCommand.taskId());
        assertEquals("reviewer", graphEngineService.completeTaskCommand.userId());
    }

    @Test
    void claimTaskValidationErrorsReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/task-1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("userId: must not be blank"));
    }
}
