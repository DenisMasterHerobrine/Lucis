package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import dev.denismasterherobrine.lucis.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;

import java.util.Arrays;

public final class LucisSkyLightEngine {
    private static final int RUNTIME_REPAIR_RADIUS_XZ = Math.max(0, Integer.getInteger("lucis.sky.runtimeRepairRadius", 2));
    private static final int RUNTIME_REPAIR_RADIUS_Y = Math.max(0, Integer.getInteger("lucis.sky.runtimeRepairRadiusY", 4));
    private static final int RUNTIME_OPENING_REPAIR_RADIUS_XZ = Math.max(0,
            Integer.getInteger("lucis.sky.runtimeOpeningRepairRadius", RUNTIME_REPAIR_RADIUS_XZ));
    private static final int RUNTIME_FULL_SKY_RECOMPUTE_COLUMN_PERCENT = Math.max(0,
            Math.min(Integer.getInteger("lucis.sky.runtimeFullRecomputeColumnPercent", 75), 100));

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

        Arrays.fill(data.skyLight, (byte) 0);
        for (int z = 0; z < depth; z++) {
            int columnZBase = z * width;
            for (int x = 0; x < width; x++) {
                int columnBase = columnZBase + x;
                for (int y = height - 1; y >= 0; y--) {
                    int index = columnBase + y * area;
                    int opacity = data.opacity[index] & 0xF;
                    int light;
                    if (opacity == 0) {
                        light = LucisConstants.MAX_LIGHT;
                    } else {
                        light = propagatedCandidate(LucisConstants.MAX_LIGHT, opacity);
                    }
                    data.skyLight[index] = (byte) light;
                    if (metrics && light > 1) {
                        oldSeedCandidates++;
                    }
                    if (opacity != 0) {
                        break;
                    }
                }
            }
        }

        data.markCoreSkySectionsDirty();
        seeds = seedFrontiers(data, queue, metrics);
        int dequeues = spread(data, queue, metrics);
        if (metrics) {
            LucisBenchmarkSupport.count("lucis.sky.old_seed_candidates", oldSeedCandidates);
            LucisBenchmarkSupport.count("lucis.sky.frontier.seeds", seeds);
            LucisBenchmarkSupport.count("lucis.sky.frontier.skipped", oldSeedCandidates - seeds);
            LucisBenchmarkSupport.count("lucis.sky.spread.dequeues", dequeues);
        }
    }

    public void applyRuntimeChanges(RegionLightData data, RuntimeLightChangeBuffer changes) {
        RuntimeColumns columns = runtimeColumns.get();
        collectColumns(data, changes, columns);
        LucisBenchmarkSupport.count("lucis.sky.runtime.columns", columns.count);
        if (columns.count == 0) {
            return;
        }
        if (shouldPromoteToFullSkyRecompute(data, columns)) {
            LucisBenchmarkSupport.count("lucis.sky.runtime.promoted_full_recompute");
            compute(data);
            return;
        }
        recomputeColumnsShallow(data, columns);
        LucisBenchmarkSupport.count("lucis.sky.runtime.shallow");
        repairRuntimeArea(data, columns);
    }

    private boolean shouldPromoteToFullSkyRecompute(RegionLightData data, RuntimeColumns columns) {
        if (RUNTIME_FULL_SKY_RECOMPUTE_COLUMN_PERCENT <= 0 || !columns.opaqueSkyCutAdded || columns.openedSkyPath) {
            return false;
        }
        int totalColumns = data.bounds.widthBlocks() * data.bounds.depthBlocks();
        return columns.count * 100 >= totalColumns * RUNTIME_FULL_SKY_RECOMPUTE_COLUMN_PERCENT;
    }

    private void collectColumns(RegionLightData data, RuntimeLightChangeBuffer changes, RuntimeColumns columns) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        columns.reset(width * data.bounds.depthBlocks());
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            if (!RuntimeLightChangeBuffer.hasSkyChange(change)) {
                continue;
            }
            int index = RuntimeLightChangeBuffer.localIndex(change);
            int localY = index / area;
            int column = index - localY * area;
            if (RuntimeLightChangeBuffer.hasSkyMaterialChange(change)) {
                int localZ = column / width;
                int localX = column - localZ * width;
                data.markDirtySkyLocal(localX, localY, localZ);
            }
            columns.add(column, localY, requiresFullDepthRepair(data, change, index, localY),
                    RuntimeLightChangeBuffer.oldOpacity(change), RuntimeLightChangeBuffer.newOpacity(change));
        }
    }

    private boolean requiresFullDepthRepair(RegionLightData data, long change, int index, int localY) {
        if (RuntimeLightChangeBuffer.oldOpacity(change) == RuntimeLightChangeBuffer.newOpacity(change)) {
            return false;
        }
        if ((data.skyLight[index] & 0xF) != 0) {
            return true;
        }
        return localY + 1 >= data.bounds.heightBlocks()
                || (data.skyLight[index + data.offsetPosY] & 0xF) != 0;
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
                int verticalCandidate = verticalCandidate(vertical, opacity);
                int lateral = lateralCandidate(data, localX, localY - 1, localZ, index);
                setSkyCell(data, localX, localY - 1, localZ, index, verticalCandidate >= lateral ? verticalCandidate : lateral);
            }
        }
    }

    private void repairRuntimeArea(RegionLightData data, RuntimeColumns columns) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        int minX = width;
        int minY = height;
        int minZ = depth;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;

        for (int i = 0; i < columns.count; i++) {
            int column = columns.columns[i];
            int localZ = column / width;
            int localX = column - localZ * width;
            int localMinY = columns.minY[column];
            int localMaxY = columns.maxY[column];
            if (localX < minX) minX = localX;
            if (localX > maxX) maxX = localX;
            if (localZ < minZ) minZ = localZ;
            if (localZ > maxZ) maxZ = localZ;
            if (localMinY < minY) minY = localMinY;
            if (localMaxY > maxY) maxY = localMaxY;
        }
        if (maxX < 0) {
            return;
        }

        int changedMinY = minY;
        int changedMaxY = maxY;
        boolean useRepairMask = false;
        boolean seedLateral = !columns.fullDepthRepair;
        if (columns.fullDepthRepair) {
            if (columns.opaqueSkyCutAdded && !columns.openedSkyPath) {
                columns.beginRepairMask(width * depth);
                markFullDepthSkyCutColumns(data, columns, changedMinY, changedMaxY);
                if (columns.repairCount > 0) {
                    minX = columns.repairMinX;
                    maxX = columns.repairMaxX;
                    minZ = columns.repairMinZ;
                    maxZ = columns.repairMaxZ;
                    useRepairMask = true;
                }
            } else if (columns.openedSkyPath) {
                minX = Math.max(0, minX - RUNTIME_OPENING_REPAIR_RADIUS_XZ);
                maxX = Math.min(width - 1, maxX + RUNTIME_OPENING_REPAIR_RADIUS_XZ);
                minZ = Math.max(0, minZ - RUNTIME_OPENING_REPAIR_RADIUS_XZ);
                maxZ = Math.min(depth - 1, maxZ + RUNTIME_OPENING_REPAIR_RADIUS_XZ);
                seedLateral = true;
            }
        } else {
            minX = Math.max(0, minX - RUNTIME_REPAIR_RADIUS_XZ);
            maxX = Math.min(width - 1, maxX + RUNTIME_REPAIR_RADIUS_XZ);
            minZ = Math.max(0, minZ - RUNTIME_REPAIR_RADIUS_XZ);
            maxZ = Math.min(depth - 1, maxZ + RUNTIME_REPAIR_RADIUS_XZ);
        }
        minY = columns.fullDepthRepair ? 0 : Math.max(0, minY - RUNTIME_REPAIR_RADIUS_Y);
        maxY = Math.min(height - 1, maxY + RUNTIME_REPAIR_RADIUS_Y);

        int cells = (useRepairMask ? columns.repairCount : (maxX - minX + 1) * (maxZ - minZ + 1))
                * (maxY - minY + 1);
        LucisBenchmarkSupport.count("lucis.sky.runtime.repair.cells", cells);
        int dirtyBefore = columns.fullDepthRepair ? data.dirtySkySections.cardinality() : 0;

        if (useRepairMask) {
            clearRepairColumns(data, columns, minY, maxY);
        } else {
            clearRepairBox(data, minX, maxX, minY, maxY, minZ, maxZ);
        }

        if (columns.fullDepthRepair) {
            LucisBenchmarkSupport.count("lucis.sky.runtime.full_depth_dirty_sections",
                    data.dirtySkySections.cardinality() - dirtyBefore);
        }

        IntBucketQueue queue = queues.get();
        queue.clear();
        if (useRepairMask) {
            seedRepairColumns(data, queue, columns, minY, maxY);
            seedRepairCells(data, queue, columns, minY, maxY);
            spreadRepair(data, queue, minX, maxX, minY, maxY, minZ, maxZ, columns);
        } else {
            seedRepairColumns(data, queue, minX, maxX, minY, maxY, minZ, maxZ);
            seedRepairOutside(data, minX, maxX, minY, maxY, minZ, maxZ, seedLateral);
            seedRepairCells(data, queue, minX, maxX, minY, maxY, minZ, maxZ);
            spreadRepair(data, queue, minX, maxX, minY, maxY, minZ, maxZ, null);
        }
        LucisBenchmarkSupport.count("lucis.sky.runtime.repair");
    }

    private void markFullDepthSkyCutColumns(RegionLightData data, RuntimeColumns columns, int minY, int maxY) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int area = data.bounds.area();
        for (int z = 0; z < depth; z++) {
            int rowBase = z * width;
            for (int x = 0; x < width; x++) {
                int columnBase = rowBase + x;
                if (hasFullDepthSkyCutBarrier(data, columnBase, area, minY, maxY)) {
                    columns.markRepairColumn(columnBase, x, z);
                }
            }
        }
    }

    private boolean hasFullDepthSkyCutBarrier(RegionLightData data, int columnBase, int area, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            if ((data.opacity[columnBase + y * area] & 0xF) >= LucisConstants.MAX_LIGHT) {
                return true;
            }
        }
        return false;
    }

    private void clearRepairBox(RegionLightData data, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        for (int y = minY; y <= maxY; y++) {
            int yBase = y * area;
            for (int z = minZ; z <= maxZ; z++) {
                int index = yBase + z * width + minX;
                for (int x = minX; x <= maxX; x++, index++) {
                    if ((data.skyLight[index] & 0xF) != 0) {
                        data.skyLight[index] = 0;
                        data.markDirtySkyLocal(x, y, z);
                    }
                }
            }
        }
    }

    private void clearRepairColumns(RegionLightData data, RuntimeColumns columns, int minY, int maxY) {
        int area = data.bounds.area();
        for (int i = 0; i < columns.repairCount; i++) {
            int column = columns.repairColumns[i];
            int x = columns.repairLocalX[i];
            int z = columns.repairLocalZ[i];
            for (int y = minY, index = column + minY * area; y <= maxY; y++, index += area) {
                if ((data.skyLight[index] & 0xF) != 0) {
                    data.skyLight[index] = 0;
                    data.markDirtySkyLocal(x, y, z);
                }
            }
        }
    }

    private void seedRepairColumns(RegionLightData data, IntBucketQueue queue, int minX, int maxX, int minY, int maxY,
                                   int minZ, int maxZ) {
        int height = data.bounds.heightBlocks();
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int columnBase = x + z * width;
                int incoming = maxY + 1 >= height ? LucisConstants.MAX_LIGHT
                        : (data.skyLight[columnBase + (maxY + 1) * area] & 0xF);
                for (int y = maxY, index = columnBase + maxY * area; y >= minY; y--, index -= area) {
                    int opacity = data.opacity[index] & 0xF;
                    int next = verticalCandidate(incoming, opacity);
                    setSkyCell(data, x, y, z, index, next);
                    incoming = next;
                    if (incoming <= 0) {
                        break;
                    }
                }
            }
        }
    }

    private void seedRepairColumns(RegionLightData data, IntBucketQueue queue, RuntimeColumns columns, int minY, int maxY) {
        int height = data.bounds.heightBlocks();
        int area = data.bounds.area();
        for (int i = 0; i < columns.repairCount; i++) {
            int column = columns.repairColumns[i];
            int x = columns.repairLocalX[i];
            int z = columns.repairLocalZ[i];
            int incoming = maxY + 1 >= height ? LucisConstants.MAX_LIGHT
                    : (data.skyLight[column + (maxY + 1) * area] & 0xF);
            for (int y = maxY, index = column + maxY * area; y >= minY; y--, index -= area) {
                int opacity = data.opacity[index] & 0xF;
                int next = verticalCandidate(incoming, opacity);
                setSkyCell(data, x, y, z, index, next);
                incoming = next;
                if (incoming <= 0) {
                    break;
                }
            }
        }
    }

    private void seedRepairOutside(RegionLightData data, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                   boolean seedLateral) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        if (seedLateral) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (minX > 0) {
                        seedRepairFromNeighbor(data, minX, y, z, data.localIndex(minX, y, z), data.offsetNegX);
                    }
                    if (maxX + 1 < width) {
                        seedRepairFromNeighbor(data, maxX, y, z, data.localIndex(maxX, y, z), data.offsetPosX);
                    }
                }
                for (int x = minX; x <= maxX; x++) {
                    if (minZ > 0) {
                        seedRepairFromNeighbor(data, x, y, minZ, data.localIndex(x, y, minZ), data.offsetNegZ);
                    }
                    if (maxZ + 1 < depth) {
                        seedRepairFromNeighbor(data, x, y, maxZ, data.localIndex(x, y, maxZ), data.offsetPosZ);
                    }
                }
            }
        }
        if (minY > 0) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    seedRepairFromNeighbor(data, x, minY, z, data.localIndex(x, minY, z), data.offsetNegY);
                }
            }
        }
        if (maxY + 1 < height) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    seedRepairFromAbove(data, x, maxY, z, data.localIndex(x, maxY, z));
                }
            }
        }
    }

    private void seedRepairFromNeighbor(RegionLightData data, int x, int y, int z, int index, int neighborOffset) {
        int neighbor = data.skyLight[index + neighborOffset] & 0xF;
        int candidate = propagatedCandidate(neighbor, data.opacity[index] & 0xF);
        if (candidate > (data.skyLight[index] & 0xF)) {
            data.skyLight[index] = (byte) candidate;
            data.markDirtySkyLocal(x, y, z);
        }
    }

    private void seedRepairFromAbove(RegionLightData data, int x, int y, int z, int index) {
        int incoming = data.skyLight[index + data.offsetPosY] & 0xF;
        int opacity = data.opacity[index] & 0xF;
        int candidate = verticalCandidate(incoming, opacity);
        if (candidate > (data.skyLight[index] & 0xF)) {
            data.skyLight[index] = (byte) candidate;
            data.markDirtySkyLocal(x, y, z);
        }
    }

    private void seedRepairCells(RegionLightData data, IntBucketQueue queue, int minX, int maxX, int minY, int maxY,
                                 int minZ, int maxZ) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        for (int y = minY; y <= maxY; y++) {
            int yBase = y * area;
            for (int z = minZ; z <= maxZ; z++) {
                int index = yBase + z * width + minX;
                for (int x = minX; x <= maxX; x++, index++) {
                    seedRepairCell(data, queue, index);
                }
            }
        }
    }

    private void seedRepairCells(RegionLightData data, IntBucketQueue queue, RuntimeColumns columns, int minY, int maxY) {
        int area = data.bounds.area();
        for (int i = 0; i < columns.repairCount; i++) {
            int column = columns.repairColumns[i];
            for (int y = minY, index = column + minY * area; y <= maxY; y++, index += area) {
                seedRepairCell(data, queue, index);
            }
        }
    }

    private void seedRepairCell(RegionLightData data, IntBucketQueue queue, int index) {
        int current = data.skyLight[index] & 0xF;
        if (current > 1) {
            queue.enqueue(current, index);
        }
    }

    private void spreadRepair(RegionLightData data, IntBucketQueue queue, int minX, int maxX, int minY, int maxY,
                              int minZ, int maxZ, RuntimeColumns repairMask) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int index;
        while ((index = queue.poll()) >= 0) {
            int current = data.skyLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }
            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;

            if (x + 1 <= maxX) {
                spreadRepairTo(data, queue, current, x + 1, y, z, index + data.offsetPosX, repairMask);
            }
            if (x - 1 >= minX) {
                spreadRepairTo(data, queue, current, x - 1, y, z, index + data.offsetNegX, repairMask);
            }
            if (z + 1 <= maxZ) {
                spreadRepairTo(data, queue, current, x, y, z + 1, index + data.offsetPosZ, repairMask);
            }
            if (z - 1 >= minZ) {
                spreadRepairTo(data, queue, current, x, y, z - 1, index + data.offsetNegZ, repairMask);
            }
            if (y + 1 <= maxY) {
                spreadRepairTo(data, queue, current, x, y + 1, z, index + data.offsetPosY, repairMask);
            }
            if (y - 1 >= minY) {
                spreadRepairTo(data, queue, current, x, y - 1, z, index + data.offsetNegY, repairMask);
            }
        }
    }

    private void spreadRepairTo(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ,
                                int nextIndex, RuntimeColumns repairMask) {
        if (repairMask != null && !repairMask.isRepairColumn(nextX + nextZ * data.bounds.widthBlocks())) {
            return;
        }
        spreadTo(data, queue, current, nextX, nextY, nextZ, nextIndex);
    }

    private void setSkyCell(RegionLightData data, int localX, int localY, int localZ, int index, int next) {
        if ((data.skyLight[index] & 0xF) != next) {
            data.skyLight[index] = (byte) next;
            data.markDirtySkyLocal(localX, localY, localZ);
        }
    }

    private void updateSkyCell(RegionLightData data, int localX, int localY, int localZ, int index, int incoming) {
        int opacity = data.opacity[index] & 0xF;
        int next = verticalCandidate(incoming, opacity);
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
        return propagatedCandidate(best, opacity);
    }

    private int neighborSky(RegionLightData data, int localX, int localY, int localZ, int index) {
        if (localX < 0 || localX >= data.bounds.widthBlocks() || localZ < 0 || localZ >= data.bounds.depthBlocks()
                || localY < 0 || localY >= data.bounds.heightBlocks()) {
            return 0;
        }
        return data.skyLight[index] & 0xF;
    }

    private int seedFrontiers(RegionLightData data, IntBucketQueue queue, boolean metrics) {
        RegionBounds bounds = data.bounds;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int height = bounds.heightBlocks();
        int seeds = 0;
        for (int y = 0; y < height; y++) {
            int yBase = y * area;
            for (int z = 0; z < depth; z++) {
                int rowBase = yBase + z * width;
                for (int x = 0; x < width; x++) {
                    int index = rowBase + x;
                    int current = data.skyLight[index] & 0xF;
                    if (current > 1) {
                        seeds += seedKnownFrontier(data, queue, current, x, y, z, index);
                    }
                }
            }
        }
        return seeds;
    }

    private int seedKnownFrontier(RegionLightData data, IntBucketQueue queue, int current, int x, int y, int z, int index) {
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
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, north, index)) {
                queue.enqueue(north, northIndex);
                seeds++;
            }
        }
        if (x + 1 < data.bounds.widthBlocks()) {
            int eastIndex = index + data.offsetPosX;
            int east = data.skyLight[eastIndex] & 0xF;
            if (canImproveSky(data, current, eastIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, east, index)) {
                queue.enqueue(east, eastIndex);
                seeds++;
            }
        }
        if (z + 1 < data.bounds.depthBlocks()) {
            int southIndex = index + data.offsetPosZ;
            int south = data.skyLight[southIndex] & 0xF;
            if (canImproveSky(data, current, southIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, south, index)) {
                queue.enqueue(south, southIndex);
                seeds++;
            }
        }
        if (y > 0) {
            int downIndex = index + data.offsetNegY;
            int down = data.skyLight[downIndex] & 0xF;
            if (canImproveSky(data, current, downIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, down, index)) {
                queue.enqueue(down, downIndex);
                seeds++;
            }
        }
        if (y + 1 < data.bounds.heightBlocks()) {
            int upIndex = index + data.offsetPosY;
            int up = data.skyLight[upIndex] & 0xF;
            if (canImproveSky(data, current, upIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    seeds++;
                }
            } else if (canImproveSky(data, up, index)) {
                queue.enqueue(up, upIndex);
                seeds++;
            }
        }
        return seeds;
    }

    private boolean canImproveSky(RegionLightData data, int current, int nextIndex) {
        int candidate = propagatedCandidate(current, data.opacity[nextIndex] & 0xF);
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
            if (y + 1 < bounds.heightBlocks()) {
                spreadTo(data, queue, current, x, y + 1, z, index + data.offsetPosY);
            }
            if (y > 0) {
                spreadTo(data, queue, current, x, y - 1, z, index + data.offsetNegY);
            }
        }
        return dequeues;
    }

    private void spreadTo(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ, int nextIndex) {
        int candidate = propagatedCandidate(current, data.opacity[nextIndex] & 0xF);
        if (candidate > (data.skyLight[nextIndex] & 0xF)) {
            data.skyLight[nextIndex] = (byte) candidate;
            data.markDirtySkyLocal(nextX, nextY, nextZ);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private static int verticalCandidate(int incoming, int opacity) {
        if (incoming <= 0) {
            return 0;
        }
        if (incoming == LucisConstants.MAX_LIGHT && opacity == 0) {
            return LucisConstants.MAX_LIGHT;
        }
        return propagatedCandidate(incoming, opacity);
    }

    private static int propagatedCandidate(int incoming, int opacity) {
        int candidate = incoming - propagationCost(opacity);
        return candidate > 0 ? candidate : 0;
    }

    private static int propagationCost(int opacity) {
        return opacity <= 0 ? 1 : opacity;
    }

    private static final class RuntimeColumns {
        private int[] maxY = new int[0];
        private int[] minY = new int[0];
        private int[] stamps = new int[0];
        private int[] columns = new int[0];
        private int[] repairStamps = new int[0];
        private int[] repairColumns = new int[0];
        private int[] repairLocalX = new int[0];
        private int[] repairLocalZ = new int[0];
        private int stamp;
        private int repairStamp;
        private int count;
        private int repairCount;
        private int repairMinX;
        private int repairMinZ;
        private int repairMaxX;
        private int repairMaxZ;
        private boolean fullDepthRepair;
        private boolean opaqueSkyCutAdded;
        private boolean openedSkyPath;

        private void reset(int capacity) {
            ensureCapacity(capacity);
            count = 0;
            fullDepthRepair = false;
            opaqueSkyCutAdded = false;
            openedSkyPath = false;
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
            minY = new int[capacity];
            stamps = new int[capacity];
            columns = new int[capacity];
            repairStamps = new int[capacity];
            repairColumns = new int[capacity];
            repairLocalX = new int[capacity];
            repairLocalZ = new int[capacity];
        }

        private void add(int column, int localY, boolean fullDepthRepair, int oldOpacity, int newOpacity) {
            if (fullDepthRepair) {
                this.fullDepthRepair = true;
                if (oldOpacity < LucisConstants.MAX_LIGHT && newOpacity >= LucisConstants.MAX_LIGHT) {
                    opaqueSkyCutAdded = true;
                }
                if (newOpacity < oldOpacity) {
                    openedSkyPath = true;
                }
            }
            if (stamps[column] != stamp) {
                stamps[column] = stamp;
                maxY[column] = localY;
                minY[column] = localY;
                columns[count++] = column;
            } else if (localY > maxY[column]) {
                maxY[column] = localY;
            } else if (localY < minY[column]) {
                minY[column] = localY;
            }
        }

        private void beginRepairMask(int capacity) {
            ensureCapacity(capacity);
            repairCount = 0;
            repairMinX = Integer.MAX_VALUE;
            repairMinZ = Integer.MAX_VALUE;
            repairMaxX = -1;
            repairMaxZ = -1;
            repairStamp++;
            if (repairStamp == 0) {
                Arrays.fill(repairStamps, 0);
                repairStamp = 1;
            }
        }

        private void markRepairColumn(int column, int localX, int localZ) {
            if (repairStamps[column] == repairStamp) {
                return;
            }
            repairStamps[column] = repairStamp;
            repairColumns[repairCount] = column;
            repairLocalX[repairCount] = localX;
            repairLocalZ[repairCount] = localZ;
            repairCount++;
            if (localX < repairMinX) repairMinX = localX;
            if (localX > repairMaxX) repairMaxX = localX;
            if (localZ < repairMinZ) repairMinZ = localZ;
            if (localZ > repairMaxZ) repairMaxZ = localZ;
        }

        private boolean isRepairColumn(int column) {
            return repairStamps[column] == repairStamp;
        }
    }
}
