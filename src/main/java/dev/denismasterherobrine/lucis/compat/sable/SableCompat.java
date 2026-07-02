package dev.denismasterherobrine.lucis.compat.sable;

import dev.denismasterherobrine.lucis.mixin.compat.sable.SableServerLevelAccessor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class SableCompat {
    private SableCompat() {
    }

    public static boolean isSablePlotChunk(Level level, int chunkX, int chunkZ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        SubLevelContainer container = ((SableServerLevelAccessor) serverLevel).lucis$getSablePlotContainer();
        return container != null && container.getPlot(chunkX, chunkZ) != null;
    }
}
