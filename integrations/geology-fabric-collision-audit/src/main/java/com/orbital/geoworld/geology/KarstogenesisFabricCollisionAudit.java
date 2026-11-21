package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the frozen Karstogenesis run 210 collision audit. */
public final class KarstogenesisFabricCollisionAudit implements ModInitializer {
    static final Logger LOGGER =
            LoggerFactory.getLogger("karstogenesis-fabric-collision-audit");

    @Override
    public void onInitialize() {
        CollisionAuditState.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(CollisionAuditState::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(CollisionAuditState::onServerTick);
    }
}
