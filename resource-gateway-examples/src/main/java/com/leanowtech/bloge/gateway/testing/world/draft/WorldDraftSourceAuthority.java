package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Map;

/** Metadata-first source port; only its second method may read source payload storage. */
public interface WorldDraftSourceAuthority {
    SourceMetadata inspect(WorldDraftSourceRef source, WorldDraftCandidateService.Access access);

    SourcePayload read(SourceMetadata metadata, WorldDraftCandidateService.Access access);

    final class SourceMetadata {
        private final WorldDraftSourceRef source;
        private final String tenantId;
        private final boolean published;
        private final boolean valid;
        private final Instant expiresAt;
        private final SchemaEnvelope requestSchema;
        private final SchemaEnvelope responseSchema;
        private final String schemaFingerprint;
        private final String redactionPolicyFingerprint;
        private final String requestFingerprint;
        private final String responseFingerprint;
        private final String metadataFingerprint;

        private SourceMetadata(WorldDraftSourceRef source, String tenantId, boolean published, boolean valid,
                               Instant expiresAt, SchemaEnvelope requestSchema, SchemaEnvelope responseSchema,
                               String schemaFingerprint, String redactionPolicyFingerprint,
                               String requestFingerprint, String responseFingerprint, String metadataFingerprint) {
            if (source == null || tenantId == null || tenantId.isBlank() || expiresAt == null
                    || requestSchema == null || responseSchema == null
                    || !fp(schemaFingerprint) || !fp(redactionPolicyFingerprint)
                    || !fp(requestFingerprint) || !fp(responseFingerprint) || !fp(metadataFingerprint)) throw invalid();
            this.source = source; this.tenantId = tenantId.trim(); this.published = published; this.valid = valid;
            this.expiresAt = expiresAt; this.requestSchema = requestSchema; this.responseSchema = responseSchema;
            this.schemaFingerprint = schemaFingerprint; this.redactionPolicyFingerprint = redactionPolicyFingerprint;
            this.requestFingerprint = requestFingerprint; this.responseFingerprint = responseFingerprint;
            this.metadataFingerprint = metadataFingerprint;
        }

        static SourceMetadata sealed(WorldDraftSourceRef source, String tenantId,
                                            boolean published, boolean valid, Instant expiresAt,
                                            SchemaEnvelope requestSchema, SchemaEnvelope responseSchema,
                                            String policyFingerprint, String requestFingerprint,
                                            String responseFingerprint) {
            String schema = VisualBundleFingerprint.fromMaterial(Map.of(
                    "request", schemaMaterial(requestSchema), "response", schemaMaterial(responseSchema)));
            String metadata = VisualBundleFingerprint.fromMaterial(Map.of("source", source,
                    "tenantId", tenantId, "published", published, "valid", valid,
                    "expiresAt", expiresAt.toString(), "schemaFingerprint", schema,
                    "redactionPolicyFingerprint", policyFingerprint,
                    "requestFingerprint", requestFingerprint, "responseFingerprint", responseFingerprint));
            return new SourceMetadata(source, tenantId, published, valid, expiresAt,
                    requestSchema, responseSchema, schema, policyFingerprint,
                    requestFingerprint, responseFingerprint, metadata);
        }

        static SourceMetadata unsafeForTest(WorldDraftSourceRef source, String tenantId, boolean published,
                                            boolean valid, Instant expiresAt, SchemaEnvelope requestSchema,
                                            SchemaEnvelope responseSchema, String schemaFingerprint,
                                            String policyFingerprint, String requestFingerprint,
                                            String responseFingerprint, String metadataFingerprint) {
            return new SourceMetadata(source, tenantId, published, valid, expiresAt, requestSchema,
                    responseSchema, schemaFingerprint, policyFingerprint, requestFingerprint,
                    responseFingerprint, metadataFingerprint);
        }

        public WorldDraftSourceRef source() { return source; }
        public String tenantId() { return tenantId; }
        public boolean published() { return published; }
        public boolean valid() { return valid; }
        public Instant expiresAt() { return expiresAt; }
        public SchemaEnvelope requestSchema() { return requestSchema; }
        public SchemaEnvelope responseSchema() { return responseSchema; }
        public String schemaFingerprint() { return schemaFingerprint; }
        public String redactionPolicyFingerprint() { return redactionPolicyFingerprint; }
        public String requestFingerprint() { return requestFingerprint; }
        public String responseFingerprint() { return responseFingerprint; }
        public String metadataFingerprint() { return metadataFingerprint; }

        public String recomputedFingerprint() {
            return VisualBundleFingerprint.fromMaterial(Map.of("source", source,
                    "tenantId", tenantId, "published", published, "valid", valid,
                    "expiresAt", expiresAt.toString(), "schemaFingerprint", schemaFingerprint,
                    "redactionPolicyFingerprint", redactionPolicyFingerprint,
                    "requestFingerprint", requestFingerprint, "responseFingerprint", responseFingerprint));
        }

        public String recomputedSchemaFingerprint() {
            return VisualBundleFingerprint.fromMaterial(Map.of(
                    "request", schemaMaterial(requestSchema), "response", schemaMaterial(responseSchema)));
        }

        private static Map<String, Object> schemaMaterial(SchemaEnvelope schema) {
            if (schema == null) throw invalid();
            return Map.of("format", schema.format(), "version", schema.version(), "schema", schema.schema());
        }
        private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
        private static WorldDraftCandidateException invalid() {
            return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        }

        @Override public String toString() { return "SourceMetadata[source=" + source + ",fingerprinted]"; }
    }

    /** Frozen raw hand-off; its value accessors are package-private and never part of a candidate. */
    final class SourcePayload {
        private final Object request;
        private final Object response;

        SourcePayload(Object request, Object response) {
            try {
                this.request = ProtocolJsonValue.freeze(request);
                this.response = ProtocolJsonValue.freeze(response);
            } catch (RuntimeException invalid) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_READ_FAILED);
            }
        }

        Object request() { return request; }
        Object response() { return response; }
        String requestFingerprint() { return ProtocolFingerprint.of(MAPPER, request); }
        String responseFingerprint() { return ProtocolFingerprint.of(MAPPER, response); }
        @Override public String toString() { return "SourcePayload[fingerprinted]"; }
    }

    com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules();
}
