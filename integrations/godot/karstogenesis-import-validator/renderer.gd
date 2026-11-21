extends SceneTree


func _load_json(path: String) -> Variant:
	var file := FileAccess.open(path, FileAccess.READ)
	if file == null:
		return null
	return JSON.parse_string(file.get_as_text())


func _collect_meshes(node: Node, output: Array[MeshInstance3D]) -> void:
	if node is MeshInstance3D:
		output.append(node as MeshInstance3D)
	for child in node.get_children():
		_collect_meshes(child, output)


func _select_view(navigation: Dictionary) -> Dictionary:
	var degrees := {}
	for node in navigation["graph"]["nodes"]:
		degrees[str(node["node_id"])] = 0
	for edge in navigation["graph"]["edges"]:
		degrees[str(edge["from_node"])] += 1
		degrees[str(edge["to_node"])] += 1
	var selected_node := ""
	var selected_degree := -1
	for node_id in degrees:
		if int(degrees[node_id]) > selected_degree:
			selected_node = str(node_id)
			selected_degree = int(degrees[node_id])
	var best_points: Array = []
	var best_length := -1.0
	for edge in navigation["graph"]["edges"]:
		if str(edge["from_node"]) != selected_node and str(edge["to_node"]) != selected_node:
			continue
		var points: Array = edge["centerline_xyz_m"]
		var length := 0.0
		for index in range(1, points.size()):
			var previous: Array = points[index - 1]
			var current: Array = points[index]
			length += Vector3(
				float(current[0]) - float(previous[0]),
				float(current[1]) - float(previous[1]),
				float(current[2]) - float(previous[2])
			).length()
		if length > best_length and points.size() >= 2:
			best_length = length
			best_points = points.duplicate(true)
			if str(edge["to_node"]) == selected_node:
				best_points.reverse()
	var first: Array = best_points[0]
	var target_index := mini(best_points.size() - 1, 3)
	var second: Array = best_points[target_index]
	var graph_position := Vector3(float(first[0]), float(first[1]), float(first[2]))
	var position := graph_position
	var nearest_route_distance := INF
	for run in navigation["protected_route_voxel_runs"]:
		for x in range(int(run[0]), int(run[0]) + int(run[3])):
			var candidate := Vector3(x + 0.5, float(run[1]) + 0.5, float(run[2]) + 0.5)
			var distance := candidate.distance_squared_to(graph_position)
			if distance < nearest_route_distance:
				nearest_route_distance = distance
				position = candidate
	var forward := Vector3(
		float(second[0]) - graph_position.x,
		float(second[1]) - graph_position.y,
		float(second[2]) - graph_position.z
	).normalized()
	if forward.length_squared() < 0.5:
		forward = Vector3.FORWARD
	return {
		"position": position,
		"forward": forward,
		"edge_length": best_length,
		"junction_degree": selected_degree,
	}


func _initialize() -> void:
	call_deferred("_render")


func _render() -> void:
	var arguments := OS.get_cmdline_user_args()
	if arguments.size() != 3 and arguments.size() != 4:
		push_error("expected case id, PNG output, metadata output and optional camera register")
		quit(2)
		return
	var case_id := arguments[0]
	var output_png := arguments[1]
	var output_json := arguments[2]
	var package_path := "res://cases/%s" % case_id
	var navigation: Variant = _load_json("%s/navigation.json" % package_path)
	var scene_resource := ResourceLoader.load("%s/cave-interior.glb" % package_path)
	if navigation == null or not scene_resource is PackedScene:
		push_error("could not load render inputs")
		quit(2)
		return

	DisplayServer.window_set_size(Vector2i(1280, 720))
	get_root().size = Vector2i(1280, 720)
	var root_3d := Node3D.new()
	get_root().add_child(root_3d)
	var cave := (scene_resource as PackedScene).instantiate()
	root_3d.add_child(cave)
	var meshes: Array[MeshInstance3D] = []
	_collect_meshes(cave, meshes)
	var material := ShaderMaterial.new()
	var shader := Shader.new()
	shader.code = """
shader_type spatial;
render_mode cull_disabled, diffuse_burley;
varying vec3 world_position;
float hash31(vec3 p) {
	p = fract(p * 0.1031);
	p += dot(p, p.yzx + 33.33);
	return fract((p.x + p.y) * p.z);
}
void vertex() {
	float first = sin(dot(VERTEX, vec3(1.71, 2.29, 1.13)));
	float second = sin(dot(VERTEX, vec3(4.07, 1.37, 3.11)) + 1.4);
	VERTEX += NORMAL * (first * 0.055 + second * 0.025);
	world_position = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
}
void fragment() {
	vec3 cell = floor(world_position * 0.72);
	float grain = hash31(cell) * 0.16;
	float strata = sin(world_position.y * 0.42 + hash31(floor(world_position * 0.11)) * 2.0) * 0.045;
	vec3 base = vec3(0.22, 0.265, 0.275) + vec3(grain + strata);
	ALBEDO = base;
	ROUGHNESS = 0.96;
}
"""
	material.shader = shader
	for mesh in meshes:
		mesh.material_override = material

	var environment := Environment.new()
	environment.background_mode = Environment.BG_COLOR
	environment.background_color = Color("070b0d")
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.ambient_light_color = Color("6d8490")
	environment.ambient_light_energy = 0.045
	environment.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	var world_environment := WorldEnvironment.new()
	world_environment.environment = environment
	root_3d.add_child(world_environment)

	var view := _select_view(navigation)
	if arguments.size() == 4:
		var camera_register: Variant = _load_json(arguments[3])
		if camera_register != null and camera_register["cases"].has(case_id):
			var registered: Dictionary = camera_register["cases"][case_id]
			var registered_position: Array = registered["position_xyz_m"]
			var registered_forward: Array = registered["forward_xyz"]
			view["position"] = Vector3(
				float(registered_position[0]),
				float(registered_position[1]),
				float(registered_position[2])
			)
			view["forward"] = Vector3(
				float(registered_forward[0]),
				float(registered_forward[1]),
				float(registered_forward[2])
			).normalized()
			view["clearance_layer"] = int(registered["clearance_layer"])
	var camera := Camera3D.new()
	camera.position = view["position"]
	camera.near = 0.04
	camera.far = 800.0
	camera.fov = 82.0
	root_3d.add_child(camera)
	var forward: Vector3 = view["forward"]
	var up := Vector3.UP if absf(forward.dot(Vector3.UP)) < 0.95 else Vector3.RIGHT
	camera.look_at(camera.position + forward, up)
	camera.current = true

	var key_light := OmniLight3D.new()
	key_light.position = camera.position + forward * 0.5
	key_light.light_color = Color("d8e8ee")
	key_light.light_energy = 1.35
	key_light.omni_range = 22.0
	key_light.shadow_enabled = false
	root_3d.add_child(key_light)
	var fill_light := OmniLight3D.new()
	fill_light.position = camera.position - forward * 2.0 + Vector3.UP * 1.5
	fill_light.light_color = Color("69a2b8")
	fill_light.light_energy = 0.32
	fill_light.omni_range = 16.0
	root_3d.add_child(fill_light)

	for _frame in range(12):
		await process_frame
	var image := get_root().get_texture().get_image()
	var save_error := image.save_png(output_png)
	var metadata := {
		"schema_version": "geoworld.karstogenesis-godot-render.v1",
		"case_id": case_id,
		"engine": Engine.get_version_info(),
		"display_server": DisplayServer.get_name(),
		"rendering_method": RenderingServer.get_current_rendering_method(),
		"rendering_device": RenderingServer.get_video_adapter_name(),
		"image_size": [image.get_width(), image.get_height()],
		"camera_position_xyz_m": [camera.position.x, camera.position.y, camera.position.z],
		"camera_forward_xyz": [forward.x, forward.y, forward.z],
		"selected_edge_length_m": view["edge_length"],
		"selected_junction_degree": view["junction_degree"],
		"camera_clearance_layer": view.get("clearance_layer", null),
		"mesh_instance_count": meshes.size(),
		"save_error": save_error,
		"maximum_shader_vertex_displacement_m": 0.08,
	}
	var metadata_file := FileAccess.open(output_json, FileAccess.WRITE)
	metadata_file.store_string(JSON.stringify(metadata, "\t") + "\n")
	metadata_file.close()
	quit(0 if save_error == OK else 1)
