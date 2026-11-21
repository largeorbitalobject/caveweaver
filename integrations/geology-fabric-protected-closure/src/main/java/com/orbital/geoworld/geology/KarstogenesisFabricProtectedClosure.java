package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 208 protected closure. */
public final class KarstogenesisFabricProtectedClosure implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-protected-closure");

    @Override
    public void onInitialize() {
        ProtectedClosureState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(
                ProtectedClosureState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(
                ProtectedClosureState::onServerTick);
    }
}
