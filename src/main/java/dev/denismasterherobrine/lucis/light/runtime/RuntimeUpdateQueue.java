package dev.denismasterherobrine.lucis.light.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class RuntimeUpdateQueue {
    private final ConcurrentLinkedQueue<BlockChangeRecord> pending = new ConcurrentLinkedQueue<>();

    public void enqueue(BlockChangeRecord record) {
        pending.add(record);
    }

    public void enqueueAll(Collection<BlockChangeRecord> records) {
        for (BlockChangeRecord record : records) {
            enqueue(record);
        }
    }

    public Collection<BlockChangeRecord> drain() {
        List<BlockChangeRecord> drained = new ArrayList<>();
        BlockChangeRecord record;
        while ((record = pending.poll()) != null) {
            drained.add(record);
        }
        return drained;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }
}
