package dev.denismasterherobrine.lucis.neoforge;

import dev.denismasterherobrine.lucis.config.LucisConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

final class LucisNeoForgeConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER.define("enabled", true);
    private static final ModConfigSpec.IntValue REGION_CHUNKS = BUILDER.defineInRange("regionChunks", 1, 1, 16);
    private static final ModConfigSpec.IntValue HALO_CHUNKS = BUILDER.defineInRange("haloChunks", 0, 0, 2);
    private static final ModConfigSpec.IntValue MAX_BATCH_CHUNKS = BUILDER.defineInRange("maxBatchChunks", 64, 1, 512);
    private static final ModConfigSpec.IntValue MAX_CACHED_REGIONS = BUILDER.defineInRange("maxCachedRegions", 128, 1, 4096);
    private static final ModConfigSpec.BooleanValue ENABLE_WORLDGEN = BUILDER.define("enableWorldgen", true);
    private static final ModConfigSpec.BooleanValue ENABLE_RUNTIME = BUILDER.define("enableRuntime", true);
    private static final ModConfigSpec.BooleanValue ENABLE_SKY = BUILDER.define("enableSky", true);
    private static final ModConfigSpec.BooleanValue ENABLE_BLOCK = BUILDER.define("enableBlock", true);
    private static final ModConfigSpec.BooleanValue VERBOSE_LOGGING = BUILDER.define("verboseLogging", false);
    private static final ModConfigSpec.BooleanValue DEBUG = BUILDER.define("debug", false);

    private static final ModConfigSpec SPEC = BUILDER.build();

    private LucisNeoForgeConfig() {
    }

    static void register(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        modEventBus.addListener(LucisNeoForgeConfig::onConfig);
    }

    private static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            sync();
        }
    }

    private static void sync() {
        LucisConfig.apply(
                ENABLED.getAsBoolean(),
                REGION_CHUNKS.getAsInt(),
                HALO_CHUNKS.getAsInt(),
                MAX_BATCH_CHUNKS.getAsInt(),
                MAX_CACHED_REGIONS.getAsInt(),
                ENABLE_WORLDGEN.getAsBoolean(),
                ENABLE_RUNTIME.getAsBoolean(),
                ENABLE_SKY.getAsBoolean(),
                ENABLE_BLOCK.getAsBoolean(),
                VERBOSE_LOGGING.getAsBoolean(),
                DEBUG.getAsBoolean()
        );
    }
}
