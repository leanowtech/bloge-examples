package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput.MaterializedFixture;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.MaterialAccessContext;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.ResolvedFixtureMaterial;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredAssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Resolves one exact authoring closure and delegates deterministic work to the pure compiler. */
public final class CorrectnessCompilationService {

    public static final String PURPOSE = "TEST_SCENARIO_PUBLISH";

    private final CorrectnessDefinitionRepository definitions;
    private final CoverageInventoryRepository inventories;
    private final BusinessOracleRepository oracles;
    private final AssertionSetRepository assertionSets;
    private final ScenarioDraftSetV2Repository scenarios;
    private final FixtureAssetRepository fixtures;
    private final FixtureMaterialResolver materials;
    private final CorrectnessCompiler compiler;
    private final ObjectMapper mapper;

    public CorrectnessCompilationService(
            CorrectnessDefinitionRepository definitions,
            CoverageInventoryRepository inventories,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets,
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            FixtureMaterialResolver materials,
            CorrectnessCompiler compiler,
            ObjectMapper mapper
    ) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.oracles = Objects.requireNonNull(oracles, "oracles");
        this.assertionSets = Objects.requireNonNull(assertionSets, "assertionSets");
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns only the payload-free report suitable for a compile-preview response. */
    public CorrectnessCompilationReport compile(
            CompilationCoordinate coordinate,
            IntegrationRequestContext identity
    ) {
        return compilePlan(coordinate, identity).report();
    }

    /** Internal publication path retaining payload-bearing registration requests in memory only. */
    CompiledCorrectnessPlan compilePlan(
            CompilationCoordinate coordinate,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        return compiler.compile(resolve(coordinate, identity));
    }

    FrozenCompilationInput resolve(
            CompilationCoordinate coordinate,
            IntegrationRequestContext identity
    ) {
        Objects.requireNonNull(coordinate, "coordinate");
        EnterpriseScope scope = scope(identity);
        CorrectnessDefinition definition = definition(scope, coordinate.definitionRef());
        CoverageInventory inventory = inventory(scope, coordinate.inventoryRef());
        ScenarioDraftSetV2 scenarioSet = scenario(scope, coordinate.scenarioDraftSetRef());
        List<BusinessOracle> resolvedOracles = coordinate.oracleRefs().stream()
                .map(ref -> oracle(scope, ref)).toList();
        List<AssertionSet> resolvedAssertions = coordinate.assertionSetRefs().stream()
                .map(ref -> assertionSet(scope, ref)).toList();
        List<MaterializedFixture> resolvedFixtures = new ArrayList<>();
        MaterialAccessContext materialAccess = new MaterialAccessContext(
                identity.actorId(), FixtureMaterialService.RESOLVE_PURPOSE,
                identity.correlationId(), identity.clearance());
        for (ExactAssetRef ref : coordinate.fixtureAssetRefs()) {
            FixtureAssetDescriptor descriptor = fixture(scope, ref);
            ResolvedFixtureMaterial material = callStore(() -> materials.resolve(
                    scope, descriptor.materialRef(), materialAccess));
            requireMaterialClosure(coordinate, ref, descriptor, material);
            resolvedFixtures.add(new MaterializedFixture(
                    ref, descriptor, material.materialRef(), material.payload()));
        }
        return new FrozenCompilationInput(
                scope, coordinate, definition, inventory, scenarioSet,
                resolvedOracles, resolvedAssertions, resolvedFixtures);
    }

    private CorrectnessDefinition definition(EnterpriseScope scope, ExactAssetRef ref) {
        requireKind(ref, "DEFINITION");
        StoredCorrectnessDefinition stored = callStore(() -> definitions.findRevision(
                scope, ref.id(), ref.revision()).orElseThrow(() -> notFound(ref)));
        if (!scope.equals(stored.definition().scope())) throw notFound(ref);
        requireExact(
                ref, stored.definition().definitionId(), stored.definition().revision(),
                stored.definitionFingerprint(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, stored.definition()));
        return stored.definition();
    }

    private CoverageInventory inventory(EnterpriseScope scope, ExactAssetRef ref) {
        requireKind(ref, "INVENTORY");
        StoredCoverageInventory stored = callStore(() -> inventories.findRevision(
                scope, ref.id(), ref.revision()).orElseThrow(() -> notFound(ref)));
        if (!scope.equals(stored.inventory().scope())) throw notFound(ref);
        requireExact(
                ref, stored.inventory().inventoryId(), stored.inventory().revision(),
                stored.inventoryFingerprint(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, stored.inventory()));
        return stored.inventory();
    }

    private ScenarioDraftSetV2 scenario(EnterpriseScope scope, ExactAssetRef ref) {
        requireKind(ref, "SCENARIO_DRAFT_SET");
        StoredScenarioDraftSetV2 stored = callStore(() -> scenarios.findRevision(
                scope, ref.id(), ref.revision()).orElseThrow(() -> notFound(ref)));
        if (!scope.equals(stored.scenarioDraftSet().scope())) throw notFound(ref);
        requireExact(
                ref, stored.scenarioDraftSet().scenarioDraftSetId(),
                stored.scenarioDraftSet().revision(), stored.scenarioDraftSetFingerprint(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, stored.scenarioDraftSet()));
        return stored.scenarioDraftSet();
    }

    private BusinessOracle oracle(EnterpriseScope scope, ExactAssetRef ref) {
        requireKind(ref, "ORACLE");
        StoredBusinessOracle stored = callStore(() -> oracles.findRevision(
                scope, ref.id(), ref.revision()).orElseThrow(() -> notFound(ref)));
        if (!scope.equals(stored.oracle().scope())) throw notFound(ref);
        requireExact(
                ref, stored.oracle().oracleId(), stored.oracle().revision(),
                stored.oracleFingerprint(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, stored.oracle()));
        return stored.oracle();
    }

    private AssertionSet assertionSet(EnterpriseScope scope, ExactAssetRef ref) {
        requireKind(ref, "ASSERTION_SET");
        StoredAssertionSet stored = callStore(() -> assertionSets.findRevision(
                scope, ref.id(), ref.revision()).orElseThrow(() -> notFound(ref)));
        if (!scope.equals(stored.scope())) {
            throw notFound(ref);
        }
        requireExact(
                ref, stored.assertionSet().assertionSetId(), stored.assertionSet().revision(),
                stored.assertionSetFingerprint(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, stored.assertionSet()));
        return stored.assertionSet();
    }

    private FixtureAssetDescriptor fixture(EnterpriseScope scope, ExactAssetRef ref) {
        requireKind(ref, "FIXTURE_ASSET");
        StoredFixtureAsset stored = callStore(() -> fixtures.findRevision(
                scope, ref.id(), ref.revision()).orElseThrow(() -> notFound(ref)));
        if (!scope.equals(stored.descriptor().scope())) throw notFound(ref);
        requireExact(
                ref, stored.descriptor().fixtureAssetId(), stored.descriptor().revision(),
                stored.descriptorFingerprint(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, stored.descriptor()));
        return stored.descriptor();
    }

    private static void requireMaterialClosure(
            CompilationCoordinate coordinate,
            ExactAssetRef fixtureRef,
            FixtureAssetDescriptor descriptor,
            ResolvedFixtureMaterial material
    ) {
        Receipt receipt = material.receipt();
        FixtureSubject expectedSubject;
        try {
            expectedSubject = FixtureSubject.valueOf(coordinate.target().kind().name());
        } catch (IllegalArgumentException unsupported) {
            throw conflict("RG.CORRECTNESS.FIXTURE_TARGET_UNSUPPORTED",
                    "Fixture material target kind cannot be compiled");
        }
        if (!material.materialRef().equals(descriptor.materialRef())
                || !receipt.materialRef().equals(descriptor.materialRef())
                || !receipt.fixtureAssetId().equals(descriptor.fixtureAssetId())
                || !receipt.target().equals(coordinate.target())
                || receipt.subject() != expectedSubject
                || !receipt.schemaRef().equals(descriptor.schemaRef())
                || !receipt.classification().equals(descriptor.classification())
                || !receipt.source().equals(descriptor.source())
                || !receipt.redaction().equals(descriptor.redaction())
                || !receipt.retention().equals(descriptor.retention())) {
            throw conflict(
                    "RG.CORRECTNESS.FIXTURE_MATERIAL_CLOSURE_DRIFT",
                    "Fixture descriptor and protected material receipt no longer identify one exact asset");
        }
        if (!fixtureRef.id().equals(receipt.fixtureAssetId())) {
            throw conflict(
                    "RG.CORRECTNESS.FIXTURE_MATERIAL_CLOSURE_DRIFT",
                    "Fixture descriptor identity differs from its material receipt");
        }
    }

    private static void requireExact(
            ExactAssetRef ref,
            String id,
            long revision,
            String storedFingerprint,
            String computedFingerprint
    ) {
        if (!ref.id().equals(id) || ref.revision() != revision
                || !ref.fingerprint().equals(storedFingerprint)
                || !ref.fingerprint().equals(computedFingerprint)) {
            throw conflict(
                    "RG.CORRECTNESS.REFERENCE_DRIFTED",
                    "An exact correctness asset reference failed immutable-content verification");
        }
    }

    private static void requireKind(ExactAssetRef ref, String kind) {
        if (ref == null || !kind.equals(ref.kind())) {
            throw new CorrectnessCompilationException(
                    400, "RG.CORRECTNESS.REFERENCE_KIND_INVALID",
                    "Compilation coordinate contains an invalid asset kind", false);
        }
    }

    private static CorrectnessCompilationException notFound(ExactAssetRef ref) {
        return new CorrectnessCompilationException(
                404, "RG.CORRECTNESS.REFERENCE_NOT_FOUND",
                "An exact correctness asset was not found in the authorized scope", false);
    }

    private static CorrectnessCompilationException conflict(String code, String message) {
        return new CorrectnessCompilationException(409, code, message, false);
    }

    private static <T> T callStore(Supplier<T> call) {
        try {
            return call.get();
        } catch (CorrectnessCompilationException known) {
            throw known;
        } catch (com.leanowtech.bloge.gateway.testing.correctness.fixture
                .FixtureMaterialCommandException known) {
            throw new CorrectnessCompilationException(
                    known.status(), known.code(), "Fixture material could not be resolved",
                    known.status() >= 500);
        } catch (RuntimeException unavailable) {
            throw new CorrectnessCompilationException(
                    503, "RG.CORRECTNESS.COMPILATION_STORE_UNAVAILABLE",
                    "Correctness compilation dependencies are unavailable", true);
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        if (identity == null) {
            throw new CorrectnessCompilationException(
                    401, "RG.CORRECTNESS.COMPILATION_AUTH_REQUIRED",
                    "Verified publication identity is required", false);
        }
        identity.requireComplete();
        if (!PURPOSE.equals(identity.purpose())) {
            throw new CorrectnessCompilationException(
                    403, "RG.CORRECTNESS.COMPILATION_PURPOSE_FORBIDDEN",
                    "Correctness compilation requires the publication purpose", false);
        }
        String environment = identity.environmentId().toLowerCase(Locale.ROOT);
        if (identity.projectId().isBlank() || identity.region().isBlank()
                || (!"test".equals(environment) && !"staging".equals(environment))) {
            throw new CorrectnessCompilationException(
                    403, "RG.CORRECTNESS.COMPILATION_SCOPE_FORBIDDEN",
                    "Correctness compilation requires a complete test or staging scope", false);
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }
}
