package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 219 provider write. */
public final class KarstogenesisFabricProviderWrite implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-provider-write");

    @Override
    public void onInitialize() {
        ProviderWriteState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(
                ProviderWriteState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(
                ProviderWriteState::onServerTick);
    }
}
