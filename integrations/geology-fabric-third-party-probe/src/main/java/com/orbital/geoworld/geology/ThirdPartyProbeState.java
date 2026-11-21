package com.orbital.geoworld.geology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

/** Read-only block, biome and collision inventory for Karstogenesis run 212. */
public final class ThirdPartyProbeState {
    private static final int EXPECTED_TARGETS = 23328;
    private static final int EXPECTED_PROTECTED = 5395;
    private static final int EXPECTED_BOUNDARY = 42;
    private static final int AUDIT_TICK = 80;

    private record Target(int x, int y, int z, boolean protectedRoute, boolean boundary) {}

    private static List<Target> targets = List.of();
    private static Set<ChunkPos> chunks = Set.of();
    private static Set<String> expectedMods = Set.of();
    private static String primaryMod;
    private static String providerNamespace;
    private static Path receiptPath;
    private static Path registerPath;
    private static boolean initialized;
    private static boolean complete;
    private static int ticks;

    private ThirdPartyProbeState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String targetValue = property("target");
        String targetSha = property("targetSha256");
        String receiptValue = property("receipt");
        String registerValue = property("register");
        String expectedValue = property("expectedMods");
        primaryMod = property("primaryMod");
        providerNamespace = property("namespace");
        require(!targetValue.isEmpty(), "target register property is empty");
        require(targetSha.length() == 64, "target register digest is invalid");
        require(!receiptValue.isEmpty() && !registerValue.isEmpty(), "output property is empty");
        require(!providerNamespace.isEmpty(), "provider namespace is empty");
        expectedMods = expectedValue.isEmpty() ? Set.of() : Set.of(expectedValue.split(","));
        Path targetPath = Path.of(targetValue).toAbsolutePath().normalize();
        receiptPath = Path.of(receiptValue).toAbsolutePath().normalize();
        registerPath = Path.of(registerValue).toAbsolutePath().normalize();
        try {
            require(sha256(targetPath).equals(targetSha), "target register digest differs");
            List<Target> loaded = new ArrayList<>();
            Set<ChunkPos> loadedChunks = new HashSet<>();
            int protectedCount = 0;
            int boundaryCount = 0;
            for (String line : Files.readAllLines(targetPath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\\t", 7);
                require(fields.length == 7, "target register row width differs");
                Target target = new Target(
                        Integer.parseInt(fields[0]),
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]),
                        Boolean.parseBoolean(fields[3]),
                        Boolean.parseBoolean(fields[4]));
                loaded.add(target);
                loadedChunks.add(new ChunkPos(Math.floorDiv(target.x(), 16), Math.floorDiv(target.z(), 16)));
                if (target.protectedRoute()) protectedCount++;
                if (target.boundary()) boundaryCount++;
            }
            loaded.sort(Comparator.comparingInt(Target::x)
                    .thenComparingInt(Target::y)
                    .thenComparingInt(Target::z));
            require(loaded.size() == EXPECTED_TARGETS, "target count differs");
            require(protectedCount == EXPECTED_PROTECTED, "protected count differs");
            require(boundaryCount == EXPECTED_BOUNDARY, "boundary count differs");
            targets = List.copyOf(loaded);
            chunks = Set.copyOf(loadedChunks);
        } catch (IOException exception) {
            throw new IllegalStateException("Run 212 input read failed", exception);
        }
        initialized = true;
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "Run 212 is not initialized");
        for (String mod : expectedMods) {
            require(FabricLoader.getInstance().isModLoaded(mod), "expected mod is not loaded: " + mod);
        }
        for (ChunkPos chunk : chunks) {
            server.getOverworld().getChunkManager().setChunkForced(chunk, true);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        synchronized (ThirdPartyProbeState.class) {
            if (complete) return;
            ticks++;
            if (ticks < AUDIT_TICK) return;
            complete = true;
        }
        try {
            audit(server);
        } catch (RuntimeException exception) {
            KarstogenesisFabricThirdPartyProbe.LOGGER.error("Run 212 probe failed", exception);
            writeFailure(exception);
        }
        for (ChunkPos chunk : chunks) {
            server.getOverworld().getChunkManager().setChunkForced(chunk, false);
        }
        server.stop(false);
    }

    private static void audit(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        StringBuilder register = new StringBuilder();
        register.append("x\ty\tz\tprotected_route\tregistered_boundary\tblock_id\tbiome_id\tair\tfluid\tempty_collision\tbase_stone\tfull_state\n");
        Set<String> uniqueBlocks = new HashSet<>();
        Set<String> uniqueBiomes = new HashSet<>();
        int namespaceBlockCount = 0;
        int namespaceBiomeCount = 0;
        for (Target target : targets) {
            BlockPos position = new BlockPos(target.x(), target.y(), target.z());
            BlockState state = world.getBlockState(position);
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            String biomeId = biomeIdentity(world.getChunk(position), position);
            boolean air = state.isAir();
            boolean fluid = !state.getFluidState().isEmpty();
            boolean emptyCollision = state.getCollisionShape(world, position).isEmpty();
            boolean baseStone = state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD);
            uniqueBlocks.add(blockId);
            uniqueBiomes.add(biomeId);
            if (blockId.startsWith(providerNamespace + ":")) namespaceBlockCount++;
            if (biomeId.startsWith(providerNamespace + ":")) namespaceBiomeCount++;
            register.append(target.x()).append('\t')
                    .append(target.y()).append('\t')
                    .append(target.z()).append('\t')
                    .append(target.protectedRoute()).append('\t')
                    .append(target.boundary()).append('\t')
                    .append(blockId).append('\t')
                    .append(biomeId).append('\t')
                    .append(air).append('\t')
                    .append(fluid).append('\t')
                    .append(emptyCollision).append('\t')
                    .append(baseStone).append('\t')
                    .append(state).append('\n');
        }
        writeAtomic(registerPath, register.toString());
        int primaryLoadedCount = primaryMod.isEmpty() ? 0 : (FabricLoader.getInstance().isModLoaded(primaryMod) ? 1 : 0);
        String receipt = String.format(
                Locale.ROOT,
                "{\"schema_version\":\"geoworld.karstogenesis-212-third-party-provider-probe-arm.v1\",\"state\":\"DONE\",\"target_count\":%d,\"protected_count\":%d,\"boundary_count\":%d,\"chunk_count\":%d,\"expected_mod_count\":%d,\"primary_mod_loaded_count\":%d,\"provider_namespace\":\"%s\",\"namespace_block_observation_count\":%d,\"namespace_biome_observation_count\":%d,\"unique_block_count\":%d,\"unique_biome_count\":%d,\"register_sha256\":\"%s\"}\n",
                targets.size(),
                EXPECTED_PROTECTED,
                EXPECTED_BOUNDARY,
                chunks.size(),
                expectedMods.size(),
                primaryLoadedCount,
                jsonEscape(providerNamespace),
                namespaceBlockCount,
                namespaceBiomeCount,
                uniqueBlocks.size(),
                uniqueBiomes.size(),
                sha256(registerPath));
        writeAtomic(receiptPath, receipt);
    }

    private static String biomeIdentity(Chunk chunk, BlockPos position) {
        return chunk.getBiomeForNoiseGen(
                        Math.floorDiv(position.getX(), 4),
                        Math.floorDiv(position.getY(), 4),
                        Math.floorDiv(position.getZ(), 4))
                .getKey()
                .map(key -> key.getValue().toString())
                .orElse("host:unregistered-biome");
    }

    private static String property(String name) {
        return System.getProperty("geoworld.probe212." + name, "").trim();
    }

    private static void writeFailure(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ":" + String.valueOf(exception.getMessage());
        writeAtomic(
                receiptPath,
                "{\"schema_version\":\"geoworld.karstogenesis-212-third-party-provider-probe-arm.v1\",\"state\":\"FAIL\",\"error\":\""
                        + jsonEscape(message)
                        + "\"}\n");
    }

    private static void writeAtomic(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".partial");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Run 212 output write failed", exception);
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Run 212 digest failed", exception);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
