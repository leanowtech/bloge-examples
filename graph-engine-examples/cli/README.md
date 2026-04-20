# BLOGE Graph Engine CLI

Standalone command-line tool for converting and validating BPMN diagrams
against the BLOGE BPMN transformer pipeline. Packaged as a runnable fat jar
with no runtime dependencies beyond a Java 25+ JVM.

## Build

From the repository root:

```bash
mvn -f bloge-examples-graph-engine/pom.xml -pl cli -am package -DskipTests
```

The shaded jar is written to `cli/target/bloge-graph-engine-cli-1.0.0.jar`.

## Commands

### `convert`

Converts one or more BPMN files (XML or JSON) into Bloge DSL.

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert [options] <input...>
```

**Single file to stdout:**

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert order-process.bpmn
```

**Single file to an explicit output path:**

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert --output order-process.bloge order-process.bpmn
```

**Batch — directory input writes `.bloge` siblings:**

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert src/bpmn/
```

**Batch — write to an output directory preserving subdirectory structure:**

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert --output out/ src/bpmn/
```

### `validate`

Parses and validates one or more BPMN files, printing diagnostics to stderr
without producing DSL output.

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar validate order-process.bpmn
```

`validate` does not accept `--output`.

## Options

| Option | Default | Description |
|---|---|---|
| `--format <xml\|json>` | Auto-detect by extension (`.json` → JSON, everything else → XML) | Force the input format |
| `--output <file\|dir>` | stdout (single file) or `<input>.bloge` siblings (batch) | Output path for `convert` |
| `--mapping <json-file>` | See [Operator mapping](#operator-mapping) | Custom operator mapping config |
| `--strict` | off | Treat WARN diagnostics as errors (exit 1) |
| `--no-source-comments` | off | Omit BPMN source-mapping comments from DSL output |
| `--no-doc-comments` | off | Omit BPMN `<documentation>` comments from DSL output |
| `--expression-mode <mode>` | `auto` | Expression translation mode: `auto`, `manual`, or `hybrid` |

Pass `--help`, `-h`, or `help` at any position to print usage.

## Output path behavior

| Input shape | `--output` absent | `--output <file>` | `--output <dir>` |
|---|---|---|---|
| Single file | stdout | Write to that file | Write `<file>.bloge` inside the directory |
| Multiple files or directory | `<input>.bloge` siblings next to each source | *(error — batch requires a directory)* | Preserve subdirectory layout under output directory |

## Operator mapping

The CLI loads operator mappings in layers:

1. **Bundled JSON defaults** — when the resolved format is JSON the built-in
   `bpmn-json-import-defaults.json` resource is loaded automatically.
2. **Convention file** — if `./bpmn-operator-mapping.json` exists in the
   working directory it is merged on top of the bundled defaults.
3. **Explicit `--mapping`** — when provided, the given file is merged on top
   instead of the convention file.

Explicit/convention rules take priority over bundled defaults; within each
layer, rules listed first win.

## Diagnostics

Both commands print diagnostics to stderr in the format:

```
<elementId>: [SEVERITY] CODE - message (suggestion: ...)
```

Process-level diagnostics use `<process>` as the element id.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success — no errors |
| `1` | Translation or validation errors found |
| `2` | Tool error — bad arguments, missing file, or parse failure |

When processing multiple files the highest exit code wins.

## Supported input extensions

Directory traversal picks up files matching `*.bpmn`, `*.xml`, and `*.json`.

## Examples

Convert a JSON-format BPMN export with a custom mapping and no source comments:

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar convert \
  --format json \
  --mapping custom-mapping.json \
  --no-source-comments \
  export.json
```

Validate a directory of BPMN files in strict mode:

```bash
java -jar cli/target/bloge-graph-engine-cli-1.0.0.jar validate --strict src/bpmn/
```
