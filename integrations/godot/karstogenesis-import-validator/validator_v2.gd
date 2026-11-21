extends "res://validator.gd"


func _validate_case(case: Dictionary, root_3d: Node3D, case_index: int) -> Dictionary:
	var case_id := str(case["case_id"])
	var package_path := "res://cases/%s" % case_id
	var manifest: Variant = _load_json("%s/manifest.json" % package_path)
	var navigation: Variant = _load_json("%s/navigation.json" % package_path)
	var failures: Array[String] = []
	if manifest == null or navigation == null:
		return {"case_id": case_id, "state": "FAIL", "failed_gates": ["json_load"]}

	var load_started := Time.get_ticks_usec()
	var render_resource := ResourceLoader.load("%s/cave-interior.glb" % package_path)
	var collision_resource := ResourceLoader.load("%s/cave-collision.glb" % package_path)
	var load_usec := Time.get_ticks_usec() - load_started
	if not render_resource is PackedScene:
		failures.append("render_resource_is_packed_scene")
	if not collision_resource is PackedScene:
		failures.append("collision_resource_is_packed_scene")
	if not failures.is_empty():
		return {"case_id": case_id, "state": "FAIL", "failed_gates": failures}

	var instantiate_started := Time.get_ticks_usec()
	var render_scene := (render_resource as PackedScene).instantiate()
	var collision_scene := (collision_resource as PackedScene).instantiate()
	root_3d.add_child(render_scene)
	root_3d.add_child(collision_scene)
	var render_meshes: Array[MeshInstance3D] = []
	var collision_meshes: Array[MeshInstance3D] = []
	_collect_meshes(render_scene, render_meshes)
	_collect_meshes(collision_scene, collision_meshes)
	var instantiate_usec := Time.get_ticks_usec() - instantiate_started
	var render_metrics := _mesh_metrics(render_meshes)
	var collision_metrics := _mesh_metrics(collision_meshes)
	var render_contract: Dictionary = manifest["render_surface"]["metrics"]
	var collision_contract: Dictionary = manifest["collision"]["source_manifest_geometry"]
	var expected_render_triangles := int(render_contract["triangle_count"])
	var expected_collision_triangles := int(collision_contract["triangle_count"])
	if int(render_metrics["triangle_count"]) != expected_render_triangles:
		failures.append("render_triangle_count_exact")
	if int(collision_metrics["triangle_count"]) != expected_collision_triangles:
		failures.append("collision_triangle_count_exact")
	if not _bounds_match(render_metrics["bounds_xyz_m"], render_contract["bounds_xyz_m"]):
		failures.append("render_bounds_exact")
	if not _bounds_match(collision_metrics["bounds_xyz_m"], collision_contract["bounds_xyz_m"]):
		failures.append("collision_bounds_exact")
	if render_meshes.is_empty() or collision_meshes.is_empty():
		failures.append("mesh_instances_present")

	var physics_started := Time.get_ticks_usec()
	var static_body := StaticBody3D.new()
	var collision_layer := 1 << case_index
	static_body.collision_layer = collision_layer
	static_body.collision_mask = 0
	root_3d.add_child(static_body)
	var collision_shape_count := 0
	var ray_from := Vector3.ZERO
	var ray_to := Vector3.ZERO
	for instance in collision_meshes:
		if instance.mesh == null:
			continue
		var shape := instance.mesh.create_trimesh_shape()
		if shape == null:
			continue
		if shape is ConcavePolygonShape3D:
			(shape as ConcavePolygonShape3D).backface_collision = true
		var collision_shape := CollisionShape3D.new()
		collision_shape.shape = shape
		collision_shape.transform = instance.global_transform
		static_body.add_child(collision_shape)
		collision_shape_count += 1
		if ray_from == Vector3.ZERO and ray_to == Vector3.ZERO:
			var faces := instance.mesh.get_faces()
			if faces.size() >= 3:
				var first := instance.global_transform * faces[0]
				var second := instance.global_transform * faces[1]
				var third := instance.global_transform * faces[2]
				var center := (first + second + third) / 3.0
				var normal := (second - first).cross(third - first).normalized()
				ray_from = center + normal * 2.0
				ray_to = center - normal * 2.0
	var physics_build_usec := Time.get_ticks_usec() - physics_started
	if collision_shape_count == 0:
		failures.append("runtime_collision_shape_created")

	var node_count := int(navigation["graph"]["nodes"].size())
	var edge_count := int(navigation["graph"]["edges"].size())
	var astar := AStar3D.new()
	var node_indexes := {}
	for node_index in range(node_count):
		var node: Dictionary = navigation["graph"]["nodes"][node_index]
		var position: Array = node["position_xyz_m"]
		astar.add_point(node_index, Vector3(float(position[0]), float(position[1]), float(position[2])))
		node_indexes[str(node["node_id"])] = node_index
	for edge in navigation["graph"]["edges"]:
		var from_index := int(node_indexes.get(str(edge["from_node"]), -1))
		var to_index := int(node_indexes.get(str(edge["to_node"]), -1))
		if from_index >= 0 and to_index >= 0 and not astar.are_points_connected(from_index, to_index):
			astar.connect_points(from_index, to_index, true)
	var astar_all_nodes_reachable := node_count > 0
	for node_index in range(1, node_count):
		if astar.get_id_path(0, node_index).is_empty():
			astar_all_nodes_reachable = false
			break
	var route_count := 0
	for run in navigation["protected_route_voxel_runs"]:
		route_count += int(run[3])
	if node_count != int(manifest["navigation"]["node_count"]):
		failures.append("navigation_node_count_exact")
	if edge_count != int(manifest["navigation"]["edge_count"]):
		failures.append("navigation_edge_count_exact")
	if route_count != int(collision_contract["protected_route_voxel_count"]):
		failures.append("protected_route_count_exact")
	if astar.get_point_count() != node_count:
		failures.append("godot_astar_node_count_exact")
	if not astar_all_nodes_reachable:
		failures.append("godot_astar_all_nodes_reachable")

	return {
		"case_id": case_id,
		"source_id": case["source_id"],
		"state": "PASS" if failures.is_empty() else "FAIL",
		"failed_gates": failures,
		"load_milliseconds": load_usec / 1000.0,
		"instantiate_milliseconds": instantiate_usec / 1000.0,
		"physics_shape_build_milliseconds": physics_build_usec / 1000.0,
		"render": render_metrics,
		"collision": collision_metrics,
		"collision_shape_count": collision_shape_count,
		"godot_astar": {
			"point_count": astar.get_point_count(),
			"source_edge_count": edge_count,
			"all_nodes_reachable": astar_all_nodes_reachable,
		},
		"expected": {
			"render_triangle_count": expected_render_triangles,
			"collision_triangle_count": expected_collision_triangles,
			"navigation_node_count": node_count,
			"navigation_edge_count": edge_count,
			"protected_route_voxel_count": route_count,
		},
		"runtime_nodes": {
			"render_inside_tree": render_scene.is_inside_tree(),
			"collision_inside_tree": collision_scene.is_inside_tree(),
			"static_body_inside_tree": static_body.is_inside_tree(),
		},
		"ray_from": [ray_from.x, ray_from.y, ray_from.z],
		"ray_to": [ray_to.x, ray_to.y, ray_to.z],
		"ray_from_internal": ray_from,
		"ray_to_internal": ray_to,
		"static_body_internal": static_body,
		"collision_layer_internal": collision_layer,
	}
