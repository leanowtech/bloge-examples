package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registry-free semantic verification for immutable Business Mirror compilation facts. */
final class BusinessMirrorCompilationVerifier {
    private static final String TOO_LARGE = "RG.BUSINESS_MIRROR.CLIENT.COMPILATION_FACT_TOO_LARGE";
    private static final String CANONICALIZATION_FAILED =
            "RG.BUSINESS_MIRROR.CLIENT.COMPILATION_CANONICALIZATION_FAILED";

    private BusinessMirrorCompilationVerifier() {
    }

    /** Verifies a content-addressed, acyclic and single-Scope Business Asset Link Closure. */
    static VerifiedLinkClosure verifyBusinessAssetLinkClosure(JsonNode closure) {
        BusinessMirrorSchemaValidator.require(closure,
                BusinessMirrorProtocol.BUSINESS_ASSET_LINK_CLOSURE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_INVALID");
        verifyFingerprint(closure, "RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_FINGERPRINT_MISMATCH");
        String scope = canonical(closure.path("scope"));
        Set<String> assets = new HashSet<>();
        closure.path("assets").forEach(asset -> {
            if (!scope.equals(canonical(asset.path("scope"))) || !assets.add(canonical(asset))) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_SCOPE_INVALID");
            }
        });
        Map<String, List<String>> adjacency = new HashMap<>();
        Set<String> linkCoordinates = new HashSet<>();
        closure.path("links").forEach(link -> {
            String source = canonical(link.path("sourceRef"));
            String target = canonical(link.path("targetRef"));
            if (!assets.contains(source) || !assets.contains(target)
                    || !scope.equals(canonical(link.path("sourceRef").path("scope")))
                    || !scope.equals(canonical(link.path("targetRef").path("scope")))) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_DANGLING");
            }
            String coordinate = source + ":" + link.path("relation").asText() + ":"
                    + target + ":" + link.path("condition").asText();
            if (!linkCoordinates.add(coordinate)) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_LINK_DUPLICATE");
            }
            adjacency.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
        });
        requireAcyclic(assets, adjacency);
        return new VerifiedLinkClosure(closure.path("closureId").asText(),
                closure.path("revision").asLong(), closure.path("fingerprint").asText(),
                closure.path("packageId").asText(), scope, closure.path("assets").size(),
                closure.path("links").size(), instant(closure.path("createdAt").asText()));
    }

    /** Verifies a content-addressed report whose status is derived from finding severities. */
    static VerifiedReadiness verifyPackageReadinessReport(JsonNode report) {
        BusinessMirrorSchemaValidator.require(report,
                BusinessMirrorProtocol.PACKAGE_READINESS_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_READINESS_INVALID");
        verifyFingerprint(report, "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_READINESS_FINGERPRINT_MISMATCH");
        Set<String> findingIds = new HashSet<>();
        boolean error = false;
        boolean warning = false;
        for (JsonNode finding : report.path("findings")) {
            if (!findingIds.add(finding.path("findingId").asText())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_READINESS_FINDING_DUPLICATE");
            }
            error |= "ERROR".equals(finding.path("severity").asText());
            warning |= "WARNING".equals(finding.path("severity").asText());
        }
        String expected = error ? "BLOCKED" : warning ? "REVIEW_REQUIRED" : "READY";
        if (!expected.equals(report.path("status").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_READINESS_STATUS_INVALID");
        }
        return new VerifiedReadiness(report.path("reportId").asText(),
                report.path("revision").asLong(), report.path("fingerprint").asText(),
                report.path("packageId").asText(), report.path("sourceDraftRevision").asLong(),
                report.path("sourceDraftFingerprint").asText(), expected,
                report.path("findings").size(), canonical(report.path("scope")),
                instant(report.path("createdAt").asText()));
    }

    /** Verifies a content-addressed Snapshot and its deterministic immutable dependency manifest. */
    static VerifiedPackageSnapshot verifyPackageSnapshot(JsonNode snapshot) {
        BusinessMirrorSchemaValidator.require(snapshot,
                BusinessMirrorProtocol.PACKAGE_SNAPSHOT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SNAPSHOT_INVALID");
        verifyFingerprint(snapshot, "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SNAPSHOT_FINGERPRINT_MISMATCH");
        if (!snapshot.path("scope").path("tenantId").asText()
                .equals(snapshot.path("provenance").path("tenantId").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SNAPSHOT_SCOPE_INVALID");
        }
        RefCoordinate previous = null;
        Set<RefCoordinate> coordinates = new HashSet<>();
        for (JsonNode ref : snapshot.path("dependencyManifest")) {
            RefCoordinate coordinate = refCoordinate(ref);
            if (!coordinates.add(coordinate) || previous != null && previous.compareTo(coordinate) >= 0
                    || "GRAPH_DRAFT".equals(ref.path("kind").asText())
                    || "CAPABILITY_PROPOSAL".equals(ref.path("kind").asText())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SNAPSHOT_MANIFEST_INVALID");
            }
            previous = coordinate;
        }
        return new VerifiedPackageSnapshot(snapshot.path("packageId").asText(),
                snapshot.path("revision").asLong(), snapshot.path("fingerprint").asText(),
                snapshot.path("sourceDraftRevision").asLong(),
                snapshot.path("sourceDraftFingerprint").asText(),
                snapshot.path("compilerVersion").asText(), canonical(snapshot.path("scope")),
                snapshot.path("dependencyManifest").size(), instant(snapshot.path("createdAt").asText()));
    }

    private static void verifyFingerprint(JsonNode value, String mismatchCode) {
        ObjectNode material = (ObjectNode) value.deepCopy();
        material.put("fingerprint", "");
        String expected = BusinessMirrorCanonical.fingerprint(material, TOO_LARGE, CANONICALIZATION_FAILED);
        if (!expected.equals(value.path("fingerprint").asText())) {
            throw invalid(mismatchCode);
        }
    }

    private static String canonical(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value, TOO_LARGE, CANONICALIZATION_FAILED);
    }

    private static RefCoordinate refCoordinate(JsonNode ref) {
        return new RefCoordinate(ref.path("kind").asText(), ref.path("id").asText(),
                ref.path("revision").asLong(), ref.path("fingerprint").asText());
    }

    private static void requireAcyclic(Set<String> assets, Map<String, List<String>> adjacency) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String root : assets) {
            if (visited.contains(root)) {
                continue;
            }
            Deque<Visit> stack = new ArrayDeque<>();
            stack.push(new Visit(root, false));
            while (!stack.isEmpty()) {
                Visit current = stack.pop();
                if (current.expanded()) {
                    visiting.remove(current.asset());
                    visited.add(current.asset());
                    continue;
                }
                if (visited.contains(current.asset())) {
                    continue;
                }
                if (!visiting.add(current.asset())) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_CYCLE");
                }
                stack.push(new Visit(current.asset(), true));
                for (String child : adjacency.getOrDefault(current.asset(), List.of())) {
                    if (visiting.contains(child)) {
                        throw invalid("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_CYCLE");
                    }
                    if (!visited.contains(child)) {
                        stack.push(new Visit(child, false));
                    }
                }
            }
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.COMPILATION_TIME_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private record Visit(String asset, boolean expanded) {
    }

    private record RefCoordinate(String kind, String id, long revision, String fingerprint)
            implements Comparable<RefCoordinate> {
        @Override
        public int compareTo(RefCoordinate other) {
            int result = kind.compareTo(other.kind);
            if (result == 0) {
                result = id.compareTo(other.id);
            }
            if (result == 0) {
                result = Long.compare(revision, other.revision);
            }
            return result == 0 ? fingerprint.compareTo(other.fingerprint) : result;
        }
    }

    /** Payload-free verified relation-closure identity. */
    record VerifiedLinkClosure(String closureId, long revision, String fingerprint,
                               String packageId, String scopeFingerprint, int assetCount,
                               int linkCount, Instant createdAt) {
    }

    /** Payload-free verified readiness identity. */
    record VerifiedReadiness(String reportId, long revision, String fingerprint,
                             String packageId, long sourceDraftRevision,
                             String sourceDraftFingerprint, String status, int findingCount,
                             String scopeFingerprint, Instant createdAt) {
    }

    /** Payload-free verified Package snapshot identity. */
    record VerifiedPackageSnapshot(String packageId, long revision, String fingerprint,
                                   long sourceDraftRevision, String sourceDraftFingerprint,
                                   String compilerVersion, String scopeFingerprint,
                                   int dependencyCount, Instant createdAt) {
    }
}
