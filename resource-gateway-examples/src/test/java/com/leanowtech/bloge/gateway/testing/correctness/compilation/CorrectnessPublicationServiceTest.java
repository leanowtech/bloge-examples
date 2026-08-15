package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationCompleted;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrectnessPublicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private FrozenCompilationInput source;
    private CompiledCorrectnessPlan plan;
    private CorrectnessCompilationService compilation;
    private InMemoryPublicationRepository publications;
    private InMemoryRegistry registry;
    private CorrectnessPublicationService service;

    @BeforeEach
    void setUp() {
        source = new CorrectnessCompilerTest().input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        CorrectnessCompiler compiler = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        plan = compiler.compile(source);
        compilation = mock(CorrectnessCompilationService.class);
        when(compilation.compilePlan(eq(source.coordinate()), any()))
                .thenReturn(plan);
        publications = new InMemoryPublicationRepository();
        registry = new InMemoryRegistry(mapper);
        service = new CorrectnessPublicationService(
                compilation, publications, registry, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesOnceAndReverifiesOnIdempotentReplay() {
        var first = service.publish(source.coordinate(), "publish-loan-v1", identity());
        var replay = service.publish(source.coordinate(), "publish-loan-v1", identity());

        assertThat(first.publication()).isEqualTo(replay.publication());
        assertThat(first.attempt().attempt().stage()).isEqualTo(AttemptStage.COMMITTED);
        assertThat(registry.fixtureRegisterCount).isEqualTo(1);
        assertThat(registry.suiteRegisterCount).isEqualTo(1);
        assertThat(registry.fixtureFindCount).isEqualTo(2);
        assertThat(registry.suiteFindCount).isEqualTo(2);
        assertThat(publications.events).hasSize(1);
        assertThat(publications.attemptHistory(
                        source.definition().scope(), first.attempt().attempt().attemptId()))
                .extracting(value -> value.attempt().stage())
                .containsExactly(
                        AttemptStage.PREPARING, AttemptStage.COMPILED,
                        AttemptStage.REGISTERING, AttemptStage.REGISTERING,
                        AttemptStage.REGISTERING, AttemptStage.COMMITTED);
    }

    @Test
    void resumesAfterSuiteFailureWithoutRegisteringVerifiedFixtureAgain() {
        registry.failSuiteOnce = true;

        assertThatThrownBy(() -> service.publish(
                source.coordinate(), "recoverable-publish", identity()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("suite temporarily unavailable");
        StoredCorrectnessPublicationAttempt failed = publications.onlyAttempt();
        assertThat(failed.attempt().stage()).isEqualTo(AttemptStage.FAILED);
        assertThat(failed.attempt().verifiedAssets()).extracting(ExactAssetRef::kind)
                .containsExactly("FIXTURE_BUNDLE");

        var result = service.publish(
                source.coordinate(), "recoverable-publish", identity());

        assertThat(result.attempt().attempt().stage()).isEqualTo(AttemptStage.COMMITTED);
        assertThat(registry.fixtureRegisterCount).isEqualTo(1);
        assertThat(registry.suiteRegisterCount).isEqualTo(2);
        assertThat(registry.fixtureFindCount).isEqualTo(2);
    }

    @Test
    void readAfterWriteMismatchFailsClosedAndNeverCreatesManifest() {
        registry.corruptFixtureRead = true;

        assertThatThrownBy(() -> service.publish(
                source.coordinate(), "corrupt-registry", identity()))
                .isInstanceOfSatisfying(CorrectnessPublicationException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.PUBLICATION_FIXTURE_VERIFY_FAILED");
                    assertThat(failure.retryable()).isTrue();
                });

        assertThat(publications.onlyAttempt().attempt().stage()).isEqualTo(AttemptStage.FAILED);
        assertThat(publications.manifests).isEmpty();
        assertThat(publications.events).isEmpty();
    }

    @Test
    void blockedCompilationPersistsPayloadFreeFailureWithoutRegistryWrites() throws Exception {
        FrozenCompilationInput unsupported = new CorrectnessCompilerTest().input(
                new InlineValue(Map.of("decision", "APPROVE")), false);
        CorrectnessCompiler compiler = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        CompiledCorrectnessPlan blocked = compiler.compile(unsupported);
        when(compilation.compilePlan(eq(unsupported.coordinate()), any()))
                .thenReturn(blocked);

        assertThatThrownBy(() -> service.publish(
                unsupported.coordinate(), "blocked-publish", identity()))
                .isInstanceOfSatisfying(CorrectnessPublicationException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.CORRECTNESS.COMPILATION_BLOCKED"));

        StoredCorrectnessPublicationAttempt failed = publications.onlyAttempt();
        assertThat(failed.attempt().stage()).isEqualTo(AttemptStage.FAILED);
        assertThat(failed.compilationReport().publishable()).isFalse();
        assertThat(mapper.writeValueAsString(failed))
                .doesNotContain("customer-account-secret-8848");
        assertThat(registry.fixtureRegisterCount).isZero();
        assertThat(registry.suiteRegisterCount).isZero();
    }

    @Test
    void idempotencyKeyCannotBeReusedForAnotherCoordinate() {
        service.publish(source.coordinate(), "same-key", identity());
        CompilationCoordinate different = new CompilationCoordinate(
                source.coordinate().definitionRef(), source.coordinate().inventoryRef(),
                source.coordinate().scenarioDraftSetRef(), source.coordinate().oracleRefs(),
                source.coordinate().assertionSetRefs(), source.coordinate().fixtureAssetRefs(),
                new ExactTargetRef(TargetKind.GRAPH, "other-graph", 1, fp('d')));

        assertThatThrownBy(() -> service.publish(different, "same-key", identity()))
                .isInstanceOfSatisfying(CorrectnessPublicationException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.CORRECTNESS.PUBLICATION_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void missingAttemptHistoryFailsClosedInsteadOfLookingEmpty() {
        assertThatThrownBy(() -> service.history("missing-attempt", identity()))
                .isInstanceOfSatisfying(CorrectnessPublicationException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(404);
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.PUBLICATION_ATTEMPT_NOT_FOUND");
                });
    }

    private IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "loan", "test", "sg", "USER", "publisher", "",
                CorrectnessCompilationService.PURPOSE, "corr-1", Set.of(),
                "CONFIDENTIAL", "");
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private static final class InMemoryPublicationRepository
            implements CorrectnessPublicationRepository {

        private final Map<String, StoredCorrectnessPublicationAttempt> attempts = new HashMap<>();
        private final Map<String, String> attemptIdsByKey = new HashMap<>();
        private final Map<String, List<StoredCorrectnessPublicationAttempt>> history = new HashMap<>();
        private final Map<String, StoredCorrectnessPublication> manifests = new HashMap<>();
        private final List<CorrectnessPublicationCompleted> events = new ArrayList<>();

        @Override
        public Optional<StoredCorrectnessPublication> findPublication(
                EnterpriseScope scope, String publicationId) {
            return Optional.ofNullable(manifests.get(key(scope, publicationId)));
        }

        @Override
        public Optional<StoredCorrectnessPublicationAttempt> findAttempt(
                EnterpriseScope scope, String attemptId) {
            return Optional.ofNullable(attempts.get(key(scope, attemptId)));
        }

        @Override
        public Optional<StoredCorrectnessPublicationAttempt>
                findAttemptByIdempotencyFingerprint(
                        EnterpriseScope scope, String idempotencyKeyFingerprint) {
            String attemptId = attemptIdsByKey.get(key(scope, idempotencyKeyFingerprint));
            return attemptId == null ? Optional.empty() : findAttempt(scope, attemptId);
        }

        @Override
        public List<StoredCorrectnessPublicationAttempt> attemptHistory(
                EnterpriseScope scope, String attemptId) {
            return List.copyOf(history.getOrDefault(key(scope, attemptId), List.of()));
        }

        @Override
        public Optional<StoredCorrectnessPublicationAttempt> saveAttemptIfVersion(
                EnterpriseScope scope,
                long expectedStateVersion,
                StoredCorrectnessPublicationAttempt candidate
        ) {
            String key = key(scope, candidate.attempt().attemptId());
            StoredCorrectnessPublicationAttempt current = attempts.get(key);
            long actual = current == null ? 0 : current.attempt().stateVersion();
            String idempotencyKey = key(
                    scope, candidate.attempt().idempotencyKeyFingerprint());
            String otherAttempt = attemptIdsByKey.get(idempotencyKey);
            if (actual != expectedStateVersion
                    || (otherAttempt != null
                    && !otherAttempt.equals(candidate.attempt().attemptId()))) {
                return Optional.empty();
            }
            attempts.put(key, candidate);
            attemptIdsByKey.put(idempotencyKey, candidate.attempt().attemptId());
            history.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
            return Optional.of(candidate);
        }

        @Override
        public Optional<CommitResult> commitIfVersion(
                EnterpriseScope scope,
                long expectedStateVersion,
                StoredCorrectnessPublicationAttempt committedAttempt,
                StoredCorrectnessPublication publication,
                CorrectnessPublicationCompleted event
        ) {
            Optional<StoredCorrectnessPublicationAttempt> saved = saveAttemptIfVersion(
                    scope, expectedStateVersion, committedAttempt);
            if (saved.isEmpty()) return Optional.empty();
            manifests.put(key(scope, publication.publication().publicationId()), publication);
            events.add(event);
            return Optional.of(new CommitResult(committedAttempt, publication));
        }

        StoredCorrectnessPublicationAttempt onlyAttempt() {
            assertThat(attempts).hasSize(1);
            return attempts.values().iterator().next();
        }

        private static String key(EnterpriseScope scope, String id) {
            return scope.tenantId() + '|' + scope.organizationId() + '|' + scope.projectId()
                    + '|' + scope.environment() + '|' + scope.region() + '|' + id;
        }
    }

    private static final class InMemoryRegistry implements CorrectnessTestingRegistryGateway {

        private final ObjectMapper mapper;
        private final Map<String, FixtureBundleRegistrationRequest> fixtures = new HashMap<>();
        private final Map<String, TestSuiteRegistrationRequest> suites = new HashMap<>();
        private int fixtureRegisterCount;
        private int fixtureFindCount;
        private int suiteRegisterCount;
        private int suiteFindCount;
        private boolean failSuiteOnce;
        private boolean corruptFixtureRead;

        private InMemoryRegistry(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public StoredFixtureBundle registerFixture(
                String fixtureBundleId,
                FixtureBundleRegistrationRequest request,
                IntegrationRequestContext identity
        ) {
            fixtureRegisterCount++;
            fixtures.put(fixtureBundleId, request);
            return fixture(fixtureBundleId, identity, false);
        }

        @Override
        public StoredFixtureBundle findFixture(
                String fixtureBundleId,
                long revision,
                IntegrationRequestContext identity
        ) {
            fixtureFindCount++;
            return fixture(fixtureBundleId, identity, corruptFixtureRead);
        }

        @Override
        public StoredTestSuite registerSuite(
                String suiteId,
                TestSuiteRegistrationRequest request,
                IntegrationRequestContext identity
        ) {
            suiteRegisterCount++;
            if (failSuiteOnce) {
                failSuiteOnce = false;
                throw new IllegalStateException("suite temporarily unavailable");
            }
            suites.put(suiteId, request);
            return suite(suiteId, identity);
        }

        @Override
        public StoredTestSuite findSuite(
                String suiteId,
                long revision,
                IntegrationRequestContext identity
        ) {
            suiteFindCount++;
            return suite(suiteId, identity);
        }

        private StoredFixtureBundle fixture(
                String id,
                IntegrationRequestContext identity,
                boolean corrupt
        ) {
            FixtureBundle bundle = fixtures.get(id).fixtureBundle();
            String fingerprint = ProtocolFingerprint.of(mapper, bundle);
            return new StoredFixtureBundle(
                    "", identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), id, bundle.revision(),
                    corrupt ? "sha256:" + "f".repeat(64) : fingerprint,
                    bundle, NOW, identity.actorId());
        }

        private StoredTestSuite suite(String id, IntegrationRequestContext identity) {
            TestSuiteProtocol suite = suites.get(id).testSuite();
            return new StoredTestSuite(
                    "", identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), id, suite.revision(),
                    ProtocolFingerprint.of(mapper, suite), suite, NOW, identity.actorId());
        }
    }
}
