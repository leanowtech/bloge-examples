package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for built-in visual resource contract seed data.
 */
class ResourceDesignContractBootstrapTest {

    @Test
    void seededContractsPassResourceContractValidation() {
        InMemoryResourceDesignContractRegistry registry = new InMemoryResourceDesignContractRegistry();
        ResourceDesignContractBootstrap bootstrap = new ResourceDesignContractBootstrap(registry);
        ResourceDesignContractValidator validator = new ResourceDesignContractValidator();

        bootstrap.seedContracts();

        assertThat(registry.all()).isNotEmpty();
        assertThat(registry.all())
                .allSatisfy(contract -> {
                    VisualValidationResult result = validator.validate(contract);
                    assertThat(result.diagnostics())
                            .describedAs(contract.resourceId())
                            .isEmpty();
                });
    }

    @Test
    void seedContractsDoesNotOverwriteExistingContracts() {
        InMemoryResourceDesignContractRegistry registry = new InMemoryResourceDesignContractRegistry();
        registry.upsert(new ResourceDesignContract(
                "contract:user-service.getProfile",
                "user-service.getProfile",
                "Customized user profile",
                "User-maintained visual contract.",
                List.of("custom"),
                SchemaEnvelope.object(Map.of(
                        "userId", Map.of("type", "string")
                ), List.of("userId")),
                SchemaEnvelope.object(Map.of(
                        "customScore", Map.of("type", "integer")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        ));
        ResourceDesignContractBootstrap bootstrap = new ResourceDesignContractBootstrap(registry);

        bootstrap.seedContracts();

        assertThat(registry.findByResourceId("user-service.getProfile"))
                .map(ResourceDesignContract::displayName)
                .contains("Customized user profile");
    }
}
