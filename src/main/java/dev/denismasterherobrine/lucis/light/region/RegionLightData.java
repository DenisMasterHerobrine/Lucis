package dev.denismasterherobrine.lucis.light.region;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import net.minecraft.core.SectionPos;

import java.util.BitSet;

public final class RegionLightData {
    public final RegionBounds bounds;
    public final byte[] opacity;
    public final byte[] emission;
    public final byte[] blockLight;
    public final byte[] skyLight;
    public final BitSet dirtyBlockSections;
    public final BitSet dirtySkySections;
    public final int sectionWidth;
    public final int sectionsPerPlane;
    public final int coreLocalChunkStart;
    public final int coreLocalChunkEnd;
    public final int offsetPosX;
    public final int offsetNegX;
    public final int offsetPosZ;
    public final int offsetNegZ;
    public final int offsetPosY;
    public final int offsetNegY;

    public RegionLightData(RegionBounds bounds) {
        this.bounds = bounds;
        this.opacity = new byte[bounds.volume()];
        this.emission = new byte[bounds.volume()];
        this.blockLight = new byte[bounds.volume()];
        this.skyLight = new byte[bounds.volume()];
        int sectionCapacity = bounds.widthBlocks() / 16 * bounds.depthBlocks() / 16 * bounds.sectionCount();
        this.dirtyBlockSections = new BitSet(sectionCapacity);
        this.dirtySkySections = new BitSet(sectionCapacity);
        this.sectionWidth = bounds.widthBlocks() >> 4;
        this.sectionsPerPlane = sectionWidth * sectionWidth;
        this.coreLocalChunkStart = bounds.haloChunks();
        this.coreLocalChunkEnd = bounds.haloChunks() + bounds.regionChunks();
        this.offsetPosX = 1;
        this.offsetNegX = -1;
        this.offsetPosZ = bounds.widthBlocks();
        this.offsetNegZ = -bounds.widthBlocks();
        this.offsetPosY = bounds.area();
        this.offsetNegY = -bounds.area();
    }

    public int index(int worldX, int worldY, int worldZ) {
        int localX = worldX - bounds.minBlockX();
        int localY = worldY - bounds.minBuildY();
        int localZ = worldZ - bounds.minBlockZ();
        return localX + localZ * bounds.widthBlocks() + localY * bounds.area();
    }

    public int localIndex(int localX, int localY, int localZ) {
        return localX + localZ * bounds.widthBlocks() + localY * bounds.area();
    }

    public boolean isInside(int worldX, int worldY, int worldZ) {
        return bounds.containsBlock(worldX, worldY, worldZ);
    }

    public int sectionLinearIndex(int worldX, int sectionY, int worldZ) {
        int chunkLocalX = (worldX - bounds.minBlockX()) >> 4;
        int chunkLocalZ = (worldZ - bounds.minBlockZ()) >> 4;
        int sectionLocalY = bounds.sectionIndex(sectionY);
        return chunkLocalX + chunkLocalZ * sectionWidth + sectionLocalY * sectionsPerPlane;
    }

    public void markDirtyBlock(int worldX, int sectionY, int worldZ) {
        dirtyBlockSections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public void markDirtySky(int worldX, int sectionY, int worldZ) {
        dirtySkySections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public void markDirtyBlockLocal(int localX, int localY, int localZ) {
        dirtyBlockSections.set(sectionLinearIndexLocal(localX, localY, localZ));
    }

    public void markDirtySkyLocal(int localX, int localY, int localZ) {
        dirtySkySections.set(sectionLinearIndexLocal(localX, localY, localZ));
    }

    public int sectionLinearIndexLocal(int localX, int localY, int localZ) {
        return (localX >> 4) + (localZ >> 4) * sectionWidth + (localY >> 4) * sectionsPerPlane;
    }

    public void markCoreBlockSectionsDirty() {
        markCoreSectionsDirty(dirtyBlockSections);
    }

    public void markCoreSkySectionsDirty() {
        markCoreSectionsDirty(dirtySkySections);
    }

    private void markCoreSectionsDirty(BitSet mask) {
        for (int sectionY = 0; sectionY < bounds.sectionCount(); sectionY++) {
            int sectionBase = sectionY * sectionsPerPlane;
            for (int chunkZ = coreLocalChunkStart; chunkZ < coreLocalChunkEnd; chunkZ++) {
                int rowStart = sectionBase + chunkZ * sectionWidth + coreLocalChunkStart;
                mask.set(rowStart, rowStart + bounds.regionChunks());
            }
        }
    }

    public int sectionBaseIndex(int sectionWorldX, int sectionY, int sectionWorldZ) {
        return index(sectionWorldX, sectionY << 4, sectionWorldZ);
    }

    public SectionPos sectionPosFromLinear(int linear) {
        int sectionY = linear / sectionsPerPlane;
        int rem = linear - sectionY * sectionsPerPlane;
        int localChunkZ = rem / sectionWidth;
        int localChunkX = rem - localChunkZ * sectionWidth;
        return SectionPos.of(
                SectionPos.blockToSectionCoord(bounds.minBlockX()) + localChunkX,
                bounds.minSectionY() + sectionY,
                SectionPos.blockToSectionCoord(bounds.minBlockZ()) + localChunkZ
        );
    }

    public byte[] selectArray(boolean sky) {
        return sky ? skyLight : blockLight;
    }

    public int localX(int index) {
        return index % bounds.widthBlocks();
    }

    public int localY(int index) {
        return index / bounds.area();
    }

    public int localZ(int index) {
        int rem = index - localY(index) * bounds.area();
        return rem / bounds.widthBlocks();
    }

    public void markDirtyBlockIndex(int index) {
        int localY = index / bounds.area();
        int rem = index - localY * bounds.area();
        int localZ = rem / bounds.widthBlocks();
        int localX = rem - localZ * bounds.widthBlocks();
        markDirtyBlockLocal(localX, localY, localZ);
    }

    public void markDirtySkyIndex(int index) {
        int localY = index / bounds.area();
        int rem = index - localY * bounds.area();
        int localZ = rem / bounds.widthBlocks();
        int localX = rem - localZ * bounds.widthBlocks();
        markDirtySkyLocal(localX, localY, localZ);
    }

    public void clearLight() {
        java.util.Arrays.fill(blockLight, (byte) 0);
        java.util.Arrays.fill(skyLight, (byte) 0);
        clearDirty();
    }

    public void clearDirty() {
        dirtyBlockSections.clear();
        dirtySkySections.clear();
    }

    public void resetForReuse() {
        java.util.Arrays.fill(opacity, (byte) 0);
        java.util.Arrays.fill(emission, (byte) 0);
        clearLight();
    }
}
