package dev.denismasterherobrine.lucisrevisited.light.region;

import java.util.EnumMap;
import java.util.List;

public final class RuntimeRegionState {
    private final RegionLightData data;
    private final EnumMap<RegionSide, BoundarySnapshot> pendingBoundarySnapshots = new EnumMap<>(RegionSide.class);
    private volatile boolean initialized;
    private volatile long lastAccessNanos;

    public RuntimeRegionState(RegionLightData data) {
        this.data = data;
        touch();
    }

    public RegionLightData data() {
        return data;
    }

    public boolean initialized() {
        return initialized;
    }

    public void markInitialized() {
        initialized = true;
        touch();
    }

    public void putBoundarySnapshot(BoundarySnapshot snapshot) {
        pendingBoundarySnapshots.put(snapshot.side(), snapshot);
    }

    public BoundarySnapshot getBoundarySnapshot(RegionSide side) {
        touch();
        return pendingBoundarySnapshots.get(side);
    }

    public List<BoundarySnapshot> drainBoundarySnapshots() {
        touch();
        List<BoundarySnapshot> drained = List.copyOf(pendingBoundarySnapshots.values());
        pendingBoundarySnapshots.clear();
        return drained;
    }

    public void touch() {
        lastAccessNanos = System.nanoTime();
    }

    public long lastAccessNanos() {
        return lastAccessNanos;
    }
}
