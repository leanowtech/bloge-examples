package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.MaterialAccessContext;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialRepository.AccessAudit;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Protected material adapter for complete Feature controlled suites.
 *
 * <p>All case ids, business intent, inputs, dependency values, expected outputs, library references,
 * coverage labels, and the candidate evaluation reference are encrypted together. Callers receive
 * only an exact vault receipt; disabled material infrastructure never falls back to plaintext.</p>
 */
@Service
public final class FeatureControlledMaterialStore {
    /** Dedicated internal purpose for payload-free suite material reconciliation. */
    public static final String RECONCILE_PURPOSE = "AGENT_TDD_FEATURE_SUITE_RECONCILE";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int RETENTION_DAYS = 365;
    private static final String MATERIAL_PREFIX = "feature-suite-";
    private final FixtureMaterialService materials;
    private final FixtureMaterialRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Creates the production adapter while preserving fail-closed optional vault startup. */
    @Autowired
    public FeatureControlledMaterialStore(
            ObjectProvider<FixtureMaterialService> materials,
            ObjectProvider<FixtureMaterialRepository> repositories,
            ObjectMapper mapper) {
        this(materials.getIfAvailable(), repositories.getIfAvailable(), mapper, Clock.systemUTC());
    }

    /** Creates a focused adapter with an explicit encrypted material vault. */
    public FeatureControlledMaterialStore(FixtureMaterialService materials, ObjectMapper mapper) {
        this(materials, null, mapper, Clock.systemUTC());
    }

    /** Creates a focused adapter with explicit material and reconciliation persistence seams. */
    FeatureControlledMaterialStore(
            FixtureMaterialService materials,
            FixtureMaterialRepository repository,
            ObjectMapper mapper,
            Clock clock) {
        this.materials = materials;
        this.repository = repository;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Writes one complete immutable suite definition and returns its payload-free exact receipt.
     *
     * @param suiteRevision next metadata revision used to give each protected write a stable identity
     */
    public JsonNode write(String featureRef,
                          long featureRevision,
                          String featureContractFingerprint,
                          long suiteRevision,
                          String definitionFingerprint,
                          FeatureControlledSuiteDefinition definition,
                          IntegrationRequestContext caller) {
        FixtureMaterialService service = requireService();
        String materialIdentity = VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "featureRef", featureRef, "suiteRevision", suiteRevision,
                "definitionFingerprint", definitionFingerprint), MAX_BYTES);
        String materialId = MATERIAL_PREFIX + materialIdentity.substring("sha256:".length(), 31);
        String schemaFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, Map.of("schema", "rg.featureControlledSuiteMaterial.v1"), MAX_BYTES);
        Instant expiresAt = clock.instant().plus(RETENTION_DAYS, ChronoUnit.DAYS);
        try {
            Receipt receipt = service.write(new WriteRequest(
                    WriteRequest.SCHEMA_VERSION, materialId, 0,
                    new FixtureSource(SourceKind.SAMPLE, null), FixtureSubject.GRAPH,
                    new ExactTargetRef(TargetKind.GRAPH, featureRef, featureRevision,
                            featureContractFingerprint),
                    new ExactSchemaRef("rg.featureControlledSuiteMaterial.v1", 1, schemaFingerprint),
                    "RESTRICTED",
                    new RetentionDescriptor("rg.featureControlledSuite.365d", RETENTION_DAYS, expiresAt),
                    new RedactionDescriptor("rg.featureControlledSuite.redaction.v1", List.of(), true),
                    mapper.convertValue(definition.protectedMaterial(), Object.class)),
                    platform(caller, FixtureMaterialService.WRITE_PURPOSE));
            return mapper.valueToTree(receipt);
        } catch (FixtureMaterialCommandException | IllegalArgumentException failure) {
            throw unavailable(failure);
        }
    }

    /** Resolves one exact suite receipt for in-process controlled execution or engineering review. */
    public FeatureControlledSuiteDefinition read(JsonNode receiptNode, IntegrationRequestContext caller) {
        FixtureMaterialService service = requireService();
        try {
            Receipt receipt = mapper.treeToValue(receiptNode, Receipt.class);
            IntegrationRequestContext platform = platform(caller, FixtureMaterialService.RESOLVE_PURPOSE);
            Object payload = service.resolve(scope(platform), receipt.materialRef(),
                    new MaterialAccessContext(platform.actorId(), platform.purpose(),
                            platform.correlationId(), platform.clearance())).payload();
            return mapper.convertValue(payload, FeatureControlledSuiteDefinition.class);
        } catch (FixtureMaterialCommandException | java.io.IOException | IllegalArgumentException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * Verifies one suite receipt and its encrypted payload without returning protected material.
     *
     * <p>The result contains only a stable issue code and the exact material-reference fingerprint.
     * Receipt parse failures and vault failures remain distinct enough for reconciliation while no
     * case value, business intent, library reference, or evaluation reference leaves this method.</p>
     */
    public MaterialVerification verify(
            JsonNode receiptNode,
            String expectedFeatureRef,
            String expectedDefinitionFingerprint,
            String expectedFeatureContractFingerprint,
            IntegrationRequestContext caller) {
        requireReconciler(caller);
        String receiptFingerprint = fingerprint(receiptNode);
        try {
            Receipt receipt = mapper.treeToValue(receiptNode, Receipt.class);
            String refFingerprint = fingerprint(receipt.materialRef());
            boolean metadataMatches = receipt.fixtureAssetId().startsWith(MATERIAL_PREFIX)
                    && receipt.subject() == FixtureSubject.GRAPH
                    && receipt.target().id().equals(expectedFeatureRef)
                    && receipt.target().fingerprint().equals(expectedFeatureContractFingerprint)
                    && receipt.payloadFingerprint().equals(expectedDefinitionFingerprint);
            if (!metadataMatches) {
                return new MaterialVerification("METADATA_MISMATCH", refFingerprint, receiptFingerprint);
            }
            FeatureControlledSuiteDefinition definition = read(
                    receiptNode, asMaterialReader(caller));
            boolean payloadMatches = definition.featureRef().equals(expectedFeatureRef)
                    && fingerprint(mapper.convertValue(
                    definition.protectedMaterial(), Object.class))
                    .equals(expectedDefinitionFingerprint);
            return new MaterialVerification(payloadMatches ? "CURRENT" : "PAYLOAD_MISMATCH",
                    refFingerprint, receiptFingerprint);
        } catch (AgentTddToolException unavailable) {
            return new MaterialVerification("MATERIAL_UNAVAILABLE", "", receiptFingerprint);
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            return new MaterialVerification("RECEIPT_INVALID", "", receiptFingerprint);
        }
    }

    /** Lists bounded suite material metadata for internal orphan reconciliation. */
    public List<MaterialCandidate> inventory(int limit, IntegrationRequestContext caller) {
        requireReconciler(caller);
        FixtureMaterialRepository store = requireRepository();
        return store.listAvailable(scope(caller), MATERIAL_PREFIX, limit).stream()
                .map(material -> new MaterialCandidate(
                        material.receipt(), material.recordFingerprint(),
                        fingerprint(material.receipt().materialRef())))
                .toList();
    }

    /**
     * Tombstones one unchanged orphan candidate only after its declared retention has elapsed.
     *
     * <p>The caller proves reference safety; the repository independently fences exact identity,
     * record version, due time, and AVAILABLE state before erasing ciphertext.</p>
     */
    public boolean reclaimExpired(
            MaterialCandidate candidate, Instant observedAt, IntegrationRequestContext caller) {
        requireReconciler(caller);
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(observedAt, "observedAt");
        FixtureMaterialRepository store = requireRepository();
        AccessAudit audit = new AccessAudit(
                UUID.randomUUID().toString(), scope(caller), candidate.receipt().materialRef(),
                caller.actorId(), caller.purpose(), "EXPIRE", "ORPHAN_RECLAIMED",
                caller.correlationId(), clock.instant());
        return store.expireExactIfDue(scope(caller), candidate.receipt().materialRef(),
                candidate.recordFingerprint(), observedAt, audit);
    }

    private FixtureMaterialService requireService() {
        if (materials == null) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected Feature suite material is unavailable.");
        }
        return materials;
    }

    private FixtureMaterialRepository requireRepository() {
        if (repository == null) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected Feature suite reconciliation inventory is unavailable.");
        }
        return repository;
    }

    private static void requireReconciler(IntegrationRequestContext caller) {
        Objects.requireNonNull(caller, "caller").requireComplete();
        if (!"PLATFORM".equals(caller.actorType()) || !RECONCILE_PURPOSE.equals(caller.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE",
                    "Platform Feature suite reconciliation purpose is required.");
        }
    }

    private static IntegrationRequestContext asMaterialReader(IntegrationRequestContext caller) {
        return new IntegrationRequestContext(
                caller.tenantId(), caller.organizationId(), caller.projectId(), caller.environmentId(),
                caller.region(), "PLATFORM", "rg-feature-controlled-suite-reconciler",
                caller.actorId(), FixtureMaterialService.RESOLVE_PURPOSE, caller.correlationId(),
                java.util.Set.of(), "RESTRICTED", "");
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static IntegrationRequestContext platform(IntegrationRequestContext caller, String purpose) {
        Objects.requireNonNull(caller, "caller").requireComplete();
        return new IntegrationRequestContext(caller.tenantId(), caller.organizationId(), caller.projectId(),
                caller.environmentId(), caller.region(), "PLATFORM", "rg-feature-controlled-suite",
                caller.actorId(), purpose, caller.correlationId(), java.util.Set.of(), "RESTRICTED", "");
    }

    private static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope
    scope(IntegrationRequestContext identity) {
        return new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static AgentTddToolException unavailable(Exception failure) {
        AgentTddToolException result = new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                "Protected Feature suite material could not be persisted or resolved.", Map.of(), true);
        result.initCause(failure);
        return result;
    }

    /** Payload-free verification result for one state-held exact receipt. */
    public record MaterialVerification(
            String status, String materialRefFingerprint, String receiptFingerprint) {
        /** Normalizes the stable reconciliation classification and fingerprints. */
        public MaterialVerification {
            status = status == null ? "" : status.trim();
            materialRefFingerprint = materialRefFingerprint == null ? "" : materialRefFingerprint.trim();
            receiptFingerprint = receiptFingerprint == null ? "" : receiptFingerprint.trim();
            if (status.isBlank() || receiptFingerprint.isBlank()) {
                throw new IllegalArgumentException("Verification status and receipt fingerprint are required");
            }
        }

        /** Returns whether state and protected material agree at this read point. */
        public boolean current() {
            return "CURRENT".equals(status);
        }
    }

    /** Internal receipt-only inventory entry; protected payload never enters this projection. */
    public record MaterialCandidate(
            Receipt receipt, String recordFingerprint, String materialRefFingerprint) {
        /** Requires an exact receipt and stable integrity fingerprints. */
        public MaterialCandidate {
            Objects.requireNonNull(receipt, "receipt");
            recordFingerprint = recordFingerprint == null ? "" : recordFingerprint.trim();
            materialRefFingerprint = materialRefFingerprint == null ? "" : materialRefFingerprint.trim();
            if (recordFingerprint.isBlank() || materialRefFingerprint.isBlank()) {
                throw new IllegalArgumentException("Material candidate fingerprints are required");
            }
        }

        /** Returns the exact immutable material coordinate. */
        public ExactAssetRef materialRef() {
            return receipt.materialRef();
        }
    }
}
