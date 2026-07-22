package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorResolutionIntegrityTest {
    private static final String PLAN_FINGERPRINT = fingerprint('a');
    private static final String REQUEST_FINGERPRINT = fingerprint('b');
    private static final MirrorArtifactRef CAPABILITY = new MirrorArtifactRef(
            "CAPABILITY", "operator:customer.lookup", 3, fingerprint('c'));
    private static final MirrorArtifactRef FIXTURE = new MirrorArtifactRef(
            "FIXTURE_BUNDLE", "customer-fixture", 7, fingerprint('d'));

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsVisibleOutputAndTheCompleteResolution() {
        MirrorResolution sealed = MirrorResolutionIntegrity.seal(mapper,
                resolved(Map.of("customerId", "C-1")));

        assertThat(sealed.schemaVersion()).isEqualTo(MirrorResolution.SCHEMA_VERSION);
        assertThat(sealed.outputFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(sealed.resolutionFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(sealed.matchedArtifactRefs()).containsExactly(FIXTURE);
        MirrorResolutionIntegrity.verify(mapper, sealed);
    }

    @Test
    void preservesAnExplicitResolvedNullInsteadOfFlatteningItIntoNoOutput() {
        MirrorResolution sealed = MirrorResolutionIntegrity.seal(mapper, resolved(null));

        assertThat(sealed.outputIncluded()).isTrue();
        assertThat(sealed.output()).isNull();
        assertThat(sealed.payloadVisibility()).isEqualTo(MirrorResolution.PayloadVisibility.FULL);
        assertThat(sealed.outputFingerprint()).matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void detectsOutputAndResolutionTampering() {
        MirrorResolution sealed = MirrorResolutionIntegrity.seal(mapper,
                resolved(Map.of("customerId", "C-1")));
        MirrorResolution changedOutput = new MirrorResolution(sealed.schemaVersion(),
                sealed.resolutionFingerprint(), sealed.runId(), sealed.planFingerprint(),
                sealed.capabilityRef(), sealed.invocationSiteId(), sealed.graphPath(),
                sealed.correlationKey(), sealed.occurrence(), sealed.attempt(),
                sealed.requestFingerprint(), sealed.status(), sealed.source(),
                sealed.payloadVisibility(), true, Map.of("customerId", "C-2"),
                sealed.outputFingerprint(), sealed.error(), sealed.matchedArtifactRefs(),
                sealed.matchedRuleRefs(), sealed.confidence(), sealed.freshness(),
                sealed.limitations());

        assertThatThrownBy(() -> MirrorResolutionIntegrity.verify(mapper, changedOutput))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output fingerprint mismatch");

        MirrorResolution changedLimitation = new MirrorResolution(sealed.schemaVersion(),
                sealed.resolutionFingerprint(), sealed.runId(), sealed.planFingerprint(),
                sealed.capabilityRef(), sealed.invocationSiteId(), sealed.graphPath(),
                sealed.correlationKey(), sealed.occurrence(), sealed.attempt(),
                sealed.requestFingerprint(), sealed.status(), sealed.source(),
                sealed.payloadVisibility(), sealed.outputIncluded(), sealed.output(),
                sealed.outputFingerprint(), sealed.error(), sealed.matchedArtifactRefs(),
                sealed.matchedRuleRefs(), sealed.confidence(), sealed.freshness(),
                List.of("NEW_LIMITATION"));
        assertThatThrownBy(() -> MirrorResolutionIntegrity.verify(mapper, changedLimitation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolution fingerprint mismatch");
    }

    @Test
    void recursivelyDetachesVisibleOutputAndRejectsCycles() {
        List<Object> nested = new ArrayList<>();
        Map<String, Object> mutable = new LinkedHashMap<>();
        nested.add("before");
        mutable.put("items", nested);
        MirrorResolution resolution = resolved(mutable);

        nested.add("after");
        mutable.put("late", true);

        assertThat(resolution.output()).isEqualTo(Map.of("items", List.of("before")));
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) resolution.output();
        assertThatThrownBy(() -> frozen.put("forbidden", true))
                .isInstanceOf(UnsupportedOperationException.class);

        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThatThrownBy(() -> resolved(cyclic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void distinguishesHashOnlyEvidenceFromAbstention() {
        MirrorResolution hashOnly = new MirrorResolution("", "", "run-1", PLAN_FINGERPRINT,
                CAPABILITY, "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1,
                REQUEST_FINGERPRINT, MirrorResolution.Status.RESOLVED,
                MirrorPlan.MirrorSource.GOVERNED_REPLAY,
                MirrorResolution.PayloadVisibility.HASH_ONLY, false, null, fingerprint('e'), null,
                List.of(FIXTURE), List.of("replay-customer"),
                new ArtifactProvenance.Confidence(1, 1, 1, "exact-replay-ref-v1"),
                0, List.of("PAYLOAD_WITHHELD", "FRESHNESS_NOT_CALIBRATED"));
        MirrorResolution abstained = abstained();

        assertThat(hashOnly.outputIncluded()).isFalse();
        assertThat(hashOnly.outputFingerprint()).isEqualTo(fingerprint('e'));
        assertThat(abstained.payloadVisibility())
                .isEqualTo(MirrorResolution.PayloadVisibility.NONE);
        assertThat(abstained.outputFingerprint()).isEmpty();
    }

    @Test
    void enforcesAbstainedRejectedAndResolvedOutcomeInvariants() {
        assertThatThrownBy(() -> new MirrorResolution("", "", "run-1", PLAN_FINGERPRINT,
                CAPABILITY, "/root/loadCustomer#PRIMARY", "/root", "", 1, 1,
                REQUEST_FINGERPRINT, MirrorResolution.Status.ABSTAINED,
                MirrorPlan.MirrorSource.OWNER_SPECIFIED, MirrorResolution.PayloadVisibility.NONE,
                false, null, "", null, List.of(), List.of(),
                new ArtifactProvenance.Confidence(0, 0, 0, "abstained-v1"), 0,
                List.of("NO_MATCH")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABSTAINED");

        assertThatThrownBy(() -> new MirrorResolution("", "", "run-1", PLAN_FINGERPRINT,
                CAPABILITY, "/root/loadCustomer#PRIMARY", "/root", "", 1, 1,
                REQUEST_FINGERPRINT, MirrorResolution.Status.REJECTED,
                MirrorPlan.MirrorSource.OWNER_SPECIFIED, MirrorResolution.PayloadVisibility.NONE,
                false, null, "", null, List.of(FIXTURE), List.of(),
                new ArtifactProvenance.Confidence(1, 1, 1, "owner-rule-v1"), 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REJECTED");

        assertThatThrownBy(() -> new MirrorResolution("", "", "run-1", PLAN_FINGERPRINT,
                CAPABILITY, "/root/loadCustomer#PRIMARY", "/root", "", 1, 1,
                REQUEST_FINGERPRINT, MirrorResolution.Status.RESOLVED,
                MirrorPlan.MirrorSource.OWNER_SPECIFIED, MirrorResolution.PayloadVisibility.FULL,
                true, "value", "", new MirrorResolution.MirrorError("E", "BUSINESS", ""),
                List.of(FIXTURE), List.of(),
                new ArtifactProvenance.Confidence(1, 1, 1, "owner-rule-v1"), 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsInvalidConfidenceFreshnessAndDuplicateProvenance() {
        assertThatThrownBy(() -> new ArtifactProvenance.Confidence(0.9, 0.95, 1,
                "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyWithFreshness(1.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");
        assertThatThrownBy(() -> copyWithArtifacts(List.of(FIXTURE, FIXTURE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void keepsPayloadAndErrorMessageOutOfGenericLogging() {
        MirrorResolution resolution = resolved(Map.of("secret", "customer-payload"));
        MirrorResolution.MirrorError error = new MirrorResolution.MirrorError(
                "CUSTOMER_NOT_FOUND", "BUSINESS", "sensitive customer C-1");

        assertThat(resolution.toString())
                .doesNotContain("customer-payload")
                .doesNotContain("secret");
        assertThat(error.toString()).doesNotContain("sensitive customer C-1");
    }

    private static MirrorResolution resolved(Object output) {
        return new MirrorResolution("", "", "run-1", PLAN_FINGERPRINT, CAPABILITY,
                "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1,
                REQUEST_FINGERPRINT, MirrorResolution.Status.RESOLVED,
                MirrorPlan.MirrorSource.OWNER_SPECIFIED, MirrorResolution.PayloadVisibility.FULL,
                true, output, "", null, List.of(FIXTURE), List.of("customer-response"),
                new ArtifactProvenance.Confidence(1, 1, 1, "owner-rule-v1"), 1,
                List.of());
    }

    private static MirrorResolution abstained() {
        return new MirrorResolution("", "", "run-1", PLAN_FINGERPRINT, CAPABILITY,
                "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1,
                REQUEST_FINGERPRINT, MirrorResolution.Status.ABSTAINED,
                MirrorPlan.MirrorSource.ABSTAINED, MirrorResolution.PayloadVisibility.NONE,
                false, null, "", null, List.of(), List.of(),
                new ArtifactProvenance.Confidence(0, 0, 0, "abstained-v1"), 0,
                List.of("NO_APPROVED_SOURCE_MATCHED"));
    }

    private static MirrorResolution copyWithFreshness(double freshness) {
        MirrorResolution source = resolved("value");
        return new MirrorResolution(source.schemaVersion(), source.resolutionFingerprint(),
                source.runId(), source.planFingerprint(), source.capabilityRef(),
                source.invocationSiteId(), source.graphPath(), source.correlationKey(),
                source.occurrence(), source.attempt(), source.requestFingerprint(), source.status(),
                source.source(), source.payloadVisibility(), source.outputIncluded(), source.output(),
                source.outputFingerprint(), source.error(), source.matchedArtifactRefs(),
                source.matchedRuleRefs(), source.confidence(), freshness, source.limitations());
    }

    private static MirrorResolution copyWithArtifacts(List<MirrorArtifactRef> artifacts) {
        MirrorResolution source = resolved("value");
        return new MirrorResolution(source.schemaVersion(), source.resolutionFingerprint(),
                source.runId(), source.planFingerprint(), source.capabilityRef(),
                source.invocationSiteId(), source.graphPath(), source.correlationKey(),
                source.occurrence(), source.attempt(), source.requestFingerprint(), source.status(),
                source.source(), source.payloadVisibility(), source.outputIncluded(), source.output(),
                source.outputFingerprint(), source.error(), artifacts, source.matchedRuleRefs(),
                source.confidence(), source.freshness(), source.limitations());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
