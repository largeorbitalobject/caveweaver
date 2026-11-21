package com.orbital.geoworld.geology.fixture;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/** Minimal genuine FlowableFluid used only by the Run 329 compatibility fixture. */
abstract class TestFluid extends FlowableFluid {
    @Override
    public Fluid getFlowing() {
        return LifecycleFixture.FLOWING_FLUID;
    }

    @Override
    public Fluid getStill() {
        return LifecycleFixture.STILL_FLUID;
    }

    @Override
    public Item getBucketItem() {
        return Items.BUCKET;
    }

    @Override
    protected boolean isInfinite(World world) {
        return false;
    }

    @Override
    protected void beforeBreakingBlock(WorldAccess world, BlockPos position, BlockState state) {}

    @Override
    protected int getMaxFlowDistance(WorldView world) {
        return 1;
    }

    @Override
    protected int getLevelDecreasePerBlock(WorldView world) {
        return 1;
    }

    @Override
    public int getTickRate(WorldView world) {
        return 1000;
    }

    @Override
    protected boolean canBeReplacedWith(
            FluidState state,
            BlockView world,
            BlockPos position,
            Fluid fluid,
            Direction direction) {
        return false;
    }

    @Override
    protected float getBlastResistance() {
        return 100.0F;
    }

    @Override
    protected BlockState toBlockState(FluidState state) {
        return LifecycleFixture.FLUID_BLOCK.getDefaultState()
                .with(FluidBlock.LEVEL, getBlockStateLevel(state));
    }

    @Override
    public boolean matchesType(Fluid fluid) {
        return fluid == LifecycleFixture.STILL_FLUID
                || fluid == LifecycleFixture.FLOWING_FLUID;
    }

    static final class Flowing extends TestFluid {
        Flowing() {
            setDefaultState(getStateManager().getDefaultState().with(LEVEL, 7));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Fluid, FluidState> builder) {
            super.appendProperties(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getLevel(FluidState state) {
            return state.get(LEVEL);
        }

        @Override
        public boolean isStill(FluidState state) {
            return false;
        }
    }

    static final class Still extends TestFluid {
        @Override
        public int getLevel(FluidState state) {
            return 8;
        }

        @Override
        public boolean isStill(FluidState state) {
            return true;
        }
    }
}
