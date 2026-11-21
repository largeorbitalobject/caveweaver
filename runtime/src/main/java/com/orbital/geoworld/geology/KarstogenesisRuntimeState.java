package com.orbital.geoworld.geology;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Immutable-package runtime with exact host binding and deferred protected closure. */
public final class KarstogenesisRuntimeState {
    private static final String CONFIG_SCHEMA_V1 = "geoworld.karst-runtime-config.v1";
    private static final String CONFIG_SCHEMA_V2 = "geoworld.karst-runtime-config.v2";
    private static final String CONFIG_SCHEMA_V3 = "geoworld.karst-runtime-config.v3";
    private static final TagKey<Block> PROTECTED_CLOSURE_REPLACEABLE = TagKey.of(
            RegistryKeys.BLOCK,
            Identifier.of("geoworld-karst-runtime", "protected_closure_replaceable"));
    private static final TagKey<Block> SURFACE_ENTRANCE_REPLACEABLE = TagKey.of(
            RegistryKeys.BLOCK,
            Identifier.of("geoworld-karst-runtime", "surface_entrance_replaceable"));
    private static final Set<String> CONFIG_KEYS_V1 = Set.of(
            "schema", "enabled", "expected_world_seed", "package_path",
            "binding_path", "status_path", "expected_package_identity",
            "expected_binding_sha256", "translation_x", "translation_y",
            "translation_z", "world_min_y", "world_max_y",
            "audit_min_chunk_x", "audit_max_chunk_x", "audit_min_chunk_z",
            "audit_max_chunk_z");
    private static final Set<String> CONFIG_KEYS_V2 = Set.of(
            "schema", "enabled", "expected_world_seed", "package_path",
            "binding_path", "status_path", "expected_package_identity",
            "expected_binding_sha256", "translation_x", "translation_y",
            "translation_z", "world_min_y", "world_max_y",
            "audit_min_chunk_x", "audit_max_chunk_x", "audit_min_chunk_z",
            "audit_max_chunk_z", "hydraulic_policy_path",
            "expected_hydraulic_policy_sha256");
    private static final Set<String> CONFIG_KEYS_V3 = Set.of(
            "schema", "enabled", "expected_world_seed", "package_path",
            "binding_path", "status_path", "expected_package_identity",
            "expected_binding_sha256", "translation_x", "translation_y",
            "translation_z", "world_min_y", "world_max_y",
            "audit_min_chunk_x", "audit_max_chunk_x", "audit_min_chunk_z",
            "audit_max_chunk_z", "hydraulic_policy_path",
            "expected_hydraulic_policy_sha256", "surface_entrance_path",
            "expected_surface_entrance_sha256");

    private record FrozenTarget(
            KarstDeltaChunkReader.Voxel voxel,
            boolean protectedRoute,
            boolean boundary,
            String action,
            String priorBlockState) {}

    private record StaticSeal(
            KarstDeltaChunkReader.Voxel voxel,
            BlockState material) {}

    private static final Map<String, List<FrozenTarget>> targetsByChunk = new HashMap<>();
    private static final Map<String, FrozenTarget> targetsByVoxel = new HashMap<>();
    private static final Set<String> targetChunkKeys = new HashSet<>();
    private static final Map<String, StaticSeal> staticSealsByVoxel = new HashMap<>();
    private static final Set<String> dryBasinVoxelKeys = new HashSet<>();
    private static final Set<String> intentionalHydrologyVoxelKeys = new HashSet<>();
    private static final Set<String> surfaceEntranceVoxelKeys = new HashSet<>();
    private static String surfaceEntranceAnchorKey = "";
    private static final Set<String> appliedChunks = new HashSet<>();
    private static final Set<String> observedAuditChunks = new HashSet<>();
    private static final List<String> errors = new ArrayList<>();
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static Path statusPath;
    private static long expectedWorldSeed;
    private static int worldMinY;
    private static int worldMaxY;
    private static int auditMinChunkX;
    private static int auditMaxChunkX;
    private static int auditMinChunkZ;
    private static int auditMaxChunkZ;
    private static int expectedAuditChunks;
    private static MinecraftServer server;
    private static boolean enabled;
    private static boolean seedValidated;
    private static boolean closureComplete;
    private static boolean hydraulicPolicyEnabled;
    private static int directWriteCount;
    private static int sealWriteCount;
    private static int drainWriteCount;
    private static int repairWriteCount;
    private static int entranceRepairWriteCount;

    private KarstogenesisRuntimeState() {}

    public static synchronized void initialize() {
        String override = System.getProperty("geoworld.karst.runtime.config", "").trim();
        Path configPath = override.isEmpty()
                ? FabricLoader.getInstance().getConfigDir().resolve("karstogenesis-runtime.properties")
                : Path.of(override).toAbsolutePath().normalize();
        if (!Files.isRegularFile(configPath)) {
            KarstogenesisFabricRuntime.LOGGER.info(
                    "Karst runtime disabled because config is absent: {}", configPath);
            return;
        }
        try {
            Properties config = loadConfig(configPath);
            String configSchema = required(config, "schema");
            enabled = Boolean.parseBoolean(required(config, "enabled"));
            if (!enabled) return;
            expectedWorldSeed = Long.parseLong(required(config, "expected_world_seed"));
            worldMinY = integer(config, "world_min_y");
            worldMaxY = integer(config, "world_max_y");
            auditMinChunkX = integer(config, "audit_min_chunk_x");
            auditMaxChunkX = integer(config, "audit_max_chunk_x");
            auditMinChunkZ = integer(config, "audit_min_chunk_z");
            auditMaxChunkZ = integer(config, "audit_max_chunk_z");
            if (worldMinY > worldMaxY
                    || auditMinChunkX > auditMaxChunkX
                    || auditMinChunkZ > auditMaxChunkZ) {
                throw new IllegalArgumentException("runtime bounds differ");
            }
            expectedAuditChunks = Math.multiplyExact(
                    auditMaxChunkX - auditMinChunkX + 1,
                    auditMaxChunkZ - auditMinChunkZ + 1);
            Path base = configPath.getParent();
            Path packagePath = resolve(base, required(config, "package_path"));
            Path bindingPath = resolve(base, required(config, "binding_path"));
            statusPath = resolve(base, required(config, "status_path"));
            String expectedIdentity = required(config, "expected_package_identity");
            String expectedBinding = required(config, "expected_binding_sha256");
            requireHex(expectedIdentity, "package identity");
            requireHex(expectedBinding, "binding identity");
            if (!sha256(bindingPath).equals(expectedBinding)) {
                throw new IllegalArgumentException("runtime binding digest differs");
            }
            reader = KarstDeltaChunkReader.open(packagePath, true);
            if (!reader.cacheIdentitySha256().equals(expectedIdentity)) {
                throw new IllegalArgumentException("runtime package identity differs");
            }
            planner = KarstDeltaChunkReader.MinecraftIntentPlanner.bind(
                    reader,
                    expectedIdentity,
                    new KarstDeltaChunkReader.Translation(
                            integer(config, "translation_x"),
                            integer(config, "translation_y"),
                            integer(config, "translation_z")),
                    new KarstDeltaChunkReader.WorldBounds(
                            new KarstDeltaChunkReader.Voxel(
                                    -30_000_000, worldMinY, -30_000_000),
                            new KarstDeltaChunkReader.Voxel(
                                    29_999_999, worldMaxY, 29_999_999)));
            if (planner.bindingState() != KarstDeltaChunkReader.PlanState.ACTIVE) {
                throw new IllegalArgumentException("runtime planner binding is inactive");
            }
            loadBinding(bindingPath);
            validatePackagePlans();
            if (CONFIG_SCHEMA_V2.equals(configSchema) || CONFIG_SCHEMA_V3.equals(configSchema)) {
                Path hydraulicPolicyPath = resolve(
                        base, required(config, "hydraulic_policy_path"));
                String expectedHydraulicPolicy = required(
                        config, "expected_hydraulic_policy_sha256");
                requireHex(expectedHydraulicPolicy, "hydraulic policy identity");
                if (!sha256(hydraulicPolicyPath).equals(expectedHydraulicPolicy)) {
                    throw new IllegalArgumentException("hydraulic policy digest differs");
                }
                loadHydraulicPolicy(hydraulicPolicyPath);
                validateHydraulicPolicy();
                hydraulicPolicyEnabled = true;
            }
            if (CONFIG_SCHEMA_V3.equals(configSchema)) {
                Path surfaceEntrancePath = resolve(
                        base, required(config, "surface_entrance_path"));
                String expectedSurfaceEntrance = required(
                        config, "expected_surface_entrance_sha256");
                requireHex(expectedSurfaceEntrance, "surface entrance identity");
                if (!sha256(surfaceEntrancePath).equals(expectedSurfaceEntrance)) {
                    throw new IllegalArgumentException("surface entrance digest differs");
                }
                loadSurfaceEntrance(surfaceEntrancePath);
                validateSurfaceEntrance();
            }
            KarstogenesisFabricRuntime.LOGGER.info(
                    "Karst runtime active package={} targets={} chunks={} audit-chunks={} hydraulic-policy={} entrance={}",
                    expectedIdentity, targetCount(), targetChunkKeys.size(), expectedAuditChunks,
                    hydraulicPolicyEnabled, surfaceEntranceVoxelKeys.size());
        } catch (IOException | RuntimeException exception) {
            enabled = false;
            errors.add(errorSummary(exception));
            KarstogenesisFabricRuntime.LOGGER.error(
                    "Karst runtime failed closed during initialization", exception);
        }
    }

    private static Properties loadConfig(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            result.load(stream);
        }
        String schema = result.getProperty("schema", "").trim();
        Set<String> keys;
        if (CONFIG_SCHEMA_V1.equals(schema)) {
            keys = CONFIG_KEYS_V1;
        } else if (CONFIG_SCHEMA_V2.equals(schema)) {
            keys = CONFIG_KEYS_V2;
        } else if (CONFIG_SCHEMA_V3.equals(schema)) {
            keys = CONFIG_KEYS_V3;
        } else {
            throw new IllegalArgumentException("runtime config schema differs");
        }
        for (String key : result.stringPropertyNames()) {
            if (!keys.contains(key)) {
                throw new IllegalArgumentException("unknown runtime config key: " + key);
            }
        }
        for (String key : keys) required(result, key);
        return result;
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing runtime config key: " + key);
        return value;
    }

    private static int integer(Properties values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }

    private static void requireHex(String value, String label) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is not a SHA-256 digest");
        }
    }

    private static void loadBinding(Path path) throws IOException {
        targetsByChunk.clear();
        targetsByVoxel.clear();
        targetChunkKeys.clear();
        Set<String> voxels = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", 7);
            if (fields.length != 7) throw new IllegalArgumentException("binding row width differs");
            KarstDeltaChunkReader.Voxel voxel = new KarstDeltaChunkReader.Voxel(
                    Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]));
            String voxelKey = voxelKey(voxel);
            if (!voxels.add(voxelKey)) throw new IllegalArgumentException("binding repeats a voxel");
            String action = fields[5];
            if (!action.equals("CARVE") && !action.equals("NOOP_EXISTING_VOID")) {
                throw new IllegalArgumentException("binding contains a non-admitted action");
            }
            FrozenTarget target = new FrozenTarget(
                    voxel, Boolean.parseBoolean(fields[3]), Boolean.parseBoolean(fields[4]),
                    action, fields[6]);
            targetsByVoxel.put(voxelKey, target);
            String chunk = chunkKey(Math.floorDiv(voxel.x(), 16), Math.floorDiv(voxel.z(), 16));
            targetsByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(target);
            targetChunkKeys.add(chunk);
        }
        if (voxels.isEmpty()) throw new IllegalArgumentException("binding is empty");
        for (List<FrozenTarget> values : targetsByChunk.values()) {
            values.sort(Comparator.comparing(value -> voxelKey(value.voxel())));
        }
    }

    private static void loadHydraulicPolicy(Path path) throws IOException {
        staticSealsByVoxel.clear();
        dryBasinVoxelKeys.clear();
        intentionalHydrologyVoxelKeys.clear();
        Set<String> classified = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", 5);
            if (fields.length != 5) {
                throw new IllegalArgumentException("hydraulic policy row width differs");
            }
            String type = fields[0];
            KarstDeltaChunkReader.Voxel voxel = new KarstDeltaChunkReader.Voxel(
                    Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]));
            String key = voxelKey(voxel);
            if (!classified.add(key)) {
                throw new IllegalArgumentException("hydraulic policy repeats a voxel");
            }
            if (!targetsByVoxel.containsKey(key)) {
                throw new IllegalArgumentException("hydraulic policy voxel is outside binding");
            }
            switch (type) {
                case "DRY_BASIN" -> {
                    if (!fields[4].equals("-")) {
                        throw new IllegalArgumentException("dry basin material differs");
                    }
                    dryBasinVoxelKeys.add(key);
                }
                case "INTENTIONAL_HYDROLOGY" -> {
                    if (!fields[4].equals("-")) {
                        throw new IllegalArgumentException("intentional hydrology material differs");
                    }
                    intentionalHydrologyVoxelKeys.add(key);
                }
                case "STATIC_SEAL" -> {
                    Identifier identifier = Identifier.tryParse(fields[4]);
                    if (identifier == null || !Registries.BLOCK.containsId(identifier)) {
                        throw new IllegalArgumentException("static seal material is unresolved");
                    }
                    BlockState material = Registries.BLOCK.get(identifier).getDefaultState();
                    if (material.isAir()
                            || !material.getFluidState().isEmpty()
                            || material.hasBlockEntity()) {
                        throw new IllegalArgumentException("static seal material is unsafe");
                    }
                    staticSealsByVoxel.put(key, new StaticSeal(voxel, material));
                }
                default -> throw new IllegalArgumentException(
                        "unknown hydraulic policy type: " + type);
            }
        }
        if (classified.isEmpty()) {
            throw new IllegalArgumentException("hydraulic policy is empty");
        }
    }

    private static void loadSurfaceEntrance(Path path) throws IOException {
        surfaceEntranceVoxelKeys.clear();
        surfaceEntranceAnchorKey = "";
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", 4);
            if (fields.length != 4) {
                throw new IllegalArgumentException("surface entrance row width differs");
            }
            KarstDeltaChunkReader.Voxel voxel = new KarstDeltaChunkReader.Voxel(
                    Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]));
            String key = voxelKey(voxel);
            if (!targetsByVoxel.containsKey(key)) {
                throw new IllegalArgumentException("surface entrance voxel is outside binding");
            }
            if (!surfaceEntranceVoxelKeys.add(key)) {
                throw new IllegalArgumentException("surface entrance repeats a voxel");
            }
            if ("ANCHOR".equals(fields[3])) {
                if (!surfaceEntranceAnchorKey.isEmpty()) {
                    throw new IllegalArgumentException("surface entrance repeats its anchor");
                }
                surfaceEntranceAnchorKey = key;
            } else if (!"MOUTH".equals(fields[3])) {
                throw new IllegalArgumentException("surface entrance role differs");
            }
        }
        if (surfaceEntranceVoxelKeys.isEmpty() || surfaceEntranceAnchorKey.isEmpty()) {
            throw new IllegalArgumentException("surface entrance is incomplete");
        }
    }

    private static void validateSurfaceEntrance() {
        FrozenTarget anchor = targetsByVoxel.get(surfaceEntranceAnchorKey);
        if (anchor == null || !anchor.protectedRoute()) {
            throw new IllegalArgumentException("surface entrance anchor is outside protected route");
        }
        Set<String> found = new HashSet<>();
        ArrayDeque<KarstDeltaChunkReader.Voxel> queue = new ArrayDeque<>();
        queue.add(anchor.voxel());
        found.add(surfaceEntranceAnchorKey);
        int[][] offsets = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0},
            {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        while (!queue.isEmpty()) {
            KarstDeltaChunkReader.Voxel voxel = queue.removeFirst();
            for (int[] offset : offsets) {
                KarstDeltaChunkReader.Voxel neighbor = new KarstDeltaChunkReader.Voxel(
                        voxel.x() + offset[0], voxel.y() + offset[1], voxel.z() + offset[2]);
                String key = voxelKey(neighbor);
                if (surfaceEntranceVoxelKeys.contains(key) && found.add(key)) {
                    queue.addLast(neighbor);
                }
            }
        }
        if (!found.equals(surfaceEntranceVoxelKeys)) {
            throw new IllegalArgumentException("surface entrance is disconnected");
        }
    }

    private static void validateHydraulicPolicy() {
        int classified = staticSealsByVoxel.size()
                + dryBasinVoxelKeys.size()
                + intentionalHydrologyVoxelKeys.size();
        if (classified != targetCount()) {
            throw new IllegalArgumentException("hydraulic policy coverage differs");
        }
        for (Map.Entry<String, FrozenTarget> entry : targetsByVoxel.entrySet()) {
            String key = entry.getKey();
            FrozenTarget target = entry.getValue();
            int classes = (staticSealsByVoxel.containsKey(key) ? 1 : 0)
                    + (dryBasinVoxelKeys.contains(key) ? 1 : 0)
                    + (intentionalHydrologyVoxelKeys.contains(key) ? 1 : 0);
            if (classes != 1) {
                throw new IllegalArgumentException("hydraulic policy partition differs");
            }
            if (target.protectedRoute() && !dryBasinVoxelKeys.contains(key)) {
                throw new IllegalArgumentException("protected route is outside dry basin");
            }
            if (target.boundary()
                    && !target.protectedRoute()
                    && !intentionalHydrologyVoxelKeys.contains(key)
                    && !dryBasinVoxelKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "hydrological boundary has no admitted hydraulic class");
            }
            if (staticSealsByVoxel.containsKey(key)
                    && (target.protectedRoute() || target.boundary())) {
                throw new IllegalArgumentException("static seal overlaps route topology");
            }
        }
    }

    private static void validatePackagePlans() {
        Set<String> seen = new HashSet<>();
        KarstDeltaChunkReader.HostMinecraftRegistry neutral = neutralRegistry();
        for (KarstDeltaChunkReader.ChunkKey key : planner.candidateWorldChunks()) {
            KarstDeltaChunkReader.ChunkPlan plan = planner.planChunk(
                    key.x(), key.z(), worldMinY, worldMaxY, neutral);
            if (plan.state() != KarstDeltaChunkReader.PlanState.ACTIVE || plan.planned().isEmpty()) {
                continue;
            }
            String chunk = chunkKey(key.x(), key.z());
            List<FrozenTarget> frozen = targetsByChunk.get(chunk);
            if (frozen == null || frozen.size() != plan.planned().size()) {
                throw new IllegalArgumentException("package plan and binding chunk differ: " + chunk);
            }
            Map<String, FrozenTarget> byVoxel = new HashMap<>();
            for (FrozenTarget value : frozen) byVoxel.put(voxelKey(value.voxel()), value);
            for (KarstDeltaChunkReader.PlannedVoxel value : plan.planned()) {
                FrozenTarget target = byVoxel.get(voxelKey(value.worldVoxel()));
                if (target == null
                        || target.protectedRoute() != value.protectedRoute()
                        || target.boundary() != value.registeredBoundary()) {
                    throw new IllegalArgumentException("package plan topology differs");
                }
                seen.add(voxelKey(value.worldVoxel()));
            }
        }
        if (seen.size() != targetCount()) {
            throw new IllegalArgumentException("package plan coverage differs");
        }
    }

    private static KarstDeltaChunkReader.HostMinecraftRegistry neutralRegistry() {
        return new KarstDeltaChunkReader.HostMinecraftRegistry() {
            public KarstDeltaChunkReader.Replaceability replaceabilityFor(
                    KarstDeltaChunkReader.Voxel voxel,
                    KarstDeltaChunkReader.VoxelIntent intent) {
                return KarstDeltaChunkReader.Replaceability.REPLACE;
            }

            public String materialFor(
                    KarstDeltaChunkReader.Voxel voxel,
                    KarstDeltaChunkReader.VoxelIntent intent) {
                return "host:binding";
            }

            public String biomeFor(
                    KarstDeltaChunkReader.Voxel voxel,
                    KarstDeltaChunkReader.VoxelIntent intent) {
                return "host:preserve-existing";
            }
        };
    }

    public static synchronized void onServerStarted(MinecraftServer value) {
        server = value;
    }

    public static synchronized void onServerStopping(MinecraftServer value) {
        writeStatus("server-stopping");
        server = null;
    }

    public static synchronized void applyCarveTail(
            ChunkRegion world,
            long seed,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        if (!enabled) return;
        if (!World.OVERWORLD.equals(world.toServerWorld().getRegistryKey())) return;
        String chunkKey = chunkKey(chunk.getPos().x, chunk.getPos().z);
        if (!targetChunkKeys.contains(chunkKey) || appliedChunks.contains(chunkKey)) return;
        try {
            if (seed != expectedWorldSeed) {
                throw new IllegalStateException("world seed differs from admitted binding");
            }
            seedValidated = true;
            List<FrozenTarget> targets = targetsByChunk.get(chunkKey);
            List<StructureStart> starts =
                    structureAccessor.getStructureStarts(chunk.getPos(), entry -> true);
            List<FrozenTarget> liveCarves = new ArrayList<>();
            for (FrozenTarget target : targets) {
                if (validateTarget(chunk, starts, target)) liveCarves.add(target);
            }
            for (FrozenTarget target : liveCarves) {
                chunk.setBlockState(
                        blockPos(target.voxel()), Blocks.CAVE_AIR.getDefaultState(), false);
                directWriteCount++;
            }
            appliedChunks.add(chunkKey);
        } catch (RuntimeException exception) {
            enabled = false;
            errors.add(chunkKey + ":" + errorSummary(exception));
            KarstogenesisFabricRuntime.LOGGER.error(
                    "Karst runtime disabled after chunk validation failure {}", chunkKey, exception);
        }
    }

    private static boolean validateTarget(
            Chunk chunk,
            List<StructureStart> starts,
            FrozenTarget target) {
        BlockPos position = blockPos(target.voxel());
        BlockState state = chunk.getBlockState(position);
        if (state.hasBlockEntity() || structurePieceContains(starts, position)) {
            throw new IllegalStateException("host ownership conflict at " + voxelKey(target.voxel()));
        }
        if (state.isAir()) return false;
        if (!state.isIn(BlockTags.OVERWORLD_CARVER_REPLACEABLES)
                || !state.getFluidState().isEmpty()) {
            throw new IllegalStateException(
                    "carve-tail replaceability differs at " + voxelKey(target.voxel())
                            + " live=" + state
                            + " bound-action=" + target.action()
                            + " bound-post-feature=" + target.priorBlockState());
        }
        return true;
    }

    private static boolean structurePieceContains(List<StructureStart> starts, BlockPos position) {
        for (StructureStart start : starts) {
            if (!start.hasChildren()) continue;
            for (StructurePiece piece : start.getChildren()) {
                if (piece.getBoundingBox().contains(position)) return true;
            }
        }
        return false;
    }

    public static synchronized void observeFeaturesTail(
            StructureWorldAccess world,
            Chunk chunk) {
        if (!enabled || closureComplete) return;
        if (!World.OVERWORLD.equals(world.toServerWorld().getRegistryKey())) return;
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        if (auditMinChunkX <= x && x <= auditMaxChunkX
                && auditMinChunkZ <= z && z <= auditMaxChunkZ) {
            observedAuditChunks.add(chunkKey(x, z));
        }
    }

    public static synchronized void onServerTick(MinecraftServer value) {
        if (!enabled || closureComplete || !seedValidated || server == null) return;
        if (appliedChunks.size() != targetChunkKeys.size()
                || observedAuditChunks.size() != expectedAuditChunks) {
            return;
        }
        try {
            List<StaticSeal> liveSeals = new ArrayList<>();
            List<FrozenTarget> liveDrains = new ArrayList<>();
            List<FrozenTarget> liveRepairs = new ArrayList<>();
            List<FrozenTarget> liveEntranceRepairs = new ArrayList<>();
            if (hydraulicPolicyEnabled) {
                for (StaticSeal seal : staticSealsByVoxel.values()) {
                    Chunk chunk = value.getOverworld().getChunk(
                            Math.floorDiv(seal.voxel().x(), 16),
                            Math.floorDiv(seal.voxel().z(), 16));
                    BlockState state = chunk.getBlockState(blockPos(seal.voxel()));
                    if (state.equals(seal.material())) continue;
                    if (state.hasBlockEntity()
                            || (!state.isAir()
                            && state.getFluidState().isEmpty()
                            && !state.isIn(PROTECTED_CLOSURE_REPLACEABLE))) {
                        throw new IllegalStateException(
                                "static seal ownership conflict at " + voxelKey(seal.voxel()));
                    }
                    liveSeals.add(seal);
                }
                for (String key : dryBasinVoxelKeys) {
                    FrozenTarget target = targetsByVoxel.get(key);
                    Chunk chunk = value.getOverworld().getChunk(
                            Math.floorDiv(target.voxel().x(), 16),
                            Math.floorDiv(target.voxel().z(), 16));
                    BlockState state = chunk.getBlockState(blockPos(target.voxel()));
                    if (!state.getFluidState().isEmpty()) liveDrains.add(target);
                }
            }
            for (List<FrozenTarget> targets : targetsByChunk.values()) {
                for (FrozenTarget target : targets) {
                    if (!target.protectedRoute()) continue;
                    Chunk chunk = value.getOverworld().getChunk(
                            Math.floorDiv(target.voxel().x(), 16),
                            Math.floorDiv(target.voxel().z(), 16));
                    BlockPos position = blockPos(target.voxel());
                    BlockState state = chunk.getBlockState(position);
                    if (state.isAir()) continue;
                    if (hydraulicPolicyEnabled
                            && !state.getFluidState().isEmpty()
                            && dryBasinVoxelKeys.contains(voxelKey(target.voxel()))) {
                        continue;
                    }
                    if (state.hasBlockEntity()
                            || !state.getFluidState().isEmpty()
                            || !state.isIn(PROTECTED_CLOSURE_REPLACEABLE)) {
                        throw new IllegalStateException(
                                "protected closure ownership conflict at "
                                        + voxelKey(target.voxel()));
                    }
                    liveRepairs.add(target);
                }
            }
            for (String key : surfaceEntranceVoxelKeys) {
                if (staticSealsByVoxel.containsKey(key)) continue;
                FrozenTarget target = targetsByVoxel.get(key);
                if (target.protectedRoute()) continue;
                Chunk chunk = value.getOverworld().getChunk(
                        Math.floorDiv(target.voxel().x(), 16),
                        Math.floorDiv(target.voxel().z(), 16));
                BlockState state = chunk.getBlockState(blockPos(target.voxel()));
                if (state.isAir()) continue;
                if (hydraulicPolicyEnabled
                        && !state.getFluidState().isEmpty()
                        && dryBasinVoxelKeys.contains(key)) {
                    continue;
                }
                if (state.hasBlockEntity()
                        || !state.getFluidState().isEmpty()
                        || !state.isIn(SURFACE_ENTRANCE_REPLACEABLE)) {
                    throw new IllegalStateException(
                            "surface entrance closure ownership conflict at " + key);
                }
                liveEntranceRepairs.add(target);
            }
            for (StaticSeal seal : liveSeals) {
                Chunk chunk = value.getOverworld().getChunk(
                        Math.floorDiv(seal.voxel().x(), 16),
                        Math.floorDiv(seal.voxel().z(), 16));
                chunk.setBlockState(blockPos(seal.voxel()), seal.material(), false);
                sealWriteCount++;
            }
            for (FrozenTarget target : liveDrains) {
                Chunk chunk = value.getOverworld().getChunk(
                        Math.floorDiv(target.voxel().x(), 16),
                        Math.floorDiv(target.voxel().z(), 16));
                chunk.setBlockState(
                        blockPos(target.voxel()), Blocks.CAVE_AIR.getDefaultState(), false);
                drainWriteCount++;
            }
            for (FrozenTarget target : liveRepairs) {
                Chunk chunk = value.getOverworld().getChunk(
                        Math.floorDiv(target.voxel().x(), 16),
                        Math.floorDiv(target.voxel().z(), 16));
                chunk.setBlockState(
                        blockPos(target.voxel()), Blocks.CAVE_AIR.getDefaultState(), false);
                repairWriteCount++;
            }
            for (FrozenTarget target : liveEntranceRepairs) {
                Chunk chunk = value.getOverworld().getChunk(
                        Math.floorDiv(target.voxel().x(), 16),
                        Math.floorDiv(target.voxel().z(), 16));
                chunk.setBlockState(
                        blockPos(target.voxel()), Blocks.CAVE_AIR.getDefaultState(), false);
                entranceRepairWriteCount++;
            }
            closureComplete = true;
            writeStatus("closure-complete");
            KarstogenesisFabricRuntime.LOGGER.info(
                    "Karst runtime closure complete direct={} seal={} drain={} repair={} entrance-repair={} chunks={}",
                    directWriteCount, sealWriteCount, drainWriteCount,
                    repairWriteCount, entranceRepairWriteCount, appliedChunks.size());
        } catch (RuntimeException exception) {
            enabled = false;
            errors.add("closure:" + errorSummary(exception));
            KarstogenesisFabricRuntime.LOGGER.error(
                    "Karst runtime disabled during protected closure", exception);
        }
    }

    private static void writeStatus(String state) {
        if (statusPath == null) return;
        String payload = "{"
                + "\"schema_version\":\"geoworld.karst-runtime-status.v4\""
                + ",\"state\":\"" + escape(state) + "\""
                + ",\"enabled\":" + enabled
                + ",\"seed_validated\":" + seedValidated
                + ",\"closure_complete\":" + closureComplete
                + ",\"target_chunk_count\":" + targetChunkKeys.size()
                + ",\"applied_chunk_count\":" + appliedChunks.size()
                + ",\"observed_audit_chunk_count\":" + observedAuditChunks.size()
                + ",\"expected_audit_chunk_count\":" + expectedAuditChunks
                + ",\"direct_write_count\":" + directWriteCount
                + ",\"hydraulic_policy_enabled\":" + hydraulicPolicyEnabled
                + ",\"static_seal_count\":" + staticSealsByVoxel.size()
                + ",\"dry_basin_count\":" + dryBasinVoxelKeys.size()
                + ",\"intentional_hydrology_count\":" + intentionalHydrologyVoxelKeys.size()
                + ",\"surface_entrance_count\":" + surfaceEntranceVoxelKeys.size()
                + ",\"surface_entrance_anchor_count\":"
                + (surfaceEntranceAnchorKey.isEmpty() ? 0 : 1)
                + ",\"seal_write_count\":" + sealWriteCount
                + ",\"drain_write_count\":" + drainWriteCount
                + ",\"repair_write_count\":" + repairWriteCount
                + ",\"entrance_repair_write_count\":" + entranceRepairWriteCount
                + ",\"error_count\":" + errors.size()
                + "}\n";
        try {
            Files.createDirectories(statusPath.getParent());
            Path temporary = statusPath.resolveSibling(statusPath.getFileName() + ".part");
            Files.writeString(temporary, payload, StandardCharsets.UTF_8);
            Files.move(temporary, statusPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            errors.add("status:" + errorSummary(exception));
            KarstogenesisFabricRuntime.LOGGER.error("Karst runtime status write failed", exception);
        }
    }

    private static int targetCount() {
        return targetsByChunk.values().stream().mapToInt(List::size).sum();
    }

    private static BlockPos blockPos(KarstDeltaChunkReader.Voxel value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static String chunkKey(int x, int z) {
        return x + "," + z;
    }

    private static String voxelKey(KarstDeltaChunkReader.Voxel value) {
        return value.x() + "," + value.y() + "," + value.z();
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        digest.update(Files.readAllBytes(path));
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) {
            output.append(String.format(Locale.ROOT, "%02x", value));
        }
        return output.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String errorSummary(Throwable value) {
        String message = value.getMessage();
        return value.getClass().getSimpleName()
                + (message == null ? "" : ":" + message.replace('\n', ' '));
    }
}
