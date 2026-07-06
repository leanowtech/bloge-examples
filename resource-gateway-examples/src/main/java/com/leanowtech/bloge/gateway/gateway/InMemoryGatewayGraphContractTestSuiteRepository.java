package com.leanowtech.bloge.gateway.gateway;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        List<GatewayGraphContractTestCase> cases = new ArrayList<>();
        cases.add(new GatewayGraphContractTestCase(
                "prime applicant approves through R1",
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                List.of(new GatewayGraphResourceMock(
                        "loan-applicant-service.getProfile",
                        Map.of("applicantId", "prime"),
                        Map.of("applicantId", "prime", "score", 780, "segment", "private-bank"))),
                "assembleLoanDecision",
                List.of(new GatewayGraphTestAssertion(
                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                        "/policy/ruleId",
                        "R1")),
                Map.of("loanPolicy", List.of(new GatewayGraphTestAssertion(
                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                        "/decision",
                        "approved")))));
        cases.add(new GatewayGraphContractTestCase(
                "declined applicant falls through R4",
                Map.of("applicantId", "decline", "requestedAmount", 120_000.0),
                List.of(new GatewayGraphResourceMock(
                        "loan-applicant-service.getProfile",
                        Map.of("applicantId", "decline"),
                        Map.of("applicantId", "decline", "score", 590, "segment", "new"))),
                "assembleLoanDecision",
                List.of(new GatewayGraphTestAssertion(
                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                        "/policy/ruleId",
                        "R4")),
                Map.of("loanPolicy", List.of(new GatewayGraphTestAssertion(
                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                        "/decision",
                        "declined")))));

        return List.of(new GatewayGraphContractTestSuite(
                "loan-decision-policy-smoke",
                "Loan decision policy smoke",
                "Covers the prime approval and decline lanes of the resource-backed decision table.",
                List.of("built-in", "loan", "decision-table", "smoke"),
                new GatewayGraphContractTestSuiteRequest("loanDecisionPolicy", cases),
                new GatewayGraphContractTestCoveragePolicy(
                        2,
                        2,
                        2,
                        2,
                        4,
                        List.of("assembleLoanDecision"))));
    }
}
