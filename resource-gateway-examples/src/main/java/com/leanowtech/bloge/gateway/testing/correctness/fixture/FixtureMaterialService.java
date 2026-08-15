package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Material;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialRepository.AccessAudit;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.MaterialAccessContext;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.ResolvedFixtureMaterial;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Protected Fixture material boundary with redaction, encryption, clearance, retention, and audit. */
public class FixtureMaterialService implements FixtureMaterialResolver {

    public static final String WRITE_PURPOSE = "CORRECTNESS_FIXTURE_MATERIAL_WRITE";
    public static final String READ_PURPOSE = "CORRECTNESS_FIXTURE_MATERIAL_READ";
    public static final String RESOLVE_PURPOSE = "CORRECTNESS_FIXTURE_MATERIAL_RESOLVE";
    private static final Set<String> CLASSIFICATIONS =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final int MAX_RETENTION_DAYS = 365;

    private final FixtureMaterialRepository repository;
    private final AuthoringFixturePayloadProtector protector;
    private final ObjectMapper mapper;
    private final FixturePayloadRedactor redactor;
    private final Clock clock;

    public FixtureMaterialService(
            FixtureMaterialRepository repository,
            AuthoringFixturePayloadProtector protector,
            ObjectMapper mapper) {
        this(repository, protector, mapper, Clock.systemUTC());
    }

    public FixtureMaterialService(
            FixtureMaterialRepository repository,
            AuthoringFixturePayloadProtector protector,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.protector = Objects.requireNonNull(protector, "protector");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.redactor = new FixturePayloadRedactor(mapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Receipt write(WriteRequest request, IntegrationRequestContext identity) {
        requireIdentity(identity, WRITE_PURPOSE);
        Objects.requireNonNull(request, "request");
        EnterpriseScope scope = scope(identity);
        requireClearance(request.classification(), identity.clearance());
        requireRetention(request.retention().retentionDays(), request.retention().expiresAt());
        FixturePayloadRedactor.Result redacted = redactor.redact(
                request.payload(), request.redaction());
        String payloadFingerprint = ProtocolFingerprint.ofBounded(
                mapper, redacted.payload(), FixturePayloadRedactor.MAX_PAYLOAD_BYTES);
        long revision = request.expectedRevision() + 1;
        ExactAssetRef materialRef = new ExactAssetRef(
                "FIXTURE_MATERIAL", request.fixtureAssetId(), revision, payloadFingerprint);
        List<ExactAssetRef> lineage = request.source().sourceRef() == null
                ? List.of() : List.of(request.source().sourceRef());
        Receipt receipt = new Receipt(
                "", request.fixtureAssetId(), materialRef, payloadFingerprint,
                request.source(), request.subject(), request.target(), request.schemaRef(),
                request.classification(), request.retention(), redacted.redaction(), lineage,
                true, false);
        String protectedPayload = protector.protect(
                encode(redacted.payload()), FixtureMaterialIntegrity.associatedData(scope, receipt));
        StoredFixtureMaterial candidate = new StoredFixtureMaterial(
                "", scope, receipt, StoredFixtureMaterial.AVAILABLE, true,
                protectedPayload, "");
        AccessAudit audit = audit(scope, materialRef, identity.actorId(), identity.purpose(),
                "WRITE", "ACCEPTED", identity.correlationId());
        return repository.saveIfRevision(request.expectedRevision(), candidate, audit)
                .map(StoredFixtureMaterial::receipt)
                .orElseThrow(() -> failure(
                        409, "RG.CORRECTNESS.FIXTURE_MATERIAL_REVISION_CONFLICT",
                        "Fixture material changed after it was opened"));
    }

    public Material read(
            String fixtureAssetId,
            long revision,
            String fingerprint,
            IntegrationRequestContext identity) {
        requireIdentity(identity, READ_PURPOSE);
        ExactAssetRef ref = exactMaterialRef(fixtureAssetId, revision, fingerprint);
        ResolvedFixtureMaterial resolved = resolve(
                scope(identity), ref,
                new MaterialAccessContext(identity.actorId(), identity.purpose(),
                        identity.correlationId(), identity.clearance()));
        return new Material("", resolved.receipt(), resolved.payload(), true);
    }

    @Override
    public ResolvedFixtureMaterial resolve(
            EnterpriseScope scope,
            ExactAssetRef materialRef,
            MaterialAccessContext access) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(access, "access");
        if (!Set.of(READ_PURPOSE, RESOLVE_PURPOSE).contains(
                access.purpose().toUpperCase(Locale.ROOT))) {
            throw failure(403, "RG.CORRECTNESS.FIXTURE_MATERIAL_PURPOSE_FORBIDDEN",
                    "A dedicated Fixture material read or resolve purpose is required");
        }
        if (materialRef == null || !"FIXTURE_MATERIAL".equals(materialRef.kind())) {
            throw failure(400, "RG.CORRECTNESS.FIXTURE_MATERIAL_REF_INVALID",
                    "An exact FIXTURE_MATERIAL reference is required");
        }
        StoredFixtureMaterial stored = repository.find(
                        scope, materialRef.id(), materialRef.revision())
                .orElseThrow(() -> failure(
                        404, "RG.CORRECTNESS.FIXTURE_MATERIAL_NOT_FOUND",
                        "Fixture material was not found in the authorized enterprise scope"));
        Receipt receipt = stored.receipt();
        if (!receipt.materialRef().equals(materialRef)) {
            denied(scope, materialRef, access, "FINGERPRINT_MISMATCH");
            throw failure(409, "RG.CORRECTNESS.FIXTURE_MATERIAL_FINGERPRINT_CONFLICT",
                    "Fixture material fingerprint does not match the immutable revision");
        }
        try {
            requireClearance(receipt.classification(), access.clearance());
        } catch (FixtureMaterialCommandException forbidden) {
            denied(scope, materialRef, access, "CLEARANCE_FORBIDDEN");
            throw forbidden;
        }
        if (!stored.payloadAvailable()
                || !StoredFixtureMaterial.AVAILABLE.equals(stored.state())
                || !receipt.retention().expiresAt().isAfter(clock.instant())) {
            denied(scope, materialRef, access, "EXPIRED");
            throw failure(410, "RG.CORRECTNESS.FIXTURE_MATERIAL_EXPIRED",
                    "Fixture material retention elapsed; only its lineage tombstone remains");
        }
        Object payload;
        try {
            byte[] plaintext = protector.unprotect(
                    stored.protectedPayload(),
                    FixtureMaterialIntegrity.associatedData(scope, receipt));
            payload = mapper.readValue(plaintext, Object.class);
        } catch (IOException | RuntimeException invalid) {
            denied(scope, materialRef, access, "INTEGRITY_INVALID");
            throw failure(503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Fixture material failed authenticated decryption");
        }
        if (!ProtocolFingerprint.ofBounded(
                mapper, payload, FixturePayloadRedactor.MAX_PAYLOAD_BYTES)
                .equals(receipt.payloadFingerprint())) {
            denied(scope, materialRef, access, "FINGERPRINT_INVALID");
            throw failure(503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Fixture material payload fingerprint failed verification");
        }
        appendAudit(audit(
                scope, materialRef, access.actorId(), access.purpose(), "READ", "ACCEPTED",
                access.correlationId()));
        return new ResolvedFixtureMaterial(materialRef, receipt, payload);
    }

    private void denied(
            EnterpriseScope scope,
            ExactAssetRef ref,
            MaterialAccessContext access,
            String outcome) {
        appendAudit(audit(
                scope, ref, access.actorId(), access.purpose(), "READ", outcome,
                access.correlationId()));
    }

    private void appendAudit(AccessAudit audit) {
        try {
            repository.appendAccessAudit(audit);
        } catch (FixtureMaterialCommandException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(503, "RG.CORRECTNESS.FIXTURE_MATERIAL_AUDIT_UNAVAILABLE",
                    "Fixture material access is unavailable because audit could not commit");
        }
    }

    private void requireRetention(int days, Instant expiresAt) {
        Instant now = clock.instant();
        if (days < 1 || days > MAX_RETENTION_DAYS || expiresAt == null
                || !expiresAt.isAfter(now)
                || Duration.between(now, expiresAt).compareTo(Duration.ofDays(days)) > 0) {
            throw failure(422, "RG.CORRECTNESS.FIXTURE_RETENTION_INVALID",
                    "Fixture retention must be future-bounded by its declared retention days");
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity, String purpose) {
        if (identity == null) {
            throw failure(401, "RG.CORRECTNESS.FIXTURE_MATERIAL_AUTH_REQUIRED",
                    "Verified Fixture material identity is required");
        }
        identity.requireComplete();
        if (!purpose.equals(identity.purpose())) {
            throw failure(403, "RG.CORRECTNESS.FIXTURE_MATERIAL_PURPOSE_FORBIDDEN",
                    "A dedicated Fixture material purpose is required");
        }
    }

    private static void requireClearance(String classification, String clearance) {
        String required = normalizedClassification(classification);
        String actual = normalizedClassification(clearance);
        if (rank(actual) < rank(required)) {
            throw failure(403, "RG.CORRECTNESS.FIXTURE_MATERIAL_CLEARANCE_FORBIDDEN",
                    "Workload clearance is insufficient for this Fixture material");
        }
    }

    private static int rank(String classification) {
        return switch (classification) {
            case "PUBLIC" -> 0;
            case "INTERNAL" -> 1;
            case "CONFIDENTIAL" -> 2;
            case "RESTRICTED" -> 3;
            default -> -1;
        };
    }

    private static String normalizedClassification(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(normalized)) {
            throw failure(422, "RG.CORRECTNESS.FIXTURE_CLASSIFICATION_INVALID",
                    "Fixture classification is unsupported");
        }
        return normalized;
    }

    private byte[] encode(Object payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException failure) {
            throw failure(422, "RG.CORRECTNESS.FIXTURE_PAYLOAD_INVALID",
                    "Fixture payload could not be encoded");
        }
    }

    private static ExactAssetRef exactMaterialRef(String id, long revision, String fingerprint) {
        try {
            return new ExactAssetRef("FIXTURE_MATERIAL", id, revision, fingerprint);
        } catch (IllegalArgumentException invalid) {
            throw failure(400, "RG.CORRECTNESS.FIXTURE_MATERIAL_REF_INVALID",
                    "An exact Fixture material id, revision, and fingerprint are required");
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private AccessAudit audit(
            EnterpriseScope scope,
            ExactAssetRef ref,
            String actorId,
            String purpose,
            String action,
            String outcome,
            String correlationId) {
        return new AccessAudit(
                UUID.randomUUID().toString(), scope, ref, actorId, purpose, action, outcome,
                correlationId, clock.instant());
    }

    private static FixtureMaterialCommandException failure(
            int status, String code, String message) {
        return new FixtureMaterialCommandException(status, code, message);
    }
}
