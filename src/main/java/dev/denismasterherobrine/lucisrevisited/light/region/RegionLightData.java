package dev.denismasterherobrine.lucisrevisited.light.region;

import dev.denismasterherobrine.lucisrevisited.light.LucisConstants;
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
    public final BitSet nonEmptySections;
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
        this.nonEmptySections = new BitSet(sectionCapacity);
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

    public boolean isInside(int worldX, int worldY, int worldZ) {
        return bounds.containsBlock(worldX, worldY, worldZ);
    }

    public int sectionLinearIndex(int worldX, int sectionY, int worldZ) {
        int chunkLocalX = Math.floorDiv(worldX - bounds.minBlockX(), 16);
        int chunkLocalZ = Math.floorDiv(worldZ - bounds.minBlockZ(), 16);
        int sectionLocalY = bounds.sectionIndex(sectionY);
        int sectionWidth = bounds.widthBlocks() >> 4;
        return chunkLocalX + chunkLocalZ * sectionWidth + sectionLocalY * sectionWidth * sectionWidth;
    }

    public void markDirtyBlock(int worldX, int sectionY, int worldZ) {
        dirtyBlockSections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public void markDirtySky(int worldX, int sectionY, int worldZ) {
        dirtySkySections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public void markNonEmpty(int worldX, int sectionY, int worldZ) {
        nonEmptySections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public int sectionBaseIndex(int sectionWorldX, int sectionY, int sectionWorldZ) {
        return index(sectionWorldX, sectionY << 4, sectionWorldZ);
    }

    public SectionPos sectionPosFromLinear(int linear) {
        int sectionWidth = bounds.widthBlocks() >> 4;
        int areaSections = sectionWidth * sectionWidth;
        int sectionY = linear / areaSections;
        int rem = linear % areaSections;
        int localChunkZ = rem / sectionWidth;
        int localChunkX = rem % sectionWidth;
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
        int localX = localX(index);
        int localY = localY(index);
        int localZ = localZ(index);
        markDirtyBlock(bounds.minBlockX() + localX, bounds.minBuildY() + localY >> 4, bounds.minBlockZ() + localZ);
    }

    public void markDirtySkyIndex(int index) {
        int localX = localX(index);
        int localY = localY(index);
        int localZ = localZ(index);
        markDirtySky(bounds.minBlockX() + localX, bounds.minBuildY() + localY >> 4, bounds.minBlockZ() + localZ);
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
        nonEmptySections.clear();
    }
}
