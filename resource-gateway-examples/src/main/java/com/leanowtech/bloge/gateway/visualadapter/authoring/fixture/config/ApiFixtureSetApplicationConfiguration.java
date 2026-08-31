package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture.config;

import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureModule;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.CompositeFixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/** Opt-in application assembly for private Fixture Set reads. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiFixtureSetApplicationConfiguration {
    /** Combines V012 child and optional V016 standalone authorities without ambiguous ids. */
    @Bean
    @Primary
    FixtureSetAuthorityReader fixtureSetAuthorityReader(
            ApiFixtureSetCommitStore child,
            ObjectProvider<StandaloneFixtureSetStore> standalone) {
        List<FixtureSetAuthorityReader> readers = new ArrayList<>();
        readers.add(child);
        StandaloneFixtureSetStore independent = standalone.getIfAvailable();
        if (independent != null) readers.add(independent);
        return readers.size() == 1 ? child : new CompositeFixtureSetAuthorityReader(readers);
    }

    /** Creates the deep read/write module over the exact configured authorities. */
    @Bean
    @ConditionalOnMissingBean
    ApiFixtureSetAuthoringFacade apiFixtureSetAuthoringFacade(
            FixtureSetAuthorityReader reader,
            ObjectProvider<ReusableFlowFixtureModule> writer) {
        return new ApiFixtureSetAuthoringFacade(reader, writer.getIfAvailable());
    }
}
