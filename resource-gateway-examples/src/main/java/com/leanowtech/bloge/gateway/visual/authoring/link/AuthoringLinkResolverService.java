package com.leanowtech.bloge.gateway.visual.authoring.link;

import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves exact source coordinates into a safe Author route without latest/name fallback. */
public final class AuthoringLinkResolverService {
    public static final String SOURCE_KIND = "BUSINESS_MIRROR_LEGACY_GRAPH";

    private final AuthoringLinkSourceAuthority legacyAuthority;

    public AuthoringLinkResolverService(BuiltInGraphAssetAuthority legacyAuthority) {
        BuiltInGraphAssetAuthority authority = Objects.requireNonNull(
                legacyAuthority, "legacyAuthority");
        this.legacyAuthority = new AuthoringLinkSourceAuthority() {
            @Override
            public java.util.List<String> graphNames() {
                return authority.graphNames();
            }

            @Override
            public BuiltInGraphAssetAuthority.Snapshot resolve(
                    CapabilitySnapshot.Scope scope, String graphName) {
                return authority.resolve(scope, graphName);
            }
        };
    }

    public AuthoringLinkResolverService(AuthoringLinkSourceAuthority legacyAuthority) {
        this.legacyAuthority = Objects.requireNonNull(legacyAuthority, "legacyAuthority");
    }

    public AuthoringLinkResolution resolve(
            AuthoringLinkResolveRequest request, IntegrationRequestContext identity) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(identity, "identity");
        if (!request.intent().equals(AuthoringLinkResolveRequest.EDIT_TOPOLOGY)) {
            return failure(AuthoringLinkResolution.Status.NOT_FOUND,
                    "RG.AUTHORING_LINK.REQUEST_INVALID");
        }
        AuthoringLinkResolveRequest.ExactSubjectRef subject = request.subjectRef();
        if (!SOURCE_KIND.equals(subject.kind())) {
            return failure(AuthoringLinkResolution.Status.NOT_FOUND,
                    "RG.AUTHORING_LINK.SOURCE_NOT_FOUND");
        }

        String graphName = BuiltInGraphAssetAuthority.graphNameFromGraphId(subject.id());
        if (graphName.isBlank() || !legacyAuthority.graphNames().contains(graphName)) {
            return failure(AuthoringLinkResolution.Status.NOT_FOUND,
                    "RG.AUTHORING_LINK.SOURCE_NOT_FOUND");
        }

        CapabilitySnapshot.Scope scope = new CapabilitySnapshot.Scope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
        BuiltInGraphAssetAuthority.Snapshot current;
        try {
            current = legacyAuthority.resolve(scope, graphName);
        } catch (RuntimeException failure) {
            return failure(AuthoringLinkResolution.Status.NOT_FOUND,
                    "RG.AUTHORING_LINK.SOURCE_NOT_FOUND");
        }
        MirrorArtifactRef expected = current.graphRef();
        if (!expected.id().equals(subject.id())
                || expected.revision() != subject.revision()
                || !expected.fingerprint().equals(subject.fingerprint())) {
            return failure(AuthoringLinkResolution.Status.DRIFTED,
                    "RG.AUTHORING_LINK.SOURCE_DRIFTED");
        }

        AuthoringLinkResolveRequest.ReturnCoordinate returnCoordinate = request.returnCoordinate();
        if (!returnCoordinate.anchor().equals("graph:" + subject.id())) {
            return failure(AuthoringLinkResolution.Status.INVALID_REQUEST,
                    "RG.AUTHORING_LINK.REQUEST_INVALID");
        }
        if (!scope.equals(current.scope())) {
            return failure(AuthoringLinkResolution.Status.FORBIDDEN,
                    "RG.AUTHORING_LINK.FORBIDDEN");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("authorWorkspace", "v2");
        query.put("authorMode", "compose");
        query.put("sourceKind", SOURCE_KIND);
        query.put("sourceGraphName", graphName);
        query.put("sourceId", expected.id());
        query.put("sourceRevision", Long.toString(expected.revision()));
        query.put("sourceFingerprint", expected.fingerprint());
        query.put("returnRoute", returnCoordinate.route());
        query.put("returnPackageId", returnCoordinate.packageId());
        query.put("returnTask", returnCoordinate.task());
        query.put("returnAnchor", returnCoordinate.anchor());
        AuthoringLinkDescriptor descriptor = new AuthoringLinkDescriptor(
                "", AuthoringLinkDescriptor.Resolution.READ_ONLY_SOURCE,
                new AuthoringLinkDescriptor.Route("/author/", "v2", "compose", query),
                subject, returnCoordinate);
        return new AuthoringLinkResolution(
                AuthoringLinkResolution.SCHEMA_VERSION,
                AuthoringLinkResolution.Status.RESOLVED, descriptor, "");
    }

    private static AuthoringLinkResolution failure(
            AuthoringLinkResolution.Status status, String code) {
        return new AuthoringLinkResolution(
                AuthoringLinkResolution.SCHEMA_VERSION, status, null, code);
    }
}
