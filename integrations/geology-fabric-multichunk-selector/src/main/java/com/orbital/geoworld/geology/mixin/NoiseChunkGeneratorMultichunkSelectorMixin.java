package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.MultichunkSelectorState;
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

/** Observes each candidate chunk at carve tail without writing blocks. */
@Mixin(NoiseChunkGenerator.class)
abstract class NoiseChunkGeneratorMultichunkSelectorMixin {
    @Inject(method = "carve", at = @At("TAIL"))
    private void geoworld$observeCarveTail(
            ChunkRegion region,
            long seed,
            NoiseConfig noiseConfig,
            BiomeAccess biomeAccess,
            StructureAccessor structureAccessor,
            Chunk chunk,
            GenerationStep.Carver carverStep,
            CallbackInfo callback) {
        MultichunkSelectorState.observeCarveTail(region, chunk, structureAccessor);
    }
}
