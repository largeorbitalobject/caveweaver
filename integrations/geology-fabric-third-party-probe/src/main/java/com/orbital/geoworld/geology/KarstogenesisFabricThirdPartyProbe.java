package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 212 third-party provider probe. */
public final class KarstogenesisFabricThirdPartyProbe implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-fabric-third-party-probe");

    @Override
    public void onInitialize() {
        ThirdPartyProbeState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(ThirdPartyProbeState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(ThirdPartyProbeState::onServerTick);
    }
}
