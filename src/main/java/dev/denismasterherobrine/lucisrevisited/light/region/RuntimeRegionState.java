package dev.denismasterherobrine.lucisrevisited.light.region;

public final class RuntimeRegionState {
    private final RegionLightData data;
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

    public void touch() {
        lastAccessNanos = System.nanoTime();
    }

    public long lastAccessNanos() {
        return lastAccessNanos;
    }
}
