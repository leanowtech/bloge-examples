package com.leanowtech.bloge.gateway.visual.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installs the replaceable enterprise candidate-provider SPI and its pure application service. */
@Configuration(proxyBeanMethods = false)
public class ReferenceCandidateConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ReferenceCandidateProvider referenceCandidateProvider(GraphDraftRepository graphDrafts,
                                                          VisualOperatorCatalog operators,
                                                          ObjectMapper mapper,
                                                          ObjectProvider<CorrectnessDefinitionRepository> definitions,
                                                          ObjectProvider<ReferenceCandidateContributor> contributors) {
        return new ResourceGatewayReferenceCandidateProvider(
                graphDrafts, operators, mapper, definitions.getIfAvailable(),
                contributors.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    ReferenceCandidateService referenceCandidateService(ReferenceCandidateProvider provider) {
        return new ReferenceCandidateService(provider);
    }
}
