package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetGovernance;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetMetadata;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogCodec;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import com.leanowtech.bloge.gateway.testing.world.persistence.TrustedTenant;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Durable unpublished draft store and governed catalog publication adapter. */
public final class DatabaseWorldDraftAssetRepository implements WorldDraftAssetRepository {
    private static final String VERSION = "rg.worldDraftAsset.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GovernedCatalogCodec catalogCodec;
    private final GovernedCatalogRepository catalog;
    private final WorldDraftAuditSink audit;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public DatabaseWorldDraftAssetRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                             GovernedCatalogRepository catalog,
                                             WorldDraftAuditSink audit) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null || catalog == null || audit == null) {
            throw invalid();
        }
        this.jdbc = jdbc; this.mapper = mapper.copy(); this.catalogCodec = new GovernedCatalogCodec(mapper);
        this.catalog = catalog; this.audit = audit;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.postgres = isPostgres(jdbc);
    }

    @Override public StoredAsset saveDraft(WorldDraftMaterializer.MaterializedDraft draft,
                                            WorldDraftCandidateService.Access access) {
        authorize(draft == null ? null : draft.worldModel().tenantId(), access);
        if (draft == null || draft.published() || draft.candidate().state() != WorldDraftState.APPROVED) throw invalid();
        StoredAsset asset = new StoredAsset(draft);
        String json = encode(asset);
        try {
            jdbc.update("INSERT INTO rg_world_draft_assets"
                            + "(tenant_id,candidate_id,materialization_fingerprint,materialization_revision,"
                            + "world_fingerprint,rule_fingerprint,status,canonical_json) VALUES (?,?,?,?,?,?,?,%s)"
                            .formatted(postgres ? "CAST(? AS JSONB)" : "?"),
                    asset.tenantId(), asset.candidateId(), asset.materializationFingerprint(), asset.materializationRevision(),
                    asset.worldModel().fingerprint(), asset.rule().fingerprint(), "DRAFT", json);
            audit.record(asset.tenantId(), asset.candidateId(), "ASSET_DRAFT", asset.materializationRevision(), true);
            return asset;
        } catch (DuplicateKeyException duplicate) {
            StoredAsset existing = find(asset.tenantId(), asset.candidateId(), asset.materializationFingerprint(), access)
                    .orElseThrow(DatabaseWorldDraftAssetRepository::invalid);
            if (!same(existing, asset)) throw conflict();
            return existing;
        } catch (RuntimeException failure) { throw failure instanceof WorldDraftCandidateException e ? e : invalid(); }
    }

    @Override public Optional<StoredAsset> find(String tenantId, String candidateId,
                                                 String materializationFingerprint,
                                                 WorldDraftCandidateService.Access access) {
        authorize(tenantId, access);
        if (candidateId == null || materializationFingerprint == null) return Optional.empty();
        return jdbc.query("SELECT canonical_json,status,publication_receipt_fingerprint"
                        + " FROM rg_world_draft_assets WHERE tenant_id=? AND candidate_id=?"
                        + " AND materialization_fingerprint=?",
                result -> result.next() ? Optional.of(decode(result.getString(1), result.getString(2), result.getString(3)))
                        : Optional.empty(), tenantId, candidateId, materializationFingerprint);
    }

    @Override public StoredAsset publish(StoredAsset asset, WorldDraftCandidate candidate,
                                         WorldDraftPublicationReceipt receipt,
                                         WorldDraftCandidateService.Access access) {
        authorize(asset == null ? null : asset.tenantId(), access);
        if (asset == null || candidate == null || receipt == null || asset.published()
                || !candidate.candidateId().equals(asset.candidateId())
                || !candidate.tenantId().equals(asset.tenantId())
                || !candidate.materializationFingerprint().equals(asset.materializationFingerprint())
                || receipt.candidateRevision() != candidate.revision()
                || !receipt.materializationFingerprint().equals(asset.materializationFingerprint())) throw conflict();
        String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt);
        StoredAsset published = asset.asPublished(receiptFingerprint);
        try {
            transactions.executeWithoutResult(status -> {
                publishCatalog(asset, receipt, access);
                int updated = jdbc.update("UPDATE rg_world_draft_assets SET status='PUBLISHED',"
                                + "publication_receipt_fingerprint=?,updated_at=CURRENT_TIMESTAMP"
                                + " WHERE tenant_id=? AND candidate_id=? AND materialization_fingerprint=? AND status='DRAFT'",
                        receiptFingerprint, asset.tenantId(), asset.candidateId(), asset.materializationFingerprint());
                if (updated != 1) throw conflict();
            });
            audit.record(asset.tenantId(), asset.candidateId(), "ASSET_PUBLISH", candidate.revision(), true);
            return published;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(); }
    }

    private void publishCatalog(StoredAsset asset, WorldDraftPublicationReceipt receipt,
                                WorldDraftCandidateService.Access access) {
        GovernedAssetGovernance governance = new GovernedAssetGovernance(
                com.leanowtech.bloge.gateway.testing.world.persistence.GovernedPayloadOrigin.REDACTED,
                com.leanowtech.bloge.gateway.testing.world.persistence.GovernedSecurityClassification.INTERNAL,
                null, "world-draft:" + asset.candidateId(), receipt.ticket());
        GovernedAssetMetadata metadata = new GovernedAssetMetadata(governance);
        upsertCatalog(asset.worldModel(), GovernedCatalogKind.RESOURCE_WORLD_MODEL, asset.worldModel().worldModelId(),
                asset.worldModel().revision(), asset.worldModel().fingerprint(), metadata);
        asset.scenario().ifPresent(scenario -> upsertCatalog(scenario, GovernedCatalogKind.SCENARIO,
                scenario.scenarioId(), scenario.revision(), scenario.fingerprint(), metadata));
    }

    private void upsertCatalog(Object value, GovernedCatalogKind kind, String id, long revision,
                               String fingerprint, GovernedAssetMetadata metadata) {
        GovernedResourceRef ref = new GovernedResourceRef(new TrustedTenant(
                value instanceof ResourceWorldModel world ? world.tenantId() : ((Scenario) value).tenantId()),
                kind, id, revision, fingerprint);
        if (catalog.findExact(ref).isPresent()) return;
        catalog.create(ref.tenant(), kind, id, value, metadata);
    }

    private String encode(StoredAsset asset) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("schemaVersion", VERSION); root.put("tenantId", asset.tenantId());
            root.put("candidateId", asset.candidateId()); root.put("materializationRevision", asset.materializationRevision());
            root.put("materializationFingerprint", asset.materializationFingerprint());
            root.put("worldFingerprint", asset.worldModel().fingerprint()); root.put("ruleFingerprint", asset.rule().fingerprint());
            root.set("world", mapper.readTree(catalogCodec.encode(new TrustedTenant(asset.tenantId()),
                    GovernedCatalogKind.RESOURCE_WORLD_MODEL, asset.worldModel().worldModelId(),
                    asset.worldModel().revision(), asset.worldModel())));
            asset.scenario().ifPresent(scenario -> {
                try { root.set("scenario", mapper.readTree(catalogCodec.encode(new TrustedTenant(asset.tenantId()),
                        GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario.revision(), scenario))); }
                catch (Exception failure) { throw invalid(); }
            });
            if (asset.scenario().isEmpty()) root.putNull("scenario");
            root.set("rule", ruleJson(asset.rule())); root.set("provenance", mapper.valueToTree(asset.provenance()));
            return mapper.writeValueAsString(root);
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    private StoredAsset decode(String json, String status, String receiptFingerprint) {
        try {
            if (!"DRAFT".equals(status) && !"PUBLISHED".equals(status)) throw invalid();
            JsonNode root = mapper.readTree(json);
            if (!VERSION.equals(root.path("schemaVersion").asText()) || !root.path("world").isObject()
                    || !root.path("rule").isObject() || !root.path("provenance").isObject()) throw invalid();
            String tenant = root.path("tenantId").asText();
            JsonNode worldEnvelope = root.get("world");
            String id = worldEnvelope.path("id").asText(); long revision = worldEnvelope.path("revision").asLong();
            String fingerprint = worldEnvelope.path("fingerprint").asText();
            ResourceWorldModel world = (ResourceWorldModel) catalogCodec.decode(mapper.writeValueAsString(worldEnvelope),
                    new TrustedTenant(tenant), GovernedCatalogKind.RESOURCE_WORLD_MODEL, id, revision, fingerprint,
                    ignored -> { throw invalid(); });
            Optional<Scenario> scenario = root.path("scenario").isObject()
                    ? Optional.of((Scenario) catalogCodec.decode(mapper.writeValueAsString(root.get("scenario")),
                    new TrustedTenant(tenant), GovernedCatalogKind.SCENARIO,
                    root.get("scenario").path("id").asText(), root.get("scenario").path("revision").asLong(),
                    root.get("scenario").path("fingerprint").asText(), ref -> world)) : Optional.empty();
            WorldDraftRule rule = decodeRule(root.get("rule"));
            WorldDraftProvenance provenance = mapper.treeToValue(root.get("provenance"), WorldDraftProvenance.class);
            if (!tenant.equals(world.tenantId())
                    || !root.path("worldFingerprint").asText().equals(world.fingerprint())
                    || !root.path("ruleFingerprint").asText().equals(rule.fingerprint())
                    || !root.path("materializationFingerprint").asText().equals(rule.fingerprint())) throw invalid();
            boolean published = "PUBLISHED".equals(status);
            return new StoredAsset(tenant, root.path("candidateId").asText(), root.path("materializationRevision").asLong(),
                    world, rule, scenario, provenance, published, published ? receiptFingerprint : "");
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    private ObjectNode ruleJson(WorldDraftRule rule) {
        ObjectNode node = mapper.createObjectNode(); node.put("requestSchemaFingerprint", rule.requestSchemaFingerprint());
        node.put("inputFingerprint", rule.inputFingerprint()); node.put("responseFingerprint", rule.responseFingerprint());
        WorldDraftRedactedPayloadRef payloadRef = rule.redactedPayloadRef();
        if (payloadRef == null) throw invalid();
        ObjectNode ref = node.putObject("redactedPayloadRef");
        ref.put("requestFingerprint", payloadRef.requestFingerprint());
        ref.put("responseFingerprint", payloadRef.responseFingerprint());
        ref.put("pairFingerprint", payloadRef.pairFingerprint());
        ref.put("tenantId", payloadRef.tenantId());
        ref.put("candidateId", payloadRef.candidateId());
        ref.put("artifactRevision", payloadRef.artifactRevision());
        if (rule.fragment() == null) node.putNull("fragment");
        else { var fragment = node.putObject("fragment"); BlogeFragmentRef fragmentRef = rule.fragment().blogeFragment();
            fragment.put("artifactId", fragmentRef.artifactId()); fragment.put("revision", fragmentRef.revision());
            fragment.put("source", fragmentRef.source()); fragment.put("outputNodeId", fragmentRef.outputNodeId()); fragment.put("fingerprint", fragmentRef.fingerprint()); }
        return node;
    }

    private WorldDraftRule decodeRule(JsonNode node) {
        JsonNode refNode = node.get("redactedPayloadRef");
        if (refNode == null || !refNode.isObject()) throw invalid();
        WorldDraftRedactedPayloadRef payloadRef = new WorldDraftRedactedPayloadRef(
                refNode.path("requestFingerprint").asText(), refNode.path("responseFingerprint").asText(),
                refNode.path("pairFingerprint").asText(), refNode.path("tenantId").asText(),
                refNode.path("candidateId").asText(), refNode.path("artifactRevision").asLong());
        WorldDraftFragmentRef fragment = null;
        if (node.get("fragment") != null && !node.get("fragment").isNull()) {
            JsonNode value = node.get("fragment");
            BlogeFragmentRef ref = BlogeFragmentRef.frozen(value.path("artifactId").asText(), value.path("revision").asLong(),
                    value.path("source").asText(), value.path("outputNodeId").asText());
            if (!ref.fingerprint().equals(value.path("fingerprint").asText())) throw invalid();
            fragment = new WorldDraftFragmentRef(ref);
        }
        return new WorldDraftRule(node.path("requestSchemaFingerprint").asText(), node.path("inputFingerprint").asText(),
                node.path("responseFingerprint").asText(), fragment, payloadRef);
    }

    private static boolean same(StoredAsset left, StoredAsset right) {
        return left.worldModel().fingerprint().equals(right.worldModel().fingerprint())
                && left.rule().fingerprint().equals(right.rule().fingerprint())
                && left.provenance().equals(right.provenance());
    }
    private static void authorize(String tenant, WorldDraftCandidateService.Access access) {
        if (tenant == null || access == null || !tenant.equals(access.tenantId())) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
    private static boolean isPostgres(JdbcTemplate jdbc) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (Exception failure) { throw invalid(); }
    }
    private static WorldDraftCandidateException conflict() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.CAS_CONFLICT);
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }
}
