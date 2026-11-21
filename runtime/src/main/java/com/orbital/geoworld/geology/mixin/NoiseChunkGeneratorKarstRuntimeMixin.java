package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.KarstogenesisRuntimeState;
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

/** Applies an admitted chunk-local transaction after vanilla carvers. */
@Mixin(NoiseChunkGenerator.class)
abstract class NoiseChunkGeneratorKarstRuntimeMixin {
    @Inject(method = "carve", at = @At("TAIL"))
    private void geoworld$applyKarstChunk(
            ChunkRegion region,
            long seed,
            NoiseConfig noiseConfig,
            BiomeAccess biomeAccess,
            StructureAccessor structureAccessor,
            Chunk chunk,
            GenerationStep.Carver carverStep,
            CallbackInfo callback) {
        KarstogenesisRuntimeState.applyCarveTail(
                region, seed, chunk, structureAccessor);
    }
}
