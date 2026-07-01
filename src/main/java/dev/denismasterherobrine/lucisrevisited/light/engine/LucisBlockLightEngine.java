package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.light.LucisConstants;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionLightData;
import dev.denismasterherobrine.lucisrevisited.light.runtime.BoundaryCellChange;
import dev.denismasterherobrine.lucisrevisited.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucisrevisited.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucisrevisited.light.util.IntRingQueue;

import java.util.List;

public final class LucisBlockLightEngine {
    private final ThreadLocal<Queues> queues = ThreadLocal.withInitial(Queues::new);

    public void compute(RegionLightData data) {
        Queues queues = this.queues.get();
        IntBucketQueue queue = queues.lightQueue;
        queue.clear();
        RegionBounds bounds = data.bounds;

        for (int worldY = bounds.minBuildY(); worldY < bounds.maxBuildY(); worldY++) {
            int localY = worldY - bounds.minBuildY();
            for (int worldZ = bounds.minBlockZ(); worldZ < bounds.maxBlockZExclusive(); worldZ++) {
                int localZ = worldZ - bounds.minBlockZ();
                for (int worldX = bounds.minBlockX(); worldX < bounds.maxBlockXExclusive(); worldX++) {
                    int index = data.index(worldX, worldY, worldZ);
                    int emission = data.emission[index] & 0xF;
                    data.blockLight[index] = (byte) emission;
                    data.markDirtyBlock(worldX, worldY >> 4, worldZ);
                    if (emission > 0) {
                        queue.enqueue(emission, index);
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            int index = queue.dequeue();
            int current = data.blockLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }

            int y = index / bounds.area();
            int rem = index - y * bounds.area();
            int z = rem / bounds.widthBlocks();
            int x = rem - z * bounds.widthBlocks();

            trySpread(data, queue, current, x + 1, y, z, index + data.offsetPosX);
            trySpread(data, queue, current, x - 1, y, z, index + data.offsetNegX);
            trySpread(data, queue, current, x, y, z + 1, index + data.offsetPosZ);
            trySpread(data, queue, current, x, y, z - 1, index + data.offsetNegZ);
            trySpread(data, queue, current, x, y + 1, z, index + data.offsetPosY);
            trySpread(data, queue, current, x, y - 1, z, index + data.offsetNegY);
        }
    }

    public void applyRuntimeChanges(RegionLightData data, List<RuntimeLightChange> changes) {
        Queues queues = this.queues.get();
        IntBucketQueue queue = queues.lightQueue;
        IntRingQueue removeIndices = queues.removeIndices;
        IntRingQueue removeLevels = queues.removeLevels;
        queue.clear();
        removeIndices.clear();
        removeLevels.clear();

        for (RuntimeLightChange change : changes) {
            int index = data.index(change.worldX(), change.worldY(), change.worldZ());
            int oldLight = data.blockLight[index] & 0xF;
            int oldOpacity = change.oldOpacity() & 0xF;
            int newOpacity = change.newOpacity() & 0xF;
            int oldEmission = change.oldEmission() & 0xF;
            int newEmission = change.newEmission() & 0xF;

            data.blockLight[index] = (byte) newEmission;
            data.markDirtyBlock(change.worldX(), change.worldY() >> 4, change.worldZ());

            if (oldLight > 0 && (newEmission < oldEmission || newOpacity > oldOpacity)) {
                enqueueRemoval(removeIndices, removeLevels, index, oldLight);
            }

            if (newEmission > 0) {
                queue.enqueue(newEmission, index);
            }

            enqueueNeighborAdds(data, index);
        }

        processRemovals(data, queue, removeIndices, removeLevels);
        processAdds(data, queue);
    }

    public void applyRuntimeChangesFast(RegionLightData data, List<RuntimeLightChange> changes) {
        for (RuntimeLightChange change : changes) {
            int index = data.index(change.worldX(), change.worldY(), change.worldZ());
            int emission = change.newEmission() & 0xF;
            data.blockLight[index] = (byte) emission;
            data.markDirtyBlock(change.worldX(), change.worldY() >> 4, change.worldZ());
            if (emission <= 1) {
                clearImmediateNeighbors(data, index);
            } else {
                lightImmediateNeighbors(data, index, emission - 1);
            }
        }
    }

    private void clearImmediateNeighbors(RegionLightData data, int index) {
        int x = data.localX(index);
        int y = data.localY(index);
        int z = data.localZ(index);
        setNeighborLight(data, 0, x + 1, y, z, index + data.offsetPosX);
        setNeighborLight(data, 0, x - 1, y, z, index + data.offsetNegX);
        setNeighborLight(data, 0, x, y, z + 1, index + data.offsetPosZ);
        setNeighborLight(data, 0, x, y, z - 1, index + data.offsetNegZ);
        setNeighborLight(data, 0, x, y + 1, z, index + data.offsetPosY);
        setNeighborLight(data, 0, x, y - 1, z, index + data.offsetNegY);
    }

    private void lightImmediateNeighbors(RegionLightData data, int index, int light) {
        int x = data.localX(index);
        int y = data.localY(index);
        int z = data.localZ(index);
        trySetNeighborLight(data, light, x + 1, y, z, index + data.offsetPosX);
        trySetNeighborLight(data, light, x - 1, y, z, index + data.offsetNegX);
        trySetNeighborLight(data, light, x, y, z + 1, index + data.offsetPosZ);
        trySetNeighborLight(data, light, x, y, z - 1, index + data.offsetNegZ);
        trySetNeighborLight(data, light, x, y + 1, z, index + data.offsetPosY);
        trySetNeighborLight(data, light, x, y - 1, z, index + data.offsetNegY);
    }

    private void trySetNeighborLight(RegionLightData data, int light, int nextX, int nextY, int nextZ, int nextIndex) {
        if (nextX < 0 || nextX >= data.bounds.widthBlocks() || nextZ < 0 || nextZ >= data.bounds.depthBlocks()
                || nextY < 0 || nextY >= data.bounds.heightBlocks()) {
            return;
        }
        int candidate = Math.max(0, light - (data.opacity[nextIndex] & 0xF));
        if (candidate > (data.blockLight[nextIndex] & 0xF)) {
            setNeighborLight(data, candidate, nextX, nextY, nextZ, nextIndex);
        }
    }

    private void setNeighborLight(RegionLightData data, int light, int nextX, int nextY, int nextZ, int nextIndex) {
        if (nextX < 0 || nextX >= data.bounds.widthBlocks() || nextZ < 0 || nextZ >= data.bounds.depthBlocks()
                || nextY < 0 || nextY >= data.bounds.heightBlocks()) {
            return;
        }
        if ((data.blockLight[nextIndex] & 0xF) != light) {
            data.blockLight[nextIndex] = (byte) light;
            data.markDirtyBlockIndex(nextIndex);
        }
    }

    public void applyBoundaryChanges(RegionLightData data, List<BoundaryCellChange> changes) {
        Queues queues = this.queues.get();
        IntBucketQueue queue = queues.lightQueue;
        IntRingQueue removeIndices = queues.removeIndices;
        IntRingQueue removeLevels = queues.removeLevels;
        queue.clear();
        removeIndices.clear();
        removeLevels.clear();

        for (BoundaryCellChange change : changes) {
            int oldLight = change.oldBlock() & 0xF;
            int newLight = change.newBlock() & 0xF;
            data.blockLight[change.index()] = change.newBlock();
            if (newLight < oldLight) {
                enqueueRemoval(removeIndices, removeLevels, change.index(), oldLight);
            }
            if (newLight > 0) {
                queue.enqueue(newLight, change.index());
            }
            enqueueNeighborAdds(data, change.index());
        }

        processRemovals(data, queue, removeIndices, removeLevels);
        processAdds(data, queue);
    }

    private void enqueueRemoval(IntRingQueue removeIndices, IntRingQueue removeLevels, int index, int lightLevel) {
        if (lightLevel <= 0) {
            return;
        }
        removeIndices.enqueue(index);
        removeLevels.enqueue(lightLevel);
    }

    private void processRemovals(RegionLightData data, IntBucketQueue queue, IntRingQueue removeIndices, IntRingQueue removeLevels) {
        while (!removeIndices.isEmpty()) {
            int index = removeIndices.dequeue();
            int removedLevel = removeLevels.dequeue();
            int x = data.localX(index);
            int y = data.localY(index);
            int z = data.localZ(index);

            tryRemoveNeighbor(data, queue, removeIndices, removeLevels, removedLevel, x + 1, y, z, index + data.offsetPosX);
            tryRemoveNeighbor(data, queue, removeIndices, removeLevels, removedLevel, x - 1, y, z, index + data.offsetNegX);
            tryRemoveNeighbor(data, queue, removeIndices, removeLevels, removedLevel, x, y, z + 1, index + data.offsetPosZ);
            tryRemoveNeighbor(data, queue, removeIndices, removeLevels, removedLevel, x, y, z - 1, index + data.offsetNegZ);
            tryRemoveNeighbor(data, queue, removeIndices, removeLevels, removedLevel, x, y + 1, z, index + data.offsetPosY);
            tryRemoveNeighbor(data, queue, removeIndices, removeLevels, removedLevel, x, y - 1, z, index + data.offsetNegY);

            int emission = data.emission[index] & 0xF;
            if (emission > 0) {
                data.blockLight[index] = (byte) emission;
                queue.enqueue(emission, index);
            }
        }
    }

    private void tryRemoveNeighbor(RegionLightData data, IntBucketQueue queue, IntRingQueue removeIndices, IntRingQueue removeLevels,
                                   int removedLevel, int nextX, int nextY, int nextZ, int nextIndex) {
        RegionBounds bounds = data.bounds;
        if (nextX < 0 || nextX >= bounds.widthBlocks() || nextZ < 0 || nextZ >= bounds.depthBlocks() || nextY < 0 || nextY >= bounds.heightBlocks()) {
            return;
        }

        int neighborLight = data.blockLight[nextIndex] & 0xF;
        if (neighborLight == 0) {
            return;
        }

        if (neighborLight < removedLevel) {
            data.blockLight[nextIndex] = 0;
            data.markDirtyBlockIndex(nextIndex);
            enqueueRemoval(removeIndices, removeLevels, nextIndex, neighborLight);
        } else {
            queue.enqueue(neighborLight, nextIndex);
        }
    }

    private void processAdds(RegionLightData data, IntBucketQueue queue) {
        while (!queue.isEmpty()) {
            int index = queue.dequeue();
            int current = data.blockLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }

            int y = index / data.bounds.area();
            int rem = index - y * data.bounds.area();
            int z = rem / data.bounds.widthBlocks();
            int x = rem - z * data.bounds.widthBlocks();

            trySpread(data, queue, current, x + 1, y, z, index + data.offsetPosX);
            trySpread(data, queue, current, x - 1, y, z, index + data.offsetNegX);
            trySpread(data, queue, current, x, y, z + 1, index + data.offsetPosZ);
            trySpread(data, queue, current, x, y, z - 1, index + data.offsetNegZ);
            trySpread(data, queue, current, x, y + 1, z, index + data.offsetPosY);
            trySpread(data, queue, current, x, y - 1, z, index + data.offsetNegY);
        }
    }

    private void enqueueNeighborAdds(RegionLightData data, int index) {
        int x = data.localX(index);
        int y = data.localY(index);
        int z = data.localZ(index);
        IntBucketQueue queue = queues.get().lightQueue;
        enqueueIfLit(data, queue, x + 1, y, z, index + data.offsetPosX);
        enqueueIfLit(data, queue, x - 1, y, z, index + data.offsetNegX);
        enqueueIfLit(data, queue, x, y, z + 1, index + data.offsetPosZ);
        enqueueIfLit(data, queue, x, y, z - 1, index + data.offsetNegZ);
        enqueueIfLit(data, queue, x, y + 1, z, index + data.offsetPosY);
        enqueueIfLit(data, queue, x, y - 1, z, index + data.offsetNegY);
    }

    private void enqueueIfLit(RegionLightData data, IntBucketQueue queue, int nextX, int nextY, int nextZ, int nextIndex) {
        RegionBounds bounds = data.bounds;
        if (nextX < 0 || nextX >= bounds.widthBlocks() || nextZ < 0 || nextZ >= bounds.depthBlocks() || nextY < 0 || nextY >= bounds.heightBlocks()) {
            return;
        }
        int light = data.blockLight[nextIndex] & 0xF;
        if (light > 0) {
            queue.enqueue(light, nextIndex);
        }
    }

    private void trySpread(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ, int nextIndex) {
        RegionBounds bounds = data.bounds;
        if (nextX < 0 || nextX >= bounds.widthBlocks() || nextZ < 0 || nextZ >= bounds.depthBlocks() || nextY < 0 || nextY >= bounds.heightBlocks()) {
            return;
        }

        int candidate = Math.max(0, current - 1 - (data.opacity[nextIndex] & 0xF));
        if (candidate > (data.blockLight[nextIndex] & 0xF)) {
            data.blockLight[nextIndex] = (byte) candidate;
            int worldX = bounds.minBlockX() + nextX;
            int worldY = bounds.minBuildY() + nextY;
            int worldZ = bounds.minBlockZ() + nextZ;
            data.markDirtyBlock(worldX, worldY >> 4, worldZ);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private static final class Queues {
        private final IntBucketQueue lightQueue = new IntBucketQueue(16, 4096);
        private final IntRingQueue removeIndices = new IntRingQueue(4096);
        private final IntRingQueue removeLevels = new IntRingQueue(4096);
    }
}
