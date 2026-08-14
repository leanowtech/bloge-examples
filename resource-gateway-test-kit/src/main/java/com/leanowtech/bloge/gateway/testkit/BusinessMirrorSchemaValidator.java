package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the complete Business Mirror v1 schema dependency closure from the test-kit JAR. */
final class BusinessMirrorSchemaValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUSINESS_SCHEMA_ID_ROOT =
            "https://bloge.dev/schemas/resource-gateway-business-mirror/";
    private static final String MIRROR_SCHEMA_ID_ROOT =
            "https://bloge.dev/schemas/resource-gateway-mirror/";
    private static final String MIRROR_RESOURCE_ROOT = "/schemas/resource-gateway-mirror/";
    private static final List<String> BUSINESS_SCHEMA_NAMES = List.of(
            "business-mirror-common-v1.schema.json",
            "business-asset-link-v1.schema.json",
            "business-asset-link-closure-v1.schema.json",
            "domain-capability-package-draft-v1.schema.json",
            "stored-domain-capability-package-draft-v1.schema.json",
            "domain-capability-package-save-receipt-v1.schema.json",
            "domain-capability-package-page-v1.schema.json",
            "package-compilation-receipt-v1.schema.json",
            "legacy-graph-package-projection-v1.schema.json",
            "legacy-graph-package-projection-catalog-v1.schema.json",
            "domain-capability-package-snapshot-v1.schema.json",
            "package-readiness-report-v1.schema.json",
            "capability-proposal-draft-v1.schema.json",
            "stored-capability-proposal-draft-v1.schema.json",
            "capability-proposal-save-receipt-v1.schema.json",
            "capability-proposal-page-v1.schema.json",
            "capability-proposal-snapshot-v1.schema.json",
            "capability-proposal-simulation-request-v1.schema.json",
            "capability-proposal-simulation-evidence-v1.schema.json",
            "stored-capability-proposal-simulation-v1.schema.json",
            "capability-implementation-binding-request-v1.schema.json",
            "capability-implementation-binding-v1.schema.json",
            "stored-capability-implementation-binding-v1.schema.json");
    private static final List<String> MIRROR_SCHEMA_NAMES = List.of(
            "artifact-provenance-v1.schema.json",
            "effect-contract-v1.schema.json",
            "capability-contract-v1.schema.json");
    private static final Map<String, String> RESOURCE_TO_ID = resourceIds();

    private BusinessMirrorSchemaValidator() {
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
        for (String name : BUSINESS_SCHEMA_NAMES) {
            ids.put(BusinessMirrorProtocol.SCHEMA_RESOURCE_ROOT + name,
                    BUSINESS_SCHEMA_ID_ROOT + name);
        }
        for (String name : MIRROR_SCHEMA_NAMES) {
            ids.put(MIRROR_RESOURCE_ROOT + name, MIRROR_SCHEMA_ID_ROOT + name);
        }
        return Map.copyOf(ids);
    }

    private static SchemaRegistry registry() {
        Map<String, String> schemas = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : RESOURCE_TO_ID.entrySet()) {
            schemas.put(entry.getValue(), load(entry.getKey(), entry.getValue()));
        }
        requireReferenceClosure(schemas);
        try {
            return SchemaRegistry.withDialect(Dialects.getDraft202012(),
                    builder -> builder.schemas(schemas));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void requireReferenceClosure(Map<String, String> schemas) {
        for (Map.Entry<String, String> entry : schemas.entrySet()) {
            JsonNode schema;
            try {
                schema = JSON.readTree(entry.getValue());
            } catch (IOException failure) {
                throw unavailable();
            }
            URI base = URI.create(entry.getKey());
            for (JsonNode reference : schema.findValues("$ref")) {
                if (!reference.isTextual() || reference.textValue().startsWith("#")) {
                    continue;
                }
                String target = base.resolve(reference.textValue()).toString().split("#", 2)[0];
                if (!schemas.containsKey(target)) {
                    throw unavailable();
                }
            }
        }
    }

    private static String load(String resource, String expectedId) {
        try (InputStream input = BusinessMirrorSchemaValidator.class.getResourceAsStream(resource)) {
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

    private static SchemaRegistry registryInstance() {
        try {
            return RegistryHolder.REGISTRY;
        } catch (ExceptionInInitializerError | NoClassDefFoundError failure) {
            throw unavailable();
        }
    }

    private static IllegalArgumentException invalid(String code) {
        String safe = code == null ? "" : code.trim();
        if (!safe.matches("RG\\.BUSINESS_MIRROR\\.CLIENT\\.[A-Z0-9_.]{1,191}")) {
            safe = "RG.BUSINESS_MIRROR.CLIENT.SCHEMA_INVALID";
        }
        return new IllegalArgumentException(safe);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("RG.BUSINESS_MIRROR.CLIENT.SCHEMA_UNAVAILABLE");
    }

    private static final class RegistryHolder {
        private static final SchemaRegistry REGISTRY = registry();
    }
}
