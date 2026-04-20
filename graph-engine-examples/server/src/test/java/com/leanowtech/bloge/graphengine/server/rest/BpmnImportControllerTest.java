package com.leanowtech.bloge.graphengine.server.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BpmnImportControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new BpmnImportController());
    }

    @Test
    void importSimpleSequentialBpmnReturnsDsl() throws Exception {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <process id="simpleSequential" name="simpleSequential">
                    <startEvent id="start"/>
                    <serviceTask id="fetchCustomer" name="Fetch Customer"
                                 camunda:delegateExpression="fetchCustomer"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="fetchCustomer"/>
                    <sequenceFlow id="f2" sourceRef="fetchCustomer" targetRef="end"/>
                  </process>
                </definitions>
                """;

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", bpmnXml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dslSource").isString())
                .andExpect(jsonPath("$.dslSource").isNotEmpty());
    }

    @Test
    void importWithOperatorMappingsReturnsMappedDsl() throws Exception {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <process id="mapped" name="mapped">
                    <startEvent id="start"/>
                    <serviceTask id="fetchCustomer" name="Fetch Customer"
                                 camunda:delegateExpression="fetchCustomer"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="fetchCustomer"/>
                    <sequenceFlow id="f2" sourceRef="fetchCustomer" targetRef="end"/>
                  </process>
                </definitions>
                """;

        String body = """
                {
                  "bpmnXml": %s,
                  "operatorMappings": [
                    {
                      "taskDefinitionKey": "fetchCustomer",
                      "type": "serviceTask",
                      "operatorRef": "FetchCustomerOp",
                      "inputMapping": {"customerId": "ctx.customerId"}
                    }
                  ]
                }
                """.formatted(objectMapper.writeValueAsString(bpmnXml));

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dslSource").isString());
    }

    @Test
    void importWithDefaultMappingsUsedForUnmatchedTasks() throws Exception {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <process id="withScript" name="withScript">
                    <startEvent id="start"/>
                    <scriptTask id="computeTotal" name="Compute Total" scriptFormat="groovy">
                      <script>total = price * quantity</script>
                    </scriptTask>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="computeTotal"/>
                    <sequenceFlow id="f2" sourceRef="computeTotal" targetRef="end"/>
                  </process>
                </definitions>
                """;

        String body = """
                {
                  "bpmnXml": %s,
                  "defaultMappings": {
                    "scriptTask": {"operatorRef": "__script__", "lang": "groovy"}
                  }
                }
                """.formatted(objectMapper.writeValueAsString(bpmnXml));

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dslSource").isString());
    }

    @Test
    void importWithStrictModeReportsErrorsOnWarnings() throws Exception {
        // Use BPMN with a construct that generates warnings (unmapped service task)
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="unmapped" name="unmapped">
                    <startEvent id="start"/>
                    <serviceTask id="unknownTask" name="Unknown"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="unknownTask"/>
                    <sequenceFlow id="f2" sourceRef="unknownTask" targetRef="end"/>
                  </process>
                </definitions>
                """;

        String body = """
                {
                  "bpmnXml": %s,
                  "strictMode": true
                }
                """.formatted(objectMapper.writeValueAsString(bpmnXml));

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.diagnostics").isArray())
                .andExpect(jsonPath("$.diagnostics.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void importWithoutStrictModeReportsWarningsButSucceeds() throws Exception {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="unmapped" name="unmapped">
                    <startEvent id="start"/>
                    <serviceTask id="unknownTask" name="Unknown"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="unknownTask"/>
                    <sequenceFlow id="f2" sourceRef="unknownTask" targetRef="end"/>
                  </process>
                </definitions>
                """;

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", bpmnXml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.diagnostics").isArray())
                .andExpect(jsonPath("$.dslSource").isString());
    }

    @Test
    void importRejectsBlankBpmnXml() throws Exception {
        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bpmnXml": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importRejectsMissingBpmnPayload() throws Exception {
        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importRejectsRequestsWithBothXmlAndJsonPayloads() throws Exception {
        String body = """
                {
                  "bpmnXml": "<definitions/>",
                  "bpmnJson": "[]"
                }
                """;

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importJsonPayloadReturnsDsl() throws Exception {
        String bpmnJson = """
                {
                  "id": "jsonImport",
                  "name": "jsonImport",
                  "elements": [
                    {"resourceId":"start","stencil":"StartNoneEvent","properties":{"name":"Start"}},
                    {"resourceId":"fetchCustomer","stencil":"UserTask","properties":{"name":"Fetch Customer","nodeType":"systemUserTask","inputParams":{"customerId":"ctx.customerId"}}},
                    {"resourceId":"end","stencil":"EndNoneEvent","properties":{"name":"End"}},
                    {"resourceId":"f1","stencil":"SequenceFlow","source":{"resourceId":"start"},"target":{"resourceId":"fetchCustomer"},"properties":{}},
                    {"resourceId":"f2","stencil":"SequenceFlow","source":{"resourceId":"fetchCustomer"},"target":{"resourceId":"end"},"properties":{}}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnJson", bpmnJson))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dslSource").isString())
                .andExpect(jsonPath("$.diagnostics.length()").value(0));
    }

    @Test
    void importReturnsErrorForMalformedXml() throws Exception {
        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("bpmnXml", "<<<not xml>>>"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void importDiagnosticsContainExpectedFields() throws Exception {
        // Unmapped service task produces UNMAPPED_OPERATOR diagnostic
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="diag" name="diag">
                    <startEvent id="start"/>
                    <serviceTask id="myTask" name="My Task"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="myTask"/>
                    <sequenceFlow id="f2" sourceRef="myTask" targetRef="end"/>
                  </process>
                </definitions>
                """;

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", bpmnXml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics[0].severity").isString())
                .andExpect(jsonPath("$.diagnostics[0].code").isString())
                .andExpect(jsonPath("$.diagnostics[0].message").isString());
    }

    @Test
    void importUserTaskPreservesMetadata() throws Exception {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <process id="approval" name="approval">
                    <startEvent id="start"/>
                    <userTask id="review" name="Review Order"
                              camunda:assignee="john.doe"
                              camunda:candidateGroups="managers"
                              camunda:formKey="orderReviewForm"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                  </process>
                </definitions>
                """;

        mockMvc.perform(post("/api/v1/import/bpmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", bpmnXml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dslSource").isString());
    }
}
