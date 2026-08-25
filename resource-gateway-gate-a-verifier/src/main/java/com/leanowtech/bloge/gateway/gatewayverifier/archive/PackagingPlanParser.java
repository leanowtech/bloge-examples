package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict parser for packaging plan JSON documents.
 *
 * <p>Validates the plan structure and extracts typed contract data:
 * <ul>
 *   <li>Accepts raw plan byte[] and externally expected SHA-256 fingerprint</li>
 *   <li>Hashes raw bytes before parsing to verify integrity</li>
 *   <li>Validates strict JSON structure: UTF-8, no duplicate keys, no trailing tokens</li>
 *   <li>Rejects non-finite numbers (NaN, Infinity)</li>
 *   <li>Validates exact schemaVersion, 28 unique archive entries, 7 embedded dependencies</li>
 *   <li>Validates strict field names and fingerprint formats</li>
 *   <li>Validates artifactLimits with required exact keys (maxTotalUncompressedBytes) and long values</li>
 * </ul>
 *
 * <p>Error codes (frozen protocol — no paths/system exception text):
 * <ul>
 *   <li>AK-PLAN-HASH-MISMATCH: computed SHA-256 does not match expected</li>
 *   <li>AK-PLAN-INVALID-JSON: JSON parsing error or malformed structure</li>
 *   <li>AK-PLAN-SCHEMA-MISMATCH: schemaVersion is not "v1"</li>
 *   <li>AK-PLAN-INVALID-ENTRIES: exactArchiveEntries validation failed</li>
 *   <li>AK-PLAN-INVALID-DEPS: embeddedDependencies validation failed</li>
 *   <li>AK-PLAN-INVALID-LIMITS: artifactLimits validation failed</li>
 *   <li>AK-PLAN-INVALID-FINGERPRINT: fingerprint format validation failed</li>
 * </ul>
 *
 * <p>This class is immutable and thread-safe.
 */
public final class PackagingPlanParser {

    /**
     * Immutable result of parsing, either success with a {@link PackagedPlan}
     * or a structured rejection.
     */
    public static final class ParseResult {

        private final PackagedPlan plan;
        private final String rejectionCode;
        private final Map<String, Object> rejectionArgs;

        private ParseResult(PackagedPlan plan, String rejectionCode, Map<String, Object> rejectionArgs) {
            this.plan = plan;
            this.rejectionCode = rejectionCode;
            this.rejectionArgs = rejectionArgs != null ? Map.copyOf(rejectionArgs) : null;
        }

        private static ParseResult success(PackagedPlan plan) {
            return new ParseResult(plan, null, null);
        }

        private static ParseResult rejection(String code, Map<String, Object> args) {
            return new ParseResult(null, code, args);
        }

        public boolean isSuccess() { return plan != null; }
        public PackagedPlan plan() { return plan; }
        public String rejectionCode() { return rejectionCode; }
        public Map<String, Object> rejectionArgs() { return rejectionArgs; }
        public boolean isRejected() { return !isSuccess(); }
    }

    private final ObjectMapper strictMapper;

    /**
     * Constructs a strict parser.
     */
    public PackagingPlanParser() {
        // Build ObjectMapper via JsonFactory to avoid deprecated JsonParser.Feature flags.
        // - ALLOW_NON_NUMERIC_NUMBERS: use JsonReadFeature (replaces JsonParser.Feature)
        // - STRICT_DUPLICATE_DETECTION: use StreamReadFeature (replaces JsonParser.Feature)
        JsonFactory factory = JsonFactory.builder()
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.strictMapper = new ObjectMapper(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /**
     * Parses a packaging plan from raw bytes.
     */
    public ParseResult parse(byte[] rawPlanBytes, String expectedSha256) {
        Objects.requireNonNull(rawPlanBytes, "rawPlanBytes must not be null");
        Objects.requireNonNull(expectedSha256, "expectedSha256 must not be null");

        if (!PackagingPlanBinding.isValidSha256Fingerprint(expectedSha256)) {
            return ParseResult.rejection("AK-PLAN-INVALID-FINGERPRINT",
                    Map.of("detail", "invalid expectedSha256 format"));
        }

        String computedSha256 = computeSha256(rawPlanBytes);

        if (!expectedSha256.equals(computedSha256)) {
            return ParseResult.rejection("AK-PLAN-HASH-MISMATCH",
                    Map.of("expected", expectedSha256, "actual", computedSha256));
        }

        JsonNode root;
        try {
            root = strictMapper.readTree(rawPlanBytes);
        } catch (IOException e) {
            return ParseResult.rejection("AK-PLAN-INVALID-JSON",
                    Map.of("detail", "malformed JSON"));
        }

        if (root == null || !root.isObject()) {
            return ParseResult.rejection("AK-PLAN-INVALID-JSON",
                    Map.of("detail", "root must be a JSON object"));
        }

        JsonNode schemaVersionNode = root.get("schemaVersion");
        if (schemaVersionNode == null || !schemaVersionNode.isTextual()) {
            return ParseResult.rejection("AK-PLAN-SCHEMA-MISMATCH",
                    Map.of("detail", "schemaVersion missing or not string"));
        }
        String schemaVersion = schemaVersionNode.asText();
        if (!PackagedPlan.REQUIRED_SCHEMA_VERSION.equals(schemaVersion)) {
            return ParseResult.rejection("AK-PLAN-SCHEMA-MISMATCH",
                    Map.of("expected", PackagedPlan.REQUIRED_SCHEMA_VERSION, "actual", schemaVersion));
        }

        // Validate exactArchiveEntries: exactly 28 unique strings
        List<String> archiveEntries;
        {
            JsonNode entriesNode = root.get("exactArchiveEntries");
            if (entriesNode == null) {
                return ParseResult.rejection("AK-PLAN-INVALID-ENTRIES",
                        Map.of("detail", "exactArchiveEntries missing"));
            }
            if (!entriesNode.isArray()) {
                return ParseResult.rejection("AK-PLAN-INVALID-ENTRIES",
                        Map.of("detail", "exactArchiveEntries must be an array"));
            }
            List<String> entriesList = new ArrayList<>();
            Set<String> seenEntries = new HashSet<>();
            int idx = 0;
            for (JsonNode n : entriesNode) {
                if (!n.isTextual()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-ENTRIES",
                            Map.of("detail", "exactArchiveEntries[" + idx + "] must be string"));
                }
                String entry = n.asText();
                if (entry == null || entry.isEmpty()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-ENTRIES",
                            Map.of("detail", "exactArchiveEntries[" + idx + "] must be non-empty string"));
                }
                entriesList.add(entry);
                if (!seenEntries.add(entry)) {
                    return ParseResult.rejection("AK-PLAN-INVALID-ENTRIES",
                            Map.of("detail", "exactArchiveEntries contains duplicate"));
                }
                idx++;
            }
            if (entriesList.size() != PackagedPlan.REQUIRED_ARCHIVE_ENTRY_COUNT) {
                return ParseResult.rejection("AK-PLAN-INVALID-ENTRIES",
                        Map.of("expected", PackagedPlan.REQUIRED_ARCHIVE_ENTRY_COUNT,
                                "actual", entriesList.size()));
            }
            archiveEntries = List.copyOf(entriesList);
        }

        // Validate embeddedDependencies: exactly 7 with valid fingerprints
        List<PackagedPlan.Dependency> dependencies;
        {
            JsonNode depsNode = root.get("embeddedDependencies");
            if (depsNode == null) {
                return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                        Map.of("detail", "embeddedDependencies missing"));
            }
            if (!depsNode.isArray()) {
                return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                        Map.of("detail", "embeddedDependencies must be an array"));
            }
            List<PackagedPlan.Dependency> depsList = new ArrayList<>();
            int idx = 0;
            for (JsonNode n : depsNode) {
                if (!n.isObject()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                            Map.of("detail", "embeddedDependencies[" + idx + "] must be object"));
                }
                JsonNode lockIdNode = n.get("lockId");
                JsonNode entryPathNode = n.get("entryPath");
                JsonNode fingerprintNode = n.get("rawFingerprint");

                if (lockIdNode == null || !lockIdNode.isTextual()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                            Map.of("detail", "embeddedDependencies[" + idx + "].lockId missing or not string"));
                }
                if (entryPathNode == null || !entryPathNode.isTextual()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                            Map.of("detail", "embeddedDependencies[" + idx + "].entryPath missing or not string"));
                }
                if (fingerprintNode == null || !fingerprintNode.isTextual()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                            Map.of("detail", "embeddedDependencies[" + idx + "].rawFingerprint missing or not string"));
                }

                String lockId = lockIdNode.asText();
                String entryPath = entryPathNode.asText();
                String fingerprint = fingerprintNode.asText();

                if (!PackagingPlanBinding.isValidSha256Fingerprint(fingerprint)) {
                    return ParseResult.rejection("AK-PLAN-INVALID-FINGERPRINT",
                            Map.of("detail", "embeddedDependencies[" + idx + "].rawFingerprint invalid format"));
                }

                depsList.add(new PackagedPlan.Dependency(lockId, entryPath, fingerprint));
                idx++;
            }
            if (depsList.size() != PackagedPlan.REQUIRED_DEPENDENCY_COUNT) {
                return ParseResult.rejection("AK-PLAN-INVALID-DEPS",
                        Map.of("expected", PackagedPlan.REQUIRED_DEPENDENCY_COUNT,
                                "actual", depsList.size()));
            }
            dependencies = List.copyOf(depsList);
        }

        // Validate artifactLimits with required exact keys and long values
        PackagedPlan.ArtifactLimitValues limits;
        {
            JsonNode limitsNode = root.get("artifactLimits");
            if (limitsNode == null) {
                return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                        Map.of("detail", "artifactLimits missing"));
            }
            if (!limitsNode.isObject()) {
                return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                        Map.of("detail", "artifactLimits must be an object"));
            }

            Iterator<Map.Entry<String, JsonNode>> fields = limitsNode.fields();
            Set<String> foundKeys = new HashSet<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();

                if (!value.isNumber()) {
                    return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                            Map.of("detail", "artifactLimits." + key + " must be a number"));
                }
                BigDecimal bd = value.decimalValue();
                if (bd == null || bd.compareTo(bd) != 0) {
                    return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                            Map.of("detail", "artifactLimits." + key + " must be finite"));
                }

                foundKeys.add(key);
            }

            for (String requiredKey : PackagedPlan.REQUIRED_LIMIT_KEYS) {
                if (!foundKeys.contains(requiredKey)) {
                    return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                            Map.of("detail", "artifactLimits missing required key: " + requiredKey));
                }
            }

            if (foundKeys.size() != PackagedPlan.REQUIRED_LIMIT_KEYS.size()) {
                return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                        Map.of("detail", "artifactLimits contains unexpected keys"));
            }

            try {
                limits = new PackagedPlan.ArtifactLimitValues(
                        limitsNode.get(PackagedPlan.KEY_MAX_RAW_BYTES).asLong(),
                        limitsNode.get(PackagedPlan.KEY_MAX_ZIP_ENTRIES).asLong(),
                        limitsNode.get(PackagedPlan.KEY_MAX_SINGLE_ENTRY_BYTES).asLong(),
                        limitsNode.get(PackagedPlan.KEY_MAX_TOTAL_UNCOMPRESSED_BYTES).asLong(),
                        limitsNode.get(PackagedPlan.KEY_MAX_COMPRESSION_RATIO).asLong()
                );
            } catch (Exception e) {
                return ParseResult.rejection("AK-PLAN-INVALID-LIMITS",
                        Map.of("detail", "artifactLimits values must be valid long integers"));
            }
        }

        PackagedPlan plan;
        try {
            plan = new PackagedPlan(schemaVersion, archiveEntries, dependencies,
                    limits, expectedSha256, computedSha256, rawPlanBytes);
        } catch (IllegalArgumentException e) {
            return ParseResult.rejection("AK-PLAN-INVALID-JSON",
                    Map.of("detail", "plan construction failed"));
        }

        return ParseResult.success(plan);
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return "sha256:" + hex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
