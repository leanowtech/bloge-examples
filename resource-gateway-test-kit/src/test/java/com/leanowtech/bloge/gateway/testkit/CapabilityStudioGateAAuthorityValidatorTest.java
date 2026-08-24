package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused validator tests for Gate A authority parsing and validation.
 *
 * <p>Reads the tracked source authority from the classpath and exercises:</p>
 * <ul>
 *   <li>Happy path: 8 exact dependency pins and 57 visible schema IDs</li>
 *   <li>Mutation coverage per validation axis</li>
 *   <li>StrictJsonParser boundary conditions</li>
 * </ul>
 *
 * <p>No modifications to CLI / artifact / schema / canonicalizer / pom / docs.</p>
 */
class CapabilityStudioGateAAuthorityValidatorTest {

    /** Stable path to the tracked source authority document. */
    private static final Path TRACKED_AUTHORITY_PATH = Path.of(
            System.getProperty("user.dir"),
            "..", "docs", "acceptance", "capability-studio", "gate-a-wire-v1",
            "protocol-compiler", "gate-a-protocol-authority-v1.json");

    /** Shared ObjectMapper for test-side structural mutations. */
    private final ObjectMapper jackson = new ObjectMapper();

    /**
     * Reads the tracked authority from the source-docs path.
     * Fails fast if the file does not exist (fail-closed).
     */
    private static byte[] readTrackedAuthority() {
        if (!Files.exists(TRACKED_AUTHORITY_PATH)) {
            fail("TRACKED_AUTHORITY_NOT_FOUND:" + TRACKED_AUTHORITY_PATH.toAbsolutePath());
            throw new AssertionError("unreachable");
        }
        try {
            return Files.readAllBytes(TRACKED_AUTHORITY_PATH);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read tracked authority: " + TRACKED_AUTHORITY_PATH, e);
        }
    }

    // ── Happy-path tests ───────────────────────────────────────────────

    @Test
    void acceptsTrackedAuthority() {
        byte[] raw = readTrackedAuthority();
        Map<String, Object> result = CapabilityStudioGateAAuthorityValidator.validate(raw);
        assertNotNull(result);
        assertEquals("capability-studio.gate-a-protocol-authority.v1", result.get("schemaVersion"));
        assertEquals("GATE-A-PROTOCOL-AUTHORITY", result.get("authorityId"));
        assertEquals(1, ((Number) result.get("revision")).intValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    void extractsExactly8DependencyPins() {
        byte[] raw = readTrackedAuthority();
        Map<String, Object> result = CapabilityStudioGateAAuthorityValidator.validate(raw);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> pins =
                (Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin>)
                        result.get("_dependencyPins");

        assertNotNull(pins, "_dependencyPins must be present");
        assertEquals(8, pins.size(), "exactly 8 dependency pins expected");

        Set<String> expectedLockIds = Set.of(
                "JACKSON_DATABIND_2_18_2",
                "JACKSON_ANNOTATIONS_2_18_2",
                "JACKSON_CORE_2_18_2",
                "OPENTEST4J_1_3_0",
                "NETWORKNT_JSON_SCHEMA_VALIDATOR_2_0_4",
                "ETHLO_TIME_ITU_1_14_0",
                "SLF4J_API_2_0_17",
                "SLF4J_NOP_2_0_17"
        );
        assertEquals(expectedLockIds, pins.keySet());

        for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : pins.values()) {
            assertNotNull(pin.sha256, "sha256 must not be null for " + pin.lockId);
            assertTrue(pin.sha256.startsWith("sha256:"), "sha256 must start with prefix for " + pin.lockId);
            assertEquals(64, pin.bareSha256().length(), "bare sha256 must be 64 hex chars for " + pin.lockId);
            assertNotNull(pin.scope, "scope must not be null for " + pin.lockId);
            assertEquals("runtime", pin.scope, "scope must be runtime for " + pin.lockId);
            assertNotNull(pin.entryPath, "entryPath must not be null for " + pin.lockId);
            assertFalse(pin.entryPath.isBlank(), "entryPath must not be blank for " + pin.lockId);
            assertNotNull(pin.coordinate, "coordinate must not be null for " + pin.lockId);
            assertNotNull(pin.groupId(), "groupId must not be null for " + pin.lockId);
            assertNotNull(pin.artifactId(), "artifactId must not be null for " + pin.lockId);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void extractsExactly57VisibleSchemaIds() {
        byte[] raw = readTrackedAuthority();
        Map<String, Object> result = CapabilityStudioGateAAuthorityValidator.validate(raw);

        List<String> visibleIds = (List<String>) result.get("_visibleSchemaIds");
        assertNotNull(visibleIds, "_visibleSchemaIds must be present");
        assertEquals(57, visibleIds.size(),
                "exactly 57 visibleSchemaIds expected per source authority");

        for (String id : visibleIds) {
            assertNotNull(id);
            assertTrue(id.endsWith(".schema.json"), "visibleSchemaId must end with .schema.json: " + id);
        }
    }

    // ── Capabilities order mutation ───────────────────────────────────

    @Test
    void rejectsCapabilitiesOrderDrift() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = jackson.readValue(
                    new String(readTrackedAuthority(), StandardCharsets.UTF_8),
                    Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roleContracts = (List<Map<String, Object>>) auth.get("roleContracts");
            Map<String, Object> implCandidate = null;
            for (Map<String, Object> rc : roleContracts) {
                if ("IMPLEMENTATION_CANDIDATE".equals(rc.get("role"))) {
                    implCandidate = rc;
                    break;
                }
            }
            assertNotNull(implCandidate, "IMPLEMENTATION_CANDIDATE role must be present");
            @SuppressWarnings("unchecked")
            Map<String, Object> blackBoxContract =
                    (Map<String, Object>) implCandidate.get("blackBoxContract");
            @SuppressWarnings("unchecked")
            List<Object> capabilities = (List<Object>) blackBoxContract.get("capabilities");
            Object first = capabilities.get(0);
            capabilities.set(0, capabilities.get(1));
            capabilities.set(1, first);
            byte[] mutated = jackson.writeValueAsBytes(auth);
            CapabilityStudioGateAException ex = assertThrows(
                    CapabilityStudioGateAException.class,
                    () -> CapabilityStudioGateAAuthorityValidator.validate(mutated)
            );
            assertTrue(ex.getMessage().startsWith("AUTHORITY_CAPABILITIES_ORDER_DRIFT"),
                    ex.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejectsMainClassDrift() {
        byte[] raw = readTrackedAuthority();
        String json = new String(raw, StandardCharsets.UTF_8);
        String mutated = json.replace(
                "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli",
                "com.example.MaliciousClass"
        );
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(mutated.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("AUTHORITY_MAIN_CLASS_DRIFT"), ex.getMessage());
    }

    // ── Capabilities size mutation ────────────────────────────────────

    @Test
    void rejectsCapabilitiesSizeDrift() {
        byte[] raw = readTrackedAuthority();
        String json = new String(raw, StandardCharsets.UTF_8);
        // Inject an extra invalid capability at the start of the capabilities array
        String mutated = json.replace(
                "\"capabilities\": [\n          \"ROLE_SELF_TEST_RECEIPT\",",
                "\"capabilities\": [\n          \"EXTRA_INVALID_CAP\",\n          \"ROLE_SELF_TEST_RECEIPT\","
        );
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(mutated.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("AUTHORITY_CAPABILITIES_SIZE_DRIFT"), ex.getMessage());
    }

    // ── Domain mutation ───────────────────────────────────────────────

    @Test
    void rejectsRoleViewDomainDrift() {
        byte[] raw = readTrackedAuthority();
        String json = new String(raw, StandardCharsets.UTF_8);
        String mutated = json.replace(
                "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1",
                "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v99"
        );
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(mutated.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("AUTHORITY_ROLE_VIEW_DOMAIN_DRIFT"), ex.getMessage());
    }

    // ── Unknown lock ID in runtimeDeps ─────────────────────────────

    @Test
    void rejectsUnknownRuntimeDepLockId() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = jackson.readValue(
                    new String(readTrackedAuthority(), StandardCharsets.UTF_8),
                    Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roleContracts = (List<Map<String, Object>>) auth.get("roleContracts");
            Map<String, Object> implCandidate = null;
            for (Map<String, Object> rc : roleContracts) {
                if ("IMPLEMENTATION_CANDIDATE".equals(rc.get("role"))) {
                    implCandidate = rc;
                    break;
                }
            }
            assertNotNull(implCandidate, "IMPLEMENTATION_CANDIDATE role must be present");
            @SuppressWarnings("unchecked")
            List<String> runtimeDeps = (List<String>) implCandidate.get("runtimeDependencyLockIds");
            assertNotNull(runtimeDeps, "runtimeDependencyLockIds must not be null");
            assertFalse(runtimeDeps.isEmpty(), "runtimeDependencyLockIds must not be empty");
            // Mutate only the first entry in runtimeDependencyLockIds to an unknown ID
            runtimeDeps.set(0, "UNKNOWN_LOCK_ID_XYZ999");
            byte[] mutated = jackson.writeValueAsBytes(auth);
            CapabilityStudioGateAException ex = assertThrows(
                    CapabilityStudioGateAException.class,
                    () -> CapabilityStudioGateAAuthorityValidator.validate(mutated)
            );
            assertTrue(
                    ex.getMessage().startsWith("AUTHORITY_RUNTIME_DEP_NOT_IN_DEPENDENCY_AUTHORITY")
                            || ex.getMessage().startsWith("AUTHORITY_RUNTIME_DEP_NOT_IN_EMBEDDED_ENTRIES"),
                    "Expected runtime-dep error, got: " + ex.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Duplicate lock ID in dependencyAuthority ───────────────────────

    @Test
    void rejectsDuplicateDependencyLockId() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = jackson.readValue(
                    new String(readTrackedAuthority(), StandardCharsets.UTF_8),
                    Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> depAuth = (Map<String, Object>) auth.get("dependencyAuthority");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deps = (List<Map<String, Object>>) depAuth.get("dependencies");
            assertTrue(deps.size() >= 2, "Need at least 2 dependencies to test duplicate");
            // Change the second entry's lockId to match the first entry's lockId,
            // producing a genuine duplicate within the 8-entry list.
            String firstLockId = (String) deps.get(0).get("lockId");
            @SuppressWarnings("unchecked")
            Map<String, Object> second = (Map<String, Object>) deps.get(1);
            second.put("lockId", firstLockId);
            byte[] mutated = jackson.writeValueAsBytes(auth);
            CapabilityStudioGateAException ex = assertThrows(
                    CapabilityStudioGateAException.class,
                    () -> CapabilityStudioGateAAuthorityValidator.validate(mutated)
            );
            assertTrue(ex.getMessage().startsWith("AUTHORITY_DEPENDENCY_LOCK_ID_DUPLICATE"),
                    ex.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Dependency fingerprint invalid format ─────────────────────────

    @Test
    void rejectsDependencyFingerprintInvalidFormat() {
        // AuthorityValidator checks rawFingerprint starts with "sha256:" and bare part is 64 hex chars.
        // Replace the first sha256: with md5: to trigger FORMAT_INVALID.
        byte[] raw = readTrackedAuthority();
        String json = new String(raw, StandardCharsets.UTF_8);
        String mutated = json.replaceFirst("sha256:", "md5:");
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(mutated.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(
                ex.getMessage().startsWith("AUTHORITY_DEPENDENCY_FINGERPRINT_FORMAT_INVALID")
                        || ex.getMessage().startsWith("AUTHORITY_DEPENDENCY_FINGERPRINT"),
                "Expected fingerprint format error, got: " + ex.getMessage());
    }

    // ── Missing required JAR entry ──────────────────────────────────

    @Test
    void rejectsMissingRequiredJarEntry() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = jackson.readValue(
                    new String(readTrackedAuthority(), StandardCharsets.UTF_8),
                    Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roleContracts = (List<Map<String, Object>>) auth.get("roleContracts");
            Map<String, Object> implCandidate = null;
            for (Map<String, Object> rc : roleContracts) {
                if ("IMPLEMENTATION_CANDIDATE".equals(rc.get("role"))) {
                    implCandidate = rc;
                    break;
                }
            }
            assertNotNull(implCandidate, "IMPLEMENTATION_CANDIDATE role must be present");
            // Set requiredJarEntries to empty list (AuthorityValidator requires non-null, non-empty)
            implCandidate.put("requiredJarEntries", List.of());
            byte[] mutated = jackson.writeValueAsBytes(auth);
            CapabilityStudioGateAException ex = assertThrows(
                    CapabilityStudioGateAException.class,
                    () -> CapabilityStudioGateAAuthorityValidator.validate(mutated)
            );
            assertTrue(ex.getMessage().startsWith("AUTHORITY_REQUIRED_JAR_ENTRIES_MISSING"),
                    ex.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Invalid UTF-8 ───────────────────────────────────────────────

    @Test
    void rejectsInvalidUtf8() {
        // A byte sequence that is invalid UTF-8: 0x80 alone is a continuation byte
        byte[] invalid = new byte[] { (byte) 0x80, (byte) 0x81 };
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(invalid)
        );
        assertTrue(ex.getMessage().startsWith("UTF8_DECODE_ERROR") || ex.getMessage().startsWith("UTF8_INVALID_SEQUENCE"),
                "Expected UTF-8 error, got: " + ex.getMessage());
    }

    // ── Duplicate member in JSON ───────────────────────────────────

    @Test
    void rejectsDuplicateMember() {
        byte[] raw = readTrackedAuthority();
        String json = new String(raw, StandardCharsets.UTF_8);
        // Insert a duplicate key: repeat the authorityId field
        String mutated = json.replaceFirst(
                "(\"authorityId\":\\s*\\\")([^\"]+)\\\"",
                "\"authorityId\":\"$2\",\"authorityId\":\"DUPE\""
        );
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(mutated.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("DUPLICATE_MEMBER"), ex.getMessage());
    }

    // ── Trailing comma ───────────────────────────────────────────

    @Test
    void rejectsTrailingComma() {
        byte[] raw = readTrackedAuthority();
        String json = new String(raw, StandardCharsets.UTF_8);
        // Inject a double comma (trailing comma) after a value
        String mutated = json.replaceFirst(
                "(\"authorityId\":\\s*\\\")([^\"]+)\\\",",
                "\"authorityId\":\"$2\",,"
        );
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(mutated.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("TRAILING_OR_INVALID") || ex.getMessage().startsWith("OBJECT_KEY_MUST_BE_STRING"),
                "Expected parse error, got: " + ex.getMessage());
    }

    // ── Non-finite number ────────────────────────────────────────

    @Test
    void rejectsNonFiniteNumber() {
        // JSON with NaN (not valid JSON)
        String withNaN = "{\"schemaVersion\": \"capability-studio.gate-a-protocol-authority.v1\", \"authorityId\": \"GATE-A-PROTOCOL-AUTHORITY\", \"revision\": NaN}";
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(withNaN.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("INVALID_START") || ex.getMessage().startsWith("NON_FINITE"),
                "Expected parse error for NaN, got: " + ex.getMessage());
    }

    // ── Unpaired surrogate ──────────────────────────────────────

    @Test
    void rejectsUnpairedSurrogate() {
        // Inject a lone high surrogate (\uD800) via a raw byte array so there is no
        // string-escape ambiguity. The byte sequence 0x5C 0x75 0x44 0x38 0x30 0x30
        // encodes the JSON escape sequence \uD800, which the parser reads as the lone
        // surrogate code point U+D800 and rejects with INVALID_SURROGATE.
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = jackson.readValue(
                    new String(readTrackedAuthority(), StandardCharsets.UTF_8),
                    Map.class);
            auth.put("testSurrogateField", "placeholder");
            byte[] base = jackson.writeValueAsBytes(auth);
            String baseStr = new String(base, StandardCharsets.UTF_8);
            int placeholderPos = baseStr.indexOf("\"placeholder\"");
            byte[] head = Arrays.copyOf(base, placeholderPos);
            byte[] tail = Arrays.copyOfRange(base, placeholderPos + 13, base.length);
            // Build "hello\uD800world" as raw bytes: quote + hello + backslash + u + D800 + world + quote
            byte[] withSurrogate = new byte[] {
                    0x22, // opening quote
                    0x68, 0x65, 0x6C, 0x6C, 0x6F, // hello
                    0x5C, // backslash
                    0x75, // u
                    0x44, 0x38, 0x30, 0x30, // D800
                    0x77, 0x6F, 0x72, 0x6C, 0x64, // world
                    0x22  // closing quote
            };
            byte[] result = new byte[head.length + withSurrogate.length + tail.length];
            System.arraycopy(head, 0, result, 0, head.length);
            System.arraycopy(withSurrogate, 0, result, head.length, withSurrogate.length);
            System.arraycopy(tail, 0, result, head.length + withSurrogate.length, tail.length);
            CapabilityStudioGateAException ex = assertThrows(
                    CapabilityStudioGateAException.class,
                    () -> CapabilityStudioGateAAuthorityValidator.validate(result)
            );
            assertTrue(
                    ex.getMessage().startsWith("INVALID_SURROGATE") || ex.getMessage().startsWith("UTF8_INVALID_SEQUENCE"),
                    "Expected surrogate error, got: " + ex.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejectsDepthLimit() {
        // Build a JSON object nested 300 levels deep
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":\"capability-studio.gate-a-protocol-authority.v1\",\"authorityId\":\"GATE-A-PROTOCOL-AUTHORITY\",\"revision\":1,\"nested\":");
        for (int i = 0; i < 300; i++) {
            sb.append("{\"a\":");
        }
        sb.append("0");
        for (int i = 0; i < 300; i++) {
            sb.append("}");
        }
        sb.append("}");
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(sb.toString().getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("DEPTH_LIMIT_EXCEEDED"), ex.getMessage());
    }

    // ── Node budget limit ────────────────────────────────────────

    @Test
    void rejectsNodeBudgetExceeded() {
        // Build a flat JSON array with 100_001 scalar elements inside a valid root object.
        // Parser counts every scalar (including 100_001 array elements) and throws
        // NODE_BUDGET_EXCEEDED before AuthorityValidator schema-inventory check.
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":\"capability-studio.gate-a-protocol-authority.v1\",");
        sb.append("\"authorityId\":\"GATE-A-PROTOCOL-AUTHORITY\",");
        sb.append("\"revision\":1,");
        sb.append("\"schemaInventoryPolicy\":{\"closed\":true,\"gateASchemas\":[]},");
        sb.append("\"big\":[0");
        for (int i = 1; i <= 100_000; i++) {
            sb.append(",0");
        }
        sb.append("]}");
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAAuthorityValidator.validate(sb.toString().getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(ex.getMessage().startsWith("NODE_BUDGET_EXCEEDED"), ex.getMessage());
    }
}
