package com.leanowtech.bloge.gateway.visual.authoring.parse;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.inspector.UnTrustedTagInspector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict, bounded JSON/YAML decoder for untrusted visual-library authoring source.
 */
public final class AuthoringDocumentDecoder {

    public static final int MAXIMUM_DEPTH = 64;
    public static final int MAXIMUM_TOKENS = 250_000;
    public static final int MAXIMUM_ALIASES = 20;
    public static final int MAXIMUM_NAME_LENGTH = 1_024;
    public static final int MAXIMUM_STRING_LENGTH = 1024 * 1024;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final Yaml safeYaml;

    public AuthoringDocumentDecoder() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(AuthoringCompiler.MAX_AUTHORING_BYTES)
                .maxNestingDepth(MAXIMUM_DEPTH)
                .maxTokenCount(MAXIMUM_TOKENS)
                .maxNameLength(MAXIMUM_NAME_LENGTH)
                .maxStringLength(MAXIMUM_STRING_LENGTH)
                .build();
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();

        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setAllowRecursiveKeys(false);
        loaderOptions.setMaxAliasesForCollections(MAXIMUM_ALIASES);
        loaderOptions.setNestingDepthLimit(MAXIMUM_DEPTH);
        loaderOptions.setCodePointLimit(AuthoringCompiler.MAX_AUTHORING_BYTES);
        loaderOptions.setMergeOnCompose(false);
        loaderOptions.setTagInspector(new UnTrustedTagInspector());
        safeYaml = new Yaml(new SafeConstructor(loaderOptions));
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();

        jsonMapper = strictMapper(new ObjectMapper(jsonFactory));
        yamlMapper = strictMapper(new ObjectMapper(yamlFactory));
    }

    public DecodeResult decode(byte[] source) {
        if (source == null || source.length == 0 || blank(source)) {
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.PARSE_FAILED",
                    "Authoring document source is required.",
                    400,
                    -1,
                    -1
            ));
        }
        if (source.length > AuthoringCompiler.MAX_AUTHORING_BYTES) {
            return DecodeResult.failed(limitFailure(
                    "Authoring document exceeds the %d byte limit."
                            .formatted(AuthoringCompiler.MAX_AUTHORING_BYTES),
                    null));
        }
        try {
            boolean json = looksLikeJsonObject(source);
            if (!json) {
                Object parsed = safeYaml.load(new String(source, StandardCharsets.UTF_8));
                if (containsRecursiveAlias(parsed,
                        Collections.newSetFromMap(new IdentityHashMap<>()))) {
                    return DecodeResult.failed(new DecodeFailure(
                            "RG.AUTHORING.PARSE_FAILED",
                            "Recursive YAML aliases are unsupported.",
                            400,
                            -1,
                            -1
                    ));
                }
            }
            ObjectMapper mapper = json ? jsonMapper : yamlMapper;
            VisualLibraryAuthoringDocument document =
                    mapper.readValue(source, VisualLibraryAuthoringDocument.class);
            return DecodeResult.decoded(document);
        } catch (JsonProcessingException exception) {
            JsonLocation location = exception.getLocation();
            if (resourceLimitFailure(exception)) {
                return DecodeResult.failed(limitFailure(
                        safeReason(exception),
                        location));
            }
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.PARSE_FAILED",
                    "Authoring document could not be parsed: " + safeReason(exception),
                    400,
                    line(location),
                    column(location)
            ));
        } catch (YAMLException exception) {
            if (resourceLimitFailure(exception.getMessage())) {
                return DecodeResult.failed(limitFailure(
                        safeReason(exception.getMessage()),
                        null));
            }
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.PARSE_FAILED",
                    "Authoring document could not be parsed: " + safeReason(exception.getMessage()),
                    400,
                    -1,
                    -1
            ));
        } catch (IOException exception) {
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.PARSE_FAILED",
                    "Authoring document could not be read.",
                    400,
                    -1,
                    -1
            ));
        } catch (RuntimeException exception) {
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.PARSE_FAILED",
                    "Authoring document could not be parsed.",
                    400,
                    -1,
                    -1
            ));
        }
    }

    private static ObjectMapper strictMapper(ObjectMapper mapper) {
        return mapper.findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static boolean looksLikeJsonObject(byte[] source) {
        String value = new String(source, StandardCharsets.UTF_8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\ufeff' || Character.isWhitespace(current)) {
                continue;
            }
            return current == '{';
        }
        return false;
    }

    private static boolean blank(byte[] source) {
        String value = new String(source, StandardCharsets.UTF_8);
        return value.codePoints().allMatch(codePoint ->
                codePoint == 0xfeff || Character.isWhitespace(codePoint));
    }

    private static boolean resourceLimitFailure(JsonProcessingException exception) {
        if (exception instanceof StreamConstraintsException) {
            return true;
        }
        return resourceLimitFailure(exception.getOriginalMessage());
    }

    private static boolean resourceLimitFailure(String value) {
        String message = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return message.contains("aliases")
                || message.contains("nesting depth")
                || message.contains("code point limit")
                || message.contains("token count")
                || message.contains("maximum allowed");
    }

    private static boolean containsRecursiveAlias(Object value, Set<Object> active) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            return false;
        }
        if (!active.add(value)) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsRecursiveAlias(entry.getKey(), active)
                        || containsRecursiveAlias(entry.getValue(), active)) {
                    return true;
                }
            }
        } else {
            for (Object child : (List<?>) value) {
                if (containsRecursiveAlias(child, active)) {
                    return true;
                }
            }
        }
        active.remove(value);
        return false;
    }

    private static DecodeFailure limitFailure(String reason, JsonLocation location) {
        return new DecodeFailure(
                "RG.AUTHORING.DOCUMENT_LIMIT_EXCEEDED",
                reason == null || reason.isBlank()
                        ? "Authoring document exceeds a parser resource limit."
                        : reason,
                413,
                line(location),
                column(location)
        );
    }

    private static String safeReason(JsonProcessingException exception) {
        return safeReason(exception.getOriginalMessage());
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "invalid JSON or YAML";
        }
        String singleLine = value.lines().findFirst().orElse("invalid JSON or YAML").trim();
        return singleLine.length() <= 500
                ? singleLine
                : singleLine.substring(0, 500) + "...";
    }

    private static int line(JsonLocation location) {
        return location == null ? -1 : Math.max(-1, location.getLineNr());
    }

    private static int column(JsonLocation location) {
        return location == null ? -1 : Math.max(-1, location.getColumnNr());
    }

    public record DecodeFailure(
            String code,
            String message,
            int status,
            int line,
            int column
    ) {
    }

    public record DecodeResult(
            VisualLibraryAuthoringDocument document,
            DecodeFailure failure
    ) {
        public static DecodeResult decoded(VisualLibraryAuthoringDocument document) {
            return new DecodeResult(document, null);
        }

        public static DecodeResult failed(DecodeFailure failure) {
            return new DecodeResult(null, failure);
        }

        public boolean successful() {
            return document != null && failure == null;
        }
    }
}
