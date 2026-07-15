package dev.denismasterherobrine.lucis.forge;

import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.platform.LucisPlatform;
import dev.denismasterherobrine.lucis.test.LucisClientLoadDiagnostics;
import dev.denismasterherobrine.lucis.test.LucisServerBenchmark;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

@Mod(Lucis.MODID)
public final class LucisForge {
    public LucisForge() {
        LucisPlatform.installModLookup(ModList.get()::isLoaded);
        LucisForgeConfig.register();
        Lucis.initialize();

        MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        LucisClientLoadDiagnostics.onServerAboutToStart(event.getServer());
    }

    private void onServerStarted(ServerStartedEvent event) {
        LucisServerBenchmark.onServerStarted(event.getServer());
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LucisServerBenchmark.onServerTick(event.getServer());
            LucisClientLoadDiagnostics.onServerTick(event.getServer());
        }
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
