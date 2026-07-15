package dev.denismasterherobrine.lucis.light.runtime;

import net.minecraft.world.level.chunk.ChunkAccess;

public record LucisChunkSnapshot(ChunkAccess chunk, boolean trustEdges) {
}
