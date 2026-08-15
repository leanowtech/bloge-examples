package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.Comparator;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.exactFingerprint;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.sortedStrings;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.trimmed;

/** Governed Scenario authoring protocol with exact obligations, Oracles, assertions, and data refs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioDraftSetV2(
        String schemaVersion,
        String scenarioDraftSetId,
        long revision,
        EnterpriseScope scope,
        ExactTargetRef target,
        ExactAssetRef contractRef,
        List<ScenarioDraftV2> scenarios,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.scenarioDraftSet.v2";

    public enum CaseType { GOLDEN, NEGATIVE, BOUNDARY, REGRESSION, PROPERTY }
    public enum ScenarioLifecycle { EXPLORATORY, REVIEW_READY, CANONICAL, RETIRED }
    public enum BehaviorKind { REAL, RETURN, ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE, MUST_NOT_CALL }
    public enum BehaviorBoundary { NODE, TRANSPORT }
    public enum ExhaustionPolicy { FAIL, REPEAT_LAST, FALLBACK_TO_REAL }
    public enum UnmatchedPolicy { FAIL, ALLOW_REAL }

    public ScenarioDraftSetV2 {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        scenarioDraftSetId = required(scenarioDraftSetId, "scenarioDraftSetId");
        revision = mutableRevision(revision);
        scope = required(scope, "scope");
        target = required(target, "target");
        contractRef = required(contractRef, "contractRef");
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        if (scenarios.stream().map(ScenarioDraftV2::scenarioId).distinct().count()
                != scenarios.size()) {
            throw new IllegalArgumentException("Scenario ids must be unique");
        }
        metadata = required(metadata, "metadata");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioDraftV2(
            String scenarioId,
            String name,
            String businessIntent,
            String description,
            CaseType caseType,
            RiskLevel risk,
            PrincipalRef owner,
            ScenarioLifecycle lifecycle,
            List<ExactObligationRef> obligationRefs,
            List<ExactAssetRef> oracleRefs,
            List<ExactAssetRef> assertionSetRefs,
            List<ExactAssetRef> sourceRefs,
            GivenV2 given,
            List<ControlledDependencyV2> dependencies,
            ReviewRecord review,
            List<String> tags
    ) {
        public ScenarioDraftV2 {
            scenarioId = required(scenarioId, "scenarioId");
            name = required(name, "name");
            businessIntent = required(businessIntent, "businessIntent");
            description = trimmed(description);
            caseType = caseType == null ? CaseType.GOLDEN : caseType;
            risk = required(risk, "risk");
            owner = required(owner, "owner");
            lifecycle = lifecycle == null ? ScenarioLifecycle.EXPLORATORY : lifecycle;
            obligationRefs = sortObligations(obligationRefs);
            oracleRefs = sortRefs(oracleRefs);
            assertionSetRefs = sortRefs(assertionSetRefs);
            sourceRefs = sortRefs(sourceRefs);
            given = required(given, "given");
            dependencies = dependencies == null ? List.of() : dependencies.stream()
                    .sorted(Comparator.comparing(ControlledDependencyV2::dependencyId))
                    .toList();
            if (dependencies.stream().map(ControlledDependencyV2::dependencyId).distinct().count()
                    != dependencies.size()) {
                throw new IllegalArgumentException("Dependency ids must be unique");
            }
            review = review == null ? ReviewRecord.pending() : review;
            tags = sortedStrings(tags);
            if (lifecycle == ScenarioLifecycle.REVIEW_READY && assertionSetRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "Review-ready Scenario requires an exact Assertion Set");
            }
            if (lifecycle == ScenarioLifecycle.CANONICAL
                    && (obligationRefs.isEmpty() || oracleRefs.isEmpty()
                    || assertionSetRefs.isEmpty() || !review.approved())) {
                throw new IllegalArgumentException(
                        "Canonical Scenario requires exact obligation, Oracle, Assertion Set, and review");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GivenV2(ValueSource input) {
        public GivenV2 {
            input = required(input, "input");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = InlineValue.class, name = "INLINE"),
            @JsonSubTypes.Type(value = FixtureVariantRef.class, name = "FIXTURE_VARIANT"),
            @JsonSubTypes.Type(value = GeneratedValueRef.class, name = "GENERATED"),
            @JsonSubTypes.Type(value = ReplayMaterialRef.class, name = "REPLAY_MATERIAL")
    })
    public sealed interface ValueSource permits InlineValue, FixtureVariantRef,
            GeneratedValueRef, ReplayMaterialRef {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InlineValue(Object value) implements ValueSource {
        public InlineValue {
            value = ProtocolJsonValue.freeze(value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FixtureVariantRef(ExactAssetRef fixtureAssetRef, String variantKey)
            implements ValueSource {
        public FixtureVariantRef {
            fixtureAssetRef = required(fixtureAssetRef, "fixtureAssetRef");
            variantKey = required(variantKey, "variantKey");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratedValueRef(
            ExactAssetRef generatorRef,
            String deterministicSeedFingerprint
    ) implements ValueSource {
        public GeneratedValueRef {
            generatorRef = required(generatorRef, "generatorRef");
            deterministicSeedFingerprint = exactFingerprint(
                    deterministicSeedFingerprint, "deterministicSeedFingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplayMaterialRef(ExactAssetRef replayMaterialRef) implements ValueSource {
        public ReplayMaterialRef {
            replayMaterialRef = required(replayMaterialRef, "replayMaterialRef");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ControlledDependencyV2(
            String dependencyId,
            DependencySelector selector,
            ControlledBehavior behavior,
            Consumption consumption
    ) {
        public ControlledDependencyV2 {
            dependencyId = required(dependencyId, "dependencyId");
            selector = required(selector, "selector");
            behavior = required(behavior, "behavior");
            consumption = consumption == null ? Consumption.once() : consumption;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DependencySelector(
            String graphPath,
            String nodeId,
            String operatorRef,
            String resourceRef,
            String functionRef,
            List<Integer> attempts,
            List<Integer> occurrences,
            String correlationKey,
            List<PathMatch> pathMatches
    ) {
        public DependencySelector {
            graphPath = trimmed(graphPath);
            nodeId = trimmed(nodeId);
            operatorRef = trimmed(operatorRef);
            resourceRef = trimmed(resourceRef);
            functionRef = trimmed(functionRef);
            attempts = positiveNumbers(attempts, "attempts");
            occurrences = positiveNumbers(occurrences, "occurrences");
            correlationKey = trimmed(correlationKey);
            pathMatches = pathMatches == null ? List.of() : pathMatches.stream()
                    .sorted(Comparator.comparing(PathMatch::path))
                    .toList();
            if (List.of(graphPath, nodeId, operatorRef, resourceRef, functionRef).stream()
                    .allMatch(String::isEmpty)) {
                throw new IllegalArgumentException(
                        "Dependency selector requires one structural coordinate");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PathMatch(String path, Object expected) {
        public PathMatch {
            path = required(path, "path");
            expected = ProtocolJsonValue.freeze(expected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ControlledBehavior(
            BehaviorKind kind,
            BehaviorBoundary boundary,
            ValueSource value,
            String errorCode,
            long delayMs
    ) {
        public ControlledBehavior {
            kind = required(kind, "kind");
            boundary = boundary == null ? BehaviorBoundary.NODE : boundary;
            errorCode = trimmed(errorCode);
            if (delayMs < 0) throw new IllegalArgumentException("delayMs must not be negative");
            if ((kind == BehaviorKind.RETURN || kind == BehaviorKind.REPLAY) && value == null) {
                throw new IllegalArgumentException(kind + " behavior requires an exact value source");
            }
            if (kind == BehaviorKind.ERROR && errorCode.isEmpty()) {
                throw new IllegalArgumentException("ERROR behavior requires errorCode");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consumption(
            boolean required,
            int minUses,
            int maxUses,
            ExhaustionPolicy onExhausted,
            UnmatchedPolicy onUnmatched
    ) {
        public Consumption {
            if (minUses < 0 || maxUses < minUses) {
                throw new IllegalArgumentException("Consumption use bounds are invalid");
            }
            onExhausted = onExhausted == null ? ExhaustionPolicy.FAIL : onExhausted;
            onUnmatched = onUnmatched == null ? UnmatchedPolicy.FAIL : onUnmatched;
        }

        public static Consumption once() {
            return new Consumption(true, 1, 1, ExhaustionPolicy.FAIL, UnmatchedPolicy.FAIL);
        }
    }

    private static List<ExactAssetRef> sortRefs(List<ExactAssetRef> values) {
        return values == null ? List.of() : values.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision))
                .toList();
    }

    private static List<ExactObligationRef> sortObligations(List<ExactObligationRef> values) {
        return values == null ? List.of() : values.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactObligationRef::obligationId))
                .toList();
    }

    private static List<Integer> positiveNumbers(List<Integer> values, String field) {
        if (values != null && values.stream().anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException(field + " must contain positive integers");
        }
        List<Integer> normalized = values == null ? List.of() : values.stream()
                .distinct()
                .sorted()
                .toList();
        return normalized;
    }
}
