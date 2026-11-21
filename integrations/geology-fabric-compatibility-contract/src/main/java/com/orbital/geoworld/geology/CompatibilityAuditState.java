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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Read-only live registry-tag and collision-contract audit for Karstogenesis run 211. */
public final class CompatibilityAuditState {
    private static final int EXPECTED_TARGETS = 23328;
    private static final int EXPECTED_PROTECTED = 5395;
    private static final int EXPECTED_BOUNDARY = 42;
    private static final int AUDIT_TICK = 40;
    private static final String EXTERNAL_CONTRIBUTOR = "karstogenesis-compat-fixture";

    private static final TagKey<Block> PASS_THROUGH_DECORATION = tag("cave_pass_through_decoration");
    private static final TagKey<Block> COLLISION_BEARING_ECOLOGY = tag("cave_collision_bearing_ecology");
    private static final TagKey<Block> EXCLUSIVE_GEOMETRY = tag("cave_exclusive_geometry");
    private static final TagKey<Block> SHARED_VOID = tag("cave_shared_void");
    private static final TagKey<Block> REPLACEABLE_SOLID = tag("cave_replaceable_solid");
    private static final TagKey<Block> PRESERVE = tag("cave_preserve");

    private record Target(int x, int y, int z, boolean protectedRoute, boolean boundary) {}

    private static List<Target> targets = List.of();
    private static Set<ChunkPos> chunks = Set.of();
    private static Path receiptPath;
    private static Path registerPath;
    private static boolean initialized;
    private static boolean complete;
    private static int ticks;

    private CompatibilityAuditState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String targetValue = System.getProperty("geoworld.compat211.target", "").trim();
        String targetSha = System.getProperty("geoworld.compat211.targetSha256", "").trim();
        String receiptValue = System.getProperty("geoworld.compat211.receipt", "").trim();
        String registerValue = System.getProperty("geoworld.compat211.register", "").trim();
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
            throw new IllegalStateException("Run 211 input read failed", exception);
        }
        initialized = true;
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "Run 211 is not initialized");
        require(FabricLoader.getInstance().isModLoaded(EXTERNAL_CONTRIBUTOR), "external contributor is not loaded");
        for (ChunkPos chunk : chunks) {
            server.getOverworld().getChunkManager().setChunkForced(chunk, true);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        synchronized (CompatibilityAuditState.class) {
            if (complete) return;
            ticks++;
            if (ticks < AUDIT_TICK) return;
            complete = true;
        }
        try {
            audit(server);
        } catch (RuntimeException exception) {
            KarstogenesisFabricCompatibilityContract.LOGGER.error("Run 211 audit failed", exception);
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
        register.append("x\ty\tz\tprotected_route\tregistered_boundary\tblock_id\tair\tfluid\tempty_collision\tclaim_count\tclaims\tstatus\tresolution\texplicit_claim\tcollision_free\tfull_state\n");
        int explicitClaimCount = 0;
        int ambiguousClaimCount = 0;
        int invalidDeclarationCount = 0;
        int rejectedCount = 0;
        int protectedCollisionFreeCount = 0;
        int boundaryCollisionFreeCount = 0;
        for (int index = 0; index < targets.size(); index++) {
            Target target = targets.get(index);
            BlockPos position = new BlockPos(target.x(), target.y(), target.z());
            BlockState state = world.getBlockState(position);
            boolean air = state.isAir();
            boolean fluid = !state.getFluidState().isEmpty();
            boolean emptyCollision = state.getCollisionShape(world, position).isEmpty();
            Set<KarstCompatibilityContract.ClaimTag> claims = claims(state);
            KarstCompatibilityContract.Decision decision = KarstCompatibilityContract.resolve(
                    new KarstCompatibilityContract.Input(
                            index,
                            air,
                            fluid,
                            emptyCollision,
                            target.protectedRoute(),
                            target.boundary(),
                            claims));
            if (decision.explicitClaim()) explicitClaimCount++;
            if (decision.resolution() == KarstCompatibilityContract.Resolution.REJECT_AMBIGUOUS_CLAIM) ambiguousClaimCount++;
            if (decision.resolution() == KarstCompatibilityContract.Resolution.REJECT_INVALID_DECLARATION) invalidDeclarationCount++;
            if (decision.status() == KarstCompatibilityContract.Status.REJECTED) rejectedCount++;
            if (target.protectedRoute() && decision.collisionFree()) protectedCollisionFreeCount++;
            if (target.boundary() && decision.collisionFree()) boundaryCollisionFreeCount++;
            register.append(target.x()).append('\t')
                    .append(target.y()).append('\t')
                    .append(target.z()).append('\t')
                    .append(target.protectedRoute()).append('\t')
                    .append(target.boundary()).append('\t')
                    .append(Registries.BLOCK.getId(state.getBlock())).append('\t')
                    .append(air).append('\t')
                    .append(fluid).append('\t')
                    .append(emptyCollision).append('\t')
                    .append(claims.size()).append('\t')
                    .append(claimText(claims)).append('\t')
                    .append(decision.status()).append('\t')
                    .append(decision.resolution()).append('\t')
                    .append(decision.explicitClaim()).append('\t')
                    .append(decision.collisionFree()).append('\t')
                    .append(state).append('\n');
        }
        writeAtomic(registerPath, register.toString());
        String receipt = String.format(
                Locale.ROOT,
                "{\"schema_version\":\"geoworld.karstogenesis-211-live-compatibility-audit-arm.v1\",\"state\":\"DONE\",\"target_count\":%d,\"protected_count\":%d,\"boundary_count\":%d,\"chunk_count\":%d,\"external_contributor_loaded_count\":1,\"explicit_claim_count\":%d,\"ambiguous_claim_count\":%d,\"invalid_declaration_count\":%d,\"rejected_count\":%d,\"protected_collision_free_count\":%d,\"boundary_collision_free_count\":%d,\"register_sha256\":\"%s\"}\n",
                targets.size(),
                EXPECTED_PROTECTED,
                EXPECTED_BOUNDARY,
                chunks.size(),
                explicitClaimCount,
                ambiguousClaimCount,
                invalidDeclarationCount,
                rejectedCount,
                protectedCollisionFreeCount,
                boundaryCollisionFreeCount,
                sha256(registerPath));
        writeAtomic(receiptPath, receipt);
    }

    private static Set<KarstCompatibilityContract.ClaimTag> claims(BlockState state) {
        EnumSet<KarstCompatibilityContract.ClaimTag> result = EnumSet.noneOf(KarstCompatibilityContract.ClaimTag.class);
        if (state.isIn(PASS_THROUGH_DECORATION)) result.add(KarstCompatibilityContract.ClaimTag.PASS_THROUGH_DECORATION);
        if (state.isIn(COLLISION_BEARING_ECOLOGY)) result.add(KarstCompatibilityContract.ClaimTag.COLLISION_BEARING_ECOLOGY);
        if (state.isIn(EXCLUSIVE_GEOMETRY)) result.add(KarstCompatibilityContract.ClaimTag.EXCLUSIVE_GEOMETRY);
        if (state.isIn(SHARED_VOID)) result.add(KarstCompatibilityContract.ClaimTag.SHARED_VOID);
        if (state.isIn(REPLACEABLE_SOLID)) result.add(KarstCompatibilityContract.ClaimTag.REPLACEABLE_SOLID);
        if (state.isIn(PRESERVE)) result.add(KarstCompatibilityContract.ClaimTag.PRESERVE);
        return result;
    }

    private static String claimText(Set<KarstCompatibilityContract.ClaimTag> claims) {
        if (claims.isEmpty()) return "NONE";
        return claims.stream().sorted().map(Enum::name).collect(java.util.stream.Collectors.joining("|"));
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.of(RegistryKeys.BLOCK, Identifier.of("geoworld", path));
    }

    private static void writeFailure(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ":" + String.valueOf(exception.getMessage());
        writeAtomic(
                receiptPath,
                "{\"schema_version\":\"geoworld.karstogenesis-211-live-compatibility-audit-arm.v1\",\"state\":\"FAIL\",\"error\":\""
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
            throw new IllegalStateException("Run 211 output write failed", exception);
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Run 211 digest failed", exception);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
