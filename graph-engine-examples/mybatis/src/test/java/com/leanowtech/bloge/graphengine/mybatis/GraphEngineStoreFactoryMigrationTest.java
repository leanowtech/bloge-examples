package com.leanowtech.bloge.graphengine.mybatis;

import com.leanowtech.bloge.durable.mybatis.DurableStoreFactory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEngineStoreFactoryMigrationTest {

    @Test
    void migrateSchemaAppliesGraphEngineTablesAfterDurableSchemaAdvancesPastV18() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:graph_engine_migration_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

        DurableStoreFactory.migrateSchema(dataSource);
        GraphEngineStoreFactory.migrateSchema(dataSource);

        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, null, "GE_DEFINITION", null)) {
            assertTrue(tables.next(), "Expected graph-engine metadata tables to be created");
        }
    }
}
