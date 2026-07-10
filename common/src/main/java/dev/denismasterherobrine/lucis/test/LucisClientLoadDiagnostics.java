package dev.denismasterherobrine.lucis.test;

import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LucisClientLoadDiagnostics {
    private static final AtomicBoolean PLAYER_LOGGED = new AtomicBoolean();
    private static volatile long startedAtNanos;

    private LucisClientLoadDiagnostics() {
    }

    public static void onServerAboutToStart(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            return;
        }
        LucisServices.resetController();
        if (!enabled()) {
            return;
        }
        LucisBenchmarkSupport.reset();
        PLAYER_LOGGED.set(false);
        startedAtNanos = System.nanoTime();
        Lucis.LOGGER.info("LUCIS_CLIENT_MEASURE_START integrated=true");
    }

    public static void onPlayerLoggedIn() {
        if (!enabled() || startedAtNanos == 0L || !PLAYER_LOGGED.compareAndSet(false, true)) {
            return;
        }
        logSnapshot("player_join", System.nanoTime());
    }

    public static void onServerStopping(MinecraftServer server) {
        if (!enabled() || startedAtNanos == 0L || server.isDedicatedServer()) {
            return;
        }
        logSnapshot("server_stopping", System.nanoTime());
        startedAtNanos = 0L;
    }

    public static void onServerStopped(MinecraftServer server) {
        if (!server.isDedicatedServer()) {
            LucisServices.controller().shutdown();
        }
    }

    private static void logSnapshot(String phase, long nowNanos) {
        double elapsedMs = (nowNanos - startedAtNanos) / 1_000_000.0;
        LucisBenchmarkSupport.Snapshot snapshot = LucisBenchmarkSupport.snapshot();
        Lucis.LOGGER.info("LUCIS_CLIENT_MEASURE phase={} elapsed_ms={} metrics={} counters={}",
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
