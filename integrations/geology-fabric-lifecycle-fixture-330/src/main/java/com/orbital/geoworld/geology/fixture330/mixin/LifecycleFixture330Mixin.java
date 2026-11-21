package com.orbital.geoworld.geology.fixture330.mixin;

import com.orbital.geoworld.geology.ProtectedClosureState;
import com.orbital.geoworld.geology.fixture330.LifecycleFixture330;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the controlled external fixture immediately before the production audit. */
@Mixin(value = ProtectedClosureState.class, remap = false)
public abstract class LifecycleFixture330Mixin {
    @Inject(method = "auditLiveFluidInterface", at = @At("HEAD"), remap = false)
    private static void geoworld$injectLifecycleFixture(
            MinecraftServer server, CallbackInfo callbackInfo) {
        LifecycleFixture330.inject(server);
    }
}
