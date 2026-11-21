package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 207 multichunk causal transaction. */
public final class KarstogenesisFabricMultichunkCausalTransaction implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-multichunk-causal-transaction");

    @Override
    public void onInitialize() {
        MultichunkCausalTransactionState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(
                MultichunkCausalTransactionState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(
                MultichunkCausalTransactionState::onServerTick);
    }
}
