package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Copy-on-write baseline boundary for stateful mirror sessions.
 *
 * <p>Only an exact recorded entity or an owner-specified fixture may seed historical state.
 * Cluster, trajectory, inferred-schema, synthesized, and real-resource sources are absent from
 * this closed source enum and therefore cannot be accidentally admitted by an implementation.</p>
 */
@FunctionalInterface
public interface MirrorStateBaselineResolver {

    /**
     * Resolves one exact baseline without retaining the request.
     *
     * @param request payload-free exact lookup coordinates
     * @return allowed baseline or empty when absent
     */
    Optional<Baseline> resolve(Request request);

    /** @return a resolver that always reports an absent baseline */
    static MirrorStateBaselineResolver none() {
        return request -> Optional.empty();
    }

    /** Allowed copy-on-write baseline sources. */
    enum Source {
        RECORDED_EXACT,
        OWNER_SPECIFIED
    }

    /**
     * Payload-free exact lookup coordinates.
     *
     * @param sessionId isolated session identity
     * @param scope exact enterprise scope
     * @param baselineCapabilityRef exact approved read capability
     * @param entityKey requested identity
     */
    record Request(
            String sessionId,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef baselineCapabilityRef,
            SessionStateSpace.EntityKey entityKey
    ) {
        /** Validates one baseline lookup. */
        public Request {
            sessionId = required(sessionId, "sessionId");
            scope = Objects.requireNonNull(scope, "scope");
            baselineCapabilityRef = Objects.requireNonNull(
                    baselineCapabilityRef, "baselineCapabilityRef");
            if (!"CAPABILITY".equals(baselineCapabilityRef.kind())) {
                throw new IllegalArgumentException(
                        "baselineCapabilityRef must reference a CAPABILITY");
            }
            entityKey = Objects.requireNonNull(entityKey, "entityKey");
        }
    }

    /**
     * Detached entity and complete business-key index returned by an allowed source.
     *
     * @param source closed allowed source
     * @param artifactRef exact owner fixture or recorded sample
     * @param entity copied entity
     * @param businessKeys complete entity key bindings
     */
    record Baseline(
            Source source,
            MirrorArtifactRef artifactRef,
            SessionStateSpace.EntitySnapshot entity,
            List<SessionStateSpace.BusinessKeyBinding> businessKeys
    ) {
        /** Validates one exact detached baseline. */
        public Baseline {
            source = Objects.requireNonNull(source, "source");
            artifactRef = Objects.requireNonNull(artifactRef, "artifactRef");
            if (!"FIXTURE".equals(artifactRef.kind())
                    && !"CORPUS_SAMPLE".equals(artifactRef.kind())) {
                throw new IllegalArgumentException(
                        "baseline artifact must be a FIXTURE or CORPUS_SAMPLE");
            }
            if ((source == Source.RECORDED_EXACT
                    && !"CORPUS_SAMPLE".equals(artifactRef.kind()))
                    || (source == Source.OWNER_SPECIFIED
                    && !"FIXTURE".equals(artifactRef.kind()))) {
                throw new IllegalArgumentException(
                        "baseline source must match its exact artifact kind");
            }
            entity = Objects.requireNonNull(entity, "entity");
            businessKeys = businessKeys == null ? List.of() : List.copyOf(businessKeys);
            if (businessKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "baseline must carry complete business-key bindings");
            }
            for (SessionStateSpace.BusinessKeyBinding key : businessKeys) {
                if (!entity.key().equals(key.entityKey())) {
                    throw new IllegalArgumentException(
                            "baseline business keys must target the baseline entity");
                }
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
