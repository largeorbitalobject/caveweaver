package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.RuntimeBenchmarkState;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Starts the paired timing window at the first relevant carver callback. */
@Mixin(NoiseChunkGenerator.class)
abstract class NoiseChunkGeneratorRuntimeBenchmarkMixin {
    @Inject(method = "carve", at = @At("HEAD"))
    private void geoworld$beginRuntimeBenchmark(
            ChunkRegion region,
            long seed,
            NoiseConfig noiseConfig,
            BiomeAccess biomeAccess,
            StructureAccessor structureAccessor,
            Chunk chunk,
            GenerationStep.Carver carverStep,
            CallbackInfo callback) {
        RuntimeBenchmarkState.beginCarve(region, chunk);
    }
}
