package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Opt-in non-production composition for the read-only Capability Studio demo authority. */
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
}
