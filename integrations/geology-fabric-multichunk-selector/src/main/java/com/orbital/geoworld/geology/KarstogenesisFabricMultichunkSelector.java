package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 206 selector. */
public final class KarstogenesisFabricMultichunkSelector implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-multichunk-selector");

    @Override
    public void onInitialize() {
        MultichunkSelectorState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(MultichunkSelectorState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(MultichunkSelectorState::onServerTick);
    }
}
