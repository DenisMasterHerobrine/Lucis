package dev.denismasterherobrine.lucis;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Lucis.MODID)
public class Lucis {
    public static final String MODID = "lucis";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Lucis() {
        LucisConfig.register();
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("Lucis booting fast light engine");
    }

    private void onServerStopped(ServerStoppedEvent event) {
        LucisServices.shutdownController();
    }
}
