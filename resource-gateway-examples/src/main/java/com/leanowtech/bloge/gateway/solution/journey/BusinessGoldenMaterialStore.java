package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
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

import java.time.Clock;
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
    private static final int LIFECYCLE_RETENTION_DAYS = 365;
    private static final String LIFECYCLE_RETENTION_POLICY = "rg.businessGolden.lifecycle";
    private final FixtureMaterialService materials;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Creates the production adapter and keeps disabled material infrastructure fail-closed. */
    @Autowired
    public BusinessGoldenMaterialStore(ObjectProvider<FixtureMaterialService> materials, ObjectMapper mapper) {
        this(materials.getIfAvailable(), mapper, Clock.systemUTC());
    }

    /** Creates a focused adapter with an explicit protected material service. */
    public BusinessGoldenMaterialStore(FixtureMaterialService materials, ObjectMapper mapper) {
        this(materials, mapper, Clock.systemUTC());
    }

    /** Creates a focused adapter whose retention timestamps use the supplied clock. */
    BusinessGoldenMaterialStore(FixtureMaterialService materials, ObjectMapper mapper, Clock clock) {
        this.materials = materials;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Instant expires = clock.instant().plus(LIFECYCLE_RETENTION_DAYS, ChronoUnit.DAYS);
        try {
            Receipt receipt = service.write(new WriteRequest(
                    WriteRequest.SCHEMA_VERSION, materialId, 0,
                    new FixtureSource(SourceKind.SAMPLE, null), FixtureSubject.SCENARIO,
                    new ExactTargetRef(TargetKind.GRAPH, solutionRef, solutionRevision, solutionFingerprint),
                    new ExactSchemaRef("rg.businessGoldenMaterial.v2", 2, schemaFingerprint),
                    "INTERNAL", new RetentionDescriptor(
                            LIFECYCLE_RETENTION_POLICY, LIFECYCLE_RETENTION_DAYS, expires),
                    new RedactionDescriptor("rg.businessGolden.redaction.v1", List.of(), true),
                    mapper.convertValue(payload, Object.class)), platform(caller,
                            FixtureMaterialService.WRITE_PURPOSE));
            return mapper.valueToTree(receipt);
        } catch (FixtureMaterialCommandException | IllegalArgumentException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * Renews one exact business case for its ACTIVE lifecycle and returns the successor receipt.
     *
     * <p>The vault revision is written before the case-set CAS. If the process loses the response or
     * that later CAS fails, retrying with the predecessor receipt resolves and verifies the already
     * written direct successor. The predecessor remains immutable and the successor records it as
     * lineage, so an orphan renewal is safe to reconcile and never becomes authoritative until a
     * case-set revision points at it.</p>
     *
     * @param receiptNode exact protected DRAFT receipt currently held by the case-set row
     * @param caller authenticated reviewer whose scope and correlation are preserved
     * @return exact ACTIVE-lifecycle successor receipt without protected payload
     */
    public JsonNode renew(JsonNode receiptNode, IntegrationRequestContext caller) {
        FixtureMaterialService service = requireService();
        try {
            Receipt predecessor = mapper.treeToValue(receiptNode, Receipt.class);
            JsonNode payload = read(receiptNode, caller);
            RetentionDescriptor retention = new RetentionDescriptor(
                    LIFECYCLE_RETENTION_POLICY, LIFECYCLE_RETENTION_DAYS,
                    clock.instant().plus(LIFECYCLE_RETENTION_DAYS, ChronoUnit.DAYS));
            WriteRequest request = new WriteRequest(
                    WriteRequest.SCHEMA_VERSION, predecessor.fixtureAssetId(),
                    predecessor.materialRef().revision(),
                    new FixtureSource(SourceKind.REPLAY_DERIVATION, predecessor.materialRef()),
                    predecessor.subject(), predecessor.target(), predecessor.schemaRef(),
                    predecessor.classification(), retention, predecessor.redaction(),
                    mapper.convertValue(payload, Object.class));
            try {
                return mapper.valueToTree(service.write(request,
                        platform(caller, FixtureMaterialService.WRITE_PURPOSE)));
            } catch (FixtureMaterialCommandException conflict) {
                if (!"RG.CORRECTNESS.FIXTURE_MATERIAL_REVISION_CONFLICT".equals(conflict.code())) {
                    throw conflict;
                }
                return mapper.valueToTree(recoverSuccessor(service, predecessor, caller));
            }
        } catch (FixtureMaterialCommandException | java.io.IOException | IllegalArgumentException failure) {
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

    private Receipt recoverSuccessor(FixtureMaterialService service,
                                     Receipt predecessor,
                                     IntegrationRequestContext caller) {
        IntegrationRequestContext platform = platform(caller, FixtureMaterialService.RESOLVE_PURPOSE);
        var expectedRef = new ExactAssetRef(
                "FIXTURE_MATERIAL", predecessor.fixtureAssetId(),
                predecessor.materialRef().revision() + 1, predecessor.payloadFingerprint());
        Receipt successor = service.resolve(scope(platform), expectedRef,
                new MaterialAccessContext(platform.actorId(), platform.purpose(),
                        platform.correlationId(), platform.clearance())).receipt();
        boolean valid = successor.lineageRefs().contains(predecessor.materialRef())
                && successor.payloadFingerprint().equals(predecessor.payloadFingerprint())
                && successor.subject() == predecessor.subject()
                && successor.target().equals(predecessor.target())
                && successor.schemaRef().equals(predecessor.schemaRef())
                && successor.classification().equals(predecessor.classification())
                && successor.redaction().equals(predecessor.redaction())
                && LIFECYCLE_RETENTION_POLICY.equals(successor.retention().policyVersion())
                && successor.retention().retentionDays() == LIFECYCLE_RETENTION_DAYS
                && successor.retention().expiresAt().isAfter(clock.instant());
        if (!valid) {
            throw new FixtureMaterialCommandException(409,
                    "RG.CORRECTNESS.FIXTURE_MATERIAL_REVISION_CONFLICT",
                    "Fixture material successor does not match the requested lifecycle renewal");
        }
        return successor;
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
