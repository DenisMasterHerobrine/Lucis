package dev.denismasterherobrine.lucis.neoforge;

import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.platform.LucisPlatform;
import dev.denismasterherobrine.lucis.test.LucisClientLoadDiagnostics;
import dev.denismasterherobrine.lucis.test.LucisServerBenchmark;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(Lucis.MODID)
public final class LucisNeoForge {
    public LucisNeoForge(IEventBus modEventBus, ModContainer container) {
        LucisPlatform.installModLookup(ModList.get()::isLoaded);
        LucisNeoForgeConfig.register(modEventBus, container);
        Lucis.initialize();

        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        LucisClientLoadDiagnostics.onServerAboutToStart(event.getServer());
    }

    private void onServerStarted(ServerStartedEvent event) {
        LucisServerBenchmark.onServerStarted(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        LucisServerBenchmark.onServerTick(event.getServer());
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        LucisClientLoadDiagnostics.onPlayerLoggedIn();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LucisServerBenchmark.onServerStopping(event.getServer());
        LucisClientLoadDiagnostics.onServerStopping(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        LucisClientLoadDiagnostics.onServerStopped(event.getServer());
        Lucis.shutdown();
    }
}
