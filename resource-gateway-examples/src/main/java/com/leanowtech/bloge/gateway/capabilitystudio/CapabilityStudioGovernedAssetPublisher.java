package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundleIntegrity;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Publishes one already compiled Capability Studio asset set through the governed Scenario
 * registry boundary.
 *
 * <p>The registration response is intentionally ignored. A successful publication is established
 * only by an independent read of every registered revision and by recomputing the canonical
 * content fingerprint from that read. The returned receipt contains no fixture or suite payload.
 */
public final class CapabilityStudioGovernedAssetPublisher {

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final String ERROR_PREFIX = "RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper objectMapper;
    private final ScenarioGovernedRegistryGateway registry;

    public CapabilityStudioGovernedAssetPublisher(
            ObjectMapper objectMapper,
            ScenarioGovernedRegistryGateway registry) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Registers and independently verifies all immutable assets in one compiled plan.
     *
     * @param compilation exact output of Capability Studio governed compilation
     * @param identity complete enterprise request identity
     * @return payload-free deterministic receipt
     */
    public Receipt publish(
            CapabilityStudioGovernedCompilation compilation,
            IntegrationRequestContext identity) {
        require(compilation != null, "COMPILATION_MISSING", "/compilation");
        requireIdentity(identity);

        ScenarioGovernedCompilationPlan plan = compilation.plan();
        require(plan != null, "PLAN_MISSING", "/compilation/plan");
        require(plan.compiled(), "PLAN_NOT_COMPILED", "/compilation/plan/compiled");
        require(plan.diagnostics().stream().noneMatch(diagnostic -> diagnostic == null
                        || diagnostic.error()),
                "ERROR_DIAGNOSTICS", "/compilation/plan/diagnostics");
        require(!plan.fixtures().isEmpty(), "FIXTURES_MISSING", "/compilation/plan/fixtures");
        require(plan.suite() != null && plan.suite().testSuite() != null,
                "SUITE_MISSING", "/compilation/plan/suite");

        String compilationFingerprint = requireCompilationFingerprint(compilation, plan);
        String sourceMapFingerprint = boundedFingerprint(
                compilation.sourceMap(), "SOURCE_MAP_FINGERPRINT_FAILED", "/compilation/sourceMap");

        List<ExactRef> fixtureRefs = new ArrayList<>();
        for (int index = 0; index < plan.fixtures().size(); index++) {
            ScenarioGovernedCompilationPlan.CompiledFixture fixture = plan.fixtures().get(index);
            String path = "/compilation/plan/fixtures/" + index;
            require(fixture != null, "FIXTURE_MISSING", path);
            FixtureBundle expected = fixture.request() == null
                    ? null : fixture.request().fixtureBundle();
            require(expected != null, "FIXTURE_PAYLOAD_MISSING", path + "/request/fixtureBundle");
            require(!expected.fixtureBundleId().isBlank() && expected.revision() > 0,
                    "FIXTURE_IDENTITY_MISSING", path + "/request/fixtureBundle");
            String expectedFingerprint = boundedFingerprint(
                    expected, "FIXTURE_FINGERPRINT_FAILED", path + "/request/fixtureBundle");
            requireFingerprint(fixture.fingerprint(), "FIXTURE_COMPILED_FINGERPRINT_INVALID",
                    path + "/fingerprint");
            requireSame(fixture.fingerprint(), expectedFingerprint,
                    "FIXTURE_FINGERPRINT_MISMATCH", path + "/fingerprint");

            registerFixture(fixture, identity, path);
            StoredFixtureBundle stored = findFixture(expected, identity, path);
            verifyFixture(stored, expected, expectedFingerprint, identity, path);
            fixtureRefs.add(new ExactRef(
                    "FIXTURE_BUNDLE", stored.fixtureBundleId(), stored.revision(), stored.fingerprint()));
        }

        TestSuiteProtocol expectedSuite = plan.suite().testSuite();
        require(!expectedSuite.suiteId().isBlank() && expectedSuite.revision() > 0,
                "SUITE_IDENTITY_MISSING", "/compilation/plan/suite/testSuite");
        String expectedSuiteFingerprint = boundedFingerprint(
                expectedSuite, "SUITE_FINGERPRINT_FAILED", "/compilation/plan/suite/testSuite");
        registerSuite(expectedSuite, plan, identity);
        StoredTestSuite storedSuite = findSuite(expectedSuite, identity);
        verifySuite(storedSuite, expectedSuite, expectedSuiteFingerprint, identity);

        fixtureRefs.sort(Comparator.comparing(ExactRef::kind)
                .thenComparing(ExactRef::id)
                .thenComparingLong(ExactRef::revision)
                .thenComparing(ExactRef::fingerprint));
        ExactRef suiteRef = new ExactRef(
                "TEST_SUITE", storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint());
        String receiptFingerprint = boundedFingerprint(
                new ReceiptMaterial(compilationFingerprint, sourceMapFingerprint, fixtureRefs, suiteRef),
                "RECEIPT_FINGERPRINT_FAILED", "/receipt");
        return new Receipt(compilationFingerprint, sourceMapFingerprint, fixtureRefs, suiteRef,
                receiptFingerprint);
    }

    private String requireCompilationFingerprint(
            CapabilityStudioGovernedCompilation compilation,
            ScenarioGovernedCompilationPlan plan) {
        requireFingerprint(compilation.semanticFingerprint(), "COMPILATION_FINGERPRINT_INVALID",
                "/compilation/semanticFingerprint");
        String canonical = boundedFingerprint(
                new CompilationMaterial(plan, compilation.sourceMap()),
                "COMPILATION_FINGERPRINT_FAILED", "/compilation");
        requireSame(compilation.semanticFingerprint(), canonical,
                "COMPILATION_FINGERPRINT_MISMATCH", "/compilation/semanticFingerprint");
        return compilation.semanticFingerprint();
    }

    private void registerFixture(
            ScenarioGovernedCompilationPlan.CompiledFixture fixture,
            IntegrationRequestContext identity,
            String path) {
        try {
            registry.registerFixture(
                    fixture.request().fixtureBundle().fixtureBundleId(), fixture.request(), identity);
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("FIXTURE_REGISTER_FAILED", path);
        }
    }

    private StoredFixtureBundle findFixture(
            FixtureBundle expected,
            IntegrationRequestContext identity,
            String path) {
        try {
            return registry.findFixture(expected.fixtureBundleId(), expected.revision(), identity);
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("FIXTURE_READ_FAILED", path);
        }
    }

    private void registerSuite(
            TestSuiteProtocol expected,
            ScenarioGovernedCompilationPlan plan,
            IntegrationRequestContext identity) {
        try {
            registry.registerSuite(expected.suiteId(), plan.suite(), identity);
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("SUITE_REGISTER_FAILED", "/compilation/plan/suite");
        }
    }

    private StoredTestSuite findSuite(
            TestSuiteProtocol expected,
            IntegrationRequestContext identity) {
        try {
            return registry.findSuite(expected.suiteId(), expected.revision(), identity);
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("SUITE_READ_FAILED", "/compilation/plan/suite");
        }
    }

    private void verifyFixture(
            StoredFixtureBundle stored,
            FixtureBundle expected,
            String expectedFingerprint,
            IntegrationRequestContext identity,
            String path) {
        try {
            StoredFixtureBundle verified = StoredFixtureBundleIntegrity.verifiedSnapshot(
                    objectMapper, stored);
            requireFixtureCoordinate(verified.tenantId(), verified.organizationId(),
                    verified.projectId(), verified.environmentId(), verified.region(), identity, path);
            require(verified.fixtureBundleId().equals(expected.fixtureBundleId())
                            && verified.revision() == expected.revision(),
                    "FIXTURE_RE_READ_DRIFT", path);
            requireSame(verified.fingerprint(), expectedFingerprint,
                    "FIXTURE_RE_READ_DRIFT", path + "/fingerprint");
            requireSame(boundedFingerprint(verified.bundle(), "FIXTURE_CONTENT_FINGERPRINT_FAILED", path),
                    expectedFingerprint, "FIXTURE_RE_READ_DRIFT", path + "/bundle");
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("FIXTURE_RE_READ_DRIFT", path);
        }
    }

    private void verifySuite(
            StoredTestSuite stored,
            TestSuiteProtocol expected,
            String expectedFingerprint,
            IntegrationRequestContext identity) {
        try {
            StoredTestSuite verified = StoredTestSuiteIntegrity.verifiedSnapshot(
                    objectMapper, stored);
            requireSuiteCoordinate(verified.tenantId(), verified.organizationId(),
                    verified.projectId(), verified.environmentId(), verified.region(), identity);
            require(verified.suiteId().equals(expected.suiteId())
                            && verified.revision() == expected.revision(),
                    "SUITE_RE_READ_DRIFT", "/compilation/plan/suite");
            requireSame(verified.fingerprint(), expectedFingerprint,
                    "SUITE_RE_READ_DRIFT", "/compilation/plan/suite/fingerprint");
            requireSame(boundedFingerprint(verified.suite(), "SUITE_CONTENT_FINGERPRINT_FAILED",
                            "/compilation/plan/suite"), expectedFingerprint,
                    "SUITE_RE_READ_DRIFT", "/compilation/plan/suite/testSuite");
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("SUITE_RE_READ_DRIFT", "/compilation/plan/suite");
        }
    }

    private static void requireFixtureCoordinate(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region,
            IntegrationRequestContext identity,
            String path) {
        require(Objects.equals(tenantId, identity.tenantId())
                        && Objects.equals(organizationId, identity.organizationId())
                        && Objects.equals(projectId, identity.projectId())
                        && Objects.equals(environmentId, identity.environmentId())
                        && Objects.equals(region, identity.region()),
                "FIXTURE_RE_READ_DRIFT", path);
    }

    private static void requireSuiteCoordinate(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region,
            IntegrationRequestContext identity) {
        require(Objects.equals(tenantId, identity.tenantId())
                        && Objects.equals(organizationId, identity.organizationId())
                        && Objects.equals(projectId, identity.projectId())
                        && Objects.equals(environmentId, identity.environmentId())
                        && Objects.equals(region, identity.region()),
                "SUITE_RE_READ_DRIFT", "/compilation/plan/suite");
    }

    private String boundedFingerprint(Object value, String suffix, String path) {
        try {
            return ProtocolFingerprint.ofBounded(objectMapper, value, MAX_PROTOCOL_BYTES);
        } catch (RuntimeException failure) {
            throw failure(suffix, path);
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        require(identity != null, "CONTEXT_MISSING", "/identity");
        require(!identity.tenantId().isBlank() && !identity.organizationId().isBlank()
                        && !identity.projectId().isBlank() && !identity.environmentId().isBlank()
                        && !identity.region().isBlank() && !identity.actorId().isBlank()
                        && !identity.purpose().isBlank(),
                "CONTEXT_INCOMPLETE", "/identity");
    }

    private static void requireFingerprint(String value, String suffix, String path) {
        require(value != null && FINGERPRINT.matcher(value).matches(), suffix, path);
    }

    private static void requireSame(String actual, String expected, String suffix, String path) {
        require(Objects.equals(actual, expected), suffix, path);
    }

    private static void require(boolean condition, String suffix, String path) {
        if (!condition) {
            throw failure(suffix, path);
        }
    }

    private static CapabilityStudioGovernedCompilationException failure(
            String suffix,
            String path) {
        return new CapabilityStudioGovernedCompilationException(ERROR_PREFIX + suffix, path);
    }

    /** Payload-free immutable exact registry identity. */
    public record ExactRef(String kind, String id, long revision, String fingerprint) {
        public ExactRef {
            kind = normalized(kind);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
        }
    }

    /** Payload-free immutable receipt for one governed publication. */
    public record Receipt(
            String compilationFingerprint,
            String sourceMapFingerprint,
            List<ExactRef> fixtureRefs,
            ExactRef suiteRef,
            String receiptFingerprint) {
        public Receipt {
            compilationFingerprint = normalized(compilationFingerprint);
            sourceMapFingerprint = normalized(sourceMapFingerprint);
            fixtureRefs = fixtureRefs == null ? List.of() : List.copyOf(fixtureRefs);
            Objects.requireNonNull(suiteRef, "suiteRef");
            receiptFingerprint = normalized(receiptFingerprint);
        }
    }

    private record CompilationMaterial(
            ScenarioGovernedCompilationPlan plan,
            CapabilityStudioScenarioDatasetSourceMap sourceMap) {
    }

    private record ReceiptMaterial(
            String compilationFingerprint,
            String sourceMapFingerprint,
            List<ExactRef> fixtureRefs,
            ExactRef suiteRef) {
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
