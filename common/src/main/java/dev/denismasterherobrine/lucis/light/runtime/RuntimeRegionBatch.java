package dev.denismasterherobrine.lucis.light.runtime;

import java.util.List;

public record RuntimeRegionBatch(List<BlockChangeRecord> changes, boolean fullRelight, long originalChangeCount) {
    public static RuntimeRegionBatch incremental(List<BlockChangeRecord> changes) {
        return new RuntimeRegionBatch(changes, false, changes == null ? 0 : changes.size());
    }

    public static RuntimeRegionBatch fullRelight(long originalChangeCount) {
        return new RuntimeRegionBatch(List.of(), true, originalChangeCount);
    }

    public int queuedChangeCount() {
        return changes == null ? 0 : changes.size();
    }

    public int queuedWorkCount() {
        return fullRelight ? 1 : queuedChangeCount();
    }

    public boolean isEmpty() {
        return !fullRelight && queuedChangeCount() == 0;
    }
}
