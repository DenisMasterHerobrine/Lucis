package dev.denismasterherobrine.lucis.light.region;

import java.util.concurrent.ConcurrentHashMap;

public final class OwnedRegionCache {
    private final ConcurrentHashMap<Long, RuntimeRegionState> cache = new ConcurrentHashMap<>();

    public RuntimeRegionState getOrCreate(RegionBounds bounds) {
        RuntimeRegionState state = cache.compute(bounds.coreRegionKey(), (key, existing) -> {
            if (existing == null) {
                return new RuntimeRegionState(new RegionLightData(bounds));
            }
            if (!sameShape(existing.data().bounds, bounds)) {
                return new RuntimeRegionState(new RegionLightData(bounds));
            }
            existing.touch();
            return existing;
        });
        state.touch();
        return state;
    }

    public RuntimeRegionState getInitialized(long regionKey) {
        RuntimeRegionState state = cache.get(regionKey);
        if (state != null) {
            state.touch();
        }
        return state != null && state.initialized() ? state : null;
    }

    public void trimToSize(int maxEntries) {
        if (maxEntries <= 0 || cache.size() <= maxEntries) {
            return;
        }

        cache.entrySet().stream()
                .map(entry -> new TrimCandidate(entry.getKey(), entry.getValue(), entry.getValue().lastAccessNanos()))
                .sorted((left, right) -> Long.compare(left.lastAccessNanos(), right.lastAccessNanos()))
                .limit(Math.max(0, cache.size() - maxEntries))
                .forEach(candidate -> cache.remove(candidate.regionKey(), candidate.state()));
    }

    public int size() {
        return cache.size();
    }

    private boolean sameShape(RegionBounds left, RegionBounds right) {
        return left.widthBlocks() == right.widthBlocks()
                && left.depthBlocks() == right.depthBlocks()
                && left.minBuildY() == right.minBuildY()
                && left.maxBuildY() == right.maxBuildY()
                && left.regionChunks() == right.regionChunks()
                && left.haloChunks() == right.haloChunks();
    }

    private record TrimCandidate(long regionKey, RuntimeRegionState state, long lastAccessNanos) {
    }
}
