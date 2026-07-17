# Stage 5 Pure-DSL Mutation Plan Verification

## 1. Claim

This increment publishes a deterministic, bounded, content-addressed mutation **authoring plan** for
one exact Resource Gateway graph. It proves that every published mutant can be regenerated from the
current recoverable BLOGE DSL AST and independently compiled against the runtime operator registry.

It does not execute a mutant, classify it as killed or survived, detect semantic equivalence,
calculate a score, sign evidence, or make a promotion decision.

## 2. Root Cause And Boundary

The earlier Stage 5 property path could vary input while keeping the graph fixed. Mutation testing
requires the inverse coordinate: keep the reviewed test assets fixed while varying orchestration
semantics. A case count cannot be used as the denominator because uncompiled, duplicate,
unsupported, and equivalent mutants have different meanings.

The existing BLOGE recoverable definition is the authority. Resource Gateway does not maintain a
second DSL source catalog. BLOGE's generic codec did not reconstruct every nested `Set<T>` in a
record AST, so Resource Gateway adds a fail-closed decoder restricted to DSL AST records and the two
exact core enum types referenced by them. Payload size is capped at 1 MiB, recursion at 128, unknown
class tags and partially typed member trees are rejected.

## 3. Protocol Closure

`bloge.testMutationCasePlan.v1` binds:

- exact graph target fingerprint;
- recoverable source format and source fingerprint;
- independently derived baseline graph-artifact fingerprint;
- complete plan fingerprint;
- planner generation, maximum mutant count and compiler proof mode;
- ordered mutant id, kind, AST path, source coordinate and source/artifact/target fingerprints;
- explicit `UNKNOWN` equivalence classification and stable planning gaps.

The service first recompiles the baseline and requires both graph-artifact identity and the complete
resource-dependency-bound target identity to match. It then independently recompiles every candidate.
The response omits source text, business literals, fixture values and external request data.

## 4. Supported Mutations

Generation one supports nine orchestration mutations:

1. toggle exclusive/inclusive branch mode;
2. redirect a branch case to a sibling target;
3. replace an otherwise target with an explicit case target;
4. negate a decision-table condition;
5. swap adjacent FIRST-hit decision rules;
6. relax UNIQUE/ANY hit policy to FIRST;
7. swap adjacent transform bindings;
8. remove a fallback;
9. decrement a positive retry attempt count.

The planner never rewrites `operatorRef`, operator implementation, operator input binding, fixture,
payload, or external request. Imported graphs, extension semantics and nested foreach/loop/parallel
mutation are reported as gaps instead of being approximated.

## 5. Fail-Closed Outcomes

| Outcome | Meaning |
| --- | --- |
| `GENERATED` | At least one independently compiling mutant and no known planning gap |
| `PARTIAL` | At least one usable mutant plus an explicit unsupported, rejected, duplicate or bounded site |
| `UNAVAILABLE` | No safely reproducible mutant; an explicit source or verification gap is present |

The protocol limits a plan to 128 mutants and 512 payload-free gaps. The capability probe keeps
planning, execution and score evidence separate. Only `pureDslMutationPlanning` is enabled by this
increment.

## 6. Verification Matrix

| Risk | Executable proof |
| --- | --- |
| nondeterministic ordering or identity | same exact request produces an equal ordered plan and fingerprint |
| target/source drift | tampered source fails baseline artifact/target matching |
| external operator corruption | external operator graph yields only retry/fallback/transform mutations |
| invalid mutant publication | every candidate is independently compiled before inclusion |
| unbounded generation | caller and schema enforce 1..128; truncation becomes `PARTIAL` |
| arbitrary class construction | hostile tagged `java.lang.ProcessBuilder` payload is rejected |
| protocol drift | server schema/version tests and independent test-kit schema validation share the packaged Schema |
| false evidence claim | capability fixes mutation execution and score evidence to false |

## 7. Verification Commands

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

The final focused Resource Gateway path executed 30 tests with zero failures, errors or skips, covering the
planner, restricted decoder, controller, capability, real Spring/H2 HTTP and schema contract. The
independent focused test-kit path executed 34 tests before the final negative-schema case was added.

The final Resource Gateway `clean verify` executed 2398 tests with zero failures or errors and two
existing conditional skips; real-browser regression and executable Spring Boot JAR packaging passed.
The independent test-kit `clean verify` executed 96 tests with zero failures, errors or skips; packaged
Schema validation, ordinary and uber JARs, and strict public Javadoc passed.

## 8. Remaining Work

The next increment must define immutable mutation-suite materialization, exact mutant regeneration,
execution isolation, baseline/test closure reuse, killed/survived/inconclusive outcomes, timeout and
partial-run semantics, equivalent-mutant policy, score denominator, signed aggregate evidence and
publish-gate consumption. Flaky analysis, statistical confidence, cross-process scheduling and
physical test-runtime isolation remain separate Stage 5 workstreams.
