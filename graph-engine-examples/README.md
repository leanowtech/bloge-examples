# BLOGE Graph Engine — Standalone Project

Standalone multi-module build for the BLOGE graph-engine control-plane modules.
These modules implement the product-layer governance model, persistence backend,
service facade, AI authoring pipeline, and Spring Boot HTTP server that sit on
top of the core BLOGE runtime.

> **Standalone project** — this project is intentionally **not** part of the
> root bloge Maven reactor. It consumes published `bloge-*` artifacts from your
> local Maven repository and builds independently with Java 25.

## Modules

| Module | Description |
|---|---|
| **model** | Publishes `bloge-graph-engine-model`: product-layer domain records, metadata store contracts, and in-memory store implementations |
| **mybatis** | Publishes `bloge-graph-engine-mybatis`: MyBatis persistence backend for the `ge_*` metadata tables plus `GraphEngineStoreFactory` |
| **ai** | Publishes `bloge-graph-engine-ai`: AI authoring pipeline for prompt assembly, DSL generation, validation, and structured repair |
| **service** | Publishes `bloge-graph-engine-service`: control-plane service facade for authoring, publish, deployment, instance/task orchestration, and remote workers |
| **server** | Publishes `bloge-graph-engine-server`: Spring Boot HTTP server exposing the graph-engine service facade under `/api/v1` |
| **cli** | Publishes `bloge-graph-engine-cli`: standalone BPMN conversion and validation CLI packaged as a runnable fat jar |

## Builder scope notes

This standalone project intentionally keeps a lighter builder contract than the core BLOGE
libraries. `DslValidationPipeline.Builder` and `GraphEngineRuntimeSupport.Builder` remain local
example/control-plane builders without `toBuilder()` because their callers live inside this
standalone project. `GraphEngineStoreFactory.Builder` is also intentionally a fluent factory,
not a `build()`-terminating value builder.

If these types later graduate into reusable public libraries, reevaluate them under the same
builder ergonomics used in `bloge-core`, `bloge-dsl`, and the runtime extension modules.

## Prerequisites

- **Java 25+**
- **Maven 3.9+**
- Root BLOGE artifacts installed into your local Maven repository first

Install the root BLOGE artifacts from the repository root:

```bash
mvn install
```

## Build

Build the standalone project from the repository root:

```bash
mvn -f graph-engine-examples/pom.xml clean install
```

Build a single module from the standalone project root:

```bash
mvn -f graph-engine-examples/pom.xml -pl ai -am test
```

Package the standalone CLI fat jar:

```bash
mvn -f graph-engine-examples/pom.xml -pl cli -am package -DskipTests
```

## Run the CLI

The CLI converts and validates BPMN diagrams without starting a server.
See [`cli/README.md`](cli/README.md) for the full option reference.

```bash
# convert a single file to stdout
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert order-process.bpmn

# batch-convert a directory (writes .bloge siblings)
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert src/bpmn/

# validate without producing output
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar validate --strict src/bpmn/
```

## Run the server

```bash
mvn -f graph-engine-examples/pom.xml -pl server spring-boot:run
```

The server includes a static browser console at `http://localhost:8080/console`.
It consumes the existing `/api/v1` definition, version, deployment, instance,
operator, task, worker, dead-letter, diagram, node-state, context, audit,
transition, event, AI validation/generation, and version-diff endpoints.

The standalone server now defaults `spring.bloge.durable.mode=local`, so it boots with an
embedded volatile H2 durable store for local development instead of requiring an external
database first. Keep that preset for local verification only.

Instance event SSE (`GET /api/v1/instances/{id}/events`) depends on the durable
execution event journal. Enable it when starting the server:

```bash
mvn -f graph-engine-examples/pom.xml -pl server spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.bloge.event-journal.enabled=true"
```

## Why standalone?

The graph-engine control plane has its own Spring Boot, Flyway, MyBatis,
Jackson, and Micrometer lifecycle. Keeping it outside the root reactor avoids
coupling application-stack dependency management to the core reactor while
still showing how downstream products consume BLOGE as a library.

## Prompt resource packaging

`bloge-graph-engine-ai` packages `docs/ai/*.md` from the repository root onto
the classpath under `ai/`. Because the module lives under
`graph-engine-examples/ai/`, its resource directory is configured as
`${project.basedir}/../../docs/ai`.
