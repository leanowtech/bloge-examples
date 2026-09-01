package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableDefinition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyResourceDescriptorSource;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visualadapter.ResourceRegistryVisualAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Assembly for the read-only legacy asset compatibility projection. */
@Configuration(proxyBeanMethods = false)
public class LegacyAssetMigrationConfiguration {
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~:/{}-]*$");

    /** Requires every legacy authority so an enabled inventory cannot silently omit an asset family. */
    @Bean
    @ConditionalOnMissingBean
    LegacyAssetMigrationModule legacyAssetMigrationModule(
            ResourceRegistry resources,
            ResourceDesignContractRegistry contracts,
            GraphDraftRepository drafts,
            VisualGraphPublicationRepository publications,
            JsonSchemaSampleGenerator samples,
            ObjectMapper mapper,
            ObjectProvider<ApiResourceCommitStore> authoredResourceStores) {
        return new LegacyAssetMigrationModule(
                descriptors(resources), contracts, drafts, publications, samples, mapper,
                new ApiResourceDecisions(mapper), (scope, resourceId) -> {
                    ApiResourceCommitStore store = authoredResourceStores.getIfAvailable();
                    if (store == null) return Optional.empty();
                    return store.findHead(scope, resourceId).map(stored -> {
                        var resource = stored.resource();
                        return new ComposableDefinition(
                                new ReusableFlowCommand.ComposableRef.ApiResource(
                                        resource.resourceId(), resource.revision(), resource.fingerprint()),
                                resource.contract().input(), resource.contract().output());
                    });
                });
    }

    private static LegacyResourceDescriptorSource descriptors(ResourceRegistry resources) {
        return new LegacyResourceDescriptorSource() {
            @Override
            public Set<String> resourceIds() {
                return resources.all().stream().map(value -> value.resourceId())
                        .collect(Collectors.toUnmodifiableSet());
            }

            @Override
            public Optional<Descriptor> find(String resourceId) {
                if (!resources.contains(resourceId)) return Optional.empty();
                VisualResourceDescriptor source = ResourceRegistryVisualAdapter.toVisual(resources.resolve(resourceId));
                return safePath(source.urlTemplate()).map(path -> new Descriptor(
                        source.resourceId(), source.method(), path, source.parameterMapping(),
                        source.responseProtocol(), source.payloadPath()));
            }
        };
    }

    private static Optional<String> safePath(String template) {
        if (template == null || template.isBlank() || template.chars().anyMatch(Character::isISOControl)
                || template.indexOf('?') >= 0 || template.indexOf('#') >= 0) {
            return Optional.empty();
        }
        String candidate = template;
        String lower = template.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int authorityStart = template.indexOf("://") + 3;
            int pathStart = template.indexOf('/', authorityStart);
            if (pathStart < 0 || pathStart == authorityStart || template.substring(authorityStart, pathStart)
                    .contains("@")) {
                return Optional.empty();
            }
            candidate = template.substring(pathStart);
        }
        return SAFE_PATH.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }
}
