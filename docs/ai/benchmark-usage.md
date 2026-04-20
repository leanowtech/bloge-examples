# AI Quality Benchmark Usage

How to evaluate LLM-generated `.bloge` files against the live parser and lint stack.

## Quick Start

```bash
# Benchmark the default 10-workflow corpus
./benchmarks/ai-quality-bench.sh

# Score a single generated file
./benchmarks/ai-quality-bench.sh path/to/generated.bloge

# Score a directory of generated files
./benchmarks/ai-quality-bench.sh path/to/output/
```

## How Scoring Works

The benchmark uses `QualityScorer` from the `bloge-lint` module. It:

1. **Parses** the source through the real `Lexer` → `Parser` pipeline
2. **Runs all 14 built-in lint rules** (cycle detection, duplicate IDs, missing timeouts, etc.)
3. **Reports the plan metrics**: parse success rate and lint compliance rate
4. **Evaluates 6 quality dimensions** and produces a weighted score (0–100) for debugging feedback

### Score Dimensions

| Dimension | Weight | Criteria |
|-----------|--------|----------|
| `parseable` | 40 | Source parses without errors |
| `noErrors` | 20 | No ERROR-severity lint diagnostics |
| `noWarnings` | 10 | No WARNING-severity lint diagnostics |
| `documented` | 10 | Graph-level `///` doc comment present |
| `hasTimeouts` | 10 | All operator nodes declare `timeout` |
| `structureDepth` | 10 | ≥ 2 nodes + at least one dependency, branch, transform, foreach, or loop |

### Plan Acceptance Thresholds

For the default 10-workflow benchmark corpus, the evolution plan acceptance criteria are:

- **Parse success rate ≥ 90%**
- **Lint compliance rate ≥ 80%**

`Lint compliance` means the file parses and produces no ERROR-severity lint diagnostics.

The weighted score remains useful for comparing two generated files that both parse and lint cleanly, but it is not the primary pass/fail gate for the plan.

## Java API

```java
import com.leanowtech.bloge.lint.QualityScorer;
import com.leanowtech.bloge.lint.QualityScore;

QualityScorer scorer = new QualityScorer();

// Score source string
QualityScore score = scorer.scoreSource(blogeSource);
System.out.println("Score: " + score.total() + "/100");
System.out.println("Production quality: " + score.isProductionQuality());
score.dimensions().forEach((dim, pts) ->
    System.out.println("  " + dim + ": " + pts));

// Score a file
QualityScore fileScore = scorer.scoreFile(Path.of("generated.bloge"));

// Score all files in a directory
Map<Path, QualityScore> results = scorer.scoreDirectory(Path.of("output/"));
```

## Running Tests

```bash
# All bloge-lint tests including scorer
mvn -pl bloge-lint -am test

# Only scorer tests
mvn -pl bloge-lint test -Dtest=QualityScorerTest
```

## Default Corpus

The default corpus intentionally uses 10 workflow-shaped programs that cover the constructs called out in plan §6.1:

- basic DAG
- branch
- foreach
- loop
- session
- state_machine
- inclusive branch
- error-boundary replacement
- interruptible replacement

You can also pass your own file or directory to benchmark a custom generation batch.

## Interpreting Results

The shell script prints a summary like:

```
=== AI Quality Benchmark ===
  order-process.bloge                  90/100  ✓ PASS  parse=true lint=true
  generated/bloge-a.bloge              60/100  ✗ FAIL  parse=true lint=false
  generated/bloge-b.bloge               0/100  ✗ FAIL  parse=false lint=false

Summary: 1/3 parse+lints clean, average score: 50
Plan metrics: parse success 2/3 (66%), lint compliance 1/3 (33%)
```

## Integration with LLM Pipelines

1. Generate `.bloge` source via an LLM using the
   [syntax reference](bloge-dsl-syntax-reference.md) and
   [few-shot examples](few-shot-examples.md)
2. Write the output to a file
3. Score it: `./benchmarks/ai-quality-bench.sh output.bloge`
4. If parse or lint compliance fails, feed the diagnostics back to the LLM for correction
5. Use the weighted score to compare compliant candidates and choose the stronger one

The scorer diagnostics include rule IDs and messages that can be
directly included in a retry prompt for self-correction.
