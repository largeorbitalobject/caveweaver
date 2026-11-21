package com.orbital.geoworld.geology;

import net.fabricmc.api.ModInitializer;

/** Installs a read-only timing probe through required mixins. */
public final class RuntimeBenchmarkProbe implements ModInitializer {
    @Override
    public void onInitialize() {
        RuntimeBenchmarkState.initialize();
    }
}
