package com.orbital.geoworld.geology.fixture;

import net.fabricmc.api.ModInitializer;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ColoredFallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.MapColor;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ColorCode;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers and injects fixed external lifecycle hazards for Run 329. */
public final class LifecycleFixture implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-lifecycle-fixture");
    private static final String NAMESPACE = "karstogenesis_fixture";

    public static final FlowableFluid STILL_FLUID = Registry.register(
            Registries.FLUID,
            Identifier.of(NAMESPACE, "test_fluid"),
            new TestFluid.Still());
    public static final FlowableFluid FLOWING_FLUID = Registry.register(
            Registries.FLUID,
            Identifier.of(NAMESPACE, "flowing_test_fluid"),
            new TestFluid.Flowing());
    public static final Block FLUID_BLOCK = Registry.register(
            Registries.BLOCK,
            Identifier.of(NAMESPACE, "test_fluid_block"),
            new PublicFluidBlock(
                    STILL_FLUID,
                    AbstractBlock.Settings.create()
                            .mapColor(MapColor.WATER_BLUE)
                            .replaceable()
                            .noCollision()
                            .strength(100.0F)
                            .dropsNothing()
                            .liquid()));
    public static final Block FALLING_BLOCK = Registry.register(
            Registries.BLOCK,
            Identifier.of(NAMESPACE, "test_falling_block"),
            new ColoredFallingBlock(
                    new ColorCode(0xB88A5AFF),
                    AbstractBlock.Settings.create()
                            .mapColor(MapColor.TERRACOTTA_BROWN)
                            .strength(0.5F)));

    private static boolean injected;

    @Override
    public void onInitialize() {
        LOGGER.info(
                "Run 329 lifecycle fixture registered fluid={} flowing={} falling={}",
                Registries.FLUID.getId(STILL_FLUID),
                Registries.FLUID.getId(FLOWING_FLUID),
                Registries.BLOCK.getId(FALLING_BLOCK));
    }

    public static synchronized void inject(MinecraftServer server) {
        if (injected) return;
        injected = true;
        String mode = System.getProperty("geoworld.fixture329.mode", "NONE").trim();
        if (mode.equals("NONE")) {
            LOGGER.info("Run 329 lifecycle fixture mode=NONE");
            return;
        }
        if (!mode.equals("CUSTOM_FLUID")
                && !mode.equals("FALLING_UNSCHEDULED")
                && !mode.equals("FALLING_SCHEDULED")) {
            throw new IllegalStateException("Run 329 lifecycle fixture mode differs: " + mode);
        }
        int x = requiredCoordinate("geoworld.fixture329.sourceX");
        int y = requiredCoordinate("geoworld.fixture329.sourceY");
        int z = requiredCoordinate("geoworld.fixture329.sourceZ");
        BlockPos source = new BlockPos(x, y, z);
        Chunk chunk = server.getOverworld().getChunk(x >> 4, z >> 4);
        String prior = chunk.getBlockState(source).toString();
        if (mode.equals("CUSTOM_FLUID")) {
            chunk.setBlockState(source, FLUID_BLOCK.getDefaultState(), false);
        } else {
            chunk.setBlockState(source, FALLING_BLOCK.getDefaultState(), false);
            if (mode.equals("FALLING_SCHEDULED")) {
                server.getOverworld().scheduleBlockTick(source, FALLING_BLOCK, 20);
            }
        }
        LOGGER.info(
                "Run 329 lifecycle fixture injected mode={} source={} prior={} observed={} falling-queued={}",
                mode,
                source.toShortString(),
                prior,
                chunk.getBlockState(source),
                server.getOverworld().getBlockTickScheduler().isQueued(source, FALLING_BLOCK));
    }

    private static int requiredCoordinate(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) throw new IllegalStateException(property + " is empty");
        return Integer.parseInt(value);
    }

    private static final class PublicFluidBlock extends FluidBlock {
        private PublicFluidBlock(FlowableFluid fluid, AbstractBlock.Settings settings) {
            super(fluid, settings);
        }
    }
}
