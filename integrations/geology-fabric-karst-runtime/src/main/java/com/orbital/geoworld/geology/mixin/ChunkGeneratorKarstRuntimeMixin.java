package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.KarstogenesisRuntimeState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records completed host feature windows for eventual protected closure. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorKarstRuntimeMixin {
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void geoworld$observeKarstFeatures(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        KarstogenesisRuntimeState.observeFeaturesTail(world, chunk);
    }
}
