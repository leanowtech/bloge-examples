package com.leanowtech.bloge.gateway.visual.testing;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for stored operator contract-test suites.
 */
public interface VisualOperatorContractTestSuiteRepository {

    /**
     * @return all stored suites
     */
    Collection<VisualOperatorContractTestSuite> all();

    /**
     * @param suiteId suite id
     * @return matching suite
     */
    Optional<VisualOperatorContractTestSuite> find(String suiteId);

    /**
     * @param suite suite to save
     * @return stored suite
     */
    VisualOperatorContractTestSuite save(VisualOperatorContractTestSuite suite);
}
