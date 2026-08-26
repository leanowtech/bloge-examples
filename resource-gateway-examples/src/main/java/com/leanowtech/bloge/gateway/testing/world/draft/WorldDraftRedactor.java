package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Schema-guided redaction port. Unknown object fields are dropped by default. */
@FunctionalInterface
public interface WorldDraftRedactor {
    Result redact(WorldDraftSourceAuthority.SourcePayload payload,
                  WorldDraftSourceAuthority.SourceMetadata metadata,
                  WorldDraftRedactionPolicy policy);

    record Result(WorldDraftRedactedPayload payload, WorldDraftRedactionReport report) {
        public Result {
            if (payload == null || report == null) throw invalid();
        }
        private static WorldDraftCandidateException invalid() {
            return new WorldDraftCandidateException(WorldDraftCandidateException.Code.REDACTION_REQUIRED);
        }
    }

    static WorldDraftRedactor schemaGuided() {
        return schemaGuided(WorldDraftDlpScanner.failClosedDefault());
    }

    static WorldDraftRedactor schemaGuided(WorldDraftDlpScanner scanner) {
        return (payload, metadata, policy) -> {
            if (payload == null || metadata == null || policy == null
                    || scanner == null || !policy.fingerprint().equals(metadata.redactionPolicyFingerprint())) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_POLICY_DENIED);
            }
            Accumulator acc = new Accumulator(scanner);
            Object request = walk(payload.request(), metadata.requestSchema().schema(), policy.rules(true), "", "request", acc);
            Object response = walk(payload.response(), metadata.responseSchema().schema(), policy.rules(false), "", "response", acc);
            scanFinal(request, "", "request", metadata.requestSchema().schema(), acc);
            scanFinal(response, "", "response", metadata.responseSchema().schema(), acc);
            boolean valid = valid(metadata.requestSchema(), request) && valid(metadata.responseSchema(), response);
            if (!valid) acc.findings.add(WorldDraftRedactionReport.Finding.SCHEMA_INVALID);
            WorldDraftRedactionReport report = WorldDraftRedactionReport.of(valid, acc.unknown, acc.transformed, acc.findings);
            return new Result(new WorldDraftRedactedPayload(request, response), report);
        };
    }

    com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    Pattern SENSITIVE = Pattern.compile("(?i).*(password|secret|token|authorization|credential|cookie|email|phone|address|latitude|longitude|location|user|customer|name|comment|description|message).*" );

    private static Object walk(Object value, Map<String, Object> schema,
                               List<WorldDraftRedactionPolicy.FieldRule> rawRules,
                               String path, String scope, Accumulator acc) {
        Map<String, WorldDraftRedactionPolicy.FieldRule> rules = new LinkedHashMap<>();
        for (WorldDraftRedactionPolicy.FieldRule rule : rawRules) rules.put(rule.path(), rule);
        return walk(value, schema, rules, path, scope, acc);
    }

    private static Object walk(Object value, Map<String, Object> schema,
                               Map<String, WorldDraftRedactionPolicy.FieldRule> rules,
                               String path, String scope, Accumulator acc) {
        WorldDraftRedactionPolicy.FieldRule rule = rules.get(path.isEmpty() ? "/" : path);
        if (rule != null && rule.action() != WorldDraftRedactionPolicy.Action.KEEP) {
            acc.transformed++;
            return switch (rule.action()) {
                case DROP -> Missing.VALUE;
                case FIXED_REPLACEMENT -> rule.replacement();
                case FORMAT_PRESERVING_TOKEN -> {
                    acc.tokenPaths.add(scope + path);
                    yield token(schema, value);
                }
                case KEEP -> value;
            };
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> properties = objectMap(schema.get("properties"));
            Map<String, Object> output = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !properties.containsKey(key)) {
                    acc.unknown++;
                    continue;
                }
                Object child = walk(entry.getValue(), objectMap(properties.get(key)), rules,
                        path + "/" + key.replace("~", "~0").replace("/", "~1"), scope, acc);
                if (child != Missing.VALUE) output.put(key, child);
            }
            return output;
        }
        if (value instanceof List<?> list) {
            List<Object> output = new ArrayList<>();
            Map<String, Object> items = objectMap(schema.get("items"));
            for (int i = 0; i < list.size(); i++) {
                Object child = walk(list.get(i), items, rules, path + "/" + i, scope, acc);
                if (child != Missing.VALUE) output.add(child);
            }
            return output;
        }
        return value;
    }

    /** Scans the post-transform tree, so a replacement cannot reintroduce a secret. */
    private static void scanFinal(Object value, String path, String scope,
                                  Map<String, Object> schema, Accumulator acc) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> properties = objectMap(schema.get("properties"));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String child = path + "/" + entry.getKey();
                if (!acc.tokenPaths.contains(scope + child) && SENSITIVE.matcher(child).matches()) {
                    acc.findings.add(finding(child));
                }
                scanFinal(entry.getValue(), child, scope, objectMap(properties.get(entry.getKey())), acc);
            }
            return;
        }
        if (value instanceof Iterable<?> values) {
            int index = 0;
            Map<String, Object> items = objectMap(schema.get("items"));
            for (Object item : values) scanFinal(item, path + "/" + index++, scope, items, acc);
            return;
        }
        if (!acc.tokenPaths.contains(scope + path)) {
            acc.findings.addAll(acc.scanner.scan(value, path, schema));
        }
    }

    private static WorldDraftRedactionReport.Finding finding(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(password|secret|token|authorization|credential|cookie).*")) return WorldDraftRedactionReport.Finding.CREDENTIAL;
        if (lower.matches(".*(email|phone|address).*")) return WorldDraftRedactionReport.Finding.CONTACT;
        if (lower.matches(".*(latitude|longitude|location).*")) return WorldDraftRedactionReport.Finding.GEOLOCATION;
        if (lower.matches(".*(comment|description|message).*")) return WorldDraftRedactionReport.Finding.FREE_TEXT;
        return WorldDraftRedactionReport.Finding.IDENTITY;
    }

    private static Object token(Map<String, Object> schema, Object original) {
        String digest = ProtocolFingerprint.of(MAPPER, original).substring(7, 23);
        String format = String.valueOf(schema.getOrDefault("format", ""));
        if ("email".equals(format)) return "redacted-" + digest + "@example.invalid";
        if ("phone".equals(format) || "tel".equals(format)) return phoneToken(original, digest);
        return switch (String.valueOf(schema.getOrDefault("type", "string"))) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            case "array" -> List.of();
            case "object" -> Map.of();
            default -> "redacted-" + digest;
        };
    }

    private static String phoneToken(Object original, String digest) {
        String source = String.valueOf(original);
        StringBuilder result = new StringBuilder(source.length());
        int digit = 0;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (Character.isDigit(current)) {
                result.append((char) ('0' + Character.digit(digest.charAt(digit++ % digest.length()), 16) % 10));
            } else {
                result.append(current);
            }
        }
        return result.isEmpty() ? "00000000" : result.toString();
    }
    private static boolean valid(SchemaEnvelope schema, Object value) {
        return VisualSchemaValidator.validateValue(schema, value, "/redacted").isEmpty();
    }
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> { if (key instanceof String text) copy.put(text, item); });
        return copy;
    }
    enum Missing { VALUE }
    final class Accumulator {
        final WorldDraftDlpScanner scanner;
        int unknown;
        int transformed;
        List<WorldDraftRedactionReport.Finding> findings = new ArrayList<>();
        Set<String> tokenPaths = new HashSet<>();

        Accumulator(WorldDraftDlpScanner scanner) { this.scanner = scanner; }
    }

}
