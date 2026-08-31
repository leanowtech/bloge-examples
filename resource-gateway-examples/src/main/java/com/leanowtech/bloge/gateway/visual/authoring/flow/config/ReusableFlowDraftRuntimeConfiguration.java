package com.leanowtech.bloge.gateway.visual.authoring.flow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.JdbcReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in V014 persistence assembly for reusable Tool/Solution drafts. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public class ReusableFlowDraftRuntimeConfiguration {
    /** Creates the read-only V014 startup gate. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowDraftSchemaReadiness reusableFlowDraftSchemaReadiness(JdbcTemplate jdbc) {
        return new ReusableFlowDraftSchemaReadiness(jdbc);
    }

    /** Creates the JDBC draft authority over the application transaction manager. */
    @Bean
    @ConditionalOnMissingBean(ReusableFlowDraftStore.class)
    ReusableFlowDraftStore reusableFlowDraftStore(JdbcTemplate jdbc,
                                                   PlatformTransactionManager transactionManager,
                                                   ObjectMapper mapper,
                                                   ReusableFlowDraftSchemaReadiness readiness) {
        if (readiness == null) throw new IllegalStateException("Reusable Flow schema is not ready");
        return new JdbcReusableFlowDraftStore(
                jdbc, new TransactionTemplate(transactionManager), mapper);
    }
}
