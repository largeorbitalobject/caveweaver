package com.orbital.geoworld.geology.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.orbital.geoworld.geology.GeologyTileCache;
import net.minecraft.class_6910;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds immutable cached cave SDF to Terrain Diffusion's final density. */
@Mixin(value = TerrainDiffusionDensityFunction.class, remap = false)
public final class TerrainDiffusionDensityMixin {
    private static final AtomicBoolean GEOWORLD_TRACE_WRITTEN = new AtomicBoolean();

    private static void geoworld$traceOnce(String hook) {
        if (Boolean.getBoolean("geoworld.geology.trace")
                && GEOWORLD_TRACE_WRITTEN.compareAndSet(false, true)) {
            new Throwable("GeoWorld geology trace from " + hook).printStackTrace(System.out);
        }
    }

    @Inject(method = "method_40464", at = @At("RETURN"), cancellable = true, remap = false)
    private void geoworld$sample(class_6910.class_6912 position, CallbackInfoReturnable<Double> callback) {
        geoworld$traceOnce("density sample");
        callback.setReturnValue(GeologyTileCache.apply(
                position.comp_371(), position.comp_372(), position.comp_373(), callback.getReturnValueD()));
    }

    @Inject(method = "method_40470", at = @At("RETURN"), remap = false)
    private void geoworld$fill(double[] densities, class_6910.class_6911 positions, CallbackInfo callback) {
        geoworld$traceOnce("density fill");
        for (int index = 0; index < densities.length; index++) {
            class_6910.class_6912 position = positions.method_40477(index);
            densities[index] = GeologyTileCache.apply(
                    position.comp_371(), position.comp_372(), position.comp_373(), densities[index]);
        }
    }
}
