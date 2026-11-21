package com.orbital.geoworld.geology;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Read-only cache for immutable surface-relative int8 SDF tiles. */
public final class GeologyTileCache {
    private static final Map<Long, TileData> TILES = new ConcurrentHashMap<>();
    private static final Set<Long> PERMANENT_FALLBACK = ConcurrentHashMap.newKeySet();
    private static volatile Config CONFIG;
    private static volatile boolean LOAD_ATTEMPTED;
    private static volatile byte[] CW10_PROTECTED_ROUTE;
    private static final Path METRICS_PATH = metricsPath();
    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder PROTECTED = new LongAdder();
    private static final LongAdder TILE_FALLBACKS = new LongAdder();
    private static final LongAdder OUT_OF_BOUNDS = new LongAdder();
    private static final LongAdder IN_BOUNDS = new LongAdder();
    private static final LongAdder NEGATIVE_SDF_HITS = new LongAdder();
    private static final LongAdder CARVED = new LongAdder();
    private static final LongAdder FINALIZED_APPLY_RUNS = new LongAdder();
    private static final LongAdder FINALIZED_BLOCKS_MODIFIED = new LongAdder();
    private static final LongAdder CW10_AIR_BLOCKS_MODIFIED = new LongAdder();
    private static final LongAdder CW10_WATER_BLOCKS_MODIFIED = new LongAdder();

    static {
        if (METRICS_PATH != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(GeologyTileCache::writeMetrics));
        }
    }

    private GeologyTileCache() {}

    /** Fails server initialization when a required world/release pin cannot be proven. */
    public static void requireReleaseAdmission() {
        ReleaseAdmission.requireConfigured();
    }

    public static double apply(int worldX, int worldY, int worldZ, double baseDensity) {
        Config config = config();
        if (config == null || !config.enabled || !"coexist".equals(config.mode)) return baseDensity;
        CALLS.increment();
        if (!Double.isFinite(baseDensity)) return baseDensity;
        if (baseDensity < config.surfaceProtectionBlocks) {
            PROTECTED.increment();
            return baseDensity;
        }
        int tileX = Math.floorDiv(worldX, config.tileBlocks);
        int tileZ = Math.floorDiv(worldZ, config.tileBlocks);
        TileData data = tile(tileX, tileZ, config);
        if (data == null) {
            TILE_FALLBACKS.increment();
            return baseDensity;
        }
        int localX = Math.floorMod(worldX, config.tileBlocks) / config.voxelBlocks;
        int localZ = Math.floorMod(worldZ, config.tileBlocks) / config.voxelBlocks;
        int depth = (int) Math.floor(baseDensity / config.voxelBlocks);
        if (localX < 0 || localX >= config.width || localZ < 0 || localZ >= config.height
                || depth < 0 || depth >= config.depth) {
            OUT_OF_BOUNDS.increment();
            return baseDensity;
        }
        IN_BOUNDS.increment();
        int index = (depth * config.height + localZ) * config.width + localX;
        double geologyDensity = data.sdf[index] / config.quantizationPerDensity;
        if (geologyDensity < 0.0) {
            NEGATIVE_SDF_HITS.increment();
            double result = Math.min(baseDensity, geologyDensity);
            if (result != baseDensity) CARVED.increment();
            return result;
        }
        return baseDensity;
    }

    public static boolean postNoiseEnabled() {
        Config config = config();
        return config != null && config.enabled && "post_noise_air".equals(config.mode);
    }

    public static boolean finalizedRegionEnabled() {
        Config config = config();
        return config != null && config.enabled && "finalized_region_air".equals(config.mode);
    }

    public static int finalizedHaloChunks() {
        Config config = config();
        return config != null && config.enabled
                && ("finalized_region_air".equals(config.mode)
                || "cw10_absolute_classes".equals(config.mode))
                ? config.requiredFinalizedHaloChunks : 0;
    }

    public static int tileBlocks() {
        Config config = config();
        if (config == null) throw new IllegalStateException("geology cache is not configured");
        return config.tileBlocks;
    }

    public static List<TileCoordinate> finalizedTiles() {
        Config config = config();
        if (config == null || !config.enabled
                || !("finalized_region_air".equals(config.mode)
                || "cw10_absolute_classes".equals(config.mode))) return List.of();
        return finalizedTilesForConfig(config);
    }

    /** Loads and hash-verifies every payload before a finalized application may mutate blocks. */
    public static boolean preflightFinalizedTiles() {
        Config config = config();
        List<TileCoordinate> coordinates = finalizedTiles();
        if (!finalizedRegionEnabled() || coordinates.isEmpty()) return false;
        for (TileCoordinate coordinate : coordinates) {
            TileData data = tile(coordinate.x(), coordinate.z(), config);
            if (data == null || data.surface == null) return false;
        }
        return true;
    }

    /** Enumerates only negative-SDF blocks after all required chunks have been preflighted. */
    public static long visitFinalizedCarveBlocks(CarveBlockConsumer consumer) {
        Config config = config();
        if (!finalizedRegionEnabled()) return 0;
        long candidates = 0;
        for (TileCoordinate coordinate : finalizedTiles()) {
            TileData data = tile(coordinate.x(), coordinate.z(), config);
            if (data == null || data.surface == null) continue;
            int tileStartX = coordinate.x() * config.tileBlocks;
            int tileStartZ = coordinate.z() * config.tileBlocks;
            for (int depth = 0; depth < config.depth; depth++) {
                for (int localZ = 0; localZ < config.height; localZ++) {
                    for (int localX = 0; localX < config.width; localX++) {
                        int index = (depth * config.height + localZ) * config.width + localX;
                        if (data.sdf[index] >= 0) continue;
                        int surfaceY = data.surface[localZ * config.width + localX];
                        for (int fineZ = 0; fineZ < config.voxelBlocks; fineZ++) {
                            int worldZ = tileStartZ + localZ * config.voxelBlocks + fineZ;
                            for (int fineX = 0; fineX < config.voxelBlocks; fineX++) {
                                int worldX = tileStartX + localX * config.voxelBlocks + fineX;
                                for (int offset = 0; offset < config.voxelBlocks; offset++) {
                                    int depthBlocks = depth * config.voxelBlocks + offset;
                                    if (depthBlocks < config.surfaceProtectionBlocks) continue;
                                    CALLS.increment();
                                    IN_BOUNDS.increment();
                                    NEGATIVE_SDF_HITS.increment();
                                    int worldY = surfaceY - depthBlocks;
                                    if (consumer.accept(worldX, worldY, worldZ)) {
                                        CARVED.increment();
                                        FINALIZED_BLOCKS_MODIFIED.increment();
                                    }
                                    candidates++;
                                }
                            }
                        }
                    }
                }
            }
        }
        FINALIZED_APPLY_RUNS.increment();
        return candidates;
    }

    /** Returns true only for an in-bounds negative SDF below the protected surface shell. */
    public static boolean shouldCarve(int worldX, int worldY, int worldZ) {
        Config config = config();
        if (config == null || !config.enabled || !"post_noise_air".equals(config.mode)) return false;
        CALLS.increment();
        int tileX = Math.floorDiv(worldX, config.tileBlocks);
        int tileZ = Math.floorDiv(worldZ, config.tileBlocks);
        TileData data = tile(tileX, tileZ, config);
        if (data == null || data.surface == null) {
            TILE_FALLBACKS.increment();
            return false;
        }
        int localX = Math.floorMod(worldX, config.tileBlocks) / config.voxelBlocks;
        int localZ = Math.floorMod(worldZ, config.tileBlocks) / config.voxelBlocks;
        if (localX < 0 || localX >= config.width || localZ < 0 || localZ >= config.height) {
            OUT_OF_BOUNDS.increment();
            return false;
        }
        int surfaceY = data.surface[localZ * config.width + localX];
        int depthBlocks = surfaceY - worldY;
        if (depthBlocks < config.surfaceProtectionBlocks) {
            PROTECTED.increment();
            return false;
        }
        int depth = Math.floorDiv(depthBlocks, config.voxelBlocks);
        if (depth < 0 || depth >= config.depth) {
            OUT_OF_BOUNDS.increment();
            return false;
        }
        IN_BOUNDS.increment();
        int index = (depth * config.height + localZ) * config.width + localX;
        if (data.sdf[index] < 0) {
            NEGATIVE_SDF_HITS.increment();
            CARVED.increment();
            return true;
        }
        return false;
    }

    private static TileData tile(int tileX, int tileZ, Config config) {
        long key = (((long) tileX) << 32) ^ (tileZ & 0xffffffffL);
        TileData cached = TILES.get(key);
        if (cached != null) return cached;
        if (PERMANENT_FALLBACK.contains(key)) return null;
        TileRecord record = config.tiles.get(tileX + "," + tileZ);
        if (record == null) {
            PERMANENT_FALLBACK.add(key);
            return null;
        }
        try {
            Path path = config.root.resolve(record.file).normalize();
            if (!path.startsWith(config.root) || !Files.isRegularFile(path)) throw new IllegalArgumentException("unsafe/missing tile path");
            byte[] content = Files.readAllBytes(path);
            int expected = config.depth * config.height * config.width;
            if (content.length != expected) throw new IllegalArgumentException("tile byte count mismatch");
            if (!sha256(content).equalsIgnoreCase(record.sha256)) throw new IllegalArgumentException("tile hash mismatch");
            if ("cw10_absolute_classes".equals(config.mode)) {
                for (byte value : content) {
                    if ((value & 0xff) > 2) throw new IllegalArgumentException("unknown CW10 block class");
                }
            }
            short[] surface = null;
            if (record.surfaceFile != null) {
                Path surfacePath = config.root.resolve(record.surfaceFile).normalize();
                if (!surfacePath.startsWith(config.root) || !Files.isRegularFile(surfacePath)) {
                    throw new IllegalArgumentException("unsafe/missing surface path");
                }
                byte[] surfaceContent = Files.readAllBytes(surfacePath);
                if (surfaceContent.length != config.height * config.width * 2) {
                    throw new IllegalArgumentException("surface byte count mismatch");
                }
                if (!sha256(surfaceContent).equalsIgnoreCase(record.surfaceSha256)) {
                    throw new IllegalArgumentException("surface hash mismatch");
                }
                surface = new short[config.height * config.width];
                ByteBuffer.wrap(surfaceContent).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(surface);
            } else if ("post_noise_air".equals(config.mode)
                    || "finalized_region_air".equals(config.mode)) {
                throw new IllegalArgumentException("post-noise tile missing frozen surface");
            }
            TileData loaded = new TileData(content, surface);
            TileData prior = TILES.putIfAbsent(key, loaded);
            return prior != null ? prior : loaded;
        } catch (Throwable throwable) {
            System.out.println("[GeoWorld-Geology] permanent legacy fallback for tile " + tileX + "," + tileZ + ": " + throwable);
            PERMANENT_FALLBACK.add(key);
            return null;
        }
    }

    private static synchronized Config config() {
        if (LOAD_ATTEMPTED) return CONFIG;
        LOAD_ATTEMPTED = true;
        String configuredPath = System.getProperty("geoworld.geology.config");
        if (configuredPath == null || configuredPath.isBlank()) return null;
        try {
            Path path = Path.of(configuredPath).toAbsolutePath().normalize();
            JsonObject root;
            try (var reader = Files.newBufferedReader(path)) {
                root = new Gson().fromJson(reader, JsonObject.class);
            }
            String schema = string(root, "schema_version");
            if (!"geoworld.geology-cache.v1".equals(schema)
                    && !"geoworld.geology-cache.v2".equals(schema)
                    && !"geoworld.caveweaver-minecraft-cache.v1".equals(schema)) {
                throw new IllegalArgumentException("unsupported schema");
            }
            Config config = new Config();
            config.enabled = bool(root, "enabled", false);
            config.mode = string(root, "mode");
            if (!"legacy".equals(config.mode) && !"coexist".equals(config.mode)
                    && !"post_noise_air".equals(config.mode)
                    && !"finalized_region_air".equals(config.mode)
                    && !"cw10_absolute_classes".equals(config.mode)) {
                throw new IllegalArgumentException("unsupported mode");
            }
            if (("post_noise_air".equals(config.mode) || "finalized_region_air".equals(config.mode))
                    && !"geoworld.geology-cache.v2".equals(schema)) {
                throw new IllegalArgumentException("post-noise mode requires v2 schema");
            }
            if ("cw10_absolute_classes".equals(config.mode)
                    && !"geoworld.caveweaver-minecraft-cache.v1".equals(schema)) {
                throw new IllegalArgumentException("CW10 absolute classes require the CaveWeaver schema");
            }
            config.tileBlocks = integer(root, "tile_blocks");
            config.voxelBlocks = integer(root, "voxel_blocks");
            config.surfaceProtectionBlocks = integer(root, "surface_protection_blocks");
            config.quantizationPerDensity = number(root, "quantization_per_density");
            config.requiredFinalizedHaloChunks = integer(root, "required_finalized_halo_chunks", 0);
            var shape = root.getAsJsonArray("shape_yzx");
            config.depth = shape.get(0).getAsInt();
            config.height = shape.get(1).getAsInt();
            config.width = shape.get(2).getAsInt();
            if ("cw10_absolute_classes".equals(config.mode)) {
                var origin = root.getAsJsonArray("origin_world_xyz");
                if (origin == null || origin.size() != 3) {
                    throw new IllegalArgumentException("CW10 cache requires origin_world_xyz");
                }
                config.originWorldX = origin.get(0).getAsInt();
                config.originWorldY = origin.get(1).getAsInt();
                config.originWorldZ = origin.get(2).getAsInt();
                if (config.voxelBlocks != 1 || config.depth != 96
                        || config.height != 256 || config.width != 256
                        || config.tileBlocks != 256) {
                    throw new IllegalArgumentException("CW10 v1 absolute geometry is incompatible");
                }
                requireValue(root, "class_semantics", "0=unchanged_rock,1=air,2=water");
                requireSha256(root, "canonical_asset_sha256");
                requireSha256(root, "content_sha256");
                requireSha256(root, "topology_plan_hash");
                JsonObject route = root.getAsJsonObject("protected_route");
                if (route == null) throw new IllegalArgumentException("CW10 cache requires protected_route");
                config.protectedRouteFile = string(route, "file");
                config.protectedRouteSha256 = string(route, "sha256");
                if (!config.protectedRouteSha256.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid protected route hash");
                }
                config.protectedRouteVoxelCount = integer(route, "voxel_count");
                requireValue(route, "semantics", "1=hard_topology_route_priority,0=ordinary_cave_policy");
                requireValue(route, "dilation", "one_block_six_neighbor");
            }
            if (config.tileBlocks <= 0 || config.voxelBlocks <= 0 || config.quantizationPerDensity <= 0
                    || config.width * config.voxelBlocks != config.tileBlocks
                    || config.height * config.voxelBlocks != config.tileBlocks
                    || config.requiredFinalizedHaloChunks < 0) {
                throw new IllegalArgumentException("invalid geometry");
            }
            config.root = path.getParent();
            config.tiles = new ConcurrentHashMap<>();
            JsonObject tiles = root.getAsJsonObject("tiles");
            for (String key : tiles.keySet()) {
                JsonObject value = tiles.getAsJsonObject(key);
                config.tiles.put(key, new TileRecord(
                        string(value, "file"),
                        string(value, "sha256"),
                        optionalString(value, "surface_file"),
                        optionalString(value, "surface_sha256")));
            }
            if ("cw10_absolute_classes".equals(config.mode)
                    && (config.tiles.size() != 1 || !config.tiles.containsKey("0,0"))) {
                throw new IllegalArgumentException("CW10 v1 requires exactly tile 0,0");
            }
            CONFIG = config;
            System.out.println("[GeoWorld-Geology] mode=" + config.mode + " tiles=" + config.tiles.size()
                    + " shape=" + config.depth + "x" + config.height + "x" + config.width);
            return CONFIG;
        } catch (Throwable throwable) {
            System.out.println("[GeoWorld-Geology] config failed closed: " + throwable);
            return null;
        }
    }

    public static boolean cw10AbsoluteClassesEnabled() {
        Config config = config();
        return config != null && config.enabled && "cw10_absolute_classes".equals(config.mode);
    }

    public static int cw10OriginWorldY() {
        Config config = config();
        if (!cw10AbsoluteClassesEnabled()) throw new IllegalStateException("CW10 class cache is inactive");
        return config.originWorldY;
    }

    public static int cw10OriginWorldX() {
        Config config = config();
        if (!cw10AbsoluteClassesEnabled()) throw new IllegalStateException("CW10 class cache is inactive");
        return config.originWorldX;
    }

    public static int cw10OriginWorldZ() {
        Config config = config();
        if (!cw10AbsoluteClassesEnabled()) throw new IllegalStateException("CW10 class cache is inactive");
        return config.originWorldZ;
    }

    public static int cw10SizeY() {
        Config config = config();
        if (!cw10AbsoluteClassesEnabled()) throw new IllegalStateException("CW10 class cache is inactive");
        return config.depth;
    }

    public static long visitCw10ClassBlocks(Cw10ClassBlockConsumer consumer) {
        Config config = config();
        if (!cw10AbsoluteClassesEnabled() || !preflightCw10ClassTiles()) return 0;
        long candidates = 0;
        for (TileCoordinate coordinate : finalizedTilesForConfig(config)) {
            TileData data = tile(coordinate.x(), coordinate.z(), config);
            if (data == null) throw new IllegalStateException("CW10 class tile failed preflight");
            int tileStartX = config.originWorldX + coordinate.x() * config.tileBlocks;
            int tileStartZ = config.originWorldZ + coordinate.z() * config.tileBlocks;
            for (int localY = 0; localY < config.depth; localY++) {
                int worldY = config.originWorldY + localY;
                for (int localZ = 0; localZ < config.height; localZ++) {
                    int worldZ = tileStartZ + localZ;
                    for (int localX = 0; localX < config.width; localX++) {
                        int index = (localY * config.height + localZ) * config.width + localX;
                        int blockClass = data.sdf[index] & 0xff;
                        if (blockClass == 0) continue;
                        if (blockClass != 1 && blockClass != 2) {
                            throw new IllegalStateException("CW10 cache contains an unknown block class");
                        }
                        boolean protectedRoute = CW10_PROTECTED_ROUTE[index] != 0;
                        if (consumer.accept(
                                tileStartX + localX, worldY, worldZ, blockClass, protectedRoute)) {
                            CARVED.increment();
                            FINALIZED_BLOCKS_MODIFIED.increment();
                            if (blockClass == 1) CW10_AIR_BLOCKS_MODIFIED.increment();
                            else CW10_WATER_BLOCKS_MODIFIED.increment();
                        }
                        candidates++;
                    }
                }
            }
        }
        FINALIZED_APPLY_RUNS.increment();
        return candidates;
    }

    public static boolean preflightCw10ClassTiles() {
        Config config = config();
        if (!cw10AbsoluteClassesEnabled() || config.tiles.isEmpty()) return false;
        for (TileCoordinate coordinate : finalizedTilesForConfig(config)) {
            TileData data = tile(coordinate.x(), coordinate.z(), config);
            if (data == null || data.surface != null) return false;
        }
        return loadCw10ProtectedRoute(config);
    }

    private static synchronized boolean loadCw10ProtectedRoute(Config config) {
        if (CW10_PROTECTED_ROUTE != null) return true;
        try {
            Path path = config.root.resolve(config.protectedRouteFile).normalize();
            if (!path.startsWith(config.root) || !Files.isRegularFile(path)) return false;
            byte[] content = Files.readAllBytes(path);
            int expected = config.depth * config.height * config.width;
            if (content.length != expected || !sha256(content).equals(config.protectedRouteSha256)) {
                return false;
            }
            int count = 0;
            for (byte value : content) {
                if (value != 0 && value != 1) return false;
                if (value == 1) count++;
            }
            if (count != config.protectedRouteVoxelCount || count <= 0) return false;
            CW10_PROTECTED_ROUTE = content;
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static List<TileCoordinate> finalizedTilesForConfig(Config config) {
        return config.tiles.keySet().stream()
                .map(key -> {
                    String[] parts = key.split(",", -1);
                    return new TileCoordinate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                })
                .sorted((left, right) -> {
                    int byZ = Integer.compare(left.z(), right.z());
                    return byZ != 0 ? byZ : Integer.compare(left.x(), right.x());
                })
                .toList();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("missing " + key);
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(JsonObject object, String key) {
        return object.get(key).getAsInt();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null ? fallback : value.getAsInt();
    }

    private static double number(JsonObject object, String key) {
        return object.get(key).getAsDouble();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value == null ? fallback : value.getAsBoolean();
    }

    private static void requireValue(JsonObject object, String key, String expected) {
        if (!expected.equals(string(object, key))) throw new IllegalArgumentException("invalid " + key);
    }

    private static void requireSha256(JsonObject object, String key) {
        String value = string(object, key);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid " + key);
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static Path metricsPath() {
        String value = System.getProperty("geoworld.geology.metrics");
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static void writeMetrics() {
        try {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("schema_version", "geoworld.geology-bridge-metrics.v1");
            metrics.put("calls", CALLS.sum());
            metrics.put("surface_protected", PROTECTED.sum());
            metrics.put("tile_fallbacks", TILE_FALLBACKS.sum());
            metrics.put("out_of_bounds", OUT_OF_BOUNDS.sum());
            metrics.put("in_bounds", IN_BOUNDS.sum());
            metrics.put("negative_sdf_hits", NEGATIVE_SDF_HITS.sum());
            metrics.put("carved_density_samples", CARVED.sum());
            metrics.put("finalized_apply_runs", FINALIZED_APPLY_RUNS.sum());
            metrics.put("finalized_blocks_modified", FINALIZED_BLOCKS_MODIFIED.sum());
            metrics.put("cw10_air_blocks_modified", CW10_AIR_BLOCKS_MODIFIED.sum());
            metrics.put("cw10_water_blocks_modified", CW10_WATER_BLOCKS_MODIFIED.sum());
            metrics.put("cached_tiles", TILES.size());
            metrics.put("permanent_fallback_tiles", PERMANENT_FALLBACK.size());
            Files.createDirectories(METRICS_PATH.getParent());
            Files.writeString(METRICS_PATH, new Gson().toJson(metrics) + System.lineSeparator());
        } catch (Throwable throwable) {
            System.out.println("[GeoWorld-Geology] failed to write metrics: " + throwable);
        }
    }

    private record TileRecord(String file, String sha256, String surfaceFile, String surfaceSha256) {}

    private record TileData(byte[] sdf, short[] surface) {}

    public record TileCoordinate(int x, int z) {}

    @FunctionalInterface
    public interface CarveBlockConsumer {
        boolean accept(int worldX, int worldY, int worldZ);
    }

    @FunctionalInterface
    public interface Cw10ClassBlockConsumer {
        boolean accept(int worldX, int worldY, int worldZ, int blockClass, boolean protectedRoute);
    }

    private static final class Config {
        boolean enabled;
        String mode;
        int tileBlocks;
        int voxelBlocks;
        int surfaceProtectionBlocks;
        int depth;
        int height;
        int width;
        double quantizationPerDensity;
        int requiredFinalizedHaloChunks;
        int originWorldX;
        int originWorldY;
        int originWorldZ;
        String protectedRouteFile;
        String protectedRouteSha256;
        int protectedRouteVoxelCount;
        Path root;
        Map<String, TileRecord> tiles;
    }
}
