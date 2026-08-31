package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Payload-free evidence returned by one explicit governed Connection check. */
public record ApiConnectionCheckResult(
        String schemaVersion,
        String connectionId,
        int revision,
        String kind,
        Status status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant checkedAt,
        long durationMs,
        List<Stage> stages,
        Audit audit) {
    public static final String SCHEMA_VERSION = "bloge.connectionCheckResult.v1";
    private static final Set<String> KINDS = Set.of("NETWORK_ONLY", "SAFE_READ");

    /** Creates an immutable, coordinate-closed result. */
    public ApiConnectionCheckResult {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || connectionId == null || connectionId.isBlank()
                || revision < 1 || !KINDS.contains(kind) || status == null || checkedAt == null
                || durationMs < 0 || durationMs > 120_000 || stages == null || stages.isEmpty()
                || stages.size() > 8 || audit == null) {
            throw new IllegalArgumentException("connection check result is invalid");
        }
        stages = List.copyOf(stages);
        Set<String> stageNames = stages.stream().map(Stage::stage)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!"EGRESS_POLICY".equals(stages.getFirst().stage())
                || stageNames.size() != stages.size()
                || status == Status.REACHABLE && stages.stream().anyMatch(stage -> !"PASSED".equals(stage.status()))
                || status == Status.REACHABLE && "NETWORK_ONLY".equals(kind)
                        && !stageNames.equals(Set.of("EGRESS_POLICY", "DNS", "TLS", "CONNECT"))
                || status == Status.REACHABLE && "SAFE_READ".equals(kind)
                        && !stageNames.equals(Set.of("EGRESS_POLICY", "DNS", "TLS", "CONNECT", "SAFE_READ"))
                || status == Status.UNREACHABLE && stages.stream().noneMatch(stage -> "FAILED".equals(stage.status()))
                || status == Status.UNREACHABLE && stages.stream().anyMatch(stage -> "BLOCKED".equals(stage.status()))
                || status == Status.BLOCKED && stages.stream().noneMatch(stage -> "BLOCKED".equals(stage.status()))
                || status == Status.BLOCKED && stages.stream().anyMatch(stage -> "FAILED".equals(stage.status()))) {
            throw new IllegalArgumentException("connection check evidence is inconsistent");
        }
    }

    /** High-level outcome; an unreachable endpoint is evidence, not a transport error. */
    public enum Status { REACHABLE, UNREACHABLE, BLOCKED }

    /** One code-only check stage; no provider text or payload is retained. */
    public record Stage(String stage, String status, String code) {
        private static final Set<String> STAGES = Set.of(
                "EGRESS_POLICY", "DNS", "TLS", "CONNECT", "SAFE_READ");
        private static final Set<String> STATUSES = Set.of("PASSED", "FAILED", "BLOCKED", "NOT_RUN");

        public Stage {
            if (!STAGES.contains(stage) || !STATUSES.contains(status) || code == null || code.isBlank()
                    || code.length() > 128 || !code.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("connection check stage is invalid");
            }
        }
    }

    /** Exact egress decision evidence without policy internals. */
    public record Audit(String decisionId, String policyFingerprint) {
        public Audit {
            Objects.requireNonNull(decisionId, "decisionId");
            if (decisionId.isBlank() || decisionId.length() > 256
                    || !decisionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                    || policyFingerprint == null
                    || !policyFingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("connection check audit is invalid");
            }
        }
    }
}
