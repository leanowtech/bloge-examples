# BLOGE Graph Engine MyBatis

> This module is part of the
> [standalone graph-engine project](../README.md) and is built with Java 25
> outside the root bloge reactor.

`bloge-graph-engine-mybatis` is the JDBC/MyBatis persistence backend for the
product-layer graph-engine metadata model.

It adds four mapper-backed stores for the new control-plane tables:

- `ge_definition`
- `ge_version`
- `ge_deployment`
- `ge_instance`

The module deliberately **does not** replace the durable runtime substrate.
Runtime execution state, checkpoints, waits, work items, task inboxes, and
audit records continue to live in the existing `bd_*` tables managed by
`bloge-durable-mybatis`.

## What it provides

- `GraphEngineStoreFactory` for creating a `SqlSessionFactory`,
  `ScopedSqlSessionManager`, and typed `GraphEngineStores`
- Flyway migrations under `db/migration/graph-engine/{dialect}`
- MyBatis-backed implementations of:
  - `GraphDefinitionStore`
  - `GraphVersionStore`
  - `GraphDeploymentStore`
  - `GraphInstanceStore`
- H2-backed contract tests that reuse the store test-jar exported by
  `bloge-graph-engine-model`

## Typical usage

```java
CheckpointCodec codec = new JacksonCheckpointCodec();

GraphEngineStores stores = GraphEngineStoreFactory.builder(dataSource, codec)
        .migrateSchema()
        .graphEngineStores();
```

Use this module together with `DurableStoreFactory` when the product-layer
service also needs the existing durable runtime stores.
