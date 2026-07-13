package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import dev.denismasterherobrine.lucis.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucis.light.util.IntRingQueue;

public final class LucisBlockLightEngine {
    private static final int REMOVAL_LEVEL_BITS = 4;
    private static final int REMOVAL_LEVEL_MASK = (1 << REMOVAL_LEVEL_BITS) - 1;

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
            blockLight[index] = (byte) emission;
            if (emission <= 0) {
                continue;
            }
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

    public void applyRuntimeChanges(RegionLightData data, RuntimeLightChangeBuffer changes) {
        Queues queues = this.queues.get();
        IntBucketQueue queue = queues.lightQueue;
        IntRingQueue removals = queues.removals;
        queue.clear();
        removals.clear();

        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            int index = RuntimeLightChangeBuffer.localIndex(change);
            int oldLight = data.blockLight[index] & 0xF;
            int oldOpacity = RuntimeLightChangeBuffer.oldOpacity(change);
            int newOpacity = RuntimeLightChangeBuffer.newOpacity(change);
            int oldEmission = RuntimeLightChangeBuffer.oldEmission(change);
            int newEmission = RuntimeLightChangeBuffer.newEmission(change);

            data.blockLight[index] = (byte) newEmission;
            data.markDirtyBlockIndex(index);

            if (oldLight > 0 && (newEmission < oldEmission || newOpacity > oldOpacity)) {
                enqueueRemoval(removals, index, oldLight);
            }

            if (newEmission > 1) {
                queue.enqueue(newEmission, index);
            }

            enqueueNeighborAdds(data, queue, index);
        }

        processRemovals(data, queue, removals);
        processAdds(data, queue);
    }

    public void applyRuntimeChangesFast(RegionLightData data, RuntimeLightChangeBuffer changes) {
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            if (RuntimeLightChangeBuffer.newEmission(change) < RuntimeLightChangeBuffer.oldEmission(change)
                    || RuntimeLightChangeBuffer.newOpacity(change) != RuntimeLightChangeBuffer.oldOpacity(change)) {
                applyRuntimeChanges(data, changes);
                return;
            }
        }

        IntBucketQueue queue = queues.get().lightQueue;
        queue.clear();
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            int index = RuntimeLightChangeBuffer.localIndex(change);
            int emission = RuntimeLightChangeBuffer.newEmission(change);
            int oldLight = data.blockLight[index] & 0xF;
            if (emission <= oldLight) {
                continue;
            }
            data.blockLight[index] = (byte) emission;
            data.markDirtyBlockIndex(index);
            if (emission > 1) {
                queue.enqueue(emission, index);
            }
        }

        processAdds(data, queue);
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
        int candidate = light - propagationCost(data.opacity[nextIndex] & 0xF);
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

    private void enqueueRemoval(IntRingQueue removals, int index, int lightLevel) {
        if (lightLevel <= 0) {
            return;
        }
        assert index >= 0 && index < (1 << (Integer.SIZE - REMOVAL_LEVEL_BITS));
        assert lightLevel <= REMOVAL_LEVEL_MASK;
        removals.enqueue((index << REMOVAL_LEVEL_BITS) | lightLevel);
    }

    private void processRemovals(RegionLightData data, IntBucketQueue queue, IntRingQueue removals) {
        int packed;
        while ((packed = removals.poll()) != Integer.MIN_VALUE) {
            int index = packed >>> REMOVAL_LEVEL_BITS;
            int removedLevel = packed & REMOVAL_LEVEL_MASK;
            int x = data.localX(index);
            int y = data.localY(index);
            int z = data.localZ(index);

            tryRemoveNeighbor(data, queue, removals, removedLevel, x + 1, y, z, index + data.offsetPosX);
            tryRemoveNeighbor(data, queue, removals, removedLevel, x - 1, y, z, index + data.offsetNegX);
            tryRemoveNeighbor(data, queue, removals, removedLevel, x, y, z + 1, index + data.offsetPosZ);
            tryRemoveNeighbor(data, queue, removals, removedLevel, x, y, z - 1, index + data.offsetNegZ);
            tryRemoveNeighbor(data, queue, removals, removedLevel, x, y + 1, z, index + data.offsetPosY);
            tryRemoveNeighbor(data, queue, removals, removedLevel, x, y - 1, z, index + data.offsetNegY);

            int emission = data.emission[index] & 0xF;
            if (emission > 0) {
                data.blockLight[index] = (byte) emission;
                if (emission > 1) {
                    queue.enqueue(emission, index);
                }
            }
        }
    }

    private void tryRemoveNeighbor(RegionLightData data, IntBucketQueue queue, IntRingQueue removals,
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
            enqueueRemoval(removals, nextIndex, neighborLight);
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

    private void enqueueNeighborAdds(RegionLightData data, IntBucketQueue queue, int index) {
        int x = data.localX(index);
        int y = data.localY(index);
        int z = data.localZ(index);
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
        int candidate = current - propagationCost(data.opacity[nextIndex] & 0xF);
        if (candidate > (data.blockLight[nextIndex] & 0xF)) {
            data.blockLight[nextIndex] = (byte) candidate;
            data.markDirtyBlockLocal(nextX, nextY, nextZ);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private static int propagationCost(int opacity) {
        return opacity <= 0 ? 1 : opacity;
    }

    private static final class Queues {
        private final IntBucketQueue lightQueue = new IntBucketQueue(16, 4096);
        private final IntRingQueue removals = new IntRingQueue(4096);
    }
}
