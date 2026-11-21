package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observation-only Fabric entry point for Karstogenesis run 200. */
public final class KarstogenesisFabricProbe implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-fabric-probe");

    @Override
    public void onInitialize() {
        ProbeState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(ProbeState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(ProbeState::onServerTick);
    }
}
