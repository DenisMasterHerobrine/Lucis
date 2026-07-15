package dev.denismasterherobrine.lucis.compat;

import dev.denismasterherobrine.lucis.compat.sable.SableCompat;
import dev.denismasterherobrine.lucis.platform.LucisPlatform;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;

public final class LucisCompat {
    public static final String SABLE_MOD_ID = "sable";

    private static volatile Boolean sableLoaded;

    private LucisCompat() {
    }

    public static boolean isSableLoaded() {
        Boolean cached = sableLoaded;
        if (cached == null) {
            cached = LucisPlatform.isModLoaded(SABLE_MOD_ID);
            sableLoaded = cached;
        }
        return cached;
    }

    public static boolean isSablePlotChunk(LightChunkGetter getter, ChunkPos chunkPos) {
        Level level = levelFromGetter(getter);
        return level != null && isSablePlotChunk(level, chunkPos.x, chunkPos.z);
    }

    public static boolean isSablePlotChunk(LightChunkGetter getter, int chunkX, int chunkZ) {
        Level level = levelFromGetter(getter);
        return level != null && isSablePlotChunk(level, chunkX, chunkZ);
    }

    public static boolean isSablePlotChunk(Level level, int chunkX, int chunkZ) {
        return isSableLoaded() && SableCompat.isSablePlotChunk(level, chunkX, chunkZ);
    }

    private static Level levelFromGetter(LightChunkGetter getter) {
        if (getter instanceof ServerChunkCache serverChunkCache) {
            return serverChunkCache.getLevel();
        }
        return null;
    }
}
