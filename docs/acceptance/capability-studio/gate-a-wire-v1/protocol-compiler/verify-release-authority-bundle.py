#!/usr/bin/env python3
"""Thin CLI for verifying a sealed Gate A Release Authority Bundle."""

from __future__ import annotations

import argparse
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from release_authority_bundle import verify_cli  # noqa: E402


parser = argparse.ArgumentParser(description="Verify a sealed Gate A Release Authority Bundle")
parser.add_argument("--bundle-root", required=True)
parser.add_argument("--expected-root-fingerprint", required=True)
verify_cli(parser.parse_args())
