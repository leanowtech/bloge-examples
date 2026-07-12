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
     * Returns one immutable stored revision when the repository supports history.
     */
    default Optional<VisualOperatorContractTestSuite> findRevision(String suiteId, long revision) {
        return Optional.empty();
    }

    /**
     * Returns the current aggregate revision, or zero when history is unsupported.
     */
    default long revision(String suiteId) {
        return 0;
    }

    /**
     * @param suite suite to save
     * @return stored suite
     */
    VisualOperatorContractTestSuite save(VisualOperatorContractTestSuite suite);
}
