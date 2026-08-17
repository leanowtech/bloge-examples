package com.leanowtech.bloge.gateway.visual.authoring.link;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Untrusted input for exact Author navigation; scope is supplied by the authenticated identity. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AuthoringLinkResolveRequest(
        String schemaVersion,
        ExactSubjectRef subjectRef,
        String intent,
        ReturnCoordinate returnCoordinate
) {
    public static final String SCHEMA_VERSION = "bloge.authoringLinkResolveRequest.v1";
    public static final String EDIT_TOPOLOGY = "EDIT_TOPOLOGY";

    public AuthoringLinkResolveRequest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported AuthoringLinkResolveRequest schemaVersion");
        }
        subjectRef = Objects.requireNonNull(subjectRef, "subjectRef");
        intent = required(intent, "intent").toUpperCase(Locale.ROOT);
        if (!EDIT_TOPOLOGY.equals(intent)) {
            throw new IllegalArgumentException("Only EDIT_TOPOLOGY is supported");
        }
        returnCoordinate = Objects.requireNonNull(returnCoordinate, "returnCoordinate");
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown Authoring link request field: " + name);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExactSubjectRef(
            String kind,
            String id,
            long revision,
            String fingerprint
    ) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        public ExactSubjectRef {
            kind = required(kind, "subjectRef.kind").toUpperCase(Locale.ROOT);
            id = required(id, "subjectRef.id");
            if (revision < 1) {
                throw new IllegalArgumentException("subjectRef.revision must be positive");
            }
            fingerprint = required(fingerprint, "subjectRef.fingerprint");
            if (!FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException(
                        "subjectRef.fingerprint must be a canonical SHA-256 value");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReturnCoordinate(
            String route,
            String packageId,
            String task,
            String anchor
    ) {
        private static final Set<String> ROUTES = Set.of("business-mirror");
        private static final Set<String> TASKS = Set.of(
                "capabilities", "definition", "evidence", "rehearsal");
        private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,160}");

        public ReturnCoordinate {
            route = required(route, "returnCoordinate.route").toLowerCase(Locale.ROOT);
            if (!ROUTES.contains(route)) {
                throw new IllegalArgumentException("returnCoordinate.route is not allowlisted");
            }
            packageId = required(packageId, "returnCoordinate.packageId");
            if (!SAFE_TOKEN.matcher(packageId).matches()) {
                throw new IllegalArgumentException("returnCoordinate.packageId is invalid");
            }
            task = required(task, "returnCoordinate.task").toLowerCase(Locale.ROOT);
            if (!TASKS.contains(task)) {
                throw new IllegalArgumentException("returnCoordinate.task is not allowlisted");
            }
            anchor = required(anchor, "returnCoordinate.anchor");
            if (!anchor.startsWith("graph:") || !SAFE_TOKEN.matcher(anchor.substring("graph:".length())).matches()) {
                throw new IllegalArgumentException("returnCoordinate.anchor is not allowlisted");
            }
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown return coordinate field: " + name);
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
