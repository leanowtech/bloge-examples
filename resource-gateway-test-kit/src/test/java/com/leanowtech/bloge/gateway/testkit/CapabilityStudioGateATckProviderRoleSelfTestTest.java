package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authority projection tests for TCK_PROVIDER role kernel layer 1.
 *
 * <p>Tests validate the authority parsing and projection logic for the TCK_PROVIDER
 * role contract. Each test category covers a specific drift/missing/null/duplicate
 * failure mode to ensure fail-closed behavior.
 *
 * <p>Uses the real tracked authority file and creates synthetic variants for
 * negative test cases using targeted structured mutations.
 */
class CapabilityStudioGateATckProviderRoleSelfTestTest {

    @TempDir
    Path temp;

    private static final Path TRACKED_AUTHORITY_PATH = Path.of(
            System.getProperty("user.dir"),
            "..", "docs", "acceptance", "capability-studio", "gate-a-wire-v1",
            "protocol-compiler", "gate-a-protocol-authority-v1.json");

    private static final String TCK_ROLE = "TCK_PROVIDER";

    // ── Success test ───────────────────────────────────────────────────────

    @Test
    void projectAndValidate_acceptsRealAuthority() {
        byte[] rawAuthority = readTrackedAuthority();
        
        CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract contract =
                CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(rawAuthority);

        assertThat(contract.schemaVersion()).isEqualTo("capability-studio.gate-a-protocol-authority.v1");
        assertThat(contract.authorityId()).isEqualTo("GATE-A-PROTOCOL-AUTHORITY");
        assertThat(contract.revision()).isEqualTo(1);
        assertThat(contract.role()).isEqualTo(TCK_ROLE);
        assertThat(contract.modulePath()).isEqualTo("resource-gateway-gate-a-tck-provider");
        assertThat(contract.launchMode()).isEqualTo("SERVICE_LOADER_PROBE");
        assertThat(contract.buildProfile()).isEqualTo("gate-a-provider");
        assertThat(contract.releaseGate()).isEqualTo("STRUCTURE_AND_BLACK_BOX_REQUIRED");
        assertThat(contract.fixtureSetId()).isEqualTo("GATE_A_ROLE_BLACK_BOX_V1");
        assertThat(contract.capabilities()).containsExactlyInAnyOrder(
                "ROLE_SELF_TEST_RECEIPT", "THIN_SERVICE_PROVIDER", "PROVIDER_IDENTITY");
        assertThat(contract.maxRawBytes()).isEqualTo(16777216L);
        assertThat(contract.requiredJarEntries()).hasSize(5);
        assertThat(contract.packagingModel()).isEqualTo("THIN_SERVICE_PROVIDER");
        assertThat(contract.dependencyLockManifestMode()).isEqualTo("PROVIDED_ABI_ONLY_NO_EMBEDDED_RUNTIME");
        assertThat(contract.spiInterfaceClass())
                .isEqualTo("com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider");
        assertThat(contract.embeddingPolicy()).isEqualTo("PROVIDED_ABI_NOT_EMBEDDED");
        assertThat(contract.requiredRuntimeArtifactRoles()).containsExactly("IMPLEMENTATION_CANDIDATE");
        assertThat(contract.visibleSchemaIds()).hasSize(57);
    }

    // ── Schema version drift tests ────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsSchemaVersionDrift() {
        byte[] modified = mutateAuthorityField("schemaVersion", "wrong.version.v0");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_SCHEMA_VERSION_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsNullSchemaVersion() {
        byte[] modified = mutateAuthorityFieldNull("schemaVersion");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_SCHEMA_VERSION_DRIFT");
    }

    // ── Authority ID drift tests ──────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsAuthorityIdDrift() {
        byte[] modified = mutateAuthorityField("authorityId", "WRONG-AUTHORITY");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_AUTHORITY_ID_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsNullAuthorityId() {
        byte[] modified = mutateAuthorityFieldNull("authorityId");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_AUTHORITY_ID_DRIFT");
    }

    // ── Revision drift tests ──────────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsRevisionDrift() {
        byte[] modified = mutateAuthorityField("revision", 2);
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_REVISION_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsNullRevision() {
        byte[] modified = mutateAuthorityFieldNull("revision");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_REVISION_DRIFT");
    }

    // ── Role contract drift tests ────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsDuplicateTckProvider() {
        byte[] modified = addDuplicateTckProviderRole();
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_TCK_PROVIDER_COUNT_DRIFT");
    }

    // ── Module/path field drift tests ────────────────────────────────────

    @Test
    void projectAndValidate_rejectsModulePathDrift() {
        byte[] modified = mutateTckRoleField("modulePath", "wrong-module");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_MODULE_PATH_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsLaunchModeDrift() {
        byte[] modified = mutateTckRoleField("launchMode", "CLASSPATH_MAIN");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_LAUNCH_MODE_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsBuildProfileDrift() {
        byte[] modified = mutateTckRoleField("buildProfile", "wrong-profile");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_BUILD_PROFILE_DRIFT");
    }

    // ── Null field drift tests ───────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsMainClassNonNull() {
        // TCK_PROVIDER already has mainClass: null, set to non-null
        byte[] modified = mutateTckRoleField("mainClass", "com.example.Main");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_MAIN_CLASS_NULL_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsClassifierNonNull() {
        // TCK_PROVIDER already has classifier: null, set to non-null
        byte[] modified = mutateTckRoleField("classifier", "some-classifier");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_CLASSIFIER_NULL_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsProfilePathNonNull() {
        byte[] modified = mutateTckRoleField("profilePath", "META-INF/profile.json");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_PROFILE_PATH_NULL_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsRuntimeAllowlistNonEmpty() {
        byte[] modified = mutateTckRoleField("runtimeDependencyAllowlist", 
                List.of("some:dependency"));
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_RUNTIME_ALLOWLIST_EMPTY_DRIFT");
    }

    // ── Capabilities drift tests ────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsMissingCapability() {
        byte[] modified = mutateTckCapabilitiesRemove("PROVIDER_IDENTITY");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_CAPABILITIES_SIZE_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsCapabilitiesSizeMismatch() {
        byte[] modified = mutateTckCapabilitiesSet(List.of("ROLE_SELF_TEST_RECEIPT"));
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_CAPABILITIES_SIZE_DRIFT");
    }

    // ── Visible schema ID tests ─────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsVisibleSchemaCountMismatch() {
        byte[] modified = mutateTckVisibleSchemasRemoveFirst();
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_VISIBLE_SCHEMA_COUNT_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsDuplicateVisibleSchema() {
        byte[] modified = mutateTckVisibleSchemasAddDuplicate();
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_VISIBLE_SCHEMA_DUPLICATE");
    }

    @Test
    void projectAndValidate_rejectsSchemaNotInInventory() {
        byte[] modified = mutateTckVisibleSchemasAddInvalid();
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_VISIBLE_SCHEMA_NOT_IN_INVENTORY");
    }

    // ── Packaging contract tests ─────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsPackagingModelDrift() {
        byte[] modified = mutateTckPackagingField("model", "SHADED_CLOSED_JAR");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_PACKAGING_MODEL_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsEmbeddedDepsNonEmpty() {
        byte[] modified = mutateTckPackagingField("embeddedDependencyEntries", 
                List.of(Map.of("path", "some.jar")));
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_EMBEDDED_DEPS_EMPTY_DRIFT");
    }

    // ── Release bundle domain tests ──────────────────────────────────────

    @Test
    void projectAndValidate_rejectsRoleViewDomainDrift() {
        byte[] modified = mutateBundleField("roleViewDomain", "WRONG_DOMAIN");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_ROLE_VIEW_DOMAIN_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsRoleInputTreeDomainDrift() {
        byte[] modified = mutateBundleField("roleInputTreeDomain", "WRONG_DOMAIN");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_ROLE_INPUT_TREE_DOMAIN_DRIFT");
    }

    @Test
    void projectAndValidate_rejectsSchemaSetDomainDrift() {
        byte[] modified = mutateBundleField("schemaSetDomain", "WRONG_DOMAIN");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(modified))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_SCHEMA_SET_DOMAIN_DRIFT");
    }

    // ── JSON parsing tests ───────────────────────────────────────────────

    @Test
    void projectAndValidate_rejectsDuplicateJsonKeys() {
        byte[] json = "{\"schemaVersion\":\"v1\",\"schemaVersion\":\"duplicate\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(json))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("DUPLICATE_MEMBER");
    }

    @Test
    void projectAndValidate_rejectsInvalidUtf8() {
        byte[] invalid = new byte[] { '{', (byte) 0x80, (byte) 0xFF, '}' };
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(invalid))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("UTF8_DECODE_ERROR");
    }

    @Test
    void projectAndValidate_rejectsTrailingComma() {
        byte[] json = "{\"schemaVersion\":\"v1\",}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(json))
                .isInstanceOf(CapabilityStudioGateAException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Structured mutation helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private byte[] readTrackedAuthority() {
        if (!Files.exists(TRACKED_AUTHORITY_PATH)) {
            throw new AssertionError("TRACKED_AUTHORITY_NOT_FOUND:" + TRACKED_AUTHORITY_PATH);
        }
        try {
            return Files.readAllBytes(TRACKED_AUTHORITY_PATH);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read tracked authority", e);
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateAuthorityField(String fieldName, Object newValue) {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        authority.put(fieldName, newValue);
        return serializeToJson(authority);
    }

    private byte[] mutateAuthorityFieldNull(String fieldName) {
        return mutateAuthorityField(fieldName, null);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateBundleField(String fieldName, Object newValue) {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> bundle = (Map<String, Object>) authority.get("releaseAuthorityBundleContract");
        bundle.put(fieldName, newValue);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findTckRoleContract(Map<String, Object> authority) {
        List<Map<String, Object>> roleContracts = (List<Map<String, Object>>) authority.get("roleContracts");
        for (Map<String, Object> rc : roleContracts) {
            if (TCK_ROLE.equals(rc.get("role"))) {
                return rc;
            }
        }
        throw new RuntimeException("TCK_PROVIDER role contract not found");
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckRoleField(String fieldName, Object newValue) {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        tck.put(fieldName, newValue);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckCapabilitiesRemove(String capToRemove) {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        Map<String, Object> blackBox = (Map<String, Object>) tck.get("blackBoxContract");
        List<String> caps = new java.util.ArrayList<>((List<String>) blackBox.get("capabilities"));
        caps.remove(capToRemove);
        blackBox.put("capabilities", caps);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckCapabilitiesSet(List<String> newCaps) {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        Map<String, Object> blackBox = (Map<String, Object>) tck.get("blackBoxContract");
        blackBox.put("capabilities", newCaps);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckVisibleSchemasRemoveFirst() {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        List<String> schemas = new java.util.ArrayList<>((List<String>) tck.get("visibleSchemaIds"));
        schemas.remove(0);
        tck.put("visibleSchemaIds", schemas);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckVisibleSchemasAddDuplicate() {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        List<String> schemas = new java.util.ArrayList<>((List<String>) tck.get("visibleSchemaIds"));
        // Add the last schema again as duplicate
        schemas.add(schemas.get(schemas.size() - 1));
        tck.put("visibleSchemaIds", schemas);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckVisibleSchemasAddInvalid() {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        List<String> schemas = new java.util.ArrayList<>((List<String>) tck.get("visibleSchemaIds"));
        schemas.add("nonexistent-schema.schema.json");
        tck.put("visibleSchemaIds", schemas);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] mutateTckPackagingField(String fieldName, Object newValue) {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        Map<String, Object> tck = findTckRoleContract(authority);
        Map<String, Object> packaging = (Map<String, Object>) tck.get("packagingContract");
        packaging.put(fieldName, newValue);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private byte[] addDuplicateTckProviderRole() {
        Map<String, Object> authority = StrictJsonParser.parse(readTrackedAuthority());
        List<Map<String, Object>> roleContracts = (List<Map<String, Object>>) authority.get("roleContracts");
        Map<String, Object> tck = findTckRoleContract(authority);
        // Deep copy the TCK contract
        Map<String, Object> dup = deepCopy(tck);
        dup.put("modulePath", "duplicate-module");
        roleContracts.add(dup);
        return serializeToJson(authority);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> original) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : original.entrySet()) {
            Object val = e.getValue();
            if (val instanceof Map) {
                copy.put(e.getKey(), deepCopy((Map<String, Object>) val));
            } else if (val instanceof List) {
                copy.put(e.getKey(), deepCopyList((List<?>) val));
            } else {
                copy.put(e.getKey(), val);
            }
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private List<Object> deepCopyList(List<?> original) {
        List<Object> copy = new java.util.ArrayList<>();
        for (Object item : original) {
            if (item instanceof Map) {
                copy.add(deepCopy((Map<String, Object>) item));
            } else if (item instanceof List) {
                copy.add(deepCopyList((List<?>) item));
            } else {
                copy.add(item);
            }
        }
        return copy;
    }

    // JSON serializer using simple formatting
    private byte[] serializeToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        serializeValue(sb, map, 0);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void serializeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append('"').append(escapeJson((String) value)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof List) {
            sb.append('[');
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                serializeValue(sb, list.get(i), indent + 1);
            }
            sb.append(']');
        } else if (value instanceof Map) {
            sb.append('{');
            Map<?, ?> obj = (Map<?, ?>) value;
            boolean first = true;
            for (Map.Entry<?, ?> e : obj.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(e.getKey().toString())).append("\":");
                serializeValue(sb, e.getValue(), indent + 1);
            }
            sb.append('}');
        }
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
