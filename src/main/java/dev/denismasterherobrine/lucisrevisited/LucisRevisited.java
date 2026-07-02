package dev.denismasterherobrine.lucisrevisited;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.lucisrevisited.config.LucisConfig;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(LucisRevisited.MODID)
public class LucisRevisited {
    public static final String MODID = "lucisrevisited";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LucisRevisited() {
        LucisConfig.register();
        LOGGER.info("LucisRevisited booting fast light engine");
    }
}
