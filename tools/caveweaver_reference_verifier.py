#!/usr/bin/env python3
"""Standalone standard-library verifier for a CaveWeaver CW10 portable bundle."""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import struct
from typing import Any


REQUIRED_EXPORTS = {
    "minecraft_block_mask": 1,
    "triangle_mesh": 1,
    "mesh_lod": 2,
    "collider": 1,
    "navigation_graph": 1,
    "raw_sdf": 1,
    "hydrology": 1,
}


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def core_digest(asset: dict[str, Any]) -> str:
    value = deepcopy(asset)
    value.pop("exports", None)
    value["provenance"].pop("content_sha256", None)
    return sha256_bytes(canonical_bytes(value))


def read_glb(path: Path) -> dict[str, Any]:
    content = path.read_bytes()
    if len(content) < 20:
        raise ValueError(f"truncated GLB: {path.name}")
    magic, version, declared_length = struct.unpack_from("<4sII", content)
    if magic != b"glTF" or version != 2 or declared_length != len(content):
        raise ValueError(f"invalid GLB header: {path.name}")
    json_length, json_type = struct.unpack_from("<I4s", content, 12)
    if json_type != b"JSON" or 20 + json_length > len(content):
        raise ValueError(f"invalid GLB JSON chunk: {path.name}")
    document = json.loads(content[20 : 20 + json_length])
    primitive = document["meshes"][0]["primitives"][0]
    attributes = primitive["attributes"]
    if "POSITION" not in attributes or "NORMAL" not in attributes:
        raise ValueError(f"GLB lacks positions or normals: {path.name}")
    accessors = document["accessors"]
    positions = accessors[attributes["POSITION"]]
    normals = accessors[attributes["NORMAL"]]
    indices = accessors[primitive["indices"]]
    if positions["count"] != normals["count"] or indices["count"] % 3:
        raise ValueError(f"GLB accessor counts are inconsistent: {path.name}")
    if any(
        low < -1e-6 or high > limit + 1e-6
        for low, high, limit in zip(positions["min"], positions["max"], (256.0, 96.0, 256.0), strict=True)
    ):
        raise ValueError(f"GLB coordinates exceed the canonical region: {path.name}")
    return {
        "vertices": int(positions["count"]),
        "triangles": int(indices["count"] // 3),
        "bounds_min_xyz_m": positions["min"],
        "bounds_max_xyz_m": positions["max"],
        "normal_count": int(normals["count"]),
    }


def connected_graph(nodes: list[dict[str, Any]], edges: list[dict[str, Any]]) -> bool:
    node_ids = {node["node_id"] for node in nodes}
    adjacency = {node_id: set() for node_id in node_ids}
    for edge in edges:
        left = edge["from_node"]
        right = edge["to_node"]
        if left not in adjacency or right not in adjacency:
            raise ValueError("graph edge references a missing node")
        adjacency[left].add(right)
        adjacency[right].add(left)
    seen = set()
    stack = [next(iter(node_ids))]
    while stack:
        node = stack.pop()
        if node in seen:
            continue
        seen.add(node)
        stack.extend(adjacency[node] - seen)
    return seen == node_ids


def verify(bundle: Path) -> dict[str, Any]:
    asset_path = bundle / "caveweaver-asset.json"
    asset = json.loads(asset_path.read_text(encoding="utf-8"))
    if asset.get("schema_version") != "geoworld.caveweaver-asset.v2":
        raise ValueError("unsupported canonical asset schema")
    if asset.get("coordinate_system") != "right_handed_xyz_y_up" or asset.get("unit_meters") != 1.0:
        raise ValueError("unsupported coordinate system or units")
    content_sha256 = core_digest(asset)
    if asset["provenance"]["content_sha256"] != content_sha256:
        raise ValueError("canonical content digest mismatch")
    if asset["provenance"].get("sealed_confirmation_access") is not False:
        raise ValueError("sealed-access declaration changed")
    if asset["geometry_contract"].get("target_geometry_access_at_inference") is not False:
        raise ValueError("target-geometry inference policy changed")
    topology_hash = asset["geometry_contract"]["topology_plan_hash"]
    if not connected_graph(asset["topology"]["nodes"], asset["topology"]["edges"]):
        raise ValueError("canonical topology is disconnected")
    node_ids = {node["node_id"] for node in asset["topology"]["nodes"]}
    for port in asset["topology"]["boundary_ports"]:
        if port["node_id"] not in node_ids:
            raise ValueError("boundary port references a missing node")

    exports = asset.get("exports", [])
    counts = {kind: 0 for kind in REQUIRED_EXPORTS}
    records = {}
    for record in exports:
        kind = record["kind"]
        if kind not in counts:
            raise ValueError(f"unknown export kind: {kind}")
        counts[kind] += 1
        if record["export_id"] in records:
            raise ValueError("duplicate export ID")
        if record["source_content_sha256"] != content_sha256:
            raise ValueError("export belongs to another canonical cave")
        path = bundle / record["uri"]
        if not path.is_file() or sha256_file(path) != record["sha256"]:
            raise ValueError(f"export hash mismatch: {record['export_id']}")
        records[record["export_id"]] = record
    missing = {kind: required - counts[kind] for kind, required in REQUIRED_EXPORTS.items() if counts[kind] < required}
    if missing:
        raise ValueError(f"required exports missing: {missing}")

    payload = asset["anatomy"]["payload"]
    anatomy_path = bundle / payload["uri"]
    if sha256_file(anatomy_path) != payload["sha256"]:
        raise ValueError("anatomy payload hash mismatch")
    anatomy_header = anatomy_path.read_bytes()[:20]
    magic, token_count, grid_size, flags, grid_spacing = struct.unpack("<8sIHHf", anatomy_header)
    expected_anatomy_bytes = 20 + token_count * 4 + token_count * grid_size * grid_size
    if (
        magic != b"CWSDF1\0\0"
        or flags != 0
        or token_count != len(asset["anatomy"]["tokens"])
        or token_count != payload["token_count"]
        or grid_size != payload["grid_size"]
        or abs(grid_spacing - payload["grid_spacing_m"]) > 1e-6
        or anatomy_path.stat().st_size != expected_anatomy_bytes
    ):
        raise ValueError("anatomy payload header or length mismatch")

    raw_record = next(record for record in exports if record["kind"] == "raw_sdf")
    raw_path = bundle / raw_record["uri"]
    with raw_path.open("rb") as stream:
        raw_header = stream.read(36)
        raw_values = stream.read()
    raw_magic, sy, sz, sx, spacing, oy, oz, quantization = struct.unpack("<8sIIIffff", raw_header)
    if raw_magic != b"CWSDF3\0\0" or (sy, sz, sx) != (96, 256, 256) or spacing != 1.0:
        raise ValueError("raw SDF header is incompatible")
    if len(raw_values) != sy * sz * sx or not quantization > 0:
        raise ValueError("raw SDF payload length or quantization is invalid")

    block_record = next(record for record in exports if record["kind"] == "minecraft_block_mask")
    block_path = bundle / block_record["uri"]
    with block_path.open("rb") as stream:
        block_header = stream.read(20)
        block_values = stream.read()
    block_magic, by, bz, bx = struct.unpack("<8sIII", block_header)
    if block_magic != b"CWBLK1\0\0" or (by, bz, bx) != (sy, sz, sx) or len(block_values) != sy * sz * sx:
        raise ValueError("Minecraft block-class payload is incompatible")
    block_counts = {str(value): block_values.count(value) for value in (0, 1, 2)}
    if sum(block_counts.values()) != len(block_values) or not block_counts["1"] or not block_counts["2"]:
        raise ValueError("Minecraft block classes lack rock, air, or water")

    port_queries = []
    for port in asset["topology"]["boundary_ports"]:
        x, y, z = (round(value) for value in port["position_xyz_m"])
        x = min(sx - 1, max(0, x))
        y = min(sy - 1, max(0, y))
        z = min(sz - 1, max(0, z))
        index = (y * sz + z) * sx + x
        sdf_quantized = struct.unpack_from("<b", raw_values, index)[0]
        block_class = block_values[index]
        if sdf_quantized >= 0 or block_class == 0:
            raise ValueError(f"registered port is not void in SDF and Minecraft exports: {port['port_id']}")
        port_queries.append(
            {"port_id": port["port_id"], "sdf_m": sdf_quantized / quantization, "block_class": block_class}
        )

    navigation_record = next(record for record in exports if record["kind"] == "navigation_graph")
    navigation = json.loads((bundle / navigation_record["uri"]).read_text(encoding="utf-8"))
    if navigation["topology_plan_hash"] != topology_hash or not connected_graph(navigation["nodes"], navigation["edges"]):
        raise ValueError("navigation export differs from canonical topology")
    if not all(edge.get("traversable") is True for edge in navigation["edges"]):
        raise ValueError("navigation export contains a non-traversable required edge")

    hydrology_record = next(record for record in exports if record["kind"] == "hydrology")
    hydrology = json.loads((bundle / hydrology_record["uri"]).read_text(encoding="utf-8"))
    if hydrology["topology_plan_hash"] != topology_hash or not hydrology["recharge_ports"] or not hydrology["outlet_ports"]:
        raise ValueError("hydrology export lacks topology identity, recharge, or outlet")

    meshes = {}
    for record in exports:
        if record["kind"] in {"triangle_mesh", "mesh_lod", "collider"}:
            meshes[record["export_id"]] = read_glb(bundle / record["uri"])
    lod_triangles = [
        meshes[record["export_id"]]["triangles"]
        for record in sorted(
            (item for item in exports if item["kind"] in {"triangle_mesh", "mesh_lod"}),
            key=lambda item: item["lod"],
        )
    ]
    if not all(left > right for left, right in zip(lod_triangles, lod_triangles[1:])):
        raise ValueError("mesh LOD triangle counts do not strictly decrease")

    return {
        "schema_version": "geoworld.caveweaver-reference-verification.v1",
        "state": "PASS_STANDALONE_STANDARD_LIBRARY_CONSUMER",
        "asset_sha256": sha256_file(asset_path),
        "content_sha256": content_sha256,
        "topology_plan_hash": topology_hash,
        "coordinate_system": asset["coordinate_system"],
        "unit_meters": asset["unit_meters"],
        "nodes": len(asset["topology"]["nodes"]),
        "edges": len(asset["topology"]["edges"]),
        "ports": len(asset["topology"]["boundary_ports"]),
        "tokens": token_count,
        "field_shape_yzx": [sy, sz, sx],
        "block_class_counts": block_counts,
        "port_queries": port_queries,
        "meshes": meshes,
        "lod_triangles": lod_triangles,
        "runtime_dependencies": ["Python 3 standard library"],
        "minecraft_dependency": False,
        "geoworld_runtime_dependency": False,
        "claim_limit": "Portable format and cross-export conformance verification; not Minecraft live-world, performance, rollback, or visual approval",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = verify(args.bundle)
    payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output is not None:
        if args.output.exists():
            raise FileExistsError(args.output)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".partial")
        temporary.write_text(payload, encoding="utf-8")
        temporary.replace(args.output)
    print(json.dumps({key: report[key] for key in ("state", "asset_sha256", "content_sha256")}, sort_keys=True))


if __name__ == "__main__":
    main()
