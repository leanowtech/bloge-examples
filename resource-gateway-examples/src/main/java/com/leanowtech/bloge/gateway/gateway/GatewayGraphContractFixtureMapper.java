package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lossless adapter from the legacy graph-contract table model to the common fixture protocol.
 *
 * <p>Both the compatibility runner and catalog materializer use this mapper. Keeping one mapping
 * prevents transport fidelity, retry consumption, schema assertions, or numeric tolerance from
 * acquiring different meanings depending on which execution entry point a caller selects.</p>
 */
public final class GatewayGraphContractFixtureMapper {

    /**
     * Converts one source case into an immutable common fixture revision.
     *
     * @param fixtureBundleId stable destination fixture id
     * @param revision immutable positive destination revision
     * @param targetFingerprint exact graph dependency fingerprint
     * @param testCase source table case
     * @param contract frozen graph input/output contract
     * @param metadata bounded migration or adapter provenance
     * @return common fixture bundle preserving source control and assertion semantics
     */
    public FixtureBundle map(String fixtureBundleId,
                             long revision,
                             String targetFingerprint,
                             GatewayGraphContractTestCase testCase,
                             GatewayGraphContract contract,
                             Map<String, Object> metadata) {
        GatewayGraphContractTestCase safeCase = testCase == null
                ? new GatewayGraphContractTestCase("", Map.of(), List.of(), "", List.of(), Map.of())
                : testCase;
        List<FixtureRule> rules = new ArrayList<>();
        for (int index = 0; index < safeCase.resourceMocks().size(); index++) {
            GatewayGraphResourceMock mock = safeCase.resourceMocks().get(index);
            FixtureRule.Selector selector = FixtureRule.Selector.resource(mock.resourceId());
            if (!mock.expectedParams().isEmpty()) {
                selector = selector.matching(FixtureRule.Match.pathEquals("/params", mock.expectedParams()));
            }
            FixtureRule.Consumption consumption = new FixtureRule.Consumption(
                    mock.required(), mock.minUses(), mock.maxUses(),
                    FixtureRule.ExhaustedAction.FAIL, FixtureRule.UnmatchedAction.FAIL);
            rules.add(new FixtureRule(FixtureRule.SCHEMA_VERSION, "resource-mock-" + index,
                    selector, resourceBehavior(mock), consumption, FixtureRule.SchemaCheck.strict()));
        }
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, fixtureBundleId, revision,
                targetFingerprint, "INTERNAL", null, null, rules,
                assertions(safeCase, contract), metadata);
    }

    /**
     * Returns the common assertion inventory produced for one source case.
     *
     * @param testCase source table case
     * @param contract frozen graph contract
     * @return output-schema, output, and node assertion inventory
     */
    public List<FixtureBundle.Assertion> assertions(GatewayGraphContractTestCase testCase,
                                                    GatewayGraphContract contract) {
        List<FixtureBundle.Assertion> assertions = new ArrayList<>();
        String outputNode = testCase.outputNode().isBlank()
                ? contract.outputNodes().stream().findFirst().orElse("")
                : testCase.outputNode();
        assertions.add(new FixtureBundle.Assertion("OUTPUT_PATH", outputNode, "", "MATCHES_SCHEMA",
                contract.outputSchema().schema(), null));
        testCase.assertions().forEach(assertion -> assertions.add(mapAssertion(
                "OUTPUT_PATH", outputNode, assertion)));
        testCase.nodeAssertions().forEach((nodeId, values) -> values.forEach(assertion ->
                assertions.add(mapAssertion("NODE_OUTPUT", nodeId, assertion))));
        return List.copyOf(assertions);
    }

    private static FixtureRule.Behavior resourceBehavior(GatewayGraphResourceMock mock) {
        if (mock.fixtureMode() == GatewayGraphResourceMock.FixtureMode.OUTPUT_LEVEL) {
            return FixtureRule.Behavior.returning(new HttpResourceOutput(
                    mock.resourceId(), mock.statusCode(), mock.payload(), mock.rawBody(),
                    Duration.ofMillis(mock.durationMs()), mock.success()));
        }
        FixtureRule.DoubleBoundary boundary = mock.fixtureMode()
                == GatewayGraphResourceMock.FixtureMode.TRANSPORT_LEVEL
                ? FixtureRule.DoubleBoundary.TRANSPORT
                : FixtureRule.DoubleBoundary.NODE;
        return FixtureRule.Behavior.protocolResponse(mock.rawBody(), mock.statusCode(),
                mock.responseHeaders(), boundary);
    }

    private static FixtureBundle.Assertion mapAssertion(String scope, String nodeId,
                                                         GatewayGraphTestAssertion assertion) {
        return switch (assertion.mode()) {
            case OUTPUT_EQUALS -> new FixtureBundle.Assertion(
                    scope, nodeId, "", "EQUALS", assertion.expectedValue(), assertion.numericTolerance());
            case OUTPUT_MATCHES_SCHEMA -> new FixtureBundle.Assertion(
                    scope, nodeId, "", "MATCHES_SCHEMA", assertion.expectedValue(), null);
            case PATH_EQUALS -> new FixtureBundle.Assertion(
                    scope, nodeId, assertion.path(), "EQUALS", assertion.expectedValue(),
                    assertion.numericTolerance());
            case PATH_EXISTS -> new FixtureBundle.Assertion(
                    scope, nodeId, assertion.path(), "EXISTS", null, null);
            case PATH_ABSENT -> new FixtureBundle.Assertion(
                    scope, nodeId, assertion.path(), "ABSENT", null, null);
        };
    }
}
