package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and validates the exact capability-mirror schemas packaged with the test-kit JAR. */
final class CapabilityMirrorSchemaValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA_ID_ROOT =
            "https://bloge.dev/schemas/resource-gateway-mirror/";
    private static final List<String> SCHEMA_NAMES = List.of(
            "artifact-provenance-v1.schema.json",
            "effect-contract-v1.schema.json",
            "capability-contract-v1.schema.json",
            "capability-snapshot-v1.schema.json",
            "capability-closure-v1.schema.json",
            "capability-lifecycle-transition-v1.schema.json",
            "capability-mirror-compatibility-v1.schema.json",
            "mirror-execution-request-v1.schema.json",
            "mirror-execution-request-v2.schema.json",
            "mirror-run-summary-v1.schema.json",
            "mirror-resolution-v1.schema.json",
            "mirror-run-evidence-v1.schema.json",
            "mirror-run-evidence-v2.schema.json",
            "mirror-run-evidence-v3.schema.json",
            "mirror-run-evidence-v4.schema.json",
            "mirror-run-evidence-v5.schema.json",
            "mirror-state-run-evidence-v1.schema.json",
            "mirror-state-run-evidence-v2.schema.json",
            "mirror-state-run-evidence-v3.schema.json",
            "mirror-state-workbook-seed-v1.schema.json",
            "mirror-state-transition-workbook-seed-v1.schema.json",
            "mirror-state-write-outcome-workbook-seed-v1.schema.json",
            "mirror-evidence-attestation-v1.schema.json",
            "mirror-evidence-attestation-v2.schema.json",
            "mirror-evidence-attestation-v3.schema.json",
            "mirror-evidence-attestation-v4.schema.json",
            "mirror-evidence-attestation-v5.schema.json",
            "mirror-evidence-bundle-v1.schema.json",
            "mirror-evidence-bundle-v2.schema.json",
            "mirror-evidence-bundle-v3.schema.json",
            "mirror-evidence-bundle-v4.schema.json",
            "mirror-evidence-bundle-v5.schema.json",
            "mirror-deployment-isolation-attestation-v1.schema.json",
            "mirror-deployment-isolation-attestation-status-v1.schema.json",
            "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json",
            "mirror-deployment-isolation-attestation-bundle-v1.schema.json",
            "mirror-deployment-isolation-agent-snapshot-v1.schema.json",
            "mirror-deployment-isolation-run-trust-v1.schema.json",
            "mirror-deployment-isolation-authority-key-set-publication-v1.schema.json",
            "capability-observation-v1.schema.json",
            "capability-observation-admission-v1.schema.json",
            "capability-observation-receipt-v1.schema.json",
            "capability-observation-review-request-v1.schema.json",
            "capability-observation-review-v1.schema.json",
            "capability-corpus-candidate-request-v1.schema.json",
            "capability-corpus-revision-v1.schema.json",
            "capability-corpus-publish-request-v1.schema.json",
            "capability-corpus-publication-v1.schema.json",
            "capability-corpus-trajectory-publish-request-v1.schema.json",
            "capability-corpus-trajectory-publication-v1.schema.json",
            "capability-corpus-cluster-validation-v1.schema.json",
            "capability-corpus-cluster-publish-request-v1.schema.json",
            "capability-corpus-cluster-publication-v1.schema.json",
            "fixture-mirror-corpus-bindings-v1.schema.json",
            "fixture-mirror-trajectory-bindings-v1.schema.json",
            "fixture-mirror-cluster-bindings-v1.schema.json",
            "bounded-state-expression-v1.schema.json",
            "state-model-v1.schema.json",
            "state-read-spec-v1.schema.json",
            "write-effect-spec-v1.schema.json",
            "session-state-space-v1.schema.json",
            "mirror-session-payload-v1.schema.json",
            "mirror-session-create-request-v1.schema.json",
            "mirror-session-descriptor-v1.schema.json",
            "mirror-session-command-request-v1.schema.json",
            "mirror-session-command-result-v1.schema.json",
            "mirror-session-store-generation-v1.schema.json",
            "mirror-state-write-attempt-v1.schema.json",
            "mirror-session-checkpoint-v1.schema.json",
            "mirror-session-checkpoint-attestation-v1.schema.json",
            "mirror-session-checkpoint-bundle-v1.schema.json",
            "mirror-session-recovery-result-v1.schema.json",
            "stateful-refund-stage3-v1.fixture.schema.json");
    private static final Map<String, String> RESOURCE_TO_ID = resourceIds();

    private CapabilityMirrorSchemaValidator() {
    }

    static void require(JsonNode value, String resource, String failureCode) {
        String schemaId = RESOURCE_TO_ID.get(resource);
        if (value == null || schemaId == null) {
            throw invalid(failureCode);
        }
        List<com.networknt.schema.Error> errors;
        try {
            Schema schema = registryInstance().getSchema(SchemaLocation.of(schemaId));
            errors = schema.validate(value,
                    context -> context.executionConfig(config -> config
                            .formatAssertionsEnabled(true)
                            .failFast(true)));
        } catch (RuntimeException failure) {
            throw invalid(failureCode);
        }
        if (!errors.isEmpty()) {
            throw invalid(failureCode);
        }
    }

    private static Map<String, String> resourceIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String name : SCHEMA_NAMES) {
            ids.put(CapabilityMirrorProtocol.SCHEMA_RESOURCE_ROOT + name, SCHEMA_ID_ROOT + name);
        }
        return Map.copyOf(ids);
    }

    private static SchemaRegistry registry() {
        Map<String, String> schemas = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : RESOURCE_TO_ID.entrySet()) {
            schemas.put(entry.getValue(), load(entry.getKey(), entry.getValue()));
        }
        try {
            return SchemaRegistry.withDialect(Dialects.getDraft202012(),
                    builder -> builder.schemas(schemas));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static SchemaRegistry registryInstance() {
        try {
            return RegistryHolder.REGISTRY;
        } catch (ExceptionInInitializerError | NoClassDefFoundError failure) {
            throw unavailable();
        }
    }

    private static String load(String resource, String expectedId) {
        try (InputStream input = CapabilityMirrorSchemaValidator.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Schema is absent");
            }
            JsonNode schema = JSON.readTree(input);
            if (!schema.isObject() || !expectedId.equals(schema.path("$id").asText())) {
                throw new IOException("Schema id does not match its packaged resource");
            }
            return JSON.writeValueAsString(schema);
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static IllegalArgumentException invalid(String code) {
        String safe = code == null ? "" : code.trim();
        if (!safe.matches("RG\\.MIRROR\\.CLIENT\\.[A-Z0-9_.]{1,191}")) {
            safe = "RG.MIRROR.CLIENT.SCHEMA_INVALID";
        }
        return new IllegalArgumentException(safe);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("RG.MIRROR.CLIENT.SCHEMA_UNAVAILABLE");
    }

    private static final class RegistryHolder {
        private static final SchemaRegistry REGISTRY = registry();
    }
}
