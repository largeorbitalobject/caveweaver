package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 205 multichunk transaction. */
public final class KarstogenesisFabricMultichunkTransaction implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-multichunk-transaction");

    @Override
    public void onInitialize() {
        MultichunkTransactionState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(
                MultichunkTransactionState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(
                MultichunkTransactionState::onServerTick);
    }
}
