package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.light.region.BoundaryDelta;
import dev.denismasterherobrine.lucisrevisited.light.region.BoundarySnapshot;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionLightData;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionSide;
import dev.denismasterherobrine.lucisrevisited.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucisrevisited.light.runtime.BoundaryCellChange;

import java.util.ArrayList;
import java.util.List;

public final class LucisBoundaryEngine {
    public List<BoundaryDelta> capture(RegionLightData data) {
        List<BoundaryDelta> deltas = new ArrayList<>(4);
        deltas.add(capture(data, RegionSide.WEST));
        deltas.add(capture(data, RegionSide.EAST));
        deltas.add(capture(data, RegionSide.NORTH));
        deltas.add(capture(data, RegionSide.SOUTH));
        return deltas;
    }

    public List<BoundaryCellChange> apply(RuntimeRegionState state) {
        List<BoundaryCellChange> changes = new ArrayList<>();
        for (BoundarySnapshot snapshot : state.drainBoundarySnapshots()) {
            changes.addAll(applySnapshot(state.data(), snapshot));
        }
        return changes;
    }

    public BoundarySnapshot toSnapshot(BoundaryDelta delta) {
        return new BoundarySnapshot(delta.side(), delta.blockLevels(), delta.skyLevels());
    }

    private BoundaryDelta capture(RegionLightData data, RegionSide side) {
        RegionBounds bounds = data.bounds;
        int faceSize = side == RegionSide.WEST || side == RegionSide.EAST
                ? bounds.depthBlocks() * bounds.heightBlocks()
                : bounds.widthBlocks() * bounds.heightBlocks();
        byte[] block = new byte[faceSize];
        byte[] sky = new byte[faceSize];
        int write = 0;

        switch (side) {
            case WEST -> {
                int worldX = bounds.originChunkX() << 4;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldZ = bounds.originChunkZ() << 4; worldZ < (bounds.originChunkZ() + bounds.regionChunks()) << 4; worldZ++) {
                        int index = data.index(worldX, worldY, worldZ);
                        block[write] = data.blockLight[index];
                        sky[write++] = data.skyLight[index];
                    }
                }
            }
            case EAST -> {
                int worldX = ((bounds.originChunkX() + bounds.regionChunks()) << 4) - 1;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldZ = bounds.originChunkZ() << 4; worldZ < (bounds.originChunkZ() + bounds.regionChunks()) << 4; worldZ++) {
                        int index = data.index(worldX, worldY, worldZ);
                        block[write] = data.blockLight[index];
                        sky[write++] = data.skyLight[index];
                    }
                }
            }
            case NORTH -> {
                int worldZ = bounds.originChunkZ() << 4;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldX = bounds.originChunkX() << 4; worldX < (bounds.originChunkX() + bounds.regionChunks()) << 4; worldX++) {
                        int index = data.index(worldX, worldY, worldZ);
                        block[write] = data.blockLight[index];
                        sky[write++] = data.skyLight[index];
                    }
                }
            }
            case SOUTH -> {
                int worldZ = ((bounds.originChunkZ() + bounds.regionChunks()) << 4) - 1;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldX = bounds.originChunkX() << 4; worldX < (bounds.originChunkX() + bounds.regionChunks()) << 4; worldX++) {
                        int index = data.index(worldX, worldY, worldZ);
                        block[write] = data.blockLight[index];
                        sky[write++] = data.skyLight[index];
                    }
                }
            }
        }

        return new BoundaryDelta(
                bounds.coreRegionKey(),
                targetRegionKey(bounds, side),
                side,
                bounds.minSectionY(),
                bounds.sectionCount(),
                bounds.widthBlocks(),
                bounds.depthBlocks(),
                block,
                sky
        );
    }

    private long targetRegionKey(RegionBounds bounds, RegionSide side) {
        return switch (side) {
            case WEST -> RegionBounds.regionKey(bounds.originChunkX() - bounds.regionChunks(), bounds.originChunkZ());
            case EAST -> RegionBounds.regionKey(bounds.originChunkX() + bounds.regionChunks(), bounds.originChunkZ());
            case NORTH -> RegionBounds.regionKey(bounds.originChunkX(), bounds.originChunkZ() - bounds.regionChunks());
            case SOUTH -> RegionBounds.regionKey(bounds.originChunkX(), bounds.originChunkZ() + bounds.regionChunks());
        };
    }

    private List<BoundaryCellChange> applySnapshot(RegionLightData data, BoundarySnapshot snapshot) {
        RegionBounds bounds = data.bounds;
        RegionSide haloSide = snapshot.side().opposite();
        int read = 0;
        List<BoundaryCellChange> changes = new ArrayList<>();
        switch (haloSide) {
            case WEST -> {
                int worldX = (bounds.originChunkX() << 4) - 1;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldZ = bounds.originChunkZ() << 4; worldZ < (bounds.originChunkZ() + bounds.regionChunks()) << 4; worldZ++) {
                        if (!data.isInside(worldX, worldY, worldZ)) {
                            read++;
                            continue;
                        }
                        int index = data.index(worldX, worldY, worldZ);
                        byte oldBlock = data.blockLight[index];
                        byte oldSky = data.skyLight[index];
                        byte newBlock = snapshot.blockLevels()[read];
                        byte newSky = snapshot.skyLevels()[read++];
                        data.blockLight[index] = newBlock;
                        data.skyLight[index] = newSky;
                        if (oldBlock != newBlock || oldSky != newSky) {
                            changes.add(new BoundaryCellChange(index, oldBlock, newBlock, oldSky, newSky));
                        }
                    }
                }
            }
            case EAST -> {
                int worldX = (bounds.originChunkX() + bounds.regionChunks()) << 4;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldZ = bounds.originChunkZ() << 4; worldZ < (bounds.originChunkZ() + bounds.regionChunks()) << 4; worldZ++) {
                        if (!data.isInside(worldX, worldY, worldZ)) {
                            read++;
                            continue;
                        }
                        int index = data.index(worldX, worldY, worldZ);
                        byte oldBlock = data.blockLight[index];
                        byte oldSky = data.skyLight[index];
                        byte newBlock = snapshot.blockLevels()[read];
                        byte newSky = snapshot.skyLevels()[read++];
                        data.blockLight[index] = newBlock;
                        data.skyLight[index] = newSky;
                        if (oldBlock != newBlock || oldSky != newSky) {
                            changes.add(new BoundaryCellChange(index, oldBlock, newBlock, oldSky, newSky));
                        }
                    }
                }
            }
            case NORTH -> {
                int worldZ = (bounds.originChunkZ() << 4) - 1;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldX = bounds.originChunkX() << 4; worldX < (bounds.originChunkX() + bounds.regionChunks()) << 4; worldX++) {
                        if (!data.isInside(worldX, worldY, worldZ)) {
                            read++;
                            continue;
                        }
                        int index = data.index(worldX, worldY, worldZ);
                        byte oldBlock = data.blockLight[index];
                        byte oldSky = data.skyLight[index];
                        byte newBlock = snapshot.blockLevels()[read];
                        byte newSky = snapshot.skyLevels()[read++];
                        data.blockLight[index] = newBlock;
                        data.skyLight[index] = newSky;
                        if (oldBlock != newBlock || oldSky != newSky) {
                            changes.add(new BoundaryCellChange(index, oldBlock, newBlock, oldSky, newSky));
                        }
                    }
                }
            }
            case SOUTH -> {
                int worldZ = (bounds.originChunkZ() + bounds.regionChunks()) << 4;
                for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
                    for (int worldX = bounds.originChunkX() << 4; worldX < (bounds.originChunkX() + bounds.regionChunks()) << 4; worldX++) {
                        if (!data.isInside(worldX, worldY, worldZ)) {
                            read++;
                            continue;
                        }
                        int index = data.index(worldX, worldY, worldZ);
                        byte oldBlock = data.blockLight[index];
                        byte oldSky = data.skyLight[index];
                        byte newBlock = snapshot.blockLevels()[read];
                        byte newSky = snapshot.skyLevels()[read++];
                        data.blockLight[index] = newBlock;
                        data.skyLight[index] = newSky;
                        if (oldBlock != newBlock || oldSky != newSky) {
                            changes.add(new BoundaryCellChange(index, oldBlock, newBlock, oldSky, newSky));
                        }
                    }
                }
            }
        }
        return changes;
    }
}
