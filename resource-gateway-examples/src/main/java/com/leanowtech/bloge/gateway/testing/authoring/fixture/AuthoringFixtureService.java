package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.AssetKind;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureMaterial;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureReceipt;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SaveRequest;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringFixtureCapability;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact-draft, scoped, encrypted and expiring fixture authoring service.
 */
@Service
@org.springframework.context.annotation.Profile("!production & (test | staging)")
public final class AuthoringFixtureService implements AuthoringFixtureCapability {
    public static final int MAXIMUM_PAYLOAD_BYTES =
            AuthoringFixtureCapability.MAXIMUM_PAYLOAD_BYTES;
    public static final int MAXIMUM_PAYLOAD_DEPTH =
            AuthoringFixtureCapability.MAXIMUM_PAYLOAD_DEPTH;
    public static final int MAXIMUM_PAYLOAD_NODES =
            AuthoringFixtureCapability.MAXIMUM_PAYLOAD_NODES;
    public static final int MAXIMUM_REDACTION_PATHS =
            AuthoringFixtureCapability.MAXIMUM_REDACTION_PATHS;
    public static final int MAXIMUM_RETENTION_DAYS =
            AuthoringFixtureCapability.MAXIMUM_RETENTION_DAYS;
    public static final int MAXIMUM_ASSET_REF_LENGTH = 320;
    public static final String RETENTION_POLICY_VERSION =
            "visual-authoring-fixture-retention.v1";
    public static final String REDACTION_PROFILE_VERSION =
            "visual-authoring-sensitive-keys.v1";
    private static final Pattern FIXTURE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");
    private static final Set<String> CLASSIFICATIONS =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|authorization|credential|cookie|api[-_]?key).*");

    private final AuthoringDraftService drafts;
    private final AuthoringFixtureRepository fixtures;
    private final AuthoringFixturePayloadProtector protector;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AuthoringFixtureService(
            AuthoringDraftService drafts,
            AuthoringFixtureRepository fixtures,
            AuthoringFixturePayloadProtector protector,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper) {
        this(drafts, fixtures, protector, securityEvents, objectMapper, Clock.systemUTC());
    }

    AuthoringFixtureService(
            AuthoringDraftService drafts,
            AuthoringFixtureRepository fixtures,
            AuthoringFixturePayloadProtector protector,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            Clock clock) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.protector = Objects.requireNonNull(protector, "protector");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FixtureReceipt save(
            String draftId,
            long expectedDraftRevision,
            SaveRequest request,
            IntegrationRequestContext identity) {
        TestingArtifactScope scope = requireIdentity(identity, "TEST_FIXTURE_WRITE");
        requireRequest(request, draftId, expectedDraftRevision);
        requireClearance(request.classification(), identity, draftId, expectedDraftRevision);
        AuthoringCompileResult preview = exactPreview(
                scope, draftId, expectedDraftRevision);
        requireFixtureLineage(
                scope, request, draftId, expectedDraftRevision);
        String artifactFingerprint = artifactFingerprint(
                preview, request.assetKind(), request.assetRef(),
                draftId, expectedDraftRevision);
        RedactionResult redacted = redact(
                request.payload(), request.redactionPaths(),
                draftId, expectedDraftRevision);
        String payloadFingerprint = payloadFingerprint(
                redacted.payload(), draftId, expectedDraftRevision);
        Instant createdAt = clock.instant();
        long revision = request.expectedFixtureRevision() + 1;
        FixtureReceipt receipt = new FixtureReceipt(
                FixtureReceipt.SCHEMA_VERSION,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                request.fixtureId(),
                revision,
                request.sourceKind(),
                request.assetKind(),
                request.assetRef(),
                draftId,
                expectedDraftRevision,
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                artifactFingerprint,
                payloadFingerprint,
                request.classification(),
                RETENTION_POLICY_VERSION,
                createdAt.plus(request.retentionDays(), ChronoUnit.DAYS),
                REDACTION_PROFILE_VERSION,
                redacted.paths(),
                createdAt,
                identity.actorId(),
                true,
                false);
        byte[] plaintext = jsonBytes(
                redacted.payload(), draftId, expectedDraftRevision);
        String protectedPayload = protector.protect(
                plaintext, AuthoringFixtureIntegrity.associatedData(scope, receipt));
        StoredAuthoringFixture candidate = AuthoringFixtureIntegrity.attach(
                objectMapper,
                new StoredAuthoringFixture(
                        StoredAuthoringFixture.SCHEMA_VERSION,
                        scope,
                        receipt,
                        StoredAuthoringFixture.AVAILABLE,
                        true,
                        protectedPayload,
                        ""));
        TestSecurityEvent audit = event(
                identity,
                "AUTHORING_FIXTURE_SAVED",
                "ACCEPTED",
                "RG.AUTHORING.FIXTURE_SAVED",
                receipt);
        try {
            TestRuntimeTransactionMutation auditMutation =
                    securityEvents.boundAppend(audit);
            StoredAuthoringFixture stored = fixtures.create(
                    scope,
                    candidate,
                    request.expectedFixtureRevision(),
                    auditMutation);
            return AuthoringFixtureIntegrity.verifyLookup(
                    objectMapper, stored, scope, receipt.fixtureId(), revision)
                    .descriptor();
        } catch (AuthoringFixtureRevisionConflictException stale) {
            throw failure(
                    409,
                    "RG.AUTHORING.FIXTURE_REVISION_STALE",
                    "Fixture changed after it was opened; reload its latest revision.",
                    draftId,
                    expectedDraftRevision,
                    "/expectedFixtureRevision",
                    Map.of("currentFixtureRevision", stale.currentRevision()));
        } catch (AuthoringFixtureLineageConflictException rebound) {
            throw lineageConflict(draftId, expectedDraftRevision);
        } catch (AuthoringLifecycleException known) {
            throw known;
        } catch (AuthoringFixtureIntegrityException invalid) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_INTEGRITY_INVALID",
                    "The governed fixture store failed immutable-content verification.",
                    draftId,
                    expectedDraftRevision,
                    "/",
                    Map.of());
        } catch (RuntimeException unavailable) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_STORE_UNAVAILABLE",
                    "The governed fixture store or mandatory audit sink is unavailable.",
                    draftId,
                    expectedDraftRevision,
                    "/",
                    Map.of());
        }
    }

    public FixtureMaterial find(
            String fixtureId,
            long revision,
            IntegrationRequestContext identity) {
        TestingArtifactScope scope = requireIdentity(
                identity, "TEST_FIXTURE_READ", "TEST_FIXTURE_WRITE");
        if (!validFixtureId(fixtureId) || revision <= 0) {
            throw failure(
                    400,
                    "RG.AUTHORING.FIXTURE_IDENTITY_INVALID",
                    "A bounded fixture id and positive revision are required.",
                    "",
                    0,
                    "/fixtureId",
                    Map.of());
        }
        StoredAuthoringFixture stored;
        try {
            stored = fixtures.find(scope, fixtureId.trim(), revision)
                    .map(value -> AuthoringFixtureIntegrity.verifyLookup(
                            objectMapper, value, scope, fixtureId.trim(), revision))
                    .orElseThrow(() -> failure(
                            404,
                            "RG.AUTHORING.FIXTURE_NOT_FOUND",
                            "Fixture was not found in the authorized enterprise scope.",
                            "",
                            0,
                            "/fixtureId",
                            Map.of()));
        } catch (AuthoringLifecycleException known) {
            throw known;
        } catch (AuthoringFixtureIntegrityException invalid) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_INTEGRITY_INVALID",
                    "The governed fixture store failed immutable-content verification.",
                    "",
                    0,
                    "/fixtureId",
                    Map.of());
        } catch (RuntimeException unavailable) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_STORE_UNAVAILABLE",
                    "The governed fixture store is unavailable.",
                    "",
                    0,
                    "/fixtureId",
                    Map.of());
        }
        FixtureReceipt receipt = stored.descriptor();
        requireClearance(receipt.classification(), identity, receipt.draftId(),
                receipt.authoringRevision());
        if (!stored.payloadAvailable()
                || !StoredAuthoringFixture.AVAILABLE.equals(stored.state())) {
            throw failure(
                    410,
                    "RG.AUTHORING.FIXTURE_EXPIRED",
                    "Fixture payload retention has elapsed; its lineage tombstone remains.",
                    receipt.draftId(),
                    receipt.authoringRevision(),
                    "/fixtureId",
                    Map.of("fixtureRevision", receipt.revision()));
        }
        Object payload;
        try {
            byte[] plaintext = protector.unprotect(
                    stored.protectedPayload(),
                    AuthoringFixtureIntegrity.associatedData(stored));
            payload = objectMapper.readValue(plaintext, Object.class);
        } catch (IOException | AuthoringFixtureIntegrityException invalid) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_INTEGRITY_INVALID",
                    "The governed fixture payload failed authenticated decryption.",
                    receipt.draftId(),
                    receipt.authoringRevision(),
                    "/fixtureId",
                    Map.of());
        }
        String actualFingerprint = payloadFingerprint(
                payload, receipt.draftId(), receipt.authoringRevision());
        if (!actualFingerprint.equals(receipt.payloadFingerprint())) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_INTEGRITY_INVALID",
                    "The governed fixture payload fingerprint does not match its descriptor.",
                    receipt.draftId(),
                    receipt.authoringRevision(),
                    "/fixtureId",
                    Map.of());
        }
        appendReadAudit(identity, receipt);
        return new FixtureMaterial(
                FixtureMaterial.SCHEMA_VERSION,
                receipt,
                payload,
                true);
    }

    private AuthoringCompileResult exactPreview(
            TestingArtifactScope scope,
            String draftId,
            long revision) {
        AuthoringCompileResult preview = drafts.preview(
                new AuthoringScope(
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region()),
                draftId,
                revision);
        if (preview.canonicalLibrary() == null) {
            throw failure(
                    422,
                    "RG.AUTHORING.FIXTURE_TARGET_NOT_COMPILED",
                    "The exact draft revision must compile before a fixture can be saved.",
                    draftId,
                    revision,
                    "/",
                    Map.of());
        }
        return preview;
    }

    private void requireFixtureLineage(
            TestingArtifactScope scope,
            SaveRequest request,
            String draftId,
            long revision) {
        if (request.expectedFixtureRevision() == 0) {
            return;
        }
        StoredAuthoringFixture previous;
        try {
            previous = fixtures.find(
                            scope,
                            request.fixtureId(),
                            request.expectedFixtureRevision())
                    .map(value -> AuthoringFixtureIntegrity.verifyLookup(
                            objectMapper,
                            value,
                            scope,
                            request.fixtureId(),
                            request.expectedFixtureRevision()))
                    .orElseThrow(() -> new AuthoringFixtureRevisionConflictException(
                            fixtures.latestRevision(scope, request.fixtureId())));
        } catch (AuthoringFixtureRevisionConflictException stale) {
            throw failure(
                    409,
                    "RG.AUTHORING.FIXTURE_REVISION_STALE",
                    "Fixture changed after it was opened; reload its latest revision.",
                    draftId,
                    revision,
                    "/expectedFixtureRevision",
                    Map.of("currentFixtureRevision", stale.currentRevision()));
        } catch (AuthoringFixtureIntegrityException invalid) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_INTEGRITY_INVALID",
                    "The governed fixture store failed immutable-content verification.",
                    draftId,
                    revision,
                    "/fixtureId",
                    Map.of());
        } catch (RuntimeException unavailable) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_STORE_UNAVAILABLE",
                    "The governed fixture store is unavailable.",
                    draftId,
                    revision,
                    "/fixtureId",
                    Map.of());
        }
        FixtureReceipt descriptor = previous.descriptor();
        if (descriptor.sourceKind() != request.sourceKind()
                || descriptor.assetKind() != request.assetKind()
                || !descriptor.assetRef().equals(request.assetRef())
                || !descriptor.draftId().equals(draftId)
                || !descriptor.classification().equals(request.classification())) {
            throw lineageConflict(draftId, revision);
        }
    }

    private String artifactFingerprint(
            AuthoringCompileResult preview,
            AssetKind kind,
            String assetRef,
            String draftId,
            long revision) {
        String ref = assetRef == null ? "" : assetRef.trim();
        if (kind == AssetKind.OPERATOR) {
            return preview.canonicalLibrary().operators().stream()
                    .filter(Objects::nonNull)
                    .filter(operator -> operator.operatorRef().equals(ref))
                    .map(OperatorDefinition::fingerprint)
                    .findFirst()
                    .orElseThrow(() -> targetNotFound(
                            draftId, revision, "/assetRef"));
        }
        if (kind == AssetKind.FUNCTION) {
            return preview.canonicalLibrary().builtInFunctions().stream()
                    .filter(Objects::nonNull)
                    .filter(function -> function.name().equals(ref))
                    .map(BuiltInFunctionContract::callableFingerprint)
                    .findFirst()
                    .orElseThrow(() -> targetNotFound(
                            draftId, revision, "/assetRef"));
        }
        throw failure(
                400,
                "RG.AUTHORING.FIXTURE_ASSET_KIND_INVALID",
                "Fixture assetKind must be OPERATOR or FUNCTION.",
                draftId,
                revision,
                "/assetKind",
                Map.of());
    }

    private void requireRequest(
            SaveRequest request,
            String draftId,
            long revision) {
        if (request == null
                || !SaveRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw failure(
                    400,
                    "RG.AUTHORING.FIXTURE_REQUEST_INVALID",
                    "A supported versioned fixture save request is required.",
                    draftId,
                    revision,
                    "/schemaVersion",
                    Map.of());
        }
        if (!validFixtureId(request.fixtureId())
                || request.expectedFixtureRevision() < 0
                || request.sourceKind() == null
                || request.assetKind() == null
                || request.assetRef().isBlank()
                || request.assetRef().length() > MAXIMUM_ASSET_REF_LENGTH
                || !sourceMatchesAsset(
                        request.sourceKind(), request.assetKind())
                || request.retentionDays() < 1
                || request.retentionDays() > MAXIMUM_RETENTION_DAYS
                || request.redactionPaths().size() > MAXIMUM_REDACTION_PATHS) {
            throw failure(
                    400,
                    "RG.AUTHORING.FIXTURE_REQUEST_INVALID",
                    "Fixture identity, source, target, retention, or redaction bounds are invalid.",
                    draftId,
                    revision,
                    "/",
                    Map.of(
                            "maximumRetentionDays", MAXIMUM_RETENTION_DAYS,
                            "maximumRedactionPaths", MAXIMUM_REDACTION_PATHS));
        }
        if (!CLASSIFICATIONS.contains(request.classification())) {
            throw failure(
                    400,
                    "RG.AUTHORING.FIXTURE_CLASSIFICATION_INVALID",
                    "Fixture classification must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.",
                    draftId,
                    revision,
                    "/classification",
                    Map.of());
        }
    }

    private TestingArtifactScope requireIdentity(
            IntegrationRequestContext identity, String... purposes) {
        if (identity == null) {
            throw failure(
                    401,
                    "RG.AUTHORING.FIXTURE_AUTHENTICATION_REQUIRED",
                    "An authenticated enterprise identity is required.",
                    "",
                    0,
                    "/",
                    Map.of());
        }
        try {
            identity.requireComplete();
            if (java.util.Arrays.stream(purposes)
                    .noneMatch(identity.purpose()::equals)) {
                throw failure(
                        403,
                        "RG.AUTHORING.FIXTURE_PURPOSE_FORBIDDEN",
                        "The authenticated purpose cannot access authoring fixtures.",
                        "",
                        0,
                        "/",
                        Map.of());
            }
            return TestingArtifactScope.from(identity);
        } catch (AuthoringLifecycleException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw failure(
                    400,
                    "RG.AUTHORING.FIXTURE_SCOPE_INVALID",
                    "Complete tenant, organization, project, environment, and region scope is required.",
                    "",
                    0,
                    "/",
                    Map.of());
        }
    }

    private void requireClearance(
            String classification,
            IntegrationRequestContext identity,
            String draftId,
            long revision) {
        if (!identity.hasClearanceAtLeast(classification)) {
            throw failure(
                    403,
                    "RG.AUTHORING.FIXTURE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot access this fixture classification.",
                    draftId,
                    revision,
                    "/classification",
                    Map.of("classification", classification));
        }
    }

    private RedactionResult redact(
            Object payload,
            List<String> explicitPaths,
            String draftId,
            long revision) {
        JsonNode root;
        try {
            root = objectMapper.valueToTree(payload);
        } catch (IllegalArgumentException invalid) {
            throw failure(
                    400,
                    "RG.AUTHORING.FIXTURE_PAYLOAD_INVALID",
                    "Fixture payload must be bounded JSON data.",
                    draftId,
                    revision,
                    "/payload",
                    Map.of());
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        redactSensitiveKeys(
                root,
                "",
                paths,
                0,
                new PayloadCounter(),
                draftId,
                revision);
        for (String path : explicitPaths) {
            if (!validJsonPointer(path) || path.length() > 512) {
                throw failure(
                        400,
                        "RG.AUTHORING.FIXTURE_REDACTION_PATH_INVALID",
                        "Redaction paths must be bounded JSON Pointers.",
                        draftId,
                        revision,
                        "/redactionPaths",
                        Map.of());
            }
            if (!replaceAtPointer(root, path)) {
                throw failure(
                        400,
                        "RG.AUTHORING.FIXTURE_REDACTION_PATH_NOT_FOUND",
                        "Every explicit redaction path must resolve in the submitted payload.",
                        draftId,
                        revision,
                        "/redactionPaths",
                        Map.of("path", path));
            }
            paths.add(path);
            requireRedactionCapacity(paths, draftId, revision);
        }
        List<String> ordered = paths.stream().sorted().toList();
        return new RedactionResult(
                objectMapper.convertValue(root, Object.class), ordered);
    }

    private void redactSensitiveKeys(
            JsonNode node,
            String path,
            Set<String> paths,
            int depth,
            PayloadCounter counter,
            String draftId,
            long revision) {
        if (depth > MAXIMUM_PAYLOAD_DEPTH
                || ++counter.nodes > MAXIMUM_PAYLOAD_NODES) {
            throw failure(
                    413,
                    "RG.AUTHORING.FIXTURE_PAYLOAD_COMPLEXITY_EXCEEDED",
                    "Fixture payload exceeds the bounded depth or node limit.",
                    draftId,
                    revision,
                    "/payload",
                    Map.of(
                            "maximumDepth", MAXIMUM_PAYLOAD_DEPTH,
                            "maximumNodes", MAXIMUM_PAYLOAD_NODES));
        }
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String childPath = path + "/" + escapePointer(name);
                if (SENSITIVE_KEY.matcher(name).matches()) {
                    object.set(name, TextNode.valueOf("[REDACTED]"));
                    paths.add(childPath);
                    requireRedactionCapacity(paths, draftId, revision);
                } else {
                    redactSensitiveKeys(
                            object.get(name),
                            childPath,
                            paths,
                            depth + 1,
                            counter,
                            draftId,
                            revision);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                redactSensitiveKeys(
                        array.get(index),
                        path + "/" + index,
                        paths,
                        depth + 1,
                        counter,
                        draftId,
                        revision);
            }
        }
    }

    private static void requireRedactionCapacity(
            Set<String> paths, String draftId, long revision) {
        if (paths.size() > MAXIMUM_REDACTION_PATHS) {
            throw failure(
                    413,
                    "RG.AUTHORING.FIXTURE_REDACTION_LIMIT_EXCEEDED",
                    "Fixture payload contains more sensitive fields than the bounded redaction policy permits.",
                    draftId,
                    revision,
                    "/payload",
                    Map.of("maximumRedactionPaths", MAXIMUM_REDACTION_PATHS));
        }
    }

    private static boolean replaceAtPointer(JsonNode root, String pointer) {
        List<String> segments = parsePointer(pointer);
        if (segments.isEmpty()) {
            return false;
        }
        JsonNode parent = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            parent = child(parent, segments.get(index));
            if (parent == null || parent.isMissingNode()) {
                return false;
            }
        }
        String leaf = segments.getLast();
        if (parent instanceof ObjectNode object && object.has(leaf)) {
            object.set(leaf, TextNode.valueOf("[REDACTED]"));
            return true;
        }
        if (parent instanceof ArrayNode array) {
            try {
                int index = Integer.parseInt(leaf);
                if (index >= 0 && index < array.size()) {
                    array.set(index, TextNode.valueOf("[REDACTED]"));
                    return true;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static JsonNode child(JsonNode parent, String segment) {
        if (parent instanceof ObjectNode object) {
            return object.get(segment);
        }
        if (parent instanceof ArrayNode array) {
            try {
                int index = Integer.parseInt(segment);
                return index >= 0 && index < array.size() ? array.get(index) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> parsePointer(String pointer) {
        if (!validJsonPointer(pointer)) {
            return List.of();
        }
        return java.util.Arrays.stream(pointer.substring(1).split("/", -1))
                .map(value -> value.replace("~1", "/").replace("~0", "~"))
                .toList();
    }

    private static boolean validJsonPointer(String pointer) {
        if (pointer == null || pointer.isBlank() || !pointer.startsWith("/")) {
            return false;
        }
        for (int index = 0; index < pointer.length(); index++) {
            if (pointer.charAt(index) == '~') {
                if (index + 1 >= pointer.length()
                        || (pointer.charAt(index + 1) != '0'
                        && pointer.charAt(index + 1) != '1')) {
                    return false;
                }
                index++;
            }
        }
        return true;
    }

    private String payloadFingerprint(
            Object payload, String draftId, long revision) {
        try {
            return VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, payload, MAXIMUM_PAYLOAD_BYTES);
        } catch (IllegalArgumentException tooLarge) {
            throw failure(
                    413,
                    "RG.AUTHORING.FIXTURE_PAYLOAD_TOO_LARGE",
                    "Fixture payload exceeds the bounded persistence limit.",
                    draftId,
                    revision,
                    "/payload",
                    Map.of("maximumBytes", MAXIMUM_PAYLOAD_BYTES));
        }
    }

    private byte[] jsonBytes(
            Object payload, String draftId, long revision) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            if (bytes.length > MAXIMUM_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("payload too large");
            }
            return bytes;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw failure(
                    413,
                    "RG.AUTHORING.FIXTURE_PAYLOAD_TOO_LARGE",
                    "Fixture payload exceeds the bounded persistence limit.",
                    draftId,
                    revision,
                    "/payload",
                    Map.of("maximumBytes", MAXIMUM_PAYLOAD_BYTES));
        }
    }

    private void appendReadAudit(
            IntegrationRequestContext identity, FixtureReceipt receipt) {
        try {
            securityEvents.append(event(
                    identity,
                    "AUTHORING_FIXTURE_READ",
                    "ACCEPTED",
                    "RG.AUTHORING.FIXTURE_READ",
                    receipt));
        } catch (RuntimeException unavailable) {
            throw failure(
                    503,
                    "RG.AUTHORING.FIXTURE_AUDIT_UNAVAILABLE",
                    "Fixture material cannot be returned because mandatory audit is unavailable.",
                    receipt.draftId(),
                    receipt.authoringRevision(),
                    "/",
                    Map.of());
        }
    }

    private TestSecurityEvent event(
            IntegrationRequestContext identity,
            String type,
            String outcome,
            String reason,
            FixtureReceipt receipt) {
        return new TestSecurityEvent(
                0,
                clock.instant(),
                identity.correlationId(),
                identity.tenantId(),
                identity.environmentId(),
                identity.actorId(),
                type,
                outcome,
                reason,
                Map.of(
                        "fixtureId", receipt.fixtureId(),
                        "revision", receipt.revision(),
                        "assetKind", receipt.assetKind().name(),
                        "assetRef", receipt.assetRef(),
                        "classification", receipt.classification(),
                        "payloadFingerprint", receipt.payloadFingerprint()));
    }

    private static boolean validFixtureId(String value) {
        return value != null && FIXTURE_ID.matcher(value.trim()).matches();
    }

    private static boolean sourceMatchesAsset(
            AuthoringFixtureProtocol.SourceKind source,
            AssetKind asset) {
        return source == AuthoringFixtureProtocol.SourceKind.SAMPLE
                || (source == AuthoringFixtureProtocol.SourceKind.OPERATOR_TEST_CASE
                && asset == AssetKind.OPERATOR)
                || (source == AuthoringFixtureProtocol.SourceKind.FUNCTION_TEST_CASE
                && asset == AssetKind.FUNCTION);
    }

    private static AuthoringLifecycleException targetNotFound(
            String draftId, long revision, String path) {
        return failure(
                404,
                "RG.AUTHORING.FIXTURE_TARGET_NOT_FOUND",
                "Fixture target is not present in this exact draft revision.",
                draftId,
                revision,
                path,
                Map.of());
    }

    private static AuthoringLifecycleException lineageConflict(
            String draftId, long revision) {
        return failure(
                409,
                "RG.AUTHORING.FIXTURE_LINEAGE_CONFLICT",
                "A fixture revision cannot change source, target, draft, or classification; use a new fixture id.",
                draftId,
                revision,
                "/fixtureId",
                Map.of());
    }

    private static AuthoringLifecycleException failure(
            int status,
            String code,
            String message,
            String draftId,
            long revision,
            String path,
            Map<String, Object> details) {
        AuthoringDiagnostic diagnostic = AuthoringDiagnostic.compiler(
                "ERROR",
                code,
                message,
                path,
                -1,
                details);
        return new AuthoringLifecycleException(AuthoringProblem.of(
                code,
                message,
                status,
                draftId,
                revision,
                List.of(diagnostic)));
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record RedactionResult(Object payload, List<String> paths) {
    }

    private static final class PayloadCounter {
        private int nodes;
    }
}
