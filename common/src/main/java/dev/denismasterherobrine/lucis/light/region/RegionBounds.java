package dev.denismasterherobrine.lucis.light.region;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;

public record RegionBounds(
        int originChunkX,
        int originChunkZ,
        int regionChunks,
        int haloChunks,
        int widthBlocks,
        int depthBlocks,
        int minBuildY,
        int maxBuildY,
        int minSectionY,
        int sectionCount,
        int heightBlocks,
        int area,
        int volume
) {
    public static RegionBounds around(ChunkPos center, LevelHeightAccessor level, int regionChunks, int haloChunks) {
        int originChunkX = Math.floorDiv(center.x(), regionChunks) * regionChunks;
        int originChunkZ = Math.floorDiv(center.z(), regionChunks) * regionChunks;
        int widthBlocks = (regionChunks + haloChunks * 2) * LucisConstants.SECTION_SIZE;
        int depthBlocks = widthBlocks;
        int minBuildY = level.getMinY();
        int maxBuildY = level.getMaxY() + 1;
        int minSectionY = level.getMinSectionY();
        int sectionCount = level.getSectionsCount();
        int heightBlocks = maxBuildY - minBuildY;
        int area = widthBlocks * depthBlocks;
        int volume = area * heightBlocks;
        return new RegionBounds(originChunkX, originChunkZ, regionChunks, haloChunks, widthBlocks, depthBlocks, minBuildY,
                maxBuildY, minSectionY, sectionCount, heightBlocks, area, volume);
    }

    public int minBlockX() {
        return (originChunkX - haloChunks) << 4;
    }

    public int minBlockZ() {
        return (originChunkZ - haloChunks) << 4;
    }

    public int maxBlockXExclusive() {
        return minBlockX() + widthBlocks;
    }

    public int maxBlockZExclusive() {
        return minBlockZ() + depthBlocks;
    }

    public int sectionIndex(int sectionY) {
        return sectionY - minSectionY;
    }

    public long coreRegionKey() {
        return regionKey(originChunkX, originChunkZ);
    }

    public static long regionKey(int chunkX, int chunkZ) {
        return ((long) chunkZ & 0xFFFFFFFFL) << 32 | ((long) chunkX & 0xFFFFFFFFL);
    }

    public boolean containsBlock(int x, int y, int z) {
        return x >= minBlockX() && x < maxBlockXExclusive()
                && z >= minBlockZ() && z < maxBlockZExclusive()
                && y >= minBuildY && y < maxBuildY;
    }

    public SectionPos coreSectionPos(int localChunkX, int sectionY, int localChunkZ) {
        return SectionPos.of(originChunkX + localChunkX, sectionY, originChunkZ + localChunkZ);
    }
}
