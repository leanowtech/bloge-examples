package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline semantic verifier for content-addressed Business Asset impact reports. */
public final class BusinessMirrorImpactVerifier {
    private static final String TOO_LARGE =
            "RG.BUSINESS_MIRROR.CLIENT.IMPACT_REPORT_TOO_LARGE";
    private static final String CANONICALIZATION_FAILED =
            "RG.BUSINESS_MIRROR.CLIENT.IMPACT_CANONICALIZATION_FAILED";
    private static final List<String> RISKS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private BusinessMirrorImpactVerifier() {
    }

    /**
     * Verifies Schema, fingerprint, Scope, selector, path, pagination, and Deep Link semantics.
     *
     * @param report decoded Business Asset impact report
     * @return payload-free verified summary
     */
    public static VerifiedImpactReport verify(JsonNode report) {
        BusinessMirrorSchemaValidator.require(report,
                BusinessMirrorProtocol.BUSINESS_ASSET_IMPACT_REPORT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_IMPACT_REPORT_INVALID");
        verifyFingerprint(report);
        String scope = canonical(report.path("scope"));
        String selectorKind = report.path("selector").path("kind").asText();
        String selectorId = report.path("selector").path("id").asText();
        String selectorAuthority = report.path("selector").path("authority").asText();
        requireSortedUniqueText(report.path("stalePackageIds"),
                "RG.BUSINESS_MIRROR.CLIENT.IMPACT_STALE_PACKAGES_INVALID");
        String previousPackage = null;
        int sourceCount = 0;
        int pathCount = 0;
        Set<String> packages = new HashSet<>();
        for (JsonNode item : report.path("items")) {
            String packageId = item.path("packageId").asText();
            long revision = item.path("compilationRevision").asLong();
            if (!scope.equals(canonical(item.path("scope")))
                    || previousPackage != null && previousPackage.compareTo(packageId) >= 0
                    || !packages.add(packageId)
                    || !packageId.equals(item.path("packageSnapshotRef").path("id").asText())
                    || revision != item.path("packageSnapshotRef").path("revision").asLong()) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_PACKAGE_COORDINATE_INVALID");
            }
            requireDeepLink(item.path("deepLink").asText(), packageId, revision, null);
            String previousSource = null;
            Set<String> sources = new HashSet<>();
            for (JsonNode match : item.path("matches")) {
                JsonNode source = match.path("sourceRef");
                String sourceCoordinate = coordinate(source);
                if (!scope.equals(canonical(source.path("scope")))
                        || !selectorKind.equals(source.path("kind").asText())
                        || !selectorId.equals(source.path("id").asText())
                        || !selectorAuthority.isBlank()
                        && !selectorAuthority.equals(source.path("authority").asText())
                        || previousSource != null && previousSource.compareTo(sourceCoordinate) >= 0
                        || !sources.add(sourceCoordinate)) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_SOURCE_MATCH_INVALID");
                }
                requireDeepLink(match.path("deepLink").asText(), packageId, revision, source);
                verifyPaths(scope, packageId, revision, source, match.path("paths"));
                sourceCount++;
                pathCount += match.path("paths").size();
                previousSource = sourceCoordinate;
            }
            previousPackage = packageId;
        }
        String cursor = report.path("nextCursor").asText();
        if (!cursor.isBlank() && (previousPackage == null || !cursor.equals(previousPackage))) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_PAGE_CURSOR_INVALID");
        }
        String projected = report.path("projectedThrough").isNull()
                ? "" : instant(report.path("projectedThrough").asText()).toString();
        return new VerifiedImpactReport(selectorKind, selectorId, selectorAuthority,
                report.path("status").asText(), packages.size(), sourceCount, pathCount,
                report.path("stalePackageIds").size(),
                report.path("stalePackageIdsTruncated").asBoolean(), cursor, projected,
                report.path("fingerprint").asText());
    }

    private static void verifyPaths(
            String scope,
            String packageId,
            long revision,
            JsonNode source,
            JsonNode paths) {
        String previousTarget = null;
        Set<String> targets = new HashSet<>();
        for (JsonNode path : paths) {
            JsonNode target = path.path("impactedRef");
            String targetCoordinate = coordinate(target);
            JsonNode links = path.path("representativePath");
            if (!scope.equals(canonical(target.path("scope")))
                    || path.path("depth").asInt() != links.size()
                    || previousTarget != null && previousTarget.compareTo(targetCoordinate) >= 0
                    || !targets.add(targetCoordinate)) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_PATH_INVALID");
            }
            JsonNode expected = source;
            int representativeRisk = 0;
            for (JsonNode link : links) {
                if (!canonical(expected).equals(canonical(link.path("sourceRef")))) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_PATH_DISCONNECTED");
                }
                expected = link.path("targetRef");
                representativeRisk = Math.max(representativeRisk,
                        RISKS.indexOf(link.path("risk").asText()));
            }
            if (!canonical(expected).equals(canonical(target))
                    || RISKS.indexOf(path.path("highestRisk").asText()) < representativeRisk) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_PATH_DISCONNECTED");
            }
            requireDeepLink(path.path("deepLink").asText(), packageId, revision, target);
            previousTarget = targetCoordinate;
        }
    }

    private static void requireDeepLink(
            String value, String packageId, long revision, JsonNode asset) {
        try {
            URI uri = URI.create(value);
            Map<String, String> query = query(uri.getRawQuery());
            boolean exact = "/business-mirror/".equals(uri.getPath())
                    && packageId.equals(query.get("packageId"))
                    && Long.toString(revision).equals(query.get("compilationRevision"))
                    && "capabilities".equals(query.get("task"));
            if (asset != null) {
                exact &= asset.path("kind").asText().equals(query.get("assetKind"))
                        && asset.path("id").asText().equals(query.get("assetId"))
                        && asset.path("revision").asText().equals(query.get("assetRevision"))
                        && asset.path("authority").asText().equals(query.get("assetAuthority"));
            }
            if (!exact) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_DEEP_LINK_INVALID");
            }
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException
                    && "RG.BUSINESS_MIRROR.CLIENT.IMPACT_DEEP_LINK_INVALID"
                    .equals(failure.getMessage())) {
                throw failure;
            }
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_DEEP_LINK_INVALID");
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "",
                    StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_DEEP_LINK_INVALID");
            }
        }
        return values;
    }

    private static void verifyFingerprint(JsonNode report) {
        ObjectNode material = (ObjectNode) report.deepCopy();
        material.put("fingerprint", "");
        String expected = BusinessMirrorCanonical.fingerprint(
                material, TOO_LARGE, CANONICALIZATION_FAILED);
        if (!expected.equals(report.path("fingerprint").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_REPORT_FINGERPRINT_MISMATCH");
        }
    }

    private static void requireSortedUniqueText(JsonNode values, String code) {
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode value : values) {
            String exact = value.asText();
            if (!seen.add(exact) || previous != null && previous.compareTo(exact) >= 0) {
                throw invalid(code);
            }
            previous = exact;
        }
    }

    private static String coordinate(JsonNode asset) {
        return asset.path("layer").asText() + ":" + asset.path("kind").asText() + ":"
                + asset.path("id").asText() + ":" + asset.path("authority").asText() + ":"
                + asset.path("revision").asLong() + ":" + asset.path("fingerprint").asText();
    }

    private static String canonical(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value, TOO_LARGE, CANONICALIZATION_FAILED);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPACT_TIME_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Payload-free result safe for build logs and governance adapters.
     *
     * @param selectorKind normalized logical asset kind
     * @param selectorId normalized logical asset id
     * @param selectorAuthority optional exact asset authority
     * @param status projection freshness status
     * @param packageCount exact packages represented by this page
     * @param sourceMatchCount matching exact source coordinates
     * @param impactPathCount downstream impact paths in this page
     * @param stalePackageCount total stale packages visible to the report
     * @param stalePackageIdsTruncated whether the stale-package inventory was bounded
     * @param nextCursor opaque continuation cursor or an empty string
     * @param projectedThrough latest projection time represented by the report
     * @param fingerprint verified canonical report fingerprint
     */
    public record VerifiedImpactReport(
            String selectorKind,
            String selectorId,
            String selectorAuthority,
            String status,
            int packageCount,
            int sourceMatchCount,
            int impactPathCount,
            int stalePackageCount,
            boolean stalePackageIdsTruncated,
            String nextCursor,
            String projectedThrough,
            String fingerprint
    ) {
    }
}
