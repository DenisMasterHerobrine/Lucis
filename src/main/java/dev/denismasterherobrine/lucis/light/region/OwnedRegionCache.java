package dev.denismasterherobrine.lucis.light.region;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class OwnedRegionCache {
    private static final Comparator<TrimCandidate> NEWEST_FIRST =
            Comparator.comparingLong(TrimCandidate::lastAccessNanos).reversed();

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
        int currentSize = cache.size();
        if (maxEntries <= 0 || currentSize <= maxEntries) {
            return;
        }

        int removeCount = currentSize - maxEntries;
        if (removeCount == 1) {
            removeOldestEntry();
            return;
        }

        PriorityQueue<TrimCandidate> oldestEntries = new PriorityQueue<>(removeCount, NEWEST_FIRST);
        Iterator<Map.Entry<Long, RuntimeRegionState>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Long, RuntimeRegionState> entry = entries.next();
            RuntimeRegionState state = entry.getValue();
            long lastAccessNanos = state.lastAccessNanos();
            if (oldestEntries.size() < removeCount) {
                oldestEntries.add(new TrimCandidate(entry.getKey(), state, lastAccessNanos));
                continue;
            }
            TrimCandidate newestOldEntry = oldestEntries.peek();
            if (newestOldEntry != null && lastAccessNanos < newestOldEntry.lastAccessNanos()) {
                oldestEntries.poll();
                oldestEntries.add(new TrimCandidate(entry.getKey(), state, lastAccessNanos));
            }
        }
        while (!oldestEntries.isEmpty()) {
            TrimCandidate candidate = oldestEntries.poll();
            cache.remove(candidate.regionKey(), candidate.state());
        }
    }

    private void removeOldestEntry() {
        long oldestKey = 0L;
        long oldestAccessNanos = Long.MAX_VALUE;
        RuntimeRegionState oldestState = null;
        Iterator<Map.Entry<Long, RuntimeRegionState>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Long, RuntimeRegionState> entry = entries.next();
            RuntimeRegionState state = entry.getValue();
            long lastAccessNanos = state.lastAccessNanos();
            if (lastAccessNanos < oldestAccessNanos) {
                oldestKey = entry.getKey();
                oldestState = state;
                oldestAccessNanos = lastAccessNanos;
            }
        }
        if (oldestState != null) {
            cache.remove(oldestKey, oldestState);
        }
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
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
