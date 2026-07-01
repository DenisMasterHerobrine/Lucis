package dev.denismasterherobrine.lucisrevisited.config;

import dev.denismasterherobrine.lucisrevisited.LucisRevisited;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = LucisRevisited.MODID)
public final class LucisConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable Lucis light engine hooks")
            .define("enabled", true);

    private static final ModConfigSpec.IntValue REGION_CHUNKS = BUILDER
            .comment("Owned region size in chunks per axis")
            .defineInRange("regionChunks", 1, 1, 16);

    private static final ModConfigSpec.IntValue HALO_CHUNKS = BUILDER
            .comment("Read-only halo size in chunks")
            .defineInRange("haloChunks", 0, 0, 2);

    private static final ModConfigSpec.IntValue MAX_BATCH_CHUNKS = BUILDER
            .comment("Max chunks per runtime batch")
            .defineInRange("maxBatchChunks", 64, 1, 512);

    private static final ModConfigSpec.IntValue MAX_CACHED_REGIONS = BUILDER
            .comment("Max runtime-owned regions kept in memory")
            .defineInRange("maxCachedRegions", 128, 1, 4096);

    private static final ModConfigSpec.BooleanValue ENABLE_WORLDGEN = BUILDER
            .comment("Replace worldgen light with Lucis")
            .define("enableWorldgen", true);

    private static final ModConfigSpec.BooleanValue ENABLE_RUNTIME = BUILDER
            .comment("Replace block update light with Lucis")
            .define("enableRuntime", true);

    private static final ModConfigSpec.BooleanValue ENABLE_SKY = BUILDER
            .comment("Compute skylight in Lucis")
            .define("enableSky", true);

    private static final ModConfigSpec.BooleanValue ENABLE_BLOCK = BUILDER
            .comment("Compute block light in Lucis")
            .define("enableBlock", true);

    private static final ModConfigSpec.BooleanValue VERBOSE_LOGGING = BUILDER
            .comment("Extra engine diagnostics")
            .define("verboseLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

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

    private LucisConfig() {
    }

    public static void register() {
        ModLoadingContext context = ModLoadingContext.get();
        ModContainer container = context.getActiveContainer();
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        applyOverrides();
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            sync();
        }
    }

    private static void sync() {
        enabled = ENABLED.get();
        regionChunks = REGION_CHUNKS.get();
        haloChunks = HALO_CHUNKS.get();
        maxBatchChunks = MAX_BATCH_CHUNKS.get();
        maxCachedRegions = MAX_CACHED_REGIONS.get();
        enableWorldgen = ENABLE_WORLDGEN.get();
        enableRuntime = ENABLE_RUNTIME.get();
        enableSky = ENABLE_SKY.get();
        enableBlock = ENABLE_BLOCK.get();
        verboseLogging = VERBOSE_LOGGING.get();
        applyOverrides();
    }

    private static void applyOverrides() {
        enabled = overrideBoolean("lucis.enabled", enabled);
        enableWorldgen = overrideBoolean("lucis.enableWorldgen", enableWorldgen);
        enableRuntime = overrideBoolean("lucis.enableRuntime", enableRuntime);
        enableSky = overrideBoolean("lucis.enableSky", enableSky);
        enableBlock = overrideBoolean("lucis.enableBlock", enableBlock);
        verboseLogging = overrideBoolean("lucis.verboseLogging", verboseLogging);
        regionChunks = overrideInt("lucis.regionChunks", regionChunks);
        haloChunks = overrideInt("lucis.haloChunks", haloChunks);
        maxBatchChunks = overrideInt("lucis.maxBatchChunks", maxBatchChunks);
        maxCachedRegions = overrideInt("lucis.maxCachedRegions", maxCachedRegions);
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
}
