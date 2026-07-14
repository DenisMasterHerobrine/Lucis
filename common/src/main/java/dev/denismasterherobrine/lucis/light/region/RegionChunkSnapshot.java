package dev.denismasterherobrine.lucis.light.region;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;

public final class RegionChunkSnapshot implements BlockGetter {
    private final RegionBounds bounds;
    private final SnapshotChunk[] chunks;
    private final int minChunkX;
    private final int minChunkZ;
    private final int chunkWidth;

    private RegionChunkSnapshot(RegionBounds bounds, SnapshotChunk[] chunks, int minChunkX, int minChunkZ, int chunkWidth) {
        this.bounds = bounds;
        this.chunks = chunks;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunkWidth = chunkWidth;
    }

    public static RegionChunkSnapshot capture(LightChunkGetter getter, RegionBounds bounds, LightChunk coreChunk) {
        int minChunkX = bounds.minBlockX() >> 4;
        int maxChunkX = (bounds.maxBlockXExclusive() - 1) >> 4;
        int minChunkZ = bounds.minBlockZ() >> 4;
        int maxChunkZ = (bounds.maxBlockZExclusive() - 1) >> 4;
        int chunkWidth = maxChunkX - minChunkX + 1;
        SnapshotChunk[] chunks = new SnapshotChunk[chunkWidth * (maxChunkZ - minChunkZ + 1)];

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                LightChunk chunk = coreChunk != null
                        && chunkX == bounds.originChunkX()
                        && chunkZ == bounds.originChunkZ()
                        ? coreChunk
                        : getter.getChunkForLighting(chunkX, chunkZ);
                if (chunk instanceof ChunkAccess chunkAccess) {
                    chunks[(chunkX - minChunkX) + (chunkZ - minChunkZ) * chunkWidth] = SnapshotChunk.capture(chunkAccess);
                }
            }
        }

        return new RegionChunkSnapshot(bounds, chunks, minChunkX, minChunkZ, chunkWidth);
    }

    public RegionBounds bounds() {
        return bounds;
    }

    public BlockState getBlockState(int worldX, int worldY, int worldZ) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        int chunkIndex = (chunkX - minChunkX) + (chunkZ - minChunkZ) * chunkWidth;
        SnapshotChunk chunk = chunkIndex >= 0 && chunkIndex < chunks.length ? chunks[chunkIndex] : null;
        if (chunk != null) {
            return chunk.getBlockState(worldX, worldY, worldZ);
        }

        int coreMinBlockX = bounds.originChunkX() << 4;
        int coreMaxBlockX = coreMinBlockX + bounds.regionChunks() * 16;
        int coreMinBlockZ = bounds.originChunkZ() << 4;
        int coreMaxBlockZ = coreMinBlockZ + bounds.regionChunks() * 16;
        return worldX >= coreMinBlockX && worldX < coreMaxBlockX
                && worldZ >= coreMinBlockZ && worldZ < coreMaxBlockZ
                ? Blocks.BEDROCK.defaultBlockState()
                : Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return bounds.heightBlocks();
    }

    @Override
    public int getMinBuildHeight() {
        return bounds.minBuildY();
    }

    private static final class SnapshotChunk {
        private final int minBuildY;
        private final int maxBuildY;
        private final int minSectionY;
        private final PalettedContainer<BlockState>[] sections;

        private SnapshotChunk(int minBuildY, int maxBuildY, int minSectionY, PalettedContainer<BlockState>[] sections) {
            this.minBuildY = minBuildY;
            this.maxBuildY = maxBuildY;
            this.minSectionY = minSectionY;
            this.sections = sections;
        }

        @SuppressWarnings("unchecked")
        private static SnapshotChunk capture(ChunkAccess chunk) {
            LevelChunkSection[] liveSections = chunk.getSections();
            PalettedContainer<BlockState>[] sections = new PalettedContainer[liveSections.length];
            for (int i = 0; i < liveSections.length; i++) {
                sections[i] = liveSections[i].getStates().copy();
            }
            return new SnapshotChunk(chunk.getMinBuildHeight(), chunk.getMaxBuildHeight(), chunk.getMinSection(), sections);
        }

        private BlockState getBlockState(int worldX, int worldY, int worldZ) {
            if (worldY < minBuildY || worldY >= maxBuildY) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            int sectionIndex = (worldY >> 4) - minSectionY;
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return Blocks.AIR.defaultBlockState();
            }
            return sections[sectionIndex].get(worldX & 15, worldY & 15, worldZ & 15);
        }
    }
}
