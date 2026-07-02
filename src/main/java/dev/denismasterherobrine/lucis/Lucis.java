package dev.denismasterherobrine.lucis;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Lucis.MODID)
public class Lucis {
    public static final String MODID = "lucis";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Lucis() {
        LucisConfig.register();
        LOGGER.info("Lucis booting fast light engine");
    }
}
