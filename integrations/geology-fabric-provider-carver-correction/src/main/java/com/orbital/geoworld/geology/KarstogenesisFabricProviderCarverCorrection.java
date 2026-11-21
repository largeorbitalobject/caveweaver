package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 217 carver-replaceability correction. */
public final class KarstogenesisFabricProviderCarverCorrection implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-provider-carver-correction");

    @Override
    public void onInitialize() {
        ProviderCarverCorrectionState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(ProviderCarverCorrectionState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(ProviderCarverCorrectionState::onServerTick);
    }
}
