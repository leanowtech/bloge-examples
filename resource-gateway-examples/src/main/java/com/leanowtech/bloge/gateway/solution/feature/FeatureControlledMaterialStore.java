package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
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
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Protected material adapter for complete Feature controlled suites.
 *
 * <p>All case ids, business intent, inputs, dependency values, expected outputs, library references,
 * coverage labels, and the candidate evaluation reference are encrypted together. Callers receive
 * only an exact vault receipt; disabled material infrastructure never falls back to plaintext.</p>
 */
@Service
public final class FeatureControlledMaterialStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int RETENTION_DAYS = 365;
    private final FixtureMaterialService materials;
    private final ObjectMapper mapper;

    /** Creates the production adapter while preserving fail-closed optional vault startup. */
    @Autowired
    public FeatureControlledMaterialStore(
            ObjectProvider<FixtureMaterialService> materials, ObjectMapper mapper) {
        this(materials.getIfAvailable(), mapper);
    }

    /** Creates a focused adapter with an explicit encrypted material vault. */
    public FeatureControlledMaterialStore(FixtureMaterialService materials, ObjectMapper mapper) {
        this.materials = materials;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
        String materialId = "feature-suite-" + materialIdentity.substring("sha256:".length(), 31);
        String schemaFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, Map.of("schema", "rg.featureControlledSuiteMaterial.v1"), MAX_BYTES);
        Instant expiresAt = Instant.now().plus(RETENTION_DAYS, ChronoUnit.DAYS);
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

    private FixtureMaterialService requireService() {
        if (materials == null) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected Feature suite material is unavailable.");
        }
        return materials;
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
}
