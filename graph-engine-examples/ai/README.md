# bloge-graph-engine-ai

> This module is part of the
> [standalone graph-engine project](../README.md) and is built with Java 25
> outside the root bloge reactor.

AI authoring pipeline for BLOGE DSL — prompt assembly, LLM-backed generation, DSL validation, and structured repair.

## Core Classes

Under `com.leanowtech.bloge.graphengine.ai`:

- **`GraphAuthoringService`** — end-to-end generate→validate→repair loop. Takes an `LlmProvider`, `PromptContextBuilder`, and `DslValidationPipeline`. `generate(GraphAuthoringRequest)` returns `GraphAuthoringResult`; `validate(String)` validates DSL without calling the LLM.
- **`GraphAuthoringRequest`** — record: `naturalLanguageRequest`, `model`, `fewShotExampleCount` (0–5, default 3), `maxRepairRounds` (0–2, default 2), `temperature`, `maxTokens`
- **`GraphAuthoringResult`** — record: `naturalLanguageRequest`, `model`, `dslSource`, `validation` (`DslValidationResult`), `attempts`, `selectedExampleTitles`, `operatorCatalogSize`, `repaired`
- **`GraphAuthoringAttempt`** — record: `phase` (`GENERATE`/`REPAIR`), `round`, `dslSource`, `finishReason`, `promptTokens`, `completionTokens`, `validation`
- **`GraphAuthoringPhase`** — enum: `GENERATE`, `REPAIR`
- **`GraphAuthoringException`** — runtime exception for non-validation failures

## Prompt Assembly (`prompt` subpackage)

- **`PromptResourceLoader`** — loads classpath resources like `ai/bloge-dsl-syntax-reference.md` and `ai/few-shot-examples.md`
- **`PromptContextBuilder`** — assembles syntax reference, operator catalog, and few-shot examples into a system prompt via `SystemPromptRenderer`
- **`SystemPromptRenderer`** — renders the system prompt XML structure with `<bloge-syntax-reference>`, `<available-operators>`, `<few-shot-examples>` sections
- **`OperatorCatalogBuilder`** — builds operator catalog entries from the `OperatorRegistry`
- **`FewShotExampleSelector`** — parses and selects few-shot examples from the bundled markdown

## Validation (`validate` subpackage)

- **`DslValidationPipeline`** — parse → lint → compile pipeline using `bloge-dsl` compilers and `bloge-lint` `QualityScorer`/`LintRunner`. Supports `GRAPH`, `SESSION`, `STATE_MACHINE` execution modes. Uses `LENIENT` compilation mode. Builder pattern with optional stores.
- **`DslValidationResult`** — record: `dslSource`, `executionMode`, `declaredRootName`, `diagnostics`, `qualityScore`, `valid`
- **`DslDiagnostic`** — record: `stage` (`PARSE`/`LINT`/`COMPILE`/`SERVICE`), `code`, `severity` (`ERROR`/`WARNING`/`INFO`), `message`, `nodeId`, `field`, `line`, `column`
- **`QualityScoreSummary`** — quality score summary

## Repair (`repair` subpackage)

- **`DslSourceNormalizer`** — strips markdown fences and normalizes LLM output
- **`RepairPromptBuilder`** — builds generation and repair prompts with diagnostic context

## Prompt Resource Packaging

The `pom.xml` includes a `<resources>` block that copies `docs/ai/*.md` from
the repository root into the classpath under `ai/`. Because this module now
lives under `bloge-examples-graph-engine/ai/`, the resource directory
is configured as `${project.basedir}/../../docs/ai`. At runtime
`PromptResourceLoader.loadRequired("ai/bloge-dsl-syntax-reference.md")` reads
them from the JAR.

## Server Integration

`bloge-graph-engine-server` wires this module into `/api/v1/ai`:

- `POST /api/v1/ai/validate` calls `DslValidationPipeline` directly and works even when no `LlmProvider` is configured
- `POST /api/v1/ai/generate` delegates to `GraphAuthoringService` and remains draft-only: callers receive the generated DSL plus validation metadata, but no `GraphVersion` is persisted automatically

## Dependencies

- **compile**: `bloge-graph-engine-model`, `bloge-core`, `bloge-dsl`, `bloge-lint`, `bloge-common-operators`, `bloge-session-ext`, `bloge-state-ext`
- **test**: JUnit 5, `bloge-durable`, `bloge-spring`

## Build and Test

```bash
cd bloge-examples-graph-engine
mvn -pl ai -am test
```

Install the root BLOGE artifacts first; see the
[standalone project README](../README.md#prerequisites).

Test classes: `GraphAuthoringServiceTest`, `FewShotExampleSelectorTest`, `OperatorCatalogBuilderTest`, `PromptContextBuilderTest`, `DslValidationPipelineTest`
