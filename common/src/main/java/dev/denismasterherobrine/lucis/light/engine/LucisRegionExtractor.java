package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionChunkSnapshot;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                int blockZStart = Math.max(minBlockZ, chunkZ << 4);
                int blockZEnd = Math.min(maxBlockZ, (chunkZ << 4) + 16);
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
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

                    int blockXStart = Math.max(minBlockX, chunkX << 4);
                    int blockXEnd = Math.min(maxBlockX, (chunkX << 4) + 16);
                    if (chunk instanceof ChunkAccess chunkAccess) {
                        populateChunkSections(level, data, mutable, chunkAccess, blockXStart, blockXEnd, blockZStart, blockZEnd);
                    } else {
                        BlockState missingState = blockXStart < coreMaxBlockX && blockXEnd > coreMinBlockX
                                && blockZStart < coreMaxBlockZ && blockZEnd > coreMinBlockZ
                                ? Blocks.BEDROCK.defaultBlockState()
                                : Blocks.AIR.defaultBlockState();
                        populateFallback(level, data, mutable, chunk, missingState, blockXStart, blockXEnd, blockZStart, blockZEnd);
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
            int yBase = (worldY - bounds.minBuildY()) * area;
            for (int worldZ = minBlockZ; worldZ < maxBlockZ; worldZ++) {
                int rowBase = yBase + (worldZ - minBlockZ) * width;
                for (int worldX = minBlockX; worldX < maxBlockX; worldX++) {
                    BlockState state = snapshot.getBlockState(worldX, worldY, worldZ);
                    writeMaterial(level, data, mutable, state, worldX, worldY, worldZ, rowBase + (worldX - minBlockX));
                }
            }
        }
    }

    private void populateChunkSections(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                                       ChunkAccess chunk, int blockXStart, int blockXEnd, int blockZStart, int blockZEnd) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        int firstSection = Math.max(0, (data.bounds.minBuildY() >> 4) - minSectionY);
        int lastSectionExclusive = Math.min(sections.length, ((data.bounds.maxBuildY() - 1) >> 4) - minSectionY + 1);

        for (int sectionIndex = firstSection; sectionIndex < lastSectionExclusive; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            int sectionMinY = (minSectionY + sectionIndex) << 4;
            int blockYStart = Math.max(data.bounds.minBuildY(), sectionMinY);
            int blockYEnd = Math.min(data.bounds.maxBuildY(), sectionMinY + 16);
            populateSectionCells(level, data, mutable, section, blockXStart, blockXEnd, blockYStart, blockYEnd, blockZStart, blockZEnd);
        }
    }

    private void populateSectionCells(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                                      LevelChunkSection section, int blockXStart, int blockXEnd, int blockYStart,
                                      int blockYEnd, int blockZStart, int blockZEnd) {
        int minBlockX = data.bounds.minBlockX();
        int minBlockZ = data.bounds.minBlockZ();
        int minBuildY = data.bounds.minBuildY();
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        for (int worldY = blockYStart; worldY < blockYEnd; worldY++) {
            int yBase = (worldY - minBuildY) * area;
            for (int worldZ = blockZStart; worldZ < blockZEnd; worldZ++) {
                int rowBase = yBase + (worldZ - minBlockZ) * width;
                for (int worldX = blockXStart; worldX < blockXEnd; worldX++) {
                    BlockState state = section.getBlockState(worldX & 15, worldY & 15, worldZ & 15);
                    writeMaterial(level, data, mutable, state, worldX, worldY, worldZ, rowBase + (worldX - minBlockX));
                }
            }
        }
    }

    private void populateFallback(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                                  LightChunk chunk, BlockState missingState, int blockXStart, int blockXEnd,
                                  int blockZStart, int blockZEnd) {
        for (int worldY = data.bounds.minBuildY(); worldY < data.bounds.maxBuildY(); worldY++) {
            int yBase = (worldY - data.bounds.minBuildY()) * data.bounds.area();
            for (int worldZ = blockZStart; worldZ < blockZEnd; worldZ++) {
                int rowBase = yBase + (worldZ - data.bounds.minBlockZ()) * data.bounds.widthBlocks();
                for (int worldX = blockXStart; worldX < blockXEnd; worldX++) {
                    mutable.set(worldX, worldY, worldZ);
                    BlockState state = chunk == null ? missingState : chunk.getBlockState(mutable);
                    writeMaterial(level, data, mutable, state, worldX, worldY, worldZ, rowBase + (worldX - data.bounds.minBlockX()));
                }
            }
        }
    }

    private void writeMaterial(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                               BlockState state, int worldX, int worldY, int worldZ, int index) {
        mutable.set(worldX, worldY, worldZ);
        int material = materialCache.lookupLight(level, state, mutable);
        data.opacity[index] = LightMaterial.opacity(material);
        data.emission[index] = LightMaterial.emission(material);
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
