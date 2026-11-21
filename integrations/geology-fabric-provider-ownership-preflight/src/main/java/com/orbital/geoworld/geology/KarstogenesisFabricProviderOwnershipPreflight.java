package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 216 provider ownership preflight. */
public final class KarstogenesisFabricProviderOwnershipPreflight implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-provider-ownership-preflight");

    @Override
    public void onInitialize() {
        ProviderOwnershipPreflightState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(ProviderOwnershipPreflightState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(ProviderOwnershipPreflightState::onServerTick);
    }
}
