package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucis.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LucisSkyLightEngine {
    private final ThreadLocal<IntBucketQueue> queues = ThreadLocal.withInitial(() -> new IntBucketQueue(16, 4096));

    public void compute(RegionLightData data) {
        IntBucketQueue queue = queues.get();
        queue.clear();
        RegionBounds bounds = data.bounds;

        for (int worldZ = bounds.minBlockZ(); worldZ < bounds.maxBlockZExclusive(); worldZ++) {
            for (int worldX = bounds.minBlockX(); worldX < bounds.maxBlockXExclusive(); worldX++) {
                int light = LucisConstants.MAX_LIGHT;
                for (int worldY = bounds.maxBuildY() - 1; worldY >= bounds.minBuildY(); worldY--) {
                    int index = data.index(worldX, worldY, worldZ);
                    int opacity = data.opacity[index] & 0xF;
                    if (opacity == 0) {
                        data.skyLight[index] = (byte) light;
                    } else {
                        light = Math.max(0, light - opacity);
                        data.skyLight[index] = (byte) light;
                    }

                    if (light > 1) {
                        queue.enqueue(light, index);
                    }

                    data.markDirtySky(worldX, worldY >> 4, worldZ);
                    if (light <= 0) {
                        break;
                    }
                }
            }
        }

        spread(data, queue);
    }

    public void applyRuntimeChanges(RegionLightData data, List<RuntimeLightChange> changes) {
        Map<Integer, Integer> columns = collectColumns(data, changes);
        LucisBenchmarkSupport.count("lucis.sky.runtime.columns", columns.size());
        recomputeColumnsShallow(data, columns);
        LucisBenchmarkSupport.count("lucis.sky.runtime.shallow");
    }

    private Map<Integer, Integer> collectColumns(RegionLightData data, List<RuntimeLightChange> changes) {
        Map<Integer, Integer> columns = new HashMap<>();
        for (RuntimeLightChange change : changes) {
            int localX = change.worldX() - data.bounds.minBlockX();
            int localY = change.worldY() - data.bounds.minBuildY();
            int localZ = change.worldZ() - data.bounds.minBlockZ();
            if (localX >= 0 && localX < data.bounds.widthBlocks()
                    && localY >= 0 && localY < data.bounds.heightBlocks()
                    && localZ >= 0 && localZ < data.bounds.depthBlocks()) {
                int column = localX + localZ * data.bounds.widthBlocks();
                columns.merge(column, localY, Math::max);
            }
        }
        return columns;
    }

    private void recomputeColumnsShallow(RegionLightData data, Map<Integer, Integer> columns) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int maxY = data.bounds.heightBlocks() - 1;
        for (Map.Entry<Integer, Integer> entry : columns.entrySet()) {
            int column = entry.getKey();
            int localZ = column / width;
            int localX = column - localZ * width;
            int base = localX + localZ * width;
            int localY = Math.min(entry.getValue(), maxY);
            int above = localY == maxY ? LucisConstants.MAX_LIGHT : (data.skyLight[base + (localY + 1) * area] & 0xF);
            updateSkyCell(data, localX, localY, localZ, base + localY * area, above);
            if (localY > 0) {
                int index = base + (localY - 1) * area;
                int vertical = data.skyLight[base + localY * area] & 0xF;
                int opacity = data.opacity[index] & 0xF;
                int verticalCandidate = opacity == 0 ? vertical : Math.max(0, vertical - opacity);
                int lateral = lateralCandidate(data, localX, localY - 1, localZ, index);
                setSkyCell(data, localX, localY - 1, localZ, index, Math.max(verticalCandidate, lateral));
            }
        }
    }

    private void setSkyCell(RegionLightData data, int localX, int localY, int localZ, int index, int next) {
        if ((data.skyLight[index] & 0xF) != next) {
            data.skyLight[index] = (byte) next;
            markDirtySkyLocal(data, localX, localY, localZ);
        }
    }

    private void updateSkyCell(RegionLightData data, int localX, int localY, int localZ, int index, int incoming) {
        int opacity = data.opacity[index] & 0xF;
        int next = opacity == 0 ? incoming : Math.max(0, incoming - opacity);
        if ((data.skyLight[index] & 0xF) != next) {
            data.skyLight[index] = (byte) next;
            markDirtySkyLocal(data, localX, localY, localZ);
        }
    }

    private void markDirtySkyLocal(RegionLightData data, int localX, int localY, int localZ) {
        int sectionWidth = data.bounds.widthBlocks() >> 4;
        int sectionLocalY = localY >> 4;
        int linear = (localX >> 4) + (localZ >> 4) * sectionWidth + sectionLocalY * sectionWidth * sectionWidth;
        data.dirtySkySections.set(linear);
    }

    private int lateralCandidate(RegionLightData data, int localX, int localY, int localZ, int index) {
        int opacity = data.opacity[index] & 0xF;
        if (opacity >= LucisConstants.MAX_LIGHT) {
            return 0;
        }
        int best = 0;
        best = Math.max(best, neighborSky(data, localX + 1, localY, localZ, index + data.offsetPosX));
        best = Math.max(best, neighborSky(data, localX - 1, localY, localZ, index + data.offsetNegX));
        best = Math.max(best, neighborSky(data, localX, localY, localZ + 1, index + data.offsetPosZ));
        best = Math.max(best, neighborSky(data, localX, localY, localZ - 1, index + data.offsetNegZ));
        return Math.max(0, best - 1 - opacity);
    }

    private int neighborSky(RegionLightData data, int localX, int localY, int localZ, int index) {
        if (localX < 0 || localX >= data.bounds.widthBlocks() || localZ < 0 || localZ >= data.bounds.depthBlocks()
                || localY < 0 || localY >= data.bounds.heightBlocks()) {
            return 0;
        }
        return data.skyLight[index] & 0xF;
    }

    private void spread(RegionLightData data, IntBucketQueue queue) {
        RegionBounds bounds = data.bounds;
        while (!queue.isEmpty()) {
            int index = queue.dequeue();
            int current = data.skyLight[index] & 0xF;
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
        }
    }

    private void trySpread(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ, int nextIndex) {
        RegionBounds bounds = data.bounds;
        if (nextX < 0 || nextX >= bounds.widthBlocks() || nextZ < 0 || nextZ >= bounds.depthBlocks() || nextY < 0 || nextY >= bounds.heightBlocks()) {
            return;
        }

        int candidate = Math.max(0, current - 1 - (data.opacity[nextIndex] & 0xF));
        if (candidate > (data.skyLight[nextIndex] & 0xF)) {
            data.skyLight[nextIndex] = (byte) candidate;
            int worldX = bounds.minBlockX() + nextX;
            int worldY = bounds.minBuildY() + nextY;
            int worldZ = bounds.minBlockZ() + nextZ;
            data.markDirtySky(worldX, worldY >> 4, worldZ);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }
}
