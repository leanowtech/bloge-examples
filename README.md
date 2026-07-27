# BLOGE Examples Workspace

This repository contains standalone example projects that demonstrate how to build on top of BLOGE runtime artifacts.

Each example family and the Resource Gateway client test kit are intentionally
isolated, with their own Maven build and dependency lifecycle.

## Projects

| Path | Purpose | Packaging |
|---|---|---|
| `mono-examples/` | Broad BLOGE scenario catalog (beginner flows, integration recipes, durable/session/state-machine patterns, BPMN conversion samples) | Single-module Maven project |
| `graph-engine-examples/` | Graph-engine control-plane stack (model, mybatis persistence, AI authoring, service facade, server, CLI) | Multi-module Maven reactor |
| `resource-gateway-examples/` | Spring Boot API resource gateway using DSL orchestration + generic `httpResource` operator | Single-module Maven Spring Boot app |
| `resource-gateway-test-kit/` | Standalone HTTP client, typed graph-catalog materialization, fixture/suite builders, signed bounded stability verification, JUnit 5 assertions/XML, governed-suite CI CLI, and offline exact-inventory rollout verification | Maven library + executable CLI JAR |
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

# resource gateway test kit
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

## Run Key Examples

### Start/Stop Visual Servers

From the repository root, use the helper scripts for the two browser-backed
Spring Boot examples:

```bash
./scripts/start-examples.sh
./scripts/stop-examples.sh
```

By default, the scripts start Graph Engine on port `8080` and Resource Gateway
on port `8081` so both visual consoles can run at the same time:

- Graph Engine: `http://localhost:8080/console`
- Resource Gateway: `http://localhost:8081/examples/gateway`

Run only one service when needed:

```bash
./scripts/start-examples.sh graph-engine
./scripts/start-examples.sh resource-gateway
./scripts/stop-examples.sh graph-engine
./scripts/stop-examples.sh resource-gateway
```

Override ports with environment variables:

```bash
GRAPH_ENGINE_PORT=18080 RESOURCE_GATEWAY_PORT=18081 ./scripts/start-examples.sh
```

PID files are written to `target/example-pids/`, and logs are written to
`target/example-logs/`. The start script runs Graph Engine through its Maven
`spring-boot:run` target and runs Resource Gateway from its repackaged Spring
Boot jar.

For the newer BLOGE visual canvas product demo, use the dedicated Resource
Gateway script. It builds the React `/author/` and `/showcase/` bundle by
default, starts the service on port `8080` with the `test` profile, and prints
the demo URLs. This exposes the isolated `/api/testing/**` control plane without
enabling it in production:

```bash
./scripts/start-visual-canvas-demo.sh
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
./scripts/stop-visual-canvas-demo.sh
```

Common demo options:

```bash
./scripts/start-visual-canvas-demo.sh --open
./scripts/start-visual-canvas-demo.sh --port 18080
./scripts/start-visual-canvas-demo.sh --no-build
./scripts/start-visual-canvas-demo.sh --profile staging
./scripts/start-visual-canvas-demo.sh --profile production
./scripts/start-visual-canvas-demo.sh --shadow-jobs
./scripts/start-visual-canvas-demo.sh --shadow-scheduler
```

Startup waits for `GET /api/integration/capabilities`, then prints the canvas,
showcase, capability probe, correctness-workbook, and gate-feedback entry points.
`--shadow-jobs` enables the protected durable Shadow queue/lifecycle API;
`--shadow-scheduler` additionally starts bounded pollers while honestly leaving
worker/serving readiness false until a trusted data-plane connector is installed.
Runtime logs and PID files remain under `target/example-logs/` and
`target/example-pids/`.

### Graph Engine Server

```bash
mvn -f graph-engine-examples/pom.xml -pl server spring-boot:run
```

Open the browser console:

```text
http://localhost:8080/console
```

The console visualizes definitions, versions, deployments, instances, operators,
tasks, workers, dead letters, DSL validation, draft generation, and version
diffs using the existing `/api/v1` control-plane APIs.

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
|- resource-gateway-test-kit/
|- AGENTS.md
|- README.md
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
- `resource-gateway-examples/README.md` for the focused Resource Gateway overview
  and `resource-gateway-examples/REFERENCE.md` for the full API/test reference
- `docs/resource-gateway-test-kit-design-and-user-guide.md` for the Test Kit
  architecture, five-minute Java path, CI usage, trust boundaries, and troubleshooting;
  `resource-gateway-test-kit/README.md` remains the exhaustive API and protocol reference
- `docs/bloge-visual-canvas-product-and-system-guide.md` for the visual canvas
  product guide, demo scripts, and system usage notes
- `docs/bloge-vscode-extension-lightweight-authoring-plan.md` for the lighter
  VSCode extension direction that can visualize and mock-test BLOGE graphs
  without starting the Resource Gateway service
- `mono-examples/README.md` for the full example catalog
- `docs/ai/README.md` for AI prompt and DSL benchmark usage
- `docs/example-ux-visualization-evolution.md` for the example UX visualization
  evolution plan across graph-engine and resource-gateway
