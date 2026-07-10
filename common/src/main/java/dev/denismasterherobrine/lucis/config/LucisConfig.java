package dev.denismasterherobrine.lucis.config;

import dev.denismasterherobrine.lucis.Lucis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LucisConfig {
    public static volatile boolean enabled = true;
    public static volatile int regionChunks = 1;
    public static volatile int haloChunks = 0;
    public static volatile int maxBatchChunks = 64;
    public static volatile int maxCachedRegions = 128;
    public static volatile boolean enableWorldgen = true;
    public static volatile boolean enableRuntime = true;
    public static volatile boolean enableSky = true;
    public static volatile boolean enableBlock = true;
    public static volatile boolean verboseLogging = false;
    public static volatile boolean debug = false;

    private LucisConfig() {
    }

    public static synchronized void loadProperties(Path path) {
        Properties properties = defaults();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException exception) {
                Lucis.LOGGER.warn("Failed to read Lucis config from {}", path, exception);
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (OutputStream output = Files.newOutputStream(path)) {
                    properties.store(output, "Lucis configuration");
                }
            } catch (IOException exception) {
                Lucis.LOGGER.warn("Failed to create Lucis config at {}", path, exception);
            }
        }

        apply(
                booleanValue(properties, "enabled", true),
                intValue(properties, "regionChunks", 1, 1, 16),
                intValue(properties, "haloChunks", 0, 0, 2),
                intValue(properties, "maxBatchChunks", 64, 1, 512),
                intValue(properties, "maxCachedRegions", 128, 1, 4096),
                booleanValue(properties, "enableWorldgen", true),
                booleanValue(properties, "enableRuntime", true),
                booleanValue(properties, "enableSky", true),
                booleanValue(properties, "enableBlock", true),
                booleanValue(properties, "verboseLogging", false),
                booleanValue(properties, "debug", false)
        );
    }

    public static synchronized void apply(boolean enabledValue, int regionChunksValue, int haloChunksValue,
                                          int maxBatchChunksValue, int maxCachedRegionsValue,
                                          boolean enableWorldgenValue, boolean enableRuntimeValue,
                                          boolean enableSkyValue, boolean enableBlockValue,
                                          boolean verboseLoggingValue, boolean debugValue) {
        enabled = enabledValue;
        regionChunks = clamp(regionChunksValue, 1, 16);
        haloChunks = clamp(haloChunksValue, 0, 2);
        maxBatchChunks = clamp(maxBatchChunksValue, 1, 512);
        maxCachedRegions = clamp(maxCachedRegionsValue, 1, 4096);
        enableWorldgen = enableWorldgenValue;
        enableRuntime = enableRuntimeValue;
        enableSky = enableSkyValue;
        enableBlock = enableBlockValue;
        verboseLogging = verboseLoggingValue;
        debug = debugValue;
        applySystemOverrides();
    }

    public static synchronized void applySystemOverrides() {
        enabled = overrideBoolean("lucis.enabled", enabled);
        enableWorldgen = overrideBoolean("lucis.enableWorldgen", enableWorldgen);
        enableRuntime = overrideBoolean("lucis.enableRuntime", enableRuntime);
        enableSky = overrideBoolean("lucis.enableSky", enableSky);
        enableBlock = overrideBoolean("lucis.enableBlock", enableBlock);
        verboseLogging = overrideBoolean("lucis.verboseLogging", verboseLogging);
        debug = overrideBoolean("lucis.debug", debug);
        regionChunks = clamp(overrideInt("lucis.regionChunks", regionChunks), 1, 16);
        haloChunks = clamp(overrideInt("lucis.haloChunks", haloChunks), 0, 2);
        maxBatchChunks = clamp(overrideInt("lucis.maxBatchChunks", maxBatchChunks), 1, 512);
        maxCachedRegions = clamp(overrideInt("lucis.maxCachedRegions", maxCachedRegions), 1, 4096);
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        properties.setProperty("regionChunks", "1");
        properties.setProperty("haloChunks", "0");
        properties.setProperty("maxBatchChunks", "64");
        properties.setProperty("maxCachedRegions", "128");
        properties.setProperty("enableWorldgen", "true");
        properties.setProperty("enableRuntime", "true");
        properties.setProperty("enableSky", "true");
        properties.setProperty("enableBlock", "true");
        properties.setProperty("verboseLogging", "false");
        properties.setProperty("debug", "false");
        return properties;
    }

    private static boolean booleanValue(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int intValue(Properties properties, String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return clamp(Integer.parseInt(value.trim()), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean overrideBoolean(String key, boolean current) {
        String value = System.getProperty(key);
        return value == null ? current : Boolean.parseBoolean(value);
    }

    private static int overrideInt(String key, int current) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return current;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return current;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
