package dev.denismasterherobrine.lucis.light.runtime;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuntimeUpdateQueue {
    private final ConcurrentHashMap<Long, PendingRegion> pendingByRegion = new ConcurrentHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final int maxPendingRecords;
    private final int fullRelightChangeThreshold;

    public RuntimeUpdateQueue(int maxPendingRecords, int fullRelightChangeThreshold) {
        this.maxPendingRecords = Math.max(1, maxPendingRecords);
        this.fullRelightChangeThreshold = Math.max(1, fullRelightChangeThreshold);
    }

    public boolean enqueue(long regionKey, int x, int y, int z, BlockState oldState, BlockState newState) {
        int reserved = pendingCount.incrementAndGet();
        if (reserved > maxPendingRecords) {
            pendingCount.decrementAndGet();
            return false;
        }
        PendingRegion pending = pendingByRegion.computeIfAbsent(regionKey, ignored -> new PendingRegion());
        pending.enqueue(x, y, z, oldState, newState, fullRelightChangeThreshold);
        return true;
    }

    public boolean enqueue(BlockChangeRecord record) {
        if (record == null) {
            return false;
        }
        return enqueue(regionKeyForRecord(record), record.x(), record.y(), record.z(), record.oldState(), record.newState());
    }

    public int enqueueAll(Collection<BlockChangeRecord> records) {
        int accepted = 0;
        for (BlockChangeRecord record : records) {
            if (enqueue(record)) {
                accepted++;
            }
        }
        return accepted;
    }

    public void enqueueFullRelight(long regionKey, long originalChangeCount) {
        PendingRegion pending = pendingByRegion.computeIfAbsent(regionKey, ignored -> new PendingRegion());
        int queued = pending.enqueueFullRelight(originalChangeCount);
        pendingCount.addAndGet(queued);
    }

    public boolean hasFullRelight(long regionKey) {
        PendingRegion pending = pendingByRegion.get(regionKey);
        return pending != null && pending.hasFullRelight();
    }

    public int drainTo(Map<Long, RuntimeRegionBatch> drained) {
        int count = 0;
        for (Map.Entry<Long, PendingRegion> entry : pendingByRegion.entrySet()) {
            PendingRegion pending = pendingByRegion.remove(entry.getKey());
            if (pending == null) {
                continue;
            }
            RuntimeRegionBatch batch = pending.drain();
            if (batch.isEmpty()) {
                continue;
            }
            count += batch.queuedWorkCount();
            drained.merge(entry.getKey(), batch, RuntimeUpdateQueue::mergeBatches);
        }
        pendingCount.addAndGet(-count);
        return count;
    }

    public boolean isEmpty() {
        return pendingCount.get() <= 0 || pendingByRegion.isEmpty();
    }

    public boolean hasCapacity() {
        return pendingCount.get() < maxPendingRecords;
    }

    public void clear() {
        pendingByRegion.clear();
        pendingCount.set(0);
    }

    private static long regionKeyForRecord(BlockChangeRecord record) {
        return (((long) (record.x() >> 4)) << 32) ^ ((record.z() >> 4) & 0xffffffffL);
    }

    private static RuntimeRegionBatch mergeBatches(RuntimeRegionBatch first, RuntimeRegionBatch second) {
        if (first.fullRelight() || second.fullRelight()) {
            return RuntimeRegionBatch.fullRelight(first.originalChangeCount() + second.originalChangeCount());
        }
        ArrayList<BlockChangeRecord> merged = new ArrayList<>(first.queuedChangeCount() + second.queuedChangeCount());
        merged.addAll(first.changes());
        merged.addAll(second.changes());
        return RuntimeRegionBatch.incremental(merged);
    }

    private static final class PendingRegion {
        private final HashMap<Long, BlockChangeRecord> changes = new HashMap<>();
        private boolean fullRelight;
        private long originalChangeCount;

        private synchronized void enqueue(int x, int y, int z, BlockState oldState, BlockState newState, int fullRelightChangeThreshold) {
            originalChangeCount++;
            if (fullRelight) {
                return;
            }
            changes.put(blockKey(x, y, z), new BlockChangeRecord(x, y, z, oldState, newState));
            if (changes.size() >= fullRelightChangeThreshold) {
                fullRelight = true;
                changes.clear();
            }
        }

        private synchronized int enqueueFullRelight(long changes) {
            long count = Math.max(1L, changes);
            originalChangeCount += count;
            boolean wasFullRelight = fullRelight;
            int previousChanges = this.changes.size();
            fullRelight = true;
            this.changes.clear();
            return wasFullRelight ? 0 : Math.max(1, previousChanges == 0 ? 1 : previousChanges);
        }

        private synchronized boolean hasFullRelight() {
            return fullRelight;
        }

        private synchronized RuntimeRegionBatch drain() {
            if (fullRelight) {
                RuntimeRegionBatch batch = RuntimeRegionBatch.fullRelight(originalChangeCount);
                fullRelight = false;
                originalChangeCount = 0L;
                changes.clear();
                return batch;
            }
            ArrayList<BlockChangeRecord> drained = new ArrayList<>(changes.values());
            changes.clear();
            originalChangeCount = 0L;
            return RuntimeRegionBatch.incremental(drained);
        }

        private static long blockKey(int x, int y, int z) {
            return (((long) x & 0x3ffffffL) << 38) | (((long) z & 0x3ffffffL) << 12) | (y & 0xfffL);
        }
    }
}
