package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Collectors;

/** Assembly for the read-only legacy asset compatibility projection. */
@Configuration(proxyBeanMethods = false)
public class LegacyAssetMigrationConfiguration {
    /** Requires every legacy authority so an enabled inventory cannot silently omit an asset family. */
    @Bean
    @ConditionalOnMissingBean
    LegacyAssetMigrationModule legacyAssetMigrationModule(
            ResourceRegistry resources,
            ResourceDesignContractRegistry contracts,
            GraphDraftRepository drafts,
            VisualGraphPublicationRepository publications) {
        return new LegacyAssetMigrationModule(
                () -> resources.all().stream().map(value -> value.resourceId())
                        .collect(Collectors.toUnmodifiableSet()),
                contracts, drafts, publications);
    }
}
