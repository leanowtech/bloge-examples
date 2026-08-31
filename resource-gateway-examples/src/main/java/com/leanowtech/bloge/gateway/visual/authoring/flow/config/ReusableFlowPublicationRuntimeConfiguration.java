package com.leanowtech.bloge.gateway.visual.authoring.flow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.JdbcReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in V015 persistence assembly for immutable reusable Flow versions. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public class ReusableFlowPublicationRuntimeConfiguration {
    /** Creates the read-only V015 startup gate. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowPublicationSchemaReadiness reusableFlowPublicationSchemaReadiness(JdbcTemplate jdbc) {
        return new ReusableFlowPublicationSchemaReadiness(jdbc);
    }

    /** Creates the JDBC publication authority over the application transaction manager. */
    @Bean
    @ConditionalOnMissingBean(ReusableFlowPublicationStore.class)
    ReusableFlowPublicationStore reusableFlowPublicationStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper mapper,
            ReusableFlowPublicationSchemaReadiness readiness) {
        if (readiness == null) throw new IllegalStateException("Reusable Flow publication schema is not ready");
        return new JdbcReusableFlowPublicationStore(
                jdbc, new TransactionTemplate(transactionManager), mapper);
    }
}
