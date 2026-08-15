package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;

import java.util.Objects;

/** Adapter delegating correctness publication to the authoritative Testing Control Plane. */
public final class TestingControlPlaneCorrectnessRegistryGateway
        implements CorrectnessTestingRegistryGateway {

    private final TestExecutionApiService executions;
    private final TestSuiteRegistryService suites;

    public TestingControlPlaneCorrectnessRegistryGateway(
            TestExecutionApiService executions,
            TestSuiteRegistryService suites
    ) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.suites = Objects.requireNonNull(suites, "suites");
    }

    @Override
    public StoredFixtureBundle registerFixture(
            String fixtureBundleId,
            FixtureBundleRegistrationRequest request,
            IntegrationRequestContext identity
    ) {
        return executions.registerFixture(fixtureBundleId, request, identity);
    }

    @Override
    public StoredFixtureBundle findFixture(
            String fixtureBundleId,
            long revision,
            IntegrationRequestContext identity
    ) {
        return executions.findFixture(fixtureBundleId, revision, identity);
    }

    @Override
    public StoredTestSuite registerSuite(
            String suiteId,
            TestSuiteRegistrationRequest request,
            IntegrationRequestContext identity
    ) {
        return suites.register(suiteId, request, identity);
    }

    @Override
    public StoredTestSuite findSuite(
            String suiteId,
            long revision,
            IntegrationRequestContext identity
    ) {
        return suites.find(suiteId, revision, identity);
    }
}
