package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Production-shaped entry point for immutable Karstogenesis packages. */
public final class KarstogenesisFabricRuntime implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("geoworld-karst-runtime");

    @Override
    public void onInitialize() {
        KarstogenesisRuntimeState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(
                KarstogenesisRuntimeState::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(
                KarstogenesisRuntimeState::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(
                KarstogenesisRuntimeState::onServerTick);
    }
}
