package dev.denismasterherobrine.lucis;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Lucis {
    public static final String MODID = "lucis";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private Lucis() {
    }

    public static void initialize() {
        LucisConfig.applySystemOverrides();
        if (INITIALIZED.compareAndSet(false, true)) {
            LOGGER.info("Lucis booting fast light engine");
        }
    }

    public static void shutdown() {
        LucisServices.shutdownController();
    }
}
