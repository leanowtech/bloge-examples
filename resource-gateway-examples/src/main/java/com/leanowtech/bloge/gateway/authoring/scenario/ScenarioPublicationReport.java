package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Payload-free durable state of one content-addressed Scenario publication saga.
 *
 * <p>The report records source, target, immutable registry references, and machine-readable
 * failure state only. Scenario inputs, dependency responses, and assertion values are deliberately
 * excluded so this lifecycle record can be retained as operational evidence without becoming a
 * second payload store.</p>
 *
 * @param schemaVersion report protocol version
 * @param publicationId deterministic publication identity
 * @param scope complete enterprise ownership boundary
 * @param source exact mutable Scenario source coordinate
 * @param runtimeTarget exact independently discovered runtime target
 * @param status current saga status
 * @param attempt one-based publication attempt
 * @param fixtures independently verified immutable fixture references
 * @param suite independently verified immutable suite reference, null until published
 * @param diagnostics compile-time machine codes
 * @param failure payload-free failure state
 * @param startedAt first attempt start
 * @param updatedAt latest durable transition
 * @param completedAt successful completion time
 * @param actor verified publisher
 */
public record ScenarioPublicationReport(
        String schemaVersion,
        String publicationId,
        ScenarioDraftSet.EnterpriseScope scope,
        SourceRef source,
        TestExecutionApiRequest.Target runtimeTarget,
        Status status,
        int attempt,
        List<AssetRef> fixtures,
        AssetRef suite,
        List<String> diagnostics,
        Failure failure,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        String actor
) {
    /** Current payload-free publication-report protocol. */
    public static final String SCHEMA_VERSION = "bloge.scenarioPublicationReport.v1";

    /** Durable publication saga states. */
    public enum Status {
        IN_PROGRESS,
        PARTIAL,
        FAILED,
        PUBLISHED
    }

    /** Normalizes identifiers, sorts set-like references, and freezes all collections. */
    public ScenarioPublicationReport {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        publicationId = normalized(publicationId);
        scope = scope == null ? ScenarioDraftSet.EnterpriseScope.empty() : scope;
        status = status == null ? Status.IN_PROGRESS : status;
        attempt = Math.max(1, attempt);
        fixtures = sortedAssets(fixtures);
        diagnostics = diagnostics == null
                ? List.of()
                : diagnostics.stream().map(ScenarioPublicationReport::normalized)
                .filter(value -> !value.isBlank()).distinct().sorted().toList();
        failure = failure == null ? Failure.none() : failure;
        actor = normalized(actor);
    }

    /**
     * Exact mutable source and immutable semantic coordinates used by the compiler.
     *
     * @param scenarioDraftSetId source asset id
     * @param revision source revision
     * @param fingerprint stored source fingerprint
     * @param targetKind design target kind; optional only while reading legacy v1 reports
     * @param targetId design target id before runtime lowering; optional only for legacy v1
     * @param targetFingerprint exact visual target fingerprint
     * @param contractFingerprint exact Contract fingerprint
     * @param compilerSchemaVersion governed compiler plan protocol version
     * @param compilationPlanFingerprint canonical complete compilation-plan fingerprint
     */
    public record SourceRef(
            String scenarioDraftSetId,
            long revision,
            String fingerprint,
            String targetKind,
            String targetId,
            String targetFingerprint,
            String contractFingerprint,
            String compilerSchemaVersion,
            String compilationPlanFingerprint
    ) {
        /** Normalizes the source coordinate. */
        public SourceRef {
            scenarioDraftSetId = normalized(scenarioDraftSetId);
            fingerprint = normalized(fingerprint);
            targetKind = normalized(targetKind).toUpperCase(java.util.Locale.ROOT);
            targetId = normalized(targetId);
            targetFingerprint = normalized(targetFingerprint);
            contractFingerprint = normalized(contractFingerprint);
            compilerSchemaVersion = normalized(compilerSchemaVersion);
            compilationPlanFingerprint = normalized(compilationPlanFingerprint);
        }

        /**
         * Backward-compatible constructor for stored v1 reports created before target identity
         * was made explicit.
         */
        public SourceRef(
                String scenarioDraftSetId,
                long revision,
                String fingerprint,
                String targetFingerprint,
                String contractFingerprint,
                String compilerSchemaVersion,
                String compilationPlanFingerprint) {
            this(scenarioDraftSetId, revision, fingerprint, "", "", targetFingerprint,
                    contractFingerprint, compilerSchemaVersion, compilationPlanFingerprint);
        }
    }

    /**
     * Exact registry asset coordinate.
     *
     * @param kind FIXTURE_BUNDLE or TEST_SUITE
     * @param id immutable registry id
     * @param revision immutable revision
     * @param fingerprint canonical content fingerprint
     */
    public record AssetRef(String kind, String id, long revision, String fingerprint) {
        /** Normalizes the immutable registry coordinate. */
        public AssetRef {
            kind = normalized(kind).toUpperCase(java.util.Locale.ROOT);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
        }
    }

    /**
     * Payload-free failure state.
     *
     * @param stage compiler or registry stage
     * @param code stable machine-readable error code
     * @param retryable whether retrying the exact publication can converge
     */
    public record Failure(String stage, String code, boolean retryable) {
        /** Normalizes failure coordinates. */
        public Failure {
            stage = normalized(stage).toUpperCase(java.util.Locale.ROOT);
            code = normalized(code).toUpperCase(java.util.Locale.ROOT);
        }

        /** @return no-failure marker */
        public static Failure none() {
            return new Failure("", "", false);
        }
    }

    /** Returns the next-attempt IN_PROGRESS report while preserving verified references. */
    public ScenarioPublicationReport retry(Instant now, String publisher) {
        return new ScenarioPublicationReport(schemaVersion, publicationId, scope, source,
                runtimeTarget, Status.IN_PROGRESS, attempt + 1, fixtures, null, diagnostics,
                Failure.none(), startedAt == null ? now : startedAt, now, null, publisher);
    }

    /** Returns a report with one newly independently verified fixture reference. */
    public ScenarioPublicationReport verifiedFixture(AssetRef fixture, Instant now) {
        List<AssetRef> updated = new ArrayList<>(fixtures);
        updated.removeIf(existing -> existing.kind().equals(fixture.kind())
                && existing.id().equals(fixture.id())
                && existing.revision() == fixture.revision());
        updated.add(fixture);
        return new ScenarioPublicationReport(schemaVersion, publicationId, scope, source,
                runtimeTarget, Status.IN_PROGRESS, attempt, updated, null, diagnostics,
                Failure.none(), startedAt, now, null, actor);
    }

    /** Returns the final PUBLISHED report after suite verification. */
    public ScenarioPublicationReport published(AssetRef verifiedSuite, Instant now) {
        return new ScenarioPublicationReport(schemaVersion, publicationId, scope, source,
                runtimeTarget, Status.PUBLISHED, attempt, fixtures, verifiedSuite, diagnostics,
                Failure.none(), startedAt, now, now, actor);
    }

    /** Returns a payload-free failed or partial transition. */
    public ScenarioPublicationReport failed(
            String stage, String code, boolean retryable, Instant now) {
        Status failedStatus = fixtures.isEmpty() ? Status.FAILED : Status.PARTIAL;
        return new ScenarioPublicationReport(schemaVersion, publicationId, scope, source,
                runtimeTarget, failedStatus, attempt, fixtures, null, diagnostics,
                new Failure(stage, code, retryable), startedAt, now, null, actor);
    }

    private static List<AssetRef> sortedAssets(List<AssetRef> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().distinct()
                .sorted(Comparator.comparing(AssetRef::kind)
                        .thenComparing(AssetRef::id)
                        .thenComparingLong(AssetRef::revision))
                .toList();
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
