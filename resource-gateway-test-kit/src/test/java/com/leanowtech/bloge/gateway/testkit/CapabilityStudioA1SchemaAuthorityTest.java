package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Authority test for the {@code resource-gateway-capability-studio-a1} schema surface.
 *
 * <p>Verifies the wire-contract surface for Capability Studio Gate A1 Step 0:
 * static semantic compilation of all 13 release schemas, unique {@code $id}
 * enforcement, meta-schema constraint, and classpath quarantine boundaries.</p>
 *
 * <p>Valid fixture coverage is out of scope.  This test covers only static schema
 * semantics: strict JSON parsing (STRICT_DUPLICATE_DETECTION + FAIL_ON_TRAILING_TOKENS),
 * compilation with authority-bound {@code $ref} resolution, identity, and packaging boundaries.</p>
 *
 * <ul>
 *   <li>Exactly 13 {@code *.schema.json} resources are available to tests under
 *       {@code schemas/resource-gateway-capability-studio-a1/}</li>
 *   <li>Each schema parses as strict JSON (no duplicate keys, no trailing tokens)</li>
 *   <li>All 13 schemas compile independently with authority-bound absolute and
 *       local-relative {@code $ref} resolution</li>
 *   <li>Each schema has a unique top-level {@code $id}</li>
 *   <li>Every schema declares
 *       {@code "https://json-schema.org/draft/2020-12/schema"} as its {@code $schema}</li>
 *   <li>Every root schema has {@code "additionalProperties": false}</li>
 *   <li>The classpath contains no Python artifacts ({@code .py}, {@code .pyc})</li>
 *   <li>The classpath contains no entry whose path contains
 *       {@code step0-manifest}, {@code gate-a1-step0}, or {@code quarantine}
 *       (non-release boundaries)</li>
 *   <li>The 13 exact schema paths are compared as full relative paths without
 *       basename folding</li>
 * </ul>
 */
class CapabilityStudioA1SchemaAuthorityTest {

    private static final String CP_PATH = "schemas/resource-gateway-capability-studio-a1/";
    private static final String META_SCHEMA_URI =
            "https://json-schema.org/draft/2020-12/schema";

    /** The authoritative 13-schema set expected on the classpath. */
    private static final Set<String> EXPECTED_FILENAMES = Set.of(
            "acceptance-receipt-v1.schema.json",
            "attack-case-v1.schema.json",
            "compiler-manifest-v1.schema.json",
            "evidence-catalog-entry-v1.schema.json",
            "hermetic-observation-v1.schema.json",
            "ledger-entry-v1.schema.json",
            "normative-primitives-v1.schema.json",
            "observation-receipt-v1.schema.json",
            "observer-failure-v1.schema.json",
            "oracle-manifest-v1.schema.json",
            "revocation-record-v1.schema.json",
            "source-package-v1.schema.json",
            "source-unit-v1.schema.json"
    );

    /**
     * Strict JSON parser: strict duplicate-key detection per {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION}
     * and trailing-token rejection per {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}.
     * Used to validate every schema before it is handed to the networknt registry.
     */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(
            new JsonFactory().rebuild()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // -------------------------------------------------------------------------
    // Test 1 – Exact filename set
    // -------------------------------------------------------------------------

    /**
     * Asserts the test classpath (target/test-classes or a protocol JAR) contains exactly
     * the 13 expected A1 schema files as full-relative paths and no others.
     * Comparison is performed on full relative paths from the classpath root — basenames
     * are NOT collapsed — so that directory boundary integrity is also verified.
     * Boundary: the a1 schema set is closed; any extra file or missing file signals a
     * packaging error. Handles both file: (Surefire target/test-classes) and jar:
     * protocols without consulting target/classes, where stale files could hide a leak.
     */
    @Test
    void classpath_packages_exactly_the_13_expected_schema_files() {
        // listClasspathResources returns full relative paths (e.g.
        // "schemas/resource-gateway-capability-studio-a1/foo.schema.json"); no
        // basename collapsing is performed.
        Set<String> found = new TreeSet<>();
        for (String fullPath : listClasspathResources(CP_PATH, ".schema.json")) {
            found.add(fullPath);
        }
        assertThat(found)
                .as("Classpath must contain exactly the 13 expected A1 schema full-relative paths "
                        + "(no basename collapse; handles both file: and jar: protocol)")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_FILENAMES.stream()
                        .map(name -> CP_PATH + name)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    // -------------------------------------------------------------------------
    // Test 2 – Strict JSON parsing on every resource (before registry)
    // -------------------------------------------------------------------------

    /**
     * Asserts every schema parses as strict JSON: no duplicate keys and no trailing
     * tokens.  Validation is performed before the schema is handed to networknt.
     * Boundary: non-strict JSON would silently accept duplicate-key ambiguity.
     */
    @Test
    void every_schema_is_strict_json_no_duplicate_keys_no_trailing_tokens()
            throws IOException {
        for (String filename : EXPECTED_FILENAMES) {
            String resourcePath = "/" + CP_PATH + filename;
            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    fail("Classpath resource not found: " + resourcePath);
                    continue;
                }
                String schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // Throws on duplicate keys or trailing tokens
                STRICT_JSON.readTree(schemaText);
            } catch (IOException e) {
                fail("Schema '" + filename
                        + "' failed strict JSON parse (duplicate key or trailing token): "
                        + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 3 – Draft2020-12 meta-schema on every resource
    // -------------------------------------------------------------------------

    /**
     * Asserts every schema declares Draft2020-12 as its meta-schema.
     * Boundary: wrong draft version would bypass {@code $ref} resolution guarantees.
     */
    @Test
    void every_schema_declares_draft_2020_12_meta_schema() throws IOException {
        for (String filename : EXPECTED_FILENAMES) {
            JsonNode root = loadSchemaNode(filename);
            String metaSchema = root.path("$schema").asText("");
            assertThat(metaSchema)
                    .as("Schema '%s' must declare the Draft2020-12 meta-schema URI", filename)
                    .isEqualTo(META_SCHEMA_URI);
        }
    }

    // -------------------------------------------------------------------------
    // Test 4 – Unique $id across the surface
    // -------------------------------------------------------------------------

    /**
     * Asserts every schema declares a top-level {@code $id} and all 13 are distinct.
     * Boundary: duplicate {@code $id}s would cause registry collision during
     * compilation.
     */
    @Test
    void every_schema_declares_a_unique_top_level_id() throws IOException {
        Set<String> ids = new TreeSet<>();
        for (String filename : EXPECTED_FILENAMES) {
            JsonNode root = loadSchemaNode(filename);
            String id = root.path("$id").asText("");
            assertThat(id)
                    .as("Schema '%s' must declare a top-level $id", filename)
                    .isNotEmpty();
            assertThat(ids.add(id))
                    .as("Top-level $id '%s' from '%s' must be unique across the surface", id, filename)
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Test 5 – Root additionalProperties=false on every schema
    // -------------------------------------------------------------------------

    /**
     * Asserts every root schema has {@code additionalProperties: false}.
     * Boundary: any root schema permitting additional properties would weaken
     * the closed-world contract of the gate.
     */
    @Test
    void every_root_schema_has_additionalProperties_false() throws IOException {
        for (String filename : EXPECTED_FILENAMES) {
            JsonNode root = loadSchemaNode(filename);
            boolean additionalProperties = root.path("additionalProperties").asBoolean(true);
            assertThat(additionalProperties)
                    .as("Root schema '%s' must have additionalProperties=false", filename)
                    .isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Test 6 – Networknt Draft2020-12 registry compilation with local refs
    // -------------------------------------------------------------------------

    /**
     * Compiles all 13 schemas independently using a fresh networknt Draft2020-12
     * {@link SchemaRegistry} and an explicit {@code $id -> filename} authority map.
     *
     * <p>Absolute URNs must exactly match one of the 13 declared schema IDs. Relative
     * refs may target only a filename in the same authority root and may not contain
     * traversal or non-normalized path segments.</p>
     *
     * <p>Boundary: any {@code $ref} that cannot be resolved, any schema that fails
     * to parse, or any registry collision is a compilation failure here — not a
     * fixture error (fixture coverage is out of scope for this authority test).</p>
     */
    @Test
    void networknt_registry_compiles_all_13_schemas() throws IOException {
        Map<String, String> authority = loadSchemaAuthority();
        List<String> failures = new ArrayList<>();
        for (String filename : new TreeSet<>(EXPECTED_FILENAMES)) {
            String location = CP_PATH + filename;
            try (InputStream in = getClass().getResourceAsStream("/" + location)) {
                if (in == null) {
                    failures.add("Cannot load classpath resource: /" + location);
                    continue;
                }
                String schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                STRICT_JSON.readTree(schemaText);
                SchemaRegistry registry = buildA1Registry(getClass(), CP_PATH, authority);
                Schema schema = registry.getSchema(
                        SchemaLocation.of(location),
                        schemaText,
                        InputFormat.JSON);
                if (schema == null) {
                    failures.add("Registry returned null for: " + location);
                }
            } catch (Exception e) {
                failures.add("Compilation failed for '" + location + "': " + e.getMessage());
            }
        }

        assertThat(failures)
                .as("All 13 A1 schemas must compile cleanly in the networknt Draft2020-12 registry "
                        + "independently with authority-bound $ref resolution")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 7 – No Python artifacts in classpath
    // -------------------------------------------------------------------------

    /**
     * Asserts the classpath contains no Python source or bytecode files.
     * Boundary: Python artifacts indicate a quarantine-space leak.
     */
    @Test
    void classpath_contains_zero_python_files() {
        Set<String> violations = new TreeSet<>();
        violations.addAll(listClasspathResources("", ".py"));
        violations.addAll(listClasspathResources("", ".pyc"));
        assertThat(violations)
                .as("Classpath must not contain any .py or .pyc files (quarantine boundary)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 8 – No non-release paths in classpath
    // -------------------------------------------------------------------------

    /**
     * Asserts the classpath contains no entry whose path segment contains
     * {@code step0-manifest}, {@code gate-a1-step0}, or {@code quarantine}.
     * Boundary: these strings signal non-release or quarantine-space paths that
     * must not be packaged into the release surface.
     * Matches YAML and JSON manifests alike.
     */
    @Test
    void classpath_contains_no_non_release_paths() {
        Set<String> violations = new TreeSet<>();
        // Collect all entries for suffix scan
        Set<String> allYaml = listClasspathResources("", ".yaml");
        Set<String> allYml = listClasspathResources("", ".yml");
        Set<String> allJson = listClasspathResources("", ".json");
        Set<String> allPy = listClasspathResources("", ".py");
        Set<String> allPyc = listClasspathResources("", ".pyc");

        // Build full entry set for full-path leak check
        Set<String> allEntries = new TreeSet<>();
        allEntries.addAll(allYaml);
        allEntries.addAll(allYml);
        allEntries.addAll(allJson);
        allEntries.addAll(allPy);
        allEntries.addAll(allPyc);

        // Check full relative paths (as reported by listClasspathResources) for the
        // forbidden path segments.  listClasspathResources returns the relative entry
        // name from the root of the classpath resource tree.
        for (String entry : allEntries) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.contains("step0-manifest")
                    || lower.contains("gate-a1-step0")
                    || lower.contains("quarantine")) {
                violations.add(entry);
            }
        }
        assertThat(violations)
                .as("Classpath must not contain any entry whose path contains "
                        + "'step0-manifest', 'gate-a1-step0', or 'quarantine' "
                        + "(non-release / quarantine boundary)")
                .isEmpty();
    }


    // -------------------------------------------------------------------------
    // Negative tests — networknt schema enforcement
    // -------------------------------------------------------------------------

    /**
     * Asserts that the networknt validator rejects an all-zero WireDigest.
     * Per {@code normative-primitives-v1.schema.json}: {@code not: { const: "sha256:0000..." }}
     * rejects the genesis sentinel.  Valid digests and {@code genesis-zero} sentinel
     * strings are NOT subject to this constraint.
     */
    @Test
    void networknt_rejects_all_zero_wire_digest() throws IOException {
        String allZeros = "sha256:0000000000000000000000000000000000000000000000000000000000000000";
        Schema schema = acceptanceReceiptSchema();
        ObjectNode receipt = validAcceptanceReceipt();

        assertThat(validate(schema, receipt))
                .as("Acceptance receipt baseline must be valid before the digest mutation")
                .isEmpty();

        receipt.put("invocationKeyFP", allZeros);
        List<com.networknt.schema.Error> errors = validate(schema, receipt);

        assertThat(errors)
                .as("All-zero invocationKeyFP must be rejected by the WireDigest not-constraint")
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("not");
                    assertThat(error.getInstanceLocation().toString()).contains("invocationKeyFP");
                });
    }

    /**
     * Asserts that the networknt validator rejects an unknown {@code status} value.
     * Per {@code acceptance-receipt-v1.schema.json}: {@code status} is a closed enum
     * of {@code ["ACCEPTED", "REJECTED", "ERROR"]}.  NON_RELEASE is not a valid
     * target status per §12.3 R3.
     */
    @Test
    void networknt_rejects_unknown_receipt_status() throws IOException {
        Schema schema = acceptanceReceiptSchema();
        ObjectNode receipt = validAcceptanceReceipt();

        assertThat(validate(schema, receipt))
                .as("Acceptance receipt baseline must be valid before the status mutation")
                .isEmpty();

        receipt.put("status", "UNKNOWN");
        List<com.networknt.schema.Error> errors = validate(schema, receipt);

        assertThat(errors)
                .as("Unknown receipt status 'UNKNOWN' must be rejected by the closed status enum")
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("enum");
                    assertThat(error.getInstanceLocation().toString()).contains("status");
                });
    }

    @Test
    void source_unit_requires_a_valid_relation_handle() throws IOException {
        Schema schema = a1Schema("source-unit-v1.schema.json");
        ObjectNode sourceUnit = validSourceUnit();

        assertThat(validate(schema, sourceUnit))
                .as("SourceUnit baseline with relationHandle must be valid")
                .isEmpty();

        ObjectNode missingHandle = sourceUnit.deepCopy();
        missingHandle.remove("relationHandle");
        assertThat(validate(schema, missingHandle))
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("required");
                    assertThat(error.toString()).contains("relationHandle");
                });

        assertThat(validate(schema, sourceUnit))
                .as("SourceUnit baseline must remain valid before the invalid-handle mutation")
                .isEmpty();

        ObjectNode invalidHandle = sourceUnit.deepCopy();
        invalidHandle.put("relationHandle", "not-a-128-bit-handle");
        assertThat(validate(schema, invalidHandle))
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("pattern");
                    assertThat(error.getInstanceLocation().toString()).contains("relationHandle");
                });
    }

    @Test
    void evidence_catalog_reserves_observer_generated_outcomes_for_observer_failure()
            throws IOException {
        Schema schema = a1Schema("evidence-catalog-entry-v1.schema.json");
        ObjectNode reducerEntry = validReducerEvidenceCatalogEntry();

        assertThat(validate(schema, reducerEntry))
                .as("Ordinary reducer-derived evidence baseline must be valid")
                .isEmpty();

        ObjectNode ordinaryObserverGenerated = reducerEntry.deepCopy();
        ordinaryObserverGenerated.withObject("semanticVerifier")
                .put("outcomeSource", "OBSERVER_GENERATED");
        assertThat(validate(schema, ordinaryObserverGenerated))
                .as("Ordinary evidence must not claim observer-generated outcome authority")
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("const");
                    assertThat(error.getInstanceLocation().toString())
                            .contains("semanticVerifier/outcomeSource");
                });

        ObjectNode observerFailure = validObserverFailureCatalogEntry();
        assertThat(validate(schema, observerFailure))
                .as("The exact ObserverFailure generated catalog entry must be valid")
                .isEmpty();

        ObjectNode observerIdentityWithReducerOutcome = observerFailure.deepCopy();
        observerIdentityWithReducerOutcome.withObject("semanticVerifier")
                .put("outcomeSource", "REDUCTOR_DERIVED");
        assertThat(validate(schema, observerIdentityWithReducerOutcome))
                .as("ObserverFailure identity must not claim reducer-derived outcome authority")
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("const");
                    assertThat(error.getInstanceLocation().toString())
                            .contains("semanticVerifier/outcomeSource");
                });
    }

    @Test
    void hermetic_observation_rejects_legacy_observation_receipt_fingerprint()
            throws IOException {
        Schema schema = a1Schema("hermetic-observation-v1.schema.json");
        ObjectNode observation = validHermeticObservation();

        assertThat(validate(schema, observation))
                .as("Hermetic observation baseline must be valid")
                .isEmpty();

        observation.put("observationReceiptFP", "sha256:" + "a".repeat(64));
        assertThat(validate(schema, observation))
                .as("Hermetic observation must reject the legacy observationReceiptFP model")
                .anySatisfy(error -> {
                    assertThat(error.getKeyword()).isEqualTo("additionalProperties");
                    assertThat(error.toString()).contains("observationReceiptFP");
                });
    }

    private Schema acceptanceReceiptSchema() throws IOException {
        return a1Schema("acceptance-receipt-v1.schema.json");
    }

    private Schema a1Schema(String filename) throws IOException {
        SchemaRegistry registry = buildA1Registry(getClass(), CP_PATH);
        String location = CP_PATH + filename;
        try (InputStream in = getClass().getResourceAsStream("/" + location)) {
            assertThat(in).as("A1 schema must be present on the classpath: %s", filename).isNotNull();
            String schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return registry.getSchema(SchemaLocation.of(location), schemaText, InputFormat.JSON);
        }
    }

    private static ObjectNode validAcceptanceReceipt() {
        String goodDigest = "sha256:" + "a".repeat(64);
        return STRICT_JSON.createObjectNode()
                .put("invocationKey", "test-key")
                .put("invocationKeyFP", goodDigest)
                .put("decisionInputFP", goodDigest)
                .put("reducerOutputFP", goodDigest)
                .put("resultFP", goodDigest)
                .put("ledgerEntryFP", goodDigest)
                .put("status", "ACCEPTED")
                .put("issuedAt", "2026-08-20T00:00:00Z")
                .put("issuerId", "test-issuer");
    }

    private static ObjectNode validSourceUnit() {
        ObjectNode sourceUnit = STRICT_JSON.createObjectNode()
                .put("apiVersion", "studio.a1/v1")
                .put("unitType", "rule")
                .put("unitId", "urn:studio:rule:fixture:one")
                .put("relationHandle", "0123456789abcdef0123456789abcdef");
        sourceUnit.putArray("roleVisibility").add("reducer");
        sourceUnit.putObject("content").put("name", "fixture-rule");
        return sourceUnit;
    }

    private static ObjectNode validReducerEvidenceCatalogEntry() {
        ObjectNode entry = STRICT_JSON.createObjectNode()
                .put("evidenceId", "urn:studio:a1:evidence:fixture")
                .put("schemaRef", "urn:studio:schema:evidence:fixture:v1")
                .put("producerOwner", "reducer")
                .put("artifactRelation", "PROVES");
        ObjectNode verifier = entry.putObject("semanticVerifier")
                .put("verifierId", "builtin:fixture-verifier")
                .put("policy", "READ_CATALOG_ONLY")
                .put("clockUsage", false)
                .put("randomUsage", false)
                .put("networkUsage", false)
                .put("outcomeSource", "REDUCTOR_DERIVED")
                .put("version", "1.0.0");
        verifier.putArray("inputs").add("urn:studio:a1:evidence:input");
        entry.putArray("relatedEvidenceRefs").add("urn:studio:a1:evidence:input");
        return entry;
    }

    private static ObjectNode validObserverFailureCatalogEntry() {
        ObjectNode entry = STRICT_JSON.createObjectNode()
                .put("evidenceId", "urn:studio:a1:observer-failure")
                .put("schemaRef", "urn:studio:schema:observer-failure:v1")
                .put("producerOwner", "observer")
                .put("visibilityPolicyRef", "urn:studio:visibility:observer-failure:v1")
                .put("artifactRelation", "RELATES_TO");
        ObjectNode verifier = entry.putObject("semanticVerifier")
                .put("verifierId", "builtin:observer-failure")
                .put("policy", "READ_CATALOG_ONLY")
                .put("clockUsage", false)
                .put("randomUsage", false)
                .put("networkUsage", false)
                .put("outcomeSource", "OBSERVER_GENERATED")
                .put("version", "1.0.0");
        verifier.putArray("inputs");
        entry.putArray("relatedEvidenceRefs");
        return entry;
    }

    private static ObjectNode validHermeticObservation() {
        String digest = "sha256:" + "a".repeat(64);
        ObjectNode observation = STRICT_JSON.createObjectNode()
                .put("observationId", "A1_HERMETIC_FIXTURE")
                .put("executionMode", "RELEASE")
                .put("probeTimestamp", "2026-08-20T00:00:00Z")
                .put("probeKernelVersion", "fixture-kernel")
                .put("sandboxAuthority", "PRESENT")
                .put("inputTreeFP", digest)
                .put("inputSnapshotManifestFP", digest)
                .put("inputSnapshotError", "NONE")
                .put("outputTreeFP", digest)
                .put("outputMaterialFP", digest)
                .put("outputMaterialError", "NONE")
                .put("codeSourceBeforeDigest", digest)
                .put("codeSourceAfterDigest", digest)
                .put("codeSourceManifestDigest", digest)
                .put("codeSourceError", "NONE")
                .put("processResidueError", "NONE")
                .put("toctouDetected", false)
                .put("toctouError", "NONE")
                .put("escapeDetected", false)
                .put("detachedChildEscaped", false)
                .put("quiescenceUnprovable", false)
                .put("quiescenceError", "NONE")
                .put("terminationReason", "EXITED")
                .put("exitCode", 0)
                .putNull("exitSignal")
                .put("durationMillis", 1)
                .put("stdoutHash", digest)
                .put("stdoutLength", 0)
                .put("stderrHash", digest)
                .put("stderrLength", 0)
                .put("logFileDetected", false)
                .put("secretInStdoutStderr", false)
                .put("spiMaterializationNonUnique", false)
                .put("spiMaterializationError", "NONE")
                .put("pathLeakDetected", false)
                .put("pathLeakError", "NONE");
        observation.putObject("capabilityProbe")
                .put("mountNamespace", "supported")
                .put("cgroupV2", "supported")
                .put("userNamespace", "supported")
                .put("seccomp", "supported")
                .put("landlock", "supported")
                .put("immutableAttr", "supported");
        observation.putObject("sandboxProfile")
                .put("clockUsage", false)
                .put("randomUsage", false)
                .put("networkUsage", false)
                .put("mountMode", "MINIMAL_ALLOWLIST")
                .put("hardlinkPolicy", "DENY_ALL")
                .put("symlinkPolicy", "DENY_ALL")
                .put("userNamespace", "REQUIRED")
                .put("capabilityMode", "MINIMAL");
        observation.putObject("processResidue")
                .put("openFileDescriptors", 0)
                .put("cgroupMembers", 0)
                .put("childProcesses", 0);
        return observation;
    }

    private static List<com.networknt.schema.Error> validate(Schema schema, ObjectNode document) {
        return schema.validate(document.toString(), InputFormat.JSON,
                ctx -> ctx.executionConfig(cfg -> cfg.failFast(false)));
    }

    /**
     * Asserts that the evidence verifier (via {@link TestingProtocolSchemaValidator})
     * rejects an {@code executionServiceStateSnapshot} where {@code restorable: true}
     * and {@code restoreGaps} is non-empty — violating the conditional schema constraint.
     *
     * <p>The conditional schema rule: {@code restorable: true} requires
     * {@code restoreGaps} to be empty (maxItems: 0).  A non-empty {@code usages}
     * combined with {@code restorable: true} and non-empty {@code restoreGaps} is
     * rejected by the conditional constraint.</p>
     *
     * <p>This negative test verifies the evidence verifier enforces the
     * {@code restorable} conditional invariant: active use states with gaps cannot
     * be safely restored.</p>
     */
    @Test
    void execution_service_state_snapshot_rejects_non_empty_restoreGaps_when_restorable()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", "bloge.executionServiceStateSnapshot.v1");
        snapshot.put("planFingerprint", "sha256:" + "a".repeat(64));
        snapshot.put("bindingSetFingerprint", "sha256:" + "b".repeat(64));
        snapshot.putNull("logicalTime");
        snapshot.putObject("randomScopeCursors");
        snapshot.putObject("uuidScopeCursors");

        // Non-empty usages: evidence verifier treats this as an in-use state
        ObjectNode usageEntry = mapper.createObjectNode();
        usageEntry.put("service", "TIME");
        usageEntry.put("providerCalls", 1);
        usageEntry.put("semanticProviderCalls", 1);
        usageEntry.put("functionCalls", 1);
        usageEntry.putArray("functionCallSites").add("src/main/java/Clock.java");
        usageEntry.putArray("providerScopeFingerprints").add("sha256:" + "c".repeat(64));
        snapshot.putArray("usages").add(usageEntry);

        snapshot.put("restorable", true);
        // restoreGaps is non-empty → violates the conditional: restorable:true → maxItems:0
        snapshot.putArray("restoreGaps").add("gap-001");
        snapshot.put("snapshotFingerprint", "sha256:" + "d".repeat(64));

        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                snapshot, "executionServiceStateSnapshot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionServiceStateSnapshot")
                .as("executionServiceStateSnapshot with restorable:true and non-empty restoreGaps must be rejected");
    }

    // -------------------------------------------------------------------------
    // Schema registry provider
    // -------------------------------------------------------------------------

    /**
     * Builds a networknt Draft2020-12 {@link SchemaRegistry} for the A1 schema surface.
     * The provider resolves:
     * <ul>
     *   <li>Relative {@code ./foo.schema.json} refs (from other schemas)</li>
     *   <li>URN-base resolution for schemas with {@code $id: urn:studio:schema:...} values
     *       (networknt resolves refs against the base URN, producing
     *       {@code urn:studio:schema:acceptance-receipt:normative-primitives-v1.schema.json};
     *       we extract the actual filename from the URN path)</li>
     * </ul>
     *
     * @param self the class to use for classpath resource resolution
     * @param cpPath classpath-relative directory prefix for the schema surface
     * @return a configured Draft2020-12 registry
     */
    private Map<String, String> loadSchemaAuthority() throws IOException {
        return loadSchemaAuthority(getClass(), CP_PATH);
    }

    private static Map<String, String> loadSchemaAuthority(Class<?> self, String cpPath)
            throws IOException {
        Map<String, String> authority = new java.util.LinkedHashMap<>();
        for (String filename : new TreeSet<>(EXPECTED_FILENAMES)) {
            String resourcePath = "/" + cpPath + filename;
            JsonNode schema;
            try (InputStream in = self.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IOException("Classpath resource not found: " + resourcePath);
                }
                schema = STRICT_JSON.readTree(in);
            }
            String id = schema.path("$id").asText("");
            if (!id.startsWith("urn:studio:schema:") || id.contains("#")) {
                throw new IOException("Invalid A1 schema authority ID in " + filename + ": " + id);
            }
            String previous = authority.putIfAbsent(id, filename);
            if (previous != null) {
                throw new IOException("Duplicate A1 schema authority ID " + id
                        + " in " + previous + " and " + filename);
            }
        }
        if (authority.size() != EXPECTED_FILENAMES.size()) {
            throw new IOException("A1 schema authority must contain exactly 13 entries");
        }
        return Map.copyOf(authority);
    }

    private static SchemaRegistry buildA1Registry(Class<?> self, String cpPath)
            throws IOException {
        return buildA1Registry(self, cpPath, loadSchemaAuthority(self, cpPath));
    }

    private static SchemaRegistry buildA1Registry(Class<?> self, String cpPath,
                                                   Map<String, String> authority) {
        Map<String, String> resolvable = new java.util.HashMap<>(authority);
        for (String sourceId : authority.keySet()) {
            String sourceRoot = sourceId.substring(0, sourceId.lastIndexOf(':') + 1);
            for (String targetFilename : EXPECTED_FILENAMES) {
                resolvable.put(sourceRoot + targetFilename, targetFilename);
            }
        }
        return SchemaRegistry.withDialect(
                Dialects.getDraft202012(),
                builder -> builder.schemas(uri -> {
                    String baseUri = uri.contains("#")
                            ? uri.substring(0, uri.indexOf('#'))
                            : uri;
                    String filename = resolvable.get(baseUri);
                    if (filename == null && baseUri.startsWith("./")) {
                        String relative = baseUri.substring(2);
                        if (isAuthorityFilename(relative)) {
                            filename = relative;
                        }
                    }
                    if (filename == null || !isAuthorityFilename(filename)) {
                        throw new IllegalArgumentException(
                                "Reference is outside the A1 schema authority: " + uri);
                    }
                    String resourcePath = "/" + cpPath + filename;
                    try (InputStream in = self.getResourceAsStream(resourcePath)) {
                        if (in == null) {
                            throw new IllegalArgumentException(
                                    "Authority resource is missing: " + resourcePath);
                        }
                        String schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        STRICT_JSON.readTree(schemaText);
                        return schemaText;
                    } catch (IOException e) {
                        throw new IllegalArgumentException(
                                "Authority resource is invalid: " + resourcePath, e);
                    }
                }));
    }

    private static boolean isAuthorityFilename(String value) {
        return EXPECTED_FILENAMES.contains(value)
                && !value.contains("/")
                && !value.contains("\\")
                && !value.equals(".")
                && !value.equals("..");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JsonNode loadSchemaNode(String filename) throws IOException {
        String resourcePath = "/" + CP_PATH + filename;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + resourcePath);
            }
            return STRICT_JSON.readTree(in);
        }
    }

    /**
     * Scans the classpath for entries under the given directory prefix that end
     * with the given suffix.
     *
     * <p>Supports two protocols:</p>
     * <ul>
     *   <li>{@code file:} — when running via Surefire against
     *       {@code target/test-classes}</li>
     *   <li>{@code jar:} — when running from a packaged JAR</li>
     * </ul>
     *
     * <p>Returns the full relative entry path from the root of the classpath
     * resource tree (e.g. {@code schemas/resource-gateway-capability-studio-a1/foo.schema.json}),
     * enabling full-path leak detection in quarantine boundary tests.</p>
     *
     * @param dirPrefix   classpath-relative directory prefix (e.g.
     *                    {@code "schemas/..."}); empty string scans from root
     * @param suffix      filename suffix to match (e.g. {@code ".schema.json"})
     * @return sorted set of full relative entry paths
     */
    private Set<String> listClasspathResources(String dirPrefix, String suffix) {
        Set<String> entries = new TreeSet<>();
        try {
            // Primary: try to get the class location via ProtectionDomain.
            // This works even when the TCCL does not expose the test class resource.
            java.net.URL selfUrl = null;
            try {
                java.security.ProtectionDomain pd = CapabilityStudioA1SchemaAuthorityTest.class.getProtectionDomain();
                if (pd != null) {
                    selfUrl = pd.getCodeSource().getLocation();
                }
            } catch (Exception ignored) { }

            // Fallback: try classloader resource lookup
            if (selfUrl == null) {
                String selfResource = "/" + getClass().getName().replace('.', '/') + ".class";
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = CapabilityStudioA1SchemaAuthorityTest.class.getClassLoader();
                }
                selfUrl = cl.getResource(selfResource);
            }

            // Last-resort fallback: walk from CWD/project-root (Surefire runs from module dir)
            if (selfUrl == null) {
                walkProjectRootClasspath(dirPrefix, suffix, entries);
                return entries;
            }

            String protocol = selfUrl.getProtocol();
            if ("jar".equals(protocol)) {
                walkJarClasspath(dirPrefix, suffix, selfUrl, entries);
            } else if ("file".equals(protocol)) {
                java.nio.file.Path location = java.nio.file.Path.of(selfUrl.toURI());
                if (java.nio.file.Files.isRegularFile(location)) {
                    walkJarFile(dirPrefix, suffix, location, entries);
                } else {
                    walkFileClasspath(dirPrefix, suffix, selfUrl, entries);
                }
            } else {
                walkProjectRootClasspath(dirPrefix, suffix, entries);
            }
        } catch (Exception e) {
            fail("Failed to scan classpath resources: " + e.getMessage());
        }
        return entries;
    }

    /**
     * Walks a JAR classpath, selecting entries that match the prefix and suffix.
     * Returns full relative paths from the JAR root.
     */
    private void walkJarClasspath(String dirPrefix, String suffix,
                                   java.net.URL selfUrl, Set<String> out) throws IOException {
        // URL format: jar:file:/path/to/jar!/resource
        String urlPath = selfUrl.getPath();
        int jarEnd = urlPath.indexOf("!/");
        if (jarEnd < 5) {
            return;
        }
        String jarFile = urlPath.substring("jar:file:".length(), jarEnd);
        String decoded = java.net.URLDecoder.decode(jarFile, StandardCharsets.UTF_8);
        java.util.jar.JarInputStream jar =
                new java.util.jar.JarInputStream(
                        new java.io.FileInputStream(decoded));
        try {
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()
                        && name.startsWith(dirPrefix)
                        && name.endsWith(suffix)) {
                    out.add(name);
                }
                jar.closeEntry();
            }
        } finally {
            jar.close();
        }
    }

    private void walkJarFile(String dirPrefix, String suffix,
                             java.nio.file.Path jarPath, Set<String> out) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(dirPrefix) && name.endsWith(suffix)) {
                    out.add(name);
                }
            }
        }
    }

    /**
     * Walks the test directory classpath (file: protocol), specifically
     * {@code target/test-classes}. Selects entries
     * matching the prefix and suffix.  Returns full relative paths from the
     * directory root.
     *
     * <p>The scan deliberately never falls back to {@code target/classes}; A1 is
     * test-only there and a stale main resource must not make this test pass.</p>
     */
    private void walkFileClasspath(String dirPrefix, String suffix,
                                   java.net.URL selfUrl, Set<String> out) throws IOException, URISyntaxException {
        Path selfPath = Path.of(selfUrl.toURI());
        Path classesRoot = Files.isDirectory(selfPath) ? selfPath : selfPath.getParent();
        while (classesRoot != null
                && !classesRoot.endsWith(Path.of("target", "test-classes"))) {
            classesRoot = classesRoot.getParent();
        }
        if (classesRoot == null || !Files.isDirectory(classesRoot)) {
            return;
        }

        final Path root = classesRoot;
        final String pref = dirPrefix;
        final String suf = suffix;

        Files.walk(classesRoot)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    return rel.startsWith(pref) && rel.endsWith(suf);
                })
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .forEach(out::add);
    }
    /**
     * Fallback classpath scanner that walks the Maven project root directory.
     * Used when the classloader resource lookup fails (e.g. under Surefire JUnit Platform
     * with custom classloaders that do not expose the test class as a resource).
     *
     * <p>Surefire runs from the module directory, so {@code user.dir} resolves to the
     * module root (e.g. {@code resource-gateway-test-kit/}).  The Maven resources phase
     * copies A1 schemas to {@code target/test-classes/}.</p>
     *
     * @param dirPrefix classpath-relative directory prefix to match
     * @param suffix    filename suffix to match
     * @param out       set to accumulate matching full-relative entry paths
     */
    private void walkProjectRootClasspath(String dirPrefix, String suffix, Set<String> out) {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path classesRoot = projectRoot.resolve("target").resolve("test-classes");
        if (!Files.isDirectory(classesRoot)) {
            return;
        }
        final Path root = classesRoot;
        final String pref = dirPrefix;
        final String suf = suffix;
        try {
            Files.walk(classesRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        return rel.startsWith(pref) && rel.endsWith(suf);
                    })
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .forEach(out::add);
        } catch (IOException ignored) { }
    }
}
