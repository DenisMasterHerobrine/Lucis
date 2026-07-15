package dev.denismasterherobrine.lucis.compat.sable;

import net.minecraft.world.level.Level;

public final class SableCompat {
    private SableCompat() {
    }

    public static boolean isSablePlotChunk(Level level, int chunkX, int chunkZ) {
        return false;
    }
}
