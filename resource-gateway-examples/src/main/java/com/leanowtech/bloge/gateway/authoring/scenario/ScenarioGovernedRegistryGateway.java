package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;

/**
 * Narrow outbound port from Scenario publication to the governed testing control plane.
 *
 * <p>Keeping this interface in the visual module prevents the authoring lifecycle from acquiring
 * registry implementation details while still requiring explicit register and independent read
 * operations.</p>
 */
public interface ScenarioGovernedRegistryGateway {

    /** Discovers the current exact runtime graph target. */
    TestExecutionApiRequest.Target describeGraphTarget(
            String graphName,
            IntegrationRequestContext identity);

    /** Discovers the current exact runtime operator target. */
    TestExecutionApiRequest.Target describeOperatorTarget(
            String operatorRef,
            IntegrationRequestContext identity);

    /** Registers one immutable fixture revision. */
    StoredFixtureBundle registerFixture(
            String fixtureBundleId,
            FixtureBundleRegistrationRequest request,
            IntegrationRequestContext identity);

    /** Independently reads one immutable fixture revision. */
    StoredFixtureBundle findFixture(
            String fixtureBundleId,
            long revision,
            IntegrationRequestContext identity);

    /** Registers one dependency-closed immutable suite revision. */
    StoredTestSuite registerSuite(
            String suiteId,
            TestSuiteRegistrationRequest request,
            IntegrationRequestContext identity);

    /** Independently reads one immutable suite revision. */
    StoredTestSuite findSuite(
            String suiteId,
            long revision,
            IntegrationRequestContext identity);
}
