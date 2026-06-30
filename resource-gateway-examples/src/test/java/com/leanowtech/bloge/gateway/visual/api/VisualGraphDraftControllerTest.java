package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchService;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.example.DynamicGatewayComposerService;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationResult;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublishRequest;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for visual graph draft APIs.
 */
class VisualGraphDraftControllerTest {

    @Test
    void compileBlocksInvalidDraftBeforeDslGeneration() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, null);
        GraphDraft draft = withFingerprints(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        )), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("ctx.score").contains("string").contains("integer");
                });
    }

    @Test
    void validateRejectsUnsupportedDraftSchemaVersion() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = withSchemaVersion(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "bloge.visualGraphDraft.v2");

        var result = controller.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                });
    }

    @Test
    void compileGeneratesDslAfterVisualValidationPasses() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, null);
        GraphDraft draft = withFingerprints(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.dsl()).contains("transform eligibility");
    }

    @Test
    void compileRejectsDraftWithoutOperatorFingerprints() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.fingerprintMissing");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/operatorRef");
                });
    }

    @Test
    void compileRejectsGeneratedDslWhenRuntimeOperatorIsMissing() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(nativePolicyLibrary());
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = withFingerprints(nativePolicyDraft(), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).contains("node policy : riskMissingRuntime");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("bloge.dsl");
                    assertThat(diagnostic.message()).contains("riskMissingRuntime");
                });
    }

    @Test
    void createStoresCurrentOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        GraphDraft stored = controller.create(draft);

        assertThat(stored.operatorFingerprints())
                .containsEntry("eligibility", catalog.find("risk:eligibility").orElseThrow().fingerprint());
    }

    @Test
    void createRejectsUnsupportedDraftSchemaVersionBeforeStorage() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft draft = withSchemaVersion(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "bloge.visualGraphDraft.v2");

        assertThatThrownBy(() -> controller.create(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bloge.visualGraphDraft.v2");
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void createDoesNotSnapshotDeprecatedOperatorAsNewExecutableNode() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                deprecatedNumericPassLibrary());
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());

        GraphDraft stored = controller.create(numericPassDraft());
        DslGenerationResult result = controller.compile(stored);

        assertThat(stored.operatorFingerprints()).doesNotContainKey("pass");
        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.operator.fingerprintMissing");
    }

    @Test
    void createStoresRevisionMetadataSnapshot() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();

        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        assertThat(stored.revisionMetadata().createdAt()).isNotBlank();
        assertThat(stored.revisionMetadata().updatedAt()).isNotBlank();
        assertThat(stored.revisionMetadata().createdBy()).isEqualTo("visual-canvas");
        assertThat(stored.revisionMetadata().updatedBy()).isEqualTo("visual-canvas");
        assertThat(stored.revisionMetadata().changeSource()).isEqualTo("api");
        assertThat(stored.revisionMetadata().changeSummary()).isEqualTo("Saved draft.");
    }

    @Test
    void createIgnoresSubmittedIdentityAndDoesNotOverwriteExistingDraft() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft submittedWithExistingIdentity = renameDraft(first, "attemptedOverwrite");

        GraphDraft second = controller.create(submittedWithExistingIdentity);

        assertThat(second.draftId()).isNotBlank().isNotEqualTo(first.draftId());
        assertThat(second.revision()).isEqualTo(1);
        assertThat(repository.find(first.draftId()).orElseThrow().graphName()).isEqualTo(first.graphName());
        assertThat(repository.find(second.draftId()).orElseThrow().graphName()).isEqualTo("attemptedOverwrite");
    }

    @Test
    void patchStoredDraftAppliesExpectedRevisionAndIncrementsRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "patchedPolicy")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isTrue();
        assertThat(response.getBody().draft().graphName()).isEqualTo("patchedPolicy");
        assertThat(response.getBody().draft().revision()).isEqualTo(stored.revision() + 1);
    }

    @Test
    void patchStoredDraftCapturesRevisionMetadata() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(
                        stored.revision(),
                        "alice@example.com",
                        "browser-save",
                        "Rename graph",
                        List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "patchedPolicy"))
                ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft patched = response.getBody().draft();
        assertThat(patched.revisionMetadata().createdAt()).isEqualTo(stored.revisionMetadata().createdAt());
        assertThat(patched.revisionMetadata().createdBy()).isEqualTo(stored.revisionMetadata().createdBy());
        assertThat(patched.revisionMetadata().updatedAt()).isNotBlank();
        assertThat(patched.revisionMetadata().updatedBy()).isEqualTo("alice@example.com");
        assertThat(patched.revisionMetadata().changeSource()).isEqualTo("browser-save");
        assertThat(patched.revisionMetadata().changeSummary()).isEqualTo("Rename graph");
        assertThat(patched.revisionMetadata().changedPaths()).containsExactly("/graphName");
    }

    @Test
    void patchStoredDraftPreservesExistingOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<GraphDraftPatchResult> response = evolvedController.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "renamedPolicy")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft patched = response.getBody().draft();
        assertThat(patched.operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint)
                .doesNotContainEntry("eligibility", evolvedFingerprint);
        assertThat(validator(evolvedCatalog).validate(patched).diagnostics())
                .extracting("code")
                .contains("visual.operator.fingerprintMismatch");
    }

    @Test
    void updatePreservesSubmittedOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<Object> response = evolvedController.update(stored.draftId(), renameDraft(stored, "renamedPolicy"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(GraphDraft.class);
        GraphDraft updated = (GraphDraft) response.getBody();
        assertThat(updated.operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint)
                .doesNotContainEntry("eligibility", evolvedFingerprint);
        assertThat(validator(evolvedCatalog).validate(updated).diagnostics())
                .extracting("code")
                .contains("visual.operator.fingerprintMismatch");
    }

    @Test
    void updateKeepsExistingOperatorFingerprintSnapshotOverSubmittedRebase() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        GraphDraft rebased = renameDraft(stored.withOperatorFingerprints(Map.of(
                "eligibility", evolvedFingerprint
        )), "rebasedPolicy");

        ResponseEntity<Object> response = evolvedController.update(stored.draftId(), rebased);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft updated = (GraphDraft) response.getBody();
        assertThat(updated.operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint)
                .doesNotContainEntry("eligibility", evolvedFingerprint);
    }

    @Test
    void updateRejectsUnsupportedDraftSchemaVersionAndKeepsCurrentDraft() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft futureDraft = withSchemaVersion(renameDraft(stored, "futureContract"), "bloge.visualGraphDraft.v2");

        ResponseEntity<Object> response = controller.update(stored.draftId(), futureDraft);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        var result = (VisualValidationResult) response.getBody();
        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                });
        assertThat(controller.get(stored.draftId()).getBody().schemaVersion()).isEqualTo(GraphDraft.SCHEMA_VERSION);
        assertThat(controller.get(stored.draftId()).getBody().graphName()).isEqualTo(stored.graphName());
    }

    @Test
    void updateRejectsStaleRevisionAndKeepsCurrentDraft() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        ResponseEntity<Object> freshResponse = controller.update(stored.draftId(), renameDraft(stored, "freshPolicy"));
        assertThat(freshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(freshResponse.getBody()).isInstanceOf(GraphDraft.class);
        GraphDraft fresh = (GraphDraft) freshResponse.getBody();

        ResponseEntity<Object> staleResponse = controller.update(stored.draftId(), renameDraft(stored, "stalePolicy"));

        assertThat(staleResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleResponse.getBody()).isInstanceOf(GraphDraftPatchResult.class);
        GraphDraftPatchResult result = (GraphDraftPatchResult) staleResponse.getBody();
        assertThat(result.patched()).isFalse();
        assertThat(result.draft().revision()).isEqualTo(fresh.revision());
        assertThat(result.draft().graphName()).isEqualTo("freshPolicy");
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.draft.revisionConflict");
        assertThat(controller.get(stored.draftId()).getBody().graphName()).isEqualTo("freshPolicy");
    }

    @Test
    void patchRejectsOperatorFingerprintMutation() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String fingerprint = stored.operatorFingerprints().get("eligibility");

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace",
                                "/operatorFingerprints/eligibility", "manual-rebase")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.patchPathForbidden");
        assertThat(controller.get(stored.draftId()).getBody().operatorFingerprints())
                .containsEntry("eligibility", fingerprint);
    }

    @Test
    void patchRejectsDraftSchemaVersionMutation() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace",
                                "/schemaVersion", "bloge.visualGraphDraft.v2")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.patchPathForbidden");
        assertThat(controller.get(stored.draftId()).getBody().schemaVersion()).isEqualTo(GraphDraft.SCHEMA_VERSION);
    }

    @Test
    void patchRejectsMissingPatchOperationWithStructuredDiagnostic() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), Collections.singletonList(null)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .containsExactly("visual.draft.patchOperationMissing");
    }

    @Test
    void patchFillsFingerprintForNewNodeWithoutClientFingerprintPatch() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String fingerprint = catalog.find("risk:eligibility").orElseThrow().fingerprint();
        GraphDraft.DraftNode newNode = new GraphDraft.DraftNode(
                "eligibility2",
                "risk:eligibility",
                "",
                Map.of(
                        "score", GraphDraft.Binding.contextPath("score"),
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                Map.of(),
                null
        );

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("add", "/nodes/-", newNode)
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft patched = response.getBody().draft();
        assertThat(patched.nodes())
                .extracting(GraphDraft.DraftNode::id)
                .contains("eligibility", "eligibility2");
        assertThat(patched.operatorFingerprints())
                .containsEntry("eligibility", fingerprint)
                .containsEntry("eligibility2", fingerprint);
    }

    @Test
    void patchStoredDraftRejectsStaleRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision() - 1, List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "stalePatch")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().draft().revision()).isEqualTo(stored.revision());
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.revisionConflict");
    }

    @Test
    void revisionsReturnStoredDraftHistory() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        ResponseEntity<GraphDraftPatchResult> patched = controller.patch(first.draftId(),
                new GraphDraftPatchRequest(first.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "revisionTwo")
                )));
        GraphDraft second = patched.getBody().draft();

        ResponseEntity<List<GraphDraft>> response = controller.revisions(first.draftId());
        ResponseEntity<GraphDraft> firstRevision = controller.revision(first.draftId(), first.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .extracting(GraphDraft::revision)
                .containsExactly(second.revision(), first.revision());
        assertThat(firstRevision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRevision.getBody()).isEqualTo(first);
    }

    @Test
    void deleteRejectsStaleExpectedRevisionAndKeepsCurrentDraft() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), drafts);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "deleteGuarded"))
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<Object> response = controller.delete(first.draftId(), first.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(GraphDraftPatchResult.class);
        GraphDraftPatchResult result = (GraphDraftPatchResult) response.getBody();
        assertThat(result.draft()).isEqualTo(patched.draft());
        assertThat(result.diagnostics())
                .extracting("code")
                .containsExactly("visual.draft.revisionConflict");
        assertThat(drafts.find(first.draftId())).contains(patched.draft());
    }

    @Test
    void deleteRemovesDraftWhenExpectedRevisionMatches() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), drafts);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<Object> response = controller.delete(stored.draftId(), stored.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(drafts.find(stored.draftId())).isEmpty();
    }

    @Test
    void runStoredDraftRejectsStaleExpectedRevision() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), drafts);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "runGuarded"))
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<VisualGraphRunResponse> response = controller.runStored(first.draftId(),
                new VisualStoredDraftRunRequest(
                        Map.of("score", 720, "amount", 100_000),
                        "eligibility",
                        first.revision()
                ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.revisionConflict");
                    assertThat(diagnostic.message())
                            .contains("expected %d".formatted(first.revision()))
                            .contains("current revision is %d".formatted(patched.draft().revision()));
                });
        assertThat(response.getBody().errors())
                .anySatisfy(error -> assertThat(error).contains("Draft revision conflict"));
    }

    @Test
    void publishStoredDraftCreatesImmutablePublication() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VisualGraphPublicationResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.published()).isTrue();
        assertThat(result.publication().publicationId()).isNotBlank();
        assertThat(result.publication().dsl()).contains("transform eligibility");
        assertThat(result.publication().operatorSnapshots())
                .extracting("operatorRef")
                .containsExactly("risk:eligibility");
        assertThat(result.publication().operatorFingerprints()).containsKey("eligibility");
        assertThat(publications.find(result.publication().publicationId())).contains(result.publication());
    }

    @Test
    void publishRejectsStaleExpectedRevision() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "latestDraft"))
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(first.draftId(),
                new VisualGraphPublishRequest(first.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.revisionConflict");
                    assertThat(diagnostic.message())
                            .contains("expected %d".formatted(first.revision()))
                            .contains("current revision is %d".formatted(patched.draft().revision()));
                });
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishRejectsInvalidStoredDraft() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch");
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishRejectsGeneratedDslWhenRuntimeOperatorIsMissing() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(nativePolicyLibrary());
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(nativePolicyDraft());

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("bloge.dsl");
                    assertThat(diagnostic.message()).contains("riskMissingRuntime");
                });
        assertThat(publications.all()).isEmpty();
    }

    private static VisualGraphDraftController controllerWithEligibilityLibrary() {
        return controllerWithCatalog(eligibilityCatalog(), null);
    }

    private static DefaultVisualOperatorCatalog eligibilityCatalog() {
        return VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository) {
        return controllerWithCatalog(catalog, repository, new InMemoryVisualGraphPublicationRepository());
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository,
                                                                    InMemoryVisualGraphPublicationRepository publications) {
        return new VisualGraphDraftController(
                repository == null ? new InMemoryGraphDraftRepository() : repository,
                validator(catalog),
                runner(catalog),
                catalog,
                publications,
                new GraphDraftPatchService(new ObjectMapper())
        );
    }

    private static GraphDraftValidator validator(DefaultVisualOperatorCatalog catalog) {
        return new GraphDraftValidator(catalog);
    }

    private static GraphDraftDslGenerator generator(DefaultVisualOperatorCatalog catalog) {
        return new GraphDraftDslGenerator(catalog);
    }

    private static VisualGraphRunService runner(DefaultVisualOperatorCatalog catalog) {
        GraphDraftValidator validator = validator(catalog);
        GraphDraftDslGenerator generator = generator(catalog);
        return new VisualGraphRunService(validator, generator,
                new DynamicGatewayComposerService(httpResourceOperatorStub()));
    }

    private static HttpResourceOperator httpResourceOperatorStub() {
        return new HttpResourceOperator(null, null, null, null, null, null);
    }

    private static GraphDraft eligibilityDraft(SchemaEnvelope inputSchema) {
        return new GraphDraft(
                "",
                "",
                0,
                "compileGate",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static SchemaEnvelope graphInputSchema(Map<String, Object> properties) {
        return SchemaEnvelope.object(properties, properties.keySet().stream().toList());
    }

    private static GraphDraft nativePolicyDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "nativeCompileGate",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string")
                ), List.of("applicantId")),
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:nativePolicy",
                        "",
                        Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft renameDraft(GraphDraft draft, String graphName) {
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                graphName,
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.revisionMetadata()
        );
    }

    private static GraphDraft withSchemaVersion(GraphDraft draft, String schemaVersion) {
        return new GraphDraft(
                schemaVersion,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.revisionMetadata()
        );
    }

    private static GraphDraft numericPassDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "deprecatedGate",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "value", Map.of("type", "integer")
                ), List.of("value")),
                List.of(new GraphDraft.DraftNode(
                        "pass",
                        "risk:numericPass",
                        "",
                        Map.of("value", GraphDraft.Binding.contextPath("value")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("pass", "")
        );
    }

    private static GraphDraft withFingerprints(GraphDraft draft, DefaultVisualOperatorCatalog catalog) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            catalog.find(node.operatorRef())
                    .ifPresent(operator -> fingerprints.put(node.id(), operator.fingerprint()));
        }
        return draft.withOperatorFingerprints(fingerprints);
    }

    private static OperatorLibrary nativePolicyLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nativePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Native policy", "Requires a runtime native operator.",
                        List.of("risk", "native")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of(
                                        "applicantId", Map.of("type", "string")
                                ), List.of("applicantId")),
                                true,
                                "Native inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "decision", Map.of("type", "string")
                                ), List.of()),
                                true,
                                "Native output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskMissingRuntime", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-native-policy",
                "Risk native operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary deprecatedNumericPassLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-numeric-pass",
                "Numeric pass operators",
                "1.0.0",
                "risk-team",
                "DEPRECATED",
                List.of(VisualCatalogTestSupport.numericPassOperator())
        );
    }
}
