package com.leanowtech.bloge.gateway.visual.authoring.fixture.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.JdbcApiFixtureSetCommitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in V012 assembly for private Default Fixture children. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiFixtureSetRuntimeConfiguration {
    /** Creates the read-only V012 startup gate. */
    @Bean
    @ConditionalOnMissingBean
    ApiFixtureSetSchemaReadiness apiFixtureSetSchemaReadiness(JdbcTemplate jdbc) {
        return new ApiFixtureSetSchemaReadiness(jdbc);
    }

    /** Creates the deterministic example-to-Fixture materializer. */
    @Bean
    @ConditionalOnMissingBean
    DefaultFixtureSetMaterializer defaultFixtureSetMaterializer() {
        return new DefaultFixtureSetMaterializer();
    }

    /** Creates the JDBC child store over the same application transaction manager. */
    @Bean
    @ConditionalOnMissingBean(ApiFixtureSetCommitStore.class)
    ApiFixtureSetCommitStore apiFixtureSetCommitStore(JdbcTemplate jdbc,
                                                       PlatformTransactionManager transactionManager,
                                                       ObjectMapper mapper,
                                                       ApiFixtureSetSchemaReadiness readiness) {
        if (readiness == null) throw new IllegalStateException("API Fixture Set schema is not ready");
        return new JdbcApiFixtureSetCommitStore(jdbc, new TransactionTemplate(transactionManager), mapper);
    }
}
