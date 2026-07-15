package dev.denismasterherobrine.lucis.fabric;

import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.platform.LucisPlatform;
import dev.denismasterherobrine.lucis.test.LucisClientLoadDiagnostics;
import dev.denismasterherobrine.lucis.test.LucisServerBenchmark;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class LucisFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        LucisPlatform.installModLookup(loader::isModLoaded);
        LucisConfig.loadProperties(loader.getConfigDir().resolve("lucis.properties"));
        Lucis.initialize();

        ServerLifecycleEvents.SERVER_STARTING.register(LucisClientLoadDiagnostics::onServerAboutToStart);
        ServerLifecycleEvents.SERVER_STARTED.register(LucisServerBenchmark::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LucisServerBenchmark.onServerTick(server);
            LucisClientLoadDiagnostics.onServerTick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LucisServerBenchmark.onServerStopping(server);
            LucisClientLoadDiagnostics.onServerStopping(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LucisClientLoadDiagnostics.onServerStopped(server);
            Lucis.shutdown();
        });
    }
}
