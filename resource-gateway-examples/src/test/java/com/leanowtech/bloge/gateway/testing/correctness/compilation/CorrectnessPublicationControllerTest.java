package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectnessPublicationControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final String PAYLOAD_MARKER = "customer-account-secret-8848";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesPayloadFreePreviewAndDurablePublicationReads() throws Exception {
        FrozenCompilationInput source = CorrectnessCompilationTestData.input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        CorrectnessCompiler compiler = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        CompiledCorrectnessPlan plan = compiler.compile(source);
        CorrectnessCompilationService compilation = mock(CorrectnessCompilationService.class);
        CorrectnessPublicationService publication = mock(CorrectnessPublicationService.class);
        when(compilation.compile(eq(source.coordinate()), any())).thenReturn(plan.report());

        CorrectnessPublicationRepository.CommitResult committed = committed(source, plan);
        when(publication.publish(eq(source.coordinate()), eq("publish-1"), any()))
                .thenReturn(committed);
        String publicationId = committed.publication().publication().publicationId();
        String attemptId = committed.attempt().attempt().attemptId();
        when(publication.findPublication(eq(publicationId), any()))
                .thenReturn(committed.publication());
        when(publication.findAttempt(eq(attemptId), any()))
                .thenReturn(committed.attempt());
        when(publication.history(eq(attemptId), any()))
                .thenReturn(List.of(committed.attempt()));

        MockMvc mvc = mvc(compilation, publication);
        String coordinate = mapper.writeValueAsString(source.coordinate());

        mvc.perform(post("/api/visual/correctness-publications:compile-preview")
                        .headers(headers("publish-1"))
                        .contentType("application/json")
                        .content(coordinate))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.protocolVersion").value("bloge.correctnessApi.v1"))
                .andExpect(jsonPath("$.capabilities[0]").value("CORRECTNESS_COMPILATION_V1"))
                .andExpect(jsonPath("$.data.publishable").value(true))
                .andExpect(jsonPath("$.data.compilationFingerprint")
                        .value(plan.report().compilationFingerprint()))
                .andExpect(content().string(not(containsString(PAYLOAD_MARKER))));

        mvc.perform(post("/api/visual/correctness-publications")
                        .headers(headers("publish-1"))
                        .contentType("application/json")
                        .content(coordinate))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.attempt.attempt.stage").value("COMMITTED"))
                .andExpect(jsonPath("$.data.publication.publication.publicationId")
                        .value(publicationId))
                .andExpect(content().string(not(containsString(PAYLOAD_MARKER))));

        mvc.perform(get("/api/visual/correctness-publications/{id}", publicationId)
                        .headers(headers("read-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publication.publicationId").value(publicationId));
        mvc.perform(get("/api/visual/correctness-publications/attempts/{id}", attemptId)
                        .headers(headers("read-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempt.attemptId").value(attemptId));
        mvc.perform(get("/api/visual/correctness-publications/attempts/{id}/history", attemptId)
                        .headers(headers("read-3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attempt.stage").value("COMMITTED"));
    }

    @Test
    void rejectsMissingIdempotencyKeyAndMapsCompilationFailures() throws Exception {
        FrozenCompilationInput source = CorrectnessCompilationTestData.input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        CorrectnessCompilationService compilation = mock(CorrectnessCompilationService.class);
        CorrectnessPublicationService publication = mock(CorrectnessPublicationService.class);
        when(compilation.compile(eq(source.coordinate()), any())).thenThrow(
                new CorrectnessCompilationException(
                        409, "RG.CORRECTNESS.REFERENCE_DRIFT",
                        "Exact source reference drifted", false));
        MockMvc mvc = mvc(compilation, publication);
        String coordinate = mapper.writeValueAsString(source.coordinate());

        mvc.perform(post("/api/visual/correctness-publications:compile-preview")
                        .headers(headers(""))
                        .contentType("application/json")
                        .content(coordinate))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.PUBLICATION_IDEMPOTENCY_KEY_INVALID"));

        mvc.perform(post("/api/visual/correctness-publications:compile-preview")
                        .headers(headers("preview-drift"))
                        .contentType("application/json")
                        .content(coordinate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RG.CORRECTNESS.REFERENCE_DRIFT"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(content().string(not(containsString(PAYLOAD_MARKER))));
    }

    private MockMvc mvc(
            CorrectnessCompilationService compilation,
            CorrectnessPublicationService publication
    ) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "studio", "tenant-a", "org-a", "loan", "test", "sg",
                "USER", "publisher", "", Set.of("TEST_SCENARIO_PUBLISH"),
                Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new CorrectnessCompilationController(compilation, authenticator),
                        new CorrectnessPublicationController(publication, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_SCENARIO_PUBLISH");
        headers.set("X-Correlation-Id", "corr-publication");
        if (!idempotencyKey.isBlank()) headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    private CorrectnessPublicationRepository.CommitResult committed(
            FrozenCompilationInput source,
            CompiledCorrectnessPlan plan
    ) {
        List<ExactAssetRef> fixtureRefs = plan.fixtureRegistrations().stream()
                .map(request -> fixtureRef(request.fixtureBundle())).toList();
        ExactAssetRef suiteRef = suiteRef(plan.suiteRegistration().testSuite());
        List<ExactAssetRef> verified = new ArrayList<>(fixtureRefs);
        verified.add(suiteRef);
        PrincipalRef actor = new PrincipalRef("publisher", PrincipalKind.USER, "");
        AuditMetadata metadata = new AuditMetadata(NOW, NOW, actor, actor);
        CorrectnessPublication value = new CorrectnessPublication(
                "", "publication-a", source.scope(), source.coordinate().target(),
                source.coordinate().definitionRef(), source.coordinate().inventoryRef(),
                source.coordinate().scenarioDraftSetRef(), source.coordinate().oracleRefs(),
                source.coordinate().assertionSetRefs(), source.coordinate().fixtureAssetRefs(),
                fixtureRefs, suiteRef, CorrectnessCompiler.COMPILER_VERSION,
                plan.report().compilationFingerprint(), metadata);
        StoredCorrectnessPublication stored = StoredCorrectnessPublication.verified(mapper, value);
        PublicationAttempt attempt = new PublicationAttempt(
                "", "attempt-a", 6, "sha256:" + "9".repeat(64), source.coordinate(),
                AttemptStage.COMMITTED, verified, CorrectnessPublication.Failure.none(), metadata);
        StoredCorrectnessPublicationAttempt storedAttempt =
                new StoredCorrectnessPublicationAttempt("", source.scope(), attempt, plan.report());
        return new CorrectnessPublicationRepository.CommitResult(storedAttempt, stored);
    }

    private ExactAssetRef fixtureRef(FixtureBundle fixture) {
        return new ExactAssetRef(
                "FIXTURE_BUNDLE", fixture.fixtureBundleId(), fixture.revision(),
                ProtocolFingerprint.of(mapper, fixture));
    }

    private ExactAssetRef suiteRef(TestSuiteProtocol suite) {
        return new ExactAssetRef(
                "TEST_SUITE", suite.suiteId(), suite.revision(),
                ProtocolFingerprint.of(mapper, suite));
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(records);
        }
    }
}
