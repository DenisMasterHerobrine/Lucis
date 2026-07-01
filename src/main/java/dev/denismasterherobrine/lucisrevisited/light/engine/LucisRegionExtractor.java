package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.light.LightMaterial;
import dev.denismasterherobrine.lucisrevisited.light.LightMaterialCache;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
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
        int chunkWidth = maxChunkX - minChunkX + 1;
        LightChunk[] chunks = new LightChunk[chunkWidth * (maxChunkZ - minChunkZ + 1)];
        boolean[] chunkResolved = new boolean[chunks.length];

        for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
            int sectionY = worldY >> 4;
            for (int worldZ = bounds.minBlockZ(); worldZ < bounds.maxBlockZExclusive(); worldZ++) {
                int chunkZ = worldZ >> 4;
                for (int worldX = bounds.minBlockX(); worldX < bounds.maxBlockXExclusive(); worldX++) {
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
                        state = Blocks.BEDROCK.defaultBlockState();
                        mutable.set(worldX, worldY, worldZ);
                    } else {
                        mutable.set(worldX, worldY, worldZ);
                        state = chunk.getBlockState(mutable);
                    }

                    LightMaterial material = materialCache.lookup(level, state, mutable);
                    int index = data.index(worldX, worldY, worldZ);
                    data.opacity[index] = material.opacity();
                    data.emission[index] = material.emission();
                    if (!material.isAir()) {
                        data.markNonEmpty(worldX, sectionY, worldZ);
                    }
                }
            }
        }
    }
}
