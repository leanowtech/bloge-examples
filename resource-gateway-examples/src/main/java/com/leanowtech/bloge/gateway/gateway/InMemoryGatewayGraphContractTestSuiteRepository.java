package com.leanowtech.bloge.gateway.gateway;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory repository seeded with representative resource graph contract-test suites.
 */
@Component
public class InMemoryGatewayGraphContractTestSuiteRepository implements GatewayGraphContractTestSuiteRepository {

    private final Map<String, GatewayGraphContractTestSuite> suitesById = new ConcurrentHashMap<>();

    /**
     * Creates a repository with built-in demonstration suites.
     */
    public InMemoryGatewayGraphContractTestSuiteRepository() {
        builtInSuites().forEach(this::save);
    }

    /**
     * Creates a repository with custom initial suites.
     *
     * @param suites initial suites
     */
    public InMemoryGatewayGraphContractTestSuiteRepository(List<GatewayGraphContractTestSuite> suites) {
        (suites == null ? List.<GatewayGraphContractTestSuite>of() : suites).forEach(this::save);
    }

    @Override
    public List<GatewayGraphContractTestSuite> all() {
        return suitesById.values().stream()
                .sorted(Comparator.comparing(GatewayGraphContractTestSuite::suiteId))
                .toList();
    }

    @Override
    public Optional<GatewayGraphContractTestSuite> find(String suiteId) {
        return Optional.ofNullable(suitesById.get(suiteId == null ? "" : suiteId.trim()));
    }

    @Override
    public GatewayGraphContractTestSuite save(GatewayGraphContractTestSuite suite) {
        GatewayGraphContractTestSuite safeSuite = suite == null
                ? new GatewayGraphContractTestSuite("", "", "", List.of(),
                        new GatewayGraphContractTestSuiteRequest("", List.of()),
                        GatewayGraphContractTestCoveragePolicy.none())
                : suite;
        if (safeSuite.suiteId().isBlank()) {
            throw new IllegalArgumentException("Contract test suite id must not be blank.");
        }
        suitesById.put(safeSuite.suiteId(), safeSuite);
        return safeSuite;
    }

    private static List<GatewayGraphContractTestSuite> builtInSuites() {
        return BuiltInGatewayGraphContractTestSuites.all();
    }
}
