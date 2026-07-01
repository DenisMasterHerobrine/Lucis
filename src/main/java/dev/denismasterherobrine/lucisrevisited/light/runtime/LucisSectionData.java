package dev.denismasterherobrine.lucisrevisited.light.runtime;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

public record LucisSectionData(SectionPos sectionPos, LightLayer layer, DataLayer dataLayer) {
}
