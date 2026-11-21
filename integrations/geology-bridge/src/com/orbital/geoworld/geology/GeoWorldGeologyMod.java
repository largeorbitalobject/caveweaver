package com.orbital.geoworld.geology;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.class_1923;
import net.minecraft.class_2168;
import net.minecraft.class_2170;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2346;
import net.minecraft.class_2791;
import net.minecraft.class_2806;
import net.minecraft.class_2818;
import net.minecraft.class_3218;

/** Explicit second-phase command for applying geology only to an already-finalized region. */
public final class GeoWorldGeologyMod implements ModInitializer {
    private static final String APPLY_COMMAND = "geoworld_geology_apply";
    private static final String AUDIT_COMMAND = "geoworld_geology_audit";
    private static final String STABILIZE_COMMAND = "geoworld_geology_stabilize";
    private static final int BOTTOM_PROTECTION_BLOCKS = 12;
    private static Set<Long> baselineFallingBlocks;
    private static Set<Long> gravitySupportBlocks;
    private static Set<Long> fluidBoundaryBlocks;

    private enum Operation { APPLY, AUDIT, STABILIZE }

    @Override
    public void onInitialize() {
        // This executes during Fabric initialization, before any server world is opened.
        GeologyTileCache.requireReleaseAdmission();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(class_2170.method_9247(APPLY_COMMAND)
                        .requires(source -> source.method_9259(4))
                        .executes(context -> inspect(context.getSource(), Operation.APPLY))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(class_2170.method_9247(AUDIT_COMMAND)
                        .requires(source -> source.method_9259(4))
                        .executes(context -> inspect(context.getSource(), Operation.AUDIT))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(class_2170.method_9247(STABILIZE_COMMAND)
                        .requires(source -> source.method_9259(4))
                        .executes(context -> inspect(context.getSource(), Operation.STABILIZE))));
    }

    private static int inspect(class_2168 source, Operation operation) {
        if (!GeologyTileCache.finalizedRegionEnabled()) {
            System.out.println("[GeoWorld-Geology] finalized apply refused: mode is not finalized_region_air");
            return 0;
        }
        if (!GeologyTileCache.preflightFinalizedTiles()) {
            System.out.println("[GeoWorld-Geology] finalized apply refused: cache preflight failed");
            return 0;
        }
        class_3218 world = source.method_9225();
        Map<Long, class_2818> chunks = preflight(world);
        if (chunks == null) return 0;
        if (operation == Operation.APPLY && baselineFallingBlocks == null) {
            baselineFallingBlocks = snapshotFallingBlocks(world);
            gravitySupportBlocks = new HashSet<>();
            fluidBoundaryBlocks = new HashSet<>();
            System.out.println("[GeoWorld-Geology] finalized gravity snapshot blocks="
                    + baselineFallingBlocks.size());
        } else if (operation != Operation.APPLY && baselineFallingBlocks == null) {
            System.out.println("[GeoWorld-Geology] finalized " + operation.name().toLowerCase()
                    + " refused: gravity snapshot is absent");
            return 0;
        }

        class_2338.class_2339 position = new class_2338.class_2339();
        class_2338.class_2339 abovePosition = new class_2338.class_2339();
        LongAdder modified = new LongAdder();
        LongAdder alreadyAir = new LongAdder();
        LongAdder existingFluid = new LongAdder();
        LongAdder existingNonfluid = new LongAdder();
        LongAdder gravitySupportsPreserved = new LongAdder();
        LongAdder fluidBoundariesPreserved = new LongAdder();
        LongAdder bottomBlocksPreserved = new LongAdder();
        int bottomY = world.method_31607();
        int topY = bottomY + world.method_31605();
        long candidates = GeologyTileCache.visitFinalizedCarveBlocks((worldX, worldY, worldZ) -> {
            if (worldY < bottomY || worldY >= topY) return false;
            if (worldY < bottomY + BOTTOM_PROTECTION_BLOCKS) {
                bottomBlocksPreserved.increment();
                return false;
            }
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            class_2818 chunk = chunks.get(class_1923.method_8331(chunkX, chunkZ));
            if (chunk == null) {
                throw new IllegalStateException("carve position escaped preflighted chunks");
            }
            position.method_10103(worldX, worldY, worldZ);
            if (operation != Operation.APPLY
                    && gravitySupportBlocks != null
                    && gravitySupportBlocks.contains(position.method_10063())) {
                gravitySupportsPreserved.increment();
                return false;
            }
            if (operation != Operation.APPLY
                    && fluidBoundaryBlocks != null
                    && fluidBoundaryBlocks.contains(position.method_10063())) {
                fluidBoundariesPreserved.increment();
                return false;
            }
            var previous = chunk.method_8320(position);
            if (class_2246.field_10124.method_9564().equals(previous)) {
                alreadyAir.increment();
                return false;
            }
            if (!previous.method_26227().method_15769()) {
                existingFluid.increment();
                return false;
            }
            if (operation == Operation.APPLY && previous.method_26204() instanceof class_2346) {
                gravitySupportBlocks.add(position.method_10063());
                gravitySupportsPreserved.increment();
                return false;
            }
            if (operation == Operation.APPLY && hasFluidNeighbor(world, worldX, worldY, worldZ)) {
                fluidBoundaryBlocks.add(position.method_10063());
                fluidBoundariesPreserved.increment();
                return false;
            }
            if (operation != Operation.APPLY && existingNonfluid.sum() < 16) {
                System.out.println("[GeoWorld-Geology] finalized residual nonfluid position="
                        + worldX + "," + worldY + "," + worldZ + " state=" + previous);
            }
            existingNonfluid.increment();
            if (operation == Operation.APPLY && worldY + 1 < topY) {
                abovePosition.method_10103(worldX, worldY + 1, worldZ);
                if (world.method_8320(abovePosition).method_26204() instanceof class_2346) {
                    gravitySupportBlocks.add(position.method_10063());
                    gravitySupportsPreserved.increment();
                    return false;
                }
            }
            if (operation == Operation.AUDIT) return false;
            boolean changed = world.method_8652(
                    position, class_2246.field_10124.method_9564(), 2);
            if (changed) modified.increment();
            return changed;
        });
        long changed = modified.sum();
        long displacedGravity = 0;
        if (operation != Operation.APPLY) {
            displacedGravity = inspectDisplacedGravity(world, operation == Operation.STABILIZE);
            if (operation == Operation.STABILIZE) changed += displacedGravity;
        }
        String operationName = operation.name().toLowerCase();
        System.out.println("[GeoWorld-Geology] finalized " + operationName + " complete candidates="
                + candidates + " modified=" + changed + " air=" + alreadyAir.sum()
                + " fluid=" + existingFluid.sum() + " nonfluid=" + existingNonfluid.sum()
                + " displaced_gravity=" + displacedGravity
                + " gravity_supports_preserved=" + gravitySupportsPreserved.sum()
                + " fluid_boundaries_preserved=" + fluidBoundariesPreserved.sum()
                + " bottom_blocks_preserved=" + bottomBlocksPreserved.sum()
                + " chunks=" + chunks.size());
        long result = operation == Operation.AUDIT
                ? existingNonfluid.sum() + displacedGravity : changed;
        return (int) Math.min(Integer.MAX_VALUE, result);
    }

    private static boolean hasFluidNeighbor(
            class_3218 world, int worldX, int worldY, int worldZ) {
        class_2338.class_2339 neighbor = new class_2338.class_2339();
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0 && offsetZ == 0) continue;
                    neighbor.method_10103(
                            worldX + offsetX, worldY + offsetY, worldZ + offsetZ);
                    if (!world.method_8320(neighbor).method_26227().method_15769()) return true;
                }
            }
        }
        return false;
    }

    private static Set<Long> snapshotFallingBlocks(class_3218 world) {
        Set<Long> snapshot = new HashSet<>();
        visitTargetBlocks(world, (position, state) -> {
            if (state.method_26204() instanceof class_2346) snapshot.add(position.method_10063());
        });
        return snapshot;
    }

    private static long inspectDisplacedGravity(class_3218 world, boolean remove) {
        LongAdder displaced = new LongAdder();
        visitTargetBlocks(world, (position, state) -> {
            if (state.method_26204() instanceof class_2346
                    && !baselineFallingBlocks.contains(position.method_10063())) {
                displaced.increment();
                if (remove) {
                    world.method_8652(position, class_2246.field_10124.method_9564(), 2);
                }
            }
        });
        return displaced.sum();
    }

    private static void visitTargetBlocks(class_3218 world, BlockVisitor visitor) {
        int tileBlocks = GeologyTileCache.tileBlocks();
        int bottomY = world.method_31607();
        int topY = bottomY + world.method_31605();
        class_2338.class_2339 position = new class_2338.class_2339();
        for (GeologyTileCache.TileCoordinate tile : GeologyTileCache.finalizedTiles()) {
            int minX = tile.x() * tileBlocks;
            int minZ = tile.z() * tileBlocks;
            for (int worldY = bottomY; worldY < topY; worldY++) {
                for (int worldZ = minZ; worldZ < minZ + tileBlocks; worldZ++) {
                    for (int worldX = minX; worldX < minX + tileBlocks; worldX++) {
                        position.method_10103(worldX, worldY, worldZ);
                        visitor.accept(position, world.method_8320(position));
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface BlockVisitor {
        void accept(class_2338 position, net.minecraft.class_2680 state);
    }

    /** Resolve every target and halo chunk at FULL without generating; mutate nothing on failure. */
    private static Map<Long, class_2818> preflight(class_3218 world) {
        int tileBlocks = GeologyTileCache.tileBlocks();
        int halo = GeologyTileCache.finalizedHaloChunks();
        Map<Long, class_2818> chunks = new HashMap<>();
        for (GeologyTileCache.TileCoordinate tile : GeologyTileCache.finalizedTiles()) {
            int minChunkX = Math.floorDiv(tile.x() * tileBlocks, 16) - halo;
            int minChunkZ = Math.floorDiv(tile.z() * tileBlocks, 16) - halo;
            int maxChunkX = Math.floorDiv((tile.x() + 1) * tileBlocks - 1, 16) + halo;
            int maxChunkZ = Math.floorDiv((tile.z() + 1) * tileBlocks - 1, 16) + halo;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    long key = class_1923.method_8331(chunkX, chunkZ);
                    if (chunks.containsKey(key)) continue;
                    class_2791 chunk = world.method_8402(chunkX, chunkZ, class_2806.field_12803, false);
                    if (!(chunk instanceof class_2818 fullChunk)) {
                        System.out.println("[GeoWorld-Geology] finalized apply waiting: missing FULL chunk "
                                + chunkX + "," + chunkZ);
                        return null;
                    }
                    chunks.put(key, fullChunk);
                }
            }
        }
        return chunks;
    }
}
