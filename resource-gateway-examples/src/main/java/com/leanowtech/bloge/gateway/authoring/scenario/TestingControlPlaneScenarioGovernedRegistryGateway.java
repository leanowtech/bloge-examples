package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;

import java.util.Objects;

/**
 * Adapter that delegates Scenario publication to the authoritative testing-control-plane services.
 */
public final class TestingControlPlaneScenarioGovernedRegistryGateway
        implements ScenarioGovernedRegistryGateway {

    private final TestExecutionApiService executions;
    private final TestSuiteRegistryService suites;

    /**
     * @param executions target and fixture registry boundary
     * @param suites immutable suite registry boundary
     */
    public TestingControlPlaneScenarioGovernedRegistryGateway(
            TestExecutionApiService executions,
            TestSuiteRegistryService suites) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.suites = Objects.requireNonNull(suites, "suites");
    }

    @Override
    public TestExecutionApiRequest.Target describeGraphTarget(
            String graphName,
            IntegrationRequestContext identity) {
        return executions.describeGraphTarget(graphName, identity).target();
    }

    @Override
    public TestExecutionApiRequest.Target describeOperatorTarget(
            String operatorRef,
            IntegrationRequestContext identity) {
        return executions.describeOperatorTarget(operatorRef, identity).target();
    }

    @Override
    public StoredFixtureBundle registerFixture(
            String fixtureBundleId,
            FixtureBundleRegistrationRequest request,
            IntegrationRequestContext identity) {
        return executions.registerFixture(fixtureBundleId, request, identity);
    }

    @Override
    public StoredFixtureBundle findFixture(
            String fixtureBundleId,
            long revision,
            IntegrationRequestContext identity) {
        return executions.findFixture(fixtureBundleId, revision, identity);
    }

    @Override
    public StoredTestSuite registerSuite(
            String suiteId,
            TestSuiteRegistrationRequest request,
            IntegrationRequestContext identity) {
        return suites.register(suiteId, request, identity);
    }

    @Override
    public StoredTestSuite findSuite(
            String suiteId,
            long revision,
            IntegrationRequestContext identity) {
        return suites.find(suiteId, revision, identity);
    }
}
