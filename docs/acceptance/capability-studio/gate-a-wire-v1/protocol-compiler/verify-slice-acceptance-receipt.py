#!/usr/bin/env python3
"""Consume one Gate A SliceAcceptanceReceipt exactly once.

Round 7 changes:
- New ledger head protocol: caller pins expected previous head fingerprint
  and revision; verifier validates head in transaction, creates new head with
  O_EXCL, increments revision by 1.
- Marker contains full lineage (Authority, source tree, toolchain, bundle,
  invocation, slice, receipt fingerprint, previous head, predecessor).
- Typed evidence: each evidence record validated against Authority schema.
- Ledger key = (bundleRoot, sliceId, challengeNonce, buildInvocationId) only;
  receiptFingerprint stored in marker value, not the key.
- Return value includes ledgerHeadFingerprint and ledgerRevision.
"""

from __future__ import annotations

import argparse
import pathlib
import sys


HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from slice_acceptance_receipt import (  # noqa: E402
    GENESIS_PREVIOUS_HEAD_FP,
    ReplayRejected,
    ReceiptError,
    verify_and_consume,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify and consume one Gate A slice receipt",
    )
    # Core receipt parameters
    parser.add_argument("--bundle-root", required=True)
    parser.add_argument("--expected-bundle-root-fingerprint", required=True)
    parser.add_argument("--receipt", required=True)
    parser.add_argument("--expected-slice-id", required=True)
    parser.add_argument("--expected-challenge-nonce", required=True)
    parser.add_argument("--expected-source-tree-fingerprint", required=True)
    parser.add_argument("--expected-toolchain-identity-fingerprint", required=True)
    parser.add_argument("--expected-build-invocation-id", required=True)
    parser.add_argument("--expected-launcher-observation-fingerprint", required=True)
    parser.add_argument("--expected-command-fingerprint", required=True)
    parser.add_argument("--expected-test-source-fingerprint", required=True)
    parser.add_argument("--artifact-root", required=True)
    parser.add_argument("--evidence-root", required=True)
    parser.add_argument("--consumption-ledger-root", required=True)
    parser.add_argument("--expected-predecessor-receipt-fingerprint")
    # --- Round 7: Ledger head tracking ---
    parser.add_argument(
        "--expected-ledger-head-fingerprint",
        default=GENESIS_PREVIOUS_HEAD_FP,
        help=(
            "sha256 fingerprint of the expected previous ledger head marker. "
            "Use sha256:0...0 (64 zeros) for genesis ledger (first consumption). "
            "Default: genesis sentinel."
        ),
    )
    parser.add_argument(
        "--expected-ledger-revision",
        type=int,
        default=0,
        help=(
            "Expected ledger revision number. Use 0 for genesis ledger. "
            "Verifier validates that this matches the current ledger head revision "
            "before committing. Default: 0 (genesis)."
        ),
    )
    parser.add_argument(
        "--predecessor-marker-fingerprint",
        help=(
            "sha256 fingerprint of the predecessor consumption marker. "
            "Required for slices with predecessor dependencies (A1.2+)."
        ),
    )

    args = parser.parse_args()
    predecessor_supplied = args.expected_predecessor_receipt_fingerprint is not None

    try:
        result = verify_and_consume(
            bundle_root=args.bundle_root,
            expected_bundle_root_fingerprint=args.expected_bundle_root_fingerprint,
            receipt_path=args.receipt,
            expected_slice_id=args.expected_slice_id,
            expected_challenge_nonce=args.expected_challenge_nonce,
            expected_source_tree_fingerprint=args.expected_source_tree_fingerprint,
            expected_toolchain_identity_fingerprint=args.expected_toolchain_identity_fingerprint,
            expected_build_invocation_id=args.expected_build_invocation_id,
            expected_launcher_observation_fingerprint=args.expected_launcher_observation_fingerprint,
            expected_command_fingerprint=args.expected_command_fingerprint,
            expected_test_source_fingerprint=args.expected_test_source_fingerprint,
            artifact_root=args.artifact_root,
            evidence_root=args.evidence_root,
            consumption_ledger_root=args.consumption_ledger_root,
            expected_predecessor_receipt_fingerprint=args.expected_predecessor_receipt_fingerprint,
            predecessor_argument_supplied=predecessor_supplied,
            # --- Round 7 ledger head parameters ---
            expected_ledger_head_fingerprint=args.expected_ledger_head_fingerprint,
            expected_ledger_revision=args.expected_ledger_revision,
            predecessor_marker_fingerprint=args.predecessor_marker_fingerprint,
        )
    except ReplayRejected as error:
        print(f"SLICE_RECEIPT_REPLAY_REJECTED:{error}", file=sys.stderr)
        return 5
    except (ReceiptError, ValueError, OSError) as error:
        print(f"SLICE_RECEIPT_REJECTED:{error}", file=sys.stderr)
        return 4
    except Exception as error:  # pragma: no cover - last-resort fail closed boundary
        import traceback
        traceback.print_exc()
        print(f"SLICE_RECEIPT_REJECTED:UNEXPECTED:{type(error).__name__}:{error}", file=sys.stderr)
        return 4

    # Result is a dict with receiptFingerprint, ledgerHeadFingerprint, ledgerRevision, markerKeyFingerprint
    print(
        f"SLICE_RECEIPT_ACCEPTED:"
        f"receiptFingerprint={result['receiptFingerprint']};"
        f"ledgerHeadFingerprint={result['ledgerHeadFingerprint']};"
        f"ledgerRevision={result['ledgerRevision']};"
        f"markerKeyFingerprint={result['markerKeyFingerprint']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
