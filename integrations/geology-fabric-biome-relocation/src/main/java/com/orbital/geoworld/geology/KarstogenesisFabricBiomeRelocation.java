package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 213 biome-aware relocation probe. */
public final class KarstogenesisFabricBiomeRelocation implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-fabric-biome-relocation");

    @Override
    public void onInitialize() {
        BiomeRelocationState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(BiomeRelocationState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(BiomeRelocationState::onServerTick);
    }
}
