package dev.denismasterherobrine.lucisrevisited.light.region;

public record BoundaryDelta(
        long sourceRegionKey,
        long targetRegionKey,
        RegionSide side,
        int minSectionY,
        int sectionCount,
        int widthBlocks,
        int depthBlocks,
        byte[] blockLevels,
        byte[] skyLevels
) {
}
