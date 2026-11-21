package com.orbital.geoworld.geology.fixture330;

import net.fabricmc.api.ModInitializer;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ColoredFallingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ColorCode;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.chunk.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides one genuine FallingBlock whose automatic scheduling hooks are controlled. */
public final class LifecycleFixture330 implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-lifecycle-fixture-330");
    private static final String NAMESPACE = "karstogenesis_fixture_330";

    public static final Block FALLING_BLOCK = Registry.register(
            Registries.BLOCK,
            Identifier.of(NAMESPACE, "controlled_falling_block"),
            new ControlledFallingBlock(
                    new ColorCode(0xB88A5AFF),
                    AbstractBlock.Settings.create()
                            .mapColor(MapColor.TERRACOTTA_BROWN)
                            .strength(0.5F)));

    private static boolean injected;

    @Override
    public void onInitialize() {
        LOGGER.info("Run 330 lifecycle fixture registered falling={}", Registries.BLOCK.getId(FALLING_BLOCK));
    }

    public static synchronized void inject(MinecraftServer server) {
        if (injected) return;
        injected = true;
        String mode = System.getProperty("geoworld.fixture329.mode", "NONE").trim();
        if (mode.equals("NONE")) {
            LOGGER.info("Run 330 lifecycle fixture mode=NONE");
            return;
        }
        if (!mode.equals("FALLING_UNSCHEDULED") && !mode.equals("FALLING_SCHEDULED")) {
            throw new IllegalStateException("Run 330 lifecycle fixture mode differs: " + mode);
        }
        int x = requiredCoordinate("geoworld.fixture329.sourceX");
        int y = requiredCoordinate("geoworld.fixture329.sourceY");
        int z = requiredCoordinate("geoworld.fixture329.sourceZ");
        BlockPos source = new BlockPos(x, y, z);
        Chunk chunk = server.getOverworld().getChunk(x >> 4, z >> 4);
        String prior = chunk.getBlockState(source).toString();
        chunk.setBlockState(source, FALLING_BLOCK.getDefaultState(), false);
        boolean queuedBeforeExplicitSchedule = server.getOverworld()
                .getBlockTickScheduler()
                .isQueued(source, FALLING_BLOCK);
        if (queuedBeforeExplicitSchedule) {
            throw new IllegalStateException("controlled falling block auto-scheduled during insertion");
        }
        if (mode.equals("FALLING_SCHEDULED")) {
            server.getOverworld().scheduleBlockTick(source, FALLING_BLOCK, 20);
        }
        boolean queuedAfterExplicitSchedule = server.getOverworld()
                .getBlockTickScheduler()
                .isQueued(source, FALLING_BLOCK);
        boolean expectedQueued = mode.equals("FALLING_SCHEDULED");
        if (queuedAfterExplicitSchedule != expectedQueued) {
            throw new IllegalStateException("controlled falling block queue state differs");
        }
        LOGGER.info(
                "Run 330 lifecycle fixture injected mode={} source={} prior={} observed={} queued-before-explicit={} queued-after-explicit={}",
                mode,
                source.toShortString(),
                prior,
                chunk.getBlockState(source),
                queuedBeforeExplicitSchedule,
                queuedAfterExplicitSchedule);
    }

    private static int requiredCoordinate(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) throw new IllegalStateException(property + " is empty");
        return Integer.parseInt(value);
    }

    private static final class ControlledFallingBlock extends ColoredFallingBlock {
        private ControlledFallingBlock(ColorCode color, AbstractBlock.Settings settings) {
            super(color, settings);
        }

        @Override
        protected void onBlockAdded(
                BlockState state, World world, BlockPos position, BlockState oldState, boolean notify) {}

        @Override
        protected BlockState getStateForNeighborUpdate(
                BlockState state,
                Direction direction,
                BlockState neighborState,
                WorldAccess world,
                BlockPos position,
                BlockPos neighborPosition) {
            return state;
        }
    }
}
