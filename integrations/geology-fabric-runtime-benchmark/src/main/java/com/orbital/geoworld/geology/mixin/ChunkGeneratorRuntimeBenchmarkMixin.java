package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.RuntimeBenchmarkState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Finishes the paired timing window at the last relevant feature callback. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorRuntimeBenchmarkMixin {
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void geoworld$observeRuntimeBenchmark(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        RuntimeBenchmarkState.observeFeatures(world, chunk);
    }
}
