package com.orbital.geoworld.geology.mixin;

import com.orbital.geoworld.geology.GeologyTileCache;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2791;
import net.minecraft.class_2794;
import net.minecraft.class_5138;
import net.minecraft.class_5281;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies immutable learned air after the completed vanilla feature stage. */
@Mixin(value = class_2794.class, remap = false)
public final class ChunkGeneratorFeaturesMixin {
    @Inject(method = "method_12102", at = @At("TAIL"), remap = false)
    private void geoworld$applyAfterFeatures(
            class_5281 world,
            class_2791 chunk,
            class_5138 structureAccessor,
            CallbackInfo callback) {
        if (!GeologyTileCache.postNoiseEnabled()) {
            return;
        }
        int startX = chunk.method_12004().field_9181 << 4;
        int startZ = chunk.method_12004().field_9180 << 4;
        int bottomY = chunk.method_31607();
        int topY = bottomY + chunk.method_31605();
        class_2338.class_2339 position = new class_2338.class_2339();
        for (int worldY = bottomY; worldY < topY; worldY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = startZ + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    int worldX = startX + localX;
                    if (GeologyTileCache.shouldCarve(worldX, worldY, worldZ)) {
                        position.method_10103(worldX, worldY, worldZ);
                        chunk.method_12010(
                                position, class_2246.field_10124.method_9564(), false);
                    }
                }
            }
        }
    }
}
