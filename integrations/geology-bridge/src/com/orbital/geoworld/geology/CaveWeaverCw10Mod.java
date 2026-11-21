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
import net.minecraft.class_2680;
import net.minecraft.class_2791;
import net.minecraft.class_2806;
import net.minecraft.class_2818;
import net.minecraft.class_3218;

/** Explicit, fail-closed importer for the canonical CW10 air/water class volume. */
public final class CaveWeaverCw10Mod implements ModInitializer {
    private static final String APPLY = "caveweaver_cw10_apply";
    private static final String AUDIT = "caveweaver_cw10_audit";
    private static Set<Long> baselineFallingBlocks;
    private static Set<Long> gravitySupportBlocks;
    private static Set<Long> fluidBoundaryBlocks;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(class_2170.method_9247(APPLY)
                    .requires(source -> source.method_9259(4))
                    .executes(context -> inspect(context.getSource(), true)));
            dispatcher.register(class_2170.method_9247(AUDIT)
                    .requires(source -> source.method_9259(4))
                    .executes(context -> inspect(context.getSource(), false)));
        });
    }

    private static int inspect(class_2168 source, boolean apply) {
        if (!GeologyTileCache.cw10AbsoluteClassesEnabled()
                || !GeologyTileCache.preflightCw10ClassTiles()) {
            System.out.println("[CaveWeaver-CW10] refused: immutable class cache preflight failed");
            return 0;
        }
        class_3218 world = source.method_9225();
        Map<Long, class_2818> chunks = preflight(world);
        if (chunks == null) return 0;
        int bottom = world.method_31607();
        int top = bottom + world.method_31605();
        int originY = GeologyTileCache.cw10OriginWorldY();
        if (originY < bottom + 12 || originY + GeologyTileCache.cw10SizeY() > 84) {
            System.out.println("[CaveWeaver-CW10] refused: Y mapping violates world safety bounds");
            return 0;
        }
        if (apply && baselineFallingBlocks == null) {
            baselineFallingBlocks = snapshotFallingBlocks(world);
            gravitySupportBlocks = new HashSet<>();
            fluidBoundaryBlocks = new HashSet<>();
        } else if (!apply && baselineFallingBlocks == null) {
            System.out.println("[CaveWeaver-CW10] audit refused: apply snapshot is absent");
            return 0;
        }

        class_2338.class_2339 position = new class_2338.class_2339();
        class_2338.class_2339 above = new class_2338.class_2339();
        LongAdder airModified = new LongAdder();
        LongAdder waterModified = new LongAdder();
        LongAdder alreadyVoidOrFluid = new LongAdder();
        LongAdder gravityPreserved = new LongAdder();
        LongAdder fluidBoundaryPreserved = new LongAdder();
        LongAdder protectedRoutePriority = new LongAdder();
        LongAdder remainingMismatch = new LongAdder();
        long candidates = GeologyTileCache.visitCw10ClassBlocks((x, y, z, blockClass, protectedRoute) -> {
            if (y < bottom || y >= top) throw new IllegalStateException("CW10 block escaped world Y bounds");
            class_2818 chunk = chunks.get(class_1923.method_8331(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
            if (chunk == null) throw new IllegalStateException("CW10 block escaped preflighted chunks");
            position.method_10103(x, y, z);
            class_2680 previous = chunk.method_8320(position);
            if (previous.method_26215() || !previous.method_26227().method_15769()) {
                alreadyVoidOrFluid.increment();
                return false;
            }
            if (!protectedRoute && (gravitySupportBlocks.contains(position.method_10063())
                    || previous.method_26204() instanceof class_2346)) {
                gravityPreserved.increment();
                return false;
            }
            if (!protectedRoute && blockClass == 1
                    && fluidBoundaryBlocks.contains(position.method_10063())) {
                fluidBoundaryPreserved.increment();
                return false;
            }
            above.method_10103(x, y + 1, z);
            if (!protectedRoute && y + 1 < top
                    && world.method_8320(above).method_26204() instanceof class_2346) {
                gravitySupportBlocks.add(position.method_10063());
                gravityPreserved.increment();
                return false;
            }
            if (!protectedRoute && blockClass == 1 && hasFluidNeighbor(world, x, y, z)) {
                fluidBoundaryBlocks.add(position.method_10063());
                fluidBoundaryPreserved.increment();
                return false;
            }
            if (protectedRoute) protectedRoutePriority.increment();
            if (!apply) {
                remainingMismatch.increment();
                return false;
            }
            class_2680 desired = blockClass == 1
                    ? class_2246.field_10124.method_9564()
                    : class_2246.field_10382.method_9564();
            // Materialize the frozen field directly in the preflighted chunk.
            // World.setBlockState can synchronously trigger fluid/block side
            // effects even while scheduled ticks are frozen, which makes its
            // final region differ from the registered class payload.
            boolean changed = chunk.method_12010(position, desired, false) != null;
            if (changed) {
                chunk.method_12008(true);
                if (blockClass == 1) airModified.increment();
                else waterModified.increment();
            }
            return changed;
        });
        System.out.println("[CaveWeaver-CW10] " + (apply ? "apply" : "audit")
                + " complete candidates=" + candidates
                + " air_modified=" + airModified.sum()
                + " water_modified=" + waterModified.sum()
                + " already_void_or_fluid=" + alreadyVoidOrFluid.sum()
                + " gravity_preserved=" + gravityPreserved.sum()
                + " fluid_boundary_preserved=" + fluidBoundaryPreserved.sum()
                + " protected_route_priority=" + protectedRoutePriority.sum()
                + " remaining_mismatch=" + remainingMismatch.sum()
                + " chunks=" + chunks.size());
        long result = apply ? airModified.sum() + waterModified.sum() : remainingMismatch.sum();
        return (int) Math.min(Integer.MAX_VALUE, result);
    }

    private static boolean hasFluidNeighbor(class_3218 world, int x, int y, int z) {
        class_2338.class_2339 neighbor = new class_2338.class_2339();
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                neighbor.method_10103(x + dx, y + dy, z + dz);
                if (!world.method_8320(neighbor).method_26227().method_15769()) return true;
            }
        }
        return false;
    }

    private static Set<Long> snapshotFallingBlocks(class_3218 world) {
        Set<Long> snapshot = new HashSet<>();
        visitRegion(world, (position, state) -> {
            if (state.method_26204() instanceof class_2346) snapshot.add(position.method_10063());
        });
        return snapshot;
    }

    private static void visitRegion(class_3218 world, BlockVisitor visitor) {
        class_2338.class_2339 position = new class_2338.class_2339();
        int x0 = GeologyTileCache.cw10OriginWorldX();
        int z0 = GeologyTileCache.cw10OriginWorldZ();
        int y0 = GeologyTileCache.cw10OriginWorldY();
        for (int y = y0; y < y0 + GeologyTileCache.cw10SizeY(); y++) {
            for (int z = z0; z < z0 + 256; z++) for (int x = x0; x < x0 + 256; x++) {
                position.method_10103(x, y, z);
                visitor.accept(position, world.method_8320(position));
            }
        }
    }

    private static Map<Long, class_2818> preflight(class_3218 world) {
        int halo = GeologyTileCache.finalizedHaloChunks();
        int x0 = GeologyTileCache.cw10OriginWorldX();
        int z0 = GeologyTileCache.cw10OriginWorldZ();
        int minChunkX = Math.floorDiv(x0, 16) - halo;
        int minChunkZ = Math.floorDiv(z0, 16) - halo;
        int maxChunkX = Math.floorDiv(x0 + 255, 16) + halo;
        int maxChunkZ = Math.floorDiv(z0 + 255, 16) + halo;
        Map<Long, class_2818> chunks = new HashMap<>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                class_2791 chunk = world.method_8402(chunkX, chunkZ, class_2806.field_12803, false);
                if (!(chunk instanceof class_2818 full)) {
                    System.out.println("[CaveWeaver-CW10] refused: missing FULL chunk " + chunkX + "," + chunkZ);
                    return null;
                }
                chunks.put(class_1923.method_8331(chunkX, chunkZ), full);
            }
        }
        return chunks;
    }

    @FunctionalInterface
    private interface BlockVisitor {
        void accept(class_2338 position, class_2680 state);
    }
}
