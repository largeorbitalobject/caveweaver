package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 204 timing intervention. */
public final class KarstogenesisFabricCausalConfirmation implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-fabric-causal-confirmation");

    @Override
    public void onInitialize() {
        CausalConfirmationState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(CausalConfirmationState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(CausalConfirmationState::onServerTick);
    }
}
