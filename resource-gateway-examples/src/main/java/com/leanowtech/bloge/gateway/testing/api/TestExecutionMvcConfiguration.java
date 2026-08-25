package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers MVC boundary controls for the profile-gated test execution API. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
class TestExecutionMvcConfiguration implements WebMvcConfigurer {
    private final TestExecutionAuthenticationInterceptor testExecutionAuthentication;

    TestExecutionMvcConfiguration(TestExecutionAuthenticationInterceptor testExecutionAuthentication) {
        this.testExecutionAuthentication = testExecutionAuthentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(testExecutionAuthentication)
                .addPathPatterns("/api/testing/**");
    }
}
