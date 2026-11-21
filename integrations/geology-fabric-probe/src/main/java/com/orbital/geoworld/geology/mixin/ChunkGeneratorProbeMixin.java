package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.ProbeState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes vanilla carve and feature phases without mutating the world. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorProbeMixin {
    @Inject(method = "generateFeatures", at = @At("HEAD"))
    private void geoworld$observeFeaturesHead(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        ProbeState.observeFeaturesHead(world, chunk, structureAccessor);
    }

    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void geoworld$observeFeaturesTail(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        ProbeState.observeFeaturesTail(chunk);
    }
}
