package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucis.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucis.light.util.IntRingQueue;

import java.util.List;

public final class LucisBlockLightEngine {
    private final ThreadLocal<Queues> queues = ThreadLocal.withInitial(Queues::new);

    public void compute(RegionLightData data) {
        Queues queues = this.queues.get();
        IntBucketQueue queue = queues.lightQueue;
        queue.clear();
        RegionBounds bounds = data.bounds;
        byte[] emissionArray = data.emission;
        byte[] blockLight = data.blockLight;
        int volume = bounds.volume();
        int area = bounds.area();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();

        for (int index = 0; index < volume; index++) {
            int emission = emissionArray[index] & 0xF;
            if (emission <= 0) {
                continue;
            }
            blockLight[index] = (byte) emission;
            if (emission > 1) {
                queue.enqueue(emission, index);
            }
        }
        data.markCoreBlockSectionsDirty();

        int index;
        while ((index = queue.poll()) >= 0) {
            int current = data.blockLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }

            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;

            if (x + 1 < width) spreadTo(data, queue, current, x + 1, y, z, index + data.offsetPosX);
            if (x > 0) spreadTo(data, queue, current, x - 1, y, z, index + data.offsetNegX);
            if (z + 1 < depth) spreadTo(data, queue, current, x, y, z + 1, index + data.offsetPosZ);
            if (z > 0) spreadTo(data, queue, current, x, y, z - 1, index + data.offsetNegZ);
            if (y + 1 < height) spreadTo(data, queue, current, x, y + 1, z, index + data.offsetPosY);
            if (y > 0) spreadTo(data, queue, current, x, y - 1, z, index + data.offsetNegY);
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
        RegionBounds bounds = data.bounds;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int minBuildY = bounds.minBuildY();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();

        for (RuntimeLightChange change : changes) {
            int localX = change.worldX() - minBlockX;
            int localY = change.worldY() - minBuildY;
            int localZ = change.worldZ() - minBlockZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth || localY < 0 || localY >= height) {
                continue;
            }
            int index = data.localIndex(localX, localY, localZ);
            int oldLight = data.blockLight[index] & 0xF;
            int oldOpacity = change.oldOpacity() & 0xF;
            int newOpacity = change.newOpacity() & 0xF;
            int oldEmission = change.oldEmission() & 0xF;
            int newEmission = change.newEmission() & 0xF;

            data.blockLight[index] = (byte) newEmission;
            data.markDirtyBlockLocal(localX, localY, localZ);

            if (oldLight > 0 && (newEmission < oldEmission || newOpacity > oldOpacity)) {
                enqueueRemoval(removeIndices, removeLevels, index, oldLight);
            }

            if (newEmission > 1) {
                queue.enqueue(newEmission, index);
            }

            enqueueNeighborAdds(data, index);
        }

        processRemovals(data, queue, removeIndices, removeLevels);
        processAdds(data, queue);
    }

    public void applyRuntimeChangesFast(RegionLightData data, List<RuntimeLightChange> changes) {
        RegionBounds bounds = data.bounds;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int minBuildY = bounds.minBuildY();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();
        for (RuntimeLightChange change : changes) {
            int localX = change.worldX() - minBlockX;
            int localY = change.worldY() - minBuildY;
            int localZ = change.worldZ() - minBlockZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth || localY < 0 || localY >= height) {
                continue;
            }
            int index = data.localIndex(localX, localY, localZ);
            int emission = change.newEmission() & 0xF;
            data.blockLight[index] = (byte) emission;
            data.markDirtyBlockLocal(localX, localY, localZ);
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
        int candidate = light - (data.opacity[nextIndex] & 0xF);
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
            data.markDirtyBlockLocal(nextX, nextY, nextZ);
        }
    }

    private void enqueueRemoval(IntRingQueue removeIndices, IntRingQueue removeLevels, int index, int lightLevel) {
        if (lightLevel <= 0) {
            return;
        }
        removeIndices.enqueue(index);
        removeLevels.enqueue(lightLevel);
    }

    private void processRemovals(RegionLightData data, IntBucketQueue queue, IntRingQueue removeIndices, IntRingQueue removeLevels) {
        int index;
        while ((index = removeIndices.poll()) != Integer.MIN_VALUE) {
            int removedLevel = removeLevels.poll();
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
                if (emission > 1) {
                    queue.enqueue(emission, index);
                }
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
            if (neighborLight > 1) {
                queue.enqueue(neighborLight, nextIndex);
            }
        }
    }

    private void processAdds(RegionLightData data, IntBucketQueue queue) {
        RegionBounds bounds = data.bounds;
        int area = bounds.area();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();
        int index;
        while ((index = queue.poll()) >= 0) {
            int current = data.blockLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }

            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;

            if (x + 1 < width) spreadTo(data, queue, current, x + 1, y, z, index + data.offsetPosX);
            if (x > 0) spreadTo(data, queue, current, x - 1, y, z, index + data.offsetNegX);
            if (z + 1 < depth) spreadTo(data, queue, current, x, y, z + 1, index + data.offsetPosZ);
            if (z > 0) spreadTo(data, queue, current, x, y, z - 1, index + data.offsetNegZ);
            if (y + 1 < height) spreadTo(data, queue, current, x, y + 1, z, index + data.offsetPosY);
            if (y > 0) spreadTo(data, queue, current, x, y - 1, z, index + data.offsetNegY);
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
        if (light > 1) {
            queue.enqueue(light, nextIndex);
        }
    }

    private void spreadTo(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ, int nextIndex) {
        int candidate = current - 1 - (data.opacity[nextIndex] & 0xF);
        if (candidate > (data.blockLight[nextIndex] & 0xF)) {
            data.blockLight[nextIndex] = (byte) candidate;
            data.markDirtyBlockLocal(nextX, nextY, nextZ);
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
