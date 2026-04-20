package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.graphengine.mybatis.GraphEngineStoreFactory;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

/**
 * Shared H2 bootstrap for graph-engine MyBatis contract tests.
 */
class MybatisContractTestBase {
    static final CheckpointCodec CODEC = new JacksonCheckpointCodec();

    private DataSource dataSource;
    private ScopedSqlSessionManager sessionManager;

    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:graph_engine_store_test_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;TRACE_LEVEL_SYSTEM_OUT=0");
        dataSource = ds;
        GraphEngineStoreFactory.migrateSchema(dataSource);
        SqlSessionFactory factory = GraphEngineStoreFactory.createSqlSessionFactory(dataSource, CODEC);
        sessionManager = new ScopedSqlSessionManager(factory);
    }

    DataSource dataSource() {
        return dataSource;
    }

    ScopedSqlSessionManager sessionManager() {
        return sessionManager;
    }
}
