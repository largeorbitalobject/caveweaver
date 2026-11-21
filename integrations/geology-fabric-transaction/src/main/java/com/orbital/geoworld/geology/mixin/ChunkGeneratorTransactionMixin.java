package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.TransactionState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Audits the selected chunk after underground features complete. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorTransactionMixin {
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void geoworld$auditFeaturesTail(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor,
            CallbackInfo callback) {
        TransactionState.observeFeaturesTail(chunk);
    }
}
