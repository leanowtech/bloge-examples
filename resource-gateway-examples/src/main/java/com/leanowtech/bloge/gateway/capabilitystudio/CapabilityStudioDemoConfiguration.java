package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in non-production composition for the Capability Studio demo authority. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.capability-studio.demo", name = "enabled", havingValue = "true")
public class CapabilityStudioDemoConfiguration {

    @Bean
    CapabilityStudioGoldenDemoPack capabilityStudioGoldenDemoPack(ObjectMapper mapper) {
        try {
            return new CapabilityStudioGoldenDemoPackLoader().load(mapper);
        } catch (RuntimeException failure) {
            throw new BeanCreationException("capabilityStudioGoldenDemoPack", "Invalid golden demo pack", failure);
        }
    }

    @Bean
    CapabilityStudioTutorialBranchRepository capabilityStudioTutorialBranchRepository(
            JdbcTemplate jdbc) {
        return new CapabilityStudioTutorialBranchRepository(jdbc);
    }

    @Bean
    TransactionTemplate capabilityStudioTutorialBranchTransactions(
            PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    CapabilityStudioTutorialBranchAuthority capabilityStudioTutorialBranchAuthority(
            CapabilityStudioTutorialBranchRepository repository,
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            TransactionTemplate capabilityStudioTutorialBranchTransactions) {
        return new CapabilityStudioTutorialBranchAuthority(
                repository, pack, mapper, capabilityStudioTutorialBranchTransactions);
    }

    @Bean
    CapabilityStudioScenarioDatasetProjector capabilityStudioScenarioDatasetProjector(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper) {
        return new CapabilityStudioScenarioDatasetProjector(pack, mapper);
    }
}
