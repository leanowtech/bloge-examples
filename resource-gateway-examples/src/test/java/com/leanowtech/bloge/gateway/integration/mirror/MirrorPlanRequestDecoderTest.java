package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorPlanRequestDecoder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorPlanRequestDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MirrorPlanRequestDecoder decoder = new MirrorPlanRequestDecoder(mapper);

    @Test
    void decodesTheExactPublicShapeAndRecursivelyRejectsUnknownFields() {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper, MirrorPersistenceTestFixtures.scope("org-a"), "plan-a", 'a');
        CapabilityClosure closure = new CapabilityClosure("", plan.rootCapability(),
                plan.capabilityClosure(), plan.capabilityClosureFingerprint());
        MirrorPlanCreateRequest request = new MirrorPlanCreateRequest("", "plan-a",
                "customer-view", plan.capabilityClosure().stream()
                .filter(snapshot -> snapshot.kind() == CapabilitySnapshot.Kind.COMPOSED)
                .findFirst().orElseThrow().source().sourceFingerprint(), closure,
                plan.fixtureBundleRef(), 100, Duration.ofMinutes(5), false, plan.expiresAt());
        ObjectNode exact = mapper.valueToTree(request);

        assertThat(decoder.decode(exact, identity())).isEqualTo(request);

        ObjectNode topLevelUnknown = exact.deepCopy();
        topLevelUnknown.put("networkEgressAllowed", true);
        assertMalformed(() -> decoder.decode(topLevelUnknown, identity()));

        ObjectNode nestedUnknown = exact.deepCopy();
        ((ObjectNode) nestedUnknown.path("capabilityClosure"))
                .put("callerApproved", true);
        assertMalformed(() -> decoder.decode(nestedUnknown, identity()));

        ObjectNode snapshotUnknown = exact.deepCopy();
        ((ObjectNode) snapshotUnknown.at("/capabilityClosure/snapshots/0"))
                .put("unreviewedRuntimeOverride", "real");
        assertMalformed(() -> decoder.decode(snapshotUnknown, identity()));
    }

    @Test
    void rejectsNullAndMalformedTreesWithAStablePayloadFreeProblem() {
        assertMalformed(() -> decoder.decode(null, identity()));
        assertMalformed(() -> decoder.decode(mapper.createObjectNode()
                .put("schemaVersion", MirrorPlanCreateRequest.SCHEMA_VERSION), identity()));
    }

    private static void assertMalformed(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.PLAN_REQUEST_MALFORMED");
                    assertThat(failure.problem().details())
                            .containsEntry("schemaVersion",
                                    MirrorPlanCreateRequest.SCHEMA_VERSION)
                            .containsKey("maximumBytes");
                });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "support", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL", "corr-1",
                Set.of(), "CONFIDENTIAL", "");
    }
}
