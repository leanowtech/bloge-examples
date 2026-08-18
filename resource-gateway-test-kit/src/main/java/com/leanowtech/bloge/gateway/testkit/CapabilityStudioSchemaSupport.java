package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Package-private Draft 2020-12 loader for Capability Studio schemas. */
final class CapabilityStudioSchemaSupport {
    static final String BASELINE_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-acceptance-baseline-v1.schema.json";
    static final String MANIFEST_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-golden-path-acceptance-manifest-v1.schema.json";
    static final String BRANCH_PROJECTION_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-branch-projection-v1.schema.json";
    static final String BRANCH_UPDATE_REQUEST_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-branch-update-request-v1.schema.json";
    static final String PREFLIGHT_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-preflight-v1.schema.json";
    static final String ERROR_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-error-v1.schema.json";
    static final String SCENARIO_DATASET_PROJECTION_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-scenario-dataset-projection-v1.schema.json";
    static final String FEATURE_REHEARSAL_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-feature-rehearsal-v1.schema.json";
    static final String FEATURE_REHEARSAL_BASELINE_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-feature-rehearsal-baseline-v1.schema.json";
    static final String GOVERNED_BASELINE_V1_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-governed-baseline-v1.schema.json";
    static final String GOVERNED_BASELINE_V2_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-governed-baseline-v2.schema.json";
    static final String GOVERNED_BASELINE_V3_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-governed-baseline-v3.schema.json";
    /** Default governed-baseline contract for current callers. */
    static final String GOVERNED_BASELINE_RESOURCE = GOVERNED_BASELINE_V3_RESOURCE;
    static final String GOVERNED_RUN_EVIDENCE_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-governed-run-evidence-v1.schema.json";
    static final String SCENARIO_QUALITY_IMPACT_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-scenario-quality-impact-v1.schema.json";
    static final String BROWSER_MATRIX_RESULT_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-browser-matrix-result-v1.schema.json";
    static final String BROWSER_ANOMALY_MATRIX_RESULT_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-browser-anomaly-matrix-result-v1.schema.json";
    static final String BROWSER_EVIDENCE_BUNDLE_MANIFEST_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "browser-evidence-bundle-manifest-v1.schema.json";
    static final String STAGE_ACCEPTANCE_RESULT_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-stage-acceptance-result-v1.schema.json";
    static final String STAGE_ACCEPTANCE_RESULT_V2_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-stage-acceptance-result-v2.schema.json";
    static final String AUTHORITY_EVIDENCE_ENVELOPE_V1_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-authority-evidence-envelope-v1.schema.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDialect(Dialects.getDraft202012());
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private CapabilityStudioSchemaSupport() {
    }

    static List<Error> validate(JsonNode value, String resource) {
        if (value == null) {
            return List.of();
        }
        return schema(resource).validate(value.toString(), InputFormat.JSON,
                context -> context.executionConfig(config -> config
                        .formatAssertionsEnabled(true)
                        .failFast(true)));
    }

    private static Schema schema(String resource) {
        return SCHEMAS.computeIfAbsent(resource, CapabilityStudioSchemaSupport::load);
    }

    private static Schema load(String resource) {
        try (InputStream input = CapabilityStudioSchemaSupport.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Capability Studio schema is absent");
            }
            JsonNode schema = JSON.readTree(input);
            if (schema == null || !schema.isObject()) {
                throw new IOException("Capability Studio schema is invalid");
            }
            return REGISTRY.getSchema(schema.toString(), InputFormat.JSON);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "RG.CAPABILITY_STUDIO.VERIFIER_SCHEMA_UNAVAILABLE");
        }
    }
}
