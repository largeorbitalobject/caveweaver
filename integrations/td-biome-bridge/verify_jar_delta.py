#!/usr/bin/env python3
"""Fail unless a candidate JAR changes exactly the approved bridge classes."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import zipfile


APPROVED_CHANGES = frozenset(
    {
        "com/orbital/tdbridge/CoarseConditioningPaint.class",
        "com/orbital/tdbridge/CoarseConditioningPaint$Config.class",
        "com/orbital/tdbridge/CoarseConditioningPaint$Delta.class",
        "com/orbital/tdbridge/mixin/SyntheticMapFactoryMixin.class",
    }
)


def _entry_hashes(path: Path) -> dict[str, str]:
    with zipfile.ZipFile(path) as archive:
        return {
            name: hashlib.sha256(archive.read(name)).hexdigest()
            for name in archive.namelist()
            if not name.endswith("/")
        }


def changed_entries(baseline: Path, candidate: Path) -> frozenset[str]:
    before = _entry_hashes(baseline)
    after = _entry_hashes(candidate)
    if set(before) != set(after):
        added = sorted(set(after) - set(before))
        removed = sorted(set(before) - set(after))
        raise ValueError(f"JAR entry set changed; added={added}, removed={removed}")
    return frozenset(name for name in before if before[name] != after[name])


def verify(baseline: Path, candidate: Path) -> None:
    changed = changed_entries(baseline, candidate)
    if changed != APPROVED_CHANGES:
        raise ValueError(
            f"unexpected JAR delta; expected={sorted(APPROVED_CHANGES)}, actual={sorted(changed)}"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    verify(args.baseline, args.candidate)
    print("PASS: candidate changes exactly four approved bridge classes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

