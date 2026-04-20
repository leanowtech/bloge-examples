package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.durable.mybatis.store.AbstractMybatisStore;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphTenantSupport;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared MyBatis infrastructure for graph-engine product stores.
 *
 * @param <M> primary mapper type used by the concrete store
 */
abstract class AbstractGraphEngineStore<M> extends AbstractMybatisStore<M> {
    protected AbstractGraphEngineStore(ScopedSqlSessionManager sessionManager,
                                       Class<M> mapperType,
                                       CheckpointCodec checkpointCodec,
                                       TimeSource timeSource) {
        super(sessionManager, mapperType, checkpointCodec, timeSource);
    }

    /**
     * Resolves the effective tenant/namespace filter once the current thread's
     * bound tenant visibility is applied.
     *
     * @param tenantId requested tenant filter, or {@code null}
     * @param namespace requested namespace filter, or {@code null}
     * @return resolved scope, which may be marked hidden when the current tenant
     *         context forbids the requested scope
     */
    protected EffectiveScope resolveScope(String tenantId, String namespace) {
        Optional<TenantContext> current = GraphTenantSupport.currentTenant();
        if (current.isEmpty()) {
            return new EffectiveScope(tenantId, namespace, true);
        }
        TenantContext bound = current.get();
        if ((tenantId != null && !Objects.equals(tenantId, bound.tenantId()))
                || (namespace != null && !Objects.equals(namespace, bound.namespace()))) {
            return EffectiveScope.hidden();
        }
        return new EffectiveScope(bound.tenantId(), bound.namespace(), true);
    }

    protected GraphEngineStoreException notFound(String type, String id) {
        return new GraphEngineStoreException(GraphEngineErrorCode.NOT_FOUND, type + " not found: " + id);
    }

    protected GraphEngineStoreException duplicate(String type, String id) {
        return new GraphEngineStoreException(GraphEngineErrorCode.DUPLICATE, type + " already exists: " + id);
    }

    protected GraphEngineStoreException versionConflict(String type, String id, long expectedRevision) {
        return new GraphEngineStoreException(
                GraphEngineErrorCode.VERSION_CONFLICT,
                type + " revision conflict for " + id + " (expected revision " + expectedRevision + ')'
        );
    }

    /**
     * Simple scope tuple representing the tenant filter after thread-local
     * tenant visibility has been applied.
     *
     * @param tenantId effective tenant filter
     * @param namespace effective namespace filter
     * @param visible whether the current scope can see any rows
     */
    protected record EffectiveScope(String tenantId, String namespace, boolean visible) {
        static EffectiveScope hidden() {
            return new EffectiveScope(null, null, false);
        }
    }
}
