package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;

/** Narrow outbound port to the existing immutable testing registries. */
public interface CorrectnessTestingRegistryGateway {

    StoredFixtureBundle registerFixture(
            String fixtureBundleId,
            FixtureBundleRegistrationRequest request,
            IntegrationRequestContext identity);

    StoredFixtureBundle findFixture(
            String fixtureBundleId,
            long revision,
            IntegrationRequestContext identity);

    StoredTestSuite registerSuite(
            String suiteId,
            TestSuiteRegistrationRequest request,
            IntegrationRequestContext identity);

    StoredTestSuite findSuite(
            String suiteId,
            long revision,
            IntegrationRequestContext identity);
}
