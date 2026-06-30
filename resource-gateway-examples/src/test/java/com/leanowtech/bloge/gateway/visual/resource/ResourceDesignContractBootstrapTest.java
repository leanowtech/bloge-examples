package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

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
}
