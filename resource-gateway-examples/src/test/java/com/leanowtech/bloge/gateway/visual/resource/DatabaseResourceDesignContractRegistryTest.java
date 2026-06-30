package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for H2-backed visual resource design contract persistence.
 */
class DatabaseResourceDesignContractRegistryTest {

    private DatabaseResourceDesignContractRegistry registry;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        registry = new DatabaseResourceDesignContractRegistry(jdbc, objectMapper);
        registry.init();
    }

    @Test
    void upsertThenFind() {
        ResourceDesignContract contract = contract("order-service.listOrders", "Order list");

        registry.upsert(contract);

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(contract);
        assertThat(registry.all()).containsExactly(contract);
    }

    @Test
    void persistenceSurvivesReInit() {
        registry.upsert(contract("order-service.listOrders", "Order list"));

        DatabaseResourceDesignContractRegistry reloaded = new DatabaseResourceDesignContractRegistry(
                jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.findByResourceId("order-service.listOrders"))
                .map(ResourceDesignContract::displayName)
                .contains("Order list");
    }

    @Test
    void allReturnsContractsSortedByResourceId() {
        ResourceDesignContract user = contract("user-service.getProfile", "User profile");
        ResourceDesignContract order = contract("order-service.listOrders", "Order list");

        registry.upsert(user);
        registry.upsert(order);

        assertThat(registry.all())
                .extracting(ResourceDesignContract::resourceId)
                .containsExactly("order-service.listOrders", "user-service.getProfile");
    }

    @Test
    void deleteRemovesContractFromDatabaseAndCache() {
        registry.upsert(contract("order-service.listOrders", "Order list"));

        registry.deleteByResourceId("order-service.listOrders");
        DatabaseResourceDesignContractRegistry reloaded = new DatabaseResourceDesignContractRegistry(
                jdbc, objectMapper);
        reloaded.init();

        assertThat(registry.findByResourceId("order-service.listOrders")).isEmpty();
        assertThat(reloaded.findByResourceId("order-service.listOrders")).isEmpty();
    }

    private static ResourceDesignContract contract(String resourceId, String displayName) {
        return new ResourceDesignContract(
                "contract:" + resourceId,
                resourceId,
                displayName,
                "Test contract.",
                List.of("resource"),
                SchemaEnvelope.object(Map.of(
                        "userId", Map.of("type", "string")
                ), List.of()),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object", "additionalProperties", true)
                        )
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );
    }
}
