package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucis.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;

import java.util.Arrays;
import java.util.List;

public final class LucisSkyLightEngine {
    private final ThreadLocal<IntBucketQueue> queues = ThreadLocal.withInitial(() -> new IntBucketQueue(16, 4096));
    private final ThreadLocal<RuntimeColumns> runtimeColumns = ThreadLocal.withInitial(RuntimeColumns::new);

    public void compute(RegionLightData data) {
        IntBucketQueue queue = queues.get();
        queue.clear();
        RegionBounds bounds = data.bounds;
        boolean metrics = LucisBenchmarkSupport.enabled();
        int oldSeedCandidates = 0;
        int seeds = 0;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int height = bounds.heightBlocks();

        for (int z = 0; z < depth; z++) {
            int columnZBase = z * width;
            for (int x = 0; x < width; x++) {
                int columnBase = columnZBase + x;
                int light = LucisConstants.MAX_LIGHT;
                for (int y = height - 1; y >= 0; y--) {
                    int index = columnBase + y * area;
                    int opacity = data.opacity[index] & 0xF;
                    if (opacity != 0) {
                        light -= opacity;
                        if (light < 0) {
                            light = 0;
                        }
                    }
                    data.skyLight[index] = (byte) light;
                    if (light > 1) {
                        seeds += seedKnownHorizontalFrontier(data, queue, light, x, z, index);
                    }

                    if (metrics) {
                        if (light > 1) {
                            oldSeedCandidates++;
                        }
                    }
                    if (light <= 0) {
                        break;
                    }
                }
            }
        }

        data.markCoreSkySectionsDirty();
        int dequeues = spread(data, queue, metrics);
        if (metrics) {
            LucisBenchmarkSupport.count("lucis.sky.old_seed_candidates", oldSeedCandidates);
            LucisBenchmarkSupport.count("lucis.sky.frontier.seeds", seeds);
            LucisBenchmarkSupport.count("lucis.sky.frontier.skipped", oldSeedCandidates - seeds);
            LucisBenchmarkSupport.count("lucis.sky.spread.dequeues", dequeues);
        }
    }

    public void applyRuntimeChanges(RegionLightData data, List<RuntimeLightChange> changes) {
        RuntimeColumns columns = runtimeColumns.get();
        collectColumns(data, changes, columns);
        LucisBenchmarkSupport.count("lucis.sky.runtime.columns", columns.count);
        if (columns.count == 0) {
            return;
        }
        recomputeColumnsShallow(data, columns);
        LucisBenchmarkSupport.count("lucis.sky.runtime.shallow");
    }

    private void collectColumns(RegionLightData data, List<RuntimeLightChange> changes, RuntimeColumns columns) {
        int width = data.bounds.widthBlocks();
        int height = data.bounds.heightBlocks();
        int depth = data.bounds.depthBlocks();
        columns.reset(width * depth);
        for (RuntimeLightChange change : changes) {
            int localX = change.worldX() - data.bounds.minBlockX();
            int localY = change.worldY() - data.bounds.minBuildY();
            int localZ = change.worldZ() - data.bounds.minBlockZ();
            if (localX >= 0 && localX < width && localY >= 0 && localY < height && localZ >= 0 && localZ < depth) {
                columns.add(localX + localZ * width, localY);
            }
        }
    }

    private void recomputeColumnsShallow(RegionLightData data, RuntimeColumns columns) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int maxY = data.bounds.heightBlocks() - 1;
        for (int i = 0; i < columns.count; i++) {
            int column = columns.columns[i];
            int localZ = column / width;
            int localX = column - localZ * width;
            int base = localX + localZ * width;
            int localY = columns.maxY[column];
            if (localY > maxY) {
                localY = maxY;
            }
            int above = localY == maxY ? LucisConstants.MAX_LIGHT : (data.skyLight[base + (localY + 1) * area] & 0xF);
            updateSkyCell(data, localX, localY, localZ, base + localY * area, above);
            if (localY > 0) {
                int index = base + (localY - 1) * area;
                int vertical = data.skyLight[base + localY * area] & 0xF;
                int opacity = data.opacity[index] & 0xF;
                int verticalCandidate = opacity == 0 ? vertical : vertical - opacity;
                if (verticalCandidate < 0) {
                    verticalCandidate = 0;
                }
                int lateral = lateralCandidate(data, localX, localY - 1, localZ, index);
                setSkyCell(data, localX, localY - 1, localZ, index, verticalCandidate >= lateral ? verticalCandidate : lateral);
            }
        }
    }

    private void setSkyCell(RegionLightData data, int localX, int localY, int localZ, int index, int next) {
        if ((data.skyLight[index] & 0xF) != next) {
            data.skyLight[index] = (byte) next;
            data.markDirtySkyLocal(localX, localY, localZ);
        }
    }

    private void updateSkyCell(RegionLightData data, int localX, int localY, int localZ, int index, int incoming) {
        int opacity = data.opacity[index] & 0xF;
        int next = opacity == 0 ? incoming : incoming - opacity;
        if (next < 0) {
            next = 0;
        }
        if ((data.skyLight[index] & 0xF) != next) {
            data.skyLight[index] = (byte) next;
            data.markDirtySkyLocal(localX, localY, localZ);
        }
    }

    private int lateralCandidate(RegionLightData data, int localX, int localY, int localZ, int index) {
        int opacity = data.opacity[index] & 0xF;
        if (opacity >= LucisConstants.MAX_LIGHT) {
            return 0;
        }
        int best = neighborSky(data, localX + 1, localY, localZ, index + data.offsetPosX);
        int value = neighborSky(data, localX - 1, localY, localZ, index + data.offsetNegX);
        if (value > best) {
            best = value;
        }
        value = neighborSky(data, localX, localY, localZ + 1, index + data.offsetPosZ);
        if (value > best) {
            best = value;
        }
        value = neighborSky(data, localX, localY, localZ - 1, index + data.offsetNegZ);
        if (value > best) {
            best = value;
        }
        int candidate = best - 1 - opacity;
        return candidate > 0 ? candidate : 0;
    }

    private int neighborSky(RegionLightData data, int localX, int localY, int localZ, int index) {
        if (localX < 0 || localX >= data.bounds.widthBlocks() || localZ < 0 || localZ >= data.bounds.depthBlocks()
                || localY < 0 || localY >= data.bounds.heightBlocks()) {
            return 0;
        }
        return data.skyLight[index] & 0xF;
    }

    private int seedKnownHorizontalFrontier(RegionLightData data, IntBucketQueue queue, int current, int x, int z, int index) {
        int seeds = 0;
        boolean queuedCurrent = false;
        if (x > 0) {
            int westIndex = index + data.offsetNegX;
            int west = data.skyLight[westIndex] & 0xF;
            if (canImproveSky(data, current, westIndex)) {
                queue.enqueue(current, index);
                queuedCurrent = true;
                seeds++;
            } else if (canImproveSky(data, west, index)) {
                queue.enqueue(west, westIndex);
                seeds++;
            }
        }
        if (z > 0) {
            int northIndex = index + data.offsetNegZ;
            int north = data.skyLight[northIndex] & 0xF;
            if (canImproveSky(data, current, northIndex)) {
                if (!queuedCurrent) {
                        queue.enqueue(current, index);
                    seeds++;
                }
            } else if (canImproveSky(data, north, index)) {
                queue.enqueue(north, northIndex);
                seeds++;
            }
        }
        return seeds;
    }

    private boolean canImproveSky(RegionLightData data, int current, int nextIndex) {
        int candidate = current - 1 - (data.opacity[nextIndex] & 0xF);
        return candidate > (data.skyLight[nextIndex] & 0xF);
    }

    private int spread(RegionLightData data, IntBucketQueue queue, boolean metrics) {
        RegionBounds bounds = data.bounds;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int dequeues = 0;
        int index;
        while ((index = queue.poll()) >= 0) {
            if (metrics) {
                dequeues++;
            }
            int current = data.skyLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }

            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;

            if (x + 1 < width) {
                spreadTo(data, queue, current, x + 1, y, z, index + data.offsetPosX);
            }
            if (x > 0) {
                spreadTo(data, queue, current, x - 1, y, z, index + data.offsetNegX);
            }
            if (z + 1 < depth) {
                spreadTo(data, queue, current, x, y, z + 1, index + data.offsetPosZ);
            }
            if (z > 0) {
                spreadTo(data, queue, current, x, y, z - 1, index + data.offsetNegZ);
            }
        }
        return dequeues;
    }

    private void spreadTo(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ, int nextIndex) {
        int candidate = current - 1 - (data.opacity[nextIndex] & 0xF);
        if (candidate > (data.skyLight[nextIndex] & 0xF)) {
            data.skyLight[nextIndex] = (byte) candidate;
            data.markDirtySkyLocal(nextX, nextY, nextZ);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private static final class RuntimeColumns {
        private int[] maxY = new int[0];
        private int[] stamps = new int[0];
        private int[] columns = new int[0];
        private int stamp;
        private int count;

        private void reset(int capacity) {
            ensureCapacity(capacity);
            count = 0;
            stamp++;
            if (stamp == 0) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
        }

        private void ensureCapacity(int capacity) {
            if (maxY.length >= capacity) {
                return;
            }
            maxY = new int[capacity];
            stamps = new int[capacity];
            columns = new int[capacity];
        }

        private void add(int column, int localY) {
            if (stamps[column] != stamp) {
                stamps[column] = stamp;
                maxY[column] = localY;
                columns[count++] = column;
            } else if (localY > maxY[column]) {
                maxY[column] = localY;
            }
        }
    }
}
