package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded Fabric entry point for Karstogenesis run 202. */
public final class KarstogenesisFabricTransaction implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("karstogenesis-fabric-transaction");

    @Override
    public void onInitialize() {
        TransactionState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(TransactionState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(TransactionState::onServerTick);
    }
}
