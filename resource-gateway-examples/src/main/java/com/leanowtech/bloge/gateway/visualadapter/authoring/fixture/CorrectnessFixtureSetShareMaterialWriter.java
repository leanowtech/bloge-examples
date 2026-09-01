package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.MaterialAccessContext;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.ResolvedFixtureMaterial;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFailure;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureShareIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureSetShareMaterialWriter;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Correctness material/catalog adapter for whole-flow Fixture Set sharing. */
public final class CorrectnessFixtureSetShareMaterialWriter
        implements FixtureSetShareMaterialWriter {
    private final FixtureMaterialService materials;
    private final FixtureCatalogService catalog;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Creates the protected material writer over the existing governed Fixture services. */
    public CorrectnessFixtureSetShareMaterialWriter(
            FixtureMaterialService materials, FixtureCatalogService catalog,
            ObjectMapper mapper, Clock clock) {
        this.materials = Objects.requireNonNull(materials, "materials");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public Result write(
            Request request, FixtureShareIdentity identity) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(identity, "identity");
        IntegrationRequestContext context = context(identity);
        try {
            return writeProtected(request, identity, context);
        } catch (FixtureMaterialCommandException | FixtureCatalogCommandException failure) {
            throw new ApiFixtureSetAuthoringFailure(
                    ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
    }

    private Result writeProtected(
            Request request, FixtureShareIdentity identity, IntegrationRequestContext context) {
        if (!(request.source().subject() instanceof FixtureSubjectRef.FlowVersion subject)) {
            throw new IllegalArgumentException("Shared Fixture requires an exact Flow Version subject");
        }
        Instant now = clock.instant();
        PrincipalRef owner = new PrincipalRef(identity.actorId(), principalKind(identity.actorType()), "");
        EnterpriseScope scope = new EnterpriseScope(
                identity.scope().tenantId(), identity.organizationId(), identity.scope().projectId(),
                identity.scope().environmentId(), identity.region());
        ExactAssetRef sourceRef = new ExactAssetRef(
                "FIXTURE_SET", request.source().fixtureSetId(), request.source().revision(),
                request.source().fingerprint());
        FixtureSource source = new FixtureSource(SourceKind.SCENARIO, sourceRef);
        ExactTargetRef target = new ExactTargetRef(
                TargetKind.GRAPH, subject.publicationId(), subject.revision(), subject.fingerprint());
        String schemaFingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(mapper, Map.of(
                "subjectFingerprint", subject.fingerprint(),
                "schema", request.outputSchema().schema()));
        ExactSchemaRef schemaRef = new ExactSchemaRef(
                subject.publicationId() + ":output", subject.revision(), schemaFingerprint);
        RetentionDescriptor retention = new RetentionDescriptor(
                "fixture-share-retention-v1", request.policy().retentionDays(),
                now.plus(Duration.ofDays(request.policy().retentionDays())));
        RedactionDescriptor redaction = new RedactionDescriptor(
                request.policy().redaction().profileVersion(),
                request.policy().redaction().paths(), false);
        Receipt receipt = materials.write(new WriteRequest(
                WriteRequest.SCHEMA_VERSION, request.fixtureAssetId(), 0, source,
                FixtureSubject.SCENARIO, target, schemaRef, request.policy().classification(),
                retention, redaction, mapper.convertValue(request.payload(), Object.class)), context);
        ResolvedFixtureMaterial resolved = materials.resolve(
                scope, receipt.materialRef(), new MaterialAccessContext(
                        identity.actorId(), FixtureMaterialService.RESOLVE_PURPOSE,
                        identity.correlationId(), identity.clearance()));
        if (!receipt.equals(resolved.receipt())) {
            throw new ApiFixtureSetAuthoringFailure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
        FixtureAssetDescriptor candidate = new FixtureAssetDescriptor(
                FixtureAssetDescriptor.SCHEMA_VERSION, request.fixtureAssetId(), 0, scope,
                request.caseName() + " shared Fixture", source, receipt.materialRef(), schemaRef,
                request.caseId(), FixtureLifecycle.DRAFT, request.policy().classification(), owner,
                redaction, retention, new QualityProfile(true, false, 0, 0),
                List.of("fixture-set-share", request.reviewRequestId()),
                new AuditMetadata(now, now, owner, owner));
        var draft = catalog.saveDraft(0, candidate, owner);
        var proposed = catalog.submitForReview(
                scope, request.fixtureAssetId(), draft.descriptor().revision(), owner);
        return new Result(new FixtureSetCommand.Material.FixtureAsset(
                proposed.descriptor().fixtureAssetId(),
                Math.toIntExact(proposed.descriptor().revision()), schemaFingerprint),
                mapper.valueToTree(resolved.payload()));
    }

    private static IntegrationRequestContext context(FixtureShareIdentity identity) {
        return new IntegrationRequestContext(
                identity.scope().tenantId(), identity.organizationId(), identity.scope().projectId(),
                identity.scope().environmentId(), identity.region(), identity.actorType(),
                identity.actorId(), "", FixtureMaterialService.WRITE_PURPOSE,
                identity.correlationId(), Set.of(), identity.clearance(), "");
    }

    private static PrincipalKind principalKind(String actorType) {
        return switch (actorType == null ? "" : actorType.toUpperCase(java.util.Locale.ROOT)) {
            case "USER", "HUMAN" -> PrincipalKind.USER;
            case "TEAM" -> PrincipalKind.TEAM;
            case "SERVICE", "WORKLOAD" -> PrincipalKind.SERVICE;
            default -> throw new IllegalArgumentException("Fixture share actor type is invalid");
        };
    }
}
