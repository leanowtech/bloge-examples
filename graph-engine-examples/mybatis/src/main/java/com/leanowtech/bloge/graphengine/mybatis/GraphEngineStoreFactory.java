package com.leanowtech.bloge.graphengine.mybatis;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.mybatis.DialectResolver;
import com.leanowtech.bloge.durable.mybatis.DurableStoreFactory;
import com.leanowtech.bloge.durable.mybatis.SqlDialect;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.graphengine.mybatis.store.GraphDefinitionStoreMapper;
import com.leanowtech.bloge.graphengine.mybatis.store.GraphDeploymentStoreMapper;
import com.leanowtech.bloge.graphengine.mybatis.store.GraphInstanceStoreMapper;
import com.leanowtech.bloge.graphengine.mybatis.store.GraphVersionStoreMapper;
import com.leanowtech.bloge.graphengine.mybatis.store.MybatisGraphDefinitionStore;
import com.leanowtech.bloge.graphengine.mybatis.store.MybatisGraphDeploymentStore;
import com.leanowtech.bloge.graphengine.mybatis.store.MybatisGraphInstanceStore;
import com.leanowtech.bloge.graphengine.mybatis.store.MybatisGraphVersionStore;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.GraphEngineStores;
import com.leanowtech.bloge.graphengine.store.GraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.GraphVersionStore;
import org.flywaydb.core.Flyway;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Factory for MyBatis-backed graph-engine metadata stores.
 *
 * <p>This factory composes with {@link DurableStoreFactory}: it reuses the same
 * MyBatis configuration and durable schema migration flow, then layers the
 * product-layer {@code ge_*} mappers and migrations on top.</p>
 */
public final class GraphEngineStoreFactory {
    private GraphEngineStoreFactory() {
    }

    /**
     * Creates a fluent builder for graph-engine metadata stores backed by the
     * supplied data source and durable JSON codec.
     *
     * @param dataSource JDBC data source hosting both durable and product-layer tables
     * @param checkpointCodec durable JSON codec used for structured metadata columns
     * @return a new builder
     */
    public static Builder builder(DataSource dataSource, CheckpointCodec checkpointCodec) {
        return new Builder(dataSource, checkpointCodec);
    }

    /**
     * Creates a {@link SqlSessionFactory} with both durable and graph-engine
     * mapper registrations.
     *
     * @param dataSource JDBC data source to bind
     * @param checkpointCodec durable JSON codec used by shared type handlers
     * @return configured session factory
     */
    public static SqlSessionFactory createSqlSessionFactory(DataSource dataSource, CheckpointCodec checkpointCodec) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(checkpointCodec, "checkpointCodec");
        SqlSessionFactory factory = DurableStoreFactory.createSqlSessionFactory(dataSource, checkpointCodec);
        registerGraphEngineMappers(factory.getConfiguration());
        return factory;
    }

    /**
     * Runs durable migrations first and then applies the graph-engine metadata
     * migrations for the detected SQL dialect.
     *
     * <p>The graph-engine module intentionally introduces its metadata tables at
     * version {@code V18} while the shared durable stream already contains later
     * versions. Flyway therefore needs out-of-order mode enabled for the combined
     * durable + graph-engine migration set so a schema that has already advanced
     * through the durable stream can still apply the graph-engine metadata
     * migration safely.</p>
     *
     * @param dataSource JDBC data source to migrate
     */
    public static void migrateSchema(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        SqlDialect dialect = DialectResolver.resolve(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations(dialect))
                .outOfOrder(true)
                .load()
                .migrate();
    }

    private static void registerGraphEngineMappers(Configuration configuration) {
        registerMapper(configuration, GraphDefinitionStoreMapper.class);
        registerMapper(configuration, GraphVersionStoreMapper.class);
        registerMapper(configuration, GraphDeploymentStoreMapper.class);
        registerMapper(configuration, GraphInstanceStoreMapper.class);
    }

    private static void registerMapper(Configuration configuration, Class<?> mapperType) {
        if (!configuration.hasMapper(mapperType)) {
            configuration.addMapper(mapperType);
        }
    }

    private static String[] migrationLocations(SqlDialect dialect) {
        ArrayList<String> locations = new ArrayList<>(Arrays.asList(DialectResolver.migrationLocations(dialect)));
        locations.add(graphEngineMigrationLocation(dialect));
        return locations.toArray(String[]::new);
    }

    private static String graphEngineMigrationLocation(SqlDialect dialect) {
        String suffix = switch (dialect) {
            case H2 -> "h2";
            case MYSQL -> "mysql";
            case POSTGRESQL -> "postgresql";
        };
        return "classpath:db/migration/graph-engine/" + suffix;
    }

    /**
     * Fluent builder for creating graph-engine store infrastructure and store
     * instances.
     */
    public static final class Builder {
        private final DataSource dataSource;
        private final CheckpointCodec checkpointCodec;
        private TimeSource timeSource = SystemTimeSource.INSTANCE;
        private SqlSessionFactory sqlSessionFactory;
        private ScopedSqlSessionManager sessionManager;

        private Builder(DataSource dataSource, CheckpointCodec checkpointCodec) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            this.checkpointCodec = Objects.requireNonNull(checkpointCodec, "checkpointCodec");
        }

        /**
         * Runs durable and graph-engine Flyway migrations.
         *
         * @return this builder
         */
        public Builder migrateSchema() {
            GraphEngineStoreFactory.migrateSchema(dataSource);
            return this;
        }

        /**
         * Overrides the logical time source used by created stores.
         *
         * @param timeSource logical time source, or {@code null} for system time
         * @return this builder
         */
        public Builder timeSource(TimeSource timeSource) {
            this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
            return this;
        }

        /**
         * Overrides the shared {@link SqlSessionFactory}.
         *
         * @param sqlSessionFactory preconfigured session factory
         * @return this builder
         */
        public Builder sqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
            this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
            return this;
        }

        /**
         * Overrides the shared scoped session manager.
         *
         * @param sessionManager preconfigured scoped session manager
         * @return this builder
         */
        public Builder sessionManager(ScopedSqlSessionManager sessionManager) {
            this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
            return this;
        }

        /**
         * Creates a graph-definition metadata store.
         *
         * @return definition store
         */
        public GraphDefinitionStore graphDefinitionStore() {
            return new MybatisGraphDefinitionStore(resolveSessionManager(), checkpointCodec, timeSource);
        }

        /**
         * Creates a graph-version metadata store.
         *
         * @return version store
         */
        public GraphVersionStore graphVersionStore() {
            return new MybatisGraphVersionStore(resolveSessionManager(), checkpointCodec, timeSource);
        }

        /**
         * Creates a graph-deployment routing store.
         *
         * @return deployment store
         */
        public GraphDeploymentStore graphDeploymentStore() {
            return new MybatisGraphDeploymentStore(resolveSessionManager(), checkpointCodec, timeSource);
        }

        /**
         * Creates a graph-instance projection store.
         *
         * @return instance store
         */
        public GraphInstanceStore graphInstanceStore() {
            return new MybatisGraphInstanceStore(resolveSessionManager(), checkpointCodec, timeSource);
        }

        /**
         * Creates the aggregate store bundle used by the service layer.
         *
         * @return aggregate graph-engine stores
         */
        public GraphEngineStores graphEngineStores() {
            return new GraphEngineStores(
                    graphDefinitionStore(),
                    graphVersionStore(),
                    graphDeploymentStore(),
                    graphInstanceStore()
            );
        }

        private ScopedSqlSessionManager resolveSessionManager() {
            if (sessionManager != null) {
                return sessionManager;
            }
            if (sqlSessionFactory == null) {
                sqlSessionFactory = GraphEngineStoreFactory.createSqlSessionFactory(dataSource, checkpointCodec);
            }
            sessionManager = new ScopedSqlSessionManager(sqlSessionFactory);
            return sessionManager;
        }
    }
}
