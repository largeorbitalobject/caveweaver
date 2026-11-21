package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.ProtectedClosureState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Audits every target chunk after host feature generation. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorProtectedClosureMixin {
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void geoworld$observeFeaturesTail(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        ProtectedClosureState.observeFeaturesTail(chunk);
    }
}
