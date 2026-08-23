package com.leanowtech.bloge.gateway.testkit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Wire and schema conformance tests for Capability Studio acceptance wire contracts.
 * Uses networknt json-schema-validator 2.0.4 new API:
 *   SchemaRegistry.withDialect(Drafts.getDraft202012()),
 *   registry.getSchema(SchemaLocation.of(...), schemaText, InputFormat.JSON),
 *   schema.validate(jsonText, InputFormat.JSON, ctx -> ctx.executionConfig(cfg -> cfg.failFast(false))).
 */
@DisplayName("CapabilityStudio Wire Schema Conformance")
class CapabilityStudioWireAndSchemaTest {

    static ObjectMapper STRICT_MAPPER;
    static SchemaRegistry registry;

    static final Map<String, String> SCHEMA_PATHS = Map.of(
        "bloge.capability-studio.acceptance-plan.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-acceptance-plan-v1.schema.json",
        "bloge.capability-studio.contract-catalog.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-contract-catalog-v1.schema.json",
        "bloge.capability-studio.compiled-plan.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-compiled-acceptance-plan-v1.schema.json",
        "bloge.capability-studio.compiled-plan-verification-result.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-compiled-plan-verification-result-v1.schema.json"
    );

    static final Map<String, Schema> SCHEMAS = new HashMap<>();
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setup() throws Exception {
        registry = SchemaRegistry.withDialect(Dialects.getDraft202012());

        for (Map.Entry<String, String> e : SCHEMA_PATHS.entrySet()) {
            String key = e.getKey();
            String path = e.getValue();
            try (InputStream in = CapabilityStudioWireAndSchemaTest.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Schema resource not found on classpath: " + path);
                }
                String schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Schema schema = registry.getSchema(
                    SchemaLocation.of(path), schemaText, InputFormat.JSON);
                SCHEMAS.put(key, schema);
            }
        }

        // STRICT_DUPLICATE_DETECTION via JsonFactory.builder().enable(...)
        STRICT_MAPPER = new ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Validate fixture text against the named schema using the networknt 2.0.4
     * String overload with InputFormat.JSON and ExecutionContextCustomizer.
     */
    private List<Error> validateSchema(String schemaKey, String fixtureRaw) {
        Schema schema = SCHEMAS.get(schemaKey);
        assertThat(schema).as("Schema '%s' must be registered", schemaKey).isNotNull();
        return schema.validate(
            fixtureRaw,
            InputFormat.JSON,
            (ExecutionContext ctx) -> ctx.executionConfig(cfg -> cfg.failFast(false)));
    }

    private void assertValid(String schemaKey, String fixtureRaw) {
        List<Error> msgs = validateSchema(schemaKey, fixtureRaw);
        assertThat(msgs)
            .as("Schema '%s' must accept valid fixture", schemaKey)
            .isEmpty();
    }

    private void assertInvalid(String schemaKey, String fixtureRaw) {
        List<Error> msgs = validateSchema(schemaKey, fixtureRaw);
        assertThat(msgs)
            .as("Schema '%s' must reject invalid fixture", schemaKey)
            .isNotEmpty();
    }

    /**
     * Load fixture as exact bytes, fail-fast on null resource.
     */
    private String loadFixture(String name) {
        InputStream in = CapabilityStudioWireAndSchemaTest.class
            .getResourceAsStream("/acceptance/" + name);
        if (in == null) {
            throw new IllegalStateException("Fixture not found: /acceptance/" + name);
        }
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read fixture: " + name, e);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private JsonNode loadFixtureNode(String name) throws IOException {
        return MAPPER.readTree(loadFixture(name));
    }

    // ── Catalog Schema Tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Catalog Schema (contract-catalog.v1)")
    class CatalogSchemaTests {

        @Test
        @DisplayName("POS: valid catalog passes schema validation")
        void catalogValid() {
            assertValid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-valid.json"));
        }

        @Test
        @DisplayName("NEG: missing oracleId is rejected")
        void catalogMissingOracle() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-missing-oracle-id.json"));
        }

        @Test
        @DisplayName("NEG: duplicate contractId is rejected")
        void catalogDuplicateContractId() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-duplicate-contract-id.json"));
        }

        @Test
        @DisplayName("NEG: wrong discriminator (schemaVersion v2) is rejected")
        void catalogWrongDiscriminator() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-wrong-discriminator.json"));
        }

        @Test
        @DisplayName("NEG: bad category enum value is rejected")
        void catalogBadCategoryEnum() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-bad-category-enum.json"));
        }

        @Test
        @DisplayName("NEG: bad evidenceRole enum value is rejected")
        void catalogBadEvidenceRole() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-bad-evidence-role.json"));
        }

        @Test
        @DisplayName("NEG: fixedDenominator below minimum is rejected")
        void catalogBadDenominator() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-bad-denominator.json"));
        }

        @Test
        @DisplayName("NEG: extra property is rejected (additionalProperties=false)")
        void catalogExtraProperty() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-extra-property.json"));
        }

        @Test
        @DisplayName("NEG: stage out of valid range [0-5] is rejected")
        void catalogBadStageRange() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-stage-out-of-range.json"));
        }

        @Test
        @DisplayName("POS: catalog with name field on AC_STD/FELT entries passes")
        void catalogWithAcNames() {
            assertValid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-valid.json"));
        }

        @Test
        @DisplayName("NEG: STAGE_EXIT with forbidden name field is rejected (oneOf rule)")
        void catalogStageExitNameForbidden() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1", loadFixture("catalog-neg-missing-name.json"));
        }
    }

    // ── Plan Schema Tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plan Schema (acceptance-plan.v1)")
    class PlanSchemaTests {

        @Test
        @DisplayName("POS: valid plan passes schema validation")
        void planValid() {
            assertValid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-valid.json"));
        }

        @Test
        @DisplayName("NEG: forbidden className field is rejected")
        void planForbiddenClassName() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-forbidden-classname.json"));
        }

        @Test
        @DisplayName("NEG: forbidden script field is rejected")
        void planForbiddenScript() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-forbidden-script.json"));
        }

        @Test
        @DisplayName("NEG: wrong discriminator (schemaVersion v2) is rejected")
        void planWrongDiscriminator() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-wrong-discriminator.json"));
        }

        @Test
        @DisplayName("NEG: missing obligationSet is rejected")
        void planMissingObligationSet() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-missing-obligation-set.json"));
        }

        @Test
        @DisplayName("NEG: bad terminalGate enum is rejected")
        void planBadTerminalGate() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-bad-terminal-gate.json"));
        }

        @Test
        @DisplayName("NEG: duplicate primitive id is rejected")
        void planDuplicatePrimitiveId() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-duplicate-primitive-id.json"));
        }

        @Test
        @DisplayName("NEG: extra property (produces) is rejected")
        void planExtraProperty() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-extra-property.json"));
        }

        @Test
        @DisplayName("NEG: bad primitive typeId is rejected")
        void planBadPrimitiveTypeId() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-bad-primitive-typeid.json"));
        }

        @Test
        @DisplayName("NEG: wrong obligationSet case is rejected")
        void planUppercaseObligationSet() {
            assertInvalid("bloge.capability-studio.acceptance-plan.v1", loadFixture("plan-neg-uppercase-obligation-set.json"));
        }
    }

    // ── Compiled Plan Schema Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("Compiled Plan Schema (compiled-acceptance-plan.v1)")
    class CompiledPlanSchemaTests {

        @Test
        @DisplayName("POS: valid compiled plan passes schema validation")
        void compiledPlanValid() {
            assertValid("bloge.capability-studio.compiled-plan.v1", loadFixture("compiled-plan-valid.json"));
        }

        @Test
        @DisplayName("NEG: wrong discriminator (schemaVersion v2) is rejected")
        void compiledPlanWrongDiscriminator() {
            assertInvalid("bloge.capability-studio.compiled-plan.v1", loadFixture("compiled-plan-neg-wrong-discriminator.json"));
        }

        @Test
        @DisplayName("NEG: bad effectClass enum is rejected")
        void compiledPlanBadEffectClass() {
            assertInvalid("bloge.capability-studio.compiled-plan.v1", loadFixture("compiled-plan-neg-bad-effect-class.json"));
        }

        @Test
        @DisplayName("NEG: stageExitContractCount=26 (const 27) is rejected")
        void compiledPlanBad27Const() {
            assertInvalid("bloge.capability-studio.compiled-plan.v1", loadFixture("compiled-plan-neg-bad-27-const.json"));
        }

        @Test
        @DisplayName("NEG: duplicate contractId in exactContractIds is rejected")
        void compiledPlanDuplicateContractId() {
            assertInvalid("bloge.capability-studio.compiled-plan.v1", loadFixture("compiled-plan-neg-duplicate-contract-id.json"));
        }

        @Test
        @DisplayName("NEG: wrong barrierId is rejected (barrierId exact 7)")
        void compiledPlanBadBarrierId() {
            assertInvalid("bloge.capability-studio.compiled-plan.v1", loadFixture("compiled-plan-neg-bad-barrier-id.json"));
        }
    }

    // ── Verification Result Schema Tests ─────────────────────────────────────

    @Nested
    @DisplayName("Verification Result Schema (compiled-plan-verification-result.v1)")
    class VerificationResultSchemaTests {

        @Test
        @DisplayName("POS: VERIFIED result passes schema validation")
        void verificationVerified() {
            assertValid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-verified.json"));
        }

        @Test
        @DisplayName("POS: INVALID result with closed reasonCode passes")
        void verificationInvalid() {
            assertValid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-invalid.json"));
        }

        @Test
        @DisplayName("POS: UNAVAILABLE result (reasonField absent) passes")
        void verificationUnavailable() {
            assertValid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-unavailable.json"));
        }

        @Test
        @DisplayName("NEG: VERIFIED with non-null reasonCode is rejected")
        void verificationVerifiedWithReason() {
            assertInvalid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-neg-verified-with-reason.json"));
        }

        @Test
        @DisplayName("NEG: VERIFIED with false boolean field is rejected")
        void verificationVerifiedWithFalse() {
            assertInvalid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-neg-verified-with-false-bool.json"));
        }

        @Test
        @DisplayName("NEG: INVALID with null reasonField is rejected")
        void verificationInvalidNullField() {
            assertInvalid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-neg-invalid-null-field.json"));
        }

        @Test
        @DisplayName("NEG: INVALID with unknown reasonCode is rejected (closed enum)")
        void verificationInvalidBadCode() {
            assertInvalid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-neg-bad-reason-code.json"));
        }

        @Test
        @DisplayName("NEG: UNAVAILABLE with reasonField present is rejected")
        void verificationUnavailableWithField() {
            assertInvalid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-neg-unavailable-with-field.json"));
        }

        @Test
        @DisplayName("NEG: uppercase SHA fingerprint (must be lowercase) is rejected")
        void verificationUppercaseSha() {
            assertInvalid("bloge.capability-studio.compiled-plan-verification-result.v1",
                loadFixture("verification-result-neg-uppercase-sha.json"));
        }
    }

    // ── Jackson STRICT_DUPLICATE_DETECTION Tests ───────────────────────────────

    @Nested
    @DisplayName("Jackson STRICT_DUPLICATE_DETECTION")
    class JacksonStrictDuplicateTests {

        @Test
        @DisplayName("POS: well-formed JSON parses without exception via STRICT_DUPLICATE_DETECTION mapper")
        void validJsonParses() throws Exception {
            STRICT_MAPPER.readTree(loadFixture("catalog-valid.json"));
        }

        @Test
        @DisplayName("NEG: duplicate key throws JsonParseException with STRICT_DUPLICATE_DETECTION")
        void duplicateKeyThrows() {
            String bad = "{\"a\":1,\"a\":2}";
            assertThatThrownBy(() -> STRICT_MAPPER.readTree(bad))
                .isInstanceOf(JsonParseException.class);
        }

        @Test
        @DisplayName("NEG: JSON with duplicate key is rejected as invalid catalog")
        void allSchemasDuplicateRejected() {
            assertInvalid("bloge.capability-studio.contract-catalog.v1",
                loadFixture("json-neg-duplicate-keys.json"));
        }
    }

    // ── Structural / Reference Tests ───────────────────────────────────────────

    @Nested
    @DisplayName("Structural Reference Tests")
    class StructuralReferenceTests {

        @Test
        @DisplayName("Catalog: matrixCells count is 27")
        void catalogMatrixCellCount() throws IOException {
            JsonNode node = loadFixtureNode("catalog-valid.json");
            assertThat(node.at("/matrixCells").size()).isEqualTo(27);
        }

        @Test
        @DisplayName("Catalog: matrixCellId pattern matches case@canonical-suite-run-XX")
        void catalogMatrixCellIdPattern() throws IOException {
            JsonNode node = loadFixtureNode("catalog-valid.json");
            List<String> cellIds = new ArrayList<>();
            node.at("/matrixCells").forEach(n -> cellIds.add(n.at("/matrixCellId").asText()));
            assertThat(cellIds).hasSize(27);
            for (String id : cellIds) {
                assertThat(id).contains("@canonical-suite-run-");
            }
        }

        @Test
        @DisplayName("Catalog: suiteRunIds are canonical-suite-run-01..03")
        void catalogSuiteRunIds() throws IOException {
            JsonNode node = loadFixtureNode("catalog-valid.json");
            List<String> runIds = new ArrayList<>();
            node.at("/suiteRuns").forEach(n -> runIds.add(n.at("/suiteRunId").asText()));
            assertThat(runIds).containsExactly(
                "canonical-suite-run-01","canonical-suite-run-02","canonical-suite-run-03");
        }

        @Test
        @DisplayName("Plan: catalogRef matches ^builtin-contract-catalog-v1@sha256:[0-9a-f]{64}$")
        void planCatalogRefFormat() throws IOException {
            JsonNode node = loadFixtureNode("plan-valid.json");
            String ref = node.at("/catalogRef").asText();
            assertThat(ref).matches("^builtin-contract-catalog-v1@sha256:[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("Plan: obligationSet is RG-CS-FELT-v1")
        void planObligationSetValue() throws IOException {
            JsonNode node = loadFixtureNode("plan-valid.json");
            assertThat(node.at("/obligationSet").asText()).isEqualTo("RG-CS-FELT-v1");
        }

        @Test
        @DisplayName("Plan: no forbidden fields (className/script/url/expression/serviceLoader)")
        void planNoForbiddenFields() throws IOException {
            JsonNode node = loadFixtureNode("plan-valid.json");
            assertThat(node.has("className")).isFalse();
            assertThat(node.has("script")).isFalse();
            assertThat(node.has("url")).isFalse();
            assertThat(node.has("expression")).isFalse();
            assertThat(node.has("serviceLoader")).isFalse();
        }

        // Profile resource: copied from docs/acceptance/capability-studio/acceptance-engine-v1/
        private static final String PROFILE_PATH = "/acceptance-engine-v1/builtin-compiler-profile-formal-v1.json";

        @Test
        @DisplayName("Profile: no self-referential compilerProfileRawFingerprint")
        void profileNoSelfRef() throws Exception {
            try (InputStream in = CapabilityStudioWireAndSchemaTest.class.getResourceAsStream(PROFILE_PATH)) {
                assertThat(in).as("builtin-compiler-profile-formal-v1.json must exist at " + PROFILE_PATH).isNotNull();
                JsonNode node = MAPPER.readTree(in);
                assertThat(node.has("compilerProfileRawFingerprint")).isFalse();
            }
        }

        @Test
        @DisplayName("Profile: 8 primitiveDescriptors embedded")
        void profileHas8Descriptors() throws Exception {
            try (InputStream in = CapabilityStudioWireAndSchemaTest.class.getResourceAsStream(PROFILE_PATH)) {
                assertThat(in).isNotNull();
                JsonNode node = MAPPER.readTree(in);
                assertThat(node.at("/primitiveDescriptors").size()).isEqualTo(8);
            }
        }

        @Test
        @DisplayName("Profile: 10 phaseOrder stages (BOOTSTRAP_FACTS through EXTERNAL_ADJUDICATION)")
        void profileTenPhases() throws Exception {
            try (InputStream in = CapabilityStudioWireAndSchemaTest.class.getResourceAsStream(PROFILE_PATH)) {
                assertThat(in).isNotNull();
                JsonNode node = MAPPER.readTree(in);
                List<String> phases = new ArrayList<>();
                for (JsonNode n : node.at("/phaseOrder")) phases.add(n.asText());
                assertThat(phases).hasSize(10);
                assertThat(phases.get(0)).isEqualTo("BOOTSTRAP_FACTS");
                assertThat(phases.get(9)).isEqualTo("EXTERNAL_ADJUDICATION");
            }
        }

        @Test
        @DisplayName("Profile: 7 exact barrierIds (PURE_VERIFY_GATE through NO_ACCEPTED_FROM_LOCAL)")
        void profileSevenBarriers() throws Exception {
            try (InputStream in = CapabilityStudioWireAndSchemaTest.class.getResourceAsStream(PROFILE_PATH)) {
                assertThat(in).isNotNull();
                JsonNode node = MAPPER.readTree(in);
                List<String> ids = new ArrayList<>();
                for (JsonNode n : node.at("/barriers")) ids.add(n.at("/barrierId").asText());
                assertThat(ids).containsExactlyInAnyOrder(
                    "PURE_VERIFY_GATE","LEASE_GATE","NO_DELETE_AFTER_LEASE",
                    "DURABLE_COMMIT_GATE","STORE_RECEIPT_GATE","OWNER_SIGNOFF_GATE",
                    "NO_ACCEPTED_FROM_LOCAL");
            }
        }
    }

    // ── Lossless Compiled IR Wire Assertions ─────────────────────────────────

    @Nested
    @DisplayName("Lossless Compiled IR Wire")
    class CompiledPlanLosslessWireAssertions {

        // 1. Valid item shapes/counts: 9 primitives, 7 barriers, 6 roles, 50 oracles
        @Test
        @DisplayName("POS: valid fixture has 9 primitives, 7 barriers, 6 roles, 50 oracles")
        void validItemShapesAndCounts() throws IOException {
            JsonNode node = loadFixtureNode("compiled-plan-valid.json");
            assertThat(node.at("/primitiveContracts").size()).isEqualTo(9);
            assertThat(node.at("/phaseBarriers").size()).isEqualTo(7);
            assertThat(node.at("/expectedEvidenceRoles").size()).isEqualTo(6);
            assertThat(node.at("/oracleBindings").size()).isEqualTo(50);
        }

        // 2. Missing dependsOn is rejected
        @Test
        @DisplayName("NEG: primitive without dependsOn is rejected (required field)")
        void primitiveMissingDependsOnRejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            ((ObjectNode) valid.at("/primitiveContracts/0")).remove("dependsOn");
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 3. Old single phase field rejected
        @Test
        @DisplayName("NEG: old single-phase phaseBarrier (no phases[]) is rejected")
        void oldSinglePhaseRejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            // Replace first barrier with old format (single phase field, no phases array)
            ObjectNode barrier = (ObjectNode) valid.at("/phaseBarriers/0");
            barrier.remove("phases");
            barrier.put("phase", "BOOTSTRAP_FACTS");
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 4. Old producerPrimitiveId in evidenceRoleBinding rejected
        @Test
        @DisplayName("NEG: old producerPrimitiveId in evidenceRoleBinding is rejected")
        void oldProducerPrimitiveIdRejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            ObjectNode role = (ObjectNode) valid.at("/expectedEvidenceRoles/0");
            role.remove("contractIds");
            role.put("producerPrimitiveId", "some-primitive-id");
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 5. Old oracle primitiveId in oracleBinding rejected
        @Test
        @DisplayName("NEG: old oracle primitiveId field in oracleBinding is rejected")
        void oldOraclePrimitiveIdRejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            ObjectNode binding = (ObjectNode) valid.at("/oracleBindings/0");
            binding.remove("oracleId");
            binding.put("primitiveId", "some-primitive-id");
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 6. Role contractIds empty array rejected
        @Test
        @DisplayName("NEG: evidenceRoleBinding with empty contractIds array is rejected")
        void roleContractIdsEmptyRejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            ObjectNode role = (ObjectNode) valid.at("/expectedEvidenceRoles/0");
            while (((ArrayNode) role.get("contractIds")).size() > 0) { ((ArrayNode) role.get("contractIds")).remove(0); }
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 7. oracleBindings 49 rejected
        @Test
        @DisplayName("NEG: oracleBindings with 49 items (needs exactly 50) is rejected")
        void oracleBindings49Rejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            ((ArrayNode) valid.get("oracleBindings")).remove(0);
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 8. phaseBarriers 6 rejected (needs exactly 7)
        @Test
        @DisplayName("NEG: phaseBarriers with 6 items (needs exactly 7) is rejected")
        void phaseBarriers6Rejected() throws IOException {
            JsonNode valid = loadFixtureNode("compiled-plan-valid.json");
            ((ArrayNode) valid.get("phaseBarriers")).remove(0);
            assertInvalid("bloge.capability-studio.compiled-plan.v1",
                MAPPER.writeValueAsString(valid));
        }

        // 9. Projection semantic: role->contractIds and contract->oracleId from catalog
        //    independently aggregated equals compiled fixture values
        @Test
        @DisplayName("POS: projection semantic equals independently-aggregated catalog values")
        void projectionSemanticEqualsIndependentAggregate() throws IOException {
            JsonNode catalogNode = loadFixtureNode("catalog-valid.json");
            JsonNode compiledNode = loadFixtureNode("compiled-plan-valid.json");

            // Aggregate role->contractIds from catalog
            Map<String, Set<String>> catalogRoleMap = new java.util.LinkedHashMap<>();
            for (JsonNode contract : catalogNode.at("/stageExitContracts")) {
                String cid = contract.at("/contractId").asText();
                for (JsonNode roleNode : contract.at("/evidenceRoles")) {
                    String role = roleNode.asText();
                    catalogRoleMap.computeIfAbsent(role, k -> new java.util.LinkedHashSet<>()).add(cid);
                }
            }
            for (JsonNode contract : catalogNode.at("/acStandards")) {
                String cid = contract.at("/contractId").asText();
                for (JsonNode roleNode : contract.at("/evidenceRoles")) {
                    String role = roleNode.asText();
                    catalogRoleMap.computeIfAbsent(role, k -> new java.util.LinkedHashSet<>()).add(cid);
                }
            }
            for (JsonNode contract : catalogNode.at("/feltObligations")) {
                String cid = contract.at("/contractId").asText();
                for (JsonNode roleNode : contract.at("/evidenceRoles")) {
                    String role = roleNode.asText();
                    catalogRoleMap.computeIfAbsent(role, k -> new java.util.LinkedHashSet<>()).add(cid);
                }
            }

            // Aggregate contractId->oracleId from catalog
            Map<String, String> catalogOracleMap = new java.util.LinkedHashMap<>();
            for (JsonNode contract : catalogNode.at("/stageExitContracts")) {
                catalogOracleMap.put(contract.at("/contractId").asText(),
                    contract.at("/oracleId").asText());
            }
            for (JsonNode contract : catalogNode.at("/acStandards")) {
                catalogOracleMap.put(contract.at("/contractId").asText(),
                    contract.at("/oracleId").asText());
            }
            for (JsonNode contract : catalogNode.at("/feltObligations")) {
                catalogOracleMap.put(contract.at("/contractId").asText(),
                    contract.at("/oracleId").asText());
            }

            // Compare compiled expectedEvidenceRoles against catalog aggregation
            List<String> compiledRoles = new ArrayList<>();
            for (JsonNode roleNode : compiledNode.at("/expectedEvidenceRoles")) {
                compiledRoles.add(roleNode.at("/role").asText());
                List<String> catalogCids = catalogRoleMap.get(roleNode.at("/role").asText())
                    .stream().sorted().toList();
                List<String> compiledCids = new ArrayList<>();
                for (JsonNode cidNode : roleNode.at("/contractIds")) {
                    compiledCids.add(cidNode.asText());
                }
                assertThat(compiledCids)
                    .as("role " + roleNode.at("/role").asText() + " contractIds must match catalog aggregation")
                    .isEqualTo(catalogCids);
            }

            // Compare compiled oracleBindings against catalog aggregation
            List<Map.Entry<String, String>> compiledOracles = new ArrayList<>();
            for (JsonNode binding : compiledNode.at("/oracleBindings")) {
                compiledOracles.add(Map.entry(
                    binding.at("/contractId").asText(),
                    binding.at("/oracleId").asText()));
            }
            compiledOracles.sort(Comparator.comparing(Map.Entry::getKey));
            List<Map.Entry<String, String>> catalogOracles = new ArrayList<>(catalogOracleMap.entrySet());
            catalogOracles.sort(Comparator.comparing(Map.Entry::getKey));
            assertThat(compiledOracles)
                .as("oracleBindings must match catalog contractId->oracleId aggregation")
                .isEqualTo(catalogOracles);
        }
    }


        // 10. Exact projection: compiled primitive id->dependsOn/inputSlot equals authority plan
        @Test
        @DisplayName("POS: compiled primitive dependsOn/inputSlot exact-matches authority plan projection")
        void compiledPrimitiveExactProjectionMatchesAuthorityPlan() throws IOException {
            // Load authority plan
            try (InputStream authorityIn = CapabilityStudioWireAndSchemaTest.class
                    .getResourceAsStream("/acceptance-engine-v1/rg-cs-felt-v1.acceptance.plan.json")) {
                assertThat(authorityIn)
                    .as("authority plan must be on classpath")
                    .isNotNull();
                JsonNode authorityPlan = MAPPER.readTree(authorityIn);

                // Build authority projection: primitiveId -> {dependsOn[], inputSlot}
                Map<String, ProjectionEntry> authorityProjection = new java.util.LinkedHashMap<>();
                for (JsonNode prim : authorityPlan.at("/primitives")) {
                    String pid = prim.at("/id").asText();
                    List<String> deps = new ArrayList<>();
                    for (JsonNode d : prim.at("/dependsOn")) deps.add(d.asText());
                    String slot = prim.has("inputSlot") ? prim.at("/inputSlot").asText() : null;
                    authorityProjection.put(pid, new ProjectionEntry(deps, slot));
                }

                // Load compiled plan
                JsonNode compiled = loadFixtureNode("compiled-plan-valid.json");

                // Avoid subset-only verification: authority and compiled must be complete
                assertThat(authorityProjection)
                    .as("authority projection size must equal compiled primitive count")
                    .hasSize(compiled.at("/primitiveContracts").size());

                // Assert each compiled primitive exactly matches authority projection
                for (JsonNode prim : compiled.at("/primitiveContracts")) {
                    String pid = prim.at("/primitiveId").asText();
                    ProjectionEntry expected = authorityProjection.get(pid);
                    assertThat(expected)
                        .as("primitiveId '" + pid + "' must exist in authority plan")
                        .isNotNull();

                    List<String> compiledDeps = new ArrayList<>();
                    for (JsonNode d : prim.at("/dependsOn")) compiledDeps.add(d.asText());
                    assertThat(compiledDeps)
                        .as("primitiveId '" + pid + "' dependsOn must exactly match authority plan")
                        .isEqualTo(expected.deps);

                    String compiledSlot = prim.has("inputSlot")
                        ? prim.at("/inputSlot").asText() : null;
                    assertThat(compiledSlot)
                        .as("primitiveId '" + pid + "' inputSlot must exactly match authority plan")
                        .isEqualTo(expected.slot);
                }
            }
        }

        /** Holds authority-derived dependsOn and inputSlot for one primitive. */
        private static class ProjectionEntry {
            final List<String> deps;
            final String slot;
            ProjectionEntry(List<String> deps, String slot) {
                this.deps = deps;
                this.slot = slot;
            }
            @Override public String toString() {
                return "ProjectionEntry{deps=" + deps + ", slot=" + slot + "}";
            }
            @Override public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof ProjectionEntry)) return false;
                ProjectionEntry e = (ProjectionEntry) o;
                return Objects.equals(deps, e.deps) && Objects.equals(slot, e.slot);
            }
            @Override public int hashCode() { return Objects.hash(deps, slot); }
        }

}
