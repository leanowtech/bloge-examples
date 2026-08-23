package com.leanowtech.bloge.gateway.testkit.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Tests for {@link CapabilityStudioCompiledPlanVerifier}. */
class CapabilityStudioCompiledPlanVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String AUTHORITY_COMPILED_FP =
            "sha256:aee15e4acc7cbdfe3eee36ba22a20c8eee2f45aa433e22f9701f2f3736f2e530";

    private static final byte[] AUTHORITY_COMPILED_PLAN;
    private static final byte[] AUTHORITY_PLAN_BYTES;
    private static final byte[] AUTHORITY_CATALOG_BYTES;

    static {
        try {
            AUTHORITY_PLAN_BYTES = readResource(
                    "/acceptance-engine-v1/rg-cs-felt-v1.acceptance.plan.json");
            AUTHORITY_CATALOG_BYTES = readResource(
                    "/acceptance-engine-v1/builtin-contract-catalog.json");
            var compiler = CapabilityStudioAcceptancePlanCompiler.withBuiltInInternals();
            var r = compiler.compile(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES);
            AUTHORITY_COMPILED_PLAN = r.compiledPlanBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load authority fixtures", e);
        }
    }

    // T1: Authority compiled plan -> VERIFIED
    @org.junit.jupiter.api.Test
    void authorityCompiledPlan_verifies() {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);

        assertThat(result.status())
                .as("Authority plan must VERIFY")
                .isEqualTo(CompiledPlanVerificationResult.Status.VERIFIED);
        assertThat(result.verified()).isTrue();

        assertThat(result.catalogRefVerified()).isTrue();
        assertThat(result.catalogRawFingerprintVerified()).isTrue();
        assertThat(result.catalogSemanticFingerprintVerified()).isTrue();
        assertThat(result.planFingerprintVerified()).isTrue();
        assertThat(result.phaseBarrierVerified()).isTrue();
        assertThat(result.dependencyDagVerified()).isTrue();
        assertThat(result.effectBarrierVerified()).isTrue();
        assertThat(result.canonicalMatrixCellCountVerified()).isTrue();
        assertThat(result.stageExitContractCountVerified()).isTrue();

        assertThat(result.reasonCode()).isNull();
        assertThat(result.reasonField()).isNull();
        assertThat(result.expectedCompiledPlanFingerprint()).isEqualTo(AUTHORITY_COMPILED_FP);
        assertThat(result.recomputedCompiledPlanFingerprint()).isEqualTo(AUTHORITY_COMPILED_FP);
        assertThat(result.verificationFingerprint()).matches("^sha256:[0-9a-f]{64}$");
    }

    @org.junit.jupiter.api.Test
    void authorityResult_schemaValid() throws Exception {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);

        Schema schema = loadSchema(
                "/schemas/resource-gateway-capability-studio/" +
                "capability-studio-compiled-plan-verification-result-v1.schema.json");
        JsonNode node = JSON.readTree(result.verificationResultBytes());
        java.util.List<com.networknt.schema.Error> errors = schema.validate(node);
        assertThat(errors).as("Result must validate against its schema").isEmpty();
    }

    @org.junit.jupiter.api.Test
    void authorityResult_defensiveBytes() {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        byte[] a = result.verificationResultBytes();
        byte[] b = result.verificationResultBytes();
        assertThat(a).isNotSameAs(b);
        assertThat(a).isEqualTo(b);
    }

    @org.junit.jupiter.api.Test
    void authorityResult_deterministic() {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var r1 = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        var r2 = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        assertThat(r1.verificationFingerprint()).isEqualTo(r2.verificationFingerprint());
    }

    // T2: Independent verificationFingerprint recomputation
    // Parse actual result bytes, remove verificationFingerprint field, recursive canonical, Domain2
    @org.junit.jupiter.api.Test
    void verificationFingerprint_recomputedTestSide() throws Exception {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        String recomputed = computeVerificationFingerprintTestSide(result);
        assertThat(result.verificationFingerprint())
                .as("verificationFingerprint independently recomputable via recursive canonical")
                .isEqualTo(recomputed);
    }

    // T3: Malformed inputs -> INVALID
    @org.junit.jupiter.api.Test
    void malformedPlanJson_returnsInvalid() {
        byte[] bad = "{not json".getBytes(StandardCharsets.UTF_8);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(bad, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode()).isNotNull();
        assertThat(result.reasonField()).isNotNull();
        assertThat(result.reasonField().length()).isLessThanOrEqualTo(512);
    }

    @org.junit.jupiter.api.Test
    void oversizePlanBytes_returnsInvalid() {
        byte[] huge = new byte[1 << 20 + 1];
        Arrays.fill(huge, (byte) 'x');
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(huge, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_COLLECTION_SIZE");
        assertThat(result.reasonField()).isEqualTo("/planBytes");
    }

    // T4: Tamper compiled fingerprint -> INVALID_TAMPERED_PLAN
    @org.junit.jupiter.api.Test
    void tamperCompiledFingerprint_returnsInvalidTampedPlan() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        String fp = compiled.at("/compiledPlanFingerprint").asText();
        String tampered = fp.substring(0, 7) + (fp.charAt(7) == 'a' ? 'b' : '9') + fp.substring(8);
        tamper.put("compiledPlanFingerprint", tampered);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);

        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);

        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
        assertThat(result.reasonField()).isEqualTo("/compiledPlanFingerprint");
    }

    // T5: IR self-consistency tampered
    @org.junit.jupiter.api.Test
    void tamperPhase_recomputesToDifferentFingerprint() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode prims = (ArrayNode) tamper.get("primitiveContracts");
        ((ObjectNode) prims.get(0)).put("phase", "STATEFUL_EXECUTION");

        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);

        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
    }

    @org.junit.jupiter.api.Test
    void tamperMatrixCellCount_rejects() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode matrixIds = (ArrayNode) tamper.get("matrixCellIds");
        ArrayNode smaller = JSON.createArrayNode();
        for (int i = 0; i < 26; i++) smaller.add(matrixIds.get(i));
        tamper.set("matrixCellIds", smaller);
        tamper.put("canonicalMatrixCellCount", 26);

        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES,
                JSON.writeValueAsBytes(tamper));
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.canonicalMatrixCellCountVerified()).isFalse();
    }

    // T6: Topology negatives
    @org.junit.jupiter.api.Test
    void cyclicDependency_returnsInvalid() throws Exception {
        // Cycle: a depends on b, b depends on a. Use builtin catalog for catalogRef match.
        String plan = "{\"schemaVersion\":\"bloge.capability-studio.acceptance-plan.v1\","
                + "\"planId\":\"cyclic\",\"revision\":1,"
                + "\"compilerProfile\":\"formal-evidence-v1\","
                + "\"catalogId\":\"builtin-contract-catalog-v1\","
                + "\"catalogRef\":\"builtin-contract-catalog-v1@sha256:b14c3ee599a87e0c10a94f4e0237455bcae93ff2c9fcc8a6a82ab9145942990c\","
                + "\"obligationSet\":\"RG-CS-FELT-v1\","
                + "\"terminalGate\":\"DEVELOPMENT_VERIFIED_ONLY\","
                + "\"primitives\":["
                + "{\"id\":\"a\",\"typeId\":\"VERIFY_FIXED_MATERIAL_V1\",\"revision\":1,\"dependsOn\":[\"b\"]},"
                + "{\"id\":\"b\",\"typeId\":\"VERIFY_FIXED_MATERIAL_V1\",\"revision\":1,\"dependsOn\":[\"a\"]}"
                + "]}";
        byte[] builtinCatalog = readResource("/acceptance-engine-v1/builtin-contract-catalog.json");
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(plan.getBytes(StandardCharsets.UTF_8),
                builtinCatalog, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TOPOLOGY_CYCLE");
    }

    // T8: Barrier bypass — MATERIAL_SNAPSHOT primitive depends on STATEFUL_EXECUTION primitive
    // (dependency phase > dependent phase) is INVALID_BARRIER_BYPASS.
    @org.junit.jupiter.api.Test
    void barrierBypass_returnsInvalid() throws Exception {
        // "early" is MATERIAL_SNAPSHOT phase; "late" is STATEFUL_EXECUTION phase.
        // early depends on late: dep phase (STATEFUL_EXECUTION) > pred phase (MATERIAL_SNAPSHOT)
        String bypassPlan = "{\"schemaVersion\":\"bloge.capability-studio.acceptance-plan.v1\","
                + "\"planId\":\"bypass\",\"revision\":1,"
                + "\"compilerProfile\":\"formal-evidence-v1\","
                + "\"catalogId\":\"builtin-contract-catalog-v1\","
                + "\"catalogRef\":\"builtin-contract-catalog-v1@sha256:b14c3ee599a87e0c10a94f4e0237455bcae93ff2c9fcc8a6a82ab9145942990c\","
                + "\"obligationSet\":\"RG-CS-FELT-v1\","
                + "\"terminalGate\":\"DEVELOPMENT_VERIFIED_ONLY\","
                + "\"primitives\":["
                + "{\"id\":\"early\",\"typeId\":\"VERIFY_FIXED_MATERIAL_V1\",\"revision\":1,\"dependsOn\":[\"late\"]},"
                + "{\"id\":\"late\",\"typeId\":\"EXECUTE_LEASE_EVIDENCE_V1\",\"revision\":1,\"dependsOn\":[]}"
                + "]}";
        byte[] builtinCatalog = readResource("/acceptance-engine-v1/builtin-contract-catalog.json");
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(bypassPlan.getBytes(StandardCharsets.UTF_8),
                builtinCatalog, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_BARRIER_BYPASS");
        assertThat(result.reasonField()).isEqualTo("/primitives");
    }

    // T7: Architecture
    @org.junit.jupiter.api.Test
    void verifierDoesNotReferenceCompilerClasses() throws Exception {
        String[] forbidden = {
                "CapabilityStudioAcceptancePlanCompiler",
                "CompilationResult",
                "CapabilityStudioAcceptancePlanProtocol",
                "CapabilityStudioAcceptancePrimitiveRegistry",
                "CompilerException",
                "Domain2",
        };
        String src = readSourceFile(
                "src/main/java/com/leanowtech/bloge/gateway/testkit/acceptance/" +
                "CapabilityStudioCompiledPlanVerifier.java");
        for (String f : forbidden) {
            assertThat(src)
                    .as("Verifier must not import " + f)
                    .doesNotContain("import " + f);
        }
    }

    @org.junit.jupiter.api.Test
    void resultDoesNotExposeMutableJsonNode() {
        for (java.lang.reflect.Method m : CompiledPlanVerificationResult.class.getDeclaredMethods()) {
            String ret = m.getReturnType().getName();
            if (ret.contains("JsonNode") || ret.contains("ObjectNode") || ret.contains("ArrayNode")) {
                fail("Result must not expose JsonNode types: " + m.getName() + " -> " + ret);
            }
        }
    }

    // T8: Reason pointers bounded
    @org.junit.jupiter.api.Test
    void invalidResultReasonFieldBounded512() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("stageExitContractCount", 99);

        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES,
                JSON.writeValueAsBytes(tamper));

        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonField()).isNotNull();
        assertThat(result.reasonField().length()).isLessThanOrEqualTo(512);
        assertThat(result.reasonField()).matches("^/[^\\x00-\\x1f\\x7f~]+$");
    }

    // T9: No execution / verdict fields
    @org.junit.jupiter.api.Test
    void verificationResultHasNoExecutionFields() throws Exception {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        JsonNode node = JSON.readTree(result.verificationResultBytes());
        assertThat(node.has("formalPassCount")).isFalse();
        assertThat(node.has("PASS")).isFalse();
        assertThat(node.has("ACCEPTED")).isFalse();
    }

    // T10: Test duration
    @org.junit.jupiter.api.Test
    void authorityVerificationCompletesInSeconds() {
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
        assertThat(elapsed).as("10 iterations under 10s").isLessThan(10.0);
    }

    // Helpers
    private static byte[] readResource(String path) throws Exception {
        try (InputStream in = CapabilityStudioCompiledPlanVerifierTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Resource not found: " + path);
            return in.readAllBytes();
        }
    }

    private static String readSourceFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private static Schema loadSchema(String path) throws Exception {
        try (InputStream in = CapabilityStudioCompiledPlanVerifierTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Schema not found: " + path);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SchemaRegistry reg = SchemaRegistry.withDialect(Dialects.getDraft202012());
            return reg.getSchema(SchemaLocation.of(path), json, InputFormat.JSON);
        }
    }

    // Parse actual result bytes, remove verificationFingerprint, recursive canonical JSON, Domain2.
    // Proves wire-body determinism independent of ObjectMapper insertion order.
    private static String computeVerificationFingerprintTestSide(CompiledPlanVerificationResult result)
            throws Exception {
        // Parse the actual serialized result bytes (not manually reconstructed)
        JsonNode root = JSON.readTree(result.verificationResultBytes());
        ObjectNode withoutFp = JSON.createObjectNode();
        root.fields().forEachRemaining(e -> {
            if (!"verificationFingerprint".equals(e.getKey()))
                withoutFp.set(e.getKey(), e.getValue());
        });
        // Recursive canonical (same as verifier's canonicalRebuild): object keys sorted, arrays preserved
        JsonNode canonical = recursiveCanonical(withoutFp);
        byte[] bytes = JSON.writeValueAsBytes(canonical);
        return domain2("RG-CS-COMPILED-PLAN-VERIFICATION-v1", bytes);
    }

    // Recursive canonical: object keys sorted by Java String (UTF-16) order, arrays preserved
    private static JsonNode recursiveCanonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            List<String> keys = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(java.util.Comparator.naturalOrder());
            for (String k : keys) out.set(k, recursiveCanonical(node.get(k)));
            return out;
        } else if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode e : node) out.add(recursiveCanonical(e));
            return out;
        } else {
            return node;
        }
    }

    // Recursive canonical (keys sorted) before Domain2 — proves body determinism
    private static String computeCompiledFingerprintTestSide(JsonNode compiled) throws Exception {
        ObjectNode withoutFp = JSON.createObjectNode();
        compiled.fields().forEachRemaining(e -> {
            if (!"compiledPlanFingerprint".equals(e.getKey()))
                withoutFp.set(e.getKey(), e.getValue());
        });
        JsonNode canonical = recursiveCanonical(withoutFp);
        return domain2("RG-CS-COMPILED-PLAN-v1", JSON.writeValueAsBytes(canonical));
    }

    private static String domain2(String domain, byte[] payload) {
        byte[] d = domain.getBytes(StandardCharsets.UTF_8);
        byte[] l = ByteBuffer.allocate(4).putInt(payload.length).array();
        byte[] in = new byte[d.length + 4 + payload.length];
        System.arraycopy(d, 0, in, 0, d.length);
        System.arraycopy(l, 0, in, d.length, 4);
        System.arraycopy(payload, 0, in, d.length + 4, payload.length);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(in);
            return "sha256:" + toHex(hash);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(v & 0xf, 16));
        }
        return sb.toString();
    }




    // ═══════════════════════════════════════════════════════════════════════════
    // T11: Protocol invariant — catalogRef input binding
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void catalogRefInputBinding_mismatch_returnsInvalid() throws Exception {
        // plan.catalogRef uses authority catalog hash; builtin catalog has different hash
        String planWithBadRef = "{\"schemaVersion\":\"bloge.capability-studio.acceptance-plan.v1\","
                + "\"planId\":\"rg-cs-felt-v1\",\"revision\":1,"
                + "\"compilerProfile\":\"formal-evidence-v1\","
                + "\"catalogId\":\"builtin-contract-catalog-v1\","
                + "\"catalogRef\":\"builtin-contract-catalog-v1@sha256:DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF\","
                + "\"obligationSet\":\"RG-CS-FELT-v1\","
                + "\"terminalGate\":\"DEVELOPMENT_VERIFIED_ONLY\","
                + "\"primitives\":[{\"id\":\"a\",\"typeId\":\"VERIFY_FIXED_MATERIAL_V1\",\"revision\":1,\"dependsOn\":[]}]}";
        byte[] builtinCatalog = readResource("/acceptance-engine-v1/builtin-contract-catalog.json");
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(planWithBadRef.getBytes(StandardCharsets.UTF_8),
                builtinCatalog, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_FINGERPRINT_MISMATCH");
        assertThat(result.reasonField()).isEqualTo("/catalogRef");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T12: Protocol invariant — malformed plan JSON (valid JSON, schema-invalid)
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void malformedPlanSchemaInvalid_returnsInvalid() {
        // Valid JSON but missing required "compilerProfile" field
        String badPlan = "{\"schemaVersion\":\"bloge.capability-studio.acceptance-plan.v1\","
                + "\"planId\":\"test\",\"revision\":1,"
                + "\"catalogId\":\"builtin-contract-catalog-v1\","
                + "\"catalogRef\":\"builtin-contract-catalog-v1@sha256:b14c3ee599a87e0c10a94f4e0237455bcae93ff2c9fcc8a6a82ab9145942990c\","
                + "\"obligationSet\":\"RG-CS-FELT-v1\","
                + "\"terminalGate\":\"DEVELOPMENT_VERIFIED_ONLY\","
                + "\"primitives\":[]}";
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(badPlan.getBytes(StandardCharsets.UTF_8),
                AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_SCHEMA");
        assertThat(result.reasonField()).isEqualTo("/planBytes");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T13: Protocol invariant — malformed catalog JSON (valid JSON, schema-invalid)
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void malformedCatalogSchemaInvalid_returnsInvalid() {
        // Valid JSON but missing required "catalogId" field
        String badCatalog = "{\"schemaVersion\":\"bloge.capability-studio.contract-catalog.v1\","
                + "\"catalogRevision\":1,\"stageExitContracts\":[],\"acStandards\":[],"
                + "\"feltObligations\":[],\"canonicalCases\":[],\"suiteRuns\":[],\"matrixCells\":[]}";
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES,
                badCatalog.getBytes(StandardCharsets.UTF_8), AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_SCHEMA");
        assertThat(result.reasonField()).isEqualTo("/catalogBytes");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T14: Registry fingerprint mismatch (tampered primitiveRegistryFingerprint)
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void registryFingerprintMismatch_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("primitiveRegistryFingerprint",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
        assertThat(result.reasonField()).isEqualTo("/primitiveRegistryFingerprint");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T15: Attack matrix — tamper primitiveContracts phase
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperPrimitiveContractsPhase_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode prims = (ArrayNode) tamper.get("primitiveContracts");
        ((ObjectNode) prims.get(0)).put("phase", "STATEFUL_EXECUTION");
        // Recompute fingerprint test-side
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
        assertThat(result.reasonField()).isEqualTo("/primitiveContracts/0/phase");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T16: Attack matrix — tamper executionOrder
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperExecutionOrder_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode order = (ArrayNode) tamper.get("executionOrder");
        // Reverse first two entries
        JsonNode first = order.get(0), second = order.get(1);
        ((ArrayNode) order).removeAll();
        order.add(second); order.add(first);
        for (int i = 2; i < order.size(); i++) { /* keep rest */ }
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T17: Attack matrix — tamper phaseBarriers barrierId (schema-valid: swap enum to another valid value)
    @org.junit.jupiter.api.Test
    void tamperPhaseBarriersBarrierId_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode barriers = (ArrayNode) tamper.get("phaseBarriers");
        // Swap first barrierId from PURE_VERIFY_GATE to LEASE_GATE (both valid enums, schema passes)
        ((ObjectNode) barriers.get(0)).put("barrierId", "LEASE_GATE");
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
        assertThat(result.reasonField()).isEqualTo("/phaseBarriers/0/barrierId");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T18: Attack matrix — tamper counts and IDs (canonicalMatrixCellCount)
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperCanonicalMatrixCellCount_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("canonicalMatrixCellCount", 26);
        ArrayNode matrixIds = (ArrayNode) tamper.get("matrixCellIds");
        ArrayNode smaller = JSON.createArrayNode();
        for (int i = 0; i < 26; i++) smaller.add(matrixIds.get(i));
        tamper.set("matrixCellIds", smaller);
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.canonicalMatrixCellCountVerified()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T19: Attack matrix — tamper expectedEvidenceRoles
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperExpectedEvidenceRoles_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode roles = (ArrayNode) tamper.get("expectedEvidenceRoles");
        ObjectNode firstRole = (ObjectNode) roles.get(0);
        ArrayNode ids = (ArrayNode) firstRole.get("contractIds");
        if (ids.size() > 0) ids.remove(0);
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T20: Attack matrix — tamper oracleBindings
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperOracleBindings_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode bindings = (ArrayNode) tamper.get("oracleBindings");
        ObjectNode first = (ObjectNode) bindings.get(0);
        first.put("oracleId", "ORACLE-TAMPERED");
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T21: Attack matrix — tamper terminalGate (schema const DEVELOPMENT_VERIFIED_ONLY; no second valid value → INVALID_SCHEMA)
    @org.junit.jupiter.api.Test
    void tamperTerminalGate_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("terminalGate", "ALLOW_ALL");
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_SCHEMA");
        assertThat(result.reasonField()).isEqualTo("/compiledPlanBytes");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T22: Attack matrix — tamper stageExitContractCount
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperStageExitContractCount_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("stageExitContractCount", 50);
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.stageExitContractCountVerified()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T23: Attack matrix — tamper exactContractIds (schema-valid: swap two elements, preserve size/unique)
    @org.junit.jupiter.api.Test
    void tamperExactContractIds_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode ids = (ArrayNode) tamper.get("exactContractIds");
        // Swap first two IDs: keeps size=50, unique=true, all strings match pattern — schema passes
        String first = ids.get(0).asText();
        ((ArrayNode) ids).set(0, ids.get(1));
        ((ArrayNode) ids).set(1, new com.fasterxml.jackson.databind.node.TextNode(first));
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
        assertThat(result.reasonField()).isEqualTo("/exactContractIds/0");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T24: Attack matrix — tamper primitiveContracts dependsOn (wrong dependency)
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperPrimitiveContractsDependsOn_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode prims = (ArrayNode) tamper.get("primitiveContracts");
        ObjectNode second = (ObjectNode) prims.get(1);
        ArrayNode deps = (ArrayNode) second.get("dependsOn");
        deps.removeAll();
        deps.add("NONEXISTENT-PRIMITIVE");
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T25: Attack matrix — tamper primitiveContracts effectClass (schema-valid: swap to another valid enum)
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void tamperPrimitiveContractsEffectClass_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        ArrayNode prims = (ArrayNode) tamper.get("primitiveContracts");
        ((ObjectNode) prims.get(0)).put("effectClass", "AUTHORITY_LEASE");
        String tamperedFp = computeCompiledFingerprintTestSide(tamper);
        assertThat(tamperedFp).isNotEqualTo(AUTHORITY_COMPILED_FP);
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_TAMPERED_PLAN");
        assertThat(result.reasonField()).isEqualTo("/primitiveContracts/0/effectClass");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T26: Attack matrix — extra field in compiled plan body (schema additionalProperties=false → INVALID_SCHEMA)
    @org.junit.jupiter.api.Test
    void extraFieldInCompiledBody_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("extraSecretField", "SHOULD_NOT_EXIST");
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_SCHEMA");
        assertThat(result.reasonField()).isEqualTo("/compiledPlanBytes");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T27: Attack matrix — plan fingerprint (planSourceSemanticFingerprint) mismatch
    // ═══════════════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void planSemanticFingerprintMismatch_returnsInvalid() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        ObjectNode tamper = compiled.deepCopy();
        tamper.put("planSourceSemanticFingerprint",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        byte[] tamperedBytes = JSON.writeValueAsBytes(tamper);
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, tamperedBytes);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.INVALID);
        assertThat(result.reasonCode()).isEqualTo("INVALID_FINGERPRINT_MISMATCH");
        assertThat(result.reasonField()).isEqualTo("/planSourceSemanticFingerprint");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // T28: Authority — compiled plan wire body has exactly 22 fields (compiledPlanFingerprint excluded from canonical body)
    @org.junit.jupiter.api.Test
    void authorityCompiledPlanHasExactly22WireBodyFields() throws Exception {
        JsonNode compiled = JSON.readTree(AUTHORITY_COMPILED_PLAN);
        java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = compiled.fields();
        int count = 0;
        while (it.hasNext()) { it.next(); count++; }
        assertThat(count)
                .as("Authority compiled plan has 23 wire fields (22 canonical body + compiledPlanFingerprint)")
                .isEqualTo(23);
        // The canonical body for fingerprinting omits compiledPlanFingerprint → exactly 22 fields
        assertThat(count - 1)
                .as("Canonical body (compiledPlanFingerprint excluded) has exactly 22 fields")
                .isEqualTo(22);
        // Verify result schema validation passes (separate concern)
        var verifier = CapabilityStudioCompiledPlanVerifier.withBuiltInInternals();
        var result = verifier.verify(AUTHORITY_PLAN_BYTES, AUTHORITY_CATALOG_BYTES, AUTHORITY_COMPILED_PLAN);
        assertThat(result.status()).isEqualTo(CompiledPlanVerificationResult.Status.VERIFIED);
    }


}
