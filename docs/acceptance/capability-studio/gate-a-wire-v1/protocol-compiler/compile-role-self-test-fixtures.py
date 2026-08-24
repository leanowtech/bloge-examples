#!/usr/bin/env python3
"""Thin CLI for the Gate A sealed Release Authority Bundle compiler."""

from __future__ import annotations

import argparse
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from release_authority_bundle import compile_cli  # noqa: E402


parser = argparse.ArgumentParser(description="Compile a sealed Gate A Release Authority Bundle")
parser.add_argument("--authority", default=str(HERE / "gate-a-protocol-authority-v1.json"))
parser.add_argument("--output-root", required=True)
parser.add_argument("--target-slice-id", required=True)
parser.add_argument("--role-jar", action="append", required=True)
compile_cli(parser.parse_args())
