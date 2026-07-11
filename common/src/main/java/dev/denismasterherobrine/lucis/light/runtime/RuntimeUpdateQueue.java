package dev.denismasterherobrine.lucis.light.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.state.BlockState;

public final class RuntimeUpdateQueue {
    private final Object lock = new Object();
    private final HashMap<Long, HashMap<Long, BlockChangeRecord>> pendingByRegion = new HashMap<>();
    private final HashMap<Long, Long> fullRelightCountsByRegion = new HashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final int maxPendingRecords;
    private final int fullRelightChangeThreshold;

    public RuntimeUpdateQueue(int maxPendingRecords, int fullRelightChangeThreshold) {
        this.maxPendingRecords = Math.max(1, maxPendingRecords);
        this.fullRelightChangeThreshold = Math.max(1, fullRelightChangeThreshold);
    }

    public boolean enqueue(long regionKey, BlockChangeRecord record) {
        if (record == null) {
            return false;
        }
        return enqueue(regionKey, record.x(), record.y(), record.z(), record.oldState(), record.newState());
    }

    public boolean enqueue(long regionKey, int x, int y, int z, BlockState oldState, BlockState newState) {
        synchronized (lock) {
            Long fullRelightCount = fullRelightCountsByRegion.get(regionKey);
            if (fullRelightCount != null) {
                fullRelightCountsByRegion.put(regionKey, fullRelightCount + 1L);
                return true;
            }

            long key = blockKey(x, y, z);
            HashMap<Long, BlockChangeRecord> regionChanges = pendingByRegion.computeIfAbsent(regionKey, ignored -> new HashMap<>());
            BlockChangeRecord existing = regionChanges.get(key);
            if (existing != null) {
                regionChanges.put(key, new BlockChangeRecord(x, y, z, existing.oldState(), newState));
                return true;
            }

            if (regionChanges.size() + 1 >= fullRelightChangeThreshold || pendingCount.get() >= maxPendingRecords) {
                promoteToFullRelight(regionKey, regionChanges.size() + 1);
                return true;
            }

            int reserved = pendingCount.incrementAndGet();
            if (reserved > maxPendingRecords) {
                pendingCount.decrementAndGet();
                promoteToFullRelight(regionKey, regionChanges.size() + 1);
                return true;
            }
            regionChanges.put(key, new BlockChangeRecord(x, y, z, oldState, newState));
            return true;
        }
    }

    public int enqueueAll(long regionKey, Collection<BlockChangeRecord> records) {
        int accepted = 0;
        for (BlockChangeRecord record : records) {
            if (enqueue(regionKey, record)) {
                accepted++;
            }
        }
        return accepted;
    }

    public int drainTo(Map<Long, RuntimeRegionBatch> drained) {
        synchronized (lock) {
            int pendingRecords = pendingCount.get();
            int queuedWork = pendingRecords + fullRelightCountsByRegion.size();
            for (Map.Entry<Long, Long> entry : fullRelightCountsByRegion.entrySet()) {
                drained.put(entry.getKey(), RuntimeRegionBatch.fullRelight(entry.getValue()));
            }
            for (Map.Entry<Long, HashMap<Long, BlockChangeRecord>> entry : pendingByRegion.entrySet()) {
                if (fullRelightCountsByRegion.containsKey(entry.getKey())) {
                    continue;
                }
                drained.put(entry.getKey(), RuntimeRegionBatch.incremental(new ArrayList<>(entry.getValue().values())));
            }
            pendingByRegion.clear();
            fullRelightCountsByRegion.clear();
            pendingCount.addAndGet(-pendingRecords);
            return queuedWork;
        }
    }

    public void enqueueFullRelight(long regionKey, long originalChangeCount) {
        synchronized (lock) {
            promoteToFullRelight(regionKey, Math.max(1, originalChangeCount));
        }
    }

    public boolean hasFullRelight(long regionKey) {
        synchronized (lock) {
            return fullRelightCountsByRegion.containsKey(regionKey);
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return pendingCount.get() == 0 && fullRelightCountsByRegion.isEmpty();
        }
    }

    public boolean hasCapacity() {
        return pendingCount.get() < maxPendingRecords;
    }

    public void clear() {
        synchronized (lock) {
            pendingByRegion.clear();
            fullRelightCountsByRegion.clear();
            pendingCount.set(0);
        }
    }

    private void promoteToFullRelight(long regionKey, long originalChangeCount) {
        HashMap<Long, BlockChangeRecord> regionChanges = pendingByRegion.remove(regionKey);
        int existingCount = regionChanges == null ? 0 : regionChanges.size();
        if (existingCount > 0) {
            pendingCount.addAndGet(-existingCount);
        }
        fullRelightCountsByRegion.merge(regionKey, Math.max(originalChangeCount, existingCount), Long::sum);
    }

    private long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }
}
