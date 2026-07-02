package dev.denismasterherobrine.lucis.light.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuntimeUpdateQueue {
    private final ConcurrentLinkedQueue<BlockChangeRecord> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final int maxPendingRecords;

    public RuntimeUpdateQueue(int maxPendingRecords) {
        this.maxPendingRecords = Math.max(1, maxPendingRecords);
    }

    public boolean enqueue(BlockChangeRecord record) {
        if (record == null) {
            return false;
        }
        int reserved = pendingCount.incrementAndGet();
        if (reserved > maxPendingRecords) {
            pendingCount.decrementAndGet();
            return false;
        }
        pending.add(record);
        return true;
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

    public Collection<BlockChangeRecord> drain() {
        List<BlockChangeRecord> drained = new ArrayList<>();
        BlockChangeRecord record;
        while ((record = pending.poll()) != null) {
            drained.add(record);
            pendingCount.decrementAndGet();
        }
        return drained;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public boolean hasCapacity() {
        return pendingCount.get() < maxPendingRecords;
    }

    public void clear() {
        pending.clear();
        pendingCount.set(0);
    }
}
