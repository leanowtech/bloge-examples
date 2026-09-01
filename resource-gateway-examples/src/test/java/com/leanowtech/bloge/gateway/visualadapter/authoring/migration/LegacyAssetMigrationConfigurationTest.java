package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationFailure;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyAssetMigrationConfigurationTest {
    @Test
    void inventoryIsAvailableWithoutEnablingDurableApiResourceWrites() {
        runner(mock(ResourceRegistry.class), mock(ResourceDesignContractRegistry.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(LegacyAssetMigrationModule.class);
                    assertThat(context).hasSingleBean(LegacyAssetMigrationProblemHandler.class);
                });
    }

    @Test
    void adapterKeepsOnlyTheRelativePathAndRejectsAmbiguousLegacyUrls() {
        ResourceRegistry resources = mock(ResourceRegistry.class);
        ResourceDescriptor safe = descriptor("customer.get", "https://secret.example.test/customers/{customerId}");
        ResourceDescriptor unsafe = descriptor("unsafe.get", "https://user@secret.example.test/unsafe");
        when(resources.all()).thenReturn(List.of(safe, unsafe));
        when(resources.contains("customer.get")).thenReturn(true);
        when(resources.contains("unsafe.get")).thenReturn(true);
        when(resources.resolve("customer.get")).thenReturn(safe);
        when(resources.resolve("unsafe.get")).thenReturn(unsafe);
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(contract("customer.get"));
        contracts.upsert(contract("unsafe.get"));

        runner(resources, contracts)
                .run(context -> {
                    LegacyAssetMigrationModule module = context.getBean(LegacyAssetMigrationModule.class);
                    assertThat(module.previewResource("customer.get").suggestedResource().operation().path())
                            .isEqualTo("/customers/{customerId}");
                    assertThatThrownBy(() -> module.previewResource("unsafe.get"))
                            .isInstanceOf(LegacyAssetMigrationFailure.class)
                            .extracting("code").isEqualTo(LegacyAssetMigrationFailure.Code.NEEDS_REPAIR);
                });
    }

    private static ApplicationContextRunner runner(ResourceRegistry resources,
                                                   ResourceDesignContractRegistry contracts) {
        return new ApplicationContextRunner()
                .withUserConfiguration(LegacyAssetMigrationConfiguration.class,
                        LegacyAssetMigrationProblemHandler.class)
                .withBean(ResourceRegistry.class, () -> resources)
                .withBean(ResourceDesignContractRegistry.class, () -> contracts)
                .withBean(GraphDraftRepository.class, () -> mock(GraphDraftRepository.class))
                .withBean(VisualGraphPublicationRepository.class,
                        () -> mock(VisualGraphPublicationRepository.class))
                .withBean(JsonSchemaSampleGenerator.class, JsonSchemaSampleGenerator::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ApiResourceDecisions.class, ApiResourceDecisions::new);
    }

    private static ResourceDescriptor descriptor(String id, String url) {
        return new ResourceDescriptor(id, url, "GET", Map.of("X-Legacy-Secret", "hidden"), null,
                Duration.ofSeconds(5), new ParameterMapping(Map.of("customerId", "ctx.params.customerId"),
                Map.of(), null), new ResponseProtocol.HttpStatus(), null);
    }

    private static ResourceDesignContract contract(String id) {
        return new ResourceDesignContract(null, id, id, "", List.of(),
                SchemaEnvelope.object(Map.of("customerId", Map.of("type", "string")), List.of("customerId")),
                SchemaEnvelope.object(Map.of(), List.of()), Map.of(), ResourceDesignContract.STATUS_ACTIVE);
    }
}
