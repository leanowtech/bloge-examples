# BLOGE Examples Workspace

This repository contains standalone example projects that demonstrate how to build on top of BLOGE runtime artifacts.

Each example family is intentionally isolated, with its own Maven build and dependency lifecycle.

## Projects

| Path | Purpose | Packaging |
|---|---|---|
| `mono-examples/` | Broad BLOGE scenario catalog (beginner flows, integration recipes, durable/session/state-machine patterns, BPMN conversion samples) | Single-module Maven project |
| `graph-engine-examples/` | Graph-engine control-plane stack (model, mybatis persistence, AI authoring, service facade, server, CLI) | Multi-module Maven reactor |
| `resource-gateway-examples/` | Spring Boot API resource gateway using DSL orchestration + generic `httpResource` operator | Single-module Maven Spring Boot app |
| `docs/ai/` | AI prompt assets and DSL references used by graph-engine AI module | Markdown docs packaged as resources |

## Requirements

- Java 25+
- Maven 3.9+
- BLOGE core artifacts installed in your local Maven repository

## Build And Test

Build each standalone project from this repository root:

```bash
# graph-engine examples (all modules)
mvn -f graph-engine-examples/pom.xml clean install

# mono examples
mvn -f mono-examples/pom.xml clean test

# resource gateway examples
mvn -f resource-gateway-examples/pom.xml clean verify
```

## Run Key Examples

### Graph Engine Server

```bash
mvn -f graph-engine-examples/pom.xml -pl server spring-boot:run
```

Enable instance event SSE journal for local testing:

```bash
mvn -f graph-engine-examples/pom.xml -pl server spring-boot:run \
	-Dspring-boot.run.jvmArguments="-Dspring.bloge.event-journal.enabled=true"
```

### Graph Engine CLI

```bash
mvn -f graph-engine-examples/pom.xml -pl cli -am package -DskipTests
java -jar graph-engine-examples/cli/target/bloge-graph-engine-cli-1.0.0.jar --help
```

### Resource Gateway

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

Local endpoints:

- `http://localhost:8080/api/gateway/dashboard/{userId}`
- `http://localhost:8080/api/gateway/resources/execute`
- `http://localhost:8080/api/gateway/ai/search/stream?q=...`
- `http://localhost:8080/admin/resources`
- `http://localhost:8080/examples/gateway`

## Repository Layout

```text
bloge-examples/
|- docs/
|  |- ai/
|- graph-engine-examples/
|  |- ai/
|  |- cli/
|  |- model/
|  |- mybatis/
|  |- service/
|  |- server/
|- mono-examples/
|- resource-gateway-examples/
|- AGENTS.MD
|- README.MD
```

## Version Notes

- All three example families currently target Java 25.
- Current BLOGE dependency line in this workspace is `0.8.3-RC3`.
- Spring Boot versions differ by project:
	- `graph-engine-examples`: `3.5.13`
	- `resource-gateway-examples`: `3.5.13`
	- `mono-examples`: `3.4.2`

## Additional Documentation

- `graph-engine-examples/README.md` for module-by-module graph-engine details
- `resource-gateway-examples/README.md` for API contract and test-layer breakdown
- `mono-examples/README.md` for the full example catalog
- `docs/ai/README.md` for AI prompt and DSL benchmark usage
- `docs/example-ux-visualization-evolution.md` for the example UX visualization
  evolution plan across graph-engine and resource-gateway
