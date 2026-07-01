package dev.denismasterherobrine.lucisrevisited;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.lucisrevisited.config.LucisConfig;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarks;
import dev.denismasterherobrine.lucisrevisited.test.LucisGameTests;
import dev.denismasterherobrine.lucisrevisited.test.LucisServerBenchmark;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(LucisRevisited.MODID)
public class LucisRevisited {
    public static final String MODID = "lucisrevisited";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LucisRevisited() {
        LucisConfig.register();
        IEventBus modBus = ModLoadingContext.get().getActiveContainer().getEventBus();
        modBus.addListener(LucisGameTests::register);
        modBus.addListener(LucisBenchmarks::register);
        LOGGER.info("LucisRevisited booting fast light engine");
    }
}
