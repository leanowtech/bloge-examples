# BLOGE AI Documentation

Machine-readable reference documentation and quality tooling for AI/LLM-assisted `.bloge` code generation.

## Purpose

These files are optimized for inclusion in LLM system prompts and context windows.
They contain no marketing prose — only syntax rules, patterns, and runnable examples
that an AI code-generation model needs to produce valid `.bloge` source.

## File Index

| File | Description |
|------|-------------|
| [`bloge-dsl-syntax-reference.md`](bloge-dsl-syntax-reference.md) | Concise, structured DSL syntax reference — every construct, operator, and expression form with minimal examples |
| [`few-shot-examples.md`](few-shot-examples.md) | Curated, complete `.bloge` programs for few-shot prompting — examples from basic DAGs through sessions and state machines |
| [`benchmark-usage.md`](benchmark-usage.md) | How to run the AI quality benchmark and interpret parse/lint plan metrics |

## How to Use

### In an LLM System Prompt

Include `bloge-dsl-syntax-reference.md` as the primary syntax reference.
Add 2–3 relevant examples from `few-shot-examples.md` matching the target use-case.

```
You are a BLOGE DSL expert. Generate valid .bloge workflow definitions.

<bloge-syntax-reference>
{contents of bloge-dsl-syntax-reference.md}
</bloge-syntax-reference>

<examples>
{selected examples from few-shot-examples.md}
</examples>
```

### For Few-Shot Prompting

Pick the example category closest to the desired output and include it verbatim
as a user/assistant turn before the generation request.

### Quality Validation

After generating `.bloge` code, run the AI quality benchmark to validate it:

```bash
./benchmarks/ai-quality-bench.sh                          # benchmark the default 10-workflow corpus
./benchmarks/ai-quality-bench.sh path/to/generated.bloge  # score a specific file
```

For the default corpus, the plan target is parse success ≥ 90% and lint compliance ≥ 80%.
The weighted quality score is still printed as a per-file debugging aid. See [`benchmark-usage.md`](benchmark-usage.md).

### Programmatic Scoring (Java API)

```java
import com.leanowtech.bloge.lint.QualityScorer;
import com.leanowtech.bloge.lint.QualityScore;

QualityScorer scorer = new QualityScorer();
QualityScore result = scorer.scoreSource(generatedBlogeSource);
System.out.println(result.total() + "/100 — " +
    (result.isProductionQuality() ? "PASS" : "FAIL"));
```

## LlmProvider SPI Capabilities

The `LlmProvider` contract used by BLOGE AI operators and `GraphAuthoringService`
supports three capability tiers:

| Capability | SPI Types | Notes |
|---|---|---|
| **Tool calling** | `ToolDefinition`, `ToolCall`, `toolChoice` | Enables reason → call → observe loops; tool results are fed back with `LlmMessage.tool(...)` |
| **Structured output** | `ResponseFormat.Text`, `JsonObject`, `JsonSchema(name, schema, strict)` | Lets providers guarantee machine-readable JSON instead of relying only on prompt wording |
| **Multimodal messages** | `LlmMessage.withParts(...)`, `LlmProvider.ContentPart` (`TextPart`, `ImagePart`, `AudioPart`, `FilePart`) | Carries image/audio/file context in the same provider-neutral message model |

The original `LlmMessage(role, content)` and `LlmRequest(model, messages)` constructors
remain available, so older providers can ignore the new optional fields and continue
to compile unchanged.

## Keeping in Sync

These docs are derived from the canonical DSL specification at
[`docs/bloge-dsl-specification.md`](../bloge-dsl-specification.md).
When the spec changes, update these files accordingly.
The quality benchmark uses the live parser and lint rules from `bloge-dsl` and
`bloge-lint`, so it always reflects the current language version.

Verification after changes:
```bash
./benchmarks/ai-quality-bench.sh          # parse ≥ 90%, lint ≥ 80% on the default corpus
mvn -pl bloge-lint -am test               # scorer and lint tests pass
```

## Java Prompt/Validation Module

The `bloge-graph-engine-ai` module, now housed under the standalone
`bloge-examples-graph-engine/` project, provides the Java-side
orchestration that consumes these documentation files at runtime:

- **Prompt packaging** — the module's `pom.xml` copies `docs/ai/*.md` from the
  repository root onto the classpath under `ai/`. Because the module now lives
  under `bloge-examples-graph-engine/ai/`, the
  resource directory is configured as `${project.basedir}/../../docs/ai`.
  `PromptResourceLoader.loadRequired("ai/bloge-dsl-syntax-reference.md")`
  loads them from the JAR.
- **Prompt assembly** — `PromptContextBuilder` combines the syntax reference,
  an operator catalog built from `OperatorRegistry`, and selected few-shot
  examples into a system prompt rendered by `SystemPromptRenderer`.
- **Validation** — `DslValidationPipeline` runs the same parse → lint → compile
  chain used by the product layer, plus `QualityScorer` from `bloge-lint`.
- **Generation and repair** — `GraphAuthoringService` calls an `LlmProvider`,
  validates the returned DSL, and retries with structured repair prompts (up to
  two rounds). The same SPI now supports tool calling, structured output, and
  multimodal content, so authoring workflows can evolve without changing the
  operator-facing contract.

When updating these docs, rebuild the AI module to verify resource loading:

```bash
cd bloge-examples-graph-engine
mvn -pl ai -am test
```
