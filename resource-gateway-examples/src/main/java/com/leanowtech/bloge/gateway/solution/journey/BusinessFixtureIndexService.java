package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutableSnapshot;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a payload-free Fixture catalog for the Feature and Instruction closure of one Solution.
 *
 * <p>The projection joins only catalog descriptors and reverse-usage coordinates. Protected
 * material references and values never cross this boundary; an authorized human must use the
 * dedicated no-store material endpoint to inspect them.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "gateway.testing.correctness",
        name = "enabled",
        havingValue = "true")
public final class BusinessFixtureIndexService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_FIXTURES = 10_000;

    private final SolutionEntityRegistry registry;
    private final FixtureAssetRepository fixtures;
    private final FixtureMaterialService materials;

    /** Creates a Solution-scoped projection over canonical contracts and Fixture metadata. */
    @Autowired
    public BusinessFixtureIndexService(
            SolutionEntityRegistry registry,
            FixtureAssetRepository fixtures,
            ObjectProvider<FixtureMaterialService> materials) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.materials = materials.getIfAvailable();
    }

    /** Creates a metadata-only focused service. Protected reads remain unavailable. */
    public BusinessFixtureIndexService(
            SolutionEntityRegistry registry, FixtureAssetRepository fixtures) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.materials = null;
    }

    /**
     * Lists every Feature and Instruction in one frozen Solution closure with its related Fixture
     * descriptor heads. Empty groups are retained so the control panel can show missing assets.
     */
    public List<CapabilityFixtures> listForSolution(
            String solutionRef, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        SolutionExecutableSnapshot closure = registry.freezeExecutable(
                AgentTddMutationService.scopeKey(identity), required(solutionRef, "solutionRef"));
        EnterpriseScope scope = new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());

        LinkedHashMap<CapabilityKey, MutableGroup> groups = groups(closure);
        for (StoredFixtureAsset stored : descriptorHeads(scope)) {
            Set<String> relatedRefs = relatedRefs(scope, stored);
            groups.values().stream().filter(group -> group.matches(relatedRefs))
                    .forEach(group -> group.fixtures.add(summary(scope, stored)));
        }
        return groups.values().stream().map(MutableGroup::freeze).toList();
    }

    /**
     * Resolves one Fixture body only after proving that its descriptor belongs to the frozen
     * Solution closure and that the human has sufficient clearance.
     *
     * <p>The returned view omits the material receipt. The underlying vault audit attributes the
     * read to the human actor even though an internal purpose is used to cross the material
     * boundary.</p>
     */
    public FixtureMaterialView readMaterialForSolution(
            String solutionRef, String fixtureAssetId, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (materials == null) throw new com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE", "Protected Fixture material is unavailable.");
        String fixtureId = required(fixtureAssetId, "fixtureAssetId");
        SolutionExecutableSnapshot closure = registry.freezeExecutable(
                AgentTddMutationService.scopeKey(identity), required(solutionRef, "solutionRef"));
        EnterpriseScope scope = new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
        Set<String> closureRefs = groups(closure).values().stream()
                .flatMap(group -> group.relatedRefs.stream()).collect(java.util.stream.Collectors.toSet());
        StoredFixtureAsset stored = descriptorHeads(scope).stream()
                .filter(candidate -> fixtureId.equals(candidate.descriptor().fixtureAssetId()))
                .filter(candidate -> relatedRefs(scope, candidate).stream().anyMatch(closureRefs::contains))
                .findFirst()
                .orElseThrow(() -> new com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException(
                        "REFERENCE_UNRESOLVED", "Fixture is not part of the requested Solution."));
        var descriptor = stored.descriptor();
        if (!identity.hasClearanceAtLeast(descriptor.classification())) {
            throw new com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException(
                    "GOLDEN_REVIEW_CLEARANCE_FORBIDDEN",
                    "The human clearance is insufficient for this protected Fixture material.");
        }
        try {
            var material = materials.read(descriptor.materialRef().id(), descriptor.materialRef().revision(),
                    descriptor.materialRef().fingerprint(), materialReader(identity));
            return new FixtureMaterialView(fixtureId, descriptor.name(), descriptor.variantKey(),
                    descriptor.classification(), material.payload());
        } catch (FixtureMaterialCommandException failure) {
            throw new com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException(
                    "FIXTURE_MATERIAL_UNAVAILABLE", "Protected Fixture material could not be resolved.");
        }
    }

    private static IntegrationRequestContext materialReader(IntegrationRequestContext identity) {
        return new IntegrationRequestContext(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region(), "PLATFORM",
                identity.actorId(), identity.actorId(), FixtureMaterialService.RESOLVE_PURPOSE,
                identity.correlationId(), Set.of(), identity.clearance(), "");
    }

    private LinkedHashMap<CapabilityKey, MutableGroup> groups(SolutionExecutableSnapshot closure) {
        LinkedHashMap<CapabilityKey, MutableGroup> groups = new LinkedHashMap<>();
        closure.contracts().features().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    FeatureContract feature = entry.getValue();
                    groups.put(new CapabilityKey("FEATURE", entry.getKey()), new MutableGroup(
                            "FEATURE", entry.getKey(), feature.businessSemantics(),
                            refs(entry.getKey(), feature.evaluationRef())));
                });
        closure.contracts().instructions().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    InstructionContract instruction = entry.getValue();
                    groups.put(new CapabilityKey("INSTRUCTION", entry.getKey()), new MutableGroup(
                            "INSTRUCTION", entry.getKey(), instruction.businessSemantics(),
                            refs(entry.getKey(), instruction.bindingRef())));
                });
        return groups;
    }

    private List<StoredFixtureAsset> descriptorHeads(EnterpriseScope scope) {
        ArrayList<StoredFixtureAsset> values = new ArrayList<>();
        for (int offset = 0; offset < MAX_FIXTURES; offset += PAGE_SIZE) {
            List<StoredFixtureAsset> page = fixtures.listHeads(scope, false, PAGE_SIZE, offset);
            values.addAll(page);
            if (page.size() < PAGE_SIZE) return List.copyOf(values);
        }
        throw new IllegalStateException("Business Fixture index exceeds its bounded catalog size");
    }

    private Set<String> relatedRefs(EnterpriseScope scope, StoredFixtureAsset stored) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (stored.descriptor().source().sourceRef() != null) {
            refs.add(stored.descriptor().source().sourceRef().id());
        }
        fixtures.usages(scope, stored.exactRef(), 1_000).forEach(
                usage -> refs.add(usage.consumerRef().id()));
        return Set.copyOf(refs);
    }

    private FixtureSummary summary(EnterpriseScope scope, StoredFixtureAsset stored) {
        var descriptor = stored.descriptor();
        return new FixtureSummary(descriptor.fixtureAssetId(), descriptor.revision(),
                descriptor.name(), descriptor.variantKey(), descriptor.lifecycle().name(),
                descriptor.classification(), descriptor.schemaRef().fingerprint(),
                fixtures.countUsages(scope, stored.exactRef()));
    }

    private static Set<String> refs(String logicalRef, String implementationRef) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        refs.add(logicalRef);
        if (implementationRef != null && !implementationRef.isBlank()) refs.add(implementationRef);
        return Set.copyOf(refs);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private record CapabilityKey(String kind, String ref) { }

    private static final class MutableGroup {
        private final String capabilityKind;
        private final String capabilityRef;
        private final String businessLabel;
        private final Set<String> relatedRefs;
        private final List<FixtureSummary> fixtures = new ArrayList<>();

        private MutableGroup(String capabilityKind, String capabilityRef, String businessLabel,
                             Set<String> relatedRefs) {
            this.capabilityKind = capabilityKind;
            this.capabilityRef = capabilityRef;
            this.businessLabel = businessLabel;
            this.relatedRefs = relatedRefs;
        }

        private boolean matches(Set<String> candidateRefs) {
            return candidateRefs.stream().anyMatch(relatedRefs::contains);
        }

        private CapabilityFixtures freeze() {
            fixtures.sort(Comparator.comparing(FixtureSummary::name)
                    .thenComparing(FixtureSummary::fixtureAssetId));
            return new CapabilityFixtures(capabilityKind, capabilityRef, businessLabel,
                    List.copyOf(fixtures));
        }
    }

    /** Payload-free Fixture groups for one business capability. */
    public record CapabilityFixtures(String capabilityKind, String capabilityRef,
                                     String businessLabel, List<FixtureSummary> fixtures) {
        /** Freezes the ordered Fixture list and rejects an incomplete capability coordinate. */
        public CapabilityFixtures {
            capabilityKind = required(capabilityKind, "capabilityKind");
            capabilityRef = required(capabilityRef, "capabilityRef");
            businessLabel = required(businessLabel, "businessLabel");
            fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
        }
    }

    /** Payload-free descriptor metadata for one current Fixture asset revision. */
    public record FixtureSummary(String fixtureAssetId, long revision, String name,
                                 String variantKey, String lifecycle, String classification,
                                 String schemaFingerprint, int usageCount) {
        /** Validates that the summary contains no material coordinate or value. */
        public FixtureSummary {
            fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
            name = required(name, "name");
            variantKey = required(variantKey, "variantKey");
            lifecycle = required(lifecycle, "lifecycle");
            classification = required(classification, "classification");
            schemaFingerprint = required(schemaFingerprint, "schemaFingerprint");
            if (revision < 1 || usageCount < 0) {
                throw new IllegalArgumentException("Fixture summary revision and usage are invalid");
            }
        }
    }

    /** Authorized no-store Fixture body without its vault receipt or exact material coordinate. */
    public record FixtureMaterialView(String fixtureAssetId, String name, String variantKey,
                                      String classification, Object payload) {
        /** Requires complete display metadata and a resolved protected payload. */
        public FixtureMaterialView {
            fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
            name = required(name, "name");
            variantKey = required(variantKey, "variantKey");
            classification = required(classification, "classification");
            Objects.requireNonNull(payload, "payload");
        }
    }
}
