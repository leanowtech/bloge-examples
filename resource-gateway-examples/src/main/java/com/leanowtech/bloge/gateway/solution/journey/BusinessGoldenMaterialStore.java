package com.leanowtech.bloge.gateway.solution.journey;

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
 * Stores original business GOLDEN cases in the existing authenticated-encryption vault.
 * Case sets retain only the exact receipt and review metadata. Compiled Feature aliases,
 * Instruction references, stubs and controlled execution plans never enter this material. The
 * vault being disabled or unavailable is a hard failure and never falls back to plaintext state.
 */
@Service
public class BusinessGoldenMaterialStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final FixtureMaterialService materials;
    private final ObjectMapper mapper;

    /** Creates the production adapter and keeps disabled material infrastructure fail-closed. */
    @Autowired
    public BusinessGoldenMaterialStore(ObjectProvider<FixtureMaterialService> materials, ObjectMapper mapper) {
        this(materials.getIfAvailable(), mapper);
    }

    /** Creates a focused adapter with an explicit protected material service. */
    public BusinessGoldenMaterialStore(FixtureMaterialService materials, ObjectMapper mapper) {
        this.materials = materials;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Writes one immutable original business case and returns only its exact protected receipt.
     *
     * @param payload original BusinessGoldenCase plus its business and approval fingerprints
     */
    public JsonNode write(String solutionRef, long solutionRevision, String solutionFingerprint,
                          String caseId, String goldenFingerprint, String proposalFingerprint, JsonNode payload,
                          IntegrationRequestContext caller) {
        FixtureMaterialService service = requireService();
        String materialIdentity = VisualBundleFingerprint.fromCanonicalValue(mapper,
                Map.of("solutionRef", solutionRef, "caseId", caseId,
                        "goldenFingerprint", goldenFingerprint,
                        "proposalFingerprint", proposalFingerprint), MAX_BYTES);
        String materialId = "business-golden-" + materialIdentity.substring("sha256:".length(), 30);
        String schemaFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper,
                Map.of("schema", "rg.businessGoldenMaterial.v2"), MAX_BYTES);
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
        try {
            Receipt receipt = service.write(new WriteRequest(
                    WriteRequest.SCHEMA_VERSION, materialId, 0,
                    new FixtureSource(SourceKind.SAMPLE, null), FixtureSubject.SCENARIO,
                    new ExactTargetRef(TargetKind.GRAPH, solutionRef, solutionRevision, solutionFingerprint),
                    new ExactSchemaRef("rg.businessGoldenMaterial.v2", 2, schemaFingerprint),
                    "INTERNAL", new RetentionDescriptor("rg.businessGolden.30d", 30, expires),
                    new RedactionDescriptor("rg.businessGolden.redaction.v1", List.of(), true),
                    mapper.convertValue(payload, Object.class)), platform(caller,
                            FixtureMaterialService.WRITE_PURPOSE));
            return mapper.valueToTree(receipt);
        } catch (FixtureMaterialCommandException | IllegalArgumentException failure) {
            throw unavailable(failure);
        }
    }

    /** Resolves one exact receipt only for in-process review or controlled-test use. */
    public JsonNode read(JsonNode receiptNode, IntegrationRequestContext caller) {
        FixtureMaterialService service = requireService();
        try {
            Receipt receipt = mapper.treeToValue(receiptNode, Receipt.class);
            IntegrationRequestContext platform = platform(caller, FixtureMaterialService.RESOLVE_PURPOSE);
            Object payload = service.resolve(scope(platform), receipt.materialRef(),
                    new MaterialAccessContext(platform.actorId(), platform.purpose(),
                            platform.correlationId(), platform.clearance())).payload();
            return mapper.valueToTree(payload);
        } catch (FixtureMaterialCommandException | java.io.IOException | IllegalArgumentException failure) {
            throw unavailable(failure);
        }
    }

    private FixtureMaterialService requireService() {
        if (materials == null) throw new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE", "Protected business case material is unavailable.");
        return materials;
    }

    private static IntegrationRequestContext platform(IntegrationRequestContext caller, String purpose) {
        Objects.requireNonNull(caller, "caller").requireComplete();
        return new IntegrationRequestContext(caller.tenantId(), caller.organizationId(), caller.projectId(),
                caller.environmentId(), caller.region(), "PLATFORM", "rg-business-golden-material",
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
                "Protected business case material could not be persisted or resolved.", Map.of(), true);
        result.initCause(failure);
        return result;
    }
}
