#!/usr/bin/env python3
"""Standard-library-only verifier for a packaged CaveWeaver CW10 release."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from typing import Any


REQUIRED_EVIDENCE = {
    "runtime",
    "cpu_gpu_parity",
    "portable_consumer",
    "minecraft",
    "rollback_chain",
    "owner_review",
    "showcase",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def safe_artifact(root: Path, record: dict[str, Any], label: str) -> Path:
    uri = record.get("uri")
    expected = record.get("sha256")
    if not isinstance(uri, str) or not uri or Path(uri).is_absolute():
        raise ValueError(f"{label} URI must be a relative path")
    path = root / uri
    resolved_root = root.resolve()
    resolved_path = path.resolve()
    if resolved_root not in resolved_path.parents:
        raise ValueError(f"{label} escapes the release root")
    if not path.is_file() or not isinstance(expected, str) or sha256_file(path) != expected:
        raise ValueError(f"{label} bytes are missing or drifted")
    return path


def safe_path_artifact(root: Path, record: dict[str, Any], label: str) -> Path:
    """Verify an evidence-package record whose relative-path key is ``path``."""

    return safe_artifact(
        root,
        {"uri": record.get("path"), "sha256": record.get("sha256")},
        label,
    )


def _load_portable_verifier(path: Path):
    spec = importlib.util.spec_from_file_location("cw10_portable_verifier", path)
    if spec is None or spec.loader is None:
        raise ValueError("cannot load packaged portable verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_owner_package(root: Path, package_path: Path, minecraft_path: Path, asset_sha256: str) -> int:
    package = load_object(package_path)
    if package.get("schema_version") != "geoworld.caveweaver-cw10-owner-review-package.v1":
        raise ValueError("unsupported owner-review package")
    if package.get("decision") != "PASS":
        raise ValueError("owner visual decision is not PASS")
    if package.get("canonical_asset_sha256") != asset_sha256:
        raise ValueError("owner review identifies another canonical asset")
    if package.get("minecraft_evidence_sha256") != sha256_file(minecraft_path):
        raise ValueError("owner review identifies another Minecraft evidence record")
    decision_path = safe_artifact(root, package.get("decision_record", {}), "owner decision")
    decision = load_object(decision_path)
    if decision.get("decision") != "PASS" or decision.get("visual_evidence_pass") is not True:
        raise ValueError("packaged owner decision is not an explicit visual PASS")
    visuals = package.get("visuals")
    if not isinstance(visuals, list) or len(visuals) < 3:
        raise ValueError("owner review package requires at least three visual artifacts")
    for index, record in enumerate(visuals):
        safe_artifact(root, record, f"owner visual {index}")
    return len(visuals)


def verify(release: Path) -> dict[str, Any]:
    manifest_path = release / "CAVEWEAVER_RELEASE.json"
    manifest = load_object(manifest_path)
    if manifest.get("schema_version") != "geoworld.caveweaver-release-manifest.v1":
        raise ValueError("unsupported CW10 release manifest")
    if manifest.get("stage") != "CW10" or manifest.get("state") != "VERIFIED_RESEARCH_CANDIDATE":
        raise ValueError("release is not a verified CW10 research candidate")
    if set(manifest.get("evidence", {})) != REQUIRED_EVIDENCE:
        raise ValueError("CW10 evidence set is incomplete")

    canonical_record = manifest.get("canonical_asset", {})
    asset_path = safe_artifact(release, canonical_record, "canonical asset")
    asset = load_object(asset_path)
    if canonical_record.get("content_sha256") != asset.get("provenance", {}).get("content_sha256"):
        raise ValueError("release and canonical content identities differ")
    if canonical_record.get("topology_plan_hash") != asset.get("geometry_contract", {}).get("topology_plan_hash"):
        raise ValueError("release and canonical topology identities differ")

    safe_artifact(release, manifest.get("execution_binding", {}), "execution binding")
    negative = manifest.get("negative_results", {})
    if set(negative) != {"cw7", "cw8", "cw9"}:
        raise ValueError("CW7/CW8/CW9 negative results are not all pinned")
    for stage, record in negative.items():
        if record.get("decision") != "NOT_PROMOTED":
            raise ValueError(f"{stage} negative result was rewritten")
        safe_artifact(release, record, f"{stage} negative result")

    asset_exports = {item["export_id"]: item for item in asset.get("exports", [])}
    release_exports = {item["export_id"]: item for item in manifest.get("exports", [])}
    if set(asset_exports) != set(release_exports):
        raise ValueError("release and canonical export sets differ")
    for export_id, record in release_exports.items():
        source = asset_exports[export_id]
        for key in ("kind", "sha256", "source_content_sha256", "media_type"):
            if record.get(key) != source.get(key):
                raise ValueError(f"release export metadata drifted: {export_id}.{key}")
        safe_artifact(release, record, f"release export {export_id}")

    evidence_paths = {
        name: safe_artifact(release, record, f"{name} evidence")
        for name, record in manifest["evidence"].items()
    }
    runtime = load_object(evidence_paths["runtime"])
    parity = load_object(evidence_paths["cpu_gpu_parity"])
    portable = load_object(evidence_paths["portable_consumer"])
    minecraft = load_object(evidence_paths["minecraft"])
    rollback = load_object(evidence_paths["rollback_chain"])
    verification = manifest.get("verification", {})
    if runtime.get("state") != "PASS_CACHED_STREAMING_RUNTIME_DIRECT_CPU_REMAINS_FAIL":
        raise ValueError("runtime evidence is not the registered cached-runtime PASS")
    if runtime.get("cached_64_cubed_decode", {}).get("p95_ms") != verification.get("cached_decode_p95_ms"):
        raise ValueError("manifest cached-runtime measurement differs from evidence")
    if runtime.get("local_64_cubed_cpu_decode", {}).get("p95_ms") != verification.get("direct_cpu_decode_p95_ms"):
        raise ValueError("manifest direct-CPU measurement differs from evidence")
    if parity.get("state") != "PASS_CPU_GPU_NUMERICAL_CONTRACT" or not (
        parity.get("block_sign_identity") and parity.get("int8_quantized_identity")
    ):
        raise ValueError("CPU/GPU parity evidence is not passing")
    if portable.get("state") != "PASS_STANDALONE_STANDARD_LIBRARY_CONSUMER":
        raise ValueError("portable-consumer evidence is not passing")
    if minecraft.get("gate_state") != "PASS" or minecraft.get("production_world_deployed") is not False:
        raise ValueError("Minecraft evidence is not a safe disposable-world PASS")
    if minecraft.get("aero_mutated") is not False:
        raise ValueError("Minecraft evidence does not prove Aero remained untouched")
    if (
        minecraft.get("canonical_asset_sha256") != sha256_file(asset_path)
        or minecraft.get("content_sha256") != canonical_record.get("content_sha256")
        or minecraft.get("topology_plan_hash") != canonical_record.get("topology_plan_hash")
    ):
        raise ValueError("Minecraft evidence identifies another canonical cave")
    minecraft_root = evidence_paths["minecraft"].parent
    safe_path_artifact(minecraft_root, minecraft.get("visual", {}), "paired Minecraft visual")
    safe_path_artifact(
        minecraft_root, minecraft.get("showcase_manifest", {}), "Minecraft showcase manifest"
    )
    minecraft_visuals = minecraft.get("owner_review_visuals")
    if not isinstance(minecraft_visuals, dict) or len(minecraft_visuals) < 4:
        raise ValueError("Minecraft evidence lacks four owner-review visuals")
    for name, record in minecraft_visuals.items():
        safe_path_artifact(minecraft_root, record, f"Minecraft owner visual {name}")
    raw_names = {
        "MINECRAFT_EVALUATION.json",
        "BASELINE_PROBE.json",
        "CANDIDATE_PROBE.json",
        "BASELINE_PERFORMANCE.json",
        "CANDIDATE_PERFORMANCE.json",
        "CW10_APPLICATION.json",
        "SHOWCASE.json",
    }
    raw_records = minecraft.get("raw_evidence")
    if not isinstance(raw_records, dict) or not raw_names.issubset(raw_records):
        raise ValueError("Minecraft raw evidence index is incomplete")
    raw_paths = {
        name: safe_path_artifact(minecraft_root, record, f"Minecraft raw evidence {name}")
        for name, record in raw_records.items()
    }
    region_audit = load_object(raw_paths["MINECRAFT_EVALUATION.json"])
    if region_audit.get("gate_state") != "PASS" or not all(region_audit.get("gates", {}).values()):
        raise ValueError("packaged Minecraft region audit is not passing every gate")
    application = load_object(raw_paths["CW10_APPLICATION.json"])
    if application.get("state") != "PASS_AIR_WATER_APPLIED_AND_AUDIT_QUIET":
        raise ValueError("packaged Minecraft application audit is not passing")
    if rollback.get("state") != "PASS":
        raise ValueError("rollback-chain evidence is not passing")
    visual_count = verify_owner_package(
        release,
        evidence_paths["owner_review"],
        evidence_paths["minecraft"],
        sha256_file(asset_path),
    )

    reproduction = manifest.get("reproduction", {})
    portable_verifier_path = safe_artifact(
        release, reproduction.get("portable_verifier", {}), "portable verifier"
    )
    safe_artifact(release, reproduction.get("release_verifier", {}), "release verifier")
    safe_artifact(release, reproduction.get("instructions", {}), "reproduction instructions")
    portable_verifier = _load_portable_verifier(portable_verifier_path)
    portable_report = portable_verifier.verify(asset_path.parent)
    if portable_report.get("asset_sha256") != sha256_file(asset_path):
        raise ValueError("packaged portable verifier disagrees with release identity")

    return {
        "schema_version": "geoworld.caveweaver-cw10-release-verification.v1",
        "state": "PASS_CLEAN_ROOM_CW10_RELEASE",
        "release_id": manifest["release_id"],
        "release_manifest_sha256": sha256_file(manifest_path),
        "canonical_asset_sha256": sha256_file(asset_path),
        "content_sha256": canonical_record["content_sha256"],
        "topology_plan_hash": canonical_record["topology_plan_hash"],
        "export_count": len(release_exports),
        "evidence_count": len(evidence_paths),
        "owner_visual_count": visual_count,
        "runtime_dependencies": ["Python 3 standard library"],
        "minecraft_dependency": False,
        "geoworld_runtime_dependency": False,
        "production_deployment_authorized": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("release", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = verify(args.release)
    payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        if args.output.exists():
            raise FileExistsError(args.output)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".partial")
        temporary.write_text(payload, encoding="utf-8")
        temporary.replace(args.output)
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
