package dev.denismasterherobrine.lucis.forge;

import dev.denismasterherobrine.lucis.config.LucisConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

final class LucisForgeConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER.define("enabled", true);
    private static final ForgeConfigSpec.IntValue REGION_CHUNKS = BUILDER.defineInRange("regionChunks", 1, 1, 16);
    private static final ForgeConfigSpec.IntValue HALO_CHUNKS = BUILDER.defineInRange("haloChunks", 0, 0, 2);
    private static final ForgeConfigSpec.IntValue MAX_BATCH_CHUNKS = BUILDER.defineInRange("maxBatchChunks", 64, 1, 512);
    private static final ForgeConfigSpec.IntValue MAX_CACHED_REGIONS = BUILDER.defineInRange("maxCachedRegions", 128, 1, 4096);
    private static final ForgeConfigSpec.BooleanValue ENABLE_WORLDGEN = BUILDER.define("enableWorldgen", true);
    private static final ForgeConfigSpec.BooleanValue ENABLE_RUNTIME = BUILDER.define("enableRuntime", true);
    private static final ForgeConfigSpec.BooleanValue ENABLE_SKY = BUILDER.define("enableSky", true);
    private static final ForgeConfigSpec.BooleanValue ENABLE_BLOCK = BUILDER.define("enableBlock", true);
    private static final ForgeConfigSpec.BooleanValue VERBOSE_LOGGING = BUILDER.define("verboseLogging", false);
    private static final ForgeConfigSpec.BooleanValue DEBUG = BUILDER.define("debug", false);

    private static final ForgeConfigSpec SPEC = BUILDER.build();

    private LucisForgeConfig() {
    }

    static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(LucisForgeConfig::onConfig);
    }

    private static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            sync();
        }
    }

    private static void sync() {
        LucisConfig.apply(
                ENABLED.get(),
                REGION_CHUNKS.get(),
                HALO_CHUNKS.get(),
                MAX_BATCH_CHUNKS.get(),
                MAX_CACHED_REGIONS.get(),
                ENABLE_WORLDGEN.get(),
                ENABLE_RUNTIME.get(),
                ENABLE_SKY.get(),
                ENABLE_BLOCK.get(),
                VERBOSE_LOGGING.get(),
                DEBUG.get()
        );
    }
}
