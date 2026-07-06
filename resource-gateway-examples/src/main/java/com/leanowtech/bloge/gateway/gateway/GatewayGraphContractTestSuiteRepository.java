package com.leanowtech.bloge.gateway.gateway;

import java.util.List;
import java.util.Optional;

/**
 * Repository for stored resource graph contract-test suites.
 */
public interface GatewayGraphContractTestSuiteRepository {

    /**
     * @return all suites in stable order
     */
    List<GatewayGraphContractTestSuite> all();

    /**
     * @param suiteId stable suite id
     * @return matching suite
     */
    Optional<GatewayGraphContractTestSuite> find(String suiteId);

    /**
     * @param suite suite to store
     * @return stored suite
     */
    GatewayGraphContractTestSuite save(GatewayGraphContractTestSuite suite);
}
