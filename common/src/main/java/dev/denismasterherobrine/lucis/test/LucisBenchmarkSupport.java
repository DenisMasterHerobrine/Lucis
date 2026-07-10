package dev.denismasterherobrine.lucis.test;

import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.compat.LucisCompat;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.platform.LucisPlatform;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class LucisBenchmarkSupport {
    private static final ConcurrentHashMap<String, LongAdder> TIME_NANOS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();
    private static final AtomicLong RESET_EPOCH = new AtomicLong();

    private LucisBenchmarkSupport() {
    }

    public static boolean enabled() {
        return LucisConfig.debug;
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    public static void reset() {
        if (!enabled()) {
            return;
        }
        TIME_NANOS.clear();
        COUNTS.clear();
        COUNTERS.clear();
        RESET_EPOCH.incrementAndGet();
    }

    public static long epoch() {
        return RESET_EPOCH.get();
    }

    public static Snapshot snapshot() {
        if (!enabled()) {
            return new Snapshot(Map.of(), Map.of());
        }
        Map<String, Metric> metrics = new TreeMap<>();
        TIME_NANOS.forEach((key, nanos) -> metrics.put(key, new Metric(nanos.sum(), counterValue(COUNTS, key))));
        Map<String, Long> counters = new TreeMap<>();
        COUNTERS.forEach((key, counter) -> counters.put(key, counter.sum()));
        return new Snapshot(metrics, counters);
    }

    public static void record(String key, long nanos) {
        if (!enabled() || nanos <= 0L) {
            return;
        }
        TIME_NANOS.computeIfAbsent(key, ignored -> new LongAdder()).add(nanos);
        COUNTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    public static void recordSince(String key, long startedAtNanos) {
        if (!enabled() || startedAtNanos == 0L) {
            return;
        }
        record(key, System.nanoTime() - startedAtNanos);
    }

    public static void count(String key) {
        count(key, 1L);
    }

    public static void count(String key, long amount) {
        if (!enabled() || amount == 0L) {
            return;
        }
        COUNTERS.computeIfAbsent(key, ignored -> new LongAdder()).add(amount);
    }

    public static void logResult(String name, int iterations, long startedAtNanos, long endedAtNanos) {
        if (!enabled()) {
            return;
        }
        long elapsedNanos = Math.max(0L, endedAtNanos - startedAtNanos);
        double elapsedMs = elapsedNanos / 1_000_000.0;
        double perIterationMs = iterations == 0 ? 0.0 : elapsedMs / iterations;
        double totalLightMs = totalLightMs();
        Lucis.LOGGER.info("LUCIS_BENCH_RESULT mode={} name={} iterations={} wall_ms={} per_iter_ms={}",
                activeMode(),
                name,
                iterations,
                format(elapsedMs),
                format(perIterationMs));
        Lucis.LOGGER.info("LUCIS_BENCH_TOTAL mode={} name={} total_light_ms={}",
                activeMode(),
                name,
                format(totalLightMs));

        TIME_NANOS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String key = entry.getKey();
                    long nanos = entry.getValue().sum();
                    long count = counterValue(COUNTS, key);
                    double ms = nanos / 1_000_000.0;
                    Lucis.LOGGER.info("LUCIS_BENCH_METRIC mode={} name={} metric={} total_ms={} calls={}",
                            activeMode(),
                            name,
                            key,
                            format(ms),
                            count);
                });

        COUNTERS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> Lucis.LOGGER.info("LUCIS_BENCH_COUNTER mode={} name={} counter={} value={}",
                        activeMode(),
                        name,
                        entry.getKey(),
                        entry.getValue().sum()));
    }

    private static long counterValue(Map<String, LongAdder> counters, String key) {
        LongAdder counter = counters.get(key);
        return counter == null ? 0L : counter.sum();
    }

    private static String activeMode() {
        boolean scalableLux = LucisPlatform.isModLoaded("scalablelux");
        boolean generatorAccelerator = LucisPlatform.isModLoaded("generator_accelerator");
        boolean c2me = LucisPlatform.isModLoaded("c2me");
        boolean sable = LucisCompat.isSableLoaded();
        String suffix = (scalableLux ? "+scalablelux" : "")
                + (generatorAccelerator ? "+generator_accelerator" : "")
                + (c2me ? "+c2me" : "")
                + (sable ? "+sable" : "");
        if (LucisConfig.enabled) {
            return "lucis" + suffix;
        }
        return suffix.isEmpty() ? "vanilla" : suffix.substring(1);
    }

    private static double totalLightMs() {
        String mode = activeMode();
        if (mode.startsWith("lucis")) {
            return millis("lucis.check_block") + millis("lucis.light_chunk") + millis("lucis.runtime_tick");
        }
        return millis("engine.check_block");
    }

    private static double millis(String key) {
        LongAdder adder = TIME_NANOS.get(key);
        return adder == null ? 0.0 : adder.sum() / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    public record Metric(long nanos, long calls) {
        public double millis() {
            return nanos / 1_000_000.0;
        }
    }

    public record Snapshot(Map<String, Metric> metrics, Map<String, Long> counters) {
    }
}
