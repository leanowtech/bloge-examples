package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.protocol.TestAssetReference;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRevision;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogDependencyResolver;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetGovernance;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetMetadata;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedPayloadOrigin;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedSecurityClassification;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizedWorldAssetResolverTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void deniedPrimaryReferenceAuthorizesBeforeAnyRepositoryRead() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository);

        assertThatThrownBy(() -> resolver.resolve(worldEnvelope(), context()))
                .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(GovernedAssetAccessException.Code.ACCESS_DENIED));
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class));
    }

    @Test
    void malformedContextPurposeCorrelationAndEnvironmentFailClosedBeforeAuthorization() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        List<GovernedResourceRef> authorized = new ArrayList<>();
        GovernedAssetReadAuthorizer authorizer = (context, ref) -> authorized.add(ref);
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository, authorizer);

        for (IntegrationRequestContext invalid : List.of(
                context("WRONG_PURPOSE", "test", "corr-1"),
                context("GRAPH_CONTRACT_TEST", "production", "corr-1"),
                context("GRAPH_CONTRACT_TEST", "test", "wrong-correlation"))) {
            assertThatThrownBy(() -> resolver.resolve(worldEnvelope(), invalid))
                    .isInstanceOf(GovernedAssetAccessException.class)
                    .extracting(value -> ((GovernedAssetAccessException) value).code())
                    .isEqualTo(GovernedAssetAccessException.Code.INVALID_CONTEXT);
        }
        assertThat(authorized).isEmpty();
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class));
    }

    @Test
    void authorizedWorldUsesIdentityTenantAndExactReference() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef stored = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedCatalogRepository repository = mockRepository(stored, world, null, world, List.of());
        List<GovernedResourceRef> authorized = new ArrayList<>();
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, ref) -> authorized.add(ref), (context, metadata) -> { });

        ResolvedWorldAssetControl result = resolver.resolve(worldEnvelope(world), context());

        assertThat(result.primaryRef()).isEqualTo(stored);
        assertThat(result.scenario()).isEmpty();
        assertThat(result.worldModel()).isSameAs(world);
        assertThat(authorized).containsExactly(stored);
    }

    @Test
    void coordinateOnlyConstructorDeniesAfterMetadataReadBeforePayload() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef ref = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedCatalogRepository repository = mockRepository(ref, world, null, world, List.of());
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, exactRef) -> { });

        assertThatThrownBy(() -> resolver.resolve(worldEnvelope(world), context()))
                .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(GovernedAssetAccessException.Code.ACCESS_DENIED));
        verify(repository).findMetadata(ref);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class));
    }

    @Test
    void untrustedMetadataIsIntegrityFailureBeforePolicyOrPayload() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef ref = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedResourceRef wrongRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, "other-world", 1, FINGERPRINT);
        GovernedAssetGovernance governance = GovernedAssetGovernance.safeDefaults();
        List<GovernedAssetMetadata> candidates = List.of(
                new GovernedAssetMetadata(wrongRef, governance,
                        GovernedAssetMetadata.fingerprint(wrongRef, governance)),
                new GovernedAssetMetadata(ref, governance, null),
                new GovernedAssetMetadata(ref, governance, "sha256:" + "f".repeat(64)));

        for (GovernedAssetMetadata candidate : candidates) {
            GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
            when(repository.findMetadata(ref)).thenReturn(Optional.of(candidate));
            List<GovernedAssetMetadata> authorized = new ArrayList<>();
            AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                    (context, exactRef) -> { }, (context, metadata) -> authorized.add(metadata));

            assertThatThrownBy(() -> resolver.resolve(worldEnvelope(world), context()))
                    .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    GovernedAssetAccessException.Code.INTEGRITY_FAILURE));
            verify(repository).findMetadata(ref);
            verify(repository, never()).findExact(any(GovernedResourceRef.class),
                    any(GovernedCatalogDependencyResolver.class));
            assertThat(authorized).isEmpty();
        }
    }

    @Test
    void authorizedScenarioAuthorizesScenarioThenWorldBeforeWorldRead() {
        ResourceWorldModel world = world("tenant-a");
        Scenario scenario = scenario(world);
        GovernedResourceRef worldRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedResourceRef scenarioRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario.revision(),
                scenario.fingerprint());
        List<String> events = new ArrayList<>();
        GovernedCatalogRepository repository = mockRepository(scenarioRef, scenario, worldRef, world,
                events);
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, ref) -> events.add("authorize:" + ref.kind().name()),
                (context, metadata) -> { });

        ResolvedWorldAssetControl result = resolver.resolve(scenarioEnvelope(scenario), context());

        assertThat(result.scenario()).containsSame(scenario);
        assertThat(result.worldModel()).isSameAs(world);
        assertThat(events).containsExactly(
                "authorize:SCENARIO", "authorize:RESOURCE_WORLD_MODEL", "read:RESOURCE_WORLD_MODEL");
    }

    @Test
    void worldDenialAfterScenarioAuthorizationDoesNotReadWorld() {
        ResourceWorldModel world = world("tenant-a");
        Scenario scenario = scenario(world);
        GovernedResourceRef scenarioRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario.revision(),
                scenario.fingerprint());
        GovernedResourceRef worldRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        List<String> events = new ArrayList<>();
        GovernedCatalogRepository repository = mockRepository(scenarioRef, scenario, worldRef, world, events);
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, ref) -> {
                    events.add("authorize:" + ref.kind().name());
                    if (ref.kind() == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
                        throw GovernedAssetAccessException.denied();
                    }
                }, (context, metadata) -> { });

        assertThatThrownBy(() -> resolver.resolve(scenarioEnvelope(scenario), context()))
                .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(GovernedAssetAccessException.Code.ACCESS_DENIED));
        assertThat(events).containsExactly("authorize:SCENARIO", "authorize:RESOURCE_WORLD_MODEL");
    }

    @Test
    void scenarioWrongFingerprintAuthorizesPrimaryOnlyAndDoesNotReadWorld() {
        ResourceWorldModel world = world("tenant-a");
        Scenario scenario = scenario(world);
        GovernedResourceRef worldRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedResourceRef scenarioRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario.revision(),
                scenario.fingerprint());
        List<String> events = new ArrayList<>();
        GovernedCatalogRepository repository = mockRepository(scenarioRef, scenario, worldRef, world,
                events);
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, ref) -> events.add("authorize:" + ref.kind().name()));
        TestControlEnvelope wrongFingerprint = new TestControlEnvelope("GRAPH_CONTRACT_TEST",
                new TestAssetReference(scenario.scenarioId(), scenario.revision(),
                        "sha256:" + "b".repeat(64)), null, "corr-1");

        assertThatThrownBy(() -> resolver.resolve(wrongFingerprint, context()))
                .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                GovernedAssetAccessException.Code.REFERENCE_NOT_FOUND));
        assertThat(events).containsExactly("authorize:SCENARIO");
    }

    @Test
    void wrongFingerprintMissingAndTypeDriftAreSanitized() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef ref = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedCatalogRepository missing = mockRepository(null, null, null, null, List.of());
        GovernedCatalogRepository wrongType = mock(GovernedCatalogRepository.class);
        Scenario scenario = scenario(world);
        when(wrongType.findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class))).thenReturn(
                java.util.Optional.of(new GovernedCatalogRevision(
                        new GovernedResourceRef("tenant-a", GovernedCatalogKind.SCENARIO,
                                scenario.scenarioId(), scenario.revision(), scenario.fingerprint()), scenario)));
        for (GovernedCatalogRepository repository : List.of(missing, wrongType)) {
            AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                    (context, exactRef) -> { });
            TestControlEnvelope envelope = new TestControlEnvelope(
                    "GRAPH_CONTRACT_TEST",
                    null,
                    new TestAssetReference(ref.id(), ref.revision(), "sha256:" + "b".repeat(64)),
                    "corr-1");
            assertThatThrownBy(() -> resolver.resolve(envelope, context()))
                    .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                            assertThat(failure.code()).isIn(
                                    GovernedAssetAccessException.Code.REFERENCE_NOT_FOUND,
                                    GovernedAssetAccessException.Code.INTEGRITY_FAILURE));
        }
    }

    @Test
    void metadataDenialReadsMetadataButNeverPayload() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef ref = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedCatalogRepository repository = mockRepository(ref, world, null, world, List.of());
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, exactRef) -> { }, (context, metadata) -> {
                    throw GovernedAssetAccessException.denied();
                });

        assertThatThrownBy(() -> resolver.resolve(worldEnvelope(world), context()))
                .isInstanceOfSatisfying(GovernedAssetAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(GovernedAssetAccessException.Code.ACCESS_DENIED));
        verify(repository).findMetadata(ref);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class));
    }

    @Test
    void fixedClockExpiryDeniesBeforePayloadAndIsDeterministic() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef ref = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedAssetGovernance expired = new GovernedAssetGovernance(GovernedPayloadOrigin.REDACTED,
                GovernedSecurityClassification.INTERNAL, Instant.parse("2026-01-01T00:00:00Z"),
                "policy:expired", null);
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        when(repository.findMetadata(ref)).thenReturn(Optional.of(
                new GovernedAssetMetadata(ref, expired, GovernedAssetMetadata.fingerprint(ref, expired))));
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, exactRef) -> { }, (context, metadata) -> { },
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));

        assertThatThrownBy(() -> resolver.resolve(worldEnvelope(world), context()))
                .isInstanceOf(GovernedAssetAccessException.class);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class));
    }

    @Test
    void authorizedRealWorldAuthorizerSeesExactMetadata() {
        ResourceWorldModel world = world("tenant-a");
        GovernedResourceRef ref = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedAssetGovernance real = new GovernedAssetGovernance(GovernedPayloadOrigin.REAL,
                GovernedSecurityClassification.RESTRICTED, Instant.parse("2026-12-01T00:00:00Z"),
                "policy:real-world", "approval:ticket-1");
        GovernedAssetMetadata metadata = new GovernedAssetMetadata(ref, real,
                GovernedAssetMetadata.fingerprint(ref, real));
        GovernedCatalogRepository repository = mockRepository(ref, world, null, world, List.of());
        when(repository.findMetadata(ref)).thenReturn(Optional.of(metadata));
        List<GovernedAssetMetadata> seen = new ArrayList<>();
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, exactRef) -> { }, (context, exactMetadata) -> seen.add(exactMetadata),
                Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), java.time.ZoneOffset.UTC));

        assertThat(resolver.resolve(worldEnvelope(world), context()).worldModel()).isSameAs(world);
        assertThat(seen).containsExactly(metadata);
    }

    @Test
    void scenarioAuthorizationIsCoordinateMetadataPayloadForScenarioThenWorld() {
        ResourceWorldModel world = world("tenant-a");
        Scenario scenario = scenario(world);
        GovernedResourceRef worldRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedResourceRef scenarioRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario.revision(),
                scenario.fingerprint());
        List<String> events = new ArrayList<>();
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        when(repository.findMetadata(any(GovernedResourceRef.class))).thenAnswer(invocation -> {
            GovernedResourceRef ref = invocation.getArgument(0);
            events.add("metadata:" + ref.kind().name());
            return Optional.of(new GovernedAssetMetadata(ref, GovernedAssetGovernance.safeDefaults(),
                    GovernedAssetMetadata.fingerprint(ref, GovernedAssetGovernance.safeDefaults())));
        });
        when(repository.findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class))).thenAnswer(invocation -> {
            GovernedResourceRef ref = invocation.getArgument(0);
            events.add("payload:" + ref.kind().name());
            if (ref.equals(worldRef)) {
                return Optional.of(new GovernedCatalogRevision(ref, world));
            }
            invocation.<GovernedCatalogDependencyResolver>getArgument(1).resolve(worldRef);
            return Optional.of(new GovernedCatalogRevision(ref, scenario));
        });
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, ref) -> events.add("coordinate:" + ref.kind().name()),
                (context, metadata) -> { });

        resolver.resolve(scenarioEnvelope(scenario), context());

        assertThat(events).containsExactly("coordinate:SCENARIO", "metadata:SCENARIO",
                "payload:SCENARIO", "coordinate:RESOURCE_WORLD_MODEL", "metadata:RESOURCE_WORLD_MODEL",
                "payload:RESOURCE_WORLD_MODEL");
    }

    @Test
    void nestedWorldMetadataDenialDoesNotReadWorldPayload() {
        ResourceWorldModel world = world("tenant-a");
        Scenario scenario = scenario(world);
        GovernedResourceRef worldRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world.revision(),
                world.fingerprint());
        GovernedResourceRef scenarioRef = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario.revision(),
                scenario.fingerprint());
        List<String> events = new ArrayList<>();
        GovernedCatalogRepository repository = mockRepository(scenarioRef, scenario, worldRef, world, events);
        AuthorizedWorldAssetResolver resolver = new AuthorizedWorldAssetResolver(repository,
                (context, ref) -> { }, (context, metadata) -> {
                    if (metadata.exactRef().equals(worldRef)) {
                        throw GovernedAssetAccessException.denied();
                    }
                });

        assertThatThrownBy(() -> resolver.resolve(scenarioEnvelope(scenario), context()))
                .isInstanceOf(GovernedAssetAccessException.class);
        assertThat(events).doesNotContain("read:RESOURCE_WORLD_MODEL");
    }

    @Test
    void failuresDoNotContainPayloadContextOrFingerprints() {
        String secret = "scenario-secret-world-dsl-expectation-header";
        GovernedAssetAccessException failure = GovernedAssetAccessException.denied();

        assertThat(failure.toString()).doesNotContain(secret, FINGERPRINT)
                .contains(failure.errorCode());
    }

    private static GovernedCatalogRepository mockRepository(GovernedResourceRef primaryRef,
                                                              Object primaryValue,
                                                              GovernedResourceRef worldRef,
                                                              ResourceWorldModel world,
                                                              List<String> events) {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        when(repository.findMetadata(any(GovernedResourceRef.class))).thenAnswer(invocation -> {
            GovernedResourceRef requested = invocation.getArgument(0);
            if ((primaryRef != null && primaryRef.equals(requested))
                    || (worldRef != null && worldRef.equals(requested))) {
                return java.util.Optional.of(new GovernedAssetMetadata(requested,
                        GovernedAssetGovernance.safeDefaults(),
                        GovernedAssetMetadata.fingerprint(requested,
                                GovernedAssetGovernance.safeDefaults())));
            }
            return java.util.Optional.empty();
        });
        when(repository.findExact(any(GovernedResourceRef.class),
                any(GovernedCatalogDependencyResolver.class))).thenAnswer(invocation -> {
            GovernedResourceRef requested = invocation.getArgument(0);
            GovernedCatalogDependencyResolver dependency = invocation.getArgument(1);
            if (worldRef != null && worldRef.equals(requested)) {
                if (events != null) {
                    events.add("read:RESOURCE_WORLD_MODEL");
                }
                return java.util.Optional.of(new GovernedCatalogRevision(requested, world));
            }
            if (primaryRef == null || !primaryRef.equals(requested)) {
                return java.util.Optional.empty();
            }
            if (requested.kind() == GovernedCatalogKind.SCENARIO) {
                dependency.resolve(worldRef);
            }
            return java.util.Optional.of(new GovernedCatalogRevision(requested, primaryValue));
        });
        return repository;
    }

    private static TestControlEnvelope worldEnvelope() {
        return new TestControlEnvelope("GRAPH_CONTRACT_TEST", null,
                new TestAssetReference("world-1", 1, FINGERPRINT), "corr-1");
    }

    private static TestControlEnvelope worldEnvelope(ResourceWorldModel world) {
        return new TestControlEnvelope("GRAPH_CONTRACT_TEST", null,
                new TestAssetReference(world.worldModelId(), world.revision(), world.fingerprint()), "corr-1");
    }

    private static TestControlEnvelope scenarioEnvelope(Scenario scenario) {
        return new TestControlEnvelope("GRAPH_CONTRACT_TEST",
                new TestAssetReference(scenario.scenarioId(), scenario.revision(), scenario.fingerprint()),
                null, "corr-1");
    }

    private static IntegrationRequestContext context() {
        return context("GRAPH_CONTRACT_TEST", "test", "corr-1");
    }

    private static IntegrationRequestContext context(String purpose, String environment,
                                                      String correlation) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", environment, "sg",
                "USER", "actor-a", "", purpose, correlation, Set.of(), "PUBLIC", "");
    }

    private static Scenario scenario(ResourceWorldModel world) {
        return new Scenario("scenario-1", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", "graph-1", FINGERPRINT), world,
                Map.of("input", "value"), Scenario.WorldStateInit.EMPTY,
                List.of(new Scenario.Expectation("OUTPUT_PATH", "", "/result", "EQUALS", "ok", null)));
    }

    private static ResourceWorldModel world(String tenant) {
        LogicalResourceContract contract = new LogicalResourceContract("contract-1",
                SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")),
                SchemaEnvelope.object(Map.of("result", Map.of("type", "string")), List.of("result")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of(),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        LogicalResourceBinding binding = LogicalResourceBinding.bind("provider", "v1",
                new ResourceDesignContract(contract.contractId(), "resource-1", "Resource", "",
                        List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE"),
                new VisualResourceDescriptor("resource-1", "https://example.test/{id}", "GET", Map.of(),
                        null, Duration.ofSeconds(2), new VisualResourceParameterMapping(Map.of(), Map.of(), null),
                        new VisualResourceResponseProtocol.HttpStatus(), "data"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(tenant, "provider", "v1",
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("world.bloge", "graph customerWorld { }"),
                StateSpec.empty());
        return new ResourceWorldModel("world-1", tenant, 1, List.of(slice));
    }
}
