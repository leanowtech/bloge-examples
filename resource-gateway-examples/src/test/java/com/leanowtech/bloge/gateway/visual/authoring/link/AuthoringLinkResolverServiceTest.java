package com.leanowtech.bloge.gateway.visual.authoring.link;

import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringLinkResolverServiceTest {
    private static final String GRAPH_ID = "built-in:loanDecisionPolicy";
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "project-a", "test", "sg");

    @Test
    void exactLegacySourceResolvesToReadOnlyAuthorComposeRoute() {
        AuthoringLinkResolution result = service().resolve(request(GRAPH_ID, 1, FINGERPRINT), identity());

        assertThat(result.status()).isEqualTo(AuthoringLinkResolution.Status.RESOLVED);
        assertThat(result.descriptor().schemaVersion())
                .isEqualTo(AuthoringLinkDescriptor.SCHEMA_VERSION);
        assertThat(result.descriptor().resolution())
                .isEqualTo(AuthoringLinkDescriptor.Resolution.READ_ONLY_SOURCE);
        assertThat(result.descriptor().route().path()).isEqualTo("/author/");
        assertThat(result.descriptor().route().query())
                .containsEntry("sourceId", GRAPH_ID)
                .containsEntry("sourceRevision", "1")
                .containsEntry("sourceFingerprint", FINGERPRINT)
                .containsEntry("authorMode", "compose")
                .doesNotContainKey("showcaseHref");
    }

    @Test
    void truthTableFailsClosedForWrongKindMissingSourceAndDrift() {
        AuthoringLinkResolverService resolver = service();

        assertThat(resolver.resolve(request("built-in:unknown", 1, FINGERPRINT), identity()))
                .extracting(AuthoringLinkResolution::status, AuthoringLinkResolution::errorCode)
                .containsExactly(AuthoringLinkResolution.Status.NOT_FOUND,
                        "RG.AUTHORING_LINK.SOURCE_NOT_FOUND");
        assertThat(resolver.resolve(request(GRAPH_ID, 2, FINGERPRINT), identity()))
                .extracting(AuthoringLinkResolution::status, AuthoringLinkResolution::errorCode)
                .containsExactly(AuthoringLinkResolution.Status.DRIFTED,
                        "RG.AUTHORING_LINK.SOURCE_DRIFTED");
        AuthoringLinkResolveRequest wrongKind = new AuthoringLinkResolveRequest(
                AuthoringLinkResolveRequest.SCHEMA_VERSION,
                new AuthoringLinkResolveRequest.ExactSubjectRef(
                        "GRAPH_DRAFT", GRAPH_ID, 1, FINGERPRINT),
                "EDIT_TOPOLOGY", coordinate());
        assertThat(resolver.resolve(wrongKind, identity()))
                .extracting(AuthoringLinkResolution::status, AuthoringLinkResolution::errorCode)
                .containsExactly(AuthoringLinkResolution.Status.NOT_FOUND,
                        "RG.AUTHORING_LINK.SOURCE_NOT_FOUND");
    }

    @Test
    void authorityFailureDoesNotTurnIntoLatestOrAShowcaseRoute() {
        AuthoringLinkSourceAuthority unavailable = new AuthoringLinkSourceAuthority() {
            @Override
            public List<String> graphNames() {
                return List.of(GRAPH_ID.substring("built-in:".length()));
            }

            @Override
            public BuiltInGraphAssetAuthority.Snapshot resolve(
                    CapabilitySnapshot.Scope scope, String graphName) {
                throw new IllegalStateException("projection unavailable");
            }
        };

        AuthoringLinkResolution result = new AuthoringLinkResolverService(unavailable)
                .resolve(request(GRAPH_ID, 1, FINGERPRINT), identity());

        assertThat(result.status()).isEqualTo(AuthoringLinkResolution.Status.NOT_FOUND);
        assertThat(result.descriptor()).isNull();
        assertThat(result.errorCode()).isEqualTo("RG.AUTHORING_LINK.SOURCE_NOT_FOUND");
    }

    @Test
    void authorityScopeMismatchIsForbidden() {
        BuiltInGraphAssetAuthority.Snapshot source = new BuiltInGraphAssetAuthority.Snapshot(
                new CapabilitySnapshot.Scope("tenant-other", "org-a", "project-a", "test", "sg"),
                "loanDecisionPolicy",
                ref("GRAPH_DRAFT", GRAPH_ID, FINGERPRINT),
                ref("CONTRACT", GRAPH_ID + ":contract", FINGERPRINT),
                ref("CAPABILITY", GRAPH_ID, FINGERPRINT),
                ref("CAPABILITY_CLOSURE", GRAPH_ID, FINGERPRINT), List.of());
        AuthoringLinkSourceAuthority authority = new AuthoringLinkSourceAuthority() {
            @Override
            public List<String> graphNames() {
                return List.of("loanDecisionPolicy");
            }

            @Override
            public BuiltInGraphAssetAuthority.Snapshot resolve(
                    CapabilitySnapshot.Scope scope, String graphName) {
                return source;
            }
        };

        AuthoringLinkResolution result = new AuthoringLinkResolverService(authority)
                .resolve(request(GRAPH_ID, 1, FINGERPRINT), identity());

        assertThat(result.status()).isEqualTo(AuthoringLinkResolution.Status.FORBIDDEN);
        assertThat(result.errorCode()).isEqualTo("RG.AUTHORING_LINK.FORBIDDEN");
    }

    @Test
    void returnCoordinateIsStrictlyAllowlisted() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new AuthoringLinkResolveRequest.ReturnCoordinate(
                        "https://evil.example", "legacy:loanDecisionPolicy",
                        "capabilities", "graph:" + GRAPH_ID));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new AuthoringLinkResolveRequest.ReturnCoordinate(
                        "business-mirror", "legacy/../../escape",
                        "capabilities", "graph:" + GRAPH_ID));
    }

    private static AuthoringLinkResolverService service() {
        BuiltInGraphAssetAuthority.Snapshot source = new BuiltInGraphAssetAuthority.Snapshot(
                SCOPE, "loanDecisionPolicy",
                ref("GRAPH_DRAFT", GRAPH_ID, FINGERPRINT),
                ref("CONTRACT", GRAPH_ID + ":contract", FINGERPRINT),
                ref("CAPABILITY", GRAPH_ID, FINGERPRINT),
                ref("CAPABILITY_CLOSURE", GRAPH_ID, FINGERPRINT), List.of());
        AuthoringLinkSourceAuthority authority = new AuthoringLinkSourceAuthority() {
            @Override
            public List<String> graphNames() {
                return List.of("loanDecisionPolicy");
            }

            @Override
            public BuiltInGraphAssetAuthority.Snapshot resolve(
                    CapabilitySnapshot.Scope scope, String graphName) {
                assertThat(scope).isEqualTo(SCOPE);
                return source;
            }
        };
        return new AuthoringLinkResolverService(authority);
    }

    private static AuthoringLinkResolveRequest request(String id, long revision, String fingerprint) {
        return new AuthoringLinkResolveRequest(AuthoringLinkResolveRequest.SCHEMA_VERSION,
                new AuthoringLinkResolveRequest.ExactSubjectRef(
                        AuthoringLinkResolverService.SOURCE_KIND, id, revision, fingerprint),
                "EDIT_TOPOLOGY", coordinate());
    }

    private static AuthoringLinkResolveRequest.ReturnCoordinate coordinate() {
        return new AuthoringLinkResolveRequest.ReturnCoordinate(
                "business-mirror", "legacy:loanDecisionPolicy", "capabilities",
                "graph:" + GRAPH_ID);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD",
                "author", "", "BUSINESS_MIRROR_AUTHORING", "corr");
    }

    private static MirrorArtifactRef ref(String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }
}
