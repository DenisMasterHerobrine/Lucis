package dev.denismasterherobrine.lucisrevisited.test;

import dev.denismasterherobrine.lucisrevisited.LucisRevisited;
import dev.denismasterherobrine.lucisrevisited.config.LucisConfig;
import dev.denismasterherobrine.lucisrevisited.light.engine.LucisServices;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@EventBusSubscriber(modid = LucisRevisited.MODID)
public final class LucisClientLoadDiagnostics {
    private static final AtomicBoolean PLAYER_LOGGED = new AtomicBoolean();
    private static volatile long startedAtNanos;

    private LucisClientLoadDiagnostics() {
    }

    @SubscribeEvent
    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (event.getServer().isDedicatedServer()) {
            return;
        }
        LucisServices.resetController();
        if (!enabled()) {
            return;
        }
        LucisBenchmarkSupport.reset();
        PLAYER_LOGGED.set(false);
        startedAtNanos = System.nanoTime();
        LucisRevisited.LOGGER.info("LUCIS_CLIENT_MEASURE_START integrated=true");
    }

    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || startedAtNanos == 0L || !PLAYER_LOGGED.compareAndSet(false, true)) {
            return;
        }
        logSnapshot("player_join", System.nanoTime());
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        if (!enabled() || startedAtNanos == 0L || event.getServer().isDedicatedServer()) {
            return;
        }
        logSnapshot("server_stopping", System.nanoTime());
        startedAtNanos = 0L;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        if (event.getServer().isDedicatedServer()) {
            return;
        }
        LucisServices.controller().shutdown();
    }

    private static void logSnapshot(String phase, long nowNanos) {
        double elapsedMs = (nowNanos - startedAtNanos) / 1_000_000.0;
        LucisBenchmarkSupport.Snapshot snapshot = LucisBenchmarkSupport.snapshot();
        LucisRevisited.LOGGER.info("LUCIS_CLIENT_MEASURE phase={} elapsed_ms={} metrics={} counters={}",
                phase,
                format(elapsedMs),
                formatMetrics(snapshot.metrics()),
                snapshot.counters());
    }

    private static boolean enabled() {
        return Boolean.getBoolean("lucis.clientMeasure") && LucisConfig.debug;
    }

    private static String formatMetrics(Map<String, LucisBenchmarkSupport.Metric> metrics) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, LucisBenchmarkSupport.Metric> entry : metrics.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            LucisBenchmarkSupport.Metric metric = entry.getValue();
            builder.append(entry.getKey())
                    .append("=")
                    .append(format(metric.millis()))
                    .append("ms/")
                    .append(metric.calls());
        }
        return builder.append("}").toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
