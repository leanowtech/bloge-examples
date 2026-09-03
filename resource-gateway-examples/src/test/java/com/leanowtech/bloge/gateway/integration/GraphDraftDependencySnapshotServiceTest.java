package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphDraftDependencySnapshotServiceTest {

    @Test
    void capturesCompleteDeterministicMultiLibrarySnapshot() {
        OperatorDefinition remote = operator("risk:remote", "lib-remote", "remote-worker",
                OperatorDefinition.Policy.unrestricted(), "Remote risk scorer");
        OperatorDefinition nativeOperator = operator("risk:native", "lib-native", "native",
                OperatorDefinition.Policy.unrestricted(), "Native policy");
        GraphDraft draft = draft(List.of(remote, nativeOperator));
        InMemoryOperatorLibraryRegistry libraries = libraries(
                library("lib-remote", "2.0.0", OperatorLibrary.STATUS_ACTIVE, remote),
                library("lib-native", "1.0.0", OperatorLibrary.STATUS_ACTIVE, nativeOperator));
        libraries.upsert(library("lib-remote", "2.1.0", OperatorLibrary.STATUS_ACTIVE, remote));
        VisualRuntimeBindingImplementationBinding binding = binding(remote, "binding-remote", 3,
                VisualRuntimeBindingImplementationBinding.STATE_BOUND);
        VisualRuntimeAdapterActivation activation = activation(binding, "activation-remote", 4,
                VisualRuntimeAdapterActivation.STATE_ACTIVE, "prod", "healthy");
        FixedSuiteRepository suites = new FixedSuiteRepository(Map.of(
                "suite-native", suite("suite-native", nativeOperator.operatorRef(), 1),
                "suite-remote", suite("suite-remote", remote.operatorRef(), 2)
        ), Map.of("suite-native", 2L, "suite-remote", 5L));
        GraphDraftDependencySnapshotService service = service(
                catalog(List.of(remote, nativeOperator)), libraries,
                new FixedBindingRepository(List.of(binding)),
                new FixedActivationRepository(List.of(activation)), suites);

        GraphDraftDependencySnapshotService.Snapshot first = service.capture(draft);
        GraphDraftDependencySnapshotService.Snapshot second = service.capture(draft);
        GraphDraftDependencyProfile profile = profile(draft, first);

        assertThat(first.fingerprint()).startsWith("sha256:").isEqualTo(second.fingerprint());
        assertThat(first.capturedAt()).isEqualTo(Instant.parse("2026-07-13T00:00:00Z"));
        assertThat(first.operators()).extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:native", "risk:remote");
        assertThat(profile.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.consistencyStatus()).isEqualTo("STABLE");
            assertThat(snapshot.operatorCount()).isEqualTo(2);
            assertThat(snapshot.operatorLibraryCount()).isEqualTo(2);
            assertThat(snapshot.runtimeBindingCount()).isEqualTo(1);
            assertThat(snapshot.contractSuiteCount()).isEqualTo(2);
        });
        assertThat(profile.operatorDependencies()).filteredOn(dependency ->
                dependency.operatorRef().equals(remote.operatorRef())).singleElement().satisfies(dependency -> {
                    assertThat(dependency.operatorLibrary()).satisfies(library -> {
                        assertThat(library.libraryId()).isEqualTo("lib-remote");
                        assertThat(library.revision()).isEqualTo(2);
                        assertThat(library.version()).isEqualTo("2.1.0");
                        assertThat(library.owner()).isEqualTo("owner-lib-remote");
                        assertThat(library.fingerprint()).startsWith("sha256:");
                    });
                    assertThat(dependency.runtimeBindingRefs()).containsExactly("binding-remote@3");
                    assertThat(dependency.runtimeBindings()).singleElement().satisfies(ref -> {
                        assertThat(ref.bindingId()).isEqualTo("binding-remote");
                        assertThat(ref.activationId()).isEqualTo("activation-remote");
                        assertThat(ref.activationRevision()).isEqualTo(4);
                        assertThat(ref.activationEnvironment()).isEqualTo("prod");
                        assertThat(ref.activationHealth()).isEqualTo("healthy");
                        assertThat(ref.ready()).isTrue();
                    });
                    assertThat(dependency.contractSuiteRefs()).containsExactly("suite-remote@5");
                    assertThat(dependency.contractSuites()).singleElement().satisfies(ref -> {
                        assertThat(ref.revision()).isEqualTo(5);
                        assertThat(ref.caseCount()).isEqualTo(2);
                        assertThat(ref.fingerprint()).startsWith("sha256:");
                    });
                    assertThat(dependency.readiness().state()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
                    assertThat(dependency.readiness().runtimeReady()).isTrue();
                });
        assertThat(profile.operatorDependencies()).filteredOn(dependency ->
                dependency.operatorRef().equals(nativeOperator.operatorRef())).singleElement()
                .satisfies(dependency -> assertThat(dependency.readiness().state())
                        .isEqualTo("RUNTIME_EXECUTABLE"));
    }

    @Test
    void classifiesMissingBlockedAndStaleReadinessAtTheRootCause() {
        OperatorDefinition suiteMissing = operator("risk:suite-missing", "lib-suite", "native",
                OperatorDefinition.Policy.unrestricted(), "");
        OperatorDefinition bindingMissing = operator("risk:binding-missing", "lib-binding", "remote-worker",
                OperatorDefinition.Policy.unrestricted(), "");
        OperatorDefinition activationStale = operator("risk:activation-stale", "lib-stale", "remote-worker",
                OperatorDefinition.Policy.unrestricted(), "");
        OperatorDefinition inactive = operator("risk:inactive", "lib-inactive", "native",
                OperatorDefinition.Policy.unrestricted(), "");
        OperatorDefinition libraryMissing = operator("risk:library-missing", "lib-absent", "native",
                OperatorDefinition.Policy.unrestricted(), "");
        OperatorDefinition catalogMissing = operator("risk:catalog-missing", "lib-deleted", "native",
                OperatorDefinition.Policy.unrestricted(), "saved catalog snapshot");
        List<OperatorDefinition> current = List.of(
                suiteMissing, bindingMissing, activationStale, inactive, libraryMissing);
        GraphDraft draft = draft(List.of(
                suiteMissing, bindingMissing, activationStale, inactive, libraryMissing, catalogMissing));
        InMemoryOperatorLibraryRegistry libraries = libraries(
                library("lib-suite", "1", OperatorLibrary.STATUS_ACTIVE, suiteMissing),
                library("lib-binding", "1", OperatorLibrary.STATUS_ACTIVE, bindingMissing),
                library("lib-stale", "1", OperatorLibrary.STATUS_ACTIVE, activationStale),
                library("lib-inactive", "1", OperatorLibrary.STATUS_DISABLED, inactive),
                library("lib-deleted", "1", OperatorLibrary.STATUS_ACTIVE, catalogMissing));
        VisualRuntimeBindingImplementationBinding staleBinding = binding(
                activationStale, "binding-stale", 2, VisualRuntimeBindingImplementationBinding.STATE_BOUND);
        VisualRuntimeAdapterActivation wrongEnvironment = activation(staleBinding, "activation-stale", 3,
                VisualRuntimeAdapterActivation.STATE_ACTIVE, "staging", "healthy");
        FixedSuiteRepository suites = new FixedSuiteRepository(Map.of(
                "suite-binding", suite("suite-binding", bindingMissing.operatorRef(), 1),
                "suite-stale", suite("suite-stale", activationStale.operatorRef(), 1),
                "suite-inactive", suite("suite-inactive", inactive.operatorRef(), 1),
                "suite-library-missing", suite("suite-library-missing", libraryMissing.operatorRef(), 1)
        ), Map.of());
        GraphDraftDependencyProfile profile = profile(draft, service(
                catalog(current), libraries,
                new FixedBindingRepository(List.of(staleBinding)),
                new FixedActivationRepository(List.of(wrongEnvironment)), suites).capture(draft));

        assertThat(readinessStates(profile)).containsExactlyInAnyOrderEntriesOf(Map.of(
                suiteMissing.operatorRef(), "CONTRACT_SUITE_MISSING",
                bindingMissing.operatorRef(), "RUNTIME_BINDING_MISSING",
                activationStale.operatorRef(), "ACTIVATION_MISSING_OR_STALE",
                inactive.operatorRef(), "LIBRARY_NOT_ACTIVE",
                libraryMissing.operatorRef(), "LIBRARY_MISSING",
                catalogMissing.operatorRef(), "CATALOG_MISSING"
        ));
        assertThat(profile.operatorDependencies()).filteredOn(dependency ->
                dependency.operatorRef().equals(catalogMissing.operatorRef())).singleElement().satisfies(dependency -> {
                    assertThat(dependency.runtimeBindings()).isEmpty();
                    assertThat(dependency.contractSuites()).isEmpty();
                    assertThat(dependency.readiness().runtimeReady()).isFalse();
                });
    }

    @Test
    void scopeMismatchExportsOnlyDraftOwnedSnapshotAndNoCurrentRuntimeAssets() throws Exception {
        OperatorDefinition saved = operator("risk:restricted", "lib-restricted", "native",
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("knowledge"), List.of("prod")),
                "saved-visible-description");
        OperatorDefinition currentRestricted = operator("risk:restricted", "lib-restricted", "remote-worker",
                new OperatorDefinition.Policy(List.of("tenant-b"), List.of("secret"), List.of("prod")),
                "CURRENT-SECRET-DESCRIPTION");
        GraphDraft draft = draft(List.of(saved));
        InMemoryOperatorLibraryRegistry libraries = libraries(library(
                "lib-restricted", "9.0.0", OperatorLibrary.STATUS_ACTIVE, currentRestricted));
        VisualRuntimeBindingImplementationBinding binding = binding(
                currentRestricted, "CURRENT-SECRET-BINDING", 9,
                VisualRuntimeBindingImplementationBinding.STATE_BOUND);
        VisualRuntimeAdapterActivation activation = activation(binding, "CURRENT-SECRET-ACTIVATION", 9,
                VisualRuntimeAdapterActivation.STATE_ACTIVE, "prod", "healthy");
        FixedSuiteRepository suites = new FixedSuiteRepository(Map.of(
                "CURRENT-SECRET-SUITE", suite("CURRENT-SECRET-SUITE", currentRestricted.operatorRef(), 1)
        ), Map.of("CURRENT-SECRET-SUITE", 9L));

        GraphDraftDependencySnapshotService.Snapshot snapshot = service(
                catalog(List.of(currentRestricted)), libraries,
                new FixedBindingRepository(List.of(binding)),
                new FixedActivationRepository(List.of(activation)), suites).capture(draft);
        GraphDraftDependencyProfile profile = profile(draft, snapshot);
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of(
                "operators", snapshot.operators(), "profile", profile));

        assertThat(snapshot.operators()).singleElement().isEqualTo(saved);
        assertThat(profile.sourceDependencyReport().scopeMismatchOperatorCount()).isEqualTo(1);
        assertThat(profile.operatorDependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.operatorFingerprint()).isEqualTo(saved.fingerprint());
            assertThat(dependency.operatorLibraryId()).isEqualTo("lib-restricted");
            assertThat(dependency.operatorLibrary().present()).isFalse();
            assertThat(dependency.runtimeBindings()).isEmpty();
            assertThat(dependency.contractSuites()).isEmpty();
            assertThat(dependency.readiness().state()).isEqualTo("SCOPE_MISMATCH");
        });
        assertThat(serialized)
                .contains("saved-visible-description")
                .doesNotContain("CURRENT-SECRET-DESCRIPTION")
                .doesNotContain("CURRENT-SECRET-BINDING")
                .doesNotContain("CURRENT-SECRET-ACTIVATION")
                .doesNotContain("CURRENT-SECRET-SUITE")
                .doesNotContain("owner-lib-restricted");
    }

    @Test
    void ignoresUnrelatedAssetsAndRepositoryIterationOrder() {
        OperatorDefinition relevant = operator("risk:relevant", "lib-relevant", "remote-worker",
                OperatorDefinition.Policy.unrestricted(), "");
        OperatorDefinition unrelated = operator("risk:unrelated", "lib-unrelated", "remote-worker",
                OperatorDefinition.Policy.unrestricted(), "");
        GraphDraft draft = draft(List.of(relevant));
        InMemoryOperatorLibraryRegistry relevantLibraries = libraries(
                library("lib-relevant", "1", OperatorLibrary.STATUS_ACTIVE, relevant));
        InMemoryOperatorLibraryRegistry noisyLibraries = libraries(
                library("lib-unrelated", "1", OperatorLibrary.STATUS_ACTIVE, unrelated),
                library("lib-relevant", "1", OperatorLibrary.STATUS_ACTIVE, relevant));
        VisualRuntimeBindingImplementationBinding relevantA = binding(
                relevant, "binding-a", 1, VisualRuntimeBindingImplementationBinding.STATE_REQUIRES_REVIEW);
        VisualRuntimeBindingImplementationBinding relevantB = binding(
                relevant, "binding-b", 2, VisualRuntimeBindingImplementationBinding.STATE_BOUND);
        VisualRuntimeBindingImplementationBinding unrelatedBinding = binding(
                unrelated, "binding-secret-unrelated", 8, VisualRuntimeBindingImplementationBinding.STATE_BOUND);
        VisualRuntimeAdapterActivation relevantActivation = activation(
                relevantB, "activation-b", 3, VisualRuntimeAdapterActivation.STATE_ACTIVE, "prod", "healthy");
        VisualRuntimeAdapterActivation unrelatedActivation = activation(
                unrelatedBinding, "activation-secret-unrelated", 8,
                VisualRuntimeAdapterActivation.STATE_ACTIVE, "prod", "healthy");
        VisualOperatorContractTestSuite suiteA = suite("suite-a", relevant.operatorRef(), 1);
        VisualOperatorContractTestSuite suiteB = suite("suite-b", relevant.operatorRef(), 1);
        VisualOperatorContractTestSuite unrelatedSuite = suite(
                "suite-secret-unrelated", unrelated.operatorRef(), 1);

        GraphDraftDependencySnapshotService.Snapshot ordered = service(
                catalog(List.of(relevant)), relevantLibraries,
                new FixedBindingRepository(List.of(relevantA, relevantB)),
                new FixedActivationRepository(List.of(relevantActivation)),
                new FixedSuiteRepository(Map.of("suite-a", suiteA, "suite-b", suiteB),
                        Map.of("suite-a", 1L, "suite-b", 2L))).capture(draft);
        GraphDraftDependencySnapshotService.Snapshot reversedAndNoisy = service(
                catalog(List.of(unrelated, relevant)), noisyLibraries,
                new FixedBindingRepository(List.of(unrelatedBinding, relevantB, relevantA)),
                new FixedActivationRepository(List.of(unrelatedActivation, relevantActivation)),
                new FixedSuiteRepository(linkedSuites(unrelatedSuite, suiteB, suiteA),
                        Map.of("suite-a", 1L, "suite-b", 2L, "suite-secret-unrelated", 9L))).capture(draft);

        assertThat(reversedAndNoisy.fingerprint()).isEqualTo(ordered.fingerprint());
        assertThat(reversedAndNoisy.assets().get(relevant.operatorRef()).runtimeBindings())
                .extracting(GraphDraftDependencyProfile.RuntimeBindingRef::bindingId)
                .containsExactly("binding-a", "binding-b");
        assertThat(reversedAndNoisy.assets().get(relevant.operatorRef()).contractSuites())
                .extracting(GraphDraftDependencyProfile.ContractSuiteRef::suiteId)
                .containsExactly("suite-a", "suite-b");
    }

    @Test
    void exportRejectsARealRelevantSuiteRevisionDriftDuringAssembly() {
        OperatorDefinition operator = operator("risk:drift", "lib-drift", "native",
                OperatorDefinition.Policy.unrestricted(), "");
        GraphDraft draft = draft(List.of(operator));
        FixedSuiteRepository suites = new FixedSuiteRepository(Map.of(
                "suite-drift", suite("suite-drift", operator.operatorRef(), 1)
        ), Map.of("suite-drift", 1L));
        GraphDraftDependencySnapshotService snapshots = service(catalog(List.of(operator)),
                libraries(library("lib-drift", "1", OperatorLibrary.STATUS_ACTIVE, operator)),
                new FixedBindingRepository(List.of()), new FixedActivationRepository(List.of()), suites);
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        when(drafts.findRevision(draft.draftId(), draft.revision())).thenReturn(Optional.of(draft));
        GraphDraftValidator validator = mock(GraphDraftValidator.class);
        when(validator.validate(draft)).thenAnswer(ignored -> {
            suites.save(suite("suite-drift", operator.operatorRef(), 2));
            return new VisualValidationResult(true, List.of());
        });
        ToolStudioIntegrationService integration = integrationService(drafts, validator,
                catalog(List.of(operator)), snapshots);

        assertThatThrownBy(() -> integration.exportDraft(
                draft.draftId(), draft.revision(), integrationContext("corr-relevant-drift")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    IntegrationProblem problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code()).isEqualTo("RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED");
                    assertThat(problem.retryable()).isTrue();
                    assertThat(problem.details()).containsEntry("draftStable", true);
                    assertThat(problem.details().get("beforeDependencyFingerprint"))
                            .isNotEqualTo(problem.details().get("afterDependencyFingerprint"));
                });
    }

    @Test
    void exportDoesNotConflictWhenOnlyAnUnrelatedSuiteChanges() {
        OperatorDefinition operator = operator("risk:stable", "lib-stable", "native",
                OperatorDefinition.Policy.unrestricted(), "");
        GraphDraft draft = draft(List.of(operator));
        FixedSuiteRepository suites = new FixedSuiteRepository(Map.of(
                "suite-stable", suite("suite-stable", operator.operatorRef(), 1)
        ), Map.of("suite-stable", 1L));
        VisualOperatorCatalog catalog = catalog(List.of(operator));
        GraphDraftDependencySnapshotService snapshots = service(catalog,
                libraries(library("lib-stable", "1", OperatorLibrary.STATUS_ACTIVE, operator)),
                new FixedBindingRepository(List.of()), new FixedActivationRepository(List.of()), suites);
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        when(drafts.findRevision(draft.draftId(), draft.revision())).thenReturn(Optional.of(draft));
        GraphDraftValidator validator = mock(GraphDraftValidator.class);
        when(validator.validate(draft)).thenAnswer(ignored -> {
            suites.save(suite("suite-unrelated", "risk:other", 3));
            return new VisualValidationResult(true, List.of());
        });
        ToolStudioIntegrationService integration = integrationService(drafts, validator, catalog, snapshots);

        IntegrationEnvelope<GraphDraftIntegrationBundle> exported = integration.exportDraft(
                draft.draftId(), draft.revision(), integrationContext("corr-unrelated-drift"));

        assertThat(exported.payload().dependencyProfile().snapshot().consistencyStatus()).isEqualTo("STABLE");
        assertThat(exported.payload().dependencyProfile().snapshot().contractSuiteCount()).isEqualTo(1);
    }

    private static GraphDraftDependencyProfile profile(
            GraphDraft draft, GraphDraftDependencySnapshotService.Snapshot snapshot) {
        GraphDraftDependencyReport report = GraphDraftDependencyReport.from(draft, snapshot.catalog());
        return GraphDraftDependencyProfile.from(draft, report, snapshot);
    }

    private static Map<String, String> readinessStates(GraphDraftDependencyProfile profile) {
        Map<String, String> states = new LinkedHashMap<>();
        profile.operatorDependencies().forEach(dependency ->
                states.put(dependency.operatorRef(), dependency.readiness().state()));
        return states;
    }

    private static GraphDraftDependencySnapshotService service(
            VisualOperatorCatalog catalog,
            InMemoryOperatorLibraryRegistry libraries,
            VisualRuntimeBindingImplementationRepository bindings,
            VisualRuntimeAdapterActivationRepository activations,
            VisualOperatorContractTestSuiteRepository suites) {
        return new GraphDraftDependencySnapshotService(catalog, libraries, bindings, activations, suites);
    }

    private static ToolStudioIntegrationService integrationService(
            GraphDraftRepository drafts,
            GraphDraftValidator validator,
            VisualOperatorCatalog catalog,
            GraphDraftDependencySnapshotService snapshots) {
        return new ToolStudioIntegrationService(drafts, validator, catalog, null,
                new InMemoryGovernanceGateResultRepository(), new ObjectMapper().findAndRegisterModules(),
                IntegrationIdentityResolver.unavailable(), new SideEffectReconcilerRegistry(List.of()), snapshots);
    }

    private static IntegrationRequestContext integrationContext(String correlationId) {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "knowledge", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke-sync", "", "GOVERNANCE_EVIDENCE_INGESTION", correlationId);
    }

    private static VisualOperatorCatalog catalog(List<OperatorDefinition> operators) {
        Map<String, OperatorDefinition> values = new LinkedHashMap<>();
        operators.forEach(operator -> values.put(operator.operatorRef(), operator));
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return values.values().stream()
                        .filter(operator -> operator.policy().allows(
                                query.tenantId(), query.namespace(), query.environment()))
                        .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                        .toList();
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return Optional.ofNullable(values.get(operatorRef));
            }

            @Override
            public Map<String, String> operatorLibraryIdsByOperatorRef(boolean includeDeprecated) {
                Map<String, String> libraryIds = new LinkedHashMap<>();
                values.values().stream().sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                        .forEach(operator -> libraryIds.put(
                                operator.operatorRef(), operator.source().libraryId()));
                return libraryIds;
            }
        };
    }

    private static InMemoryOperatorLibraryRegistry libraries(OperatorLibrary... libraries) {
        InMemoryOperatorLibraryRegistry registry = new InMemoryOperatorLibraryRegistry();
        for (OperatorLibrary library : libraries) {
            registry.upsert(library);
        }
        return registry;
    }

    private static OperatorLibrary library(String id, String version, String status, OperatorDefinition operator) {
        return new OperatorLibrary("", id, id, version, "owner-" + id, status, List.of(operator));
    }

    private static OperatorDefinition operator(String ref,
                                               String libraryId,
                                               String loweringMode,
                                               OperatorDefinition.Policy policy,
                                               String description) {
        SchemaEnvelope scalar = new SchemaEnvelope("json-schema", "2020-12", Map.of("type", "string"));
        return new OperatorDefinition("", ref, "1.0.0",
                new OperatorDefinition.Display(ref, description, List.of("risk")),
                new OperatorDefinition.Source("user-library", "", "", "", false, libraryId),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input", scalar, true, "")),
                        List.of(new OperatorDefinition.Port("output", scalar, true, ""))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(), policy,
                new OperatorDefinition.Lowering(loweringMode,
                        "native".equals(loweringMode) ? ref : "", Map.of()), List.of());
    }

    private static GraphDraft draft(List<OperatorDefinition> snapshots) {
        List<GraphDraft.DraftNode> nodes = new ArrayList<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        Map<String, OperatorDefinition> operators = new LinkedHashMap<>();
        int index = 0;
        for (OperatorDefinition operator : snapshots) {
            String nodeId = "node" + ++index;
            nodes.add(new GraphDraft.DraftNode(nodeId, operator.operatorRef(), operator.display().name(),
                    Map.of(), Map.of(), new GraphDraft.Position(index * 160, 100)));
            fingerprints.put(nodeId, operator.fingerprint());
            operators.put(nodeId, operator);
        }
        return new GraphDraft("", "draft-snapshot", 7, "snapshotGraph",
                "tenant-a", "knowledge", "prod", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), nodes, List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection(nodes.getLast().id(), "output"), fingerprints, operators,
                new GraphDraft.RevisionMetadata(
                        "2026-07-12T00:00:00Z", "author", "2026-07-13T00:00:00Z", "author",
                        "test", "snapshot", List.of(), ""));
    }

    private static VisualRuntimeBindingImplementationBinding binding(
            OperatorDefinition operator, String id, long revision, String state) {
        Instant time = Instant.parse("2026-07-13T00:00:00Z");
        return new VisualRuntimeBindingImplementationBinding("", id, revision, state, "success",
                operator.operatorRef(), operator.fingerprint(), "sha256:handoff", List.of(),
                null, null, null, "", "", List.of(), time, time);
    }

    private static VisualRuntimeAdapterActivation activation(
            VisualRuntimeBindingImplementationBinding binding,
            String id,
            long revision,
            String state,
            String environment,
            String health) {
        Instant time = Instant.parse("2026-07-13T00:00:00Z");
        return new VisualRuntimeAdapterActivation("", id, revision, state, "success",
                binding.bindingId(), binding.revision(), binding.operatorRef(), binding.operatorFingerprint(),
                "remote-worker", "worker://risk", "runtime-team", environment, health,
                "runtime-platform", "test", "", List.of(), time, time);
    }

    private static VisualOperatorContractTestSuite suite(String id, String operatorRef, int caseCount) {
        List<VisualOperatorContractTestCase> cases = java.util.stream.IntStream.range(0, caseCount)
                .mapToObj(index -> new VisualOperatorContractTestCase(
                        "case-" + index, Map.of(), Map.of(), Map.of(), Map.of()))
                .toList();
        return new VisualOperatorContractTestSuite(id, id, "", List.of("regression"),
                new VisualOperatorContractTestSuiteRequest(operatorRef, cases));
    }

    private static Map<String, VisualOperatorContractTestSuite> linkedSuites(
            VisualOperatorContractTestSuite... suites) {
        Map<String, VisualOperatorContractTestSuite> values = new LinkedHashMap<>();
        for (VisualOperatorContractTestSuite suite : suites) {
            values.put(suite.suiteId(), suite);
        }
        return values;
    }

    private record FixedBindingRepository(List<VisualRuntimeBindingImplementationBinding> values)
            implements VisualRuntimeBindingImplementationRepository {
        private FixedBindingRepository {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override public Collection<VisualRuntimeBindingImplementationBinding> all() { return values; }
        @Override public Optional<VisualRuntimeBindingImplementationBinding> find(String id) {
            return values.stream().filter(value -> value.bindingId().equals(id)).findFirst();
        }
        @Override public VisualRuntimeBindingImplementationBinding create(
                VisualRuntimeBindingImplementationBinding binding) { throw new UnsupportedOperationException(); }
        @Override public VisualRuntimeBindingImplementationBinding update(
                VisualRuntimeBindingImplementationBinding binding) { throw new UnsupportedOperationException(); }
    }

    private record FixedActivationRepository(List<VisualRuntimeAdapterActivation> values)
            implements VisualRuntimeAdapterActivationRepository {
        private FixedActivationRepository {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override public Collection<VisualRuntimeAdapterActivation> all() { return values; }
        @Override public Optional<VisualRuntimeAdapterActivation> find(String id) {
            return values.stream().filter(value -> value.activationId().equals(id)).findFirst();
        }
        @Override public VisualRuntimeAdapterActivation create(
                VisualRuntimeAdapterActivation activation) { throw new UnsupportedOperationException(); }
        @Override public VisualRuntimeAdapterActivation update(
                VisualRuntimeAdapterActivation activation) { throw new UnsupportedOperationException(); }
    }

    private static final class FixedSuiteRepository implements VisualOperatorContractTestSuiteRepository {
        private final Map<String, VisualOperatorContractTestSuite> values;
        private final Map<String, Long> revisions;

        private FixedSuiteRepository(Map<String, VisualOperatorContractTestSuite> values,
                                     Map<String, Long> revisions) {
            this.values = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
            this.revisions = revisions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(revisions);
        }

        @Override public Collection<VisualOperatorContractTestSuite> all() { return values.values(); }
        @Override public Optional<VisualOperatorContractTestSuite> find(String id) {
            return Optional.ofNullable(values.get(id));
        }
        @Override public long revision(String id) { return revisions.getOrDefault(id, 0L); }
        @Override public VisualOperatorContractTestSuite save(VisualOperatorContractTestSuite suite) {
            values.put(suite.suiteId(), suite);
            revisions.merge(suite.suiteId(), 1L, Long::sum);
            return suite;
        }
    }
}
