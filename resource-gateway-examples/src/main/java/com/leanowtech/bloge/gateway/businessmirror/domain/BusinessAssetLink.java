package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;

/**
 * Typed business relation independent from executable Graph edges.
 *
 * <p>V1 deliberately rejects cross-scope links. A future protocol may admit them only with a
 * separate, externally verifiable delegation proof.</p>
 *
 * @param schemaVersion link protocol version
 * @param sourceRef exact source business asset
 * @param targetRef exact target business asset
 * @param relation semantic relation
 * @param condition stable business condition or route label
 * @param risk highest risk carried by this relation
 * @param owner accountable relation owner
 * @param provenance source and approval lineage
 */
public record BusinessAssetLink(
        String schemaVersion,
        BusinessAssetRef sourceRef,
        BusinessAssetRef targetRef,
        Relation relation,
        String condition,
        Risk risk,
        String owner,
        ArtifactProvenance provenance
) {
    /** Current business-asset link version. */
    public static final String SCHEMA_VERSION = "resourceGateway.businessAssetLink.v1";

    /** Supported semantic relations. */
    public enum Relation {
        USES,
        COMPOSES,
        IMPLEMENTS,
        DELIVERED_BY,
        EXPOSED_ON,
        VALIDATED_BY,
        CALIBRATED_BY
    }

    /** Business risk carried by an asset relation. */
    public enum Risk {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /** Enforces scope, authority, provenance, and self-link invariants. */
    public BusinessAssetLink {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        sourceRef = java.util.Objects.requireNonNull(sourceRef, "sourceRef");
        targetRef = java.util.Objects.requireNonNull(targetRef, "targetRef");
        relation = java.util.Objects.requireNonNull(relation, "relation");
        condition = BusinessMirrorProtocolSupport.normalized(condition);
        risk = risk == null ? Risk.CRITICAL : risk;
        owner = BusinessMirrorProtocolSupport.identifier(owner, "business asset link owner");
        provenance = java.util.Objects.requireNonNull(provenance, "provenance");
        if (sourceRef.equals(targetRef)) {
            throw new IllegalArgumentException("business asset link must not be a self-link");
        }
        if (!BusinessMirrorProtocolSupport.sameScope(sourceRef.scope(), targetRef.scope())) {
            throw new IllegalArgumentException("business asset link must not cross enterprise scope");
        }
        if (!sourceRef.scope().tenantId().equals(provenance.tenantId())) {
            throw new IllegalArgumentException("business asset link provenance tenant must match asset scope");
        }
    }
}
