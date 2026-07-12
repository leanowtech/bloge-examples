package com.leanowtech.bloge.gateway.visual.testing;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory repository for demo and local CI operator contract-test suites.
 */
public class InMemoryVisualOperatorContractTestSuiteRepository
        implements VisualOperatorContractTestSuiteRepository {

    private final Map<String, VisualOperatorContractTestSuite> suites = new ConcurrentHashMap<>();

    /**
     * Creates an empty repository.
     */
    public InMemoryVisualOperatorContractTestSuiteRepository() {
        this(List.of());
    }

    /**
     * Creates a repository seeded with suites.
     *
     * @param initialSuites initial suites
     */
    public InMemoryVisualOperatorContractTestSuiteRepository(Collection<VisualOperatorContractTestSuite> initialSuites) {
        if (initialSuites != null) {
            initialSuites.forEach(this::save);
        }
    }

    @Override
    public Collection<VisualOperatorContractTestSuite> all() {
        return suites.values().stream()
                .sorted((left, right) -> left.suiteId().compareTo(right.suiteId()))
                .toList();
    }

    @Override
    public Optional<VisualOperatorContractTestSuite> find(String suiteId) {
        return Optional.ofNullable(suites.get(suiteId));
    }

    @Override
    public VisualOperatorContractTestSuite save(VisualOperatorContractTestSuite suite) {
        VisualOperatorContractTestSuite safeSuite = suite == null
                ? new VisualOperatorContractTestSuite("", "", "", List.of(),
                        new VisualOperatorContractTestSuiteRequest("", List.of()))
                : suite;
        if (safeSuite.suiteId().isBlank()) {
            throw new IllegalArgumentException("suiteId is required.");
        }
        suites.put(safeSuite.suiteId(), safeSuite);
        return safeSuite;
    }
}
