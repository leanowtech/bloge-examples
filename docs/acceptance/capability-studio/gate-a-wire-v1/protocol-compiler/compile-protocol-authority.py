#!/usr/bin/env python3
"""Gate A Protocol Compiler CLI.

Usage:
  python compile-protocol-authority.py --output-root DIR [--authority PATH] [--self-test]
  python compile-protocol-authority.py --output-root DIR --publish-packaging-plan-root DIR [--authority PATH]

Pipeline:
  strict parser -> v1 adapter -> ProtocolResourceGraph
  -> Semantic Linker -> immutable LinkedProtocolModel
  -> Projection Compiler -> PREPARE/COMMIT publication
  -> [optional] IndependentVerifierPackagingPlan -> content-addressed publication

Semantics:
  --self-test alone: compile+verify to output-root (no publication)
  --publish-packaging-plan-root alone: compile+verify to output-root AND publish
  --self-test --publish-packaging-plan-root: rejected (explicit CODE, no detail)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[4]
SCHEMA_ROOT = REPO / "docs/schemas/resource-gateway-capability-studio"

from compiler_core import (
    compile_authority,
    CompilerError,
    StrictJSONDuplicateError,
    StrictJSONNonFiniteError,
    SemanticLinkerError,
    OutputExistsError,
    LinkedProtocolModel,
    validate_authority as _validate_authority,
    rendered_outputs as _rendered_outputs,
)

# Expose for release_authority_bundle.py module import
validate_authority = _validate_authority
rendered_outputs = _rendered_outputs


def main() -> None:
    parser = argparse.ArgumentParser(description="Gate A Protocol Compiler")
    parser.add_argument(
        "--output-root",
        type=pathlib.Path,
        default=None,
        help="Directory to write compiled projections. Defaults to ./compiled for self-test.",
    )
    parser.add_argument(
        "--authority",
        type=pathlib.Path,
        default=HERE / "gate-a-protocol-authority-v1.json",
        help="Path to authority JSON (default: ./gate-a-protocol-authority-v1.json).",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run self-test suite and exit.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="No-op; recognized for run-protocol-gate.py orchestration.",
    )
    parser.add_argument(
        "--publish-packaging-plan-root",
        type=pathlib.Path,
        default=None,
        dest="publish_plan_root",
        help="Explicit opt-in: output root for IndependentVerifierPackagingPlan content-addressed publication.",
    )
    args = parser.parse_args()

    authority_path = args.authority.resolve()
    output_root = (args.output_root or HERE / "compiled").resolve()

    if not authority_path.exists():
        raise SystemExit("AUTHORITY_NOT_FOUND")
    if not SCHEMA_ROOT.exists():
        raise SystemExit("SCHEMA_ROOT_NOT_FOUND")

    if args.self_test:
        if args.publish_plan_root:
            # Explicit combination: reject with stable code only
            raise SystemExit("SELF_TEST_WITH_PUBLISH_FORBIDDEN")
        _run_self_test(authority_path, output_root)
    else:
        _run_compile(authority_path, output_root, args.publish_plan_root)


def _run_compile(
    authority_path: pathlib.Path,
    output_root: pathlib.Path,
    publish_plan_root: pathlib.Path | None,
) -> None:
    # Load packaging plan errors so PackagingPlanError is in scope for except clauses
    _ppe = _load_packaging_plan_errors()
    PackagingPlanError, Publisher, derive_packaging_plan = _ppe
    try:
        result = compile_authority(
            authority_path=authority_path,
            output_root=output_root,
            schema_root=SCHEMA_ROOT,
        )
        print(f"Compiled {result['projection_count']} projections to {output_root}")

        if publish_plan_root:
            _publish_packaging_plan(authority_path, output_root, publish_plan_root)

    except StrictJSONDuplicateError:
        raise SystemExit("PROTOCOL_JSON_FORMAT_OR_DUPLICATE_DRIFT")
    except StrictJSONNonFiniteError:
        raise SystemExit("PROTOCOL_JSON_NON_FINITE_NUMBER")
    except SemanticLinkerError as exc:
        raise SystemExit(exc.code or exc.CODE)
    except OutputExistsError as exc:
        raise SystemExit(f"COMPILER_OUTPUT_ROOT_EXISTS: {exc.detail}")
    except CompilerError as exc:
        raise SystemExit(f"{exc.CODE}: {exc.detail}")
    except PackagingPlanError as exc:
        # Stable CODE only — no paths, no detail
        raise SystemExit(exc.CODE)


# Import PackagingPlanError here so the except clause above can catch it.
# Avoid top-level import to keep the module loadable when packaging_plan is absent.
def _load_packaging_plan_errors():
    from packaging_plan import (
        PackagingPlanError,
        Publisher,
        derive_packaging_plan,
    )
    return PackagingPlanError, Publisher, derive_packaging_plan


def _publish_packaging_plan(
    authority_path: pathlib.Path,
    compiled_dir: pathlib.Path,
    plan_output_root: pathlib.Path,
) -> None:
    """Compile and publish IndependentVerifierPackagingPlan.

    Uses in-memory compilation via LinkedProtocolModel + ProjectionCompiler.compile_all
    so schema fingerprints are from the same source as compile_authority.
    Does NOT read from compiled_dir.
    """
    PackagingPlanError, Publisher, derive_packaging_plan = _load_packaging_plan_errors()

    authority_raw = authority_path.read_bytes()

    # ── In-memory compile via same pipeline as compile_authority ──────────
    # Use the SAME schema fingerprint computation as compile_authority so
    # the manifest's authoritySchemaRawFingerprint/projectionSchemaRawFingerprint
    # match exactly what compile_authority writes.
    from compiler_core import ProjectionCompiler

    auth_schema_fp = f"sha256:{hashlib.sha256((SCHEMA_ROOT / 'capability-studio-gate-a-protocol-authority-v1.schema.json').read_bytes()).hexdigest()}"
    proj_schema_fp = f"sha256:{hashlib.sha256((SCHEMA_ROOT / 'capability-studio-gate-a-protocol-projection-v1.schema.json').read_bytes()).hexdigest()}"

    linked_model = LinkedProtocolModel.from_authority(authority_raw)
    compiler = ProjectionCompiler(linked_model)
    compiled_projections, compilation_manifest = compiler.compile_all(
        auth_schema_fp, proj_schema_fp
    )
    # compiled_projections: dict[pid -> projection dict] (8 projections)
    # compilation_manifest: full manifest dict

    # ── Derive and publish ───────────────────────────────────────────────
    plan = derive_packaging_plan(
        linked_model=linked_model,
        compiled_projections=compiled_projections,
        compilation_manifest=compilation_manifest,
        authority_raw=authority_raw,
    )

    publisher = Publisher(output_root=plan_output_root)
    staging = publisher.prepare(plan=plan, authority_raw=authority_raw)
    receipt = publisher.commit(staging=staging, plan=plan, authority_raw=authority_raw)

    print(f"Packaging plan published: {receipt.publication_root}")
    print(f"Plan fingerprint: {receipt.plan_raw_fingerprint}")
    print(f"Receipt fingerprint: {receipt.receipt_fingerprint}")


def _run_self_test(
    authority_path: pathlib.Path, output_root: pathlib.Path
) -> None:
    """Self-test: double-compile in isolated temp dirs -> byte-identical -> write output."""
    raw = authority_path.read_bytes()
    auth_fp = f"sha256:{hashlib.sha256(raw).hexdigest()}"

    def do_compile(root: pathlib.Path) -> None:
        compile_authority(
            authority_path=authority_path,
            output_root=root,
            schema_root=SCHEMA_ROOT,
            overwrite=True,
        )

    with tempfile.TemporaryDirectory(prefix="gate-a-compile-1-") as d1, \
         tempfile.TemporaryDirectory(prefix="gate-a-compile-2-") as d2:
        first = pathlib.Path(d1)
        second = pathlib.Path(d2)

        do_compile(first)
        do_compile(second)

        first_files = sorted(
            str(p.relative_to(first)) for p in first.rglob("*") if p.is_file()
        )
        second_files = sorted(
            str(p.relative_to(second)) for p in second.rglob("*") if p.is_file()
        )
        if first_files != second_files:
            raise SystemExit("SELF_TEST_DOUBLE_COMPILE_FILE_SET_DRIFT")
        for rel in first_files:
            if (first / rel).read_bytes() != (second / rel).read_bytes():
                raise SystemExit(f"SELF_TEST_DOUBLE_COMPILE_BYTES_DRIFT: {rel}")

    compile_authority(
        authority_path=authority_path,
        output_root=output_root,
        schema_root=SCHEMA_ROOT,
        overwrite=True,
    )

    manifest = json.loads(
        (output_root / "protocol-compilation-manifest-v1.json").read_text()
    )
    if manifest["sourceRawFingerprint"] != auth_fp:
        raise SystemExit("SELF_TEST_MANIFEST_SOURCE_FP_DRIFT")

    expected_names = {
        e["path"] for e in manifest["projections"]
    } | {"protocol-compilation-manifest-v1.json"}
    actual_names = {
        str(p.relative_to(output_root))
        for p in output_root.rglob("*") if p.is_file()
    }
    if expected_names != actual_names:
        missing = expected_names - actual_names
        raise SystemExit(f"SELF_TEST_PROJECTION_FILES_MISSING: {missing}")

    print(
        f"Gate A protocol compiler PASS: double-compile byte-identical; "
        f"{len(manifest['projections'])} projections; "
        f"manifest fingerprint verified; {len(actual_names)} files."
    )


if __name__ == "__main__":
    main()
