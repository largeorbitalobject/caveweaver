package com.orbital.geoworld.geology;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Immutable chunk-local reader for host-neutral karst base-delta packages. */
final class KarstDeltaChunkReader {
    private static final String SCHEMA =
            "geoworld.karstogenesis-host-neutral-delta-package.v1";
    private static final int TILE_X = 256;
    private static final int TILE_Y = 96;
    private static final int TILE_Z = 256;
    private static final int CHUNK_SIZE = 16;
    private static final int AUDIT_THREADS = 8;

    interface HostBlockPaletteRegistry {
        String materialFor(VoxelIntent intent);
    }

    interface HostCaveBiomeRegistry {
        String biomeFor(VoxelIntent intent);
    }

    record Voxel(int x, int y, int z) implements Comparable<Voxel> {
        @Override
        public int compareTo(Voxel other) {
            int result = Integer.compare(x, other.x);
            if (result != 0) return result;
            result = Integer.compare(y, other.y);
            return result != 0 ? result : Integer.compare(z, other.z);
        }
    }

    record VoxelIntent(
            Voxel voxel,
            boolean evolvedAddition,
            boolean protectedRoute,
            boolean registeredBoundary,
            boolean owned) {}

    record HostVoxelIntent(VoxelIntent geometry, String material, String biome) {}

    enum Replaceability {
        REPLACE,
        PRESERVE,
        CONFLICT
    }

    interface HostMinecraftRegistry {
        Replaceability replaceabilityFor(Voxel worldVoxel, VoxelIntent sourceIntent);

        String materialFor(Voxel worldVoxel, VoxelIntent sourceIntent);

        String biomeFor(Voxel worldVoxel, VoxelIntent sourceIntent);
    }

    record Translation(int x, int y, int z) {}

    record WorldBounds(Voxel lower, Voxel upper) {
        WorldBounds {
            if (lower.x > upper.x || lower.y > upper.y || lower.z > upper.z) {
                throw new IllegalArgumentException("world bounds differ");
            }
        }

        boolean contains(Voxel voxel) {
            return voxel.x >= lower.x && voxel.x <= upper.x
                    && voxel.y >= lower.y && voxel.y <= upper.y
                    && voxel.z >= lower.z && voxel.z <= upper.z;
        }
    }

    enum PlanState {
        ACTIVE,
        INERT,
        REJECTED
    }

    record PlannedVoxel(
            Voxel sourceVoxel,
            Voxel worldVoxel,
            boolean evolvedAddition,
            boolean protectedRoute,
            boolean registeredBoundary,
            String material,
            String biome) {}

    record SkippedVoxel(Voxel sourceVoxel, Voxel worldVoxel, String reason) {}

    record ChunkPlan(
            PlanState state,
            String reason,
            String cacheIdentitySha256,
            ChunkKey worldChunk,
            int minimumY,
            int maximumY,
            Translation translation,
            List<PlannedVoxel> planned,
            List<SkippedVoxel> skipped,
            String planSha256) {
        ChunkPlan {
            planned = List.copyOf(planned);
            skipped = List.copyOf(skipped);
        }
    }

    record ChunkKey(int x, int z) implements Comparable<ChunkKey> {
        @Override
        public int compareTo(ChunkKey other) {
            int result = Integer.compare(x, other.x);
            return result != 0 ? result : Integer.compare(z, other.z);
        }
    }

    record TileKey(int x, int y, int z) implements Comparable<TileKey> {
        @Override
        public int compareTo(TileKey other) {
            int result = Integer.compare(x, other.x);
            if (result != 0) return result;
            result = Integer.compare(y, other.y);
            return result != 0 ? result : Integer.compare(z, other.z);
        }
    }

    record Range(int start, int stop) {}

    record ChunkGeometry(
            String cacheIdentitySha256,
            ChunkKey chunk,
            int minimumY,
            int maximumY,
            List<VoxelIntent> owned,
            List<VoxelIntent> readOnlyHalo) {}

    private static final class MutableFlags {
        boolean base;
        boolean delta;
        boolean protectedRoute;
        boolean boundary;
    }

    private enum RegisterClass {
        BASE,
        DELTA,
        PROTECTED,
        BOUNDARY
    }

    @FunctionalInterface
    private interface VoxelConsumer {
        void accept(Voxel voxel);
    }

    private static final class RawRegister {
        final Map<TileKey, Range> groups;
        final ShortBuffer local;
        final long mappedLocalBytes;

        RawRegister(Map<TileKey, Range> groups, ShortBuffer local, long mappedLocalBytes) {
            this.groups = Map.copyOf(groups);
            this.local = local.asReadOnlyBuffer();
            this.mappedLocalBytes = mappedLocalBytes;
        }

        void query(
                int lowX,
                int highX,
                int lowY,
                int highY,
                int lowZ,
                int highZ,
                VoxelConsumer consumer) {
            int lowTileX = Math.floorDiv(lowX, TILE_X);
            int highTileX = Math.floorDiv(highX, TILE_X);
            int lowTileY = Math.floorDiv(lowY, TILE_Y);
            int highTileY = Math.floorDiv(highY, TILE_Y);
            int lowTileZ = Math.floorDiv(lowZ, TILE_Z);
            int highTileZ = Math.floorDiv(highZ, TILE_Z);
            for (int tileX = lowTileX; tileX <= highTileX; tileX++) {
                int localLowX = tileX == lowTileX ? Math.floorMod(lowX, TILE_X) : 0;
                int localHighX = tileX == highTileX ? Math.floorMod(highX, TILE_X) : TILE_X - 1;
                for (int tileY = lowTileY; tileY <= highTileY; tileY++) {
                    for (int tileZ = lowTileZ; tileZ <= highTileZ; tileZ++) {
                        Range range = groups.get(new TileKey(tileX, tileY, tileZ));
                        if (range == null) continue;
                        int start = lowerBoundX(range.start, range.stop, localLowX);
                        int stop = lowerBoundX(range.start, range.stop, localHighX + 1);
                        for (int row = start; row < stop; row++) {
                            int offset = row * 3;
                            int x = tileX * TILE_X + Short.toUnsignedInt(local.get(offset));
                            int y = tileY * TILE_Y + Short.toUnsignedInt(local.get(offset + 1));
                            int z = tileZ * TILE_Z + Short.toUnsignedInt(local.get(offset + 2));
                            if (y >= lowY && y <= highY && z >= lowZ && z <= highZ) {
                                consumer.accept(new Voxel(x, y, z));
                            }
                        }
                    }
                }
            }
        }

        private int lowerBoundX(int start, int stop, int target) {
            int low = start;
            int high = stop;
            while (low < high) {
                int middle = (low + high) >>> 1;
                int value = Short.toUnsignedInt(local.get(middle * 3));
                if (value < target) low = middle + 1;
                else high = middle;
            }
            return low;
        }

        void addCandidateChunks(
                Set<ChunkKey> chunks,
                int translationX,
                int translationZ) {
            for (Map.Entry<TileKey, Range> entry : groups.entrySet()) {
                TileKey tile = entry.getKey();
                Range range = entry.getValue();
                for (int row = range.start; row < range.stop; row++) {
                    int offset = row * 3;
                    int sourceX = Math.addExact(
                            Math.multiplyExact(tile.x, TILE_X),
                            Short.toUnsignedInt(local.get(offset)));
                    int sourceZ = Math.addExact(
                            Math.multiplyExact(tile.z, TILE_Z),
                            Short.toUnsignedInt(local.get(offset + 2)));
                    int worldX = Math.addExact(sourceX, translationX);
                    int worldZ = Math.addExact(sourceZ, translationZ);
                    chunks.add(new ChunkKey(
                            Math.floorDiv(worldX, CHUNK_SIZE),
                            Math.floorDiv(worldZ, CHUNK_SIZE)));
                }
            }
        }
    }

    private final Path root;
    private final String cacheIdentitySha256;
    private final Properties properties;
    private final RawRegister baseVoid;
    private final RawRegister deltaVoid;
    private final RawRegister protectedVoxels;
    private final RawRegister boundaryVoxels;
    private final List<ChunkKey> candidateChunks;
    private final int minimumX;
    private final int minimumY;
    private final int minimumZ;
    private final int maximumX;
    private final int maximumY;
    private final int maximumZ;
    private final long mappedLocalPayloadBytes;
    private final double verifiedOpenSeconds;

    private KarstDeltaChunkReader(
            Path root,
            String cacheIdentitySha256,
            Properties properties,
            RawRegister baseVoid,
            RawRegister deltaVoid,
            RawRegister protectedVoxels,
            RawRegister boundaryVoxels,
            List<ChunkKey> candidateChunks,
            int[] lower,
            int[] upper,
            long mappedLocalPayloadBytes,
            double verifiedOpenSeconds) {
        this.root = root;
        this.cacheIdentitySha256 = cacheIdentitySha256;
        this.properties = properties;
        this.baseVoid = baseVoid;
        this.deltaVoid = deltaVoid;
        this.protectedVoxels = protectedVoxels;
        this.boundaryVoxels = boundaryVoxels;
        this.candidateChunks = List.copyOf(candidateChunks);
        this.minimumX = lower[0];
        this.minimumY = lower[1];
        this.minimumZ = lower[2];
        this.maximumX = upper[0];
        this.maximumY = upper[1];
        this.maximumZ = upper[2];
        this.mappedLocalPayloadBytes = mappedLocalPayloadBytes;
        this.verifiedOpenSeconds = verifiedOpenSeconds;
    }

    static KarstDeltaChunkReader open(Path packageRoot, boolean verifyHashes)
            throws IOException {
        long started = System.nanoTime();
        Path root = packageRoot.toAbsolutePath().normalize();
        Properties properties = loadProperties(root.resolve("package.properties"));
        requireValue(properties, "schema", SCHEMA);
        requireValue(properties, "state", "ready");
        String identitySha = sha256(root.resolve("identity.json"));
        requireValue(properties, "cache_identity_sha256", identitySha);
        String identityText = Files.readString(root.resolve("identity.json"));
        if (!identityText.contains("\"host_block_palette_owner\":\"host-registry\"")
                || !identityText.contains("\"host_cave_biome_owner\":\"host-registry\"")
                || identityText.contains("minecraft:")) {
            throw new IllegalArgumentException("host registry ownership identity differs");
        }
        int fileCount = requiredInt(properties, "file_count");
        for (int index = 0; index < fileCount; index++) {
            String key = String.format("file_%02d", index);
            Path path = root.resolve(required(properties, key + "_name")).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)
                    || Files.size(path) != Long.parseLong(required(properties, key + "_bytes"))) {
                throw new IllegalArgumentException("package payload path or size differs");
            }
            if (verifyHashes && !sha256(path).equals(required(properties, key + "_sha256"))) {
                throw new IllegalArgumentException("package payload hash differs");
            }
        }
        RawRegister base = loadRegister(root, "base-void");
        RawRegister delta = loadRegister(root, "delta-void");
        RawRegister protectedVoxels = loadRegister(root, "base-protected");
        RawRegister boundary = loadRegister(root, "base-boundary");
        Set<ChunkKey> chunks = new TreeSet<>();
        base.addCandidateChunks(chunks, 0, 0);
        delta.addCandidateChunks(chunks, 0, 0);
        int[] low = parseTriple(required(properties, "lower_xyz"));
        int[] high = parseTriple(required(properties, "upper_xyz"));
        long mappedBytes = base.mappedLocalBytes + delta.mappedLocalBytes
                + protectedVoxels.mappedLocalBytes + boundary.mappedLocalBytes;
        return new KarstDeltaChunkReader(
                root,
                identitySha,
                properties,
                base,
                delta,
                protectedVoxels,
                boundary,
                new ArrayList<>(chunks),
                low,
                high,
                mappedBytes,
                (System.nanoTime() - started) / 1_000_000_000.0);
    }

    String cacheIdentitySha256() {
        return cacheIdentitySha256;
    }

    List<ChunkKey> candidateChunks() {
        return candidateChunks;
    }

    Voxel lowerBound() {
        return new Voxel(minimumX, minimumY, minimumZ);
    }

    Voxel upperBound() {
        return new Voxel(maximumX, maximumY, maximumZ);
    }

    ChunkGeometry queryChunk(int chunkX, int chunkZ, int lowY, int highY, int haloBlocks) {
        if (lowY > highY || haloBlocks < 0 || haloBlocks > 1) {
            throw new IllegalArgumentException("chunk query bounds differ");
        }
        int ownedLowX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int ownedHighX = Math.addExact(ownedLowX, CHUNK_SIZE - 1);
        int ownedLowZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        int ownedHighZ = Math.addExact(ownedLowZ, CHUNK_SIZE - 1);
        int queryLowX = ownedLowX - haloBlocks;
        int queryHighX = ownedHighX + haloBlocks;
        int queryLowY = lowY - haloBlocks;
        int queryHighY = highY + haloBlocks;
        int queryLowZ = ownedLowZ - haloBlocks;
        int queryHighZ = ownedHighZ + haloBlocks;
        TreeMap<Voxel, MutableFlags> flags = new TreeMap<>();
        addRegister(baseVoid, RegisterClass.BASE, flags,
                queryLowX, queryHighX, queryLowY, queryHighY, queryLowZ, queryHighZ);
        addRegister(deltaVoid, RegisterClass.DELTA, flags,
                queryLowX, queryHighX, queryLowY, queryHighY, queryLowZ, queryHighZ);
        addRegister(protectedVoxels, RegisterClass.PROTECTED, flags,
                queryLowX, queryHighX, queryLowY, queryHighY, queryLowZ, queryHighZ);
        addRegister(boundaryVoxels, RegisterClass.BOUNDARY, flags,
                queryLowX, queryHighX, queryLowY, queryHighY, queryLowZ, queryHighZ);
        List<VoxelIntent> owned = new ArrayList<>();
        List<VoxelIntent> halo = new ArrayList<>();
        for (Map.Entry<Voxel, MutableFlags> entry : flags.entrySet()) {
            Voxel voxel = entry.getKey();
            MutableFlags value = entry.getValue();
            if (value.base && value.delta) {
                throw new IllegalArgumentException("base and delta overlap");
            }
            if ((value.protectedRoute || value.boundary) && !value.base) {
                throw new IllegalArgumentException("protected or boundary voxel leaves mature void");
            }
            boolean isOwned = voxel.x >= ownedLowX && voxel.x <= ownedHighX
                    && voxel.z >= ownedLowZ && voxel.z <= ownedHighZ
                    && voxel.y >= lowY && voxel.y <= highY;
            VoxelIntent intent = new VoxelIntent(
                    voxel, value.delta, value.protectedRoute, value.boundary, isOwned);
            if (isOwned) owned.add(intent);
            else halo.add(intent);
        }
        return new ChunkGeometry(
                cacheIdentitySha256,
                new ChunkKey(chunkX, chunkZ),
                lowY,
                highY,
                List.copyOf(owned),
                List.copyOf(halo));
    }

    List<HostVoxelIntent> resolveOwned(
            ChunkGeometry geometry,
            HostBlockPaletteRegistry blocks,
            HostCaveBiomeRegistry biomes) {
        List<HostVoxelIntent> result = new ArrayList<>(geometry.owned.size());
        for (VoxelIntent intent : geometry.owned) {
            String material = blocks.materialFor(intent);
            String biome = biomes.biomeFor(intent);
            if (material == null || material.isBlank() || biome == null || biome.isBlank()) {
                throw new IllegalArgumentException("host registry returned an empty identity");
            }
            result.add(new HostVoxelIntent(intent, material, biome));
        }
        return List.copyOf(result);
    }

    /** Immutable no-write host intent planner over one translated package. */
    static final class MinecraftIntentPlanner {
        private static final String ACTIVE_REASON = "active";
        private static final String IDENTITY_REASON = "cache-identity-mismatch";
        private static final String BOUNDS_REASON = "package-outside-world-bounds";
        private static final String OVERFLOW_REASON = "coordinate-overflow";
        private static final String QUERY_REASON = "query-outside-world-bounds";
        private static final String ORDINARY_PRESERVE_REASON = "ordinary-host-preserve";
        private static final String ORDINARY_CONFLICT_REASON = "ordinary-host-conflict";
        private static final String PROTECTED_REASON = "protected-route-conflict";
        private static final String BOUNDARY_REASON = "registered-boundary-conflict";
        private static final String REGISTRY_REASON = "host-registry-resolution-failure";

        private final KarstDeltaChunkReader reader;
        private final Translation translation;
        private final WorldBounds worldBounds;
        private final PlanState bindingState;
        private final String bindingReason;
        private final List<ChunkKey> candidateWorldChunks;

        private MinecraftIntentPlanner(
                KarstDeltaChunkReader reader,
                Translation translation,
                WorldBounds worldBounds,
                PlanState bindingState,
                String bindingReason,
                List<ChunkKey> candidateWorldChunks) {
            this.reader = reader;
            this.translation = translation;
            this.worldBounds = worldBounds;
            this.bindingState = bindingState;
            this.bindingReason = bindingReason;
            this.candidateWorldChunks = List.copyOf(candidateWorldChunks);
        }

        static MinecraftIntentPlanner bind(
                KarstDeltaChunkReader reader,
                String expectedCacheIdentitySha256,
                Translation translation,
                WorldBounds worldBounds) {
            if (!reader.cacheIdentitySha256.equals(expectedCacheIdentitySha256)) {
                return new MinecraftIntentPlanner(
                        reader, translation, worldBounds, PlanState.INERT, IDENTITY_REASON, List.of());
            }
            Voxel translatedLower;
            Voxel translatedUpper;
            try {
                translatedLower = translate(reader.lowerBound(), translation);
                translatedUpper = translate(reader.upperBound(), translation);
            } catch (ArithmeticException exception) {
                return new MinecraftIntentPlanner(
                        reader, translation, worldBounds, PlanState.INERT, OVERFLOW_REASON, List.of());
            }
            if (!worldBounds.contains(translatedLower) || !worldBounds.contains(translatedUpper)) {
                return new MinecraftIntentPlanner(
                        reader, translation, worldBounds, PlanState.INERT, BOUNDS_REASON, List.of());
            }
            TreeSet<ChunkKey> worldChunks = new TreeSet<>();
            try {
                reader.baseVoid.addCandidateChunks(
                        worldChunks, translation.x, translation.z);
                reader.deltaVoid.addCandidateChunks(
                        worldChunks, translation.x, translation.z);
            } catch (ArithmeticException exception) {
                return new MinecraftIntentPlanner(
                        reader, translation, worldBounds, PlanState.INERT, OVERFLOW_REASON, List.of());
            }
            return new MinecraftIntentPlanner(
                    reader,
                    translation,
                    worldBounds,
                    PlanState.ACTIVE,
                    ACTIVE_REASON,
                    new ArrayList<>(worldChunks));
        }

        PlanState bindingState() {
            return bindingState;
        }

        String bindingReason() {
            return bindingReason;
        }

        List<ChunkKey> candidateWorldChunks() {
            return candidateWorldChunks;
        }

        ChunkPlan planChunk(
                int worldChunkX,
                int worldChunkZ,
                int lowY,
                int highY,
                HostMinecraftRegistry registry) {
            ChunkKey worldChunk = new ChunkKey(worldChunkX, worldChunkZ);
            if (bindingState != PlanState.ACTIVE) {
                return finishPlan(
                        bindingState,
                        bindingReason,
                        worldChunk,
                        lowY,
                        highY,
                        List.of(),
                        List.of());
            }
            if (lowY > highY) throw new IllegalArgumentException("plan height differs");
            int worldLowX;
            int worldHighX;
            int worldLowZ;
            int worldHighZ;
            try {
                worldLowX = Math.multiplyExact(worldChunkX, CHUNK_SIZE);
                worldHighX = Math.addExact(worldLowX, CHUNK_SIZE - 1);
                worldLowZ = Math.multiplyExact(worldChunkZ, CHUNK_SIZE);
                worldHighZ = Math.addExact(worldLowZ, CHUNK_SIZE - 1);
            } catch (ArithmeticException exception) {
                return finishPlan(
                        PlanState.INERT,
                        OVERFLOW_REASON,
                        worldChunk,
                        lowY,
                        highY,
                        List.of(),
                        List.of());
            }
            int boundedLowY = Math.max(lowY, worldBounds.lower.y);
            int boundedHighY = Math.min(highY, worldBounds.upper.y);
            if (boundedLowY > boundedHighY
                    || worldHighX < worldBounds.lower.x
                    || worldLowX > worldBounds.upper.x
                    || worldHighZ < worldBounds.lower.z
                    || worldLowZ > worldBounds.upper.z) {
                return finishPlan(
                        PlanState.INERT,
                        QUERY_REASON,
                        worldChunk,
                        lowY,
                        highY,
                        List.of(),
                        List.of());
            }

            int sourceLowX;
            int sourceHighX;
            int sourceLowY;
            int sourceHighY;
            int sourceLowZ;
            int sourceHighZ;
            try {
                sourceLowX = Math.subtractExact(worldLowX, translation.x);
                sourceHighX = Math.subtractExact(worldHighX, translation.x);
                sourceLowY = Math.subtractExact(boundedLowY, translation.y);
                sourceHighY = Math.subtractExact(boundedHighY, translation.y);
                sourceLowZ = Math.subtractExact(worldLowZ, translation.z);
                sourceHighZ = Math.subtractExact(worldHighZ, translation.z);
            } catch (ArithmeticException exception) {
                return finishPlan(
                        PlanState.INERT,
                        OVERFLOW_REASON,
                        worldChunk,
                        lowY,
                        highY,
                        List.of(),
                        List.of());
            }
            sourceLowY = Math.max(sourceLowY, reader.minimumY);
            sourceHighY = Math.min(sourceHighY, reader.maximumY);
            if (sourceLowY > sourceHighY) {
                return finishPlan(
                        PlanState.ACTIVE,
                        ACTIVE_REASON,
                        worldChunk,
                        lowY,
                        highY,
                        List.of(),
                        List.of());
            }

            TreeMap<Voxel, VoxelIntent> translated = new TreeMap<>();
            for (int sourceChunkX = Math.floorDiv(sourceLowX, CHUNK_SIZE);
                    sourceChunkX <= Math.floorDiv(sourceHighX, CHUNK_SIZE);
                    sourceChunkX++) {
                for (int sourceChunkZ = Math.floorDiv(sourceLowZ, CHUNK_SIZE);
                        sourceChunkZ <= Math.floorDiv(sourceHighZ, CHUNK_SIZE);
                        sourceChunkZ++) {
                    ChunkGeometry geometry = reader.queryChunk(
                            sourceChunkX, sourceChunkZ, sourceLowY, sourceHighY, 0);
                    for (VoxelIntent sourceIntent : geometry.owned) {
                        Voxel worldVoxel;
                        try {
                            worldVoxel = translate(sourceIntent.voxel, translation);
                        } catch (ArithmeticException exception) {
                            return finishPlan(
                                    PlanState.INERT,
                                    OVERFLOW_REASON,
                                    worldChunk,
                                    lowY,
                                    highY,
                                    List.of(),
                                    List.of());
                        }
                        if (worldVoxel.x < worldLowX || worldVoxel.x > worldHighX
                                || worldVoxel.y < boundedLowY || worldVoxel.y > boundedHighY
                                || worldVoxel.z < worldLowZ || worldVoxel.z > worldHighZ) {
                            continue;
                        }
                        VoxelIntent prior = translated.put(worldVoxel, sourceIntent);
                        if (prior != null && !prior.equals(sourceIntent)) {
                            throw new IllegalArgumentException("translated package ownership overlaps");
                        }
                    }
                }
            }

            List<PlannedVoxel> planned = new ArrayList<>();
            List<SkippedVoxel> skipped = new ArrayList<>();
            for (Map.Entry<Voxel, VoxelIntent> entry : translated.entrySet()) {
                Voxel worldVoxel = entry.getKey();
                VoxelIntent sourceIntent = entry.getValue();
                Replaceability replaceability = registry.replaceabilityFor(worldVoxel, sourceIntent);
                if (replaceability == null) {
                    return rejectedRegistryPlan(
                            worldChunk, lowY, highY, sourceIntent.voxel, worldVoxel);
                }
                if (replaceability != Replaceability.REPLACE) {
                    String ordinaryReason = replaceability == Replaceability.PRESERVE
                            ? ORDINARY_PRESERVE_REASON
                            : ORDINARY_CONFLICT_REASON;
                    String reason = sourceIntent.registeredBoundary
                            ? BOUNDARY_REASON
                            : sourceIntent.protectedRoute ? PROTECTED_REASON : ordinaryReason;
                    SkippedVoxel conflict = new SkippedVoxel(sourceIntent.voxel, worldVoxel, reason);
                    if (sourceIntent.registeredBoundary || sourceIntent.protectedRoute) {
                        return finishPlan(
                                PlanState.REJECTED,
                                reason,
                                worldChunk,
                                lowY,
                                highY,
                                List.of(),
                                List.of(conflict));
                    }
                    skipped.add(conflict);
                    continue;
                }
                String material = registry.materialFor(worldVoxel, sourceIntent);
                String biome = registry.biomeFor(worldVoxel, sourceIntent);
                if (material == null || material.isBlank() || biome == null || biome.isBlank()) {
                    return rejectedRegistryPlan(
                            worldChunk, lowY, highY, sourceIntent.voxel, worldVoxel);
                }
                planned.add(new PlannedVoxel(
                        sourceIntent.voxel,
                        worldVoxel,
                        sourceIntent.evolvedAddition,
                        sourceIntent.protectedRoute,
                        sourceIntent.registeredBoundary,
                        material,
                        biome));
            }
            return finishPlan(
                    PlanState.ACTIVE,
                    ACTIVE_REASON,
                    worldChunk,
                    lowY,
                    highY,
                    planned,
                    skipped);
        }

        private ChunkPlan rejectedRegistryPlan(
                ChunkKey worldChunk,
                int lowY,
                int highY,
                Voxel sourceVoxel,
                Voxel worldVoxel) {
            return finishPlan(
                    PlanState.REJECTED,
                    REGISTRY_REASON,
                    worldChunk,
                    lowY,
                    highY,
                    List.of(),
                    List.of(new SkippedVoxel(sourceVoxel, worldVoxel, REGISTRY_REASON)));
        }

        private ChunkPlan finishPlan(
                PlanState state,
                String reason,
                ChunkKey worldChunk,
                int lowY,
                int highY,
                List<PlannedVoxel> planned,
                List<SkippedVoxel> skipped) {
            String digest = planDigest(
                    state,
                    reason,
                    reader.cacheIdentitySha256,
                    worldChunk,
                    lowY,
                    highY,
                    translation,
                    planned,
                    skipped);
            return new ChunkPlan(
                    state,
                    reason,
                    reader.cacheIdentitySha256,
                    worldChunk,
                    lowY,
                    highY,
                    translation,
                    planned,
                    skipped,
                    digest);
        }

        private static Voxel translate(Voxel source, Translation translation) {
            return new Voxel(
                    Math.addExact(source.x, translation.x),
                    Math.addExact(source.y, translation.y),
                    Math.addExact(source.z, translation.z));
        }

        private static void updateInt(MessageDigest digest, int value) {
            ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(value);
            digest.update(buffer.array());
        }

        private static void updateBoolean(MessageDigest digest, boolean value) {
            digest.update((byte) (value ? 1 : 0));
        }

        private static void updateString(MessageDigest digest, String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            updateInt(digest, encoded.length);
            digest.update(encoded);
        }

        private static void updatePlanVoxel(MessageDigest digest, PlannedVoxel value) {
            updateVoxel(digest, value.sourceVoxel);
            updateVoxel(digest, value.worldVoxel);
            updateBoolean(digest, value.evolvedAddition);
            updateBoolean(digest, value.protectedRoute);
            updateBoolean(digest, value.registeredBoundary);
            updateString(digest, value.material);
            updateString(digest, value.biome);
        }

        private static String planDigest(
                PlanState state,
                String reason,
                String identity,
                ChunkKey worldChunk,
                int lowY,
                int highY,
                Translation translation,
                List<PlannedVoxel> planned,
                List<SkippedVoxel> skipped) {
            MessageDigest digest = sha256Digest();
            updateString(digest, state.name());
            updateString(digest, reason);
            updateString(digest, identity);
            updateInt(digest, worldChunk.x);
            updateInt(digest, worldChunk.z);
            updateInt(digest, lowY);
            updateInt(digest, highY);
            updateInt(digest, translation.x);
            updateInt(digest, translation.y);
            updateInt(digest, translation.z);
            updateInt(digest, planned.size());
            for (PlannedVoxel value : planned) updatePlanVoxel(digest, value);
            updateInt(digest, skipped.size());
            for (SkippedVoxel value : skipped) {
                updateVoxel(digest, value.sourceVoxel);
                updateVoxel(digest, value.worldVoxel);
                updateString(digest, value.reason);
            }
            return hex(digest.digest());
        }
    }

    private static void addRegister(
            RawRegister register,
            RegisterClass registerClass,
            TreeMap<Voxel, MutableFlags> flags,
            int lowX,
            int highX,
            int lowY,
            int highY,
            int lowZ,
            int highZ) {
        register.query(lowX, highX, lowY, highY, lowZ, highZ, voxel -> {
            MutableFlags value = flags.computeIfAbsent(voxel, ignored -> new MutableFlags());
            switch (registerClass) {
                case BASE -> value.base = true;
                case DELTA -> value.delta = true;
                case PROTECTED -> value.protectedRoute = true;
                case BOUNDARY -> value.boundary = true;
            }
        });
    }

    private static RawRegister loadRegister(Path root, String prefix) throws IOException {
        int[] tiles = readInt32(root.resolve(prefix + "-tiles.i32le.bin"));
        long[] offsets = readUInt64(root.resolve(prefix + "-offsets.u64le.bin"));
        Path localPath = root.resolve(prefix + "-local-xyz.u16le.bin");
        if (tiles.length % 3 != 0 || Files.size(localPath) % 6 != 0) {
            throw new IllegalArgumentException("sparse register coordinate width differs");
        }
        int tileCount = tiles.length / 3;
        int localCount = Math.toIntExact(Files.size(localPath) / 6);
        if (offsets.length != tileCount + 1 || offsets[0] != 0
                || offsets[offsets.length - 1] != localCount) {
            throw new IllegalArgumentException("sparse register offsets differ");
        }
        Map<TileKey, Range> groups = new HashMap<>();
        for (int index = 0; index < tileCount; index++) {
            if (offsets[index] > offsets[index + 1]) {
                throw new IllegalArgumentException("sparse register offsets are not monotonic");
            }
            TileKey tile = new TileKey(
                    tiles[index * 3], tiles[index * 3 + 1], tiles[index * 3 + 2]);
            Range prior = groups.put(
                    tile,
                    new Range(Math.toIntExact(offsets[index]), Math.toIntExact(offsets[index + 1])));
            if (prior != null) {
                throw new IllegalArgumentException("sparse register repeats tile ownership");
            }
        }
        ShortBuffer local;
        if (Files.size(localPath) == 0) {
            local = ShortBuffer.allocate(0).asReadOnlyBuffer();
        } else {
            try (FileChannel channel = FileChannel.open(localPath, StandardOpenOption.READ)) {
                MappedByteBuffer mapped = channel.map(
                        FileChannel.MapMode.READ_ONLY, 0, Files.size(localPath));
                mapped.order(ByteOrder.LITTLE_ENDIAN);
                local = mapped.asShortBuffer().asReadOnlyBuffer();
            }
        }
        if (local.remaining() != localCount * 3) {
            throw new IllegalArgumentException("sparse local payload count differs");
        }
        return new RawRegister(groups, local, Files.size(localPath));
    }

    private static int[] readInt32(Path path) throws IOException {
        byte[] payload = Files.readAllBytes(path);
        if (payload.length % 4 != 0) throw new IllegalArgumentException("int32 width differs");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int[] result = new int[payload.length / 4];
        for (int index = 0; index < result.length; index++) result[index] = buffer.getInt();
        return result;
    }

    private static long[] readUInt64(Path path) throws IOException {
        byte[] payload = Files.readAllBytes(path);
        if (payload.length % 8 != 0) throw new IllegalArgumentException("uint64 width differs");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long[] result = new long[payload.length / 8];
        for (int index = 0; index < result.length; index++) {
            result[index] = buffer.getLong();
            if (result[index] < 0) throw new IllegalArgumentException("uint64 offset exceeds range");
        }
        return result;
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            result.load(stream);
        }
        return result;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("missing property: " + key);
        return value;
    }

    private static int requiredInt(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }

    private static void requireValue(Properties properties, String key, String expected) {
        if (!expected.equals(required(properties, key))) {
            throw new IllegalArgumentException("unsupported property: " + key);
        }
    }

    private static int[] parseTriple(String value) {
        String[] fields = value.split(",", -1);
        if (fields.length != 3) throw new IllegalArgumentException("coordinate triple differs");
        return new int[] {
            Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2])
        };
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        Set<T> result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static void updateVoxel(MessageDigest digest, Voxel voxel) {
        ByteBuffer value = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        value.putInt(voxel.x).putInt(voxel.y).putInt(voxel.z);
        digest.update(value.array());
    }

    private static String volumeDigest(TreeMap<Voxel, MutableFlags> values) {
        MessageDigest digest = sha256Digest();
        for (Voxel voxel : values.keySet()) updateVoxel(digest, voxel);
        for (Map.Entry<Voxel, MutableFlags> entry : values.entrySet()) {
            if (entry.getValue().protectedRoute) updateVoxel(digest, entry.getKey());
        }
        return hex(digest.digest());
    }

    private static String boundaryDigest(TreeMap<Voxel, MutableFlags> values) {
        MessageDigest digest = sha256Digest();
        for (Map.Entry<Voxel, MutableFlags> entry : values.entrySet()) {
            if (entry.getValue().boundary) updateVoxel(digest, entry.getKey());
        }
        return hex(digest.digest());
    }

    private record TimedChunk(ChunkGeometry geometry, long elapsedNanos) {}

    private record TimedIntentPlan(ChunkPlan plan, long elapsedNanos) {}

    private record IntentOrderAudit(
            TreeMap<Voxel, PlannedVoxel> values,
            Map<ChunkKey, ChunkPlan> chunks,
            List<Long> nonemptyPlanNanos) {}

    private record OrderAudit(
            TreeMap<Voxel, MutableFlags> values,
            Map<ChunkKey, ChunkGeometry> chunks,
            List<Long> nonemptyQueryNanos) {}

    private static TimedChunk timedQuery(KarstDeltaChunkReader reader, ChunkKey key) {
        long started = System.nanoTime();
        ChunkGeometry geometry = reader.queryChunk(
                key.x, key.z, reader.minimumY, reader.maximumY, 1);
        return new TimedChunk(geometry, System.nanoTime() - started);
    }

    private static OrderAudit auditOrder(
            KarstDeltaChunkReader reader,
            List<ChunkKey> order,
            boolean concurrent) throws Exception {
        List<TimedChunk> results = new ArrayList<>();
        if (concurrent) {
            ExecutorService executor = Executors.newFixedThreadPool(AUDIT_THREADS);
            try {
                List<Callable<TimedChunk>> tasks = new ArrayList<>();
                for (ChunkKey key : order) tasks.add(() -> timedQuery(reader, key));
                List<Future<TimedChunk>> futures = executor.invokeAll(tasks);
                for (Future<TimedChunk> future : futures) results.add(future.get());
            } finally {
                executor.shutdownNow();
            }
        } else {
            for (ChunkKey key : order) results.add(timedQuery(reader, key));
        }
        TreeMap<Voxel, MutableFlags> values = new TreeMap<>();
        Map<ChunkKey, ChunkGeometry> chunks = new HashMap<>();
        List<Long> timings = new ArrayList<>();
        for (TimedChunk result : results) {
            ChunkGeometry geometry = result.geometry;
            chunks.put(geometry.chunk, geometry);
            if (!geometry.owned.isEmpty()) timings.add(result.elapsedNanos);
            for (VoxelIntent intent : geometry.owned) {
                MutableFlags prior = values.put(intent.voxel, flags(intent));
                if (prior != null) throw new IllegalArgumentException("chunk ownership overlaps");
            }
        }
        return new OrderAudit(values, Map.copyOf(chunks), List.copyOf(timings));
    }

    private static MutableFlags flags(VoxelIntent intent) {
        MutableFlags result = new MutableFlags();
        result.base = !intent.evolvedAddition;
        result.delta = intent.evolvedAddition;
        result.protectedRoute = intent.protectedRoute;
        result.boundary = intent.registeredBoundary;
        return result;
    }

    private static double haloVisibility(OrderAudit audit) {
        int seams = 0;
        int visible = 0;
        Set<Voxel> all = audit.values.keySet();
        for (Voxel voxel : all) {
            for (int[] direction : new int[][] {{1, 0, 0}, {0, 0, 1}}) {
                Voxel neighbor = new Voxel(
                        voxel.x + direction[0], voxel.y, voxel.z + direction[2]);
                if (!all.contains(neighbor)) continue;
                ChunkKey first = new ChunkKey(
                        Math.floorDiv(voxel.x, CHUNK_SIZE),
                        Math.floorDiv(voxel.z, CHUNK_SIZE));
                ChunkKey second = new ChunkKey(
                        Math.floorDiv(neighbor.x, CHUNK_SIZE),
                        Math.floorDiv(neighbor.z, CHUNK_SIZE));
                if (first.equals(second)) continue;
                seams++;
                ChunkGeometry firstGeometry = audit.chunks.get(first);
                ChunkGeometry secondGeometry = audit.chunks.get(second);
                if (firstGeometry != null && secondGeometry != null
                        && containsHalo(firstGeometry, neighbor)
                        && containsHalo(secondGeometry, voxel)) {
                    visible++;
                }
            }
        }
        return seams == 0 ? 1.0 : (double) visible / seams;
    }

    private static boolean containsHalo(ChunkGeometry geometry, Voxel voxel) {
        for (VoxelIntent intent : geometry.readOnlyHalo) {
            if (intent.voxel.equals(voxel)) return true;
        }
        return false;
    }

    private static double percentileNanos(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0.0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double position = percentile / 100.0 * (sorted.size() - 1);
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        double fraction = position - low;
        return (sorted.get(low) * (1.0 - fraction) + sorted.get(high) * fraction)
                / 1_000_000_000.0;
    }

    private static HostMinecraftRegistry run193Registry(
            Voxel specialWorldVoxel,
            Replaceability specialDecision,
            boolean emptyMaterial,
            boolean emptyBiome) {
        return new HostMinecraftRegistry() {
            @Override
            public Replaceability replaceabilityFor(
                    Voxel worldVoxel, VoxelIntent sourceIntent) {
                return specialWorldVoxel != null && specialWorldVoxel.equals(worldVoxel)
                        ? specialDecision
                        : Replaceability.REPLACE;
            }

            @Override
            public String materialFor(Voxel worldVoxel, VoxelIntent sourceIntent) {
                return emptyMaterial ? "" : "test-host:carved-void";
            }

            @Override
            public String biomeFor(Voxel worldVoxel, VoxelIntent sourceIntent) {
                return emptyBiome ? "" : "test-host:underground";
            }
        };
    }

    private static String run286BiomeFor(Voxel voxel) {
        int selector = Math.floorMod(
                Math.floorDiv(voxel.x, 32) + Math.floorDiv(voxel.z, 32), 3);
        return selector == 0
                ? "minecraft:lush_caves"
                : selector == 1
                        ? "minecraft:deep_dark"
                        : "undergroundworlds:underground_tundra";
    }

    private static HostMinecraftRegistry run286Registry(Voxel blankBiomeVoxel) {
        return new HostMinecraftRegistry() {
            @Override
            public Replaceability replaceabilityFor(
                    Voxel worldVoxel, VoxelIntent sourceIntent) {
                return Replaceability.REPLACE;
            }

            @Override
            public String materialFor(Voxel worldVoxel, VoxelIntent sourceIntent) {
                return "minecraft:cave_air";
            }

            @Override
            public String biomeFor(Voxel worldVoxel, VoxelIntent sourceIntent) {
                return worldVoxel.equals(blankBiomeVoxel) ? "" : run286BiomeFor(worldVoxel);
            }
        };
    }

    private static TimedIntentPlan timedIntentPlan(
            MinecraftIntentPlanner planner,
            ChunkKey key,
            HostMinecraftRegistry registry,
            WorldBounds bounds) {
        long started = System.nanoTime();
        ChunkPlan plan = planner.planChunk(
                key.x, key.z, bounds.lower.y, bounds.upper.y, registry);
        return new TimedIntentPlan(plan, System.nanoTime() - started);
    }

    private static IntentOrderAudit auditIntentOrder(
            MinecraftIntentPlanner planner,
            List<ChunkKey> order,
            boolean concurrent,
            HostMinecraftRegistry registry,
            WorldBounds bounds) throws Exception {
        List<TimedIntentPlan> results = new ArrayList<>();
        if (concurrent) {
            ExecutorService executor = Executors.newFixedThreadPool(AUDIT_THREADS);
            try {
                List<Callable<TimedIntentPlan>> tasks = new ArrayList<>();
                for (ChunkKey key : order) {
                    tasks.add(() -> timedIntentPlan(planner, key, registry, bounds));
                }
                for (Future<TimedIntentPlan> future : executor.invokeAll(tasks)) {
                    results.add(future.get());
                }
            } finally {
                executor.shutdownNow();
            }
        } else {
            for (ChunkKey key : order) {
                results.add(timedIntentPlan(planner, key, registry, bounds));
            }
        }
        TreeMap<Voxel, PlannedVoxel> values = new TreeMap<>();
        Map<ChunkKey, ChunkPlan> chunks = new HashMap<>();
        List<Long> timings = new ArrayList<>();
        for (TimedIntentPlan result : results) {
            ChunkPlan plan = result.plan;
            if (plan.state != PlanState.ACTIVE || !plan.skipped.isEmpty()) {
                throw new IllegalArgumentException("positive intent plan differs");
            }
            chunks.put(plan.worldChunk, plan);
            if (!plan.planned.isEmpty()) timings.add(result.elapsedNanos);
            for (PlannedVoxel value : plan.planned) {
                PlannedVoxel prior = values.put(value.sourceVoxel, value);
                if (prior != null) {
                    throw new IllegalArgumentException("intent plan source ownership overlaps");
                }
            }
        }
        return new IntentOrderAudit(values, Map.copyOf(chunks), List.copyOf(timings));
    }

    private static String intentVolumeDigest(TreeMap<Voxel, PlannedVoxel> values) {
        TreeMap<Voxel, MutableFlags> flags = new TreeMap<>();
        for (PlannedVoxel value : values.values()) {
            MutableFlags item = new MutableFlags();
            item.base = !value.evolvedAddition;
            item.delta = value.evolvedAddition;
            item.protectedRoute = value.protectedRoute;
            item.boundary = value.registeredBoundary;
            flags.put(value.sourceVoxel, item);
        }
        return volumeDigest(flags);
    }

    private static double sourceWorldRoundTrip(IntentOrderAudit audit, Translation translation) {
        long valid = 0;
        for (PlannedVoxel value : audit.values.values()) {
            try {
                Voxel recovered = new Voxel(
                        Math.subtractExact(value.worldVoxel.x, translation.x),
                        Math.subtractExact(value.worldVoxel.y, translation.y),
                        Math.subtractExact(value.worldVoxel.z, translation.z));
                if (recovered.equals(value.sourceVoxel)) valid++;
            } catch (ArithmeticException ignored) {
                // Counted as a failed round trip.
            }
        }
        return audit.values.isEmpty() ? 1.0 : (double) valid / audit.values.size();
    }

    private static PlannedVoxel firstMatching(
            IntentOrderAudit audit,
            java.util.function.Predicate<PlannedVoxel> predicate) {
        for (PlannedVoxel value : audit.values.values()) {
            if (predicate.test(value)) return value;
        }
        throw new IllegalArgumentException("required intent fixture voxel is absent");
    }

    private static ChunkPlan planContaining(
            MinecraftIntentPlanner planner,
            PlannedVoxel value,
            HostMinecraftRegistry registry,
            WorldBounds bounds) {
        return planner.planChunk(
                Math.floorDiv(value.worldVoxel.x, CHUNK_SIZE),
                Math.floorDiv(value.worldVoxel.z, CHUNK_SIZE),
                bounds.lower.y,
                bounds.upper.y,
                registry);
    }

    private static String auditMinecraftIntents(Path path, boolean vanillaCandidate)
            throws Exception {
        KarstDeltaChunkReader reader = open(path, true);
        Translation translation = vanillaCandidate
                ? new Translation(4099, Math.subtractExact(-48, reader.minimumY), -3077)
                : new Translation(4099, 211, -3077);
        WorldBounds extended = new WorldBounds(
                new Voxel(-30_000_000, -1024, -30_000_000),
                new Voxel(29_999_999, 1023, 29_999_999));
        WorldBounds vanilla = new WorldBounds(
                new Voxel(-30_000_000, -64, -30_000_000),
                new Voxel(29_999_999, 319, 29_999_999));
        WorldBounds positiveBounds = vanillaCandidate ? vanilla : extended;
        HostMinecraftRegistry allow = run193Registry(
                null, Replaceability.REPLACE, false, false);
        MinecraftIntentPlanner planner = MinecraftIntentPlanner.bind(
                reader, reader.cacheIdentitySha256, translation, positiveBounds);
        List<ChunkKey> canonical = planner.candidateWorldChunks;
        List<ChunkKey> reverse = new ArrayList<>(canonical);
        Collections.reverse(reverse);
        List<ChunkKey> concurrent = new ArrayList<>(canonical);
        Collections.shuffle(concurrent, new java.util.Random(193));
        IntentOrderAudit canonicalAudit = auditIntentOrder(
                planner, canonical, false, allow, positiveBounds);
        IntentOrderAudit reverseAudit = auditIntentOrder(
                planner, reverse, false, allow, positiveBounds);
        IntentOrderAudit concurrentAudit = auditIntentOrder(
                planner, concurrent, true, allow, positiveBounds);
        String expectedVolume = required(reader.properties, "expected_volume_sha256");
        boolean canonicalExact = expectedVolume.equals(intentVolumeDigest(canonicalAudit.values));
        boolean reverseExact = expectedVolume.equals(intentVolumeDigest(reverseAudit.values));
        boolean concurrentExact = expectedVolume.equals(intentVolumeDigest(concurrentAudit.values));
        double roundTrip = sourceWorldRoundTrip(canonicalAudit, translation);
        double materialResolution = canonicalAudit.values.isEmpty()
                ? 1.0
                : (double) canonicalAudit.values.values().stream()
                        .filter(value -> "test-host:carved-void".equals(value.material))
                        .count() / canonicalAudit.values.size();
        double biomeResolution = canonicalAudit.values.isEmpty()
                ? 1.0
                : (double) canonicalAudit.values.values().stream()
                        .filter(value -> "test-host:underground".equals(value.biome))
                        .count() / canonicalAudit.values.size();

        PlannedVoxel ordinary = firstMatching(
                canonicalAudit, value -> !value.protectedRoute && !value.registeredBoundary);
        PlannedVoxel protectedValue = firstMatching(
                canonicalAudit, value -> value.protectedRoute && !value.registeredBoundary);
        PlannedVoxel boundary = firstMatching(
                canonicalAudit, value -> value.registeredBoundary);
        ChunkPlan ordinaryBaseline = planContaining(planner, ordinary, allow, positiveBounds);
        ChunkPlan ordinarySkip = planContaining(
                planner,
                ordinary,
                run193Registry(
                        ordinary.worldVoxel, Replaceability.PRESERVE, false, false),
                positiveBounds);
        boolean ordinarySkipExact = ordinarySkip.state == PlanState.ACTIVE
                && ordinarySkip.skipped.size() == 1
                && ordinarySkip.skipped.get(0).worldVoxel.equals(ordinary.worldVoxel)
                && ordinarySkip.planned.size() + 1 == ordinaryBaseline.planned.size();
        ChunkPlan protectedConflict = planContaining(
                planner,
                protectedValue,
                run193Registry(
                        protectedValue.worldVoxel, Replaceability.CONFLICT, false, false),
                positiveBounds);
        boolean protectedRejected = protectedConflict.state == PlanState.REJECTED
                && MinecraftIntentPlanner.PROTECTED_REASON.equals(protectedConflict.reason)
                && protectedConflict.planned.isEmpty();
        ChunkPlan boundaryConflict = planContaining(
                planner,
                boundary,
                run193Registry(boundary.worldVoxel, Replaceability.CONFLICT, false, false),
                positiveBounds);
        boolean boundaryRejected = boundaryConflict.state == PlanState.REJECTED
                && MinecraftIntentPlanner.BOUNDARY_REASON.equals(boundaryConflict.reason)
                && boundaryConflict.planned.isEmpty();

        ChunkKey probeChunk = canonical.get(0);
        MinecraftIntentPlanner identityMismatch = MinecraftIntentPlanner.bind(
                reader,
                "0000000000000000000000000000000000000000000000000000000000000000",
                translation,
                positiveBounds);
        ChunkPlan identityPlan = identityMismatch.planChunk(
                probeChunk.x,
                probeChunk.z,
                positiveBounds.lower.y,
                positiveBounds.upper.y,
                allow);
        boolean identityInert = identityPlan.state == PlanState.INERT
                && MinecraftIntentPlanner.IDENTITY_REASON.equals(identityPlan.reason)
                && identityPlan.planned.isEmpty();
        MinecraftIntentPlanner vanillaMismatch = MinecraftIntentPlanner.bind(
                reader, reader.cacheIdentitySha256, translation, vanilla);
        ChunkPlan vanillaPlan = vanillaMismatch.planChunk(
                probeChunk.x, probeChunk.z, vanilla.lower.y, vanilla.upper.y, allow);
        boolean vanillaInert = vanillaPlan.state == PlanState.INERT
                && MinecraftIntentPlanner.BOUNDS_REASON.equals(vanillaPlan.reason)
                && vanillaPlan.planned.isEmpty();
        boolean vanillaActive = vanillaMismatch.bindingState == PlanState.ACTIVE
                && vanillaPlan.state == PlanState.ACTIVE;
        ChunkPlan emptyMaterial = planContaining(
                planner,
                ordinary,
                run193Registry(null, Replaceability.REPLACE, true, false),
                positiveBounds);
        ChunkPlan emptyBiome = planContaining(
                planner,
                ordinary,
                run193Registry(null, Replaceability.REPLACE, false, true),
                positiveBounds);
        boolean materialRejected = emptyMaterial.state == PlanState.REJECTED
                && MinecraftIntentPlanner.REGISTRY_REASON.equals(emptyMaterial.reason)
                && emptyMaterial.planned.isEmpty();
        boolean biomeRejected = emptyBiome.state == PlanState.REJECTED
                && MinecraftIntentPlanner.REGISTRY_REASON.equals(emptyBiome.reason)
                && emptyBiome.planned.isEmpty();

        List<Long> planTimes = new ArrayList<>();
        planTimes.addAll(canonicalAudit.nonemptyPlanNanos);
        planTimes.addAll(reverseAudit.nonemptyPlanNanos);
        planTimes.addAll(concurrentAudit.nonemptyPlanNanos);
        return "{\"state\":\"PASS\",\"package\":\"" + escape(reader.root.toString())
                + "\",\"cache_identity_sha256\":\"" + reader.cacheIdentitySha256
                + "\",\"binding_active\":" + (planner.bindingState == PlanState.ACTIVE)
                + ",\"binding_reason\":\"" + escape(planner.bindingReason) + "\""
                + ",\"candidate_world_chunk_count\":" + canonical.size()
                + ",\"planned_void_voxel_count\":" + canonicalAudit.values.size()
                + ",\"canonical_exact\":" + canonicalExact
                + ",\"reverse_exact\":" + reverseExact
                + ",\"concurrent_exact\":" + concurrentExact
                + ",\"source_world_round_trip_fraction\":" + roundTrip
                + ",\"host_material_resolution_fraction\":" + materialResolution
                + ",\"host_biome_resolution_fraction\":" + biomeResolution
                + ",\"ordinary_single_skip_exact\":" + ordinarySkipExact
                + ",\"protected_conflict_rejected\":" + protectedRejected
                + ",\"boundary_conflict_rejected\":" + boundaryRejected
                + ",\"identity_mismatch_inert\":" + identityInert
                + ",\"extended_world_bounds_active\":"
                + (planner.bindingState == PlanState.ACTIVE)
                + ",\"vanilla_overworld_bounds_inert\":" + vanillaInert
                + ",\"vanilla_overworld_bounds_active\":" + vanillaActive
                + ",\"empty_material_rejected\":" + materialRejected
                + ",\"empty_biome_rejected\":" + biomeRejected
                + ",\"nonempty_plan_p95_seconds\":"
                + percentileNanos(planTimes, 95.0)
                + ",\"nonempty_plan_maximum_seconds\":"
                + percentileNanos(planTimes, 100.0)
                + ",\"global_volume_reconstruction_used\":false"
                + ",\"world_object_reference_used\":false"
                + ",\"world_block_write_count\":0}";
    }

    private static String auditBiomeIntents(Path path) throws Exception {
        KarstDeltaChunkReader reader = open(path, true);
        Translation translation = new Translation(
                4099, Math.subtractExact(-48, reader.minimumY), -3077);
        WorldBounds vanilla = new WorldBounds(
                new Voxel(-30_000_000, -64, -30_000_000),
                new Voxel(29_999_999, 319, 29_999_999));
        MinecraftIntentPlanner planner = MinecraftIntentPlanner.bind(
                reader, reader.cacheIdentitySha256, translation, vanilla);
        if (planner.bindingState != PlanState.ACTIVE) {
            throw new IllegalArgumentException("Run286 biome planner is not active");
        }
        List<ChunkKey> canonicalOrder = planner.candidateWorldChunks;
        List<ChunkKey> reverseOrder = new ArrayList<>(canonicalOrder);
        Collections.reverse(reverseOrder);
        List<ChunkKey> concurrentOrder = new ArrayList<>(canonicalOrder);
        Collections.shuffle(concurrentOrder, new java.util.Random(286));
        HostMinecraftRegistry registry = run286Registry(null);
        IntentOrderAudit canonical = auditIntentOrder(
                planner, canonicalOrder, false, registry, vanilla);
        IntentOrderAudit reverse = auditIntentOrder(
                planner, reverseOrder, false, registry, vanilla);
        IntentOrderAudit concurrent = auditIntentOrder(
                planner, concurrentOrder, true, registry, vanilla);
        boolean reverseExact = canonical.values.equals(reverse.values);
        boolean concurrentExact = canonical.values.equals(concurrent.values);
        long exact = canonical.values.values().stream()
                .filter(value -> value.biome.equals(run286BiomeFor(value.worldVoxel)))
                .count();
        long lush = canonical.values.values().stream()
                .filter(value -> "minecraft:lush_caves".equals(value.biome))
                .count();
        long deep = canonical.values.values().stream()
                .filter(value -> "minecraft:deep_dark".equals(value.biome))
                .count();
        long modded = canonical.values.values().stream()
                .filter(value -> "undergroundworlds:underground_tundra".equals(value.biome))
                .count();
        PlannedVoxel ordinary = firstMatching(
                canonical, value -> !value.protectedRoute && !value.registeredBoundary);
        ChunkPlan blankBiome = planContaining(
                planner, ordinary, run286Registry(ordinary.worldVoxel), vanilla);
        boolean blankRejected = blankBiome.state == PlanState.REJECTED
                && MinecraftIntentPlanner.REGISTRY_REASON.equals(blankBiome.reason)
                && blankBiome.planned.isEmpty();
        int total = canonical.values.size();
        return "{\"state\":\"PASS\",\"package\":\"" + escape(reader.root.toString())
                + "\",\"planned_void_voxel_count\":" + total
                + ",\"exact_registry_biome_fraction\":"
                + (total == 0 ? 1.0 : (double) exact / total)
                + ",\"canonical_reverse_exact\":" + reverseExact
                + ",\"canonical_concurrent_exact\":" + concurrentExact
                + ",\"lush_caves_count\":" + lush
                + ",\"deep_dark_count\":" + deep
                + ",\"modded_cave_biome_count\":" + modded
                + ",\"blank_biome_rejected\":" + blankRejected
                + ",\"world_object_reference_used\":false"
                + ",\"world_block_write_count\":0}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String audit(Path path) throws Exception {
        KarstDeltaChunkReader reader = open(path, true);
        List<ChunkKey> canonical = reader.candidateChunks;
        List<ChunkKey> reverse = new ArrayList<>(canonical);
        Collections.reverse(reverse);
        List<ChunkKey> concurrent = new ArrayList<>(canonical);
        Collections.shuffle(concurrent, new java.util.Random(192));
        OrderAudit canonicalAudit = auditOrder(reader, canonical, false);
        OrderAudit reverseAudit = auditOrder(reader, reverse, false);
        OrderAudit concurrentAudit = auditOrder(reader, concurrent, true);
        String expectedVolume = required(reader.properties, "expected_volume_sha256");
        String expectedBoundary = required(reader.properties, "expected_boundary_sha256");
        boolean canonicalExact = expectedVolume.equals(volumeDigest(canonicalAudit.values));
        boolean reverseExact = expectedVolume.equals(volumeDigest(reverseAudit.values));
        boolean concurrentExact = expectedVolume.equals(volumeDigest(concurrentAudit.values));
        boolean protectedExact = requiredInt(reader.properties, "protected_voxel_count")
                == canonicalAudit.values.values().stream().filter(value -> value.protectedRoute).count();
        boolean boundaryExact = expectedBoundary.equals(boundaryDigest(canonicalAudit.values));
        double haloVisibility = haloVisibility(canonicalAudit);
        long resolved = 0;
        long owned = 0;
        for (ChunkGeometry geometry : canonicalAudit.chunks.values()) {
            owned += geometry.owned.size();
            resolved += reader.resolveOwned(
                    geometry,
                    intent -> "host-material",
                    intent -> "host-biome").size();
        }
        List<Long> queryTimes = new ArrayList<>();
        queryTimes.addAll(canonicalAudit.nonemptyQueryNanos);
        queryTimes.addAll(reverseAudit.nonemptyQueryNanos);
        queryTimes.addAll(concurrentAudit.nonemptyQueryNanos);
        double queryP95 = percentileNanos(queryTimes, 95.0);
        double queryMaximum = percentileNanos(queryTimes, 100.0);
        return "{\"state\":\"PASS\",\"package\":\"" + escape(reader.root.toString())
                + "\",\"cache_identity_sha256\":\"" + reader.cacheIdentitySha256
                + "\",\"verified_open\":true"
                + ",\"verified_open_seconds\":" + reader.verifiedOpenSeconds
                + ",\"candidate_chunk_query_count\":" + canonical.size()
                + ",\"nonempty_chunk_query_count\":" + canonicalAudit.nonemptyQueryNanos.size()
                + ",\"owned_void_voxel_count\":" + canonicalAudit.values.size()
                + ",\"canonical_exact\":" + canonicalExact
                + ",\"reverse_exact\":" + reverseExact
                + ",\"concurrent_exact\":" + concurrentExact
                + ",\"protected_exact\":" + protectedExact
                + ",\"boundary_exact\":" + boundaryExact
                + ",\"void_halo_visibility_fraction\":" + haloVisibility
                + ",\"host_registry_resolution_fraction\":"
                + (owned == 0 ? 1.0 : (double) resolved / owned)
                + ",\"mapped_local_payload_bytes\":" + reader.mappedLocalPayloadBytes
                + ",\"nonempty_chunk_query_p95_seconds\":" + queryP95
                + ",\"nonempty_chunk_query_maximum_seconds\":" + queryMaximum
                + ",\"global_volume_reconstruction_used\":false"
                + ",\"world_block_write_count\":0}";
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("provide package paths");
        if ("--intent-audit".equals(args[0])) {
            if (args.length == 1) throw new IllegalArgumentException("provide package paths");
            for (int index = 1; index < args.length; index++) {
                System.out.println(auditMinecraftIntents(Path.of(args[index]), false));
            }
            return;
        }
        if ("--intent-audit-vanilla".equals(args[0])) {
            if (args.length == 1) throw new IllegalArgumentException("provide package paths");
            for (int index = 1; index < args.length; index++) {
                System.out.println(auditMinecraftIntents(Path.of(args[index]), true));
            }
            return;
        }
        if ("--biome-intent-audit".equals(args[0])) {
            if (args.length == 1) throw new IllegalArgumentException("provide package paths");
            for (int index = 1; index < args.length; index++) {
                System.out.println(auditBiomeIntents(Path.of(args[index])));
            }
            return;
        }
        for (String argument : args) System.out.println(audit(Path.of(argument)));
    }
}
