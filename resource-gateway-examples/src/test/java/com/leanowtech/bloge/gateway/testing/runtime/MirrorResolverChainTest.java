package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterValidation;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
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
    void recordedExactResolvesFrozenRequestAndOwnerRulesStillTakePrecedence()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MirrorArtifactRef capability = ref("CAPABILITY", "operator:subject", '1');
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "subject-corpus", '2');
        MirrorArtifactRef revision = ref(
                "CAPABILITY_CORPUS_REVISION", "subject-corpus", '3');
        MirrorArtifactRef observation = ref(
                "CAPABILITY_OBSERVATION", "subject-observation", '4');
        ResolvedCorpusPayloads.CapabilityCorpus corpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capability, publication, revision,
                        java.time.Instant.parse("2026-07-23T08:00:00Z"),
                        java.time.Instant.parse("2026-07-24T08:00:00Z"),
                        List.of(ResolvedCorpusPayloads.Sample.response(
                                REQUEST_FINGERPRINT,
                                mapper.writeValueAsBytes(Map.of("source", "recorded")),
                                List.of(publication, revision, observation),
                                List.of(observation.id()), 0.9, List.of())));
        FixtureRule owner = rule("owner", FixtureRule.Selector.any(),
                FixtureRule.Behavior.returning(Map.of("source", "owner")));
        var control = mirrorControl(List.of(owner), List.of(
                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorPlan.MirrorSource.RECORDED_EXACT,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolver.Request recordedRequest = new MirrorResolver.Request(
                SITE, 1, 1, REQUEST_FINGERPRINT,
                Map.of("customerId", "c-1"), List.of(), corpus);
        MirrorResolverChain.Decision recorded = MirrorResolverChain.standard(mapper)
                .resolve(control, recordedRequest);
        MirrorResolver.Request ownerRequest = new MirrorResolver.Request(
                SITE, 1, 1, REQUEST_FINGERPRINT,
                Map.of("customerId", "c-1"), List.of(owner), corpus);
        MirrorResolverChain.Decision selectedOwner = MirrorResolverChain.standard(mapper)
                .resolve(control, ownerRequest);

        assertThat(recorded.source())
                .isEqualTo(MirrorPlan.MirrorSource.RECORDED_EXACT);
        assertThat(recorded.match().rule().behavior().value())
                .isEqualTo(Map.of("source", "recorded"));
        assertThat(recorded.match().artifactRefs())
                .containsExactlyInAnyOrder(publication, revision, observation);
        assertThat(recordedRequest.toString())
                .doesNotContain("c-1")
                .doesNotContain("customerId");
        assertThat(recorded.match().toString())
                .doesNotContain("recorded")
                .doesNotContain("\"source\"");
        assertThat(selectedOwner.source())
                .isEqualTo(MirrorPlan.MirrorSource.OWNER_SPECIFIED);
    }

    @Test
    void recordedExactCarriesNormalizedErrorDetailsWithoutPayloadText() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MirrorArtifactRef capability = ref("CAPABILITY", "operator:subject", '1');
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "subject-corpus", '2');
        MirrorArtifactRef revision = ref(
                "CAPABILITY_CORPUS_REVISION", "subject-corpus", '3');
        MirrorArtifactRef observation = ref(
                "CAPABILITY_OBSERVATION", "subject-observation", '4');
        String details = "sha256:" + "e".repeat(64);
        ResolvedCorpusPayloads.CapabilityCorpus corpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capability, publication, revision,
                        java.time.Instant.parse("2026-07-23T08:00:00Z"),
                        java.time.Instant.parse("2026-07-24T08:00:00Z"),
                        List.of(ResolvedCorpusPayloads.Sample.error(
                                REQUEST_FINGERPRINT,
                                "CUSTOMER_NOT_FOUND",
                                "BUSINESS",
                                false,
                                details,
                                List.of(publication, revision, observation),
                                List.of(observation.id()),
                                0.8,
                                List.of())));
        var control = mirrorControl(List.of(), List.of(
                MirrorPlan.MirrorSource.RECORDED_EXACT,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision decision = MirrorResolverChain.standard(mapper)
                .resolve(control, new MirrorResolver.Request(
                        SITE, 1, 1, REQUEST_FINGERPRINT,
                        Map.of("customerId", "missing"), List.of(), corpus));

        assertThat(decision.match().rule().behavior().errorCode())
                .isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(decision.match().errorDetailsFingerprint()).isEqualTo(details);
        assertThat(decision.match().toString())
                .doesNotContain("customerId")
                .doesNotContain("missing");
    }

    @Test
    void recordedTrajectoryUsesTheRealRetryAttemptAndFailsClosedWhenExhausted()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MirrorArtifactRef capability = ref("CAPABILITY", "operator:subject", '1');
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "subject-corpus", '2');
        MirrorArtifactRef revision = ref(
                "CAPABILITY_CORPUS_REVISION", "subject-corpus", '3');
        MirrorArtifactRef trajectoryPublication = ref(
                "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                "subject-retry", '4');
        MirrorArtifactRef firstObservation = ref(
                "CAPABILITY_OBSERVATION", "subject-attempt-1", '5');
        MirrorArtifactRef secondObservation = ref(
                "CAPABILITY_OBSERVATION", "subject-attempt-2", '6');
        ResolvedCorpusPayloads.Trajectory trajectory =
                new ResolvedCorpusPayloads.Trajectory(
                        REQUEST_FINGERPRINT,
                        trajectoryPublication,
                        List.of(
                                ResolvedCorpusPayloads.Sample.error(
                                        REQUEST_FINGERPRINT,
                                        "UPSTREAM_TIMEOUT",
                                        "TRANSIENT",
                                        true,
                                        "sha256:" + "e".repeat(64),
                                        List.of(trajectoryPublication, firstObservation),
                                        List.of("subject-retry:attempt:1"),
                                        0.8,
                                        List.of()),
                                ResolvedCorpusPayloads.Sample.response(
                                        REQUEST_FINGERPRINT,
                                        mapper.writeValueAsBytes(
                                                Map.of("source", "recorded-retry")),
                                        List.of(trajectoryPublication, secondObservation),
                                        List.of("subject-retry:attempt:2"),
                                        0.8,
                                        List.of())));
        ResolvedCorpusPayloads.CapabilityCorpus corpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capability, publication, revision,
                        java.time.Instant.parse("2026-07-23T08:00:00Z"),
                        java.time.Instant.parse("2026-07-24T08:00:00Z"),
                        List.of(), List.of(trajectory));
        var control = mirrorControl(List.of(), List.of(
                MirrorPlan.MirrorSource.RECORDED_TRAJECTORY,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision first = MirrorResolverChain.standard(mapper)
                .resolve(control, new MirrorResolver.Request(
                        SITE, 1, 1, REQUEST_FINGERPRINT,
                        Map.of("customerId", "c-1"), List.of(), corpus));
        MirrorResolverChain.Decision second = MirrorResolverChain.standard(mapper)
                .resolve(control, new MirrorResolver.Request(
                        SITE, 1, 2, REQUEST_FINGERPRINT,
                        Map.of("customerId", "c-1"), List.of(), corpus));

        assertThat(first.source())
                .isEqualTo(MirrorPlan.MirrorSource.RECORDED_TRAJECTORY);
        assertThat(first.match().rule().behavior().errorCode())
                .isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(second.match().rule().behavior().value())
                .isEqualTo(Map.of("source", "recorded-retry"));
        assertThat(second.match().confidence().method())
                .isEqualTo("RECORDED_TRAJECTORY_V1");
        assertThatThrownBy(() -> MirrorResolverChain.standard(mapper)
                .resolve(control, new MirrorResolver.Request(
                        SITE, 1, 3, REQUEST_FINGERPRINT,
                        Map.of("customerId", "c-1"), List.of(), corpus)))
                .isInstanceOfSatisfying(TestControlException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("MIRROR_TRAJECTORY_ATTEMPT_EXHAUSTED"));
    }

    @Test
    void recordedTrajectoryRequiresRetryableIntermediateAndTerminalFinalOutcomes()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                "subject-retry",
                '1');
        ResolvedCorpusPayloads.Sample response =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        mapper.writeValueAsBytes("ok"),
                        List.of(publication),
                        List.of("attempt"),
                        1,
                        List.of());
        ResolvedCorpusPayloads.Sample retryable =
                ResolvedCorpusPayloads.Sample.error(
                        REQUEST_FINGERPRINT,
                        "UPSTREAM_TIMEOUT",
                        "TRANSIENT",
                        true,
                        "sha256:" + "e".repeat(64),
                        List.of(publication),
                        List.of("attempt"),
                        1,
                        List.of());

        assertThatThrownBy(() -> new ResolvedCorpusPayloads.Trajectory(
                REQUEST_FINGERPRINT,
                publication,
                List.of(response, response)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intermediate attempts");
        assertThatThrownBy(() -> new ResolvedCorpusPayloads.Trajectory(
                REQUEST_FINGERPRINT,
                publication,
                List.of(retryable, retryable)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final attempt");
    }

    @Test
    void recordedClusterProjectsOnlyTheCurrentRequestIdentityAndExportsProvenance()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MirrorArtifactRef clusterPublication = ref(
                "CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                "subject-cluster",
                '7');
        MirrorArtifactRef validation = ref(
                "CAPABILITY_CORPUS_CLUSTER_VALIDATION",
                "subject-cluster-validation",
                '8');
        ResolvedCorpusPayloads.Cluster cluster = cluster(
                mapper,
                clusterPublication,
                validation,
                Map.of(
                        "customer", Map.of("id", "recorded-customer"),
                        "audit", Map.of(
                                "subjects", List.of("recorded-customer")),
                        "tier", "gold"));
        ResolvedCorpusPayloads.CapabilityCorpus corpus =
                corpus(List.of(cluster));
        var control = mirrorControl(List.of(), List.of(
                MirrorPlan.MirrorSource.RECORDED_CLUSTER,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision decision =
                MirrorResolverChain.standard(mapper).resolve(
                        control,
                        new MirrorResolver.Request(
                                SITE,
                                1,
                                1,
                                "sha256:" + "9".repeat(64),
                                Map.of(
                                        "channel", "web",
                                        "operation", "lookup",
                                        "customerId", "current-customer"),
                                List.of(),
                                corpus));

        assertThat(decision.source())
                .isEqualTo(MirrorPlan.MirrorSource.RECORDED_CLUSTER);
        assertThat(decision.match().rule().behavior().value())
                .isEqualTo(Map.of(
                        "customer", Map.of("id", "current-customer"),
                        "audit", Map.of(
                                "subjects", List.of("current-customer")),
                        "tier", "gold"));
        assertThat(decision.match().confidence())
                .isEqualTo(new ArtifactProvenance.Confidence(
                        0.98, 0.91, 1,
                        CapabilityCorpusClusterValidation.CONFIDENCE_METHOD));
        assertThat(decision.match().artifactRefs())
                .containsExactlyInAnyOrder(clusterPublication, validation);
        assertThat(decision.match().ruleRefs())
                .containsExactly("subject-cluster@1");
        assertThat(decision.match().limitations())
                .containsExactly("STATE_DEPENDENCE_NOT_MODELED");
        assertThat(decision.match().toString())
                .doesNotContain("current-customer")
                .doesNotContain("recorded-customer");
    }

    @Test
    void recordedClusterAbstainsWhenMatchOrIdentityCoordinatesAreMissing()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResolvedCorpusPayloads.CapabilityCorpus corpus = corpus(List.of(
                cluster(
                        mapper,
                        ref("CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                                "subject-cluster", '7'),
                        ref("CAPABILITY_CORPUS_CLUSTER_VALIDATION",
                                "subject-cluster-validation", '8'),
                        Map.of("customer", Map.of("id", "recorded-customer"),
                                "audit", Map.of("subjects",
                                        List.of("recorded-customer"))))));
        var control = mirrorControl(List.of(), List.of(
                MirrorPlan.MirrorSource.RECORDED_CLUSTER,
                MirrorPlan.MirrorSource.ABSTAINED));

        MirrorResolverChain.Decision missingIdentity =
                MirrorResolverChain.standard(mapper).resolve(
                        control,
                        new MirrorResolver.Request(
                                SITE, 1, 1, REQUEST_FINGERPRINT,
                                Map.of(
                                        "channel", "web",
                                        "operation", "lookup"),
                                List.of(), corpus));
        MirrorResolverChain.Decision missingMatch =
                MirrorResolverChain.standard(mapper).resolve(
                        control,
                        new MirrorResolver.Request(
                                SITE, 1, 1, REQUEST_FINGERPRINT,
                                Map.of(
                                        "channel", "mobile",
                                        "customerId", "current-customer"),
                                List.of(), corpus));

        assertThat(missingIdentity.abstained()).isTrue();
        assertThat(missingMatch.abstained()).isTrue();
    }

    @Test
    void multipleRecordedClustersForTheSameRequestFailClosed()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResolvedCorpusPayloads.CapabilityCorpus corpus = corpus(List.of(
                cluster(
                        mapper,
                        ref("CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                                "subject-cluster-a", '7'),
                        ref("CAPABILITY_CORPUS_CLUSTER_VALIDATION",
                                "subject-validation-a", '8'),
                        Map.of("customer", Map.of("id", "recorded-a"),
                                "audit", Map.of("subjects",
                                        List.of("recorded-a")))),
                cluster(
                        mapper,
                        ref("CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                                "subject-cluster-b", '9'),
                        ref("CAPABILITY_CORPUS_CLUSTER_VALIDATION",
                                "subject-validation-b", '0'),
                        Map.of("customer", Map.of("id", "recorded-b"),
                                "audit", Map.of("subjects",
                                        List.of("recorded-b"))))));
        var control = mirrorControl(List.of(), List.of(
                MirrorPlan.MirrorSource.RECORDED_CLUSTER,
                MirrorPlan.MirrorSource.ABSTAINED));

        assertThatThrownBy(() -> MirrorResolverChain.standard(mapper)
                .resolve(
                        control,
                        new MirrorResolver.Request(
                                SITE, 1, 1, REQUEST_FINGERPRINT,
                                Map.of(
                                        "channel", "web",
                                        "operation", "lookup",
                                        "customerId", "current-customer"),
                                List.of(), corpus)))
                .isInstanceOfSatisfying(
                        TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_CLUSTER_AMBIGUOUS"));
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

    private static ResolvedCorpusPayloads.CapabilityCorpus corpus(
            List<ResolvedCorpusPayloads.Cluster> clusters) {
        return new ResolvedCorpusPayloads.CapabilityCorpus(
                ref("CAPABILITY", "operator:subject", '1'),
                ref("CAPABILITY_CORPUS_PUBLICATION",
                        "subject-corpus", '2'),
                ref("CAPABILITY_CORPUS_REVISION",
                        "subject-corpus", '3'),
                java.time.Instant.parse("2026-07-23T08:00:00Z"),
                java.time.Instant.parse("2026-07-24T08:00:00Z"),
                List.of(),
                List.of(),
                clusters);
    }

    private static ResolvedCorpusPayloads.Cluster cluster(
            ObjectMapper mapper,
            MirrorArtifactRef publication,
            MirrorArtifactRef validation,
            Object response) throws Exception {
        return new ResolvedCorpusPayloads.Cluster(
                publication,
                List.of(
                        new ResolvedCorpusPayloads.MatchCriterion(
                                "/channel", mapper.valueToTree("web")),
                        new ResolvedCorpusPayloads.MatchCriterion(
                                "/operation", mapper.valueToTree("lookup"))),
                CapabilityCorpusClusterValidation.IdentityMode
                        .REQUEST_PROJECTION,
                List.of(
                        new CapabilityCorpusClusterValidation
                                .IdentityProjection(
                                "/customerId",
                                List.of(
                                        "/audit/subjects/0",
                                        "/customer/id"))),
                mapper.writeValueAsBytes(response),
                List.of(publication, validation),
                List.of("subject-cluster@1"),
                new ArtifactProvenance.Confidence(
                        0.98, 0.91, 1,
                        CapabilityCorpusClusterValidation.CONFIDENCE_METHOD),
                0.87,
                List.of("STATE_DEPENDENCE_NOT_MODELED"));
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

    private static MirrorArtifactRef ref(
            String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(
                kind, id, 1,
                "sha256:" + String.valueOf(fingerprint).repeat(64));
    }
}
