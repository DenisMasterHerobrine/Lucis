package dev.denismasterherobrine.lucis.light.region;

public final class RuntimeRegionState {
    private final RegionLightData data;
    private volatile boolean initialized;
    private volatile Object sourceIdentity;
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

    public boolean initializedFor(Object sourceIdentity) {
        return initialized && this.sourceIdentity == sourceIdentity;
    }

    public void markInitialized(Object sourceIdentity) {
        this.sourceIdentity = sourceIdentity;
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
