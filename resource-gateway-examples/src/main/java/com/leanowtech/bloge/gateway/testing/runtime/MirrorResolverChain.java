package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.SelectorResolver;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes the exact source-first precedence frozen into one mirror control generation.
 *
 * <p>The built-in v1 chain adapts owner FixtureRules and governed replay FixtureRules. Future
 * recorded, stateful, inferred, or synthesized resolvers can implement {@link MirrorResolver}
 * without changing precedence semantics. If the compiled generation names an unavailable source,
 * execution fails closed instead of silently skipping it.</p>
 */
public final class MirrorResolverChain {
    private static final ArtifactProvenance.Confidence EXACT_RULE_CONFIDENCE =
            new ArtifactProvenance.Confidence(1, 1, 1, "EXACT_FIXTURE_RULE_V1");

    private final Map<MirrorPlan.MirrorSource, MirrorResolver> resolvers;

    /**
     * Creates a chain from one resolver per concrete source.
     *
     * @param resolvers available source implementations
     */
    public MirrorResolverChain(List<MirrorResolver> resolvers) {
        EnumMap<MirrorPlan.MirrorSource, MirrorResolver> indexed =
                new EnumMap<>(MirrorPlan.MirrorSource.class);
        if (resolvers != null) {
            for (MirrorResolver resolver : resolvers) {
                MirrorResolver required = Objects.requireNonNull(resolver, "resolver");
                MirrorPlan.MirrorSource source = Objects.requireNonNull(
                        required.source(), "resolver source");
                if (source == MirrorPlan.MirrorSource.ABSTAINED) {
                    throw new IllegalArgumentException("ABSTAINED is owned by the resolver chain");
                }
                if (indexed.putIfAbsent(source, required) != null) {
                    throw new IllegalArgumentException("Duplicate mirror resolver source: " + source);
                }
            }
        }
        this.resolvers = Map.copyOf(indexed);
    }

    /** @return the Stage 1 exact owner-rule and governed-replay resolver chain */
    public static MirrorResolverChain fixtureRules() {
        return new MirrorResolverChain(List.of(
                new ExactRuleResolver(MirrorPlan.MirrorSource.OWNER_SPECIFIED, false),
                new ExactRuleResolver(MirrorPlan.MirrorSource.GOVERNED_REPLAY, true)));
    }

    /**
     * Resolves one invocation using the exact source order frozen by compilation.
     *
     * @param control exact mirror control for this invocation site
     * @param request current invocation facts and matching candidates
     * @return concrete source decision or terminal abstention
     */
    public Decision resolve(
            CompiledExecutionControl.ResolvedControl control,
            MirrorResolver.Request request) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(request, "request");
        if (control.resolutionStrategy()
                != CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                .MIRROR_SOURCE_THEN_SELECTOR) {
            throw new IllegalArgumentException("MirrorResolverChain requires a mirror control");
        }
        if (!control.site().invocationSiteId().equals(request.site().invocationSiteId())) {
            throw new IllegalArgumentException("resolver request site differs from compiled control");
        }
        for (MirrorPlan.MirrorSource source : control.resolverOrder()) {
            if (source == MirrorPlan.MirrorSource.ABSTAINED) {
                return Decision.terminalAbstention();
            }
            MirrorResolver resolver = resolvers.get(source);
            if (resolver == null) {
                throw new TestControlException("MIRROR_RESOLVER_UNAVAILABLE", "MIRROR_RESOLUTION",
                        "Compiled mirror source is unavailable in this runtime generation.");
            }
            Optional<MirrorResolver.Match> match = resolver.resolve(request);
            if (match.isPresent()) {
                return Decision.resolved(source, match.get());
            }
        }
        throw new TestControlException("MIRROR_RESOLVER_ORDER_INVALID", "MIRROR_RESOLUTION",
                "Compiled mirror resolver order did not terminate in ABSTAINED.");
    }

    /**
     * Final cross-source decision.
     *
     * @param source selected source or ABSTAINED
     * @param match source claim; absent only for ABSTAINED
     */
    public record Decision(MirrorPlan.MirrorSource source, MirrorResolver.Match match) {
        /** Enforces an explicit concrete-or-abstained result. */
        public Decision {
            source = Objects.requireNonNull(source, "source");
            if ((source == MirrorPlan.MirrorSource.ABSTAINED) != (match == null)) {
                throw new IllegalArgumentException(
                        "only ABSTAINED may omit a mirror resolver match");
            }
        }

        /** @return whether every admitted source abstained */
        public boolean abstained() {
            return source == MirrorPlan.MirrorSource.ABSTAINED;
        }

        private static Decision resolved(
                MirrorPlan.MirrorSource source, MirrorResolver.Match match) {
            return new Decision(source, Objects.requireNonNull(match, "match"));
        }

        private static Decision terminalAbstention() {
            return new Decision(MirrorPlan.MirrorSource.ABSTAINED, null);
        }
    }

    private record ExactRuleResolver(MirrorPlan.MirrorSource source, boolean replay)
            implements MirrorResolver {
        private ExactRuleResolver {
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Optional<Match> resolve(Request request) {
            List<FixtureRule> candidates = request.matchedRules().stream()
                    .filter(rule -> (rule.behavior().kind() == FixtureRule.BehaviorKind.REPLAY)
                            == replay)
                    .toList();
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            int precedence = SelectorResolver.precedence(candidates.getFirst().selector());
            List<FixtureRule> winning = candidates.stream()
                    .takeWhile(rule -> SelectorResolver.precedence(rule.selector()) == precedence)
                    .toList();
            if (winning.size() > 1) {
                throw new TestControlException(
                        "CONTROL_PLAN_RUNTIME_AMBIGUITY", "MIRROR_RESOLUTION",
                        "More than one rule in one mirror source matched the invocation site.");
            }
            FixtureRule rule = winning.getFirst();
            List<String> limitations = rule.schemaCheck().mode()
                    == FixtureRule.SchemaCheckMode.WAIVED
                    ? List.of("SCHEMA_VALIDATION_WAIVED") : List.of();
            return Optional.of(new Match(rule, EXACT_RULE_CONFIDENCE, 1, limitations));
        }
    }
}
