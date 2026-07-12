package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionChunkSnapshot;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.Arrays;

public final class LucisRegionExtractor {
    private final LightMaterialCache materialCache;
    private final ThreadLocal<ChunkScratch> chunkScratch = ThreadLocal.withInitial(ChunkScratch::new);

    public LucisRegionExtractor(LightMaterialCache materialCache) {
        this.materialCache = materialCache;
    }

    public RegionLightData extract(LightChunkGetter getter, RegionBounds bounds) {
        return extract(getter, bounds, null);
    }

    public RegionLightData extract(LightChunkGetter getter, RegionBounds bounds, LightChunk coreChunk) {
        RegionLightData data = new RegionLightData(bounds);
        populate(getter, data, coreChunk);
        return data;
    }

    public void populate(LightChunkGetter getter, RegionLightData data) {
        populate(getter, data, null);
    }

    public void populate(LightChunkGetter getter, RegionLightData data, LightChunk coreChunk) {
        data.beginFullPopulate();
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
        int chunkCount = chunkWidth * (maxChunkZ - minChunkZ + 1);
        ChunkScratch scratch = chunkScratch.get();
        LightChunk[] chunks = scratch.chunks(chunkCount);
        boolean[] chunkResolved = scratch.resolved(chunkCount);

        int coreMinBlockX = bounds.originChunkX() << 4;
        int coreMaxBlockX = coreMinBlockX + bounds.regionChunks() * 16;
        int coreMinBlockZ = bounds.originChunkZ() << 4;
        int coreMaxBlockZ = coreMinBlockZ + bounds.regionChunks() * 16;

        try {
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
                            chunk = coreChunk != null
                                    && chunkX == bounds.originChunkX()
                                    && chunkZ == bounds.originChunkZ()
                                    ? coreChunk
                                    : getter.getChunkForLighting(chunkX, chunkZ);
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
        } finally {
            scratch.release(chunkCount);
        }
    }

    public void populateFromSnapshot(BlockGetter level, RegionLightData data, RegionChunkSnapshot snapshot) {
        data.beginFullPopulate();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        RegionBounds bounds = data.bounds;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int maxBlockX = bounds.maxBlockXExclusive();
        int maxBlockZ = bounds.maxBlockZExclusive();
        int width = bounds.widthBlocks();
        int area = bounds.area();

        for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
            int localY = worldY - bounds.minBuildY();
            int yBase = localY * area;
            for (int worldZ = minBlockZ; worldZ < maxBlockZ; worldZ++) {
                int localZ = worldZ - minBlockZ;
                int rowBase = yBase + localZ * width;
                for (int worldX = minBlockX; worldX < maxBlockX; worldX++) {
                    mutable.set(worldX, worldY, worldZ);
                    BlockState state = snapshot.getBlockState(worldX, worldY, worldZ);
                    LightMaterial material = materialCache.lookup(level, state, mutable);
                    int index = rowBase + (worldX - minBlockX);
                    data.opacity[index] = material.opacity();
                    data.emission[index] = material.emission();
                }
            }
        }
    }

    private static final class ChunkScratch {
        private LightChunk[] chunks = new LightChunk[9];
        private boolean[] resolved = new boolean[9];

        private LightChunk[] chunks(int required) {
            if (chunks.length < required) {
                chunks = new LightChunk[required];
            }
            return chunks;
        }

        private boolean[] resolved(int required) {
            if (resolved.length < required) {
                resolved = new boolean[required];
            }
            return resolved;
        }

        private void release(int used) {
            Arrays.fill(chunks, 0, used, null);
            Arrays.fill(resolved, 0, used, false);
        }
    }
}
