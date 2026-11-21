#!/usr/bin/env python3
"""Create patched bridge sources in a new staging tree; never overwrite inputs."""

from __future__ import annotations

import argparse
from pathlib import Path

from geoworld.minecraft.five_field_patch import transform_coarse_conditioning_paint
from geoworld.shared.canonical import sha256_file


EXPECTED_COARSE_SHA256 = "ce544c066205e861e87d23ea03250e020f354db037906c2b9bed693c997115a5"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--coarse-source", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    args = parser.parse_args()
    if sha256_file(args.coarse_source) != EXPECTED_COARSE_SHA256:
        raise SystemExit("refusing to patch an unrecognized CoarseConditioningPaint baseline")
    destination = args.output_root / "com/orbital/tdbridge/CoarseConditioningPaint.java"
    destination.parent.mkdir(parents=True, exist_ok=False)
    transformed = transform_coarse_conditioning_paint(args.coarse_source.read_text(encoding="utf-8"))
    destination.write_text(transformed, encoding="utf-8")
    print(destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

