package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.light.LucisConstants;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionLightData;
import dev.denismasterherobrine.lucisrevisited.light.runtime.BoundaryCellChange;
import dev.denismasterherobrine.lucisrevisited.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucisrevisited.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarkSupport;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        IntBucketQueue queue = queues.get();
        queue.clear();
        Map<Integer, Integer> columns = collectColumns(data, changes);
        LucisBenchmarkSupport.count("lucis.sky.runtime.columns", columns.size());
        recomputeColumnsShallow(data, columns);
        LucisBenchmarkSupport.count("lucis.sky.runtime.shallow");
    }

    public void applyBoundaryChanges(RegionLightData data, List<BoundaryCellChange> changes) {
        IntBucketQueue queue = queues.get();
        queue.clear();
        Set<Integer> columns = collectBoundaryColumns(data, changes);
        clearColumns(data, columns);
        for (BoundaryCellChange change : changes) {
            data.skyLight[change.index()] = change.newSky();
        }
        recomputeColumns(data, columns, queue);
        seedPerimeter(data, columns, queue);
        spread(data, queue);
    }

    private Set<Integer> collectBoundaryColumns(RegionLightData data, List<BoundaryCellChange> changes) {
        Set<Integer> columns = new HashSet<>();
        for (BoundaryCellChange change : changes) {
            int x = data.localX(change.index());
            int z = data.localZ(change.index());
            addColumn(columns, data, x, z);
            if (x == 0) {
                addColumn(columns, data, x + 1, z);
            } else if (x == data.bounds.widthBlocks() - 1) {
                addColumn(columns, data, x - 1, z);
            }
            if (z == 0) {
                addColumn(columns, data, x, z + 1);
            } else if (z == data.bounds.depthBlocks() - 1) {
                addColumn(columns, data, x, z - 1);
            }
        }
        return columns;
    }

    private void addColumn(Set<Integer> columns, RegionLightData data, int x, int z) {
        if (x >= 0 && x < data.bounds.widthBlocks() && z >= 0 && z < data.bounds.depthBlocks()) {
            columns.add(x + z * data.bounds.widthBlocks());
        }
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

    private void clearColumns(RegionLightData data, Set<Integer> columns) {
        for (int column : columns) {
            int localZ = column / data.bounds.widthBlocks();
            int localX = column % data.bounds.widthBlocks();
            for (int localY = 0; localY < data.bounds.heightBlocks(); localY++) {
                int index = localX + localZ * data.bounds.widthBlocks() + localY * data.bounds.area();
                data.skyLight[index] = 0;
                data.markDirtySkyIndex(index);
            }
        }
    }

    private void recomputeColumns(RegionLightData data, Set<Integer> columns, IntBucketQueue queue) {
        for (int column : columns) {
            int localZ = column / data.bounds.widthBlocks();
            int localX = column % data.bounds.widthBlocks();
            int worldX = data.bounds.minBlockX() + localX;
            int worldZ = data.bounds.minBlockZ() + localZ;
            int light = LucisConstants.MAX_LIGHT;
            for (int worldY = data.bounds.maxBuildY() - 1; worldY >= data.bounds.minBuildY(); worldY--) {
                int index = data.index(worldX, worldY, worldZ);
                int opacity = data.opacity[index] & 0xF;
                if (opacity == 0) {
                    data.skyLight[index] = (byte) light;
                } else {
                    light = Math.max(0, light - opacity);
                    data.skyLight[index] = (byte) light;
                }
                data.markDirtySky(worldX, worldY >> 4, worldZ);
                if (light > 1) {
                    queue.enqueue(light, index);
                }
                if (light <= 0) {
                    break;
                }
            }
        }
    }

    private void recomputeColumnsFast(RegionLightData data, Map<Integer, Integer> columns) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int maxY = data.bounds.heightBlocks() - 1;
        for (Map.Entry<Integer, Integer> entry : columns.entrySet()) {
            int column = entry.getKey();
            int localZ = column / width;
            int localX = column - localZ * width;
            int base = localX + localZ * width;
            int startY = Math.min(entry.getValue(), maxY);
            int light = startY == maxY ? LucisConstants.MAX_LIGHT : (data.skyLight[base + (startY + 1) * area] & 0xF);
            for (int localY = startY; localY >= 0; localY--) {
                int index = base + localY * area;
                int opacity = data.opacity[index] & 0xF;
                int next = opacity == 0 ? light : Math.max(0, light - opacity);
                int old = data.skyLight[index] & 0xF;
                if (old != next) {
                    data.skyLight[index] = (byte) next;
                    markDirtySkyLocal(data, localX, localY, localZ);
                } else if (old == next && localY < startY && opacity == 0) {
                    break;
                }
                light = next;
                if (light <= 0) {
                    clearBelow(data, localX, localY - 1, localZ, base, area);
                    break;
                }
            }
        }
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

    private void clearBelow(RegionLightData data, int localX, int fromLocalY, int localZ, int base, int area) {
        for (int localY = fromLocalY; localY >= 0; localY--) {
            int index = base + localY * area;
            if (data.skyLight[index] == 0) {
                continue;
            }
            data.skyLight[index] = 0;
            markDirtySkyLocal(data, localX, localY, localZ);
        }
    }

    private void lateralFillChangedColumns(RegionLightData data, Set<Integer> columns) {
        int width = data.bounds.widthBlocks();
        int height = data.bounds.heightBlocks();
        for (int column : columns) {
            int localZ = column / width;
            int localX = column % width;
            for (int localY = 0; localY < height; localY++) {
                int index = localX + localZ * width + localY * data.bounds.area();
                int current = data.skyLight[index] & 0xF;
                int candidate = lateralCandidate(data, localX, localY, localZ, index);
                if (candidate > current) {
                    data.skyLight[index] = (byte) candidate;
                    markDirtySkyLocal(data, localX, localY, localZ);
                }
            }
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

    private void seedPerimeter(RegionLightData data, Set<Integer> columns, IntBucketQueue queue) {
        int width = data.bounds.widthBlocks();
        for (int column : columns) {
            int z = column / width;
            int x = column % width;
            seedNeighborColumn(data, columns, x + 1, z, queue);
            seedNeighborColumn(data, columns, x - 1, z, queue);
            seedNeighborColumn(data, columns, x, z + 1, queue);
            seedNeighborColumn(data, columns, x, z - 1, queue);
        }
    }

    private void seedNeighborColumn(RegionLightData data, Set<Integer> selected, int localX, int localZ, IntBucketQueue queue) {
        if (localX < 0 || localX >= data.bounds.widthBlocks() || localZ < 0 || localZ >= data.bounds.depthBlocks()) {
            return;
        }
        int key = localX + localZ * data.bounds.widthBlocks();
        if (selected.contains(key)) {
            return;
        }
        for (int localY = 0; localY < data.bounds.heightBlocks(); localY++) {
            int index = localX + localZ * data.bounds.widthBlocks() + localY * data.bounds.area();
            int light = data.skyLight[index] & 0xF;
            if (light > 1) {
                queue.enqueue(light, index);
            }
        }
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
