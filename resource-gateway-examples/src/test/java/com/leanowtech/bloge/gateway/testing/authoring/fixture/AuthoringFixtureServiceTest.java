package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftRepository;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.application.InMemoryAuthoringCatalogOwnershipRepository;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.AssetKind;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SaveRequest;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SourceKind;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringFixtureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final AuthoringScope SCOPE = new AuthoringScope(
            "tenant-a", "org-a", "project-a", "test", "region-a");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper yaml = new YAMLMapper().findAndRegisterModules();
    private final InMemoryFixtureRepository fixtures = new InMemoryFixtureRepository();
    private final InMemorySecurityEvents events = new InMemorySecurityEvents();
    private AuthoringDraftService drafts;
    private AuthoringFixtureService service;
    private AuthoringDraft draft;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        drafts = new AuthoringDraftService(
                new InMemoryDraftRepository(),
                new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()),
                        libraries,
                        mapper),
                libraries,
                new InMemoryAuthoringCatalogOwnershipRepository(),
                mapper);
        draft = drafts.save(
                SCOPE,
                "fixture-authoring",
                0,
                "quick",
                yaml.readValue("""
                        schemaVersion: bloge.visualLibraryAuthoring.v1
                        library:
                          id: fixture-authoring
                          name: Fixture Authoring
                          version: 1.0.0
                          owner: support-quality
                        operators:
                          demo:echo:
                            name: Echo
                            archetype: pure
                            input:
                              request: any
                            output:
                              result: any
                        functions:
                          trim:
                            signatures:
                              - "(text: string) -> string"
                        """, VisualLibraryAuthoringDocument.class),
                "alice");
        service = new AuthoringFixtureService(
                drafts,
                fixtures,
                new AuthoringFixturePayloadProtector(
                        "test-v1",
                        Map.of("test-v1", "0123456789abcdef0123456789abcdef"
                                .getBytes(StandardCharsets.UTF_8))),
                events,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void savesAndReadsOneEncryptedRedactedExactDraftFixtureRevision() {
        var receipt = service.save(
                draft.draftId(),
                draft.revision(),
                new SaveRequest(
                        SaveRequest.SCHEMA_VERSION,
                        "support-echo-golden",
                        0,
                        SourceKind.OPERATOR_TEST_CASE,
                        AssetKind.OPERATOR,
                        "demo:echo",
                        "CONFIDENTIAL",
                        7,
                        List.of("/inputs/customer/email"),
                        Map.of(
                                "inputs", Map.of(
                                        "customer", Map.of(
                                                "email", "ada@example.test",
                                                "accessToken", "not-for-audit"),
                                        "request", "hello"),
                                "config", Map.of(),
                                "mockedOutputs", Map.of("result", "hello"))),
                identity("tenant-a", "project-a"));

        assertThat(receipt.fixtureId()).isEqualTo("support-echo-golden");
        assertThat(receipt.revision()).isEqualTo(1);
        assertThat(receipt.authoringRevision()).isEqualTo(draft.revision());
        assertThat(receipt.artifactFingerprint()).startsWith("sha256:");
        assertThat(receipt.payloadFingerprint()).startsWith("sha256:");
        assertThat(receipt.redactedPaths())
                .containsExactly("/inputs/customer/accessToken", "/inputs/customer/email");
        assertThat(receipt.payloadPersisted()).isTrue();
        assertThat(receipt.payloadReturned()).isFalse();

        StoredAuthoringFixture stored =
                fixtures.values.values().iterator().next().getFirst();
        assertThat(stored.protectedPayload())
                .startsWith("v1.test-v1.")
                .doesNotContain("ada@example.test", "not-for-audit", "hello");

        var material = service.find(
                receipt.fixtureId(),
                receipt.revision(),
                identity("tenant-a", "project-a"));
        assertThat(material.payloadReturned()).isTrue();
        assertThat(material.payload()).isEqualTo(Map.of(
                "inputs", Map.of(
                        "customer", Map.of(
                                "email", "[REDACTED]",
                                "accessToken", "[REDACTED]"),
                        "request", "hello"),
                "config", Map.of(),
                "mockedOutputs", Map.of("result", "hello")));
        assertThat(events.values)
                .extracting(TestSecurityEvent::eventType)
                .containsExactly("AUTHORING_FIXTURE_SAVED", "AUTHORING_FIXTURE_READ");
        assertThat(mapper.valueToTree(events.values).toString())
                .doesNotContain("ada@example.test", "not-for-audit", "hello");
    }

    @Test
    void createsMonotonicImmutableRevisionsAndRejectsStaleFixtureWriters() {
        SaveRequest initial = request(0, Map.of("inputs", Map.of("request", "one")));
        SaveRequest successor = request(1, Map.of("inputs", Map.of("request", "two")));

        assertThat(service.save(
                draft.draftId(), draft.revision(), initial, identity("tenant-a", "project-a"))
                .revision()).isEqualTo(1);
        assertThat(service.save(
                draft.draftId(), draft.revision(), successor, identity("tenant-a", "project-a"))
                .revision()).isEqualTo(2);

        assertThatThrownBy(() -> service.save(
                draft.draftId(), draft.revision(), successor, identity("tenant-a", "project-a")))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.FIXTURE_REVISION_STALE"));
    }

    @Test
    void preventsAnExistingFixtureIdFromChangingItsGovernedLineage() {
        service.save(
                draft.draftId(),
                draft.revision(),
                request(0, Map.of("inputs", Map.of("request", "one"))),
                identity("tenant-a", "project-a"));
        SaveRequest rebound = new SaveRequest(
                SaveRequest.SCHEMA_VERSION,
                "support-echo-golden",
                1,
                SourceKind.SAMPLE,
                AssetKind.OPERATOR,
                "demo:echo",
                "CONFIDENTIAL",
                7,
                List.of(),
                Map.of("samples", List.of(Map.of("request", "two"))));

        assertThatThrownBy(() -> service.save(
                draft.draftId(),
                draft.revision(),
                rebound,
                identity("tenant-a", "project-a")))
                .isInstanceOfSatisfying(
                        AuthoringLifecycleException.class,
                        exception -> assertThat(exception.problem().code())
                                .isEqualTo(
                                        "RG.AUTHORING.FIXTURE_LINEAGE_CONFLICT"));
        assertThat(fixtures.values.values().iterator().next()).hasSize(1);
        assertThat(events.values).hasSize(1);
    }

    @Test
    void hidesFixturesAcrossTheCompleteEnterpriseScope() {
        var receipt = service.save(
                draft.draftId(),
                draft.revision(),
                request(0, Map.of("inputs", Map.of("request", "scoped"))),
                identity("tenant-a", "project-a"));

        assertThatThrownBy(() -> service.find(
                receipt.fixtureId(),
                receipt.revision(),
                identity("tenant-a", "project-b")))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.FIXTURE_NOT_FOUND"));
    }

    @Test
    void rejectsStaleDraftAndInsufficientClearanceBeforePersistingPayload() {
        drafts.save(
                SCOPE,
                draft.draftId(),
                draft.revision(),
                draft.sourceMode(),
                draft.document(),
                "bob");

        assertThatThrownBy(() -> service.save(
                draft.draftId(),
                draft.revision(),
                request(0, Map.of("inputs", Map.of("request", "stale"))),
                identity("tenant-a", "project-a")))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.DRAFT_REVISION_STALE"));
        assertThat(fixtures.values).isEmpty();

        AuthoringDraft current = drafts.find(SCOPE, draft.draftId());
        IntegrationRequestContext publicIdentity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "region-a",
                "HUMAN", "alice", "", "TEST_FIXTURE_WRITE", "corr-public",
                java.util.Set.of(), "PUBLIC", "");
        assertThatThrownBy(() -> service.save(
                current.draftId(),
                current.revision(),
                request(0, Map.of("inputs", Map.of("request", "restricted"))),
                publicIdentity))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.FIXTURE_CLEARANCE_FORBIDDEN"));
        assertThat(fixtures.values).isEmpty();
    }

    @Test
    void rejectsUnboundedAutomaticRedactionAndInvalidJsonPointers() {
        Map<String, Object> sensitive = new LinkedHashMap<>();
        for (int index = 0;
             index <= AuthoringFixtureService.MAXIMUM_REDACTION_PATHS;
             index++) {
            sensitive.put("accessToken" + index, "secret-" + index);
        }
        assertThatThrownBy(() -> service.save(
                draft.draftId(),
                draft.revision(),
                request(0, Map.of("inputs", sensitive)),
                identity("tenant-a", "project-a")))
                .isInstanceOfSatisfying(
                        AuthoringLifecycleException.class,
                        exception -> assertThat(exception.problem().code())
                                .isEqualTo(
                                        "RG.AUTHORING.FIXTURE_REDACTION_LIMIT_EXCEEDED"));

        SaveRequest invalidPointer = new SaveRequest(
                SaveRequest.SCHEMA_VERSION,
                "invalid-pointer",
                0,
                SourceKind.SAMPLE,
                AssetKind.OPERATOR,
                "demo:echo",
                "INTERNAL",
                1,
                List.of("/inputs/value~2"),
                Map.of("inputs", Map.of("value~2", "not-sensitive")));
        assertThatThrownBy(() -> service.save(
                draft.draftId(),
                draft.revision(),
                invalidPointer,
                identity("tenant-a", "project-a")))
                .isInstanceOfSatisfying(
                        AuthoringLifecycleException.class,
                        exception -> assertThat(exception.problem().code())
                                .isEqualTo(
                                        "RG.AUTHORING.FIXTURE_REDACTION_PATH_INVALID"));
        assertThat(fixtures.values).isEmpty();
    }

    @Test
    void reportsIntegrityFailureDistinctFromStoreAvailability() {
        var receipt = service.save(
                draft.draftId(),
                draft.revision(),
                request(0, Map.of("inputs", Map.of("request", "verified"))),
                identity("tenant-a", "project-a"));
        var entry = fixtures.values.entrySet().iterator().next();
        StoredAuthoringFixture stored = entry.getValue().getFirst();
        entry.setValue(List.of(stored.withRecordFingerprint(
                "sha256:" + "0".repeat(64))));

        assertThatThrownBy(() -> service.find(
                receipt.fixtureId(),
                receipt.revision(),
                identity("tenant-a", "project-a")))
                .isInstanceOfSatisfying(
                        AuthoringLifecycleException.class,
                        exception -> assertThat(exception.problem().code())
                                .isEqualTo(
                                        "RG.AUTHORING.FIXTURE_INTEGRITY_INVALID"));
    }

    private SaveRequest request(long expectedRevision, Object payload) {
        return new SaveRequest(
                SaveRequest.SCHEMA_VERSION,
                "support-echo-golden",
                expectedRevision,
                SourceKind.OPERATOR_TEST_CASE,
                AssetKind.OPERATOR,
                "demo:echo",
                "CONFIDENTIAL",
                7,
                List.of(),
                payload);
    }

    private static IntegrationRequestContext identity(String tenant, String project) {
        return new IntegrationRequestContext(
                tenant, "org-a", project, "test", "region-a",
                "HUMAN", "alice", "", "TEST_FIXTURE_WRITE", "corr-1",
                java.util.Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static final class InMemoryFixtureRepository
            implements AuthoringFixtureRepository {
        private final Map<String, List<StoredAuthoringFixture>> values = new LinkedHashMap<>();

        @Override
        public synchronized StoredAuthoringFixture create(
                TestingArtifactScope scope,
                StoredAuthoringFixture fixture,
                long expectedRevision,
                TestRuntimeTransactionMutation audit) {
            String key = key(scope, fixture.descriptor().fixtureId());
            List<StoredAuthoringFixture> revisions =
                    new ArrayList<>(values.getOrDefault(key, List.of()));
            long current = revisions.isEmpty()
                    ? 0 : revisions.getLast().descriptor().revision();
            if (current != expectedRevision) {
                throw new AuthoringFixtureRevisionConflictException(current);
            }
            revisions.add(fixture);
            audit.apply(null);
            values.put(key, List.copyOf(revisions));
            return fixture;
        }

        @Override
        public Optional<StoredAuthoringFixture> find(
                TestingArtifactScope scope, String fixtureId, long revision) {
            return values.getOrDefault(key(scope, fixtureId), List.of()).stream()
                    .filter(value -> value.descriptor().revision() == revision)
                    .findFirst();
        }

        @Override
        public long latestRevision(TestingArtifactScope scope, String fixtureId) {
            List<StoredAuthoringFixture> revisions =
                    values.getOrDefault(key(scope, fixtureId), List.of());
            return revisions.isEmpty() ? 0 : revisions.getLast().descriptor().revision();
        }

        @Override
        public int expireDue(Instant observedAt, int limit) {
            return 0;
        }

        private static String key(TestingArtifactScope scope, String fixtureId) {
            return scope + ":" + fixtureId;
        }
    }

    private static final class InMemorySecurityEvents
            implements TestSecurityEventRepository {
        private final List<TestSecurityEvent> values = new ArrayList<>();

        @Override
        public TestSecurityEvent append(TestSecurityEvent event) {
            TestSecurityEvent stored = event.withSequence(values.size() + 1L);
            values.add(stored);
            return stored;
        }

        @Override
        public TestRuntimeTransactionMutation boundAppend(TestSecurityEvent event) {
            return ignored -> append(event);
        }

        @Override
        public List<TestSecurityEvent> recent(int limit) {
            return values.reversed().stream().limit(limit).toList();
        }
    }

    private static final class InMemoryDraftRepository
            implements AuthoringDraftRepository {
        private final Map<Key, AuthoringDraft> current = new LinkedHashMap<>();
        private final Map<Key, List<AuthoringDraft>> history = new LinkedHashMap<>();

        @Override
        public Collection<AuthoringDraft> all(AuthoringScope scope) {
            return current.entrySet().stream()
                    .filter(entry -> entry.getKey().scope().equals(scope))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        @Override
        public Optional<AuthoringDraft> find(AuthoringScope scope, String draftId) {
            return Optional.ofNullable(current.get(new Key(scope, draftId)));
        }

        @Override
        public List<AuthoringDraft> revisions(AuthoringScope scope, String draftId) {
            return history.getOrDefault(new Key(scope, draftId), List.of()).reversed();
        }

        @Override
        public synchronized Optional<AuthoringDraft> saveIfRevision(
                AuthoringScope scope,
                long expectedRevision,
                AuthoringDraft candidate,
                String actor) {
            Key key = new Key(scope, candidate.draftId());
            AuthoringDraft existing = current.get(key);
            if ((existing == null && expectedRevision != 0)
                    || (existing != null && existing.revision() != expectedRevision)) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            AuthoringDraft stored = candidate.withStorageIdentity(
                    candidate.draftId(),
                    expectedRevision + 1,
                    "sha256:draft-" + (expectedRevision + 1),
                    existing == null ? now : existing.createdAt(),
                    now,
                    actor);
            current.put(key, stored);
            List<AuthoringDraft> revisions =
                    new ArrayList<>(history.getOrDefault(key, List.of()));
            revisions.add(stored);
            history.put(key, List.copyOf(revisions));
            return Optional.of(stored);
        }

        private record Key(AuthoringScope scope, String draftId) {
        }
    }
}
