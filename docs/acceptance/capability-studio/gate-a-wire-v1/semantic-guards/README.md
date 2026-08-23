# Gate A semantic guards

JSON Schema is the structural authority: it freezes object shape, fixed slot identity and order, field type, closed vocabulary, and terminal/reason discriminants. It deliberately does not act as a calculator or trust engine.

`guard-catalog-v1.json` is the design authority for checks that require projection, bytes, time, process observation, cross-document closure, or caller-owned trust. Every guard has exactly one execution owner and one fixed A2 slot to receive its `FAIL`, `MISSING`, or `UNAVAILABLE` effect. The two externally visible `mandatoryGuards` remain Provider collision and review-count consistency; other semantic checks map to an existing requirement, artifact, test, or trusted-review slot, so the Gate A denominator cannot grow accidentally.

`authority-matrix-v1.json` assigns each repeated fact to exactly one authority class and source. Result fields are projections unless the matrix explicitly names the object as authority. Implementations must build an internal Observation Ledger from pinned expectations and caller observations, derive Guard outcomes once per trust domain, and only then render a wire result.

`guard-catalog-v1.json` also freezes the exact ordered `sourceFactIds` for every Guard. The list must be a duplicate-free subsequence of the Authority Matrix order and must never contain `ADMISSION_DECISION`: an admission decision is an output of A2, not an input that can justify itself. The validator compares all four A2 fixtures against the Catalog item by item (`guardId`, `admissionTarget`, and `sourceFactIds`) and checks that every Guard is evaluated.

`observationRefs` are deliberately not URI-enumerated in JSON Schema. Their Semantic Guard contract is dynamic: each fixture may carry the observation URI appropriate to its run, but refs must be canonical sorted and unique. This keeps evidence relocatable without weakening lineage checks.

`semantic-guard-vectors-v1.json` contains attacks that are intentionally valid against JSON Schema but must be rejected by the named semantic guard. Vectors are patches over canonical fixtures rather than duplicated evidence documents. This keeps structural validation and trust validation visibly separate.

`collector-contract-vectors-v1.json` is deliberately weaker: normalized `present / available / matches` observations verify every reducer path, fixed admission target, and `UNAVAILABLE > FAIL > OPEN > PASS` priority. They are unit vectors for the collector-to-reducer boundary, not security attack evidence, and do not count toward D0 material-attack coverage. The real material attack pack must prove that byte, path, process, signature and time collectors derive these observations from mutated files; production may never accept normalized vector input.

Run from the repository root:

```bash
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/validate-vectors.py
```

The same command validates the four A2 fixtures and retains the intentional separation of duties: a forged A2 `PASS` with a failed diagnostic Guard remains JSON-Schema-valid, then `A2_CONCLUSION_PRECEDENCE` must reject it semantically.

The eventual Java verifier must reproduce these reference outcomes. Adding a cross-material rule without a catalog entry, fixed admission target, and at least one attack vector is a Design Gate D0 failure.
