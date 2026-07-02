package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.LucisSectionData;
import dev.denismasterherobrine.lucis.light.util.NibblePacker;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class LucisPublishEngine {
    public LucisRelightResult publishChunk(ChunkPos chunkPos, RegionLightData data) {
        List<LucisSectionData> sections = new ArrayList<>();
        RegionBounds bounds = data.bounds;
        int minChunkX = bounds.originChunkX();
        int minChunkZ = bounds.originChunkZ();
        int maxChunkX = minChunkX + bounds.regionChunks();
        int maxChunkZ = minChunkZ + bounds.regionChunks();
        if (chunkPos.x < minChunkX || chunkPos.x >= maxChunkX || chunkPos.z < minChunkZ || chunkPos.z >= maxChunkZ) {
            return new LucisRelightResult(chunkPos, List.of());
        }

        collect(data, chunkPos, LightLayer.BLOCK, false, data.dirtyBlockSections, sections);
        collect(data, chunkPos, LightLayer.SKY, true, data.dirtySkySections, sections);
        LucisBenchmarkSupport.count("lucis.publish.chunk.calls");
        LucisBenchmarkSupport.count("lucis.publish.chunk.sections", sections.size());
        return new LucisRelightResult(chunkPos, sections);
    }

    public List<LucisRelightResult> publishRegion(RegionLightData data) {
        List<LucisRelightResult> results = new ArrayList<>();
        for (int chunkZ = 0; chunkZ < data.bounds.regionChunks(); chunkZ++) {
            for (int chunkX = 0; chunkX < data.bounds.regionChunks(); chunkX++) {
                ChunkPos chunkPos = new ChunkPos(data.bounds.originChunkX() + chunkX, data.bounds.originChunkZ() + chunkZ);
                LucisRelightResult result = publishChunk(chunkPos, data);
                if (!result.sections().isEmpty()) {
                    results.add(result);
                }
            }
        }
        return results;
    }

    private void collect(RegionLightData data, ChunkPos chunkPos, LightLayer layer, boolean sky, BitSet dirtyMask, List<LucisSectionData> out) {
        int sectionWidth = data.bounds.widthBlocks() >> 4;
        int localChunkX = chunkPos.x - (data.bounds.originChunkX() - data.bounds.haloChunks());
        int localChunkZ = chunkPos.z - (data.bounds.originChunkZ() - data.bounds.haloChunks());
        int sectionIndexBase = localChunkX + localChunkZ * sectionWidth;
        int sectionsPerPlane = sectionWidth * sectionWidth;
        byte[] source = sky ? data.skyLight : data.blockLight;

        for (int sectionY = 0; sectionY < data.bounds.sectionCount(); sectionY++) {
            int linear = sectionIndexBase + sectionY * sectionsPerPlane;
            if (!dirtyMask.get(linear)) {
                continue;
            }

            int worldSectionX = chunkPos.x << 4;
            int worldSectionZ = chunkPos.z << 4;
            int worldSectionY = data.bounds.minSectionY() + sectionY;
            DataLayer packed = NibblePacker.packSection(data, source, worldSectionX, worldSectionY, worldSectionZ);
            out.add(new LucisSectionData(SectionPos.of(chunkPos, data.bounds.minSectionY() + sectionY), layer, packed));
            LucisBenchmarkSupport.count(sky ? "lucis.publish.sky.sections" : "lucis.publish.block.sections");
        }
    }
}
