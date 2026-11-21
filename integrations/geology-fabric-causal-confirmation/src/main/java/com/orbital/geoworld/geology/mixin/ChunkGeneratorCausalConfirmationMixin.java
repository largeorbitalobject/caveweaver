package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.CausalConfirmationState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the delayed intervention and audits the selected chunk at feature tail. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorCausalConfirmationMixin {
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void geoworld$observeFeaturesTail(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        CausalConfirmationState.observeFeaturesTail(chunk);
    }
}
