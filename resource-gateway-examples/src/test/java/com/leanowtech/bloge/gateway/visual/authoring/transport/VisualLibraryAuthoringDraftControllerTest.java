package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftRepository;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPrincipal;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.application.InMemoryAuthoringCatalogOwnershipRepository;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualLibraryAuthoringDraftControllerTest {

    private static final AuthoringScope SCOPE = new AuthoringScope(
            "tenant-a", "knowledge-governance", "tool-studio", "test", "local");

    @Test
    void requiresAndReturnsNumericEtags() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        AuthoringDraftService service = new AuthoringDraftService(
                new SingleDraftRepository(),
                new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()),
                        libraries,
                        mapper
                ),
                libraries,
                new InMemoryAuthoringCatalogOwnershipRepository(),
                mapper
        );
        VisualLibraryAuthoringDraftController controller =
                new VisualLibraryAuthoringDraftController(
                        service,
                        (headers, action) -> new AuthoringPrincipal(
                                SCOPE.tenantId(),
                                SCOPE.organizationId(),
                                SCOPE.projectId(),
                                SCOPE.environmentId(),
                                SCOPE.region(),
                                "trusted-actor"));
        VisualLibraryAuthoringDocument document = new YAMLMapper().findAndRegisterModules()
                .readValue("""
                        schemaVersion: bloge.visualLibraryAuthoring.v1
                        library: {id: support-library, owner: support-team}
                        operators:
                          support:echo:
                            input: {value: string}
                            output: {value: string}
                        """, VisualLibraryAuthoringDocument.class);

        assertThat(controller.context(headers(null))).satisfies(context -> {
            assertThat(context.schemaVersion())
                    .isEqualTo("bloge.visualLibraryAuthoringHomeContext.v1");
            assertThat(context.actorId()).isEqualTo("trusted-actor");
            assertThat(context.organizationId()).isEqualTo("knowledge-governance");
            assertThat(context.projectId()).isEqualTo("tool-studio");
        });

        assertThatThrownBy(() -> controller.save(
                "support-library",
                headers(null),
                new VisualLibraryAuthoringDraftController.DraftSaveRequest("QUICK", document, "alice")
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception -> {
            assertThat(exception.problem().status()).isEqualTo(428);
            assertThat(exception.problem().code()).isEqualTo("RG.AUTHORING.IF_MATCH_REQUIRED");
        });

        var created = controller.save(
                "support-library",
                headers("\"0\""),
                new VisualLibraryAuthoringDraftController.DraftSaveRequest("QUICK", document, "alice")
        );
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(created.getBody().savedBy()).isEqualTo("trusted-actor");

        var preview = controller.preview("support-library", headers("W/\"1\""));
        assertThat(preview.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(preview.getBody()).satisfies(result -> {
            assertThat(result.draftId()).isEqualTo("support-library");
            assertThat(result.authoringRevision()).isEqualTo(1);
        });

        var inference = controller.inferSamples(
                "support-library",
                headers("\"1\""),
                """
                {
                  "schemaVersion": "bloge.visualSampleInferenceRequest.v1",
                  "target": {
                    "assetKind": "OPERATOR",
                    "assetRef": "support:echo",
                    "portDirection": "INPUT",
                    "portName": "value"
                  },
                  "samples": [
                    {"id":"one","score":1},
                    {"id":"two","score":2}
                  ],
                  "options": {
                    "suggestEnums": true,
                    "suggestFormats": true,
                    "persistPayload": false
                  },
                  "idempotencyKey": "echo-input-inference"
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        assertThat(inference.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(inference.getBody()).satisfies(result -> {
            assertThat(result.draftId()).isEqualTo("support-library");
            assertThat(result.authoringRevision()).isEqualTo(1);
            assertThat(result.payloadPersisted()).isFalse();
            assertThat(result.candidate().path("additionalProperties").asBoolean()).isTrue();
            assertThat(result.observations()).isNotEmpty();
        });

        assertInferenceFailure(
                controller,
                "\"0\"",
                inferenceRequest("support:echo", false),
                412,
                "RG.AUTHORING.DRAFT_REVISION_STALE"
        );
        assertInferenceFailure(
                controller,
                "\"1\"",
                inferenceRequest("support:missing", false),
                404,
                "RG.AUTHORING.INFERENCE_TARGET_NOT_FOUND"
        );
        assertInferenceFailure(
                controller,
                "\"1\"",
                inferenceRequest("support:echo", true),
                422,
                "RG.AUTHORING.INFERENCE_PAYLOAD_PERSISTENCE_UNSUPPORTED"
        );

        SampleInferenceRequest exactRequest = new SampleInferenceRequest(
                SampleInferenceRequest.SCHEMA_VERSION,
                new SampleInferenceRequest.Target(
                        "OPERATOR", "support:echo", "INPUT", "value"),
                List.of(
                        mapper.readTree("{\"id\":\"one\",\"score\":1}"),
                        mapper.readTree("{\"id\":\"two\",\"score\":2}")
                ),
                SampleInferenceRequest.Options.defaults(),
                "echo-input-inference"
        );
        SampleInferenceApplyRequest apply = new SampleInferenceApplyRequest(
                SampleInferenceApplyRequest.SCHEMA_VERSION,
                exactRequest,
                inference.getBody().evidenceFingerprint(),
                inference.getBody().confirmationRequests().stream()
                        .map(confirmation -> new SampleInferenceApplyRequest.Decision(
                                confirmation.confirmationId(),
                                confirmation.recommendedValue()
                        ))
                        .toList(),
                "alice"
        );
        var applied = controller.applySampleInference(
                "support-library",
                headers("\"1\""),
                mapper.writeValueAsBytes(apply)
        );
        assertThat(applied.getHeaders().getETag()).isEqualTo("\"2\"");
        assertThat(applied.getBody()).satisfies(draft -> {
            assertThat(draft.revision()).isEqualTo(2);
            assertThat(draft.evidence()).hasSize(1);
            assertThat(draft.document().operators().get("support:echo")
                    .input().get("value").path("fields").path("score").asText())
                    .isEqualTo("integer");
        });
        assertThat(controller.revision("support-library", 1, headers(null)))
                .satisfies(response -> {
                    assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().revision()).isEqualTo(1);
                });
        assertThatThrownBy(() -> controller.revision(
                "support-library", 99, headers(null)
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception -> {
            assertThat(exception.problem().status()).isEqualTo(404);
            assertThat(exception.problem().code())
                    .isEqualTo("RG.AUTHORING.DRAFT_REVISION_NOT_FOUND");
        });
    }

    private static byte[] inferenceRequest(String operatorRef, boolean persistPayload) {
        return """
                {
                  "schemaVersion": "bloge.visualSampleInferenceRequest.v1",
                  "target": {
                    "assetKind": "OPERATOR",
                    "assetRef": "%s",
                    "portDirection": "INPUT",
                    "portName": "value"
                  },
                  "samples": [{"id":"one"}],
                  "options": {
                    "suggestEnums": true,
                    "suggestFormats": true,
                    "persistPayload": %s
                  },
                  "idempotencyKey": "negative-inference"
                }
                """.formatted(operatorRef, persistPayload)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertInferenceFailure(
            VisualLibraryAuthoringDraftController controller,
            String ifMatch,
            byte[] source,
            int status,
            String code) {
        assertThatThrownBy(() -> controller.inferSamples(
                "support-library", headers(ifMatch), source
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception -> {
            assertThat(exception.problem().status()).isEqualTo(status);
            assertThat(exception.problem().code()).isEqualTo(code);
        });
    }

    private static HttpHeaders headers(String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (ifMatch != null) {
            headers.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return headers;
    }

    private static final class SingleDraftRepository implements AuthoringDraftRepository {
        private AuthoringDraft current;
        private AuthoringScope scope;
        private final List<AuthoringDraft> history = new ArrayList<>();

        @Override
        public Collection<AuthoringDraft> all(AuthoringScope requiredScope) {
            return current == null || !requiredScope.equals(scope)
                    ? List.of()
                    : List.of(current);
        }

        @Override
        public Optional<AuthoringDraft> find(AuthoringScope requiredScope, String draftId) {
            return Optional.ofNullable(current)
                    .filter(draft -> requiredScope.equals(scope))
                    .filter(draft -> draft.draftId().equals(draftId));
        }

        @Override
        public List<AuthoringDraft> revisions(AuthoringScope requiredScope, String draftId) {
            if (!requiredScope.equals(scope)) {
                return List.of();
            }
            return history.stream()
                    .filter(draft -> draft.draftId().equals(draftId))
                    .sorted(java.util.Comparator.comparingLong(AuthoringDraft::revision).reversed())
                    .toList();
        }

        @Override
        public Optional<AuthoringDraft> saveIfRevision(AuthoringScope requiredScope,
                                                      long expectedRevision,
                                                      AuthoringDraft candidate,
                                                      String actor) {
            if (current != null && !requiredScope.equals(scope)) {
                return Optional.empty();
            }
            long currentRevision = current == null ? 0 : current.revision();
            if (expectedRevision != currentRevision) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            current = candidate.withStorageIdentity(
                    candidate.draftId(),
                    expectedRevision + 1,
                    "sha256:test",
                    current == null ? now : current.createdAt(),
                    now,
                    actor
            );
            scope = requiredScope;
            history.add(current);
            return Optional.of(current);
        }
    }
}
