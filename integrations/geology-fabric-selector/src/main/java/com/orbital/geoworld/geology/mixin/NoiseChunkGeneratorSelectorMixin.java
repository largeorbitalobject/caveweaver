package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.PlacementSelectorState;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes noise and carve phases without mutating the world. */
@Mixin(NoiseChunkGenerator.class)
abstract class NoiseChunkGeneratorSelectorMixin {
    @Inject(method = "carve", at = @At("HEAD"))
    private void geoworld$observeCarveHead(
            ChunkRegion region,
            long seed,
            NoiseConfig noiseConfig,
            BiomeAccess biomeAccess,
            StructureAccessor structureAccessor,
            Chunk chunk,
            GenerationStep.Carver carverStep,
            CallbackInfo callback) {
        PlacementSelectorState.observeCarveHead(chunk);
    }

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
        PlacementSelectorState.observeCarveTail(region, chunk, structureAccessor);
    }

    @Inject(method = "populateNoise", at = @At("RETURN"))
    private void geoworld$observePopulateNoiseReturn(
            Blender blender,
            NoiseConfig noiseConfig,
            StructureAccessor structureAccessor,
            Chunk chunk,
            CallbackInfoReturnable<CompletableFuture<Chunk>> callback) {
        PlacementSelectorState.observePopulateNoiseReturn(chunk);
    }
}
