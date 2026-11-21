package com.orbital.geoworld.geology.fixture.mixin;

import com.orbital.geoworld.geology.fixture.LifecycleFixture;
import com.orbital.geoworld.geology.ProtectedClosureState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the external fixture immediately before the production registry-neutral audit. */
@Mixin(value = ProtectedClosureState.class, remap = false)
public abstract class LifecycleFixtureMixin {
    @Inject(method = "auditLiveFluidInterface", at = @At("HEAD"), remap = false)
    private static void geoworld$injectLifecycleFixture(
            MinecraftServer server, CallbackInfo callbackInfo) {
        LifecycleFixture.inject(server);
    }
}
