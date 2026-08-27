package com.leanowtech.bloge.gateway.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration-style test for {@link ResourceRegistryAdminController} using
 * standalone MockMvc (no Spring Boot context needed).
 */
class ResourceRegistryAdminControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        var jdbc = new JdbcTemplate(ds);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var registry = new DatabaseResourceRegistry(jdbc, objectMapper, new BlgeExpressionEvaluator());
        registry.init();
        var controller = new ResourceRegistryAdminController(registry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listAll_initiallyEmpty() throws Exception {
        mockMvc.perform(get("/admin/resources"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createDescriptor_returns201() throws Exception {
        var descriptor = simpleDescriptor("test.create-api", "http://example.com/api/{id}", "GET");
        String json = objectMapper.writeValueAsString(descriptor);

        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceId").value("test.create-api"));
    }

    @Test
    void createDescriptorWithHeaderAndCookieExpressions_roundTrips() throws Exception {
        var descriptor = new ResourceDescriptor(
                "test.headers-api",
                "http://example.com/api/{id}",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of("id", "ctx.params.id"),
                        Map.of(),
                        Map.of("X-Request-Id", "ctx.params[\"X-Request-Id\"]"),
                        Map.of("SESSION", "ctx.params.sessionId"),
                        null
                ),
                new ResponseProtocol.HttpStatus(),
                null
        );

        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(descriptor)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$['parameterMapping']['headerExpressions']['X-Request-Id']")
                        .value("ctx.params[\"X-Request-Id\"]"))
                .andExpect(jsonPath("$['parameterMapping']['cookieExpressions']['SESSION']")
                        .value("ctx.params.sessionId"));

        mockMvc.perform(get("/admin/resources/test.headers-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['parameterMapping']['headerExpressions']['X-Request-Id']")
                        .value("ctx.params[\"X-Request-Id\"]"))
                .andExpect(jsonPath("$['parameterMapping']['cookieExpressions']['SESSION']")
                        .value("ctx.params.sessionId"));
    }

    @Test
    void getOne_afterCreate() throws Exception {
        var descriptor = simpleDescriptor("svc-get", "http://example.com/api/get", "GET");
        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(descriptor)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/resources/svc-get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value("svc-get"))
                .andExpect(jsonPath("$.method").value("GET"));
    }

    @Test
    void updateDescriptor_changesMethod() throws Exception {
        var original = simpleDescriptor("svc-update", "http://example.com/api/u", "GET");
        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());

        var updated = simpleDescriptor("svc-update", "http://example.com/api/u", "POST");
        mockMvc.perform(put("/admin/resources/svc-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"));
    }

    @Test
    void putDescriptor_createsWhenMissingAndIsIdempotent() throws Exception {
        var descriptor = simpleDescriptor("browser-profile", "https://api.example.test/profile", "GET");
        String json = objectMapper.writeValueAsString(descriptor);

        mockMvc.perform(put("/admin/resources/browser-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value("browser-profile"));
        mockMvc.perform(put("/admin/resources/browser-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urlTemplate").value("https://api.example.test/profile"));
        mockMvc.perform(get("/admin/resources/browser-profile"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteDescriptor_returns204() throws Exception {
        var descriptor = simpleDescriptor("svc-delete", "http://example.com/api/d", "DELETE");
        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(descriptor)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/admin/resources/svc-delete"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/resources/svc-delete"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateCreate_returns409() throws Exception {
        var descriptor = simpleDescriptor("svc-dup", "http://example.com/api/dup", "GET");
        String json = objectMapper.writeValueAsString(descriptor);

        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict());
    }

    @Test
    void getMissing_returns404() throws Exception {
        mockMvc.perform(get("/admin/resources/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void putMismatchedId_returns400() throws Exception {
        var descriptor = simpleDescriptor("svc-mm", "http://example.com/api/mm", "GET");
        mockMvc.perform(post("/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(descriptor)))
                .andExpect(status().isCreated());

        var different = simpleDescriptor("svc-other", "http://example.com/api/mm", "GET");
        mockMvc.perform(put("/admin/resources/svc-mm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(different)))
                .andExpect(status().isBadRequest());
    }

    private static ResourceDescriptor simpleDescriptor(String id, String url, String method) {
        return new ResourceDescriptor(id, url, method, Map.of(), null,
                Duration.ofSeconds(5), ParameterMapping.empty(),
                new ResponseProtocol.HttpStatus(), null);
    }
}
