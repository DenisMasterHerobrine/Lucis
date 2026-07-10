package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

public final class LucisRegionExtractor {
    private final LightMaterialCache materialCache;

    public LucisRegionExtractor(LightMaterialCache materialCache) {
        this.materialCache = materialCache;
    }

    public RegionLightData extract(LightChunkGetter getter, RegionBounds bounds) {
        RegionLightData data = new RegionLightData(bounds);
        populate(getter, data);
        return data;
    }

    public void populate(LightChunkGetter getter, RegionLightData data) {
        data.resetForReuse();
        BlockGetter level = getter.getLevel();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        RegionBounds bounds = data.bounds;
        int minChunkX = bounds.minBlockX() >> 4;
        int maxChunkX = (bounds.maxBlockXExclusive() - 1) >> 4;
        int minChunkZ = bounds.minBlockZ() >> 4;
        int maxChunkZ = (bounds.maxBlockZExclusive() - 1) >> 4;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int maxBlockX = bounds.maxBlockXExclusive();
        int maxBlockZ = bounds.maxBlockZExclusive();
        int width = bounds.widthBlocks();
        int area = bounds.area();
        int chunkWidth = maxChunkX - minChunkX + 1;
        LightChunk[] chunks = new LightChunk[chunkWidth * (maxChunkZ - minChunkZ + 1)];
        boolean[] chunkResolved = new boolean[chunks.length];

        int coreMinBlockX = bounds.originChunkX() << 4;
        int coreMaxBlockX = coreMinBlockX + bounds.regionChunks() * 16;
        int coreMinBlockZ = bounds.originChunkZ() << 4;
        int coreMaxBlockZ = coreMinBlockZ + bounds.regionChunks() * 16;

        for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
            int localY = worldY - bounds.minBuildY();
            int yBase = localY * area;
            for (int worldZ = minBlockZ; worldZ < maxBlockZ; worldZ++) {
                int chunkZ = worldZ >> 4;
                int localZ = worldZ - minBlockZ;
                int rowBase = yBase + localZ * width;
                for (int worldX = minBlockX; worldX < maxBlockX; worldX++) {
                    int chunkX = worldX >> 4;
                    int chunkIndex = (chunkX - minChunkX) + (chunkZ - minChunkZ) * chunkWidth;
                    LightChunk chunk = chunks[chunkIndex];
                    if (!chunkResolved[chunkIndex]) {
                        chunk = getter.getChunkForLighting(chunkX, chunkZ);
                        chunks[chunkIndex] = chunk;
                        chunkResolved[chunkIndex] = true;
                    }
                    BlockState state;
                    if (chunk == null) {
                        state = worldX >= coreMinBlockX && worldX < coreMaxBlockX
                                && worldZ >= coreMinBlockZ && worldZ < coreMaxBlockZ
                                ? net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState()
                                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                        mutable.set(worldX, worldY, worldZ);
                    } else {
                        mutable.set(worldX, worldY, worldZ);
                        state = chunk.getBlockState(mutable);
                    }

                    LightMaterial material = materialCache.lookup(level, state, mutable);
                    int index = rowBase + (worldX - minBlockX);
                    data.opacity[index] = material.opacity();
                    data.emission[index] = material.emission();
                }
            }
        }
    }
}
