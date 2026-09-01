package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LegacyAssetMigrationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LegacyAssetMigrationConfiguration.class)
            .withBean(ResourceRegistry.class, () -> mock(ResourceRegistry.class))
            .withBean(ResourceDesignContractRegistry.class, () -> mock(ResourceDesignContractRegistry.class))
            .withBean(GraphDraftRepository.class, () -> mock(GraphDraftRepository.class))
            .withBean(VisualGraphPublicationRepository.class, () -> mock(VisualGraphPublicationRepository.class));

    @Test
    void inventoryIsAvailableWithoutEnablingDurableApiResourceWrites() {
        runner.run(context -> assertThat(context).hasSingleBean(LegacyAssetMigrationModule.class));
    }
}
