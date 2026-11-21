package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observation-only Fabric entry point for Karstogenesis run 201. */
public final class KarstogenesisFabricSelector implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-fabric-selector");

    @Override
    public void onInitialize() {
        PlacementSelectorState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(PlacementSelectorState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(PlacementSelectorState::onServerTick);
    }
}
