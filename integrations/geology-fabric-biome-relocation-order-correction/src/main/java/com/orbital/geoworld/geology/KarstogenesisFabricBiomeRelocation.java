package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 214 biome query-order correction. */
public final class KarstogenesisFabricBiomeRelocation implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-biome-relocation-order-correction");

    @Override
    public void onInitialize() {
        BiomeRelocationState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(BiomeRelocationState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(BiomeRelocationState::onServerTick);
    }
}
