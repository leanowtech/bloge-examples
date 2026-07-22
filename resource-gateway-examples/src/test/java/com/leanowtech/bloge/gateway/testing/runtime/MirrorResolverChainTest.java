package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorResolverChainTest {
    private static final String REQUEST_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String REPLAY_REF = "bloge-replay:approved@7#sha256:"
            + "b".repeat(64);
    private static final InvocationSite SITE = new InvocationSite(
            InvocationSite.SCHEMA_VERSION, "sha256:" + "c".repeat(64),
            "/root", "subject", "readOnly", "", "",
            "sha256:" + "d".repeat(64), InvocationSite.InvocationKind.PRIMARY,
            null, "", null);

    @Test
    void ownerSourceWinsBeforeMoreSpecificGovernedReplay() {
        FixtureRule owner = rule("owner", FixtureRule.Selector.any(),
                FixtureRule.Behavior.returning(Map.of("source", "owner")));
        FixtureRule replay = rule("replay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.replaying(REPLAY_REF));
        var control = mirrorControl(List.of(owner, replay), List.of(
                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorPlan.MirrorSource.GOVERNED_REPLAY,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision decision = MirrorResolverChain.fixtureRules()
                .resolve(control, request(List.of(owner, replay)));

        assertThat(decision.source()).isEqualTo(MirrorPlan.MirrorSource.OWNER_SPECIFIED);
        assertThat(decision.match().rule().ruleId()).isEqualTo("owner");
        assertThat(decision.match().confidence().method()).isEqualTo("EXACT_FIXTURE_RULE_V1");
    }

    @Test
    void governedReplayIsUsedWhenOwnerSourceAbstains() {
        FixtureRule owner = rule("owner", FixtureRule.Selector.any(),
                FixtureRule.Behavior.returning("owner"));
        FixtureRule replay = rule("replay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.replaying(REPLAY_REF));
        var control = mirrorControl(List.of(owner, replay), List.of(
                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorPlan.MirrorSource.GOVERNED_REPLAY,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision decision = MirrorResolverChain.fixtureRules()
                .resolve(control, request(List.of(replay)));

        assertThat(decision.source()).isEqualTo(MirrorPlan.MirrorSource.GOVERNED_REPLAY);
        assertThat(decision.match().rule().ruleId()).isEqualTo("replay");
    }

    @Test
    void terminalAbstentionIsExplicit() {
        FixtureRule owner = rule("owner", FixtureRule.Selector.any(),
                FixtureRule.Behavior.returning("owner"));
        var control = mirrorControl(List.of(owner), List.of(
                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision decision = MirrorResolverChain.fixtureRules()
                .resolve(control, request(List.of()));

        assertThat(decision.abstained()).isTrue();
        assertThat(decision.match()).isNull();
    }

    @Test
    void unavailableCompiledSourceFailsClosed() {
        var control = mirrorControl(List.of(), List.of(
                MirrorPlan.MirrorSource.RECORDED_EXACT,
                MirrorPlan.MirrorSource.ABSTAINED));

        assertThatThrownBy(() -> MirrorResolverChain.fixtureRules()
                .resolve(control, request(List.of())))
                .isInstanceOfSatisfying(TestControlException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MIRROR_RESOLVER_UNAVAILABLE"));
    }

    @Test
    void ordinaryControlCannotEnterMirrorResolverChain() {
        var control = new CompiledExecutionControl.ResolvedControl(SITE, List.of(), false);

        assertThatThrownBy(() -> MirrorResolverChain.fixtureRules()
                .resolve(control, request(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mirror control");
    }

    @Test
    void sameSourceRuntimeAmbiguityFailsClosed() {
        FixtureRule first = rule("first", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("one"));
        FixtureRule second = rule("second", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("two"));
        var control = mirrorControl(List.of(first, second), List.of(
                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorPlan.MirrorSource.ABSTAINED));

        assertThatThrownBy(() -> MirrorResolverChain.fixtureRules()
                .resolve(control, request(List.of(first, second))))
                .isInstanceOfSatisfying(TestControlException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_RUNTIME_AMBIGUITY"));
    }

    @Test
    void duplicateSourceRegistrationIsRejected() {
        MirrorResolver first = resolver(MirrorPlan.MirrorSource.OWNER_SPECIFIED);
        MirrorResolver second = resolver(MirrorPlan.MirrorSource.OWNER_SPECIFIED);

        assertThatThrownBy(() -> new MirrorResolverChain(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate mirror resolver source");
    }

    private static MirrorResolver.Request request(List<FixtureRule> matched) {
        return new MirrorResolver.Request(SITE, 1, 1, REQUEST_FINGERPRINT,
                Map.of("customerId", "c-1"), matched);
    }

    private static CompiledExecutionControl.ResolvedControl mirrorControl(
            List<FixtureRule> rules,
            List<MirrorPlan.MirrorSource> order) {
        return new CompiledExecutionControl.ResolvedControl(SITE, rules, false,
                CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                        .MIRROR_SOURCE_THEN_SELECTOR,
                order);
    }

    private static FixtureRule rule(
            String id, FixtureRule.Selector selector, FixtureRule.Behavior behavior) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, id, selector, behavior,
                FixtureRule.Consumption.optionalOnce(), FixtureRule.SchemaCheck.strict());
    }

    private static MirrorResolver resolver(MirrorPlan.MirrorSource source) {
        return new MirrorResolver() {
            @Override
            public MirrorPlan.MirrorSource source() {
                return source;
            }

            @Override
            public java.util.Optional<Match> resolve(Request request) {
                return java.util.Optional.empty();
            }
        };
    }
}
