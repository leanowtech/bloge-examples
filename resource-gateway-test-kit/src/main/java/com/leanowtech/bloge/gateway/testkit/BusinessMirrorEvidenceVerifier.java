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

/** Offline semantic verifier for Package evidence indexes, owner tasks, and domain Portfolios. */
public final class BusinessMirrorEvidenceVerifier {
    private static final String TOO_LARGE =
            "RG.BUSINESS_MIRROR.CLIENT.EVIDENCE_VALUE_TOO_LARGE";
    private static final String CANONICALIZATION_FAILED =
            "RG.BUSINESS_MIRROR.CLIENT.EVIDENCE_CANONICALIZATION_FAILED";
    private static final List<String> LAYERS = List.of(
            "L0_RESOURCE", "L1_SERVICE_DESIGN", "L2_SERVICE_CARRIER",
            "L3_APPLICATION", "CALIBRATION");
    private static final List<String> DIMENSIONS = List.of(
            "BEHAVIOR", "CONTRACT", "EFFECT", "ERROR_DISTRIBUTION", "OUTCOME",
            "REQUEST_SPACE", "STATE_TRANSITION");

    private BusinessMirrorEvidenceVerifier() {
    }

    /**
     * Verifies strict shape, content address, five-layer separation, exact lineage, Fidelity vector,
     * drift signals, and time ordering without linking the Resource Gateway server.
     *
     * @param index decoded Package evidence index
     * @return payload-free verified summary
     */
    public static VerifiedEvidenceIndex verifyIndex(JsonNode index) {
        BusinessMirrorSchemaValidator.require(index,
                BusinessMirrorProtocol.PACKAGE_EVIDENCE_INDEX_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_INDEX_INVALID");
        verifyFingerprint(index, "indexFingerprint",
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_INDEX_FINGERPRINT_MISMATCH");
        Instant projectedAt = instant(index.path("projectedAt").asText());
        Instant validUntil = instant(index.path("validUntil").asText());
        if (validUntil.isBefore(projectedAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_TIME_INVALID");
        }
        long compilationRevision = index.path("compilationRevision").asLong();
        if (!index.path("packageId").asText()
                .equals(index.path("packageSnapshotSource").path("id").asText())
                || !("revision:" + compilationRevision).equals(
                index.path("packageSnapshotSource").path("coordinate").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_COORDINATE_INVALID");
        }
        int conclusionCount = verifyLayers(index.path("layers"));
        verifyFidelity(index.path("fidelity"));
        requireSortedUnique(index.path("driftSignals"), "signalId",
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_SIGNAL_ORDER_INVALID");
        for (JsonNode signal : index.path("driftSignals")) {
            verifyLineage(signal.path("sourceLineage"));
            if (instant(signal.path("dueAt").asText())
                    .isBefore(instant(signal.path("detectedAt").asText()))) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_SIGNAL_TIME_INVALID");
            }
        }
        rejectAggregateScores(index);
        return new VerifiedEvidenceIndex(index.path("packageId").asText(),
                compilationRevision, index.path("projectionRevision").asLong(),
                conclusionCount, index.path("driftSignals").size(),
                index.path("fidelity").path("state").asText(),
                index.path("fidelity").path("dimensions").size(),
                index.path("indexFingerprint").asText());
    }

    /**
     * Verifies one versioned owner task including exact Package coordinate and lifecycle material.
     *
     * @param task decoded owner task
     * @return payload-free verified summary
     */
    public static VerifiedOwnerTask verifyTask(JsonNode task) {
        BusinessMirrorSchemaValidator.require(task,
                BusinessMirrorProtocol.EVIDENCE_OWNER_TASK_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.EVIDENCE_OWNER_TASK_INVALID");
        verifyFingerprint(task, "taskFingerprint",
                "RG.BUSINESS_MIRROR.CLIENT.EVIDENCE_OWNER_TASK_FINGERPRINT_MISMATCH");
        verifyLineage(task.path("sourceLineage"));
        Instant detected = instant(task.path("detectedAt").asText());
        if (instant(task.path("dueAt").asText()).isBefore(detected)
                || instant(task.path("updatedAt").asText()).isBefore(detected)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.EVIDENCE_OWNER_TASK_TIME_INVALID");
        }
        requireEvidenceDeepLink(task.path("deepLink").asText(),
                task.path("packageId").asText(), task.path("compilationRevision").asLong());
        return new VerifiedOwnerTask(task.path("taskId").asText(),
                task.path("version").asLong(), task.path("packageId").asText(),
                task.path("compilationRevision").asLong(),
                task.path("projectionRevision").asLong(), task.path("status").asText(),
                task.path("reason").asText(), task.path("taskFingerprint").asText());
    }

    /**
     * Verifies bounded Package ordering, layer arithmetic, Fidelity vectors, active task closure,
     * pagination, exact Deep Links, and canonical Portfolio content address.
     *
     * @param portfolio decoded domain Portfolio
     * @return payload-free verified summary
     */
    public static VerifiedDomainPortfolio verifyPortfolio(JsonNode portfolio) {
        BusinessMirrorSchemaValidator.require(portfolio,
                BusinessMirrorProtocol.DOMAIN_EVIDENCE_PORTFOLIO_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_PORTFOLIO_INVALID");
        verifyFingerprint(portfolio, "portfolioFingerprint",
                "RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_PORTFOLIO_FINGERPRINT_MISMATCH");
        String scope = canonical(portfolio.path("scope"));
        String domainId = portfolio.path("domainId").asText();
        String previousPackage = null;
        int taskCount = 0;
        for (JsonNode item : portfolio.path("packages")) {
            String packageId = item.path("packageId").asText();
            if (previousPackage != null && previousPackage.compareTo(packageId) >= 0) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_PACKAGE_ORDER_INVALID");
            }
            requireEvidenceDeepLink(item.path("deepLink").asText(), packageId,
                    item.path("compilationRevision").asLong());
            int layerCount = 0;
            for (int i = 0; i < LAYERS.size(); i++) {
                JsonNode layer = item.path("layers").get(i);
                if (!LAYERS.get(i).equals(layer.path("layer").asText())) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_LAYER_INVALID");
                }
                int expected = layer.path("conclusionCount").asInt();
                int states = sum(layer.path("states"), "count");
                int proofs = sum(layer.path("proofComposition"), "count");
                if (expected != states || expected != proofs) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_LAYER_ARITHMETIC_INVALID");
                }
                layerCount += expected;
            }
            verifyFidelity(item.path("fidelity"));
            for (JsonNode task : item.path("ownerTasks")) {
                VerifiedOwnerTask verified = verifyTask(task);
                if (!scope.equals(canonical(task.path("scope")))
                        || !domainId.equals(task.path("domainId").asText())
                        || !packageId.equals(verified.packageId())
                        || item.path("compilationRevision").asLong()
                        != verified.compilationRevision()
                        || item.path("projectionRevision").asLong()
                        != verified.projectionRevision()
                        || !("OPEN".equals(verified.status())
                        || "ACKNOWLEDGED".equals(verified.status()))) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_TASK_CLOSURE_INVALID");
                }
                taskCount++;
            }
            if (layerCount < 1) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_LAYER_INVALID");
            }
            previousPackage = packageId;
        }
        String cursor = portfolio.path("nextCursor").asText();
        if (!cursor.isBlank() && !cursor.equals(previousPackage)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.DOMAIN_EVIDENCE_CURSOR_INVALID");
        }
        instant(portfolio.path("generatedAt").asText());
        rejectAggregateScores(portfolio);
        return new VerifiedDomainPortfolio(domainId, portfolio.path("packages").size(), taskCount,
                cursor, portfolio.path("portfolioFingerprint").asText());
    }

    private static int verifyLayers(JsonNode layers) {
        Set<String> conclusionIds = new HashSet<>();
        int total = 0;
        for (int i = 0; i < LAYERS.size(); i++) {
            JsonNode layer = layers.get(i);
            String expectedLayer = LAYERS.get(i);
            if (!expectedLayer.equals(layer.path("layer").asText())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_LAYER_INVALID");
            }
            String previous = null;
            for (JsonNode conclusion : layer.path("conclusions")) {
                String id = conclusion.path("conclusionId").asText();
                if (!expectedLayer.equals(conclusion.path("layer").asText())
                        || previous != null && previous.compareTo(id) >= 0
                        || !conclusionIds.add(id)) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_CONCLUSION_INVALID");
                }
                verifyLineage(conclusion.path("sourceLineage"));
                if (!contains(conclusion.path("sourceLineage"), conclusion.path("subject"))) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_LINEAGE_INVALID");
                }
                JsonNode observed = conclusion.path("observedAt");
                JsonNode valid = conclusion.path("validUntil");
                if (!observed.isNull() && instant(valid.asText()).isBefore(instant(observed.asText()))) {
                    throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_TIME_INVALID");
                }
                previous = id;
                total++;
            }
        }
        if (total < 1 || total > 16_384) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_CONCLUSION_INVALID");
        }
        return total;
    }

    private static void verifyFidelity(JsonNode fidelity) {
        verifyLineage(fidelity.path("sourceLineage"));
        if (!contains(fidelity.path("sourceLineage"), fidelity.path("inventorySource"))) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_FIDELITY_LINEAGE_INVALID");
        }
        boolean missing = fidelity.path("profileSource").isNull();
        if (missing) {
            if (!fidelity.path("dimensions").isEmpty()
                    || !("MISSING".equals(fidelity.path("state").asText())
                    || "INVENTORY_DRIFT".equals(fidelity.path("state").asText()))) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_FIDELITY_SHAPE_INVALID");
            }
            return;
        }
        if (!contains(fidelity.path("sourceLineage"), fidelity.path("profileSource"))
                || fidelity.path("dimensions").size() != DIMENSIONS.size()) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_FIDELITY_SHAPE_INVALID");
        }
        Instant measured = instant(fidelity.path("measuredAt").asText());
        if (instant(fidelity.path("validUntil").asText()).isBefore(measured)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_FIDELITY_TIME_INVALID");
        }
        for (int i = 0; i < DIMENSIONS.size(); i++) {
            JsonNode dimension = fidelity.path("dimensions").get(i);
            if (!DIMENSIONS.get(i).equals(dimension.path("dimension").asText())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_DIMENSION_ORDER_INVALID");
            }
            verifyLineage(dimension.path("sourceLineage"));
            if (!dimension.path("metric").isNull()
                    && !dimension.path("dimension").asText()
                    .equals(dimension.path("metric").path("dimension").asText())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_DIMENSION_INVALID");
            }
        }
    }

    private static void verifyFingerprint(JsonNode value, String field, String code) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(code);
        }
        ObjectNode material = object.deepCopy();
        material.put(field, "");
        if (!value.path(field).asText().equals(canonical(material))) {
            throw invalid(code);
        }
    }

    private static void verifyLineage(JsonNode lineage) {
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode source : lineage) {
            String coordinate = source.path("kind").asText() + ":"
                    + source.path("id").asText() + ":" + source.path("coordinate").asText()
                    + ":" + source.path("fingerprint").asText();
            if (previous != null && previous.compareTo(coordinate) >= 0 || !seen.add(coordinate)) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_LINEAGE_INVALID");
            }
            previous = coordinate;
        }
    }

    private static boolean contains(JsonNode values, JsonNode expected) {
        String canonicalExpected = canonicalJson(expected);
        for (JsonNode value : values) {
            if (canonicalExpected.equals(canonicalJson(value))) {
                return true;
            }
        }
        return false;
    }

    private static void requireSortedUnique(JsonNode values, String field, String code) {
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode value : values) {
            String current = value.path(field).asText();
            if (previous != null && previous.compareTo(current) >= 0 || !seen.add(current)) {
                throw invalid(code);
            }
            previous = current;
        }
    }

    private static int sum(JsonNode values, String field) {
        int total = 0;
        for (JsonNode value : values) {
            total = Math.addExact(total, value.path(field).asInt());
        }
        return total;
    }

    private static void requireEvidenceDeepLink(String value, String packageId, long revision) {
        try {
            URI uri = URI.create(value);
            Map<String, String> query = query(uri.getRawQuery());
            if (!"/business-mirror/".equals(uri.getPath())
                    || !packageId.equals(query.get("packageId"))
                    || !Long.toString(revision).equals(query.get("compilationRevision"))
                    || !"evidence".equals(query.get("task"))) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_DEEP_LINK_INVALID");
            }
        } catch (RuntimeException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_DEEP_LINK_INVALID");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(name, value) != null) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_DEEP_LINK_INVALID");
            }
        }
        return values;
    }

    private static void rejectAggregateScores(JsonNode value) {
        for (String field : List.of("overallScore", "totalScore", "maturityScore", "overallPass")) {
            if (!value.findValues(field).isEmpty()) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_AGGREGATE_FORBIDDEN");
            }
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_EVIDENCE_TIME_INVALID");
        }
    }

    private static String canonical(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value, TOO_LARGE, CANONICALIZATION_FAILED);
    }

    private static String canonicalJson(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value, TOO_LARGE, CANONICALIZATION_FAILED);
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Payload-free verified Package evidence-index facts.
     *
     * @param packageId verified Package identity
     * @param compilationRevision immutable source compilation revision
     * @param projectionRevision evidence projection revision derived from the compilation
     * @param conclusionCount number of indexed evidence conclusions
     * @param driftSignalCount number of active drift signals
     * @param fidelityState declared fidelity-profile state
     * @param fidelityDimensionCount number of independently reported fidelity dimensions
     * @param fingerprint canonical fingerprint of the verified evidence index
     */
    public record VerifiedEvidenceIndex(
            String packageId,
            long compilationRevision,
            long projectionRevision,
            int conclusionCount,
            int driftSignalCount,
            String fidelityState,
            int fidelityDimensionCount,
            String fingerprint) {
    }

    /**
     * Payload-free verified owner-task facts.
     *
     * @param taskId verified owner-task identity
     * @param version optimistic-concurrency version
     * @param packageId Package whose evidence produced the task
     * @param compilationRevision immutable source compilation revision
     * @param projectionRevision evidence projection revision that produced the task
     * @param status current workflow status
     * @param reason machine-readable reason for creating the task
     * @param fingerprint canonical fingerprint of the verified owner task
     */
    public record VerifiedOwnerTask(
            String taskId,
            long version,
            String packageId,
            long compilationRevision,
            long projectionRevision,
            String status,
            String reason,
            String fingerprint) {
    }

    /**
     * Payload-free verified domain Portfolio facts.
     *
     * @param domainId verified domain identity
     * @param packageCount number of Package summaries in this page
     * @param activeTaskCount number of unresolved owner tasks in this page
     * @param nextCursor opaque cursor for the following page, or {@code null}
     * @param fingerprint canonical fingerprint of the verified Portfolio page
     */
    public record VerifiedDomainPortfolio(
            String domainId,
            int packageCount,
            int activeTaskCount,
            String nextCursor,
            String fingerprint) {
    }
}
