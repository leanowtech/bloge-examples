# AGENT Guide For `bloge-examples`

This file is a practical operating guide for coding agents working in this repository.

## Scope

Repository root contains three standalone Java example projects:

1. `mono-examples/`
2. `graph-engine-examples/`
3. `resource-gateway-examples/`

Do not assume a single root Maven reactor for all three. Build each project independently.

## Environment Assumptions

- Java 25+
- Maven 3.9+
- BLOGE artifacts already installed from the main BLOGE repo (`bloge-*` dependencies)

## Build Commands

Run from repository root.

```bash
# Graph engine multi-module build
mvn -f graph-engine-examples/pom.xml clean install

# Mono examples
mvn -f mono-examples/pom.xml clean test

# Resource gateway
mvn -f resource-gateway-examples/pom.xml clean verify
```

## Fast Feedback Commands

```bash
# Single graph-engine module with dependencies
mvn -f graph-engine-examples/pom.xml -pl ai -am test

# Build graph-engine CLI only
mvn -f graph-engine-examples/pom.xml -pl cli -am package -DskipTests

# Run resource gateway locally
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

## Project-Specific Notes

### `graph-engine-examples/`

- Multi-module control-plane stack: `model`, `mybatis`, `ai`, `service`, `server`, `cli`.
- Keep module boundaries clear:
  - `model` for shared contracts/records.
  - `mybatis` for persistence adapter details.
  - `service` for orchestration APIs.
  - `server` for HTTP transport concerns.
  - `cli` for BPMN/DSL command-line workflows.

### `resource-gateway-examples/`

- Spring Boot API gateway with declarative graph orchestration.
- `HttpResourceOperator` is the core generic integration point; avoid introducing provider-specific duplicate operators unless required.
- Registry and descriptor behavior is part of the public example surface; preserve backward-compatible request/response semantics when possible.

### `mono-examples/`

- Large scenario catalog; changes should keep sample parity between Java API and DSL examples when both variants exist.
- Prefer additive example updates over mutating existing educational scenarios unless fixing correctness issues.

## Testing Expectations

When modifying code:

1. Run the narrowest project build first.
2. If module-scoped changes are made in `graph-engine-examples/`, run `-pl <module> -am` tests.
3. For `resource-gateway-examples/`, run `mvn -f resource-gateway-examples/pom.xml clean verify` when touching controllers/operators/interceptors.
4. For `mono-examples/`, run focused tests if available, then `clean test` for final verification.

If local dependencies are missing, report it clearly and list the exact missing artifact(s).

## Documentation Sync Rules

When behavior changes, update the nearest doc to code:

- `graph-engine-examples/README.md`
- `resource-gateway-examples/README.md`
- `mono-examples/README.md`
- `docs/ai/*.md` when AI syntax/prompt behavior changes

If root-level onboarding changes, also update `README.MD`.

## Safe Change Guidelines

- Keep edits minimal and scoped.
- Do not rewrite large README sections unless stale/inaccurate.
- Avoid renaming public endpoints, graph names, or module artifact IDs without explicit request.
- Preserve Java 25 build flags (`--enable-preview`) where already configured.

## Quick Triage Checklist

Before opening a PR or handing off:

1. Confirm changed files belong to one project boundary or a deliberate cross-project change.
2. Confirm corresponding Maven command succeeds (or capture failure reason).
3. Confirm docs for touched behavior are updated.
4. Summarize risks: compatibility, runtime config impact, and test coverage impact.
