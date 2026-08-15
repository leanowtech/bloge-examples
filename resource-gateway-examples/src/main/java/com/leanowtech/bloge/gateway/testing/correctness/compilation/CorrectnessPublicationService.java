package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.Failure;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationCompleted;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Recoverable, content-addressed publication Saga for correctness authoring assets. */
public final class CorrectnessPublicationService {

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;

    private final CorrectnessCompilationService compilation;
    private final CorrectnessPublicationRepository publications;
    private final CorrectnessTestingRegistryGateway registry;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CorrectnessPublicationService(
            CorrectnessCompilationService compilation,
            CorrectnessPublicationRepository publications,
            CorrectnessTestingRegistryGateway registry,
            ObjectMapper mapper
    ) {
        this(compilation, publications, registry, mapper, Clock.systemUTC());
    }

    CorrectnessPublicationService(
            CorrectnessCompilationService compilation,
            CorrectnessPublicationRepository publications,
            CorrectnessTestingRegistryGateway registry,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.compilation = Objects.requireNonNull(compilation, "compilation");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Publishes or resumes one idempotent exact compilation coordinate. */
    public CorrectnessPublicationRepository.CommitResult publish(
            CompilationCoordinate coordinate,
            String idempotencyKey,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        Objects.requireNonNull(coordinate, "coordinate");
        String keyFingerprint = idempotencyFingerprint(
                scope(identity), identity.actorId(), idempotencyKey);
        StoredCorrectnessPublicationAttempt state = publications
                .findAttemptByIdempotencyFingerprint(scope(identity), keyFingerprint)
                .orElse(null);
        if (state != null && !state.attempt().coordinate().equals(coordinate)) {
            throw failure(
                    409, "RG.CORRECTNESS.PUBLICATION_IDEMPOTENCY_CONFLICT",
                    "Idempotency key identifies a different exact compilation coordinate", false);
        }
        if (state == null) {
            state = persist(0, preparing(coordinate, keyFingerprint, identity), identity);
        } else if (state.attempt().stage() == AttemptStage.FAILED) {
            state = persist(
                    state.attempt().stateVersion(), retrying(state, identity), identity);
        }

        CompiledCorrectnessPlan plan;
        try {
            plan = compilation.compilePlan(coordinate, identity);
        } catch (RuntimeException compileFailure) {
            failBestEffort(state, failureCode(compileFailure), identity);
            throw compileFailure;
        }
        requireStableCompilation(state, plan.report());
        if (!plan.report().publishable()) {
            state = persistFailure(
                    state, plan.report(), "RG.CORRECTNESS.COMPILATION_BLOCKED", false, identity);
            throw failure(
                    422, "RG.CORRECTNESS.COMPILATION_BLOCKED",
                    "Correctness publication is blocked by deterministic compilation", false);
        }
        if (state.attempt().stage() == AttemptStage.COMMITTED) {
            return verifyCompleted(state, plan, identity);
        }
        if (state.attempt().stage() == AttemptStage.PREPARING) {
            state = persist(
                    state.attempt().stateVersion(),
                    advance(state, AttemptStage.COMPILED, plan.report(),
                            state.attempt().verifiedAssets(), Failure.none(), identity),
                    identity);
        }
        if (state.attempt().stage() == AttemptStage.COMPILED) {
            state = persist(
                    state.attempt().stateVersion(),
                    advance(state, AttemptStage.REGISTERING, plan.report(),
                            state.attempt().verifiedAssets(), Failure.none(), identity),
                    identity);
        }

        try {
            state = registerFixtures(state, plan, identity);
            state = registerSuite(state, plan, identity);
            return commit(state, plan, identity);
        } catch (RuntimeException registryFailure) {
            failBestEffort(state, failureCode(registryFailure), identity);
            throw registryFailure;
        }
    }

    public StoredCorrectnessPublication findPublication(
            String publicationId,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        return publications.findPublication(scope(identity), normalized(publicationId))
                .orElseThrow(() -> failure(
                        404, "RG.CORRECTNESS.PUBLICATION_NOT_FOUND",
                        "Correctness Publication was not found in the authorized scope", false));
    }

    public StoredCorrectnessPublicationAttempt findAttempt(
            String attemptId,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        return publications.findAttempt(scope(identity), normalized(attemptId))
                .orElseThrow(() -> failure(
                        404, "RG.CORRECTNESS.PUBLICATION_ATTEMPT_NOT_FOUND",
                        "Correctness Publication Attempt was not found in the authorized scope", false));
    }

    public List<StoredCorrectnessPublicationAttempt> history(
            String attemptId,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        return publications.attemptHistory(scope(identity), normalized(attemptId));
    }

    private StoredCorrectnessPublicationAttempt registerFixtures(
            StoredCorrectnessPublicationAttempt state,
            CompiledCorrectnessPlan plan,
            IntegrationRequestContext identity
    ) {
        for (FixtureBundleRegistrationRequest request : plan.fixtureRegistrations()) {
            try {
                FixtureBundle expected = request.fixtureBundle();
                ExactAssetRef expectedRef = fixtureRef(expected);
                if (!state.attempt().verifiedAssets().contains(expectedRef)) {
                    registry.registerFixture(expected.fixtureBundleId(), request, identity);
                }
                StoredFixtureBundle actual = registry.findFixture(
                        expected.fixtureBundleId(), expected.revision(), identity);
                requireFixture(actual, expected, expectedRef, identity);
                if (!state.attempt().verifiedAssets().contains(expectedRef)) {
                    List<ExactAssetRef> verified = appendVerified(
                            state.attempt().verifiedAssets(), expectedRef);
                    state = persist(
                            state.attempt().stateVersion(),
                            advance(state, AttemptStage.REGISTERING, plan.report(),
                                    verified, Failure.none(), identity), identity);
                }
            } catch (RuntimeException failure) {
                failBestEffort(state, failureCode(failure), identity);
                throw failure;
            }
        }
        return state;
    }

    private StoredCorrectnessPublicationAttempt registerSuite(
            StoredCorrectnessPublicationAttempt state,
            CompiledCorrectnessPlan plan,
            IntegrationRequestContext identity
    ) {
        TestSuiteProtocol expected = plan.suiteRegistration().testSuite();
        ExactAssetRef expectedRef = suiteRef(expected);
        if (!state.attempt().verifiedAssets().contains(expectedRef)) {
            registry.registerSuite(expected.suiteId(), plan.suiteRegistration(), identity);
        }
        StoredTestSuite actual = registry.findSuite(
                expected.suiteId(), expected.revision(), identity);
        requireSuite(actual, expected, expectedRef, identity);
        if (!state.attempt().verifiedAssets().contains(expectedRef)) {
            state = persist(
                    state.attempt().stateVersion(),
                    advance(state, AttemptStage.REGISTERING, plan.report(),
                            appendVerified(state.attempt().verifiedAssets(), expectedRef),
                            Failure.none(), identity), identity);
        }
        return state;
    }

    private CorrectnessPublicationRepository.CommitResult commit(
            StoredCorrectnessPublicationAttempt state,
            CompiledCorrectnessPlan plan,
            IntegrationRequestContext identity
    ) {
        List<ExactAssetRef> fixtureRefs = plan.fixtureRegistrations().stream()
                .map(FixtureBundleRegistrationRequest::fixtureBundle)
                .map(this::fixtureRef).toList();
        ExactAssetRef suiteRef = suiteRef(plan.suiteRegistration().testSuite());
        List<ExactAssetRef> expectedAssets = new ArrayList<>(fixtureRefs);
        expectedAssets.add(suiteRef);
        if (!state.attempt().verifiedAssets().containsAll(expectedAssets)
                || state.attempt().verifiedAssets().size() != expectedAssets.size()) {
            throw failure(
                    503, "RG.CORRECTNESS.PUBLICATION_VERIFICATION_INCOMPLETE",
                    "Publication cannot commit before every compiled asset is verified", true);
        }
        Instant now = clock.instant();
        CorrectnessPublication value = new CorrectnessPublication(
                "", publicationId(plan.report()), scope(identity),
                plan.report().coordinate().target(), plan.report().coordinate().definitionRef(),
                plan.report().coordinate().inventoryRef(),
                plan.report().coordinate().scenarioDraftSetRef(),
                plan.report().coordinate().oracleRefs(),
                plan.report().coordinate().assertionSetRefs(),
                plan.report().coordinate().fixtureAssetRefs(), fixtureRefs, suiteRef,
                CorrectnessCompiler.COMPILER_VERSION,
                plan.report().compilationFingerprint(),
                metadata(state.attempt().metadata(), actor(identity), now));
        StoredCorrectnessPublication publication =
                StoredCorrectnessPublication.verified(mapper, value);
        StoredCorrectnessPublicationAttempt committed = advance(
                state, AttemptStage.COMMITTED, plan.report(), expectedAssets,
                Failure.none(), identity);
        CorrectnessPublicationCompleted event = completionEvent(
                publication, identity.actorId(), now);
        CorrectnessPublicationRepository.CommitResult result = publications.commitIfVersion(
                        scope(identity), state.attempt().stateVersion(), committed,
                        publication, event)
                .orElseThrow(() -> concurrent(identity));
        StoredCorrectnessPublication reread = publications.findPublication(
                        scope(identity), value.publicationId())
                .orElseThrow(() -> failure(
                        503, "RG.CORRECTNESS.PUBLICATION_MANIFEST_VERIFY_FAILED",
                        "Committed Publication manifest could not be read back", true));
        if (!publication.equals(reread)) {
            throw failure(
                    503, "RG.CORRECTNESS.PUBLICATION_MANIFEST_VERIFY_FAILED",
                    "Committed Publication manifest failed independent verification", true);
        }
        return result;
    }

    private CorrectnessPublicationRepository.CommitResult verifyCompleted(
            StoredCorrectnessPublicationAttempt state,
            CompiledCorrectnessPlan plan,
            IntegrationRequestContext identity
    ) {
        for (FixtureBundleRegistrationRequest request : plan.fixtureRegistrations()) {
            FixtureBundle expected = request.fixtureBundle();
            requireFixture(
                    registry.findFixture(expected.fixtureBundleId(), expected.revision(), identity),
                    expected, fixtureRef(expected), identity);
        }
        TestSuiteProtocol suite = plan.suiteRegistration().testSuite();
        requireSuite(
                registry.findSuite(suite.suiteId(), suite.revision(), identity),
                suite, suiteRef(suite), identity);
        String publicationId = publicationId(plan.report());
        StoredCorrectnessPublication publication = publications.findPublication(
                        scope(identity), publicationId)
                .orElseThrow(() -> failure(
                        503, "RG.CORRECTNESS.PUBLICATION_MANIFEST_VERIFY_FAILED",
                        "Committed Publication manifest is unavailable", true));
        if (!publication.publication().compilationFingerprint()
                .equals(plan.report().compilationFingerprint())) {
            throw failure(
                    503, "RG.CORRECTNESS.PUBLICATION_MANIFEST_VERIFY_FAILED",
                    "Committed Publication no longer matches deterministic compilation", false);
        }
        return new CorrectnessPublicationRepository.CommitResult(state, publication);
    }

    private void requireFixture(
            StoredFixtureBundle actual,
            FixtureBundle expected,
            ExactAssetRef expectedRef,
            IntegrationRequestContext identity
    ) {
        String contentFingerprint = actual == null || actual.bundle() == null ? ""
                : ProtocolFingerprint.ofBounded(mapper, actual.bundle(), MAX_PROTOCOL_BYTES);
        if (actual == null || !actual.fixtureBundleId().equals(expected.fixtureBundleId())
                || actual.revision() != expected.revision()
                || !actual.fingerprint().equals(expectedRef.fingerprint())
                || !contentFingerprint.equals(expectedRef.fingerprint())
                || !actual.bundle().equals(expected)) {
            throw failure(
                    503, "RG.CORRECTNESS.PUBLICATION_FIXTURE_VERIFY_FAILED",
                    "Registered Fixture Bundle failed independent content verification", true);
        }
    }

    private void requireSuite(
            StoredTestSuite actual,
            TestSuiteProtocol expected,
            ExactAssetRef expectedRef,
            IntegrationRequestContext identity
    ) {
        String contentFingerprint = actual == null || actual.suite() == null ? ""
                : ProtocolFingerprint.ofBounded(mapper, actual.suite(), MAX_PROTOCOL_BYTES);
        if (actual == null || !actual.suiteId().equals(expected.suiteId())
                || actual.revision() != expected.revision()
                || !actual.fingerprint().equals(expectedRef.fingerprint())
                || !contentFingerprint.equals(expectedRef.fingerprint())
                || !actual.suite().equals(expected)) {
            throw failure(
                    503, "RG.CORRECTNESS.PUBLICATION_SUITE_VERIFY_FAILED",
                    "Registered Test Suite failed independent content verification", true);
        }
    }

    private StoredCorrectnessPublicationAttempt preparing(
            CompilationCoordinate coordinate,
            String keyFingerprint,
            IntegrationRequestContext identity
    ) {
        Instant now = clock.instant();
        PrincipalRef actor = actor(identity);
        PublicationAttempt attempt = new PublicationAttempt(
                "", attemptId(keyFingerprint), 1, keyFingerprint, coordinate,
                AttemptStage.PREPARING, List.of(), Failure.none(),
                new AuditMetadata(now, now, actor, actor));
        return new StoredCorrectnessPublicationAttempt(
                "", scope(identity), attempt, null);
    }

    private StoredCorrectnessPublicationAttempt retrying(
            StoredCorrectnessPublicationAttempt state,
            IntegrationRequestContext identity
    ) {
        return advance(
                state, AttemptStage.PREPARING, null,
                state.attempt().verifiedAssets(), Failure.none(), identity);
    }

    private StoredCorrectnessPublicationAttempt advance(
            StoredCorrectnessPublicationAttempt state,
            AttemptStage stage,
            CorrectnessCompilationReport report,
            List<ExactAssetRef> verifiedAssets,
            Failure failure,
            IntegrationRequestContext identity
    ) {
        PublicationAttempt current = state.attempt();
        PublicationAttempt next = new PublicationAttempt(
                "", current.attemptId(), current.stateVersion() + 1,
                current.idempotencyKeyFingerprint(), current.coordinate(), stage,
                verifiedAssets, failure,
                metadata(current.metadata(), actor(identity), clock.instant()));
        return new StoredCorrectnessPublicationAttempt(
                "", state.scope(), next, report);
    }

    private StoredCorrectnessPublicationAttempt persistFailure(
            StoredCorrectnessPublicationAttempt state,
            CorrectnessCompilationReport report,
            String code,
            boolean retryable,
            IntegrationRequestContext identity
    ) {
        return persist(
                state.attempt().stateVersion(),
                advance(state, AttemptStage.FAILED, report,
                        state.attempt().verifiedAssets(), new Failure(code, retryable), identity),
                identity);
    }

    private void failBestEffort(
            StoredCorrectnessPublicationAttempt state,
            FailureCode failure,
            IntegrationRequestContext identity
    ) {
        if (state == null || state.attempt().stage() == AttemptStage.COMMITTED
                || state.attempt().stage() == AttemptStage.FAILED) {
            return;
        }
        try {
            persistFailure(
                    state, state.compilationReport(),
                    failure.code(), failure.retryable(), identity);
        } catch (RuntimeException ignored) {
            // The original failure remains authoritative; retry reloads durable state.
        }
    }

    private StoredCorrectnessPublicationAttempt persist(
            long expectedVersion,
            StoredCorrectnessPublicationAttempt candidate,
            IntegrationRequestContext identity
    ) {
        return publications.saveAttemptIfVersion(
                        scope(identity), expectedVersion, candidate)
                .orElseThrow(() -> concurrent(identity));
    }

    private void requireStableCompilation(
            StoredCorrectnessPublicationAttempt state,
            CorrectnessCompilationReport report
    ) {
        if (state.compilationReport() != null
                && !state.compilationReport().compilationFingerprint()
                .equals(report.compilationFingerprint())) {
            throw failure(
                    503, "RG.CORRECTNESS.COMPILATION_NONDETERMINISTIC",
                    "The same exact coordinate produced a different compilation fingerprint", false);
        }
        List<ExactAssetRef> compiledRefs = report.compiledAssets().stream()
                .map(CorrectnessCompilationReport.CompiledAssetSummary::assetRef).toList();
        if (!compiledRefs.containsAll(state.attempt().verifiedAssets())) {
            throw failure(
                    503, "RG.CORRECTNESS.PUBLICATION_VERIFIED_ASSET_DRIFT",
                    "Durable verified assets are not part of the current deterministic output", false);
        }
    }

    private CorrectnessPublicationCompleted completionEvent(
            StoredCorrectnessPublication publication,
            String actorId,
            Instant occurredAt
    ) {
        CorrectnessPublication value = publication.publication();
        ExactAssetRef publicationRef = new ExactAssetRef(
                "CORRECTNESS_PUBLICATION", value.publicationId(), 1,
                publication.publicationFingerprint());
        return new CorrectnessPublicationCompleted(
                "", eventId(publication.publicationFingerprint()), value.scope(), publicationRef,
                value.target(), value.definitionRef(), value.inventoryRef(),
                value.scenarioDraftSetRef(), value.compiledFixtureBundleRefs(),
                value.compiledTestSuiteRef(), value.compilationFingerprint(), actorId, occurredAt);
    }

    private ExactAssetRef fixtureRef(FixtureBundle bundle) {
        return new ExactAssetRef(
                "FIXTURE_BUNDLE", bundle.fixtureBundleId(), bundle.revision(),
                ProtocolFingerprint.ofBounded(mapper, bundle, MAX_PROTOCOL_BYTES));
    }

    private ExactAssetRef suiteRef(TestSuiteProtocol suite) {
        return new ExactAssetRef(
                "TEST_SUITE", suite.suiteId(), suite.revision(),
                ProtocolFingerprint.ofBounded(mapper, suite, MAX_PROTOCOL_BYTES));
    }

    private String idempotencyFingerprint(
            EnterpriseScope scope,
            String actorId,
            String idempotencyKey
    ) {
        String key = normalized(idempotencyKey);
        if (key.isEmpty() || key.length() > 512) {
            throw failure(
                    400, "RG.CORRECTNESS.PUBLICATION_IDEMPOTENCY_KEY_INVALID",
                    "A bounded Idempotency-Key is required", false);
        }
        return ProtocolFingerprint.ofBounded(
                mapper, Map.of(
                        "command", "CORRECTNESS_PUBLICATION",
                        "scope", scope,
                        "actorId", actorId,
                        "idempotencyKey", key), MAX_PROTOCOL_BYTES);
    }

    private String publicationId(CorrectnessCompilationReport report) {
        return contentAddressedId(
                "correctness-publication-" + report.coordinate().scenarioDraftSetRef().id(),
                report.compilationFingerprint());
    }

    private static String attemptId(String keyFingerprint) {
        return contentAddressedId("correctness-publication-attempt", keyFingerprint);
    }

    private static String eventId(String publicationFingerprint) {
        return contentAddressedId("correctness-publication-completed", publicationFingerprint);
    }

    private static String contentAddressedId(String prefix, String fingerprint) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        String digest = fingerprint.startsWith("sha256:")
                ? fingerprint.substring("sha256:".length()) : fingerprint;
        int prefixLimit = Math.max(0, 255 - digest.length() - 1);
        return normalizedPrefix.substring(0, Math.min(prefixLimit, normalizedPrefix.length()))
                + '-' + digest;
    }

    private static List<ExactAssetRef> appendVerified(
            List<ExactAssetRef> values,
            ExactAssetRef next
    ) {
        List<ExactAssetRef> result = new ArrayList<>(values);
        result.add(next);
        return result.stream().distinct()
                .sorted(Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision)).toList();
    }

    private static AuditMetadata metadata(
            AuditMetadata current,
            PrincipalRef actor,
            Instant now
    ) {
        return new AuditMetadata(
                current.createdAt(), now, current.createdBy(), actor);
    }

    private static PrincipalRef actor(IntegrationRequestContext identity) {
        PrincipalKind kind;
        try {
            kind = PrincipalKind.valueOf(identity.actorType());
        } catch (IllegalArgumentException unsupported) {
            kind = PrincipalKind.SERVICE;
        }
        return new PrincipalRef(identity.actorId(), kind, "");
    }

    private static FailureCode failureCode(RuntimeException failure) {
        if (failure instanceof CorrectnessPublicationException known) {
            return new FailureCode(known.code(), known.retryable());
        }
        if (failure instanceof CorrectnessCompilationException known) {
            return new FailureCode(known.code(), known.retryable());
        }
        if (failure instanceof IntegrationProblemException known) {
            return new FailureCode(known.problem().code(), known.problem().retryable());
        }
        return new FailureCode("RG.CORRECTNESS.PUBLICATION_STAGE_FAILED", true);
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        if (identity == null) {
            throw failure(
                    401, "RG.CORRECTNESS.PUBLICATION_AUTH_REQUIRED",
                    "Verified publication identity is required", false);
        }
        identity.requireComplete();
        if (!CorrectnessCompilationService.PURPOSE.equals(identity.purpose())) {
            throw failure(
                    403, "RG.CORRECTNESS.PUBLICATION_PURPOSE_FORBIDDEN",
                    "Correctness publication requires TEST_SCENARIO_PUBLISH", false);
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static CorrectnessPublicationException concurrent(
            IntegrationRequestContext identity
    ) {
        return failure(
                409, "RG.CORRECTNESS.PUBLICATION_CONCURRENT_UPDATE",
                "Another publisher advanced the same Publication Attempt", true);
    }

    private static CorrectnessPublicationException failure(
            int status,
            String code,
            String message,
            boolean retryable
    ) {
        return new CorrectnessPublicationException(status, code, message, retryable);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record FailureCode(String code, boolean retryable) {
    }
}
