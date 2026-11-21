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
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Read-only live collision-shape measurement for Karstogenesis run 210. */
public final class CollisionAuditState {
    private static final int EXPECTED_TARGETS = 23328;
    private static final int EXPECTED_PROTECTED = 5395;
    private static final int EXPECTED_BOUNDARY = 42;
    private static final int AUDIT_TICK = 40;

    private record Target(int x, int y, int z, boolean protectedRoute, boolean boundary) {}

    private static List<Target> targets = List.of();
    private static Set<ChunkPos> chunks = Set.of();
    private static Path receiptPath;
    private static Path registerPath;
    private static boolean initialized;
    private static boolean complete;
    private static int ticks;

    private CollisionAuditState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String targetValue = System.getProperty("geoworld.audit210.target", "").trim();
        String targetSha = System.getProperty("geoworld.audit210.targetSha256", "").trim();
        String receiptValue = System.getProperty("geoworld.audit210.receipt", "").trim();
        String registerValue = System.getProperty("geoworld.audit210.register", "").trim();
        require(!targetValue.isEmpty(), "target register property is empty");
        require(targetSha.length() == 64, "target register digest is invalid");
        require(!receiptValue.isEmpty() && !registerValue.isEmpty(), "output property is empty");
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
            throw new IllegalStateException("Run 210 input read failed", exception);
        }
        initialized = true;
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "Run 210 is not initialized");
        for (ChunkPos chunk : chunks) {
            server.getOverworld().getChunkManager().setChunkForced(chunk, true);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        synchronized (CollisionAuditState.class) {
            if (complete) return;
            ticks++;
            if (ticks < AUDIT_TICK) return;
            complete = true;
        }
        try {
            audit(server);
        } catch (RuntimeException exception) {
            KarstogenesisFabricCollisionAudit.LOGGER.error("Run 210 audit failed", exception);
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
        register.append("x\ty\tz\tprotected_route\tregistered_boundary\tblock_id\tair\tfluid\tempty_collision\tfull_state\n");
        int airCount = 0;
        int fluidCount = 0;
        int emptyCollisionCount = 0;
        int protectedCollisionFree = 0;
        int boundaryCollisionFree = 0;
        for (Target target : targets) {
            BlockPos position = new BlockPos(target.x(), target.y(), target.z());
            BlockState state = world.getBlockState(position);
            boolean air = state.isAir();
            boolean fluid = !state.getFluidState().isEmpty();
            boolean emptyCollision = state.getCollisionShape(world, position).isEmpty();
            boolean collisionFree = air || fluid || emptyCollision;
            if (air) airCount++;
            if (fluid) fluidCount++;
            if (emptyCollision) emptyCollisionCount++;
            if (target.protectedRoute() && collisionFree) protectedCollisionFree++;
            if (target.boundary() && collisionFree) boundaryCollisionFree++;
            register.append(target.x()).append('\t')
                    .append(target.y()).append('\t')
                    .append(target.z()).append('\t')
                    .append(target.protectedRoute()).append('\t')
                    .append(target.boundary()).append('\t')
                    .append(Registries.BLOCK.getId(state.getBlock())).append('\t')
                    .append(air).append('\t')
                    .append(fluid).append('\t')
                    .append(emptyCollision).append('\t')
                    .append(state).append('\n');
        }
        writeAtomic(registerPath, register.toString());
        String receipt = String.format(
                Locale.ROOT,
                "{\"schema_version\":\"geoworld.karstogenesis-210-live-collision-audit-arm.v1\",\"state\":\"DONE\",\"target_count\":%d,\"protected_count\":%d,\"boundary_count\":%d,\"chunk_count\":%d,\"air_count\":%d,\"fluid_count\":%d,\"empty_collision_count\":%d,\"protected_collision_free_count\":%d,\"boundary_collision_free_count\":%d,\"register_sha256\":\"%s\"}\n",
                targets.size(),
                EXPECTED_PROTECTED,
                EXPECTED_BOUNDARY,
                chunks.size(),
                airCount,
                fluidCount,
                emptyCollisionCount,
                protectedCollisionFree,
                boundaryCollisionFree,
                sha256(registerPath));
        writeAtomic(receiptPath, receipt);
    }

    private static void writeFailure(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ":" + String.valueOf(exception.getMessage());
        writeAtomic(
                receiptPath,
                "{\"schema_version\":\"geoworld.karstogenesis-210-live-collision-audit-arm.v1\",\"state\":\"FAIL\",\"error\":\""
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
            throw new IllegalStateException("Run 210 output write failed", exception);
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Run 210 digest failed", exception);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
