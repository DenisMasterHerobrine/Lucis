package dev.denismasterherobrine.lucisrevisited.light.runtime;

import net.minecraft.world.level.ChunkPos;

import java.util.List;

public record LucisRelightResult(ChunkPos chunkPos, List<LucisSectionData> sections) {
}
